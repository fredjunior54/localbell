use crate::state::State;
use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, State as Extract},
    http::{HeaderMap, StatusCode},
    routing::{get, post},
};
use serde::Deserialize;
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    sync::{Arc, Mutex},
    time::Duration,
};
use tower_http::timeout::TimeoutLayer;

type Response = Result<Json<Value>, StatusCode>;
pub trait Sink: Send {
    fn send(&mut self, notification: &Notification, previous: Option<u32>) -> anyhow::Result<u32>;
    fn dismiss(&mut self, id: u32) -> anyhow::Result<()>;
}

pub struct DesktopSink;
impl Sink for DesktopSink {
    fn send(&mut self, n: &Notification, previous: Option<u32>) -> anyhow::Result<u32> {
        use notify_rust::{Notification, Timeout, Urgency};
        let mut item = Notification::new();
        item.appname(&n.app_name)
            .summary(&n.title)
            .body(&n.message)
            .icon("dialog-information")
            .urgency(match n.urgency {
                0 => Urgency::Low,
                2 => Urgency::Critical,
                _ => Urgency::Normal,
            })
            .timeout(match n.timeout_ms {
                0 => Timeout::Never,
                ms if ms > 0 => Timeout::Milliseconds(ms as u32),
                _ => Timeout::Default,
            });
        if let Some(id) = previous {
            item.id(id);
        }
        Ok(item.show()?.id())
    }
    fn dismiss(&mut self, id: u32) -> anyhow::Result<()> {
        let bus = zbus::blocking::Connection::session()?;
        let proxy = zbus::blocking::Proxy::new(
            &bus,
            "org.freedesktop.Notifications",
            "/org/freedesktop/Notifications",
            "org.freedesktop.Notifications",
        )?;
        proxy.call::<_, _, ()>("CloseNotification", &(id,))?;
        Ok(())
    }
}

#[derive(Deserialize)]
struct Pair {
    code: String,
    name: String,
}
#[derive(Deserialize)]
pub struct Notification {
    key: String,
    app_name: String,
    title: String,
    message: String,
    urgency: u8,
    timeout_ms: i32,
}
#[derive(Deserialize)]
struct Dismiss {
    key: String,
}
struct Delivery {
    active: HashMap<(String, String), u32>,
    sink: Box<dyn Sink>,
}
#[derive(Clone)]
struct Context {
    state: Arc<Mutex<State>>,
    delivery: Arc<Mutex<Delivery>>,
}

pub fn router(state: Arc<Mutex<State>>, sink: impl Sink + 'static) -> Router {
    Router::new()
        .route("/v1/pair", post(pair))
        .route("/v1/health", get(health))
        .route("/v1/notification", post(send).delete(dismiss))
        .with_state(Context {
            state,
            delivery: Arc::new(Mutex::new(Delivery {
                active: HashMap::new(),
                sink: Box::new(sink),
            })),
        })
        .layer(DefaultBodyLimit::max(32 * 1024))
        .layer(TimeoutLayer::with_status_code(
            StatusCode::REQUEST_TIMEOUT,
            Duration::from_secs(5),
        ))
}

fn error<T>(_: T) -> StatusCode {
    StatusCode::INTERNAL_SERVER_ERROR
}

async fn pair(Extract(ctx): Extract<Context>, Json(req): Json<Pair>) -> Response {
    if req.code.len() != 64
        || req.name.trim().is_empty()
        || req.name.trim().chars().count() > 64
        || req.name.trim().chars().any(char::is_control)
    {
        return Err(StatusCode::BAD_REQUEST);
    }
    let token = ctx
        .state
        .lock()
        .map_err(error)?
        .exchange(&req.code, &req.name)
        .map_err(error)?
        .ok_or(StatusCode::UNAUTHORIZED)?;
    Ok(Json(json!({"token": token})))
}

fn device(ctx: &Context, headers: &HeaderMap) -> Result<String, StatusCode> {
    let token = headers
        .get("authorization")
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "))
        .filter(|value| value.len() == 64)
        .ok_or(StatusCode::UNAUTHORIZED)?;
    ctx.state
        .lock()
        .map_err(error)?
        .device(token)
        .map(|d| d.id.clone())
        .ok_or(StatusCode::UNAUTHORIZED)
}

