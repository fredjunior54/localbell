# LocalBell HTTPS Protocol

The receiver listens on private IPv4, port 19420 by default. The explicit `--listen=IP` override binds only a locally assigned address in `100.64.0.0/10` for overlays such as Tailscale. There is no HTTP fallback, browser setup, redirect, or public discovery service.

## Pairing v2

Start with a credential-free TLS probe. Android reads the actual leaf certificate from that connection and pins it on separate HTTPS requests to the same endpoint. The probe deliberately aborts the TLS handshake after observing the leaf; it sends no HTTP data. The phone generates an independent random 32-byte request secret and, after approval, a random 32-byte bearer token. All credential and nonce values use 64 lowercase hex characters.

| Request to `/v2/pair` | JSON body | Success response |
|---|---|---|
| `POST` | `code`, `name` | `200`, receiver-generated `nonce` |
| `PATCH` | `code` | `200`, boolean `approved` |
| `PUT` | `code`, `token` | `200`, `status: paired` |
| `DELETE` | `code` | `200`, `status: cancelled` |

On both devices, calculate SHA-256 over the ASCII string `localbell-pair-v2:<leaf SHA-256 hex>:<code>:<nonce>`. Map the low six bits of each of the first ten digest bytes to the corresponding index of the fixed 64-word list in the Rust and Kotlin implementations. Join those ten words with spaces. The phrase is never returned by the API. Accept on Linux only if all ten words are identical and in the same order on Android. The request name is an unverified label. Do not approve unexpected requests.

Test vector: a leaf fingerprint of 64 `0` characters, a code of 64 `1` characters, and a nonce of 64 `2` characters produce `eagle creek iris rose pine moon reed mint grove basil`.

One request can be active for at most two minutes. Cancel, reject, expiry, or quitting the receiver invalidates an incomplete request. `409` means occupied or already paired; `410` means the request is no longer active; unapproved completion returns `403`. Approval does not activate a token until the receiver atomically records its digest. Repeating completion with that same already-persisted token is safe, even after restart. Android retains an encrypted pending completion record so reconnecting at the same address can recover from a lost final response, then replaces that record with a saved pairing only after confirmation. Remove the old phone on Linux before pairing another. New pairings require current clients on both devices; existing saved pairs continue to use delivery v1.

## Delivery v1

| Request | JSON body | Success response |
|---|---|---|
| `GET /v1/health` | None | `200`, `status: ready` |
| `POST /v1/notification` | `key`, `app_name`, `title`, `message`, `urgency`, `timeout_ms` | `200`, `status: sent` |
| `DELETE /v1/notification` | `key` | `200`, `status: dismissed` |

All delivery requests require `Authorization: Bearer TOKEN`; invalid credentials return `401`. JSON bodies are limited to 32 KiB. Notification keys are scoped to the paired device. Urgency is 0–2; `timeout_ms` is `-1` for the desktop default, `0` for no timeout, or a positive number. Removal revokes the token across restarts. Identity and token digests remain under `$XDG_CONFIG_HOME/localbell/` (normally `~/.config/localbell/`).

LAN mDNS advertises the `_localbell-v1._tcp.local.` service with fingerprint TXT key `fp`. This is only a hint for rediscovery: the app still verifies the saved TLS pin. Explicit overlay addresses and hostnames use their configured endpoint without mDNS fallback. The override does not advertise the overlay address via LAN mDNS.
