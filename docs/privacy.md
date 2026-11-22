---
title: LocalBell Privacy Policy
description: LocalBell privacy policy — no data collection, no cloud, no tracking. All notification data stays on your local network.
---

# Privacy Policy

**Last updated:** December 2025

## Overview

LocalBell does not collect, store, or transmit any personal data to external servers.

## What data is accessed

LocalBell requires **notification access permission** to read your Android notifications. This data is:

- Forwarded directly to your Linux desktop over your **local network (LAN) only**
- Encrypted using TLS during transmission
- Never stored on any server
- Never sent to external services or cloud providers

## What we don't do

- No data collection
- No analytics or tracking
- No advertisements
- No cloud services
- No account required; the devices authenticate locally
- No third-party data sharing

## Network communication

All communication occurs exclusively within your local network:

- Your Linux desktop connects directly to the Android listener
- Connection is authenticated using a unique token generated during setup
- All traffic is encrypted with TLS and certificate pinning
- No internet connection is required after initial app download

## Permissions

| Permission | Purpose |
|------------|---------|
| Notification Access | Read notifications to forward them to your desktop |
| Local Network | Allow the desktop to reach LocalBell over the LAN |

## Open Source

LocalBell is open source software licensed under AGPL-3.0. You can review the complete source code at:

https://github.com/fredjunior54/localbell

## Contact

For questions or concerns about this privacy policy, please open an issue on GitHub:

https://github.com/fredjunior54/localbell/issues
