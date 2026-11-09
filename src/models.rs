use serde::{Deserialize, Serialize};
use std::collections::VecDeque;
use std::time::Duration;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct TrackItem {
    pub id: String,
    pub title: String,
    pub artist: Option<String>,
    pub original_url: String,
    pub duration_secs: Option<u64>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct PlayerState {
    pub current_track: Option<TrackItem>,
    pub queue: VecDeque<TrackItem>,
    pub history: Vec<TrackItem>,
    pub is_playing: bool,
    pub volume: f32,
    pub autoplay_enabled: bool,
    pub elapsed_secs: u64,
    pub ytdlp_available: bool,
    pub status_message: Option<String>,
}

impl Default for PlayerState {
    fn default() -> Self {
        Self {
            current_track: None,
            queue: VecDeque::new(),
            history: Vec::new(),
            is_playing: false,
            volume: 1.0,
            autoplay_enabled: true,
            elapsed_secs: 0,
            ytdlp_available: true,
            status_message: None,
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub enum AudioCommand {
    SubmitInput(String),
    PlayTrack(TrackItem),
    TogglePlayPause,
    Stop,
    NextTrack,
    PreviousTrack,
    SetVolume(f32),
    SetAutoplay(bool),
    Seek(Duration),
    Enqueue(TrackItem),
    EnqueueBatch(Vec<TrackItem>),
    ClearQueue,
    ToggleWindowVisibility,
    Shutdown,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct AppConfig {
    pub window_x: Option<f32>,
    pub window_y: Option<f32>,
    pub volume: f32,
    pub autoplay_enabled: bool,
}

impl Default for AppConfig {
    fn default() -> Self {
        Self {
            window_x: None,
            window_y: None,
            volume: 1.0,
            autoplay_enabled: true,
        }
    }
}

impl AppConfig {
    pub fn clamped_volume(&self) -> f32 {
        self.volume.clamp(0.0, 1.5)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn player_state_defaults_to_an_idle_ready_player() {
        // Given: no prior player state.

        // When: a default state is created.
        let state = PlayerState::default();

        // Then: it has safe idle playback defaults.
        assert_eq!(state.current_track, None);
        assert!(state.queue.is_empty());
        assert!(state.history.is_empty());
        assert!(!state.is_playing);
        assert_eq!(state.volume, 1.0);
        assert!(state.autoplay_enabled);
        assert_eq!(state.elapsed_secs, 0);
        assert!(state.ytdlp_available);
        assert_eq!(state.status_message, None);
    }

    #[test]
    fn app_config_clamps_volume_at_the_upper_bound() {
        // Given: a configuration above the supported volume range.
        let config = AppConfig {
            volume: 2.0,
            ..AppConfig::default()
        };

        // When: its volume is normalized.
        let volume = config.clamped_volume();

        // Then: it is limited to the maximum supported value.
        assert_eq!(volume, 1.5);
    }
}
