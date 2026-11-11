use crate::{
    extractor::{
        canonical_title, fetch_related_tracks, is_ytdlp_available, resolve_input,
        resolve_stream_info, ExtractorError,
    },
    media_keys::MediaKeyManager,
    models::{AppConfig, AudioCommand, PlayerState, TrackItem},
};
use rodio::{Decoder, OutputStream, OutputStreamBuilder, Sink};
use std::{
    io::{self, BufReader, Read, Seek, SeekFrom},
    sync::mpsc::{self, Receiver, Sender},
    time::Duration,
};

const MAX_BUFFER_BYTES: usize = 256 * 1024;
const READ_CHUNK_BYTES: usize = 8 * 1024;
const MAX_AUTOMATIC_FAILURES: u8 = 3;

pub struct MemoryStreamReader<R: Read> {
    reader: BufReader<R>,
    buffer: Vec<u8>,
    pos: usize,
    max_buffer_bytes: usize,
    stream_exhausted: bool,
}

impl<R: Read> MemoryStreamReader<R> {
    pub fn new(reader: BufReader<R>) -> Self {
        Self {
            reader,
            buffer: Vec::new(),
            pos: 0,
            max_buffer_bytes: MAX_BUFFER_BYTES,
            stream_exhausted: false,
        }
    }

    fn read_more(&mut self) -> io::Result<usize> {
        if self.stream_exhausted || self.buffer.len() >= self.max_buffer_bytes {
            return Ok(0);
        }
        let mut chunk = [0_u8; READ_CHUNK_BYTES];
        let length = (self.max_buffer_bytes - self.buffer.len()).min(chunk.len());
        let count = self.reader.read(&mut chunk[..length])?;
        if count == 0 {
            self.stream_exhausted = true;
        } else {
            self.buffer
                .try_reserve_exact(count)
                .map_err(|_| buffer_limit_error())?;
            self.buffer.extend_from_slice(&chunk[..count]);
        }
        Ok(count)
    }

    fn fill_to(&mut self, target: usize) -> io::Result<()> {
        while self.buffer.len() < target && self.read_more()? != 0 {}
        Ok(())
    }

    fn set_position(&mut self, target: usize) -> io::Result<u64> {
        self.fill_to(target)?;
        if target > self.buffer.len() {
            return Err(seek_error());
        }
        self.pos = target;
        u64::try_from(target).map_err(|_| seek_error())
    }
}

impl<R: Read> Read for MemoryStreamReader<R> {
    fn read(&mut self, output: &mut [u8]) -> io::Result<usize> {
        if output.is_empty() {
            return Ok(0);
        }
        if self.pos >= self.buffer.len()
            && self.buffer.len() < self.max_buffer_bytes
            && !self.stream_exhausted
            && self.read_more()? == 0
        {
            return Ok(0);
        }
        if self.pos < self.buffer.len() {
            let count = (self.buffer.len() - self.pos).min(output.len());
            output[..count].copy_from_slice(&self.buffer[self.pos..self.pos + count]);
            self.pos += count;
            return Ok(count);
        }
        let count = self.reader.read(output)?;
        if count == 0 {
            self.stream_exhausted = true;
        } else {
            self.pos += count;
        }
        Ok(count)
    }
}

impl<R: Read> Seek for MemoryStreamReader<R> {
    fn seek(&mut self, style: SeekFrom) -> io::Result<u64> {
        let target = match style {
            SeekFrom::Start(offset) => usize::try_from(offset).map_err(|_| seek_error())?,
            SeekFrom::Current(offset) => relative_position(self.pos, offset)?,
            SeekFrom::End(offset) => {
                while self.read_more()? != 0 {}
                relative_position(self.buffer.len(), offset)?
            }
        };
        self.set_position(target)
    }
}

