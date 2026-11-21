mod api;
mod listener;
mod state;
mod tls;
mod ui;

use anyhow::{Context, Result};
use axum_server::Handle;
use mdns_sd::{ServiceDaemon, ServiceInfo};
use std::{
    fs,
    net::{Ipv4Addr, UdpSocket},
    os::unix::fs::DirBuilderExt,
    path::PathBuf,
    sync::{Arc, Mutex},
    time::Duration,
};

const SERVICE: &str = "_localbell-v1._tcp.local.";

type Shared = Arc<Mutex<state::State>>;
#[derive(Debug, Default)]
struct Network {
    settings: listener::Settings,
    applying: bool,
    error: Option<String>,
    message: String,
}

fn lan() -> Option<Ipv4Addr> {
    let socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).ok()?;
    socket.connect((Ipv4Addr::new(192, 0, 2, 1), 9)).ok()?;
    match socket.local_addr().ok()?.ip() {
        std::net::IpAddr::V4(ip) if ip.is_private() || ip.is_link_local() => Some(ip),
        _ => None,
    }
}

type ListenUpdates = tokio::sync::mpsc::Sender<listener::Settings>;

fn listen_result(status: &Mutex<Network>, settings: listener::Settings, error: Option<String>) {
    if let Ok(mut status) = status.lock() {
        status.settings = settings;
        status.applying = false;
        status.error = error;
    }
}

