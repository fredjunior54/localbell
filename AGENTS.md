# LocalBell — Project Guide

## What is this project

LocalBell forwards Android push notifications to a Linux desktop via D-Bus.
Two components:

- **`server/`** — Go HTTPS server (TLS self-signed, mDNS discovery, D-Bus notifications, icon cache)
- **`app/`** — Android app (Compose UI, sends notifications to the server via HTTPS + Bearer token)

End-to-end flow: Android detects a notification → sends POST `/notification` to the Go server → server calls D-Bus → notification appears on Linux desktop. Server discovery is automatic via mDNS (`lanotifica.local`). Pairing happens by scanning a QR code on the server's web page (PIN-protected since v1.1.0).

## Key skills — read these before working

- `/project-overview` — architecture, data flow, package structure
- `/go-server-structure` — Go package layout, conventions, patterns
- `/android-app-structure` — Android architecture (MVVM, DataStore, Tink)
- `/build-and-packaging` — Makefile and local release builds
- `/bump-version` — HOW TO bump version before tagging a release
- `/release` — local release checklist
- `/lint-and-test` — run linters and tests locally
- `/new-feature` — where to add code for new features
- `/review-security` — security checklist for Go server changes

## Build

```bash
# Go server
make build           # builds bin/lanotifica
make test-server     # go test ./...
make lint-server     # golangci-lint (config in server/.golangci.yml)

# Android
make test-app        # gradle test
make lint-app        # gradle lint + spotless
```

## Versioning — CRITICAL

End every iteration that changes project files with one patch release. Do not release a no-op iteration. Only Fred Junior may authorize a major or minor bump.

Run `scripts/bump-version.sh "fix: Describe the change" -- path/to/file` from `main`, listing only the files for that iteration. The script synchronizes Gradle, Arch packaging metadata, the embedded Go version, and the Git tag; tests, lints, and builds locally; commits once; pushes `main` and the tag; and publishes the signed APK and Linux amd64 archive. Never create a second version-only commit or move a published tag.

## Code conventions (Go server)

- All errors wrapped: `fmt.Errorf("context: %w", err)`
- No `//nolint` except for genuine false positives (gosec on controlled paths), always with explanation
- All tests use `t.Parallel()`, subtests too
- Config follows XDG Base Dir spec (`~/.config/lanotifica/`)
- `defer func() { _ = f.Close() }()` for resource cleanup in defers

## CI / CD

- **CI** (`.github/workflows/ci.yml`): runs on every push to non-tag branches. Tests + lint for Go and Android.
- **Release**: runs locally on shadowBox. APK signing uses `ANDROID_SIGNING_DIR`; GitHub Releases receives only the signed APK and binary-only Linux amd64 archive. No GitHub release workflow or Play Store deployment is configured.

## GitHub Actions versions (CI)

- `actions/checkout@v7`
- `actions/setup-go@v6`
- `actions/setup-java@v5`
- `golangci/golangci-lint-action@v9`