fn buffer_limit_error() -> io::Error {
    io::Error::other("stream exceeds the in-memory audio buffer")
}
fn seek_error() -> io::Error {
    io::Error::new(io::ErrorKind::InvalidInput, "invalid stream seek")
}
fn relative_position(base: usize, offset: i64) -> io::Result<usize> {
    let position = i128::try_from(base).map_err(|_| seek_error())? + i128::from(offset);
    if position < 0 {
        return Err(seek_error());
    }
    usize::try_from(position).map_err(|_| seek_error())
}

pub fn apply_volume(state: &mut PlayerState, volume: f32) {
    state.volume = volume.clamp(0.0, 1.5);
}
pub fn advance_queue(state: &mut PlayerState) -> Option<TrackItem> {
    if let Some(track) = state.current_track.take() {
        state.history.push(track);
    }
    let track = state.queue.pop_front()?;
    state.current_track = Some(track.clone());
    Some(track)
}
pub fn pop_previous(state: &mut PlayerState) -> Option<TrackItem> {
    let track = state.history.pop()?;
    if let Some(current) = state.current_track.take() {
        state.queue.push_front(current);
    }
    state.current_track = Some(track.clone());
    Some(track)
}

struct AudioBackend {
    stream: OutputStream,
    client: reqwest::blocking::Client,
}

#[derive(Debug, thiserror::Error)]
enum StartError {
    #[error("no audio output device is available")]
    NoAudioDevice,
    #[error("stream resolution failed")]
    Resolve(#[from] ExtractorError),
    #[error("stream HTTP connection failed")]
    HttpConnect,
    #[error("stream decoding failed")]
    Decode,
}

impl AudioBackend {
    fn new() -> Option<Self> {
        // ponytail: per-request timeout caps each HTTP Range segment; HttpRangeReader reconnects on expiry
        let client = reqwest::blocking::Client::builder()
            .user_agent("Mozilla/5.0")
            .connect_timeout(Duration::from_secs(10))
            .tcp_keepalive(Duration::from_secs(15))
            .timeout(Duration::from_secs(300))
            .build()
            .ok()?;
        Some(Self {
            stream: OutputStreamBuilder::open_default_stream().ok()?,
            client,
        })
    }
}

struct HttpRangeReader {
    client: reqwest::blocking::Client,
    url: String,
    user_agent: Option<String>,
    response: reqwest::blocking::Response,
    pos: u64,
    total_len: Option<u64>,
}

impl HttpRangeReader {
    fn open(
        client: &reqwest::blocking::Client,
        url: String,
        user_agent: Option<String>,
    ) -> Result<Self, reqwest::Error> {
        let mut req = client.get(&url).header("Range", "bytes=0-");
        if let Some(ua) = &user_agent {
            req = req.header("User-Agent", ua);
        }
        let response = req
            .send()
            .and_then(reqwest::blocking::Response::error_for_status)?;
        let total_len = response.content_length();
        Ok(Self {
            client: client.clone(),
            url,
            user_agent,
            response,
            pos: 0,
            total_len,
        })
    }
}

impl Read for HttpRangeReader {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if let Ok(count) = self.response.read(buf) {
            if count > 0 || self.total_len.is_none_or(|len| self.pos >= len) {
                self.pos += count as u64;
                return Ok(count);
            }
        }
        let range = format!("bytes={}-", self.pos);
        let mut req = self.client.get(&self.url).header("Range", range);
        if let Some(ua) = &self.user_agent {
            req = req.header("User-Agent", ua);
        }
        self.response = req
            .send()
            .and_then(reqwest::blocking::Response::error_for_status)
            .map_err(io::Error::other)?;
        let count = self.response.read(buf)?;
        self.pos += count as u64;
        Ok(count)
    }
}

pub fn decode_stream<R: Read + Send + Sync + 'static>(
    reader: BufReader<R>,
) -> Option<Decoder<MemoryStreamReader<R>>> {
    Decoder::builder()
        .with_data(MemoryStreamReader::new(reader))
        .with_seekable(false)
        .with_hint("m4a")
        .build()
        .ok()
}