async fn receiver(
    dir: PathBuf,
    state: Shared,
    status: Arc<Mutex<Network>>,
    mut settings: listener::Settings,
    mut updates: tokio::sync::mpsc::Receiver<listener::Settings>,
    mut stop: tokio::sync::watch::Receiver<bool>,
) -> Result<()> {
    use axum_server::tls_rustls::RustlsAcceptor;
    let (tls, fingerprint) = tls::load(&dir).await?;
    let path = dir.join("listener.json");
    let mdns = if cfg!(test) {
        None
    } else {
        ServiceDaemon::new().ok()
    };
    let name = format!("LocalBell-{}", &fingerprint[..16]);
    let app = api::router(state.clone(), api::DesktopSink, fingerprint.clone());
    let mut prepared = None;
    loop {
        let socket = match prepared.take().map_or_else(|| settings.bind(), Ok) {
            Ok(socket) => socket,
            Err(error) => {
                if let Ok(mut status) = status.lock() {
                    status.message = format!("Cannot listen on {}: {error}", settings.endpoint());
                }
                tokio::select! {
                    _ = stop.changed() => break,
                    next = updates.recv() => {
                        let Some(next) = next else { break; };
                        match next.bind().map_err(anyhow::Error::from).and_then(|socket| {
                            next.save(&path)?;
                            Ok(socket)
                        }) {
                            Ok(socket) => { settings = next; prepared = Some(socket); listen_result(&status, settings, None); }
                            Err(error) => listen_result(&status, settings, Some(error.to_string())),
                        }
                    },
                    () = tokio::time::sleep(Duration::from_secs(3)) => {},
                }
                continue;
            }
        };
        let mut advertised = None;
        let mut refresh = tokio::time::interval(Duration::from_secs(3));
        let handle = Handle::new();
        let change = {
            let running = axum_server::from_tcp_rustls(socket, tls.clone())?
                .acceptor(RustlsAcceptor::new(tls.clone()).acceptor(listener::PrivatePeers))
                .handle(handle.clone())
                .serve(app.clone().into_make_service());
            tokio::pin!(running);
            loop {
                tokio::select! {
                    result = &mut running => { result?; break None; },
                    _ = stop.changed() => { handle.shutdown(); running.await?; break None; },
                    next = updates.recv() => {
                        let Some(next) = next else { handle.shutdown(); running.await?; break None; };
                        if next == settings {
                            let error = next.save(&path).err().map(|error| error.to_string());
                            listen_result(&status, settings, error);
                            continue;
                        }
                        let candidate = if settings.overlaps(next) { None } else {
                            match next.bind().map_err(anyhow::Error::from).and_then(|socket| {
                                next.save(&path)?;
                                Ok(socket)
                            }) {
                                Ok(socket) => Some(socket),
                                Err(error) => { listen_result(&status, settings, Some(format!("Could not apply {}: {error:#}", next.endpoint()))); continue; }
                            }
                        };
                        handle.shutdown();
                        running.await?;
                        break Some((next, candidate));
                    }
                    _ = refresh.tick() => {
                        let reachable = if settings.address.is_unspecified() { lan() } else { Some(settings.address) };
                        let publish = reachable.filter(|ip| !listener::overlay(*ip));
                        if let Some(mdns) = &mdns && advertised != publish {
                            if let Some(ip) = publish {
                                if let Ok(service) = ServiceInfo::new(SERVICE, &name, &format!("{name}.local."), std::net::IpAddr::V4(ip), settings.port, &[("fp", fingerprint.as_str())][..]) {
                                    let _ = mdns.register(service);
                                }
                            } else {
                                let _ = mdns.unregister(&format!("{name}.{SERVICE}"));
                            }
                            advertised = publish;
                        }
                        if let Ok(mut status) = status.lock() {
                            status.message = if settings.address.is_unspecified() {
                                match reachable {
                                    Some(ip) => format!("Listening on {} · Android: {ip}:{}", settings.endpoint(), settings.port),
                                    None => format!("Listening on {} · Enter a reachable IP on Android.", settings.endpoint()),
                                }
                            } else { format!("Listening on {}", settings.endpoint()) };
                        }
                    }
                }
            }
        };
        if let Some(mdns) = &mdns
            && advertised.is_some()
        {
            let _ = mdns.unregister(&format!("{name}.{SERVICE}"));
        }
        if let Ok(mut state) = state.lock() {
            state.pairing = None;
        }
        let Some((next, candidate)) = change else {
            break;
        };
        let result = candidate.map_or_else(
            || {
                next.bind().map_err(anyhow::Error::from).and_then(|socket| {
                    next.save(&path)?;
                    Ok(socket)
                })
            },
            Ok,
        );
        match result {
            Ok(socket) => {
                settings = next;
                prepared = Some(socket);
                listen_result(&status, settings, None);
            }
            Err(error) => {
                prepared = settings.bind().ok();
                listen_result(
                    &status,
                    settings,
                    Some(format!(
                        "Could not apply {}: {error}. Keeping {}.",
                        next.endpoint(),
                        settings.endpoint()
                    )),
                );
            }
        }
    }
    if let Some(mdns) = mdns {
        let _ = mdns.shutdown();
    }
    Ok(())
}
const HELP: &str = concat!(
    "LocalBell ",
    env!("CARGO_PKG_VERSION"),
    "\n",
    "Receive Android notifications on your Linux desktop.\n\n",
    "Usage: localbell [OPTIONS]\n\n",
    "With no options, open the desktop window and start the LAN receiver.\n\n",
    "Options:\n",
    "  --listen=IP    Override the listening IPv4 address for this session\n",
    "  --port=PORT    Override the HTTPS port for this session\n",
    "  -h, --help     Print this help and exit\n",
    "  --font-licenses  Print embedded font license notices\n",
    "  -V, --version  Print the version and exit\n",
);

#[derive(Debug, PartialEq, Eq)]
enum Command {
    Run(Option<Ipv4Addr>, Option<u16>),
    Help,
    Version,
    FontLicenses,
}

