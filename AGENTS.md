# LocalBell — Project Guide

## What is this project

LocalBell forwards Android push notifications to a Linux desktop via D-Bus.
- `server/`: Rust/egui Linux desktop receiver, HTTPS API, mDNS discovery, and D-Bus notifications.
- `app/`: Android notification capture, QR pairing, and certificate-pinned HTTPS delivery.

Pair from the desktop window with a temporary, single-use QR code. The tray hides or shows the window; Quit stops the receiver. The versioned LAN protocol is documented in `docs/protocol.md`.

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

## Versioning — CRITICAL

End every iteration that changes project files with one patch release. Do not release a no-op iteration. Only Fred Junior may authorize a major or minor bump.

Run `scripts/bump-version.sh "fix: Describe the change" -- path/to/file` from `main`, listing only the files for that iteration. The script synchronizes Gradle, Arch packaging metadata, the Cargo package and desktop binary versions, and the Git tag; tests, lints, and builds locally; commits once; pushes `main` and the tag; and publishes the signed APK and Linux amd64 archive. Never create a second version-only commit or move a published tag.

Keep only the current LocalBell release artifacts in `dist/`. Archive older LocalBell outputs outside `dist/` without moving unrelated files. When the configured phone is available through authorized Wi-Fi ADB, install the verified APK in place; a skipped or failed phone update does not block publication.

## Desktop conventions

- Run `cargo fmt` and Clippy before finishing Rust changes.
- Use injectable notification sinks in tests; do not send notifications to the live desktop.
- Keep identity and paired-device configuration in the LocalBell XDG config directory. Do not log notification bodies, pairing codes, or tokens.

## CI / CD

- **CI** (`.github/workflows/ci.yml`): runs on every push to non-tag branches. Tests and lint for Rust and Android.
- **Release**: runs locally on shadowBox. APK signing uses `ANDROID_SIGNING_DIR`; GitHub Releases receives only the signed APK and binary-only Linux amd64 archive. No GitHub release workflow or Play Store deployment is configured.

## GitHub Actions versions (CI)

- `actions/checkout@v7`
- `actions/setup-java@v5`
