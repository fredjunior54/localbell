use serde::Deserialize;
use std::collections::HashMap;
#[cfg(test)]
use std::time::Duration;

#[async_trait::async_trait]
pub trait Sink: Send {
    async fn send(
        &mut self,
        notification: &Notification,
        previous: Option<u32>,
    ) -> anyhow::Result<u32>;
    async fn dismiss(&mut self, id: u32) -> anyhow::Result<()>;
}

pub struct DesktopSink;
#[async_trait::async_trait]
impl Sink for DesktopSink {
    async fn send(&mut self, n: &Notification, previous: Option<u32>) -> anyhow::Result<u32> {
        use zbus::zvariant::{Structure, Value};
        let bus = zbus::Connection::session().await?;
        let proxy = notifications_proxy(&bus).await?;
        let mut hints = HashMap::from([("urgency", Value::from(n.urgency))]);
        let mut icon = "dialog-information";
        if let Some(pixels) = n.icon_rgba.as_deref().and_then(app_image) {
            let image = Structure::from((48_i32, 48_i32, 192_i32, true, 8_i32, 4_i32, pixels));
            hints.insert("image-data", Value::from(image));
            icon = "";
        }
        let actions: Vec<&str> = Vec::new();
        Ok(proxy
            .call(
                "Notify",
                &(
                    n.app_name.as_str(),
                    previous.unwrap_or_default(),
                    icon,
                    n.title.as_str(),
                    n.message.as_str(),
                    actions,
                    hints,
                    n.timeout_ms.max(-1),
                ),
            )
            .await?)
    }
    async fn dismiss(&mut self, id: u32) -> anyhow::Result<()> {
        let bus = zbus::Connection::session().await?;
        let proxy = notifications_proxy(&bus).await?;
        proxy.call::<_, _, ()>("CloseNotification", &(id,)).await?;
        Ok(())
    }
}

pub(crate) async fn notifications_proxy(bus: &zbus::Connection) -> zbus::Result<zbus::Proxy<'_>> {
    zbus::Proxy::new(
        bus,
        "org.freedesktop.Notifications",
        "/org/freedesktop/Notifications",
        "org.freedesktop.Notifications",
    )
    .await
}

