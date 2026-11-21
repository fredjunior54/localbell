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
4. Connect both devices to the same local network. Open **Server Configuration** on Android, enter the receiver address and port, and tap **Pair**. On Android 17 or later, allow local network access if prompted. Compare the six-digit number. If it matches, tap **Numbers match** on Android and select **Accept** on Linux.

After upgrading to Android version 1.3.9 from an earlier version, re-enable notification access in Android Settings and pair again.

## Configure the Linux listener

Open **Listen settings** to see or change the listen address and port. The defaults are `0.0.0.0` and `19420`. `0.0.0.0` listens on every IPv4 interface; enter a real reachable address in Android. **Apply** saves the endpoint and switches the receiver to it. If the new endpoint fails, the receiver keeps or retries the previous one. This does not change the TLS identity or paired devices. If Android cannot reconnect after an address or port change, remove its saved pairing on Linux and pair again using the new endpoint.

## Connect over Tailscale

On Linux, enter the receiver’s Tailscale IPv4 address in **Listen settings** for a persistent VPN-only bind, or use `./localbell --listen=100.70.80.90 --port=19420` as a session override (replace the example address). On Android, connect to that address or its MagicDNS name, with port `19420` if required. Both devices must be able to reach each other over the same tailnet. Configure your tailnet policy to allow only intended devices to access this port. Do not use Funnel or forward the port to the Internet. Compare the number even on a tailnet.
