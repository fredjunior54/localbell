//! Desktop side of the framed session protocol.

use crate::{session_frame, session_pairing};
use anyhow::{Context, Result, ensure};
use serde::{Deserialize, Serialize, de::DeserializeOwned};
use std::{future::Future, time::Duration};
use tokio::io::{AsyncRead, AsyncWrite};

const IO_TIMEOUT: Duration = Duration::from_secs(5);
const APPROVAL_TIMEOUT: Duration = Duration::from_secs(120);

#[derive(Clone, Serialize, Deserialize)]
pub struct Credential {
    pub leaf: [u8; 32],
    pub token: [u8; 32],
}

#[derive(Serialize, Deserialize)]
struct Frame<T> {
    v: u8,
    #[serde(flatten)]
    message: T,
}

#[derive(Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum DesktopMessage {
    PairStart { code: String, commitment: String },
    PairReveal { challenge: String },
    PairFinish { token: String },
    Authenticate { token: String },
    Readiness { ready: bool },
    Ack { id: u64, accepted: bool },
    Pong,
}

#[derive(Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum PhoneMessage {
    PairNonce { nonce: String },
    PairApproved,
    Paired,
    Authenticated,
    Rejected,
    Revoked,
    Ping,
    Notification { id: u64, payload: serde_json::Value },
    Dismiss { id: u64, key: String },
}

pub enum Event {
    Notification(serde_json::Value),
    Dismiss(String),
}

#[derive(Debug, PartialEq, Eq)]
pub enum Disconnect {
    EndOfStream,
    Rejected,
    Revoked,
}

/// `save` must durably record the credential before the phone can accept it. The
/// owner decides whether to retry enrollment or use that credential after an I/O failure.
pub async fn pair<S, Approve, Decision, Save>(
    stream: &mut S,
    leaf: [u8; 32],
    approve: Approve,
    save: Save,
) -> Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
    Approve: FnOnce(String) -> Decision,
    Decision: Future<Output = bool>,
    Save: FnOnce(&Credential) -> Result<()>,
{
    let code = random()?;
    let challenge = random()?;
    let commitment = session_pairing::commitment(&leaf, &code, &challenge);
    send(
        stream,
        DesktopMessage::PairStart {
            code: hex::encode(code),
            commitment: hex::encode(commitment),
        },
    )
    .await?;
    let Some(PhoneMessage::PairNonce { nonce }) = receive(stream, IO_TIMEOUT).await? else {
        anyhow::bail!("Phone did not start enrollment");
    };
    let nonce = decode(&nonce)?;
    send(
        stream,
        DesktopMessage::PairReveal {
            challenge: hex::encode(challenge),
        },
    )
    .await?;
    let comparison = session_pairing::comparison(&leaf, &code, &nonce, &challenge);
    tokio::time::timeout(APPROVAL_TIMEOUT, async {
        tokio::try_join!(
            async {
                ensure!(approve(comparison).await, "Desktop declined enrollment");
                Ok(())
            },
            async {
                ensure!(
                    matches!(
                        receive(stream, APPROVAL_TIMEOUT).await?,
                        Some(PhoneMessage::PairApproved)
                    ),
                    "Phone declined enrollment"
                );
                Ok(())
            },
        )?;
        Ok::<_, anyhow::Error>(())
    })
    .await
    .context("Enrollment approval expired")??;
    let credential = Credential {
        leaf,
        token: random()?,
    };
    save(&credential)?;
    send(
        stream,
        DesktopMessage::PairFinish {
            token: hex::encode(credential.token),
        },
    )
    .await?;
    ensure!(
        matches!(
            receive(stream, IO_TIMEOUT).await?,
            Some(PhoneMessage::Paired)
        ),
        "Phone did not complete enrollment"
    );
    Ok(())
}

/// The stream must already have verified the exact leaf in `credential`.
pub async fn forward<S, Deliver, Delivery>(
    stream: &mut S,
    credential: &Credential,
    ready: bool,
    mut deliver: Deliver,
) -> Result<Disconnect>
where
    S: AsyncRead + AsyncWrite + Unpin,
    Deliver: FnMut(Event) -> Delivery,
    Delivery: Future<Output = bool>,
{
    send(
        stream,
        DesktopMessage::Authenticate {
            token: hex::encode(credential.token),
        },
    )
    .await?;
    match receive(stream, IO_TIMEOUT).await? {
        Some(PhoneMessage::Authenticated) => (),
        Some(PhoneMessage::Rejected) => return Ok(Disconnect::Rejected),
        _ => anyhow::bail!("Unexpected authentication response"),
    }
    send(stream, DesktopMessage::Readiness { ready }).await?;
    let mut previous = 0;
    while let Some(message) = receive(stream, Duration::MAX).await? {
        let (id, event) = match message {
            PhoneMessage::Ping => {
                send(stream, DesktopMessage::Pong).await?;
                continue;
            }
            PhoneMessage::Notification { id, payload } => (id, Event::Notification(payload)),
            PhoneMessage::Dismiss { id, key } => (id, Event::Dismiss(key)),
            PhoneMessage::Revoked => return Ok(Disconnect::Revoked),
            _ => anyhow::bail!("Unexpected session message"),
        };
        ensure!(
            id > previous && id <= i64::MAX as u64,
            "Invalid session request ID"
        );
        previous = id;
        let accepted = ready
            && tokio::time::timeout(IO_TIMEOUT, deliver(event))
                .await
                .unwrap_or(false);
        send(stream, DesktopMessage::Ack { id, accepted }).await?;
    }
    Ok(Disconnect::EndOfStream)
}