async fn send(
    Extract(ctx): Extract<Context>,
    headers: HeaderMap,
    Json(req): Json<Notification>,
) -> Response {
    let id = device(&ctx, &headers)?;
    if req.key.is_empty() || req.key.len() > 1024 || req.urgency > 2 {
        return Err(StatusCode::BAD_REQUEST);
    }
    tokio::task::spawn_blocking(move || {
        let mut delivery = ctx.delivery.lock().map_err(error)?;
        let key = (id, req.key.clone());
        if delivery.active.len() >= 4096 && !delivery.active.contains_key(&key) {
            delivery.active.clear();
        }
        let previous = delivery.active.get(&key).copied();
        let id = delivery.sink.send(&req, previous).map_err(error)?;
        delivery.active.insert(key, id);
        Ok(Json(json!({"status": "sent"})))
    })
    .await
    .map_err(error)?
}

async fn dismiss(
    Extract(ctx): Extract<Context>,
    headers: HeaderMap,
    Json(req): Json<Dismiss>,
) -> Response {
    let id = device(&ctx, &headers)?;
    if req.key.is_empty() || req.key.len() > 1024 {
        return Err(StatusCode::BAD_REQUEST);
    }
    tokio::task::spawn_blocking(move || {
        let mut delivery = ctx.delivery.lock().map_err(error)?;
        if let Some(id) = delivery.active.remove(&(id, req.key)) {
            delivery.sink.dismiss(id).map_err(error)?;
        }
        Ok(Json(json!({"status": "dismissed"})))
    })
    .await
    .map_err(error)?
}

async fn health(Extract(ctx): Extract<Context>, headers: HeaderMap) -> Response {
    device(&ctx, &headers)?;
    Ok(Json(json!({"status": "ready"})))
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::{body::Body, http::Request};
    use tower::ServiceExt;

    #[derive(Default)]
    struct Fake(Vec<Option<u32>>);
    impl Sink for Fake {
        fn send(&mut self, _: &Notification, previous: Option<u32>) -> anyhow::Result<u32> {
            self.0.push(previous);
            Ok(previous.unwrap_or(self.0.len() as u32))
        }
        fn dismiss(&mut self, id: u32) -> anyhow::Result<()> {
            assert_eq!(id, 1);
            Ok(())
        }
    }

    async fn request(
        app: &Router,
        method: &str,
        path: &str,
        token: &str,
        body: String,
    ) -> StatusCode {
        app.clone()
            .oneshot(
                Request::builder()
                    .method(method)
                    .uri(path)
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {token}"))
                    .body(Body::from(body))
                    .unwrap(),
            )
            .await
            .unwrap()
            .status()
    }

    #[tokio::test]
    async fn authorization_size_and_device_scoping() -> anyhow::Result<()> {
        let dir = tempfile::tempdir()?;
        let mut state = State::load(dir.path().join("devices.json"))?;
        let mut tokens = Vec::new();
        for name in ["Phone A", "Phone B"] {
            state.begin()?;
            let code = state.pairing.as_ref().unwrap().code.clone();
            tokens.push(state.exchange(&code, name)?.unwrap());
        }
        let app = router(Arc::new(Mutex::new(state)), Fake::default());
        let body = json!({"key":"same", "app_name":"Test", "title":"Title", "message":"Body", "urgency":1, "timeout_ms":-1}).to_string();
        let path = "/v1/notification";
        assert_eq!(
            request(&app, "POST", path, "invalid", body.clone()).await,
            StatusCode::UNAUTHORIZED
        );
        assert_eq!(
            request(&app, "POST", path, &tokens[0], " ".repeat(33000)).await,
            StatusCode::PAYLOAD_TOO_LARGE
        );
        for i in [0, 1, 0] {
            assert_eq!(
                request(&app, "POST", path, &tokens[i], body.clone()).await,
                StatusCode::OK
            );
        }
        assert_eq!(
            request(
                &app,
                "DELETE",
                path,
                &tokens[0],
                json!({"key":"same"}).to_string()
            )
            .await,
            StatusCode::OK
        );
        Ok(())
    }
}
