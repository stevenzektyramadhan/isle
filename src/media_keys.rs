use std::{ffi::c_void, sync::mpsc::Sender, time::Duration};

use souvlaki::MediaControlEvent;
use souvlaki::{MediaControls, MediaMetadata, MediaPlayback};

use crate::models::{AudioCommand, PlayerState};

pub struct MediaKeyManager {
    controls: Option<MediaControls>,
}

impl MediaKeyManager {
    #[cfg(not(windows))]
    pub fn new(cmd_tx: Sender<AudioCommand>) -> Self {
        Self::with_hwnd(cmd_tx, None)
    }

    #[cfg(windows)]
    pub fn new(cmd_tx: Sender<AudioCommand>, hwnd: Option<*mut c_void>) -> Self {
        match hwnd {
            Some(hwnd) => Self::with_hwnd(cmd_tx, Some(hwnd)),
            None => {
                eprintln!(
                    "[isle] Media keys unavailable on Windows: eframe did not provide a Win32 HWND"
                );
                Self { controls: None }
            }
        }
    }

    fn with_hwnd(cmd_tx: Sender<AudioCommand>, hwnd: Option<*mut c_void>) -> Self {
        let config = souvlaki::PlatformConfig {
            dbus_name: "isle",
            display_name: "Isle",
            hwnd,
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

    pub fn sync_state(&mut self, state: &PlayerState) {
        let healthy = match self.controls.as_mut() {
            Some(controls) => {
                let track = state.current_track.as_ref();
                let (title, artist) = {
                    #[cfg(windows)]
                    {
                        match track {
                            Some(track) => (Some(track.title.as_str()), track.artist.as_deref()),
                            None => (Some(""), Some("")),
                        }
                    }
                    #[cfg(not(windows))]
                    {
                        (
                            track.map(|item| item.title.as_str()),
                            track.and_then(|item| item.artist.as_deref()),
                        )
                    }
                };
                let metadata = MediaMetadata {
                    title,
                    artist,
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
