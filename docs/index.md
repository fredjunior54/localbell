---
layout: default
title: LocalBell - Forward Android Notifications to Linux Desktop
description: >-
  Mirror your Android notifications to your Linux desktop over your local network.
  Encrypted with TLS and paired by phone address.
  Works with GNOME, KDE, XFCE, i3, Sway. Open source (AGPL-3.0).
---

# LocalBell

Forward your Android notifications to your Linux desktop.

## Overview

LocalBell forwards Android notifications directly to your Linux desktop over an encrypted, local connection. No account or cloud relay is required.

## How It Works

Android listens on TCP port `19420` by default. Enter the phone address and port on Linux, compare the six digits, and approve on both devices. After pairing, Linux reconnects automatically and Android forwards eligible notifications whenever the desktop is ready.

## Features

- TLS certificate pinning and explicit pairing approval
- Native Linux notifications through D-Bus, including available Android app artwork
- Android app filtering, delivery status, test notifications, and local unpairing

## Quick Start

Download the APK and Linux archive from [GitHub Releases](https://github.com/fredjunior54/localbell/releases/latest). Follow the [setup guide](getting-started.md).

## Documentation

- [Getting Started](getting-started.md)
- [Architecture](architecture.md)
- [Security](security.md)
- [Troubleshooting](troubleshooting.md)
- [Privacy Policy](privacy.md)
