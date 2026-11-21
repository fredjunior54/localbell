//! TLS transport for previously paired phones.

use rustls::{
    CertificateError, ClientConfig, DigitallySignedStruct, Error, SignatureScheme,
    client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier},
    crypto::{WebPkiSupportedAlgorithms, verify_tls12_signature, verify_tls13_signature},
    pki_types::{CertificateDer, ServerName, UnixTime},
};
use sha2::{Digest, Sha256};
use std::{net::SocketAddrV4, sync::Arc, time::Duration};
use tokio::net::TcpStream;
use tokio_rustls::{TlsConnector, client::TlsStream};

pub const ALPN: &[u8] = b"localbell/1";

#[derive(Debug)]
struct PinnedLeaf {
    fingerprint: [u8; 32],
    algorithms: WebPkiSupportedAlgorithms,
}

impl ServerCertVerifier for PinnedLeaf {
    fn verify_server_cert(
        &self,
        leaf: &CertificateDer<'_>,
        _: &[CertificateDer<'_>],
        _: &ServerName<'_>,
        _: &[u8],
        _: UnixTime,
    ) -> Result<ServerCertVerified, Error> {
        if self.fingerprint.as_slice() != Sha256::digest(leaf.as_ref()).as_slice() {
            return Err(CertificateError::ApplicationVerificationFailure.into());
        }
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        signature: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        verify_tls12_signature(message, cert, signature, &self.algorithms)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        signature: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        verify_tls13_signature(message, cert, signature, &self.algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.algorithms.supported_schemes()
    }
}

/// Connect to a prevalidated IPv4 address and verify the exact enrolled TLS leaf.
/// The caller must still authenticate the desktop before exchanging notifications.
pub async fn connect(
    address: SocketAddrV4,
    fingerprint: [u8; 32],
) -> anyhow::Result<TlsStream<TcpStream>> {
    use anyhow::Context;
    let provider = rustls::crypto::ring::default_provider();
    let verifier = PinnedLeaf {
        fingerprint,
        algorithms: provider.signature_verification_algorithms,
    };
    let mut config = ClientConfig::builder_with_provider(Arc::new(provider))
        .with_protocol_versions(&[&rustls::version::TLS13])?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(verifier))
        .with_no_client_auth();
    config.alpn_protocols.push(ALPN.to_vec());
    let connection = tokio::time::timeout(Duration::from_secs(5), async {
        let tcp = TcpStream::connect(address).await?;
        tcp.set_nodelay(true)?;
        TlsConnector::from(Arc::new(config))
            .connect(ServerName::IpAddress((*address.ip()).into()), tcp)
            .await
    })
    .await
    .context("Phone connection timed out")??;
    anyhow::ensure!(
        connection.get_ref().1.alpn_protocol() == Some(ALPN),
        "Phone did not negotiate the LocalBell protocol"
    );
    Ok(connection)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::session_frame;
    use rcgen::generate_simple_self_signed;
    use rustls::{ServerConfig, pki_types::PrivatePkcs8KeyDer};
    use tokio::net::TcpListener;
    use tokio_rustls::TlsAcceptor;

    #[tokio::test]
    async fn exchanges_frames_only_with_the_pinned_phone() -> anyhow::Result<()> {
        let phone = generate_simple_self_signed(vec!["localbell.invalid".to_owned()])?;
        let leaf = phone.cert.der().clone();
        let fingerprint = Sha256::digest(&leaf).into();
        let mut config =
            ServerConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
                .with_protocol_versions(&[&rustls::version::TLS13])?
                .with_no_client_auth()
                .with_single_cert(
                    vec![leaf],
                    PrivatePkcs8KeyDer::from(phone.signing_key.serialize_der()).into(),
                )?;
        config.alpn_protocols.push(ALPN.to_vec());
        let acceptor = TlsAcceptor::from(Arc::new(config));
        let address = SocketAddrV4::new(std::net::Ipv4Addr::LOCALHOST, 0);
        let listener = TcpListener::bind(address).await?;
        let std::net::SocketAddr::V4(address) = listener.local_addr()? else {
            unreachable!()
        };
        let server = async {
            let (socket, _) = listener.accept().await?;
            assert!(acceptor.accept(socket).await.is_err());
            let (socket, _) = listener.accept().await?;
            let mut socket = acceptor.accept(socket).await?;
            let request = session_frame::read(&mut socket).await?;
            assert_eq!(request.as_deref(), Some(b"ping".as_slice()));
            session_frame::write(&mut socket, b"pong").await?;
            Ok::<_, anyhow::Error>(())
        };
        let client = async {
            assert!(connect(address, [0; 32]).await.is_err());
            let mut socket = connect(address, fingerprint).await?;
            session_frame::write(&mut socket, b"ping").await?;
            assert_eq!(
                session_frame::read(&mut socket).await?.as_deref(),
                Some(b"pong".as_slice())
            );
            Ok::<_, anyhow::Error>(())
        };
        tokio::time::timeout(Duration::from_secs(5), async {
            tokio::try_join!(server, client)
        })
        .await??;
        Ok(())
    }
}
