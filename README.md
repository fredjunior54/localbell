<p align="center">
  <img src="assets/localbell.png" width="360" alt="LocalBell logo">
</p>

<h1 align="center">LocalBell</h1>
<p align="center"><strong>Version 1.4.6</strong></p>

<p align="center"><strong>Forward Android notifications to your Linux desktop.</strong></p>

<p align="center">
  <a href="https://github.com/fredjunior54/localbell/actions/workflows/ci.yml">
    <img src="https://img.shields.io/github/actions/workflow/status/fredjunior54/localbell/ci.yml?style=for-the-badge&label=CI" alt="CI">
  </a>
  <a href="https://github.com/fredjunior54/localbell/releases/latest">
    <img src="https://img.shields.io/badge/Download-GitHub_Releases-181717?style=for-the-badge&logo=github" alt="Download from GitHub Releases">
  </a>
  <img src="https://img.shields.io/badge/Linux-x86--64-FCC624?style=for-the-badge&logo=linux&logoColor=black" alt="Linux x86-64">
  <img src="https://img.shields.io/badge/Android-14%2B-3DDC84?style=for-the-badge&logo=android&logoColor=black" alt="Android 14 and later">
  <img src="https://img.shields.io/badge/Desktop-Rust_%2B_egui-DEA584?style=for-the-badge&logo=rust&logoColor=black" alt="Rust and egui desktop app">
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/License-AGPL--3.0-336699?style=for-the-badge" alt="AGPL-3.0 license">
  </a>
</p>

