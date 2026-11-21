---
title: LocalBell Architecture
description: How LocalBell forwards Android notifications to Linux using mDNS discovery, TLS encryption, and D-Bus desktop notifications.
---

# Architecture

## Components

LocalBell consists of two components:

**Server** - A Rust application with an egui window that runs on your Linux desktop. It receives notifications from the Android app and displays them using D-Bus desktop notifications.

**App** - An Android application that listens for notifications and forwards them to the server over your local network.

## Communication Flow

1. The receiver starts with its persistent TLS identity
2. Server advertises itself on the local network using mDNS
3. The user enters the receiver address; both devices display a six-digit number derived from the TLS certificate and fresh request values
4. The user confirms the matching number on Android and approves on Linux; Android registers a per-device token over pinned HTTPS and stores the configuration securely
5. App discovers the server via mDNS
6. When a notification arrives on the phone, the app sends it to the server over HTTPS
7. Server displays the notification on the desktop

## Discovery

The server uses mDNS (Multicast DNS) to advertise itself on the local network. The app automatically discovers the server without requiring manual IP configuration.

This works on any network that allows multicast traffic (most home and office networks).

## Notifications

The server uses D-Bus to display notifications on Linux. This is the standard notification system supported by all major desktop environments.

When a notification is dismissed on the phone, the server also dismisses it on the desktop.
