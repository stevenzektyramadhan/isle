use std::io::Read;
use std::path::Path;
use std::time::Duration;

use serde::Deserialize;

const MANIFEST_URL: &str =
    "https://github.com/stevenzektyramadhan/isle/releases/latest/download/latest.json";
const MANIFEST_SIGNATURE_URL: &str =
    "https://github.com/stevenzektyramadhan/isle/releases/latest/download/latest.json.sig";
const INSTALLER_URL: &str =
    "https://github.com/stevenzektyramadhan/isle/releases/latest/download/Isle-Setup-x64.exe";
const RELEASES_PAGE_URL: &str = "https://github.com/stevenzektyramadhan/isle/releases/latest";
const CHECK_INTERVAL_SECS: u64 = 24 * 60 * 60;

const UPDATE_PUBLIC_KEY_HEX: &str =
    "572f8ffdacc79e09f5e57ae04b6b0fd18ad42c2d2936b5bd63bd6f91f3e52e46";

#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
pub struct UpdateManifest {
    pub version: String,
    pub sha256: String,
}

/// A manifest whose detached signature verified against the embedded public
/// key. Only [`VerifiedUpdate::verify`] can build one, so the installer path
/// cannot be reached with a manifest that an attacker who controls the release
/// host forged.
#[derive(Clone, Debug)]
pub struct VerifiedUpdate {
    manifest: UpdateManifest,
}

impl std::ops::Deref for VerifiedUpdate {
    type Target = UpdateManifest;

    fn deref(&self) -> &Self::Target {
        &self.manifest
    }
}

impl VerifiedUpdate {
    fn verify(signed_bytes: Vec<u8>, signature: Vec<u8>) -> Result<Self, UpdateError> {
        let public_key = update_public_key().ok_or(UpdateError::BadSignature)?;
        Self::verify_with(signed_bytes, signature, &public_key)
    }

    fn verify_with(
        signed_bytes: Vec<u8>,
        signature: Vec<u8>,
        public_key: &[u8],
    ) -> Result<Self, UpdateError> {
        ring::signature::UnparsedPublicKey::new(&ring::signature::ED25519, public_key)
            .verify(&signed_bytes, &signature)
            .map_err(|_| UpdateError::BadSignature)?;
        Ok(Self {
            manifest: serde_json::from_slice(&signed_bytes)?,
        })
    }
}

