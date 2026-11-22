# LocalBell TLS Session Protocol

Android listens on IPv4 (default port `19420`) and Linux initiates and maintains the connection. The old HTTPS transport is not supported. Update both apps and pair again when migrating.

## Framing and identity

Inside TLS, each frame starts with a four-byte unsigned big-endian payload length, followed by exactly that many bytes. The length excludes the header and must be between 1 and 32,768 bytes inclusive. Session messages are UTF-8 JSON objects. The framing layer does not interpret the payload. Receivers reject invalid lengths before allocation; end of stream between frames is a normal disconnect, but a partial header or payload is a protocol failure. After an error or timeout, close the connection rather than try to resynchronize. Use one writer per connection so concurrent messages cannot interleave. The shared byte-level fixture is `tests/fixtures/session-frames.hex`.

The reverse transport uses TLS 1.3 with ALPN `localbell/1`. Android generates an EC P-256 server identity in Android Keystore. An enrolled Linux client verifies the SHA-256 digest of the exact TLS leaf and checks the TLS handshake signature; a valid TLS session alone does not authorize notification delivery. The listener permits RFC 1918, IPv4 link-local, and `100.64.0.0/10` peers before TLS.

For enrollment, the desktop commits to its 32-byte challenge before receiving the phone's nonce. All transcript fields below are raw 32-byte values; `leaf` is the SHA-256 digest of the actual phone TLS leaf, and `code`, `nonce`, and `challenge` are fresh random values. `||` means byte concatenation and `NUL` means one zero byte.

- Commitment: `SHA-256(ASCII("localbell-session-commit-v1") || NUL || leaf || code || challenge)`.
- Comparison digest: `SHA-256(ASCII("localbell-session-compare-v1") || NUL || leaf || code || nonce || challenge)`.

Interpret the first four comparison bytes as an unsigned big-endian number, reduce it modulo 1,000,000, and display it as six decimal digits. Keep the connection in enrollment mode until the commitment is verified and both users explicitly confirm. An enrollment connection must not receive notifications or an existing saved credential.

Each session object has integer `v: 1` and string `type`. Enrollment and authenticated delivery use separate TLS connections:

| Step | Desktop → Android | Android → Desktop |
| --- | --- | --- |
| Start enrollment | `pair_start` with `code`, `commitment` | `pair_nonce` with `nonce` |
| Verify comparison | `pair_reveal` with `challenge` | `pair_approved` after local confirmation |
| Persist new credential | `pair_finish` with a new `token`, after desktop confirmation | `paired` after writing the token digest |
| Reconnect with the saved certificate pin | `authenticate` with `token` | `authenticated` or `rejected` |
| Publish desktop readiness | `readiness` with boolean `ready` | — |
| Deliver or dismiss | `ack` with the same `id` and boolean `accepted` | `notification` with `id` and `payload`, or `dismiss` with `id` and `key` |
| Revoke | — | `revoked`, then close |

The binary fields in enrollment and authentication use 64 lowercase hex characters. Linux records the new leaf pin and token before sending `pair_finish`. Android records the token digest before returning `paired` and does not overwrite an existing enrollment. A rejected enrollment can return `rejected` at any step. Request IDs start at 1 and increase within a session, up to `2^63-1`. The desktop must not acknowledge a repeated or decreasing ID as accepted. Notification payloads contain `key`, `app_name`, `title`, `message`, `urgency` (0–2), `timeout_ms` (`-1`, `0`, or a positive number), and optional `icon_rgba` (standard base64 of 48×48×4 straight-alpha RGBA bytes). Android emits normal urgency (`1`) and desktop-default expiration (`-1`) for both forwarded and test notifications. Linux acknowledges only D-Bus acceptance, not banner display. Android does not replay notifications across sessions. Android unpair removes the token digest and closes the active session; the former token is rejected on reconnect. Do not log frame contents or pairing transcripts.
