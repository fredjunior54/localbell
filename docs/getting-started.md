---
title: Getting Started with LocalBell
description: Install and configure LocalBell to forward Android notifications to your Linux desktop. Supports Fedora, Ubuntu, Debian and all major desktop environments.
---

# Getting Started

## Requirements

- Linux x86-64 with a Wayland or X11 desktop and a D-Bus notification service.
- Android 14 or later.
- A LAN or tailnet on which Linux can reach the phone's IPv4 address.

## Install and pair

1. Download the Linux amd64 archive and Android APK from [GitHub Releases](https://github.com/fredjunior54/localbell/releases/latest). Extract the archive, run `./localbell` in your desktop session, and install the APK.
2. Open LocalBell on Android. In **Listener settings**, confirm the port (default `19420`) and select **Start listening** if needed. Allow local network access if prompted.
3. Enter the phone's IPv4 address or hostname and port under **Phone Configuration** on Linux. Select **Pair**, verify that the six digits match, and confirm on both devices.
4. Grant **Notification Access** for automatic forwarding. Select **Send Test Notification** and check the Android **Status** panel.

When migrating from HTTPS, upgrade both apps and pair again. Existing app filters are preserved. To change the phone address or port of a saved desktop configuration, unpair on Android, remove the pairing on Linux, then pair again with the new endpoint.

## Connect over Tailscale

Enter the phone's Tailscale IPv4 address or MagicDNS hostname on Linux. Allow the desktop to reach TCP port `19420` (or the configured port) in your tailnet policy. Android listens on all IPv4 interfaces and applies a local-peer filter; using its Tailscale address does not restrict the listener to that interface. Do not expose the port through Funnel or port forwarding. Always compare the digits before approving.
