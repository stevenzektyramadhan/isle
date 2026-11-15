use std::io::Read;

use ring::signature::{Ed25519KeyPair, KeyPair as _};

const USAGE: &str = "usage:
  cargo run --example sign_manifest -- --generate <secret-path>
  ISLE_UPDATE_SIGNING_KEY=<hex> cargo run --example sign_manifest -- <manifest-path>";

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    match (args.next(), args.next()) {
        (Some(flag), Some(path)) if flag == "--generate" => generate(&path)?,
        (Some(path), None) => sign(&path)?,
        _ => {
            eprintln!("{USAGE}");
            std::process::exit(2);
        }
    }
    Ok(())
}
fn generate(secret_path: &str) -> Result<(), Box<dyn std::error::Error>> {
    let document =
        Ed25519KeyPair::generate_pkcs8(&ring::rand::SystemRandom::new()).map_err(describe)?;
    let key_pair = Ed25519KeyPair::from_pkcs8(document.as_ref()).map_err(describe)?;
    write_secret(secret_path, &hex(document.as_ref()))?;
    println!("{}", hex(key_pair.public_key().as_ref()));
    Ok(())
}

fn sign(manifest_path: &str) -> Result<(), Box<dyn std::error::Error>> {
    let secret = std::env::var("ISLE_UPDATE_SIGNING_KEY")?;
    let key_pair = Ed25519KeyPair::from_pkcs8(&unhex(secret.trim())?).map_err(describe)?;
    let mut bytes = Vec::new();
    std::fs::File::open(manifest_path)?.read_to_end(&mut bytes)?;
    println!("{}", hex(key_pair.sign(&bytes).as_ref()));
    Ok(())
}

fn describe<E: std::fmt::Debug>(error: E) -> String {
    format!("{error:?}")
}

fn write_secret(path: &str, contents: &str) -> Result<(), std::io::Error> {
    std::fs::write(path, contents)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt as _;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))?;
    }
    Ok(())
}

fn hex(bytes: &[u8]) -> String {
    use std::fmt::Write as _;

    let mut text = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        let _ = write!(text, "{byte:02x}");
    }
    text
}

fn unhex(text: &str) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
    if !text.is_ascii() || !text.len().is_multiple_of(2) {
        return Err("signing key must be an even number of hex digits".into());
    }
    let mut bytes = Vec::with_capacity(text.len() / 2);
    for index in (0..text.len()).step_by(2) {
        bytes.push(u8::from_str_radix(&text[index..index + 2], 16)?);
    }
    Ok(bytes)
}