fn command(args: impl IntoIterator<Item = impl AsRef<std::ffi::OsStr>>) -> Result<Command> {
    let mut command = Command::Run(None, None);
    let mut listen = None;
    let mut port = None;
    for arg in args {
        match arg.as_ref().to_str() {
            Some("-h" | "--help") => command = Command::Help,
            Some("-V" | "--version") if command != Command::Help => command = Command::Version,
            Some("-V" | "--version") => (),
            Some("--font-licenses") if command != Command::Help => command = Command::FontLicenses,
            Some("--font-licenses") => (),
            Some(value) if value.starts_with("--listen=") => {
                let ip: Ipv4Addr = value[9..].parse().context("Invalid listen address")?;
                listener::Settings {
                    address: ip,
                    ..Default::default()
                }
                .validate()?;
                anyhow::ensure!(
                    listen.replace(ip).is_none(),
                    "Specify only one listen address"
                );
            }
            Some(value) if value.starts_with("--port=") => {
                let value: u16 = value[7..].parse().context("Invalid port")?;
                listener::Settings {
                    port: value,
                    ..Default::default()
                }
                .validate()?;
                anyhow::ensure!(port.replace(value).is_none(), "Specify only one port");
            }
            _ => anyhow::bail!(
                "Unexpected argument {:?}. Run localbell --help for usage.",
                arg.as_ref()
            ),
        }
    }
    Ok(if matches!(command, Command::Run(..)) {
        Command::Run(listen, port)
    } else {
        command
    })
}

fn main() -> Result<()> {
    let (listen, port) = match command(std::env::args_os().skip(1))? {
        Command::Help => {
            print!("{HELP}");
            return Ok(());
        }
        Command::Version => {
            println!("{}", env!("CARGO_PKG_VERSION"));
            return Ok(());
        }
        Command::FontLicenses => {
            print!(concat!(
                include_str!("../assets/NERD-FONTS.txt"),
                "\n",
                include_str!("../assets/CaskaydiaCove-LICENSE.txt"),
                "\n",
                include_str!("../assets/Symbols-Nerd-LICENSE.txt"),
            ));
            return Ok(());
        }
        Command::Run(listen, port) => (listen, port),
    };
    rustls::crypto::ring::default_provider()
        .install_default()
        .map_err(|_| anyhow::anyhow!("Initialize TLS"))?;
    let base = std::env::var_os("XDG_CONFIG_HOME")
        .filter(|v| !v.is_empty())
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|v| PathBuf::from(v).join(".config")))
        .context("Missing config directory")?;
    let dir = base.join("localbell");
    fs::DirBuilder::new()
        .recursive(true)
        .mode(0o700)
        .create(&dir)?;
    let lock = fs::OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(false)
        .open(dir.join("instance.lock"))?;
    lock.try_lock().context("LocalBell is already running")?;
    let saved = listener::Settings::load(&dir.join("listener.json"))?;
    let settings = listener::Settings {
        address: listen.unwrap_or(saved.address),
        port: port.unwrap_or(saved.port),
    };
    let (configure, updates) = tokio::sync::mpsc::channel(1);
    let (stop, stopped) = tokio::sync::watch::channel(false);
    let state = Arc::new(Mutex::new(state::State::load(dir.join("devices.json"))?));
    let status = Arc::new(Mutex::new(Network {
        message: "Starting receiver…".into(),
        settings,
        ..Default::default()
    }));
    let runtime = tokio::runtime::Runtime::new()?;
    let task = runtime.spawn({
        let state = Arc::clone(&state);
        let status = Arc::clone(&status);
        async move {
            if let Err(e) =
                receiver(dir, state, Arc::clone(&status), settings, updates, stopped).await
                && let Ok(mut status) = status.lock()
            {
                status.applying = false;
                status.message = format!("Receiver stopped: {e:#}");
            }
        }
    });
    let result = ui::run(state, status, configure);
    let _ = stop.send(true);
    let _ = runtime.block_on(async { tokio::time::timeout(Duration::from_secs(3), task).await });
    runtime.shutdown_timeout(Duration::from_secs(2));
    result
}

#[cfg(test)]
mod listener_tests {
    use super::*;
    use std::net::TcpListener;

