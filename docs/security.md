---
title: LocalBell Security
description: LocalBell security model — TLS 1.3 encryption, certificate pinning, token-based authentication, and local-only communication.
---

# Security

## Design Principles

LocalBell is designed with privacy and security as core principles:

- **Local only** - All communication stays on your local network
- **No cloud** - No data is sent to external servers
- **No accounts** - No registration or login required
- **Encrypted** - All traffic is encrypted with TLS

## Authentication

The phone and receiver display the same certificate-bound phrase. After the user compares it and approves on Linux, Android registers a per-device bearer token over pinned HTTPS. The receiver can revoke that token without affecting other devices.

The token is stored securely on both the server and the app.

## Encryption

All communication between the app and server uses HTTPS with TLS 1.3.

The server generates a self-signed certificate on first startup. The phone pins the certificate authenticated by comparing the words on both devices.

## Certificate Pinning

The app stores the server's certificate fingerprint and verifies it on every connection. This prevents man-in-the-middle attacks even if an attacker has access to your local network.

## Permissions

The Android app requires:

- **Notification access** - To read and forward notifications
- **Local network** - To communicate with the server

No internet permission is required after the initial app download.
