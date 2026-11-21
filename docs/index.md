---
layout: default
title: LocalBell - Forward Android Notifications to Linux Desktop
description: >-
  Mirror your Android notifications to your Linux desktop over your local network.
  Encrypted with TLS, auto-discovery via mDNS, zero configuration.
  Works with GNOME, KDE, XFCE, i3, Sway. Open source (AGPL-3.0).
---

# LocalBell

Forward your Android notifications to your Linux desktop.

## Overview

LocalBell is a simple tool that sends your phone notifications directly to your Linux desktop. WhatsApp, Telegram, calls — everything. All communication stays on your local network — no cloud, no accounts, no tracking.

## How It Works

```
┌──────────────┐      HTTPS/TLS       ┌──────────────┐
│              │    Local Network     │              │
│   Android    │ ──────────────────►  │    Linux     │
│    Phone     │    mDNS Discovery    │   Desktop    │
│              │                      │              │
└──────────────┘                      └──────────────┘
```

1. Install the server on your Linux machine
2. Open the app on your Android phone
3. Scan the QR code shown by the server
4. Done — notifications appear on your desktop

The server and app find each other automatically using mDNS. All communication is encrypted with TLS.

## Features

| | Feature | Description |
|---|---|---|
| 🔒 | **Zero cloud** | Everything stays on your local network |
| 🔐 | **Encrypted** | TLS with auto-generated certificates |
| ✨ | **Zero config** | mDNS auto-discovery, no IP addresses to type |
| 🔋 | **Battery friendly** | Minimal impact on your phone |
| 🐧 | **Works everywhere** | GNOME, KDE, XFCE, i3, Sway... |

## Quick Start

Download the signed Android APK and Linux amd64 archive from [GitHub Releases](https://github.com/fredjunior54/localbell/releases/latest). Follow the [setup guide](getting-started.md) to install and pair them.

## Documentation

- [Getting Started](getting-started.md) — Installation and setup guide
- [Architecture](architecture.md) — How LocalBell works under the hood
- [Security](security.md) — Encryption, authentication, and privacy details
- [Troubleshooting](troubleshooting.md) — Common issues and solutions
- [Privacy Policy](privacy.md)

## Links

- [GitHub Repository](https://github.com/fredjunior54/localbell)