    #[test]
    fn listener_defaults_validation_and_cli_overrides() -> Result<()> {
        let dir = tempfile::tempdir()?;
        let path = dir.path().join("listener.json");
        let defaults = listener::Settings::load(&path)?;
        assert_eq!(defaults.endpoint().to_string(), "0.0.0.0:19420");
        let custom = listener::Settings {
            address: "100.97.129.90".parse()?,
            port: 23456,
        };
        custom.validate()?.save(&path)?;
        assert_eq!(listener::Settings::load(&path)?, custom);
        assert_eq!(
            command(["--listen=192.168.1.26", "--port=34567"])?,
            Command::Run(Some("192.168.1.26".parse()?), Some(34567))
        );
        assert!(!listener::permitted(Ipv4Addr::LOCALHOST));
        assert!(!listener::permitted(Ipv4Addr::UNSPECIFIED));
        assert!(listener::permitted(custom.address));
        assert!(!listener::permitted(Ipv4Addr::new(8, 8, 8, 8)));
        Ok(())
    }

    async fn wait_for_status(
        status: &Mutex<Network>,
        check: impl Fn(&Network) -> bool,
    ) -> Result<()> {
        tokio::time::timeout(Duration::from_secs(5), async {
            while !check(&status.lock().unwrap()) {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .with_context(|| format!("Last state: {:?}", status.lock().unwrap()))?;
        Ok(())
    }

    #[tokio::test]
    async fn unavailable_bind_rolls_back_and_successful_rebind_persists() -> Result<()> {
        let _ = rustls::crypto::ring::default_provider().install_default();
        let dir = tempfile::tempdir()?;
        let path = dir.path().join("listener.json");
        let occupied = TcpListener::bind("0.0.0.0:0")?;
        let free = TcpListener::bind("0.0.0.0:0")?;
        let initial = listener::Settings {
            port: free.local_addr()?.port(),
            ..Default::default()
        };
        drop(free);
        let next_free = TcpListener::bind("0.0.0.0:0")?;
        let next = listener::Settings {
            port: next_free.local_addr()?.port(),
            ..initial
        };
        drop(next_free);
        initial.save(&path)?;
        let device_path = dir.path().join("devices.json");
        let mut devices = state::State::load(device_path.clone())?;
        devices.add_device("Test phone", &"a".repeat(64))?;
        let credentials = fs::read(device_path.clone())?;
        let network = Arc::new(Mutex::new(Network::default()));
        let (sender, updates) = tokio::sync::mpsc::channel(1);
        let (stop, stopped) = tokio::sync::watch::channel(false);
        let task = tokio::spawn(receiver(
            dir.path().into(),
            Arc::new(Mutex::new(devices)),
            network.clone(),
            initial,
            updates,
            stopped,
        ));
        wait_for_status(&network, |value| value.message.starts_with("Listening on")).await?;
        let identity = fs::read(dir.path().join("identity.pem"))?;
        sender
            .send(listener::Settings {
                port: occupied.local_addr()?.port(),
                ..initial
            })
            .await?;
        wait_for_status(&network, |value| value.error.is_some()).await?;
        assert_eq!(listener::Settings::load(&path)?, initial);
        let unavailable = listener::Settings {
            address: "100.127.255.253".parse()?,
            ..initial
        };
        if unavailable.bind().is_err() {
            sender.send(unavailable).await?;
            wait_for_status(&network, |value| {
                value
                    .error
                    .as_ref()
                    .is_some_and(|text| text.contains("100.127.255.253"))
            })
            .await?;
            assert!(initial.bind().is_err());
            assert_eq!(listener::Settings::load(&path)?, initial);
        }
        sender.send(next).await?;
        wait_for_status(&network, |value| {
            value.settings == next && value.message.contains(&next.endpoint().to_string())
        })
        .await?;
        assert_eq!(listener::Settings::load(&path)?, next);
        assert!(next.bind().is_err());
        stop.send(true)?;
        task.await??;
        assert!(next.bind().is_ok());
        assert_eq!(fs::read(device_path)?, credentials);
        assert_eq!(fs::read(dir.path().join("identity.pem"))?, identity);
        Ok(())
    }
}
