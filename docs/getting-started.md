---
title: Getting Started with LocalBell
description: Install and configure LocalBell to forward Android notifications to your Linux desktop. Supports Fedora, Ubuntu, Debian and all major desktop environments.
---

# Getting Started

## Requirements

**Server (Linux)**
- Any Linux distribution with systemd
- Desktop environment with D-Bus notifications (GNOME, KDE, XFCE, i3, Sway, etc.)

**App (Android)**
- Android 14 or higher

**Network**
- Both devices on the same local network (WiFi)

## Install and pair

1. Download the Linux amd64 archive and signed Android APK from [GitHub Releases](https://github.com/fredjunior54/localbell/releases/latest).
2. Extract the archive and run `./localbell` in your Linux desktop session.
3. Install the APK on your phone and grant notification access when the app prompts you.
4. Connect both devices to the same local network. Select **Pair a device** in the Linux window and scan the QR code from the Android app.

After upgrading to Android version 1.3.9 from an earlier version, re-enable notification access in Android Settings and pair again.
