//! Length-delimited payloads for the reverse TLS session.

use std::io::{self, ErrorKind};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const MAX_PAYLOAD: usize = 32 * 1024;

/// Returns `None` only at a frame boundary. Close the session after any error or timeout.
pub async fn read(reader: &mut (impl AsyncRead + Unpin)) -> io::Result<Option<Vec<u8>>> {
    let mut header = [0; 4];
    if reader.read(&mut header[..1]).await? == 0 {
        return Ok(None);
    }
    reader.read_exact(&mut header[1..]).await?;
    let len = u32::from_be_bytes(header) as usize;
    if !(1..=MAX_PAYLOAD).contains(&len) {
        return Err(io::Error::new(
            ErrorKind::InvalidData,
            "Invalid frame length",
        ));
    }
    let mut payload = vec![0; len];
    reader.read_exact(&mut payload).await?;
    Ok(Some(payload))
}

/// The caller must serialize writes and close the session if a write fails or times out.
pub async fn write(writer: &mut (impl AsyncWrite + Unpin), payload: &[u8]) -> io::Result<()> {
    if !(1..=MAX_PAYLOAD).contains(&payload.len()) {
        return Err(io::Error::new(
            ErrorKind::InvalidInput,
            "Invalid frame length",
        ));
    }
    writer
        .write_all(&(payload.len() as u32).to_be_bytes())
        .await?;
    writer.write_all(payload).await?;
    writer.flush().await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn matches_shared_fixture() -> anyhow::Result<()> {
        let expected = hex::decode(include_str!("../../tests/fixtures/session-frames.hex").trim())?;
        let payloads = [br#"{"v":1,"type":"ping"}"#, br#"{"v":1,"type":"pong"}"#];
        let mut output = Vec::new();
        let mut input = expected.as_slice();
        for payload in payloads {
            write(&mut output, payload).await?;
            assert_eq!(read(&mut input).await?.as_deref(), Some(payload.as_slice()));
        }
        assert!(read(&mut input).await?.is_none());
        assert_eq!(output, expected);
        Ok(())
    }

    #[tokio::test]
    async fn rejects_invalid_lengths_and_truncated_frames() {
        for header in [0_u32, MAX_PAYLOAD as u32 + 1, u32::MAX] {
            assert_eq!(
                read(&mut header.to_be_bytes().as_slice())
                    .await
                    .unwrap_err()
                    .kind(),
                ErrorKind::InvalidData
            );
        }
        for partial in [&[0, 0, 0][..], &[0, 0, 0, 2, 1][..]] {
            assert_eq!(
                read(&mut &*partial).await.unwrap_err().kind(),
                ErrorKind::UnexpectedEof
            );
        }
        let mut output = Vec::new();
        assert!(write(&mut output, &[]).await.is_err());
        assert!(output.is_empty());
    }
}
