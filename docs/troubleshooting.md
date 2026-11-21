---
title: LocalBell Troubleshooting
description: Fix common issues with LocalBell — server not starting, notifications not appearing, mDNS discovery problems, and Android app connection issues.
---

# Troubleshooting

## Server Issues

**Server won't start**
- Start the receiver from your Linux desktop session
- Look at the logs for error messages
- Make sure port 19420 is not in use by another application

**Pairing request does not appear**
- Show the receiver window from the tray
- Tap **Connect** after verifying the address and port on the phone

**Notifications don't appear on desktop**
- Check if your desktop environment supports D-Bus notifications
- Try sending a test notification from another application

## App Issues

**Pairing words do not match**
- Select **Reject** and verify the receiver address
- Never approve a request unless all words match in order

**App doesn't connect to server**
- Verify both devices are on the same network
- Check if your router blocks multicast traffic (mDNS)
- Try restarting both the server and the app

**Notifications not forwarded**
- Ensure notification access is granted in Android settings
- Check that the forwarding toggle is enabled in the app
- Verify the server is shown as "Connected" in the app

## Network Issues

**mDNS discovery fails**
- Some corporate networks block multicast traffic
- Guest networks often isolate devices from each other
- Try using a home network or mobile hotspot

**Connection drops frequently**
- Check your WiFi signal strength
- The server uses a health check to maintain the connection
- The app will automatically reconnect when the server is available
