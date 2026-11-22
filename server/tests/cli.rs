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
        assert!(help.contains("--start-minimized"));
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
fn former_listener_flags_are_rejected() {
    for option in ["--listen=0.0.0.0", "--port=19420"] {
        assert!(!localbell(&[option]).status.success());
    }
}

#[test]
fn start_minimized_is_a_launch_option() {
    let output = localbell(&["--start-minimized"]);
    assert!(!output.status.success());
    assert!(String::from_utf8_lossy(&output.stderr).contains("Missing config directory"));
}
