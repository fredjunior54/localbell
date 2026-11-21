use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs,
    io::Write,
    os::unix::fs::OpenOptionsExt,
    path::{Path, PathBuf},
    time::{Duration, Instant},
};

#[derive(Clone, Deserialize, Serialize)]
pub struct Device {
    pub id: String,
    pub name: String,
    digest: String,
}

pub struct Pairing {
    pub code: String,
    pub until: Instant,
}

pub struct State {
    path: PathBuf,
    pub devices: Vec<Device>,
    pub pairing: Option<Pairing>,
}

pub fn random() -> Result<String> {
    let mut bytes = [0; 32];
    getrandom::fill(&mut bytes).map_err(|err| anyhow::anyhow!("OS randomness: {err}"))?;
    Ok(hex::encode(bytes))
}

pub fn digest(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
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

impl State {
    pub fn load(path: PathBuf) -> Result<Self> {
        let devices = match fs::read(&path) {
            Ok(data) => serde_json::from_slice(&data).context("Read paired devices")?,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Vec::new(),
            Err(e) => return Err(e).context("Open paired devices"),
        };
        Ok(Self {
            path,
            devices,
            pairing: None,
        })
    }

    pub fn begin(&mut self) -> Result<()> {
        self.pairing = Some(Pairing {
            code: random()?,
            until: Instant::now() + Duration::from_secs(120),
        });
        Ok(())
    }

    pub fn exchange(&mut self, code: &str, name: &str) -> Result<Option<String>> {
        let Some(pair) = &self.pairing else {
            return Ok(None);
        };
        if Instant::now() >= pair.until || digest(code.as_bytes()) != digest(pair.code.as_bytes()) {
            return Ok(None);
        }
        let name = name.trim();
        if name.is_empty() || name.chars().count() > 64 || name.chars().any(char::is_control) {
            bail!("Invalid device name");
        }
        let token = random()?;
        let mut devices = self.devices.clone();
        devices.push(Device {
            id: random()?,
            name: name.into(),
            digest: digest(token.as_bytes()),
        });
        self.persist(devices)?;
        self.pairing = None;
        Ok(Some(token))
    }

    pub fn device(&self, token: &str) -> Option<&Device> {
        let hash = digest(token.as_bytes());
        self.devices.iter().find(|device| device.digest == hash)
    }

    pub fn revoke(&mut self, id: &str) -> Result<()> {
        self.persist(
            self.devices
                .iter()
                .filter(|device| device.id != id)
                .cloned()
                .collect(),
        )
    }

    fn persist(&mut self, devices: Vec<Device>) -> Result<()> {
        save(&self.path, &serde_json::to_vec(&devices)?)?;
        self.devices = devices;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, Mutex};

    #[test]
    fn code_is_single_use_and_revocation_survives_restart() -> Result<()> {
        let dir = tempfile::tempdir()?;
        let path = dir.path().join("devices.json");
        let mut state = State::load(path.clone())?;
        state.begin()?;
        let code = state.pairing.as_ref().unwrap().code.clone();
        let state = Arc::new(Mutex::new(state));
        let tokens = std::thread::scope(|scope| {
            let jobs: Vec<_> = (0..4)
                .map(|_| scope.spawn(|| state.lock().unwrap().exchange(&code, "Phone").unwrap()))
                .collect();
            jobs.into_iter()
                .filter_map(|job| job.join().unwrap())
                .collect::<Vec<_>>()
        });
        assert_eq!(tokens.len(), 1);
        assert!(!fs::read_to_string(&path)?.contains(&tokens[0]));
        let mut state = State::load(path.clone())?;
        let id = state.device(&tokens[0]).unwrap().id.clone();
        state.revoke(&id)?;
        assert!(State::load(path)?.device(&tokens[0]).is_none());
        Ok(())
    }

    #[test]
    fn canceled_and_expired_codes_are_rejected() -> Result<()> {
        let dir = tempfile::tempdir()?;
        let mut state = State::load(dir.path().join("devices.json"))?;
        state.begin()?;
        let pair = state.pairing.as_mut().unwrap();
        pair.until = Instant::now();
        let code = pair.code.clone();
        assert!(state.exchange(&code, "Phone")?.is_none());
        state.begin()?;
        let code = state.pairing.take().unwrap().code;
        assert!(state.exchange(&code, "Phone")?.is_none());
        Ok(())
    }
}