fn start_track(
    track: &TrackItem,
    backend: Option<&AudioBackend>,
    volume: f32,
    cookies_browser: &mut Option<String>,
) -> Result<Sink, StartError> {
    let backend = match backend {
        Some(backend) => backend,
        None => {
            eprintln!("[isle] Audio output device is unavailable");
            return Err(StartError::NoAudioDevice);
        }
    };
    let mut resolved = resolve_stream_info(&track.original_url, cookies_browser.as_deref());
    if resolved.is_err() && !track.original_url.starts_with("ytsearch") {
        eprintln!("[isle] Stream resolution failed; trying search fallback");
        let fallback = format!("ytsearch1:{} Official Audio", track.title);
        resolved = resolve_stream_info(&fallback, cookies_browser.as_deref());
    }
    let stream_info = match resolved {
        Ok(info) => info,
        Err(err) => {
            eprintln!(
                "[isle] Stream resolution failed: {}",
                resolve_diagnostic(&err)
            );
            return Err(StartError::Resolve(err));
        }
    };
    let mut detected_browser = stream_info.browser_used.clone();
    let mut reader =
        match HttpRangeReader::open(&backend.client, stream_info.url, stream_info.user_agent) {
            Ok(reader) => Some(reader),
            Err(err) => {
                eprintln!(
                    "[isle] Stream HTTP Range connection failed: {}",
                    err.without_url()
                );
                None
            }
        };
    if reader.is_none() && !track.original_url.starts_with("ytsearch") {
        eprintln!("[isle] Initial HTTP range stream failed; trying search fallback");
        let fallback = format!("ytsearch1:{} Official Audio", track.title);
        match resolve_stream_info(&fallback, cookies_browser.as_deref()) {
            Ok(info) => {
                detected_browser = info.browser_used.clone();
                reader = match HttpRangeReader::open(&backend.client, info.url, info.user_agent) {
                    Ok(reader) => Some(reader),
                    Err(err) => {
                        eprintln!(
                            "[isle] Search fallback HTTP connection failed: {}",
                            err.without_url()
                        );
                        None
                    }
                };
            }
            Err(err) => eprintln!(
                "[isle] Search fallback resolution failed: {}",
                resolve_diagnostic(&err)
            ),
        }
    }
    let reader = reader.ok_or(StartError::HttpConnect)?;
    if cookies_browser.is_none() && detected_browser.is_some() {
        eprintln!("[isle] Auto-detected usable browser cookie source");
        *cookies_browser = detected_browser;
    }
    let decoder = match decode_stream(BufReader::new(reader)) {
        Some(decoder) => decoder,
        None => {
            eprintln!("[isle] Stream decoder rejected the response format");
            return Err(StartError::Decode);
        }
    };
    let sink = Sink::connect_new(backend.stream.mixer());
    sink.set_volume(volume);
    sink.append(decoder);
    Ok(sink)
}

struct Worker {
    state: PlayerState,
    sink: Option<Sink>,
    backend: Option<AudioBackend>,
    state_tx: Sender<PlayerState>,
    consecutive_errors: u8,
    cookies_browser: Option<String>,
}

fn submit_error_message(error: &ExtractorError) -> &'static str {
    match error {
        ExtractorError::Timeout(_) => "yt-dlp timed out",
        ExtractorError::MissingBinary => "yt-dlp not found",
        ExtractorError::PrivateNetwork => "Local/private addresses are blocked",
        ExtractorError::NoResults => "No results found",
        _ => "Could not load input",
    }
}

fn resolve_diagnostic(error: &ExtractorError) -> &'static str {
    match error {
        ExtractorError::MissingBinary => "yt-dlp is unavailable",
        ExtractorError::EmptyInput => "input is empty",
        ExtractorError::PrivateNetwork => "input is blocked",
        ExtractorError::SubprocessIo(_) => "yt-dlp I/O failed",
        ExtractorError::SubprocessFailed(_) => "yt-dlp exited unsuccessfully",
        ExtractorError::Timeout(_) => "yt-dlp timed out",
        ExtractorError::Http(_) => "resolver HTTP request failed",
        ExtractorError::NoResults => "no stream result",
    }
}

