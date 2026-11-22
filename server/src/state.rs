use anyhow::{Context, Result};
use std::{fs, io::Write, os::unix::fs::OpenOptionsExt, path::Path};

pub fn random() -> Result<String> {
    let mut bytes = [0; 32];
    getrandom::fill(&mut bytes).map_err(|err| anyhow::anyhow!("OS randomness: {err}"))?;
    Ok(hex::encode(bytes))
}

pub fn save(path: &Path, bytes: &[u8]) -> Result<()> {
    let tmp = path.with_extension(random()?);
    let result = (|| -> Result<()> {
        let mut f = fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&tmp)?;
        f.write_all(bytes)?;
        f.sync_all()?;
        fs::rename(&tmp, path)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(tmp);
    }
    result.with_context(|| format!("Save {}", path.display()))
}