Maintained by **[Fred Junior](https://github.com/fredjunior54)**. LocalBell is a fork of **[LaNotifica](https://github.com/alessandrolattao/lanotifica)**, originally created by Alessandro Lattao.

## 🔔 How it works

1. Open LocalBell on your Linux desktop.
2. Open **Server Configuration** on Android, enter the receiver address and port, and tap **Pair**. Compare the six-digit number on both screens. If it matches, tap **Numbers match** on Android and select **Accept** on Linux.
3. Grant **Notification Access** on Android, then enable **Forwarding** once the receiver is connected. LocalBell delivers notifications to your desktop over the local network.

On Android 17 or later, allow local network access if prompted while pairing.

For an optional Tailscale connection, see the [setup guide](docs/getting-started.md#connect-over-tailscale). The same number check applies. The receiver stores its identity and paired-device credentials under `~/.config/localbell/` by default.

## ✨ Features

- **Pair by address:** Enter the receiver’s IP or hostname, compare a six-digit number, and approve. No QR scan or code entry.
- **Encrypted, direct delivery:** Notifications travel over certificate-pinned HTTPS on your LAN or Tailscale network. No account or cloud relay.
- **Native Linux notifications:** Get alerts through your desktop’s D-Bus notification service. Dismissing an alert on your phone also dismisses it on Linux.
- **Per-app filters:** Choose which Android apps can forward notifications.

## 🚀 Quick start

### 🐧 Server (Linux)

Download `localbell-X.Y.Z-linux-amd64.tar.gz` from [GitHub Releases](https://github.com/fredjunior54/localbell/releases/latest). From a terminal in your desktop session, extract and start it:

```bash
tar -xzf localbell-X.Y.Z-linux-amd64.tar.gz
./localbell
```

Run `./localbell --help` to list the CLI options or `./localbell --version` to print the installed version. Neither command opens a window.

The app listens on HTTPS port `19420` on a private IPv4 interface. Click the tray icon to show or hide the window. Closing the window keeps forwarding active when a tray host is available; **Quit** stops LocalBell. GNOME may require the AppIndicator and KStatusNotifierItem Support extension.

### 📱 App (Android)

Download and install `localbell-X.Y.Z.apk` from the same release. Android may ask you to allow installation from the app you used to open the APK. When upgrading from the old browser-based receiver, stop it first, update both apps, and pair again. After updating to Android version 1.3.9 from an earlier version, re-enable notification access in Android Settings and pair again.

## 📋 Requirements

| Component   | Requirement                                                                          |
| ----------- | ------------------------------------------------------------------------------------ |
| **Server**  | Linux x86-64 with a Wayland or X11 desktop, OpenGL, and a D-Bus notification service |
| **App**     | Android 14 or later (API 34+)                                                        |
| **Network** | Both devices reachable on the same LAN or tailnet                                               |

## 🔒 Security

- **TLS** encrypts notifications in transit. The Android app pins the certificate verified during the numeric comparison.
- A pairing request expires after two minutes. Only one new phone can be paired at a time. Remove a phone in the desktop window to revoke its credential. No code entry or account is required.

## 🛠️ Development

### 📦 Prerequisites

- A current stable Rust toolchain with Cargo, Clippy, and rustfmt, plus GNU Make and the Linux Wayland/X11 and EGL development libraries
- JDK 21 or later supported by the Gradle wrapper, and Android SDK Platform 37
- For local releases: Android SDK Build Tools, Arch `makepkg`, and GitHub CLI authenticated as a repository maintainer

### ⚙️ Setup

Set `ANDROID_HOME` to your installed SDK. Optionally install the repository's pre-push checks with `lefthook install`.

### 🧪 Build and test

```bash
make build        # Build the development desktop app
make test         # Run Rust and Android tests
make lint         # Run Rust and Android linters
make format       # Format Rust and Kotlin code
```

### 🏷️ Publish a release

On the release machine, `ANDROID_SIGNING_DIR` must contain exactly one `.p12`, `.jks`, or `.keystore`. Supply the store password via the environment or a private `release-password` file in that directory. Set `KEY_ALIAS` if the store has multiple private keys and `KEY_PASSWORD` if the key password differs from the store password. Keep the signing material outside Git.

On `main`, run the version bump with a commit subject and the files for that iteration:

```bash
./scripts/bump-version.sh "docs: Clarify Linux installation" -- README.md
```

The default bump is **patch**. Only Fred Junior can authorize `minor` or `major`. The script tests and lints locally, commits the selected changes with the synchronized version, and pushes `main`. It does not build release artifacts, change `dist/`, tag, publish, or install the APK.

`dist/` holds the APK and Linux archive from the last release build, which can be older than the current code version. The publisher retains verified artifacts, checksums, and the commit ID under `.git/localbell-releases/vX.Y.Z/` on the build machine.

When ready to publish, run this on the build machine with GitHub CLI authenticated and `curl` and `jq` available:

```bash
./scripts/publish-release.sh
```

The script detects the version from the current commit and verifies that the commit is on `origin/main`. If that version is already public, it prints the release URL and exits without uploading or installing anything. To publish a different version from `origin/main`, pass it explicitly: `./scripts/publish-release.sh vX.Y.Z`. The script builds the Linux binary and signed APK from the same isolated commit, verifies that their versions agree, refreshes `dist/`, then tags and publishes the two assets. The publisher reports each asset’s size and transfer progress, then verifies both files before publishing. If the upload is interrupted, rerun the same command. It reuses the retained build and matching uploaded assets and never replaces a public release or moves a tag. If GitHub reports a mismatched or incomplete asset, review the draft before retrying. GitHub Actions only runs CI.

To automatically update an already-paired phone over Wi-Fi, run `adb devices -l` while the phone is online. Copy its exact serial ending in `._adb-tls-connect._tcp`, then save it once in fish (replace the example serial):

```fish
set -Ux LOCALBELL_ADB_WIFI_DEVICE 'adb-EXAMPLE._adb-tls-connect._tcp'
```

After confirming that the GitHub release is public with both verified assets, the publisher updates only that phone without clearing app data or downgrading. An unavailable phone or failed APK install does not invalidate the release.

## 📄 License

[AGPL-3.0](LICENSE)