fn start_error_message(error: &StartError) -> &'static str {
    match error {
        StartError::NoAudioDevice => "No audio device",
        StartError::Resolve(ExtractorError::MissingBinary) => "yt-dlp not found",
        StartError::Resolve(ExtractorError::Timeout(_)) => "Resolve timed out",
        StartError::Resolve(_) | StartError::HttpConnect => "Stream HTTP error",
        StartError::Decode => "Unsupported audio format",
    }
}

impl Worker {
    fn activate(&mut self, track: TrackItem) -> bool {
        if let Some(sink) = self.sink.take() {
            sink.stop();
        }
        self.state.current_track = Some(track.clone());
        self.state.is_playing = false;
        self.state.elapsed_secs = 0;
        self.state.status_message = Some("Loading stream…".to_owned());
        let _ = self.state_tx.send(self.state.clone());
        match start_track(
            &track,
            self.backend.as_ref(),
            self.state.volume,
            &mut self.cookies_browser,
        ) {
            Ok(sink) => {
                self.sink = Some(sink);
                self.state.is_playing = true;
                self.consecutive_errors = 0;
                self.state.status_message = None;
                true
            }
            Err(error) => {
                self.sink = None;
                self.state.is_playing = false;
                self.consecutive_errors = self.consecutive_errors.saturating_add(1);
                self.state.status_message = Some(start_error_message(&error).to_owned());
                false
            }
        }
    }

    fn submit(&mut self, input: &str) {
        self.consecutive_errors = 0;
        self.state.status_message = Some(format!("Searching {input}…"));
        let _ = self.state_tx.send(self.state.clone());
        match resolve_input(input) {
            Ok(tracks) => {
                let mut tracks = tracks.into_iter();
                if let Some(track) = tracks.next() {
                    if let Some(prev) = self.state.current_track.take() {
                        self.state.history.push(prev);
                    }
                    self.state.queue.clear();
                    self.state.queue.extend(tracks);
                    self.activate(track);
                }
            }
            Err(error) => self.state.status_message = Some(submit_error_message(&error).to_owned()),
        }
    }

    fn advance(&mut self, automatic: bool) {
        if !automatic {
            self.consecutive_errors = 0;
            if let Some(track) = advance_queue(&mut self.state) {
                self.activate(track);
                return;
            }
        }

        let mut loaded_radio_mix = false;
        loop {
            if automatic && self.consecutive_errors >= MAX_AUTOMATIC_FAILURES {
                self.sink = None;
                self.state.is_playing = false;
                self.state.elapsed_secs = 0;
                self.state.status_message = Some("Playback stopped: repeated errors".to_owned());
                return;
            }
            if let Some(track) = advance_queue(&mut self.state) {
                if self.activate(track) || !automatic {
                    return;
                }
                continue;
            }
            if !loaded_radio_mix && self.state.autoplay_enabled {
                loaded_radio_mix = true;
                if let Some(last) = self.state.history.last().cloned() {
                    self.state.status_message = Some("Loading radio mix…".to_owned());
                    let _ = self.state_tx.send(self.state.clone());
                    if let Ok(related) = fetch_related_tracks(&last) {
                        let fresh = related.into_iter().filter(|t| {
                            !self.state.history.iter().any(|h| {
                                h.id == t.id
                                    || canonical_title(&h.title) == canonical_title(&t.title)
                            })
                        });
                        self.state.queue.extend(fresh);
                        if !automatic {
                            if let Some(track) = advance_queue(&mut self.state) {
                                self.activate(track);
                            }
                            return;
                        }
                        continue;
                    }
                }
            }
            self.sink = None;
            self.state.is_playing = false;
            self.state.elapsed_secs = 0;
            return;
        }
    }

    fn previous(&mut self) {
        self.consecutive_errors = 0;
        if let Some(track) = pop_previous(&mut self.state) {
            self.activate(track);
        }
    }

