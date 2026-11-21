# LocalBell LAN Protocol v1

LocalBell listens for HTTPS on a private IPv4 address, port 19420 by default. No HTTP fallback, browser setup, redirects, or public discovery service is used.

The desktop displays `localbell:1|IPV4|PORT|FINGERPRINT|CODE`, where the last two fields are 64 lowercase hexadecimal characters. The fingerprint is SHA-256 of the DER-encoded leaf certificate. The code is random, valid for at most two minutes, and consumed by the first successful exchange. Hiding the window or canceling pairing invalidates the pending code. Android verifies the pinned leaf before making any request.

| Request | JSON body | Success response |
|---|---|---|
| `POST /v1/pair` | `code`, `name` | `200`, `token` |
| `GET /v1/health` | None | `200`, `status: ready` |
| `POST /v1/notification` | `key`, `app_name`, `title`, `message`, `urgency`, `timeout_ms` | `200`, `status: sent` |
| `DELETE /v1/notification` | `key` | `200`, `status: dismissed` |

All requests except pairing require `Authorization: Bearer TOKEN`. Invalid credentials and unavailable pairing codes return `401`. The receiver rejects JSON bodies above 32 KiB. Notification keys are scoped to the paired device; urgency is 0–2 and `timeout_ms` is `-1` for the desktop default, `0` for no timeout, or a positive number. Removing a device revokes its token across restarts.

The `_localbell-v1._tcp.local.` DNS-SD service advertises the full fingerprint in TXT key `fp` and its first 16 characters in the `LocalBell-` instance name. These are discovery hints only. The pinned TLS certificate authenticates the server after an address change. Older LocalBell clients must update and pair again.
