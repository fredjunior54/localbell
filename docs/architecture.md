---
title: LocalBell Architecture
description: How LocalBell forwards Android notifications to Linux using a pinned TLS session and D-Bus desktop notifications.
---

# Architecture

## Components

- Android runs a foreground TLS listener, stores its identity in Android Keystore, and captures eligible notifications through Android's notification listener.
- Linux opens and maintains the TLS session, verifies the enrolled phone identity, and submits accepted events to the desktop notification service over D-Bus.

## Communication Flow

1. Linux connects to the phone address and port configured by the user. Both devices approve a six-digit comparison on first contact.
2. On subsequent connections, Linux verifies the phone's certificate pin and proves that it holds the saved pairing token. It reports whether the desktop notification service is ready.
3. Android sends eligible events only on a ready session. Linux acknowledges D-Bus acceptance. Android has no offline queue and never initiates delivery connections.

See the [TLS session protocol](protocol.md) for framing and message types.
