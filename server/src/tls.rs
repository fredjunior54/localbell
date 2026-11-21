use crate::state::{digest, save};
use anyhow::{Context, Result};
use axum_server::tls_rustls::RustlsConfig;
use std::{fs, path::Path};

pub async fn load(dir: &Path) -> Result<(RustlsConfig, String)> {
    let path = dir.join("identity.pem");
    let pem = match fs::read(&path) {
        Ok(pem) => pem,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            let pair = rcgen::generate_simple_self_signed(vec!["localbell.local".into()])?;
            let pem = (pair.cert.pem() + &pair.signing_key.serialize_pem()).into_bytes();
            save(&path, &pem)?;
            pem
        }
        Err(e) => return Err(e).context("Open desktop identity"),
    };
    use rustls::pki_types::{CertificateDer, pem::PemObject};
    let leaf = CertificateDer::from_pem_slice(&pem)
        .context("Invalid desktop certificate; restore the identity or remove it and pair again")?;
    let hash = digest(leaf.as_ref());
    let config = RustlsConfig::from_pem(pem.clone(), pem)
        .await
        .context("Invalid desktop TLS identity")?;
    Ok((config, hash))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn identity_is_stable_and_rejects_corruption() -> Result<()> {
        let _ = rustls::crypto::ring::default_provider().install_default();
        let a = tempfile::tempdir()?;
        let b = tempfile::tempdir()?;
        let (_, original) = load(a.path()).await?;
        assert_eq!(load(a.path()).await?.1, original);
        assert_ne!(load(b.path()).await?.1, original);
        fs::write(a.path().join("identity.pem"), "invalid")?;
        assert!(load(a.path()).await.is_err());
        assert_eq!(
            fs::read_to_string(a.path().join("identity.pem"))?,
            "invalid"
        );
        Ok(())
    }
}
