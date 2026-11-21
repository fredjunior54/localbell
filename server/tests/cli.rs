use std::process::{Command, Output};

fn localbell(args: &[&str]) -> Output {
    Command::new(env!("CARGO_BIN_EXE_localbell"))
        .args(args)
        .env_remove("DISPLAY")
        .env_remove("WAYLAND_DISPLAY")
        .env_remove("HOME")
        .env_remove("XDG_CONFIG_HOME")
        .output()
        .unwrap()
}

#[test]
fn help_lists_options_without_starting_the_receiver() {
    for args in [
        &["--help"][..],
        &["-h"],
        &["--version", "--help"],
        &["--help", "--version"],
    ] {
        let output = localbell(args);
        assert!(output.status.success());
        let help = String::from_utf8(output.stdout).unwrap();
        assert!(help.contains("Usage: localbell [OPTIONS]"));
        assert!(help.contains("--help") && help.contains("--version"));
    }
}

#[test]
fn version_remains_machine_readable() {
    for flag in ["--version", "-V"] {
        let output = localbell(&[flag]);
        assert!(output.status.success());
        assert_eq!(
            output.stdout,
            concat!(env!("CARGO_PKG_VERSION"), "\n").as_bytes()
        );
    }
}

#[test]
fn unknown_option_reports_usage_without_opening_a_window() {
    let output = localbell(&["--unknown"]);
    assert!(!output.status.success());
    assert!(
        String::from_utf8(output.stderr)
            .unwrap()
            .contains("localbell --help")
    );
}

#[test]
fn rejects_wildcard_public_and_adjacent_overlay_addresses() {
    for address in ["0.0.0.0", "8.8.8.8", "100.63.255.255", "100.128.0.0"] {
        let result = std::process::Command::new(env!("CARGO_BIN_EXE_localbell"))
            .arg(format!("--listen={address}"))
            .output()
            .unwrap();
        assert!(!result.status.success(), "accepted {address}");
        assert!(String::from_utf8_lossy(&result.stderr).contains("100.64.0.0/10"));
    }
}
