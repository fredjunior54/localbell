use crate::{
    api::{self, DesktopSink, Sink},
    listener, state,
};
use anyhow::{Context, Result, ensure};
use localbell::{
    session_client::{self, Credential, Disconnect, Event},
    session_tls,
};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    io,
    net::{SocketAddr, SocketAddrV4},
    path::Path,
    sync::{Arc, Mutex},
    time::Duration,
};
use tokio::sync::{oneshot, watch};

#[derive(Clone, Serialize, Deserialize)]
struct Settings {
    host: String,
    port: u16,
    credential: Option<Credential>,
}

#[derive(Clone)]
enum Request {
    Idle,
    Pair(Settings),
    Connect(Settings),
}

#[derive(Default)]
pub struct Status {
    pub message: &'static str,
    pub comparison: Option<(String, oneshot::Sender<bool>)>,
    pub saved: bool,
    pub busy: bool,
}

pub struct Controller {
    tx: watch::Sender<Request>,
    pub status: Arc<Mutex<Status>>,
    pub host: String,
    pub port: u16,
    path: std::path::PathBuf,
}

impl Controller {
    pub fn start(runtime: &tokio::runtime::Runtime, path: std::path::PathBuf) -> Result<Self> {
        let settings = match std::fs::read(&path) {
            Ok(bytes) => Some(
                serde_json::from_slice::<Settings>(&bytes)
                    .context("Invalid saved phone configuration")?,
            ),
            Err(error) if error.kind() == io::ErrorKind::NotFound => None,
            Err(error) => return Err(error).context("Read saved phone configuration"),
        };
        let status = Arc::new(Mutex::new(Status {
            message: "No phone configured",
            saved: settings
                .as_ref()
                .is_some_and(|value| value.credential.is_some()),
            ..Default::default()
        }));
        let initial = settings.clone().map_or(Request::Idle, Request::Connect);
        let (tx, rx) = watch::channel(initial);
        runtime.spawn(run(rx, Arc::clone(&status), path.clone()));
        Ok(Self {
            tx,
            status,
            path,
            host: settings
                .as_ref()
                .map_or_else(String::new, |value| value.host.clone()),
            port: settings.map_or(19420, |value| value.port),
        })
    }

    pub fn pair(&self, host: &str, port: u16) -> Result<()> {
        let host = host.trim();
        ensure!(
            !host.is_empty()
                && host.len() <= 253
                && host
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'-')),
            "Enter a phone IPv4 address or hostname."
        );
        ensure!(port != 0, "Port must be between 1 and 65535.");
        let mut status = self.status.lock().unwrap();
        ensure!(!status.saved, "Remove the previous phone first.");
        ensure!(!status.busy, "A connection attempt is already running.");
        status.busy = true;
        self.tx.send_replace(Request::Pair(Settings {
            host: host.to_owned(),
            port,
            credential: None,
        }));
        Ok(())
    }

    pub fn forget(&self) -> Result<()> {
        let mut status = self.status.lock().unwrap();
        match std::fs::remove_file(&self.path) {
            Ok(()) => (),
            Err(error) if error.kind() == io::ErrorKind::NotFound => (),
            Err(error) => return Err(error).context("Remove saved phone configuration"),
        }
        status.saved = false;
        status.comparison = None;
        self.tx.send_replace(Request::Idle);
        Ok(())
    }
}

async fn run(
    mut requests: watch::Receiver<Request>,
    status: Arc<Mutex<Status>>,
    path: std::path::PathBuf,
) {
    loop {
        let request = requests.borrow_and_update().clone();
        {
            let mut view = status.lock().unwrap();
            view.comparison = None;
            view.busy = !matches!(request, Request::Idle);
        }
        tokio::select! {
            biased;
            changed = requests.changed() => match changed {
                Ok(()) => continue,
                Err(_) => break,
            },
            () = process(request, &status, &path) => {
                let mut view = status.lock().unwrap();
                view.comparison = None;
                view.busy = false;
            }
        }
        if requests.changed().await.is_err() {
            break;
        }
    }
}

async fn process(request: Request, status: &Mutex<Status>, path: &Path) {
    let settings = match request {
        Request::Idle => {
            message(status, "No phone configured");
            return;
        }
        Request::Pair(settings) => {
            message(status, "Connecting for pairing…");
            match enroll(settings, status, path).await {
                Ok(settings) => settings,
                Err(_) => {
                    message(
                        status,
                        "Pairing did not complete. If either device saved a pairing, remove it before retrying.",
                    );
                    return;
                }
            }
        }
        Request::Connect(settings) => settings,
    };
    let Some(credential) = settings.credential.as_ref() else {
        return;
    };
    let mut delay = 1;
    loop {
        message(status, "Connecting to phone…");
        let started = tokio::time::Instant::now();
        match connect(&settings, credential, status).await {
            Ok(Disconnect::Revoked | Disconnect::Rejected) => {
                message(status, "Pairing was rejected. Remove it and pair again.");
                return;
            }
            Ok(Disconnect::EndOfStream) | Err(_) => {
                message(status, "Phone disconnected. Retrying…")
            }
        }
        if started.elapsed() > Duration::from_secs(30) {
            delay = 1;
        }
        tokio::time::sleep(Duration::from_secs(delay)).await;
        delay = (delay * 2).min(30);
    }
}

