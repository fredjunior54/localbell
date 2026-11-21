# LocalBell HTTPS Protocol

The receiver binds `0.0.0.0:19420` by default and rejects TCP peers outside private, link-local, and `100.64.0.0/10` IPv4 ranges before TLS. The desktop can persist a specific bind address and port in `listener.json`. The `--listen=IP` and `--port=PORT` CLI options override saved values for the session. There is no HTTP fallback, browser setup, redirect, or public discovery service.

## Pairing v3

Start with a credential-free TLS probe. Android reads the actual leaf certificate from that connection and pins it on separate HTTPS requests to the same endpoint. The probe deliberately aborts the TLS handshake after observing the leaf; it sends no HTTP data. The phone generates an independent random 32-byte request secret and, after approval, a random 32-byte bearer token. All credential and nonce values use 64 lowercase hex characters.

| Request to `/v3/pair` | JSON body | Success response |
|---|---|---|
| `POST` | `code`, `name`, `commitment` | `200`, receiver-generated `nonce` |
| `PATCH` | `code`, `challenge` | `200`, boolean `approved` |
| `PUT` | `code`, `token` | `200`, `status: paired` |
| `DELETE` | `code` | `200`, `status: cancelled` |

The phone samples a random 32-byte challenge and commits to it with SHA-256 over ASCII `localbell-commit-v3:<leaf SHA-256 hex>:<code>:<challenge>`. It sends the commitment before receiving the receiver's random 32-byte nonce. It reveals the challenge on PATCH; the receiver checks the commitment against its own leaf fingerprint before enabling desktop approval. Reject invalid reveals with `403`.

Both sides hash ASCII `localbell-compare-v3:<leaf SHA-256 hex>:<code>:<nonce>:<challenge>`, read the first four digest bytes as an unsigned big-endian integer, and reduce modulo 1,000,000. Display six decimal digits with leading zeros. The number is never returned by the API. Confirm it on Android and approve on Linux only if both displays match. Android requires local confirmation as well as remote approval before registering a token; an unverified peer claiming approval cannot trigger automatic trust. Do not approve unexpected requests.

Test vector: leaf fingerprint = 64 `0` characters, code = 64 `1` characters, nonce = 64 `2` characters, challenge = `0000000000000000000000000000000000000000000000000000000000000000`. Commitment = `e4a52592cf444899d4d5a8dd5b0fa2964e6908a323b8b43720ffb2dcffdce2d0`; comparison number = `011659`.

One request can be active for at most two minutes. The receiver allows five new requests per rolling two-minute window; `429` means wait and retry. Cancel, reject, expiry, or quitting the receiver invalidates an incomplete request. `409` means occupied or already paired; `410` means the request is no longer active; unapproved completion returns `403`. Approval does not activate a token until the receiver atomically records its digest. Repeating completion with that same already-persisted token is safe, even after restart. Android retains an encrypted pending completion record so reconnecting at the same address can recover from a lost final response, then replaces that record with a saved pairing only after confirmation. Remove the old phone on Linux before pairing another. New pairings require current clients on both devices; existing saved pairs continue to use delivery v1.

## Delivery v1

| Request | JSON body | Success response |
|---|---|---|
| `GET /v1/health` | None | `200`, `status: ready` |
| `POST /v1/notification` | `key`, `app_name`, `title`, `message`, `urgency`, `timeout_ms`, optional `icon_rgba` | `200`, `status: sent` |
| `DELETE /v1/notification` | `key` | `200`, `status: dismissed` |

Android treats the current pairing as ready only after pinned, authenticated health returns HTTP 200 with the JSON string `status: ready`. The app pauses forwarding when it next detects an unhealthy receiver and retains the forwarding preference across reconnects.

All delivery requests require `Authorization: Bearer TOKEN`; invalid credentials return `401`. JSON bodies are limited to 32 KiB. Notification keys are scoped to the paired device. Urgency is 0–2; `timeout_ms` is `-1` for the desktop default, `0` for no timeout, or a positive number. Delivery and dismissal return `503` if the desktop notification service rejects the operation or is unavailable, or `504` if the D-Bus call times out. The receiver acknowledges a new notification only after D-Bus accepts it. Removal revokes the token across restarts. Identity and token digests remain under `$XDG_CONFIG_HOME/localbell/` (normally `~/.config/localbell/`).

LAN mDNS advertises the `_localbell-v1._tcp.local.` service with fingerprint TXT key `fp`. This is only a hint for rediscovery: the app still verifies the saved TLS pin. Explicit overlay addresses and hostnames use their configured endpoint without mDNS fallback. The override does not advertise the overlay address via LAN mDNS.

An optional `icon_rgba` field contains standard base64 of exactly 48 × 48 × 4 bytes, in row-major RGBA order with straight alpha. Android omits it when unavailable or if it would exceed the existing 32 KiB request limit. The receiver ignores invalid icon data and uses a generic icon instead. Valid artwork is passed directly to the desktop notification daemon as a D-Bus `image-data` hint; no icon is fetched or saved.

## Reverse TLS session framing (not active)

The following framing is for the planned transport in which Android listens and Linux connects. The HTTPS endpoints above remain the active protocol until the new pairing, session, and delivery paths are integrated.

Inside TLS, each frame starts with a four-byte unsigned big-endian payload length, followed by exactly that many bytes. The length excludes the header and must be between 1 and 32,768 bytes inclusive. Session messages are UTF-8 JSON objects. The framing layer does not interpret the payload. Receivers reject invalid lengths before allocation; end of stream between frames is a normal disconnect, but a partial header or payload is a protocol failure. After an error or timeout, close the connection rather than try to resynchronize. Use one writer per connection so concurrent messages cannot interleave. The shared byte-level fixture is `tests/fixtures/session-frames.hex`.