#[derive(Deserialize)]
pub struct Notification {
    pub(crate) key: String,
    app_name: String,
    title: String,
    message: String,
    pub(crate) urgency: u8,
    timeout_ms: i32,
    icon_rgba: Option<String>,
}
fn app_image(encoded: &str) -> Option<Vec<u8>> {
    use base64::{Engine, engine::general_purpose::STANDARD};
    const BYTES: usize = 48 * 48 * 4;
    if encoded.len() != BYTES / 3 * 4 {
        return None;
    }
    let pixels = STANDARD.decode(encoded).ok()?;
    if pixels.len() != BYTES {
        return None;
    }
    Some(pixels)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn accepts_only_bounded_rgba_app_artwork() {
        use base64::{Engine, engine::general_purpose::STANDARD};
        let valid = STANDARD.encode(vec![0_u8; 48 * 48 * 4]);
        assert!(app_image(&valid).is_some());
        assert!(app_image("").is_none());
        assert!(app_image(&"*".repeat(valid.len())).is_none());
        assert!(app_image(&(valid + "AAAA")).is_none());
    }

    struct TestNotifications;

    #[zbus::interface(name = "org.freedesktop.Notifications")]
    impl TestNotifications {
        #[allow(clippy::too_many_arguments)] // Standard D-Bus Notify signature.
        fn notify(
            &self,
            app: &str,
            previous: u32,
            icon: &str,
            title: &str,
            body: &str,
            actions: Vec<String>,
            hints: std::collections::HashMap<String, zbus::zvariant::OwnedValue>,
            timeout: i32,
        ) -> zbus::fdo::Result<u32> {
            let _ = (app, body, actions);
            if title == "LocalBell test notification"
                && (timeout != -1
                    || hints
                        .get("urgency")
                        .and_then(|value| u8::try_from(value).ok())
                        != Some(1))
            {
                return Err(zbus::fdo::Error::Failed(
                    "Unexpected urgency or expiration".into(),
                ));
            }
            let expects_image = previous == 42 || title == "LocalBell test notification";
            if expects_image != hints.contains_key("image-data") || icon.is_empty() != expects_image
            {
                return Err(zbus::fdo::Error::Failed(
                    "Unexpected image or replacement data".into(),
                ));
            }
            Ok(42)
        }

        fn close_notification(&self, id: u32) -> zbus::fdo::Result<()> {
            if id == 42 {
                Ok(())
            } else {
                Err(zbus::fdo::Error::Failed("Unexpected ID".into()))
            }
        }
    }

    #[tokio::test]
    async fn desktop_delivery_recovers_on_a_private_bus() -> anyhow::Result<()> {
        if std::env::var_os("LOCALBELL_PRIVATE_DBUS_TEST").is_none() {
            // The fixture is started only by the explicit private-bus command.
            return Ok(());
        }
        anyhow::ensure!(
            std::env::var_os("DBUS_SESSION_BUS_ADDRESS").is_some(),
            "Private D-Bus address is required"
        );
        let mut notification = Notification {
            key: "fixture".into(),
            app_name: "LocalBell".into(),
            title: "Test".into(),
            message: "Fixture only".into(),
            urgency: 1,
            timeout_ms: 500,
            icon_rgba: None,
        };
        let mut sink = DesktopSink;
        tokio::time::timeout(Duration::from_secs(3), async {
            assert!(sink.send(&notification, None).await.is_err());
            let _bus = zbus::connection::Builder::session()?
                .name("org.freedesktop.Notifications")?
                .serve_at("/org/freedesktop/Notifications", TestNotifications)?
                .build()
                .await?;
            assert_eq!(sink.send(&notification, None).await?, 42);
            use base64::{Engine, engine::general_purpose::STANDARD};
            notification.icon_rgba = Some(STANDARD.encode(vec![255; 48 * 48 * 4]));
            assert_eq!(sink.send(&notification, Some(42)).await?, 42);
            sink.dismiss(42).await?;
            use localbell::session_client::Event;
            let mut delivered = HashMap::new();
            let payload = json!({"key":"session-fixture", "app_name":"LocalBell", "title":"Test",
                "message":"Fixture only", "urgency":1, "timeout_ms":500});
            assert!(crate::phone::deliver(&mut delivered, Event::Notification(payload)).await?);
            assert!(
                crate::phone::deliver(&mut delivered, Event::Dismiss("session-fixture".into()))
                    .await?
            );
            assert!(delivered.is_empty());
            Ok::<(), anyhow::Error>(())
        })
        .await??;
        Ok(())
    }

    #[tokio::test]
    async fn emulator_reverse_session_delivers_test_notification() -> anyhow::Result<()> {
        use localbell::{
            session_client::{self, Event},
            session_tls,
        };
        let Some(port) = std::env::var("LOCALBELL_EMULATOR_PORT").ok() else {
            return Ok(());
        };
        anyhow::ensure!(
            std::env::var_os("LOCALBELL_PRIVATE_DBUS_TEST").is_some(),
            "Run on an isolated D-Bus session"
        );
        let address = std::net::SocketAddrV4::new(std::net::Ipv4Addr::LOCALHOST, port.parse()?);
        let _bus = zbus::connection::Builder::session()?
            .name("org.freedesktop.Notifications")?
            .serve_at("/org/freedesktop/Notifications", TestNotifications)?
            .build()
            .await?;
        tokio::time::timeout(Duration::from_secs(50), async {
            let (mut socket, leaf) = loop {
                match session_tls::enroll(address).await {
                    Ok(connection) => break connection,
                    Err(_) => tokio::time::sleep(Duration::from_millis(250)).await,
                }
            };
            let mut saved = None;
            session_client::pair(&mut socket, leaf, |number| assert_eq!(number.len(), 6), || async { tokio::time::sleep(Duration::from_secs(6)).await; true }, |credential| {
                saved = Some(credential.clone());
                Ok(())
            }).await?;
            drop(socket);
            let saved = saved.ok_or_else(|| anyhow::anyhow!("Fixture did not save a credential"))?;
            let mut socket = session_tls::connect(address, saved.leaf).await?;
            let (tx, rx) = tokio::sync::oneshot::channel();
            let mut tx = Some(tx);
            let mut delivered = HashMap::new();
            let session = session_client::forward(&mut socket, &saved, true, |event| {
                let response = tx.take();
                let mut active = std::mem::take(&mut delivered);
                async move {
                    let expected = matches!(&event, Event::Notification(value) if value["title"] == "LocalBell test notification" && value["icon_rgba"].is_string());
                    let accepted = expected && crate::phone::deliver(&mut active, event).await.unwrap_or(false);
                    if let Some(response) = response { let _ = response.send(accepted); }
                    accepted
                }
            });
            // The Android UI verifies the acknowledged result before closing the session.
            let (result, accepted) = tokio::join!(session, rx);
            if let Err(error) = result {
                // Stopping the foreground service may close the connection without close_notify.
                if !error.downcast_ref::<std::io::Error>().is_some_and(|io| io.kind() == std::io::ErrorKind::UnexpectedEof) {
                    return Err(error);
                }
            }
            anyhow::ensure!(accepted?, "The isolated daemon did not receive test artwork");
            Ok::<(), anyhow::Error>(())
        }).await??;
        Ok(())
    }
}