    fn toggle(&mut self) {
        if let Some(sink) = self.sink.as_ref() {
            if self.state.is_playing {
                sink.pause();
            } else {
                sink.play();
            }
            self.state.is_playing = !self.state.is_playing;
        } else if let Some(track) = self.state.current_track.clone() {
            self.activate(track);
        } else {
            self.advance(false);
        }
    }

    fn stop(&mut self) {
        if let Some(sink) = self.sink.take() {
            sink.stop();
        }
        self.state.current_track = None;
        self.state.is_playing = false;
        self.state.elapsed_secs = 0;
    }
}

pub fn spawn_audio_worker(
    initial_config: AppConfig,
) -> (Sender<AudioCommand>, Receiver<PlayerState>) {
    let (cmd_tx, cmd_rx) = mpsc::channel();
    let (state_tx, state_rx) = mpsc::channel();
    let media_tx = cmd_tx.clone();
    std::thread::spawn(move || {
        let mut state = PlayerState::default();
        apply_volume(&mut state, initial_config.volume);
        state.autoplay_enabled = initial_config.autoplay_enabled;
        state.ytdlp_available = is_ytdlp_available();
        if state_tx.send(state.clone()).is_err() {
            return;
        }
        let cookies_browser = initial_config.cookies_browser.clone();
        let mut worker = Worker {
            state,
            sink: None,
            backend: AudioBackend::new(),
            state_tx: state_tx.clone(),
            consecutive_errors: 0,
            cookies_browser,
        };
        let mut media_keys = MediaKeyManager::new(media_tx);
        media_keys.sync_state(&worker.state);
        let mut running = true;
        while running {
            match cmd_rx.recv_timeout(Duration::from_millis(200)) {
                Ok(AudioCommand::SubmitInput(input)) => worker.submit(&input),
                Ok(AudioCommand::PlayTrack(track)) => {
                    worker.activate(track);
                }
                Ok(AudioCommand::TogglePlayPause) => worker.toggle(),
                Ok(AudioCommand::Stop) => worker.stop(),
                Ok(AudioCommand::NextTrack) => worker.advance(false),
                Ok(AudioCommand::PreviousTrack) => worker.previous(),
                Ok(AudioCommand::SetVolume(volume)) => {
                    apply_volume(&mut worker.state, volume);
                    if let Some(sink) = worker.sink.as_ref() {
                        sink.set_volume(worker.state.volume);
                    }
                }
                Ok(AudioCommand::SetAutoplay(enabled)) => worker.state.autoplay_enabled = enabled,
                Ok(AudioCommand::Seek(pos)) => {
                    if let Some(sink) = worker.sink.as_ref() {
                        if sink.try_seek(pos).is_ok() {
                            worker.state.elapsed_secs = pos.as_secs();
                        }
                    }
                }
                Ok(AudioCommand::Enqueue(track)) => worker.state.queue.push_back(track),
                Ok(AudioCommand::EnqueueBatch(tracks)) => worker.state.queue.extend(tracks),
                Ok(AudioCommand::ClearQueue) => worker.state.queue.clear(),
                Ok(AudioCommand::ToggleWindowVisibility) => {}
                Ok(AudioCommand::Shutdown) | Err(mpsc::RecvTimeoutError::Disconnected) => {
                    worker.stop();
                    running = false;
                }
                Err(mpsc::RecvTimeoutError::Timeout) => {}
            }
            if worker.state.is_playing {
                if let Some(sink) = worker.sink.as_ref() {
                    worker.state.elapsed_secs = sink.get_pos().as_secs();
                }
            }
            let completed =
                worker.state.is_playing && worker.sink.as_ref().is_some_and(Sink::empty);
            if completed {
                worker.advance(true);
            }
            media_keys.sync_state(&worker.state);
            if state_tx.send(worker.state.clone()).is_err() {
                break;
            }
        }
    });
    (cmd_tx, state_rx)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    fn track(track_id: &str) -> TrackItem {
        TrackItem {
            id: track_id.to_owned(),
            title: track_id.to_owned(),
            artist: None,
            original_url: format!("https://example.test/{track_id}"),
            duration_secs: None,
        }
    }

    fn worker_with_tracks(consecutive_errors: u8, track_ids: &[&str]) -> Worker {
        let (state_tx, _state_rx) = mpsc::channel();
        let mut state = PlayerState::default();
        state
            .queue
            .extend(track_ids.iter().map(|track_id| track(track_id)));
        Worker {
            state,
            sink: None,
            backend: None,
            state_tx,
            consecutive_errors,
            cookies_browser: None,
        }
    }

    fn worker_with_queue(consecutive_errors: u8) -> Worker {
        worker_with_tracks(consecutive_errors, &["next"])
    }

    #[test]
    fn submit_error_messages_are_specific_and_safe() {
        let timeout = ExtractorError::Timeout(Duration::from_secs(30));
        assert_eq!(submit_error_message(&timeout), "yt-dlp timed out");
        assert_eq!(
            submit_error_message(&ExtractorError::MissingBinary),
            "yt-dlp not found"
        );
        assert_eq!(
            submit_error_message(&ExtractorError::PrivateNetwork),
            "Local/private addresses are blocked"
        );
        assert_eq!(
            submit_error_message(&ExtractorError::NoResults),
            "No results found"
        );
        assert_eq!(
            submit_error_message(&ExtractorError::EmptyInput),
            "Could not load input"
        );
        assert_eq!(
            submit_error_message(&ExtractorError::SubprocessFailed(
                "raw yt-dlp output".to_owned()
            )),
            "Could not load input"
        );
    }

    #[test]
    fn repeated_error_guard_blocks_only_automatic_advance() {
        let mut worker = worker_with_queue(3);
        worker.advance(true);
        assert_eq!(worker.state.queue.len(), 1);
        assert_eq!(worker.state.current_track, None);
        assert_eq!(
            worker.state.status_message.as_deref(),
            Some("Playback stopped: repeated errors")
        );
    }

    #[test]
    fn start_track_without_backend_returns_no_audio_device() {
        let mut cookies_browser = None;

        assert!(matches!(
            start_track(&track("current"), None, 1.0, &mut cookies_browser),
            Err(StartError::NoAudioDevice)
        ));
        assert_eq!(cookies_browser, None);
    }

    #[test]
    fn backend_none_reports_no_audio_device_status() {
        let mut worker = worker_with_queue(0);
        worker.activate(track("current"));

        assert!(!worker.state.is_playing);
        assert_eq!(
            worker.state.status_message.as_deref(),
            Some("No audio device")
        );
    }

    #[test]
    fn startup_error_messages_are_short_and_specific() {
        let errors = [
            (StartError::NoAudioDevice, "No audio device"),
            (
                StartError::Resolve(ExtractorError::MissingBinary),
                "yt-dlp not found",
            ),
            (
                StartError::Resolve(ExtractorError::Timeout(Duration::from_secs(30))),
                "Resolve timed out",
            ),
            (
                StartError::Resolve(ExtractorError::SubprocessFailed(
                    "raw yt-dlp stderr".to_owned(),
                )),
                "Stream HTTP error",
            ),
            (StartError::HttpConnect, "Stream HTTP error"),
            (StartError::Decode, "Unsupported audio format"),
        ];

        for (error, message) in errors {
            assert_eq!(start_error_message(&error), message);
            assert!(message.len() <= 32);
        }
    }

    #[test]
    fn automatic_advance_skips_three_failures_and_leaves_the_fourth_queued() {
        let mut worker = worker_with_tracks(0, &["one", "two", "three", "four"]);
        worker.advance(true);

        assert_eq!(worker.consecutive_errors, 3);
        assert_eq!(
            worker.state.queue.front().map(|track| track.id.as_str()),
            Some("four")
        );
        assert_eq!(worker.state.queue.len(), 1);
        assert_eq!(
            worker
                .state
                .current_track
                .as_ref()
                .map(|track| track.id.as_str()),
            Some("three")
        );
        assert!(!worker.state.is_playing);
        assert_eq!(
            worker.state.status_message.as_deref(),
            Some("Playback stopped: repeated errors")
        );
    }

    #[test]
    fn manual_next_resets_the_error_guard_and_advances() {
        let mut worker = worker_with_queue(3);
        worker.advance(false);
        assert_eq!(worker.consecutive_errors, 1);
        assert!(worker.state.queue.is_empty());
        assert_eq!(
            worker
                .state
                .current_track
                .as_ref()
                .map(|track| track.id.as_str()),
            Some("next")
        );
        assert_eq!(
            worker.state.status_message.as_deref(),
            Some("No audio device")
        );
    }

    #[test]
    fn manual_previous_resets_the_error_guard() {
        let mut worker = worker_with_queue(3);
        worker.state.current_track = Some(track("current"));
        worker.state.history.push(track("previous"));
        worker.previous();
        assert_eq!(worker.consecutive_errors, 1);
        assert_eq!(
            worker
                .state
                .current_track
                .as_ref()
                .map(|track| track.id.as_str()),
            Some("previous")
        );
        assert_eq!(
            worker.state.status_message.as_deref(),
            Some("No audio device")
        );
    }

    #[test]
    fn submit_resets_the_error_guard_and_reports_a_safe_status() {
        let mut worker = worker_with_queue(3);
        worker.submit("");

        assert_eq!(worker.consecutive_errors, 0);
        assert_eq!(
            worker.state.status_message.as_deref(),
            Some("Could not load input")
        );
        assert_eq!(
            worker.state.queue.front().map(|track| track.id.as_str()),
            Some("next")
        );
    }

    #[test]
    fn memory_stream_reader_rewinds_and_seeks_forward() {
        let mut reader = MemoryStreamReader::new(BufReader::new(Cursor::new(b"abcdef".to_vec())));
        let mut bytes = [0_u8; 3];
        reader.read_exact(&mut bytes).unwrap();
        assert_eq!(&bytes, b"abc");
        reader.seek(SeekFrom::Start(0)).unwrap();
        reader.read_exact(&mut bytes).unwrap();
        assert_eq!(&bytes, b"abc");
        reader.seek(SeekFrom::Start(4)).unwrap();
        reader.read_exact(&mut bytes[..2]).unwrap();
        assert_eq!(&bytes[..2], b"ef");
    }

    #[test]
    fn queue_history_and_volume_transitions_preserve_order() {
        let mut state = PlayerState {
            current_track: Some(track("current")),
            ..PlayerState::default()
        };
        state.queue.push_back(track("next"));
        assert_eq!(advance_queue(&mut state).unwrap().id, "next");
        assert_eq!(
            state.history.last().map(|track| track.id.as_str()),
            Some("current")
        );
        assert_eq!(pop_previous(&mut state).unwrap().id, "current");
        assert_eq!(
            state.queue.front().map(|track| track.id.as_str()),
            Some("next")
        );
        apply_volume(&mut state, 9.0);
        assert!((state.volume - 1.5).abs() < f32::EPSILON);
        apply_volume(&mut state, -1.0);
        assert!(state.volume.abs() < f32::EPSILON);
    }

    #[test]
    fn memory_stream_reader_streams_past_probe_buffer_without_growing() {
        let mut reader = MemoryStreamReader::new(BufReader::new(Cursor::new(vec![
            42_u8;
            MAX_BUFFER_BYTES
                + 1024
        ])));
        let mut out = vec![0_u8; MAX_BUFFER_BYTES + 1024];
        reader.read_exact(&mut out).unwrap();
        assert_eq!(reader.buffer.len(), MAX_BUFFER_BYTES);
        assert!(out.iter().all(|&byte| byte == 42));
    }
}
