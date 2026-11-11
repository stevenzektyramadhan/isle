use std::env;
use std::fs;
use std::path::{Path, PathBuf};

use crate::models::AppConfig;

#[derive(Debug, thiserror::Error)]
pub enum ConfigError {
    #[error("configuration file I/O failed: {0}")]
    Io(#[from] std::io::Error),
    #[error("configuration JSON failed: {0}")]
    Json(#[from] serde_json::Error),
}

pub fn config_file_path() -> PathBuf {
    let config_home = match env::var_os("XDG_CONFIG_HOME") {
        Some(path) if !path.is_empty() => PathBuf::from(path),
        _ => match env::var_os("HOME") {
            Some(home) if !home.is_empty() => PathBuf::from(home).join(".config"),
            _ => PathBuf::from(".config"),
        },
    };

    config_home.join("isle").join("config.json")
}

pub fn load_config() -> AppConfig {
    load_config_from(&config_file_path()).unwrap_or_default()
}

pub fn load_config_from(path: &Path) -> Result<AppConfig, ConfigError> {
    let serialized = fs::read_to_string(path)?;
    let mut config = serde_json::from_str::<AppConfig>(&serialized)?;
    config.volume = config.clamped_volume();
    config.window_x = sanitize_window_coordinate(config.window_x);
    config.window_y = sanitize_window_coordinate(config.window_y);
    Ok(config)
}

fn sanitize_window_coordinate(coordinate: Option<f32>) -> Option<f32> {
    const MAX_WINDOW_COORDINATE: f32 = 32_768.0;
    coordinate.filter(|value| value.is_finite() && value.abs() <= MAX_WINDOW_COORDINATE)
}

pub fn save_config(config: &AppConfig) -> Result<(), ConfigError> {
    save_config_to(&config_file_path(), config)
}

pub fn save_config_to(path: &Path, config: &AppConfig) -> Result<(), ConfigError> {
    if let Some(parent) = path.parent().filter(|parent| !parent.as_os_str().is_empty()) {
        fs::create_dir_all(parent)?;
    }

    let mut normalized_config = config.clone();
    normalized_config.volume = normalized_config.clamped_volume();
    let serialized = serde_json::to_string_pretty(&normalized_config)?;

    // ponytail: atomic via tmp+rename; upgrade to tempfile crate if cross-fs needed
    let tmp = path.with_extension("json.tmp");
    fs::write(&tmp, serialized)?;
    fs::rename(&tmp, path)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::io::ErrorKind;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicUsize, Ordering};

    use super::*;

    static TEST_DIRECTORY_COUNTER: AtomicUsize = AtomicUsize::new(0);

    fn unique_temp_dir(name: &str) -> PathBuf {
        let sequence = TEST_DIRECTORY_COUNTER.fetch_add(1, Ordering::Relaxed);
        std::env::temp_dir().join(format!(
            "isle-config-{name}-{}-{sequence}",
            std::process::id()
        ))
    }

    #[test]
    fn saves_and_loads_config_in_a_unique_temp_directory() {
        // Given: a configuration and a new nested destination.
        let directory = unique_temp_dir("round-trip");
        let path = directory.join("nested/config.json");
        let config = AppConfig {
            window_x: Some(120.0),
            window_y: Some(240.0),
            volume: 0.75,
            autoplay_enabled: false,
            cookies_browser: None,
        };

        // When: the configuration is saved then loaded.
        save_config_to(&path, &config).expect("test configuration should save");
        let loaded = load_config_from(&path).expect("test configuration should load");

        // Then: the persisted values round-trip exactly.
        assert_eq!(loaded, config);
        fs::remove_dir_all(&directory).expect("test directory should be removed");
    }

    #[test]
    fn reports_not_found_for_a_missing_config_file() {
        // Given: a unique path without a configuration file.
        let path = unique_temp_dir("missing").join("config.json");

        // When: it is loaded through the fallible file API.
        let result = load_config_from(&path);

        // Then: the underlying missing-file condition is preserved.
        assert!(matches!(
            result,
            Err(ConfigError::Io(error)) if error.kind() == ErrorKind::NotFound
        ));
    }

    #[test]
    fn keeps_finite_in_range_window_coordinates() {
        // Given/When/Then: sane coordinates survive, corrupt ones are discarded.
        assert_eq!(sanitize_window_coordinate(Some(-1920.0)), Some(-1920.0));
        assert_eq!(sanitize_window_coordinate(Some(32_768.0)), Some(32_768.0));
        assert_eq!(sanitize_window_coordinate(Some(-32_768.0)), Some(-32_768.0));
        assert_eq!(sanitize_window_coordinate(Some(f32::NAN)), None);
        assert_eq!(sanitize_window_coordinate(Some(f32::INFINITY)), None);
        assert_eq!(sanitize_window_coordinate(Some(32_768.5)), None);
    }

    #[test]
    fn discards_out_of_range_window_coordinates_when_loading_config() {
        // Given: persisted coordinates that cannot map to a real window position.
        let directory = unique_temp_dir("invalid-window-position");
        let path = directory.join("config.json");
        fs::create_dir_all(&directory).expect("test directory should be created");
        fs::write(
            &path,
            r#"{"window_x":100000.0,"window_y":-100000.0,"volume":0.5,"autoplay_enabled":true}"#,
        )
        .expect("test configuration should be written");

        // When: the configuration is loaded.
        let config = load_config_from(&path).expect("test configuration should load");

        // Then: unsafe coordinates are discarded instead of passed to the window backend.
        assert_eq!(config.window_x, None);
        assert_eq!(config.window_y, None);
        assert_eq!(config.volume, 0.5);
        fs::remove_dir_all(&directory).expect("test directory should be removed");
    }

    #[test]
    fn clamps_out_of_range_volume_when_loading_config() {
        // Given: a persisted configuration below the supported volume range.
        let directory = unique_temp_dir("clamped-volume");
        let path = directory.join("config.json");
        fs::create_dir_all(&directory).expect("test directory should be created");
        fs::write(
            &path,
            r#"{"window_x":null,"window_y":null,"volume":-0.25,"autoplay_enabled":true}"#,
        )
        .expect("test configuration should be written");

        // When: the configuration is loaded.
        let config = load_config_from(&path).expect("test configuration should load");

        // Then: its volume is normalized to the lower bound.
        assert_eq!(config.volume, 0.0);
        fs::remove_dir_all(&directory).expect("test directory should be removed");
    }
}
