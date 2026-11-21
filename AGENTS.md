# LocalBell — Project Guide

## What is this project

LocalBell forwards Android push notifications to a Linux desktop via D-Bus.
- `server/`: Rust/egui Linux desktop receiver, HTTPS API, mDNS discovery, and D-Bus notifications.
- `app/`: Android notification capture, address-based pairing, and certificate-pinned HTTPS delivery.

Enter the receiver address on Android, compare the six-digit number, confirm on Android, and accept on Linux. Use private LAN IPv4 by default or explicitly bind a Tailscale IPv4 address with `--listen=IP`. Existing saved pairings continue to work. The tray hides or shows the window; Quit stops the receiver. The versioned LAN protocol is documented in `docs/protocol.md`.

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

### Android build cleanup

For one-off builds, tests, and lint, prefer `--no-daemon` and `-Pkotlin.compiler.execution.strategy=in-process` when running Gradle directly. After Android work, including failed runs, check for idle Gradle and Kotlin daemons left by this checkout. Use `cd app && ./gradlew --status` to verify that no same-version builds are active before running `./gradlew --stop` from `app/`. If a Kotlin daemon from this run remains, verify its PID and ownership before sending it `SIGTERM`. Do not stop unrelated Java processes or reset swap.

## Versioning — CRITICAL

End every iteration that changes project files with one patch bump. Do not bump a no-op iteration. Only Fred Junior may authorize a major or minor bump.

Run `scripts/bump-version.sh "fix: Describe the change" -- path/to/file` from `main`, listing only this iteration's files. It synchronizes the version, tests, lints, commits once, and pushes `main`. It does not generate release artifacts, update `dist/`, create tags, publish to GitHub Releases, or install the APK.

Only Fred Junior decides when to run `scripts/publish-release.sh`. It detects the version at `HEAD` and exits without changing anything if that version is already public. Otherwise it builds and verifies the signed APK and Linux archive from the same commit on `origin/main`, refreshes `dist/`, tags that commit, and publishes both assets. The publisher reports upload progress and verifies both remote checksums before publishing. Retries reuse the same verified artifacts. Never move a tag or overwrite a public release. After confirming publication, it attempts to update the configured phone over Wi-Fi ADB; an unavailable phone or failed install does not invalidate the release.

Keep only the current LocalBell artifacts in `dist/`. Retain previous verified builds under `.git/localbell-releases/` without moving unrelated files.

## Desktop conventions

- Run `cargo fmt` and Clippy before finishing Rust changes.
- Use injectable notification sinks in tests; do not send notifications to the live desktop.
- Keep identity and paired-device configuration in the LocalBell XDG config directory. Do not log notification bodies, pairing secrets, comparison numbers, or tokens.

## CI / CD

- **CI** (`.github/workflows/ci.yml`): runs on every push to non-tag branches. Tests and lint for Rust and Android.
- **Release**: runs locally on shadowBox. APK signing uses `ANDROID_SIGNING_DIR`; GitHub Releases receives only the signed APK and binary-only Linux amd64 archive. No GitHub release workflow or Play Store deployment is configured.

## GitHub Actions versions (CI)

- `actions/checkout@v7`
- `actions/setup-java@v5`
