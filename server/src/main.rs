mod api;
mod state;
mod tls;
mod ui;

use anyhow::{Context, Result};
use axum_server::Handle;
use mdns_sd::{ServiceDaemon, ServiceInfo};
use std::{
    fs,
    net::{Ipv4Addr, SocketAddr, TcpListener, UdpSocket},
    os::unix::fs::DirBuilderExt,
    path::PathBuf,
    sync::{Arc, Mutex},
    time::Duration,
};

const PORT: u16 = 19420;
const SERVICE: &str = "_localbell-v1._tcp.local.";

type Shared = Arc<Mutex<state::State>>;
#[derive(Default)]
struct Network {
    ip: Option<Ipv4Addr>,
    fingerprint: String,
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

async fn receiver(
    dir: PathBuf,
    state: Shared,
    status: Arc<Mutex<Network>>,
    listen: Option<Ipv4Addr>,
    mut stop: tokio::sync::watch::Receiver<bool>,
) -> Result<()> {
    let (tls, fingerprint) = tls::load(&dir).await?;
    let mdns = if listen.is_none() {
        ServiceDaemon::new().ok()
    } else {
        None
    };
    let short = &fingerprint[..16];
    let name = format!("LocalBell-{short}");
    let app = api::router(state.clone(), api::DesktopSink);
    loop {
        let Some(ip) = listen.or_else(lan) else {
            if let Ok(mut status) = status.lock() {
                status.message = "No private IPv4 network available.".into();
            }
            tokio::select! { _ = stop.changed() => break, () = tokio::time::sleep(Duration::from_secs(3)) => {} }
            continue;
        };
        let socket = match TcpListener::bind(SocketAddr::from((ip, PORT))) {
            Ok(socket) => socket,
            Err(e) => {
                if let Ok(mut status) = status.lock() {
                    status.message = format!("Cannot listen on {ip}:{PORT}: {e}");
                }
                tokio::select! { _ = stop.changed() => break, () = tokio::time::sleep(Duration::from_secs(3)) => {} }
                continue;
            }
        };
        socket.set_nonblocking(true)?;
        let service = ServiceInfo::new(
            SERVICE,
            &name,
            &format!("{name}.local."),
            std::net::IpAddr::V4(ip),
            PORT,
            &[("fp", fingerprint.as_str())][..],
        )?;
        let fullname = service.get_fullname().to_owned();
        let discovery = mdns.as_ref().is_some_and(|d| d.register(service).is_ok());
        if let Ok(mut status) = status.lock() {
            *status = Network {
                ip: Some(ip),
                fingerprint: fingerprint.clone(),
                message: if discovery {
                    format!("Listening on {ip}:{PORT}")
                } else {
                    format!("Listening on {ip}:{PORT}; automatic discovery is unavailable.")
                },
            };
        }
        let handle = Handle::new();
        let server = axum_server::from_tcp_rustls(socket, tls.clone())?
            .handle(handle.clone())
            .serve(app.clone().into_make_service());
        tokio::pin!(server);
        let outcome = tokio::select! {
            result = &mut server => result,
            _ = stop.changed() => { handle.shutdown(); server.await },
            () = async { loop { tokio::time::sleep(Duration::from_secs(3)).await; if listen.is_none() && lan() != Some(ip) { break; } } } => {
                handle.shutdown();
                server.await
            }
        };
        if let Some(mdns) = &mdns {
            let _ = mdns.unregister(&fullname);
        }
        if let Ok(mut status) = status.lock() {
            status.ip = None;
        }
        if let Ok(mut state) = state.lock() {
            state.pairing = None;
        }
        outcome?;
        if *stop.borrow() {
            break;
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
    "  --listen=IP    Listen only on this Tailscale IPv4 address\n",
    "  -h, --help     Print this help and exit\n",
    "  -V, --version  Print the version and exit\n",
);

#[derive(Debug, PartialEq, Eq)]
enum Command {
    Run(Option<Ipv4Addr>),
    Help,
    Version,
}

fn command(args: impl IntoIterator<Item = impl AsRef<std::ffi::OsStr>>) -> Result<Command> {
    let mut command = Command::Run(None);
    let mut listen = None;
    for arg in args {
        match arg.as_ref().to_str() {
            Some("-h" | "--help") => command = Command::Help,
            Some("-V" | "--version") if command != Command::Help => command = Command::Version,
            Some("-V" | "--version") => (),
            Some(value) if value.starts_with("--listen=") => {
                let ip: Ipv4Addr = value[9..].parse().context("Invalid listen address")?;
                let [first, second, ..] = ip.octets();
                anyhow::ensure!(
                    first == 100 && (64..=127).contains(&second),
                    "Listen override must be in 100.64.0.0/10"
                );
                anyhow::ensure!(
                    listen.replace(ip).is_none(),
                    "Specify only one listen address"
                );
            }
            _ => anyhow::bail!(
                "Unexpected argument {:?}. Run localbell --help for usage.",
                arg.as_ref()
            ),
        }
    }
    Ok(if matches!(command, Command::Run(_)) {
        Command::Run(listen)
    } else {
        command
    })
}

fn main() -> Result<()> {
    let listen = match command(std::env::args_os().skip(1))? {
        Command::Help => {
            print!("{HELP}");
            return Ok(());
        }
        Command::Version => {
            println!("{}", env!("CARGO_PKG_VERSION"));
            return Ok(());
        }
        Command::Run(listen) => listen,
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
    let (stop, stopped) = tokio::sync::watch::channel(false);
    let state = Arc::new(Mutex::new(state::State::load(dir.join("devices.json"))?));
    let status = Arc::new(Mutex::new(Network {
        message: "Starting receiver…".into(),
        ..Default::default()
    }));
    let runtime = tokio::runtime::Runtime::new()?;
    let task = runtime.spawn({
        let state = Arc::clone(&state);
        let status = Arc::clone(&status);
        async move {
            if let Err(e) = receiver(dir, state, Arc::clone(&status), listen, stopped).await
                && let Ok(mut status) = status.lock()
            {
                status.ip = None;
                status.message = format!("Receiver stopped: {e:#}");
            }
        }
    });
    let result = ui::run(state, status);
    let _ = stop.send(true);
    let _ = runtime.block_on(async { tokio::time::timeout(Duration::from_secs(3), task).await });
    runtime.shutdown_timeout(Duration::from_secs(2));
    result
}
