---
title: LocalBell Troubleshooting
description: Fix common issues with LocalBell — pairing, connection, and notification delivery issues.
---

# Troubleshooting

## Desktop cannot connect

- Open LocalBell on Android and check **Status**. If the listener is stopped, open **Listener settings** and select **Start listening**. On Android 17 or later, grant Local Network access when prompted.
- Verify that the desktop can reach the phone's configured IPv4 address and port. If either changed, remove the saved pairing on both devices and pair with the new address.
- If you migrated from the old HTTPS configuration, update both apps and pair again.

## Notifications do not appear

- If **Status** reports that desktop notifications are unavailable, run LocalBell inside your desktop session and verify that the D-Bus notification service is running.
- Try **Send Test Notification** from Android. Acknowledgment means the daemon accepted the request; display and do-not-disturb settings remain under desktop control.
- For automatic forwarding, verify Android **Notification Access** and **App Filters**. Notifications are not replayed after reconnection.
