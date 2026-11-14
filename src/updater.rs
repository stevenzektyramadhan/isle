use std::io::Read;
use std::path::Path;
use std::time::Duration;

use serde::Deserialize;

const MANIFEST_URL: &str =
    "https://github.com/stevenzektyramadhan/isle/releases/latest/download/latest.json";
const INSTALLER_URL: &str =
    "https://github.com/stevenzektyramadhan/isle/releases/latest/download/Isle-Setup-x64.exe";
const CHECK_INTERVAL_SECS: u64 = 24 * 60 * 60;

#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
pub struct UpdateManifest {
    pub version: String,
    pub sha256: String,
}

#[derive(Debug, thiserror::Error)]
pub enum UpdateError {
    #[error("update request failed: {0}")]
    Http(#[from] reqwest::Error),
    #[error("update manifest was invalid: {0}")]
    Json(#[from] serde_json::Error),
    #[error("update file I/O failed: {0}")]
    Io(#[from] std::io::Error),
    #[error("downloaded update failed its checksum")]
    ChecksumMismatch,
    #[error("update installer could not start: {0}")]
    Spawn(std::io::Error),
}

pub fn check_due(last_check_secs: Option<u64>, now_secs: u64) -> bool {
    match last_check_secs {
        None => true,
        Some(last) if now_secs < last => true,
        Some(last) => now_secs - last >= CHECK_INTERVAL_SECS,
    }
}

pub fn is_installed() -> bool {
    std::env::current_exe()
        .ok()
        .and_then(|exe| exe.parent().map(installed_marker_exists))
        .unwrap_or(false)
}

fn installed_marker_exists(directory: &Path) -> bool {
    directory.join("unins000.exe").is_file()
}

pub fn check_for_update(current_version: &str) -> Result<Option<UpdateManifest>, UpdateError> {
    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(15))
        .user_agent("isle-updater")
        .build()?;
    let manifest: UpdateManifest = serde_json::from_str(
        &client
            .get(MANIFEST_URL)
            .send()?
            .error_for_status()?
            .text()?,
    )?;
    Ok(is_newer_version(&manifest.version, current_version).then_some(manifest))
}

pub fn download_and_install(manifest: &UpdateManifest) -> Result<(), UpdateError> {
    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(180))
        .user_agent("isle-updater")
        .build()?;
    let mut response = client.get(INSTALLER_URL).send()?.error_for_status()?;
    let mut bytes = Vec::new();
    response.read_to_end(&mut bytes)?;
    if !sha256_hex(&bytes).eq_ignore_ascii_case(&manifest.sha256) {
        return Err(UpdateError::ChecksumMismatch);
    }
    let directory = std::env::temp_dir().join("isle-update");
    std::fs::create_dir_all(&directory)?;
    let installer = directory.join("Isle-Setup-x64.exe");
    std::fs::write(&installer, &bytes)?;
    std::process::Command::new(installer)
        .args([
            "/VERYSILENT",
            "/SUPPRESSMSGBOXES",
            "/CLOSEAPPLICATIONS",
            "/NORESTART",
        ])
        .spawn()
        .map_err(UpdateError::Spawn)?;
    Ok(())
}

pub fn is_newer_version(remote: &str, current: &str) -> bool {
    parse_version(remote) > parse_version(current)
}

fn parse_version(version: &str) -> (u64, u64, u64) {
    let mut parts = version
        .trim()
        .trim_start_matches('v')
        .split(['.', '-', '+'])
        .map(|part| part.parse::<u64>().unwrap_or(0));
    (
        parts.next().unwrap_or(0),
        parts.next().unwrap_or(0),
        parts.next().unwrap_or(0),
    )
}

fn sha256_hex(bytes: &[u8]) -> String {
    use sha2::{Digest, Sha256};
    use std::fmt::Write as _;

    let digest = Sha256::digest(bytes);
    let mut hex = String::with_capacity(64);
    for byte in digest {
        let _ = write!(hex, "{byte:02x}");
    }
    hex
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compares_versions_numerically() {
        assert!(is_newer_version("0.2.0", "0.1.9"));
        assert!(is_newer_version("v1.0.1", "1.0.0"));
        assert!(!is_newer_version("0.1.0", "0.1.0"));
        assert!(!is_newer_version("v1.0.0", "1.0.0"));
        assert!(!is_newer_version("0.1.0", "0.2.0"));
    }

    #[test]
    fn checks_at_most_once_per_day() {
        assert!(check_due(None, 1_000));
        assert!(!check_due(Some(1_000), 1_000 + 86_399));
        assert!(check_due(Some(1_000), 1_000 + 86_400));
        assert!(check_due(Some(5_000), 1_000));
    }

    #[test]
    fn hashes_the_update_payload() {
        assert_eq!(
            sha256_hex(b"abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }

    #[test]
    fn detects_the_uninstaller_marker() {
        let directory =
            std::env::temp_dir().join(format!("isle-updater-marker-{}", std::process::id()));
        std::fs::create_dir_all(&directory).expect("test directory should be created");

        assert!(!installed_marker_exists(&directory));
        std::fs::write(directory.join("unins000.exe"), b"")
            .expect("test uninstaller should be written");
        assert!(installed_marker_exists(&directory));

        std::fs::remove_dir_all(&directory).expect("test directory should be removed");
    }
}