fn random() -> Result<[u8; 32]> {
    let mut bytes = [0; 32];
    getrandom::fill(&mut bytes)
        .map_err(|_| anyhow::anyhow!("Could not obtain secure random bytes"))?;
    Ok(bytes)
}

fn decode(value: &str) -> Result<[u8; 32]> {
    ensure!(value.len() == 64, "Invalid session field length");
    let mut bytes = [0; 32];
    hex::decode_to_slice(value, &mut bytes).context("Invalid session field encoding")?;
    Ok(bytes)
}

async fn send(stream: &mut (impl AsyncWrite + Unpin), message: DesktopMessage) -> Result<()> {
    let bytes = serde_json::to_vec(&Frame { v: 1, message })?;
    tokio::time::timeout(IO_TIMEOUT, session_frame::write(stream, &bytes)).await??;
    Ok(())
}

async fn receive<T: DeserializeOwned>(
    stream: &mut (impl AsyncRead + Unpin),
    timeout: Duration,
) -> Result<Option<T>> {
    let data = if timeout == Duration::MAX {
        session_frame::read(stream).await?
    } else {
        tokio::time::timeout(timeout, session_frame::read(stream)).await??
    };
    data.map(|data| {
        // Do not include untrusted payloads in errors that may reach logs.
        let frame: Frame<T> = serde_json::from_slice(&data)
            .map_err(|_| anyhow::anyhow!("Malformed session message"))?;
        ensure!(frame.v == 1, "Unsupported session protocol version");
        Ok(frame.message)
    })
    .transpose()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    async fn phone(stream: &mut tokio::io::DuplexStream, message: PhoneMessage) -> Result<()> {
        let bytes = serde_json::to_vec(&Frame { v: 1, message })?;
        session_frame::write(stream, &bytes).await?;
        Ok(())
    }

    #[tokio::test]
    async fn enrolls_then_authenticates_and_acknowledges() -> Result<()> {
        let (mut desktop, mut phone_stream) = tokio::io::duplex(2048);
        let leaf = [1; 32];
        let enroll_phone = async {
            let Some(DesktopMessage::PairStart { code, commitment }) =
                receive(&mut phone_stream, IO_TIMEOUT).await?
            else {
                anyhow::bail!("Missing enrollment greeting");
            };
            let nonce = [2; 32];
            phone(
                &mut phone_stream,
                PhoneMessage::PairNonce {
                    nonce: hex::encode(nonce),
                },
            )
            .await?;
            let Some(DesktopMessage::PairReveal { challenge }) =
                receive(&mut phone_stream, IO_TIMEOUT).await?
            else {
                anyhow::bail!("Missing reveal");
            };
            assert_eq!(
                session_pairing::commitment(&leaf, &decode(&code)?, &decode(&challenge)?),
                decode(&commitment)?
            );
            phone(&mut phone_stream, PhoneMessage::PairApproved).await?;
            let Some(DesktopMessage::PairFinish { token }) =
                receive(&mut phone_stream, IO_TIMEOUT).await?
            else {
                anyhow::bail!("Missing new credential");
            };
            phone(&mut phone_stream, PhoneMessage::Paired).await?;
            Ok::<_, anyhow::Error>(token)
        };
        let mut saved = None;
        let enroll_desktop = pair(
            &mut desktop,
            leaf,
            |number| async move { number.len() == 6 },
            |credential| {
                saved = Some(credential.clone());
                Ok(())
            },
        );
        let (_, token) = tokio::try_join!(enroll_desktop, enroll_phone)?;
        let saved = saved.context("Missing saved credential")?;
        let (mut desktop, mut phone_stream) = tokio::io::duplex(2048);
        let notify = async {
            assert!(
                matches!(receive(&mut phone_stream, IO_TIMEOUT).await?, Some(DesktopMessage::Authenticate { token: ref offered }) if offered == &token)
            );
            phone(&mut phone_stream, PhoneMessage::Authenticated).await?;
            assert!(matches!(
                receive(&mut phone_stream, IO_TIMEOUT).await?,
                Some(DesktopMessage::Readiness { ready: true })
            ));
            phone(
                &mut phone_stream,
                PhoneMessage::Notification {
                    id: 1,
                    payload: json!({"key":"demo"}),
                },
            )
            .await?;
            assert!(matches!(
                receive(&mut phone_stream, IO_TIMEOUT).await?,
                Some(DesktopMessage::Ack {
                    id: 1,
                    accepted: true
                })
            ));
            phone(&mut phone_stream, PhoneMessage::Revoked).await?;
            Ok::<_, anyhow::Error>(())
        };
        let delivery = forward(&mut desktop, &saved, true, |event| async move {
            matches!(event, Event::Notification(payload) if payload["key"] == "demo")
        });
        let (result, ()) = tokio::try_join!(delivery, notify)?;
        assert_eq!(result, Disconnect::Revoked);
        Ok(())
    }
}
