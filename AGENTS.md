# LocalBell — Project Guide

## What is this project

LocalBell forwards Android push notifications to a Linux desktop via D-Bus.
- `server/`: Rust/egui desktop client that maintains a pinned TLS connection and delivers notifications through D-Bus.
- `app/`: Android foreground TLS listener, pairing and revocation, app filtering, and notification capture.

Enter the phone address and port in **Phone Configuration** on Linux and confirm the matching six-digit number on both devices. Android listens on IPv4 port `19420` by default and does not queue notifications across sessions. Linux reconnects automatically. Remove the pairing on both devices before changing peers; upgrading from legacy HTTPS requires pairing again. See `docs/protocol.md` for the versioned LAN protocol.

## Android design

Read and follow [`DESIGN_RULES.md`](DESIGN_RULES.md) and the `ui-android` skill before changing Android UI or theme tokens. Run the applicable design audit before shipping.

## Build

```bash
# Rust desktop
make build           # builds bin/localbell
make test-server     # Cargo tests
make lint-server     # rustfmt and Clippy

# Android
make test-app        # gradle test
make lint-app        # gradle lint + spotless
```

### Autonomous verification

Run every practical automated or noninteractive verification yourself, including isolated fixtures and available emulators. Do not ask the user to repeat checks you can run without physical interaction. Ask only for physical steps or device actions that require separate authorization. Do not publish a release, install an APK on a personal device, or send live notifications unless separately authorized.

For reverse-session interoperability, run `./scripts/test-reverse-emulator.sh emulator-SERIAL` with a fresh, running Android emulator. It installs debug fixtures only on the selected emulator and uses an isolated D-Bus daemon.

### Isolated Wayland testing

For Linux UI tests, launch LocalBell inside a nested niri compositor from a Wayland desktop. Give the test its own D-Bus session and XDG configuration so it does not use the real notification daemon or saved pairing. This isolates the desktop session, not the host filesystem or network; do not enter a real phone address or send live notifications.

From the repository root, run this in Bash (requires `niri` and `dbus-run-session`):

```bash
cargo build --locked --manifest-path server/Cargo.toml
fixture=$(mktemp -d "${TMPDIR:-/tmp}/localbell-wayland.XXXXXX")
mkdir -p "$fixture/config"
printf '%s\n' 'animations { off; }' > "$fixture/niri.kdl"
dbus-run-session -- niri -c "$fixture/niri.kdl" -- \
  env XDG_CONFIG_HOME="$fixture/config" "$PWD/server/target/debug/localbell"
```

For noninteractive tests, have niri launch a test script in place of `env ...`. The script inherits the nested `WAYLAND_DISPLAY`, `NIRI_SOCKET`, and `DBUS_SESSION_BUS_ADDRESS`. Use those inherited values for `niri msg`, `wtype` (keyboard input), or `grim` (screenshots); running them from the original terminal without the nested environment can target the real desktop. Save captures under the fixture directory and end automated runs with `niri msg action quit --skip-confirmation` from inside the nested session. For manual inspection, close the nested compositor window when finished.

For Android UI tests, use a dedicated emulator and target it explicitly with `adb -s emulator-SERIAL`. For browser previews such as README rendering, use headless Chromium with a temporary `--user-data-dir` rather than the user's browser profile.

### Android build cleanup

For one-off builds, tests, and lint, prefer `--no-daemon` and `-Pkotlin.compiler.execution.strategy=in-process` when running Gradle directly. After Android work, including failed runs, check for idle Gradle and Kotlin daemons left by this checkout. Use `cd app && ./gradlew --status` to verify that no same-version builds are active before running `./gradlew --stop` from `app/`. If a Kotlin daemon from this run remains, verify its PID and ownership before sending it `SIGTERM`. Do not stop unrelated Java processes or reset swap.

## Versioning — CRITICAL

End every iteration that changes project files with one patch bump. Do not bump a no-op iteration. Only Fred Junior may authorize a major or minor bump.

Run `scripts/bump-version.sh "fix: Describe the change" -- path/to/file` from `main`, listing only this iteration's files. It synchronizes the version, tests, lints, commits once, and pushes `main`. It does not generate release artifacts, update `dist/`, create tags, publish to GitHub Releases, or install the APK.

Only Fred Junior decides when to run `scripts/publish-release.sh`. It detects the version at `HEAD` and exits without changing anything if that version is already public. Otherwise it builds and verifies the signed APK and Linux archive from the same commit on `origin/main`, refreshes `dist/`, tags that commit, and publishes the signed APK, binary-only Linux archive, `.deb`, and `.rpm`. The publisher reports upload progress and verifies all four remote checksums before publishing. Retries reuse the same verified artifacts. Never move a tag or overwrite a public release. After confirming publication, it attempts to update the configured phone over Wi-Fi ADB; an unavailable phone or failed install does not invalidate the release.

Keep only the current LocalBell artifacts in `dist/`. Retain previous verified builds under `.git/localbell-releases/` without moving unrelated files.

## Desktop conventions

- Use Catppuccin Mocha and an opaque viewport and background. Use Mauve for section and dialog headings and their icons, Mocha text and surface colors for content, and Red only for errors and destructive actions.
- Embed CaskaydiaCove Nerd Font for text and Symbols Nerd Font for all control icons. Use 14 egui points for body, fields, and button text, and 16 for section and dialog titles. The LocalBell wordmark may use a larger size. Keep action labels visible next to their icons.
- Use shared named gaps, surface insets, and padded action styles. Group related content on surfaces instead of stacking separators. Review main and modal layouts at normal and narrow widths, including empty and paired states.
- Run `cargo fmt` and Clippy before finishing Rust changes.
- Keep desktop delivery asynchronous and bounded. Use injectable notification sinks or an isolated session bus in tests; do not send notifications to the live desktop. To exercise the D-Bus fixture, run `dbus-run-session -- env LOCALBELL_PRIVATE_DBUS_TEST=1 cargo test --manifest-path server/Cargo.toml desktop_delivery_recovers_on_a_private_bus`.
- Keep identity and paired-device configuration in the LocalBell XDG config directory. Do not log notification bodies, pairing secrets, comparison numbers, or tokens.

## CI / CD

- **CI** (`.github/workflows/ci.yml`): runs on every push to non-tag branches. Tests and lint for Rust and Android.
- **Release**: runs locally on shadowBox. APK signing uses `ANDROID_SIGNING_DIR`. Linux release builds use Docker with a Debian 12 baseline and native packaging tools. GitHub Releases receives the signed APK, binary-only Linux amd64 archive, Debian package, and Fedora package. The AUR package is updated separately from `/home/antrax/Dev/aur/localbell-bin` by the maintainer. No GitHub release workflow or Play Store deployment is configured.

## GitHub Actions versions (CI)

- `actions/checkout@v7`
- `actions/setup-java@v5`