#[derive(Debug, thiserror::Error)]
pub enum UpdateError {
    #[error("update request failed: {0}")]
    Http(#[from] reqwest::Error),
    #[error("update manifest was invalid: {0}")]
    Json(#[from] serde_json::Error),
    #[error("update file I/O failed: {0}")]
    Io(#[from] std::io::Error),
    #[error("update manifest failed its signature check")]
    BadSignature,
    #[error("downloaded update failed its checksum")]
    ChecksumMismatch,
    #[error("update installer could not start: {0}")]
    Spawn(std::io::Error),
    #[error("could not open the release page: {0}")]
    OpenReleasePage(std::io::Error),
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

/// Whether the running build can replace itself in place. Only the Windows
/// installer layout ships an uninstaller next to the binary, so every other
/// layout (the Linux tarball, the portable Windows ZIP) has no self-install path.
#[cfg(windows)]
pub fn self_install_supported() -> bool {
    is_installed()
}

#[cfg(not(windows))]
pub fn self_install_supported() -> bool {
    false
}

/// Released builds look for a newer version at most once a day. A self-installing
/// build applies it; a Linux tarball can only be pointed at the release page, so
/// it checks too instead of silently freezing on the version it was downloaded at.
pub fn should_check_for_updates() -> bool {
    self_install_supported() || cfg!(target_os = "linux")
}

/// Opens the release page so the user can download the newer build themselves.
/// Used where there is no self-install path.
pub fn open_release_page() -> Result<(), UpdateError> {
    let opener = if cfg!(windows) {
        "explorer"
    } else if cfg!(target_os = "macos") {
        "open"
    } else {
        "xdg-open"
    };
    std::process::Command::new(opener)
        .arg(RELEASES_PAGE_URL)
        .spawn()
        .map(|_| ())
        .map_err(UpdateError::OpenReleasePage)
}

pub fn check_for_update(current_version: &str) -> Result<Option<VerifiedUpdate>, UpdateError> {
    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(15))
        .user_agent("isle-updater")
        .build()?;
    let signed_bytes = client
        .get(MANIFEST_URL)
        .send()?
        .error_for_status()?
        .bytes()?
        .to_vec();
    let signature = decode_hex(
        &client
            .get(MANIFEST_SIGNATURE_URL)
            .send()?
            .error_for_status()?
            .text()?,
    )
    .ok_or(UpdateError::BadSignature)?;
    let update = VerifiedUpdate::verify(signed_bytes, signature)?;
    Ok(is_newer_version(&update.version, current_version).then_some(update))
}

pub fn download_and_install(update: &VerifiedUpdate) -> Result<(), UpdateError> {
    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(180))
        .user_agent("isle-updater")
        .build()?;
    let mut response = client.get(INSTALLER_URL).send()?.error_for_status()?;
    let mut bytes = Vec::new();
    response.read_to_end(&mut bytes)?;
    if !sha256_hex(&bytes).eq_ignore_ascii_case(&update.sha256) {
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

fn update_public_key() -> Option<[u8; ring::signature::ED25519_PUBLIC_KEY_LEN]> {
    decode_hex(UPDATE_PUBLIC_KEY_HEX)?.try_into().ok()
}

fn decode_hex(text: &str) -> Option<Vec<u8>> {
    let text = text.trim();
    if !text.is_ascii() || !text.len().is_multiple_of(2) {
        return None;
    }
    (0..text.len())
        .step_by(2)
        .map(|index| u8::from_str_radix(&text[index..index + 2], 16).ok())
        .collect()
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

    fn test_signer() -> (ring::signature::Ed25519KeyPair, Vec<u8>) {
        use ring::signature::KeyPair as _;

        let document =
            ring::signature::Ed25519KeyPair::generate_pkcs8(&ring::rand::SystemRandom::new())
                .expect("test key pair should be generated");
        let key_pair = ring::signature::Ed25519KeyPair::from_pkcs8(document.as_ref())
            .expect("test key pair should load");
        let public_key = key_pair.public_key().as_ref().to_vec();
        (key_pair, public_key)
    }

    fn sign(bytes: &[u8], key_pair: &ring::signature::Ed25519KeyPair) -> Vec<u8> {
        key_pair.sign(bytes).as_ref().to_vec()
    }

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

    /// The Linux tarball ships no uninstaller marker, so gating the check on
    /// "is installed" would freeze it on the downloaded version forever.
    #[cfg(target_os = "linux")]
    #[test]
    fn checks_for_updates_where_the_bundle_cannot_self_install() {
        assert!(!self_install_supported());
        assert!(should_check_for_updates());
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

    #[test]
    fn accepts_a_manifest_signed_by_its_key() {
        // Given: a manifest and the key that signs it.
        let (key_pair, public_key) = test_signer();
        let bytes = br#"{"version":"0.1.3","sha256":"ab"}"#.to_vec();

        // When: the detached signature is checked.
        let update =
            VerifiedUpdate::verify_with(bytes.clone(), sign(&bytes, &key_pair), &public_key)
                .expect("signature should verify");

        // Then: the parsed manifest is available behind the verified wrapper.
        assert_eq!(update.version, "0.1.3");
        assert_eq!(update.sha256, "ab");
    }

    #[test]
    fn rejects_a_manifest_whose_bytes_changed_after_signing() {
        // Given: a signed manifest and a version of it with a different hash.
        let (key_pair, public_key) = test_signer();
        let signed_bytes = br#"{"version":"0.1.3","sha256":"ab"}"#.to_vec();
        let signature = sign(&signed_bytes, &key_pair);
        let tampered = br#"{"version":"0.1.3","sha256":"cd"}"#.to_vec();

        // Then: swapping the installer hash invalidates the signature.
        assert!(matches!(
            VerifiedUpdate::verify_with(tampered, signature, &public_key),
            Err(UpdateError::BadSignature)
        ));
    }

    #[test]
    fn rejects_a_signature_from_a_different_key() {
        // Given: a manifest signed by one key but checked against another.
        let (signing_key, _) = test_signer();
        let (_, other_public_key) = test_signer();
        let bytes = br#"{"version":"0.1.3","sha256":"ab"}"#.to_vec();

        // Then: a forged release cannot pass with its own key.
        assert!(matches!(
            VerifiedUpdate::verify_with(
                bytes.clone(),
                sign(&bytes, &signing_key),
                &other_public_key
            ),
            Err(UpdateError::BadSignature)
        ));
    }

    #[test]
    fn rejects_a_malformed_signature() {
        // Given: a well-formed manifest with truncated or oversized signatures.
        let (_, public_key) = test_signer();
        let bytes = br#"{"version":"0.1.3","sha256":"ab"}"#.to_vec();

        // Then: every wrong length is refused rather than trusted.
        for signature in [vec![], vec![0u8; 63], vec![0u8; 65]] {
            assert!(matches!(
                VerifiedUpdate::verify_with(bytes.clone(), signature, &public_key),
                Err(UpdateError::BadSignature)
            ));
        }
    }

    #[test]
    fn rejects_a_signed_manifest_that_is_not_json() {
        // Given: bytes that are correctly signed but are not a manifest.
        let (key_pair, public_key) = test_signer();
        let bytes = b"not a manifest".to_vec();

        // Then: verification succeeds on the signature but parsing still fails.
        assert!(matches!(
            VerifiedUpdate::verify_with(bytes.clone(), sign(&bytes, &key_pair), &public_key),
            Err(UpdateError::Json(_))
        ));
    }

    #[test]
    fn embeds_a_well_formed_public_key() {
        let public_key = update_public_key().expect("embedded public key should parse");
        assert_ne!(public_key, [0u8; ring::signature::ED25519_PUBLIC_KEY_LEN]);
    }

    #[test]
    fn decodes_hex_and_rejects_garbage() {
        assert_eq!(decode_hex("00ff10"), Some(vec![0x00, 0xff, 0x10]));
        assert_eq!(decode_hex("  ABcd \n"), Some(vec![0xab, 0xcd]));
        assert_eq!(decode_hex("abc"), None);
        assert_eq!(decode_hex("zz"), None);
        assert_eq!(decode_hex("éé"), None);
    }
}