fn message(status: &Mutex<Status>, value: &'static str) {
    tracing::debug!(status = value, "Phone session changed");
    status.lock().unwrap().message = value;
}

async fn resolve(settings: &Settings) -> Result<SocketAddrV4> {
    tokio::time::timeout(
        Duration::from_secs(5),
        tokio::net::lookup_host((&*settings.host, settings.port)),
    )
    .await??
    .find_map(|addr| match addr {
        SocketAddr::V4(value) if listener::permitted(*value.ip()) => Some(value),
        _ => None,
    })
    .context("No permitted phone IPv4 address")
}

async fn enroll(mut settings: Settings, status: &Mutex<Status>, path: &Path) -> Result<Settings> {
    let (mut socket, leaf) = session_tls::enroll(resolve(&settings).await?).await?;
    let result = session_client::pair(
        &mut socket,
        leaf,
        |number| {
            let (tx, rx) = oneshot::channel();
            status.lock().unwrap().comparison = Some((number, tx));
            async move { rx.await.unwrap_or(false) }
        },
        |credential| {
            let mut view = status.lock().unwrap();
            let saved = Settings {
                credential: Some(credential.clone()),
                ..settings.clone()
            };
            state::save(path, &serde_json::to_vec(&saved)?)?;
            settings = saved;
            view.saved = true;
            Ok(())
        },
    )
    .await;
    // A lost final response is ambiguous. The pinned reconnect safely tests the saved token.
    if settings.credential.is_none() {
        result?;
    }
    Ok(settings)
}

async fn ready() -> bool {
    tokio::time::timeout(Duration::from_secs(2), async {
        let bus = zbus::Connection::session().await?;
        api::notifications_proxy(&bus)
            .await?
            .call::<_, _, Vec<String>>("GetCapabilities", &())
            .await
    })
    .await
    .is_ok_and(|reply| reply.is_ok())
}

async fn connect(
    settings: &Settings,
    credential: &Credential,
    status: &Mutex<Status>,
) -> Result<Disconnect> {
    let mut socket = session_tls::connect(resolve(settings).await?, credential.leaf).await?;
    let usable = ready().await;
    let delivered = Arc::new(tokio::sync::Mutex::new(HashMap::new()));
    let session = session_client::forward_with_status(
        &mut socket,
        credential,
        usable,
        || {
            message(
                status,
                if usable {
                    "Phone connected"
                } else {
                    "Phone connected; desktop notifications unavailable"
                },
            );
        },
        |event| {
            let delivered = Arc::clone(&delivered);
            async move {
                let started = tokio::time::Instant::now();
                let accepted = deliver(&mut *delivered.lock().await, event)
                    .await
                    .unwrap_or(false);
                tracing::debug!(
                    accepted,
                    elapsed_ms = started.elapsed().as_millis() as u64,
                    "Desktop delivery completed"
                );
                accepted
            }
        },
    );
    tokio::select! {
        result = session => result,
        () = watch_readiness(usable) => Ok(Disconnect::EndOfStream),
    }
}

async fn watch_readiness(previous: bool) {
    loop {
        tokio::time::sleep(Duration::from_secs(15)).await;
        if ready().await != previous {
            return;
        }
    }
}

pub(crate) async fn deliver(active: &mut HashMap<String, u32>, event: Event) -> Result<bool> {
    match event {
        Event::Notification(payload) => {
            let item: api::Notification = serde_json::from_value(payload)?;
            ensure!(
                !item.key.is_empty() && item.key.len() <= 1024 && item.urgency <= 2,
                "Invalid notification fields"
            );
            let previous = active.get(&item.key).copied();
            let id = DesktopSink.send(&item, previous).await?;
            if active.len() >= 4096 && previous.is_none() {
                active.clear();
            }
            active.insert(item.key, id);
        }
        Event::Dismiss(key) => {
            if let Some(&id) = active.get(&key) {
                DesktopSink.dismiss(id).await?;
                active.remove(&key);
            }
        }
    }
    Ok(true)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn resolution_excludes_public_and_loopback_addresses() -> Result<()> {
        let mut settings = Settings {
            host: "100.64.0.1".into(),
            port: 19420,
            credential: None,
        };
        assert_eq!(resolve(&settings).await?, "100.64.0.1:19420".parse()?);
        for host in ["127.0.0.1", "8.8.8.8", "0.0.0.0", "100.128.0.1"] {
            settings.host = host.into();
            assert!(resolve(&settings).await.is_err());
        }
        Ok(())
    }

    #[test]
    fn saved_phone_cannot_be_replaced_and_removal_clears_disk() -> Result<()> {
        let dir = tempfile::tempdir()?;
        let path = dir.path().join("phone-v1.json");
        state::save(
            &path,
            &serde_json::to_vec(&Settings {
                host: "192.168.0.2".into(),
                port: 19420,
                credential: Some(Credential {
                    leaf: [0; 32],
                    token: [0; 32],
                }),
            })?,
        )?;
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()?;
        let controller = Controller::start(&runtime, path.clone())?;
        assert!(controller.pair("192.168.0.3", 19420).is_err());
        controller.forget()?;
        assert!(!path.exists());
        runtime.block_on(async { tokio::task::yield_now().await });
        assert!(!controller.status.lock().unwrap().saved);
        Ok(())
    }
}
