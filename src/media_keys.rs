use std::{sync::mpsc::Sender, time::Duration};

#[cfg(not(windows))]
use souvlaki::MediaControlEvent;
use souvlaki::{MediaControls, MediaMetadata, MediaPlayback};

use crate::models::{AudioCommand, PlayerState};

pub struct MediaKeyManager {
    controls: Option<MediaControls>,
}

impl MediaKeyManager {
    pub fn new(cmd_tx: Sender<AudioCommand>) -> Self {
        #[cfg(windows)]
        {
            let _ = cmd_tx;
            eprintln!(
                "[isle] Media keys unavailable on Windows: souvlaki requires a HWND and eframe does not expose one"
            );
            return Self { controls: None };
        }

        #[cfg(not(windows))]
        {
            let config = souvlaki::PlatformConfig {
                dbus_name: "isle",
                display_name: "Isle",
                hwnd: None,
            };
            let controls = match MediaControls::new(config) {
                Ok(mut controls) => {
                    let attached = controls.attach(move |event| {
                        let command = match event {
                            MediaControlEvent::Play
                            | MediaControlEvent::Pause
                            | MediaControlEvent::Toggle => Some(AudioCommand::TogglePlayPause),
                            MediaControlEvent::Next => Some(AudioCommand::NextTrack),
                            MediaControlEvent::Previous => Some(AudioCommand::PreviousTrack),
                            MediaControlEvent::Stop => Some(AudioCommand::Stop),
                            _ => None,
                        };
                        if let Some(command) = command {
                            let _ = cmd_tx.send(command);
                        }
                    });
                    if attached.is_ok() {
                        Some(controls)
                    } else {
                        eprintln!("[isle] Media keys unavailable: event handler attach failed");
                        None
                    }
                }
                Err(error) => {
                    eprintln!("[isle] Media keys unavailable: {error:?}");
                    None
                }
            };
            Self { controls }
        }
    }

    pub fn sync_state(&mut self, state: &PlayerState) {
        let healthy = match self.controls.as_mut() {
            Some(controls) => {
                let track = state.current_track.as_ref();
                let metadata = MediaMetadata {
                    title: track.map(|item| item.title.as_str()),
                    artist: track.and_then(|item| item.artist.as_deref()),
                    duration: track
                        .and_then(|item| item.duration_secs)
                        .map(Duration::from_secs),
                    ..Default::default()
                };
                let playback = match (track.is_some(), state.is_playing) {
                    (false, _) => MediaPlayback::Stopped,
                    (true, true) => MediaPlayback::Playing { progress: None },
                    (true, false) => MediaPlayback::Paused { progress: None },
                };
                let metadata_updated = controls.set_metadata(metadata).is_ok();
                let playback_updated = controls.set_playback(playback).is_ok();
                metadata_updated && playback_updated
            }
            None => return,
        };
        if !healthy {
            self.controls = None;
        }
    }
}
