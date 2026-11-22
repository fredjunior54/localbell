---
title: LocalBell Security
description: LocalBell security model — TLS 1.3 encryption, certificate pinning, token-based authentication, and local-only communication.
---

# Security

## Authentication and Encryption

The Linux client connects to the Android listener using TLS 1.3. During first contact, compare the six digits and explicitly approve on both devices. Linux pins the phone's certificate and stores a high-entropy credential; Android stores only the credential digest. Unpairing on Android invalidates that credential even if Linux is offline.

The listener accepts private and link-local IPv4 peers and the `100.64.0.0/10` overlay range. This is not VPN-only isolation. Do not expose the port to the Internet.

## Permissions

Notification Access is required for automatic capture, not for a test. On Android 17 or later, allow Local Network access when prompted. See [the protocol](protocol.md) for additional details.
