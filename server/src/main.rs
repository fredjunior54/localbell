mod desktop;
mod phone;
mod state;
mod ui;

use anyhow::{Context, Result};
use std::{fs, os::unix::fs::DirBuilderExt, path::PathBuf, sync::Arc, time::Duration};

const HELP: &str = "Receive Android notifications on your Linux desktop.\n\nUsage: localbell [OPTIONS]\n\nConfigure the phone address and port in the desktop window.\n\n  -h, --help       Show help\n  -V, --version    Show version\n      --font-licenses    Show bundled font licenses\n      --start-minimized  Start in the system tray\n";

fn main() -> Result<()> {
    let args = std::env::args_os().skip(1).collect::<Vec<_>>();
    if args.iter().any(|arg| arg == "--help" || arg == "-h") {
        print!("{HELP}");
        return Ok(());
    }
    let start_minimized = match args.as_slice() {
        [] => false,
        [arg] if arg == "--start-minimized" => true,
        [arg] if arg == "--version" || arg == "-V" => {
            println!("{}", env!("CARGO_PKG_VERSION"));
            return Ok(());
        }
        [arg] if arg == "--font-licenses" => {
            print!(concat!(
                include_str!("../assets/NERD-FONTS.txt"),
                "\n",
                include_str!("../assets/CaskaydiaCove-LICENSE.txt"),
                "\n",
                include_str!("../assets/Symbols-Nerd-LICENSE.txt")
            ));
            return Ok(());
        }
        _ => anyhow::bail!("Unknown option. See localbell --help."),
    };
    use std::io::IsTerminal;
    let filter = tracing_subscriber::EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("localbell=info"));
    tracing_subscriber::fmt()
        .with_env_filter(filter)
        .with_writer(std::io::stderr)
        .with_ansi(std::io::stderr().is_terminal() && std::env::var_os("NO_COLOR").is_none())
        .without_time()
        .init();
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
    let runtime = tokio::runtime::Runtime::new()?;
    let phone = Arc::new(phone::Controller::start(
        &runtime,
        dir.join("phone-v1.json"),
    )?);
    let result = ui::run(phone, start_minimized);
    runtime.shutdown_timeout(Duration::from_secs(2));
    result
}
