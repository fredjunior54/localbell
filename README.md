<p align="center">
  <img src="assets/lanotifica-512.png" width="160" alt="LocalBell icon">
</p>

<h1 align="center">LocalBell</h1>
<p align="center"><strong>Version 1.3.5</strong></p>

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
2. Select **Pair a device** and scan the temporary QR code with LocalBell on Android.
3. Grant notification access on Android and enable forwarding. LocalBell delivers notifications to your desktop over the local network.

## ✨ Features

- **No cloud relay:** Notifications travel directly from your phone to your desktop.
- **Automatic discovery:** The app discovers the server over mDNS.
- **Desktop integration:** Notifications appear through your desktop's D-Bus notification service.

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

Download and install `localbell-X.Y.Z.apk` from the same release. Android may ask you to allow installation from the app you used to open the APK. LocalBell can coexist with LaNotifica. When upgrading from the old browser-based LocalBell, stop the old receiver first, update both apps, and pair again.

## 📋 Requirements

| Component | Requirement |
|-----------|-------------|
| **Server** | Linux x86-64 with a Wayland or X11 desktop, OpenGL, and a D-Bus notification service |
| **App** | Android 14 or later (API 34+) |
| **Network** | Both devices on the same local network |

## 🔒 Security

- **TLS** encrypts notifications in transit. The Android app pins the certificate fingerprint from the pairing QR code.
- Each phone gets its own credential from a **one-time QR code** that expires after two minutes. Remove a phone in the desktop window to revoke its credential. No PIN or account is required.

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

On `main`, run the release script with a commit subject and the files for that iteration:

```bash
./scripts/bump-version.sh "docs: Clarify Linux installation" -- README.md
```

The default bump is **patch**. Only Fred Junior can authorize an explicit `minor` or `major` bump. The script tests and builds locally, commits the selected changes with the synchronized version, pushes the branch and annotated tag, and publishes the signed APK and Linux archive to GitHub Releases. GitHub Actions only runs CI.

If the tag was pushed but release creation failed, retry with the same tag and the verified assets retained under `.git/localbell-releases/vX.Y.Z/`. Do not bump the version or move the tag:

```bash
gh release create vX.Y.Z --repo fredjunior54/localbell --verify-tag --generate-notes \
  .git/localbell-releases/vX.Y.Z/localbell-X.Y.Z.apk \
  .git/localbell-releases/vX.Y.Z/localbell-X.Y.Z-linux-amd64.tar.gz
```

## 📄 License

[AGPL-3.0](LICENSE)
