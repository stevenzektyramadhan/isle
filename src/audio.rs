use crate::{
    extractor::{
        canonical_title, fetch_related_tracks, is_ytdlp_available, resolve_input,
        resolve_stream_info, ExtractorError,
    },
    media_keys::MediaKeyManager,
    models::{AppConfig, AudioCommand, PlayerState, TrackItem},
};
use rodio::{Decoder, OutputStream, OutputStreamBuilder, Sink, Source};
use std::{
    io::{self, Read, Seek, SeekFrom},
    sync::mpsc::{self, Receiver, Sender},
    time::Duration,
};

const MAX_AUTOMATIC_FAILURES: u8 = 3;
// reqwest 0.12 blocking has no read_timeout; ClientBuilder::timeout is applied
// per blocking operation, including every `Response::read`, so it bounds a
// stalled range request/reconnect without capping the whole stream.
const STREAM_CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const STREAM_IO_TIMEOUT: Duration = Duration::from_secs(10);

fn clamped_seek_position(position: Duration, duration_secs: Option<u64>) -> Duration {
    duration_secs.map_or(position, |secs| position.min(Duration::from_secs(secs)))
}

fn seek_error() -> io::Error {
    io::Error::new(io::ErrorKind::InvalidInput, "invalid stream seek")
}
fn non_seekable_error() -> io::Error {
    io::Error::new(
        io::ErrorKind::Unsupported,
        "HTTP stream does not support random access",
    )
}
fn invalid_range_error() -> io::Error {
    io::Error::new(
        io::ErrorKind::InvalidData,
        "invalid HTTP byte range response",
    )
}
fn checked_relative_position(base: u64, offset: i64) -> io::Result<u64> {
    if offset.is_negative() {
        base.checked_sub(offset.unsigned_abs())
            .ok_or_else(seek_error)
    } else {
        base.checked_add(offset.unsigned_abs())
            .ok_or_else(seek_error)
    }
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

type PreparedDecoder = Box<dyn Source + Send>;

struct PreparedTrack {
    decoder: PreparedDecoder,
    detected_browser: Option<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum ActivationOrigin {
    Automatic { radio_mix_loaded: bool },
    Manual,
}

struct ActivationRequest {
    generation: u64,
    origin: ActivationOrigin,
    track: TrackItem,
    client: Option<reqwest::blocking::Client>,
    cookies_browser: Option<String>,
}

enum ActivationResult {
    Ready {
        generation: u64,
        origin: ActivationOrigin,
        decoder: PreparedDecoder,
        detected_browser: Option<String>,
    },
    Failed {
        generation: u64,
        origin: ActivationOrigin,
        error: StartError,
    },
}

fn build_http_client(
    connect_timeout: Duration,
    io_timeout: Duration,
) -> Option<reqwest::blocking::Client> {
    reqwest::blocking::Client::builder()
        .user_agent("Mozilla/5.0")
        .connect_timeout(connect_timeout)
        .tcp_keepalive(Duration::from_secs(15))
        .timeout(io_timeout)
        .build()
        .ok()
}

impl AudioBackend {
    fn new() -> Option<Self> {
        let client = build_http_client(STREAM_CONNECT_TIMEOUT, STREAM_IO_TIMEOUT)?;
        Some(Self {
            stream: OutputStreamBuilder::open_default_stream().ok()?,
            client,
        })
    }
}

#[derive(Debug, thiserror::Error)]
enum HttpRangeOpenError {
    #[error(transparent)]
    Request(#[from] reqwest::Error),
    #[error("invalid HTTP byte range response")]
    InvalidRange,
}

impl HttpRangeOpenError {
    fn without_url(self) -> Self {
        match self {
            Self::Request(error) => Self::Request(error.without_url()),
            Self::InvalidRange => Self::InvalidRange,
        }
    }
}

#[derive(Clone, Copy)]
enum StreamCapability {
    Seekable { total_len: u64 },
    Streaming,
}

#[derive(Clone, Copy)]
struct ContentRange {
    end: u64,
    total_len: u64,
}

struct HttpRangeReader {
    client: reqwest::blocking::Client,
    url: String,
    user_agent: Option<String>,
    response: reqwest::blocking::Response,
    pos: u64,
    capability: StreamCapability,
    response_end: Option<u64>,
}

impl HttpRangeReader {
    fn open(
        client: &reqwest::blocking::Client,
        url: String,
        user_agent: Option<String>,
    ) -> Result<Self, HttpRangeOpenError> {
        let response = request_range(client, &url, user_agent.as_deref(), 0)?;
        match response.status() {
            reqwest::StatusCode::PARTIAL_CONTENT => {
                let range = content_range(&response, 0, None);
                // A declared range that does not describe this body is unusable, but a
                // server that simply omits the header still yields a playable stream.
                if range.is_none() && declared_content_range(&response).is_some() {
                    return Err(HttpRangeOpenError::InvalidRange);
                }
                Ok(Self {
                    client: client.clone(),
                    url,
                    user_agent,
                    response,
                    pos: 0,
                    capability: range.map_or(StreamCapability::Streaming, |range| {
                        StreamCapability::Seekable {
                            total_len: range.total_len,
                        }
                    }),
                    response_end: range.map(|range| range.end),
                })
            }
            reqwest::StatusCode::OK => Ok(Self {
                client: client.clone(),
                url,
                user_agent,
                response,
                pos: 0,
                capability: StreamCapability::Streaming,
                response_end: None,
            }),
            _ => Err(HttpRangeOpenError::InvalidRange),
        }
    }

    fn total_len(&self) -> Option<u64> {
        match self.capability {
            StreamCapability::Seekable { total_len } => Some(total_len),
            StreamCapability::Streaming => None,
        }
    }

    fn can_continue(&self) -> bool {
        self.total_len()
            .is_some_and(|total_len| self.pos < total_len)
    }

    fn response_read_len(&self, buffer_len: usize) -> io::Result<usize> {
        let Some(response_end) = self.response_end else {
            return Ok(buffer_len);
        };
        let remaining = response_end
            .checked_sub(self.pos)
            .and_then(|bytes| bytes.checked_add(1))
            .ok_or_else(invalid_range_error)?;
        let buffer_len = u64::try_from(buffer_len).map_err(|_| invalid_range_error())?;
        usize::try_from(remaining.min(buffer_len)).map_err(|_| invalid_range_error())
    }

    fn replace_response_at(&mut self, start: u64) -> io::Result<()> {
        let total_len = self.total_len().ok_or_else(non_seekable_error)?;
        let response = request_range(&self.client, &self.url, self.user_agent.as_deref(), start)
            .map_err(|error| io::Error::other(error.without_url()))?;
        let range =
            content_range(&response, start, Some(total_len)).ok_or_else(invalid_range_error)?;
        self.response = response;
        self.pos = start;
        self.response_end = Some(range.end);
        Ok(())
    }

    fn advance_position(&mut self, count: usize) -> io::Result<()> {
        let count = u64::try_from(count).map_err(|_| invalid_range_error())?;
        self.pos = self
            .pos
            .checked_add(count)
            .ok_or_else(invalid_range_error)?;
        if self
            .total_len()
            .is_some_and(|total_len| self.pos > total_len)
        {
            return Err(invalid_range_error());
        }
        Ok(())
    }

    fn read_response(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        let read_len = self.response_read_len(buffer.len())?;
        self.response.read(&mut buffer[..read_len])
    }

    fn seek_to(&mut self, target: u64) -> io::Result<u64> {
        let total_len = self.total_len().ok_or_else(non_seekable_error)?;
        if target == self.pos {
            return Ok(self.pos);
        }
        if target > total_len {
            return Err(seek_error());
        }
        if target == total_len {
            self.pos = target;
            return Ok(target);
        }
        self.replace_response_at(target)?;
        Ok(self.pos)
    }
}

impl Read for HttpRangeReader {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if buf.is_empty()
            || self
                .total_len()
                .is_some_and(|total_len| self.pos >= total_len)
        {
            return Ok(0);
        }
        if self
            .response_end
            .is_some_and(|response_end| self.pos > response_end)
        {
            self.replace_response_at(self.pos)?;
        }
        let count = match self.read_response(buf) {
            Ok(count) => count,
            Err(_) if self.can_continue() => {
                self.replace_response_at(self.pos)?;
                self.read_response(buf)?
            }
            Err(error) => return Err(error),
        };
        if count == 0 && self.can_continue() {
            self.replace_response_at(self.pos)?;
            let count = self.read_response(buf)?;
            if count == 0 {
                return Err(io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    "HTTP range response ended before its declared length",
                ));
            }
            self.advance_position(count)?;
            return Ok(count);
        }
        self.advance_position(count)?;
        Ok(count)
    }
}

impl Seek for HttpRangeReader {
    fn seek(&mut self, style: SeekFrom) -> io::Result<u64> {
        match style {
            SeekFrom::Start(offset) => self.seek_to(offset),
            SeekFrom::Current(offset) => self.seek_to(checked_relative_position(self.pos, offset)?),
            SeekFrom::End(offset) => {
                let total_len = self.total_len().ok_or_else(non_seekable_error)?;
                self.seek_to(checked_relative_position(total_len, offset)?)
            }
        }
    }
}

fn request_range(
    client: &reqwest::blocking::Client,
    url: &str,
    user_agent: Option<&str>,
    start: u64,
) -> Result<reqwest::blocking::Response, reqwest::Error> {
    let mut request = client.get(url).header("Range", format!("bytes={start}-"));
    if let Some(user_agent) = user_agent {
        request = request.header("User-Agent", user_agent);
    }
    request
        .send()
        .and_then(reqwest::blocking::Response::error_for_status)
}

fn content_range(
    response: &reqwest::blocking::Response,
    requested_start: u64,
    expected_total_len: Option<u64>,
) -> Option<ContentRange> {
    let (start, end, total_len) = declared_content_range(response)?;
    if start != requested_start || start > end || end >= total_len {
        return None;
    }
    if expected_total_len.is_some_and(|expected| expected != total_len) {
        return None;
    }
    Some(ContentRange { end, total_len })
}

fn declared_content_range(response: &reqwest::blocking::Response) -> Option<(u64, u64, u64)> {
    if response.status() != reqwest::StatusCode::PARTIAL_CONTENT {
        return None;
    }
    let value = response
        .headers()
        .get(reqwest::header::CONTENT_RANGE)?
        .to_str()
        .ok()?;
    let (range, total_len) = value.strip_prefix("bytes ")?.split_once('/')?;
    let (start, end) = range.split_once('-')?;
    Some((
        start.parse().ok()?,
        end.parse().ok()?,
        total_len.parse().ok()?,
    ))
}

fn decode_stream(reader: HttpRangeReader) -> Option<Decoder<HttpRangeReader>> {
    let total_len = reader.total_len();
    let decoder = Decoder::builder().with_data(reader).with_hint("m4a");
    match total_len {
        Some(total_len) => decoder
            .with_byte_len(total_len)
            .with_seekable(true)
            .build()
            .ok(),
        None => decoder.with_seekable(false).build().ok(),
    }
}

fn prepare_track(
    track: &TrackItem,
    client: Option<&reqwest::blocking::Client>,
    cookies_browser: Option<&str>,
) -> Result<PreparedTrack, StartError> {
    let client = match client {
        Some(client) => client,
        None => {
            eprintln!("[isle] Audio output device is unavailable");
            return Err(StartError::NoAudioDevice);
        }
    };
    let mut resolved = resolve_stream_info(&track.original_url, cookies_browser);
    if resolved.is_err() && !track.original_url.starts_with("ytsearch") {
        eprintln!("[isle] Stream resolution failed; trying search fallback");
        let fallback = format!("ytsearch1:{} Official Audio", track.title);
        resolved = resolve_stream_info(&fallback, cookies_browser);
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
    let mut reader = match HttpRangeReader::open(client, stream_info.url, stream_info.user_agent) {
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
        match resolve_stream_info(&fallback, cookies_browser) {
            Ok(info) => {
                detected_browser = info.browser_used.clone();
                reader = match HttpRangeReader::open(client, info.url, info.user_agent) {
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
    let decoder = match decode_stream(reader) {
        Some(decoder) => decoder,
        None => {
            eprintln!("[isle] Stream decoder rejected the response format");
            return Err(StartError::Decode);
        }
    };
    Ok(PreparedTrack {
        decoder: Box::new(decoder),
        detected_browser,
    })
}

fn prepare_activation(request: ActivationRequest) -> ActivationResult {
    let ActivationRequest {
        generation,
        origin,
        track,
        client,
        cookies_browser,
    } = request;
    match prepare_track(&track, client.as_ref(), cookies_browser.as_deref()) {
        Ok(PreparedTrack {
            decoder,
            detected_browser,
        }) => ActivationResult::Ready {
            generation,
            origin,
            decoder,
            detected_browser,
        },
        Err(error) => ActivationResult::Failed {
            generation,
            origin,
            error,
        },
    }
}

struct Worker {
    state: PlayerState,
    sink: Option<Sink>,
    backend: Option<AudioBackend>,
    state_tx: Sender<PlayerState>,
    consecutive_errors: u8,
    cookies_browser: Option<String>,
    activation_generation: u64,
    activation_tx: Sender<ActivationResult>,
    activation_rx: Receiver<ActivationResult>,
    play_when_ready: bool,
    activation_pending: bool,
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
    fn invalidate_activation(&mut self) {
        self.activation_generation = self.activation_generation.wrapping_add(1);
        self.activation_pending = false;
    }

    fn schedule_activation(
        &mut self,
        track: TrackItem,
        origin: ActivationOrigin,
    ) -> ActivationRequest {
        self.invalidate_activation();
        if let Some(sink) = self.sink.take() {
            sink.stop();
        }
        self.state.current_track = Some(track.clone());
        self.state.is_playing = false;
        self.state.elapsed_secs = 0;
        self.state.status_message = Some("Loading stream…".to_owned());
        self.play_when_ready = true;
        self.activation_pending = true;
        let _ = self.state_tx.send(self.state.clone());
        ActivationRequest {
            generation: self.activation_generation,
            origin,
            track,
            client: self.backend.as_ref().map(|backend| backend.client.clone()),
            cookies_browser: self.cookies_browser.clone(),
        }
    }

    fn activate(&mut self, track: TrackItem, origin: ActivationOrigin) {
        let request = self.schedule_activation(track, origin);
        self.spawn_activation(request);
    }

    fn spawn_activation(&self, request: ActivationRequest) {
        let activation_tx = self.activation_tx.clone();
        std::thread::spawn(move || {
            let _ = activation_tx.send(prepare_activation(request));
        });
    }

    fn process_activation_results(&mut self) {
        while let Ok(result) = self.activation_rx.try_recv() {
            if let Some(request) = self.handle_activation_result(result) {
                self.spawn_activation(request);
            }
        }
    }

    fn handle_activation_result(&mut self, result: ActivationResult) -> Option<ActivationRequest> {
        match result {
            ActivationResult::Ready {
                generation,
                origin,
                decoder,
                detected_browser,
            } => {
                if generation != self.activation_generation || !self.activation_pending {
                    return None;
                }
                let _ = origin;
                let backend = self.backend.as_ref()?;
                let sink = Sink::connect_new(backend.stream.mixer());
                sink.set_volume(self.state.volume);
                if !self.play_when_ready {
                    sink.pause();
                }
                sink.append(decoder);
                self.activation_pending = false;
                self.sink = Some(sink);
                if self.cookies_browser.is_none() && detected_browser.is_some() {
                    eprintln!("[isle] Auto-detected usable browser cookie source");
                    self.cookies_browser = detected_browser;
                }
                self.state.is_playing = self.play_when_ready;
                self.consecutive_errors = 0;
                self.state.status_message = None;
                None
            }
            ActivationResult::Failed {
                generation,
                origin,
                error,
            } => {
                if generation != self.activation_generation || !self.activation_pending {
                    return None;
                }
                self.activation_pending = false;
                self.sink = None;
                self.state.is_playing = false;
                self.consecutive_errors = self.consecutive_errors.saturating_add(1);
                self.state.status_message = Some(start_error_message(&error).to_owned());
                match origin {
                    ActivationOrigin::Automatic { radio_mix_loaded } => {
                        let was_paused = !self.play_when_ready;
                        let next =
                            self.schedule_advance(ActivationOrigin::Automatic { radio_mix_loaded });
                        if next.is_some() && was_paused {
                            self.play_when_ready = false;
                        }
                        next
                    }
                    ActivationOrigin::Manual => None,
                }
            }
        }
    }

    fn submit(&mut self, input: &str) {
        self.invalidate_activation();
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
                    let request = self.schedule_activation(track, ActivationOrigin::Manual);
                    self.spawn_activation(request);
                }
            }
            Err(error) => self.state.status_message = Some(submit_error_message(&error).to_owned()),
        }
    }

    fn advance(&mut self, origin: ActivationOrigin) {
        if let Some(request) = self.schedule_advance(origin) {
            self.spawn_activation(request);
        }
    }

    fn schedule_advance(&mut self, origin: ActivationOrigin) -> Option<ActivationRequest> {
        let (automatic, radio_mix_loaded) = match origin {
            ActivationOrigin::Automatic { radio_mix_loaded } => (true, radio_mix_loaded),
            ActivationOrigin::Manual => {
                self.consecutive_errors = 0;
                (false, false)
            }
        };

        if automatic && self.consecutive_errors >= MAX_AUTOMATIC_FAILURES {
            self.sink = None;
            self.state.is_playing = false;
            self.state.elapsed_secs = 0;
            self.play_when_ready = false;
            self.state.status_message = Some("Playback stopped: repeated errors".to_owned());
            return None;
        }

        if let Some(track) = advance_queue(&mut self.state) {
            return Some(self.schedule_activation(track, origin));
        }

        if !radio_mix_loaded && self.state.autoplay_enabled {
            if let Some(last) = self.state.history.last().cloned() {
                self.state.status_message = Some("Loading radio mix…".to_owned());
                let _ = self.state_tx.send(self.state.clone());
                if let Ok(related) = fetch_related_tracks(&last) {
                    let fresh = related.into_iter().filter(|track| {
                        !self.state.history.iter().any(|history_track| {
                            history_track.id == track.id
                                || canonical_title(&history_track.title)
                                    == canonical_title(&track.title)
                        })
                    });
                    self.state.queue.extend(fresh);
                    if let Some(track) = advance_queue(&mut self.state) {
                        let origin = match origin {
                            ActivationOrigin::Automatic { .. } => ActivationOrigin::Automatic {
                                radio_mix_loaded: true,
                            },
                            ActivationOrigin::Manual => ActivationOrigin::Manual,
                        };
                        return Some(self.schedule_activation(track, origin));
                    }
                }
            }
        }

        self.sink = None;
        self.state.is_playing = false;
        self.state.elapsed_secs = 0;
        self.play_when_ready = false;
        if matches!(origin, ActivationOrigin::Manual) {
            self.invalidate_activation();
        }
        None
    }

    fn previous(&mut self) {
        if let Some(request) = self.schedule_previous() {
            self.spawn_activation(request);
        }
    }

    fn schedule_previous(&mut self) -> Option<ActivationRequest> {
        self.consecutive_errors = 0;
        self.invalidate_activation();
        pop_previous(&mut self.state)
            .map(|track| self.schedule_activation(track, ActivationOrigin::Manual))
    }

    fn toggle(&mut self) {
        if let Some(sink) = self.sink.as_ref() {
            self.play_when_ready = !self.state.is_playing;
            if self.play_when_ready {
                sink.play();
            } else {
                sink.pause();
            }
            self.state.is_playing = self.play_when_ready;
        } else if self.activation_pending {
            self.play_when_ready = !self.play_when_ready;
        } else if let Some(track) = self.state.current_track.clone() {
            self.activate(track, ActivationOrigin::Manual);
        } else {
            self.advance(ActivationOrigin::Manual);
        }
    }

    fn set_volume(&mut self, volume: f32) {
        apply_volume(&mut self.state, volume);
        if let Some(sink) = self.sink.as_ref() {
            sink.set_volume(self.state.volume);
        }
    }

    fn stop(&mut self) {
        self.invalidate_activation();
        if let Some(sink) = self.sink.take() {
            sink.stop();
        }
        self.state.current_track = None;
        self.state.is_playing = false;
        self.state.elapsed_secs = 0;
        self.state.status_message = None;
        self.play_when_ready = false;
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
        let (activation_tx, activation_rx) = mpsc::channel();
        let mut worker = Worker {
            state,
            sink: None,
            backend: AudioBackend::new(),
            state_tx: state_tx.clone(),
            consecutive_errors: 0,
            cookies_browser,
            activation_generation: 0,
            activation_tx,
            activation_rx,
            play_when_ready: false,
            activation_pending: false,
        };
        let mut media_keys = MediaKeyManager::new(media_tx);
        media_keys.sync_state(&worker.state);
        let mut running = true;
        while running {
            match cmd_rx.recv_timeout(Duration::from_millis(200)) {
                Ok(AudioCommand::SubmitInput(input)) => worker.submit(&input),
                Ok(AudioCommand::PlayTrack(track)) => {
                    worker.activate(track, ActivationOrigin::Manual);
                }
                Ok(AudioCommand::TogglePlayPause) => worker.toggle(),
                Ok(AudioCommand::Stop) => worker.stop(),
                Ok(AudioCommand::NextTrack) => worker.advance(ActivationOrigin::Manual),
                Ok(AudioCommand::PreviousTrack) => worker.previous(),
                Ok(AudioCommand::SetVolume(volume)) => worker.set_volume(volume),
                Ok(AudioCommand::SetAutoplay(enabled)) => worker.state.autoplay_enabled = enabled,
                Ok(AudioCommand::Seek(pos)) => {
                    let pos = clamped_seek_position(
                        pos,
                        worker
                            .state
                            .current_track
                            .as_ref()
                            .and_then(|track| track.duration_secs),
                    );
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
            worker.process_activation_results();
            if worker.state.is_playing {
                if let Some(sink) = worker.sink.as_ref() {
                    worker.state.elapsed_secs = sink.get_pos().as_secs();
                }
            }
            let completed =
                worker.state.is_playing && worker.sink.as_ref().is_some_and(Sink::empty);
            if completed {
                worker.advance(ActivationOrigin::Automatic {
                    radio_mix_loaded: false,
                });
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
    use std::{
        io::Write,
        net::{TcpListener, TcpStream},
        thread,
        time::Instant,
    };

    #[derive(Clone, Copy)]
    struct TestResponse {
        status: &'static str,
        content_range: Option<&'static str>,
        body: &'static [u8],
    }

    struct TestServer {
        url: String,
        requests: thread::JoinHandle<Vec<Option<String>>>,
    }

    impl TestServer {
        fn finish(self) -> Vec<Option<String>> {
            self.requests.join().unwrap()
        }
    }

    fn serve_http(responses: Vec<TestResponse>) -> TestServer {
        let listener = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        listener.set_nonblocking(true).unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let requests = thread::spawn(move || {
            let mut ranges = Vec::new();
            for response in responses {
                let deadline = Instant::now() + Duration::from_secs(1);
                let (mut stream, _) = loop {
                    match listener.accept() {
                        Ok(connection) => break connection,
                        Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                            assert!(
                                Instant::now() < deadline,
                                "timed out waiting for test request"
                            );
                            thread::yield_now();
                        }
                        Err(error) => panic!("test server accept failed: {error}"),
                    }
                };
                stream
                    .set_read_timeout(Some(Duration::from_secs(1)))
                    .unwrap();
                stream
                    .set_write_timeout(Some(Duration::from_secs(1)))
                    .unwrap();
                ranges.push(read_range_header(&mut stream));
                let mut headers = format!(
                    "HTTP/1.1 {}\r\nConnection: close\r\nContent-Length: {}\r\n",
                    response.status,
                    response.body.len()
                );
                if let Some(content_range) = response.content_range {
                    headers.push_str(&format!("Content-Range: {content_range}\r\n"));
                }
                headers.push_str("\r\n");
                stream.write_all(headers.as_bytes()).unwrap();
                stream.write_all(response.body).unwrap();
            }
            ranges
        });
        TestServer { url, requests }
    }

    fn read_range_header(stream: &mut TcpStream) -> Option<String> {
        let mut request = Vec::new();
        loop {
            let mut bytes = [0_u8; 1024];
            let count = stream.read(&mut bytes).unwrap();
            assert_ne!(count, 0);
            request.extend_from_slice(&bytes[..count]);
            assert!(request.len() <= 16 * 1024);
            if request.windows(4).any(|window| window == b"\r\n\r\n") {
                break;
            }
        }
        let request = String::from_utf8(request).unwrap();
        request.lines().find_map(|line| {
            let (name, value) = line.split_once(':')?;
            name.eq_ignore_ascii_case("range")
                .then(|| value.trim().to_owned())
        })
    }

    fn test_client() -> reqwest::blocking::Client {
        reqwest::blocking::Client::builder()
            .no_proxy()
            .timeout(Duration::from_secs(1))
            .build()
            .unwrap()
    }

    fn stalled_server() -> (String, thread::JoinHandle<()>) {
        let listener = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let handle = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream
                .set_read_timeout(Some(Duration::from_secs(1)))
                .unwrap();
            let _ = read_range_header(&mut stream);
            thread::sleep(Duration::from_millis(600));
        });
        (url, handle)
    }

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
        let (activation_tx, activation_rx) = mpsc::channel();
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
            activation_generation: 0,
            activation_tx,
            activation_rx,
            play_when_ready: false,
            activation_pending: false,
        }
    }

    fn worker_with_queue(consecutive_errors: u8) -> Worker {
        worker_with_tracks(consecutive_errors, &["next"])
    }

    fn failed_activation(generation: u64, origin: ActivationOrigin) -> ActivationResult {
        ActivationResult::Failed {
            generation,
            origin,
            error: StartError::NoAudioDevice,
        }
    }

    fn ready_activation(generation: u64, browser: Option<&str>) -> ActivationResult {
        ActivationResult::Ready {
            generation,
            origin: ActivationOrigin::Manual,
            decoder: Box::new(rodio::source::Zero::new(1, 44_100)),
            detected_browser: browser.map(str::to_owned),
        }
    }

    #[test]
    fn stream_io_timeouts_are_short() {
        assert!(STREAM_CONNECT_TIMEOUT <= Duration::from_secs(10));
        assert!(STREAM_IO_TIMEOUT <= Duration::from_secs(10));
    }

    #[test]
    fn blocking_timeout_aborts_a_stalled_range_request() {
        let (url, _server) = stalled_server();
        let client = reqwest::blocking::Client::builder()
            .no_proxy()
            .connect_timeout(Duration::from_millis(500))
            .timeout(Duration::from_millis(100))
            .build()
            .unwrap();
        let started = Instant::now();
        let result = client
            .get(url)
            .header(reqwest::header::RANGE, "bytes=0-")
            .send();
        let elapsed = started.elapsed();

        assert!(result.is_err());
        assert!(elapsed >= Duration::from_millis(80));
        assert!(elapsed < Duration::from_secs(2));
    }

    #[test]
    fn stop_clears_the_loading_status_message() {
        let mut worker = worker_with_queue(0);
        worker.state.status_message = Some("Loading stream…".to_owned());

        worker.stop();

        assert_eq!(worker.state.status_message, None);
        assert_eq!(worker.state.current_track, None);
        assert!(!worker.state.is_playing);
    }

    #[test]
    fn clamped_seek_position_stays_within_known_duration() {
        assert_eq!(
            clamped_seek_position(Duration::from_secs(200), Some(180)),
            Duration::from_secs(180)
        );
        assert_eq!(
            clamped_seek_position(Duration::from_secs(90), Some(180)),
            Duration::from_secs(90)
        );
        assert_eq!(
            clamped_seek_position(Duration::from_secs(200), None),
            Duration::from_secs(200)
        );
        assert_eq!(
            clamped_seek_position(Duration::from_secs(200), Some(0)),
            Duration::ZERO
        );
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
        assert!(worker
            .schedule_advance(ActivationOrigin::Automatic {
                radio_mix_loaded: false,
            })
            .is_none());
        assert_eq!(worker.state.queue.len(), 1);
        assert_eq!(worker.state.current_track, None);
        assert_eq!(
            worker.state.status_message.as_deref(),
            Some("Playback stopped: repeated errors")
        );
    }

    #[test]
    fn prepare_track_without_client_returns_no_audio_device() {
        assert!(matches!(
            prepare_track(&track("current"), None, None),
            Err(StartError::NoAudioDevice)
        ));
    }

    #[test]
    fn accepted_manual_failure_reports_no_audio_device_status() {
        let mut worker = worker_with_queue(0);
        let request = worker.schedule_activation(track("current"), ActivationOrigin::Manual);

        assert!(worker
            .handle_activation_result(failed_activation(
                request.generation,
                ActivationOrigin::Manual,
            ))
            .is_none());

        assert!(!worker.state.is_playing);
        assert_eq!(worker.consecutive_errors, 1);
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
        let first = worker
            .schedule_advance(ActivationOrigin::Automatic {
                radio_mix_loaded: false,
            })
            .unwrap();
        let second = worker
            .handle_activation_result(failed_activation(
                first.generation,
                ActivationOrigin::Automatic {
                    radio_mix_loaded: false,
                },
            ))
            .unwrap();
        let third = worker
            .handle_activation_result(failed_activation(
                second.generation,
                ActivationOrigin::Automatic {
                    radio_mix_loaded: false,
                },
            ))
            .unwrap();
        assert!(worker
            .handle_activation_result(failed_activation(
                third.generation,
                ActivationOrigin::Automatic {
                    radio_mix_loaded: false,
                },
            ))
            .is_none());

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
        let request = worker.schedule_advance(ActivationOrigin::Manual).unwrap();
        assert_eq!(request.origin, ActivationOrigin::Manual);
        assert_eq!(worker.consecutive_errors, 0);
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
            Some("Loading stream…")
        );
    }

    #[test]
    fn manual_previous_resets_the_error_guard() {
        let mut worker = worker_with_queue(3);
        worker.state.current_track = Some(track("current"));
        worker.state.history.push(track("previous"));
        let request = worker.schedule_previous().unwrap();
        assert_eq!(request.origin, ActivationOrigin::Manual);
        assert_eq!(worker.consecutive_errors, 0);
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
            Some("Loading stream…")
        );
    }

    #[test]
    fn stale_failed_activation_does_not_advance_or_change_state() {
        let mut worker = worker_with_tracks(1, &["queued"]);
        let stale = worker.schedule_activation(
            track("stale"),
            ActivationOrigin::Automatic {
                radio_mix_loaded: false,
            },
        );
        let current = worker.schedule_activation(track("current"), ActivationOrigin::Manual);

        assert!(worker
            .handle_activation_result(failed_activation(
                stale.generation,
                ActivationOrigin::Automatic {
                    radio_mix_loaded: false,
                },
            ))
            .is_none());

        assert_eq!(worker.activation_generation, current.generation);
        assert_eq!(worker.consecutive_errors, 1);
        assert_eq!(
            worker
                .state
                .current_track
                .as_ref()
                .map(|track| track.id.as_str()),
            Some("current")
        );
        assert_eq!(
            worker.state.queue.front().map(|track| track.id.as_str()),
            Some("queued")
        );
        assert_eq!(
            worker.state.status_message.as_deref(),
            Some("Loading stream…")
        );
        assert!(worker.activation_pending);
    }

    #[test]
    fn stale_ready_activation_does_not_install_or_cache_browser() {
        let mut worker = worker_with_queue(2);
        let stale = worker.schedule_activation(track("stale"), ActivationOrigin::Manual);
        let current = worker.schedule_activation(track("current"), ActivationOrigin::Manual);

        assert!(worker
            .handle_activation_result(ready_activation(stale.generation, Some("firefox")))
            .is_none());

        assert_eq!(worker.activation_generation, current.generation);
        assert_eq!(worker.cookies_browser, None);
        assert_eq!(worker.consecutive_errors, 2);
        assert!(worker.sink.is_none());
        assert_eq!(
            worker
                .state
                .current_track
                .as_ref()
                .map(|track| track.id.as_str()),
            Some("current")
        );
        assert_eq!(
            worker.state.status_message.as_deref(),
            Some("Loading stream…")
        );
        assert!(worker.activation_pending);
    }

    #[test]
    fn toggle_while_loading_changes_playback_intent_without_new_generation() {
        let mut worker = worker_with_queue(0);
        let request = worker.schedule_activation(track("current"), ActivationOrigin::Manual);

        worker.toggle();

        assert_eq!(worker.activation_generation, request.generation);
        assert!(worker.activation_pending);
        assert!(!worker.play_when_ready);
        assert!(!worker.state.is_playing);
    }

    #[test]
    fn volume_change_while_loading_keeps_the_activation_current() {
        let mut worker = worker_with_queue(0);
        let request = worker.schedule_activation(track("current"), ActivationOrigin::Manual);

        worker.set_volume(1.25);

        assert_eq!(worker.activation_generation, request.generation);
        assert!(worker.activation_pending);
        assert!((worker.state.volume - 1.25).abs() < f32::EPSILON);
    }

    #[test]
    fn automatic_failure_preserves_paused_loading_intent() {
        let mut worker = worker_with_tracks(0, &["one", "two"]);
        let first = worker
            .schedule_advance(ActivationOrigin::Automatic {
                radio_mix_loaded: false,
            })
            .unwrap();
        worker.toggle();

        let second = worker
            .handle_activation_result(failed_activation(
                first.generation,
                ActivationOrigin::Automatic {
                    radio_mix_loaded: false,
                },
            ))
            .unwrap();

        assert!(matches!(
            second.origin,
            ActivationOrigin::Automatic {
                radio_mix_loaded: false
            }
        ));
        assert!(worker.activation_pending);
        assert!(!worker.play_when_ready);
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
    fn http_range_reader_seeks_with_validated_ranges() {
        // Given: a server that honors the initial and subsequent byte ranges.
        let server = serve_http(vec![
            TestResponse {
                status: "206 Partial Content",
                content_range: Some("bytes 0-1/6"),
                body: b"ab",
            },
            TestResponse {
                status: "206 Partial Content",
                content_range: Some("bytes 2-5/6"),
                body: b"cdef",
            },
            TestResponse {
                status: "206 Partial Content",
                content_range: Some("bytes 4-5/6"),
                body: b"ef",
            },
            TestResponse {
                status: "206 Partial Content",
                content_range: Some("bytes 5-5/6"),
                body: b"f",
            },
            TestResponse {
                status: "206 Partial Content",
                content_range: Some("bytes 4-5/6"),
                body: b"ef",
            },
        ]);
        let client = test_client();
        let mut reader = HttpRangeReader::open(&client, server.url.clone(), None).unwrap();

        // When: the reader uses current, absolute, and end-relative seeking.
        let mut prefix = [0_u8; 2];
        reader.read_exact(&mut prefix).unwrap();
        assert_eq!(reader.stream_position().unwrap(), 2);
        let mut continued = [0_u8; 1];
        reader.read_exact(&mut continued).unwrap();
        assert_eq!(reader.seek(SeekFrom::Start(4)).unwrap(), 4);
        assert_eq!(reader.seek(SeekFrom::Current(1)).unwrap(), 5);
        assert_eq!(reader.seek(SeekFrom::End(-2)).unwrap(), 4);
        assert_eq!(reader.seek(SeekFrom::Start(4)).unwrap(), 4);
        let mut suffix = [0_u8; 1];
        reader.read_exact(&mut suffix).unwrap();

        // Then: only actual repositioning reconnects and each response begins at its request.
        assert_eq!(&prefix, b"ab");
        assert_eq!(&continued, b"c");
        assert_eq!(&suffix, b"e");
        assert_eq!(
            reader.seek(SeekFrom::Current(-7)).unwrap_err().kind(),
            io::ErrorKind::InvalidInput
        );
        assert_eq!(
            reader.seek(SeekFrom::End(1)).unwrap_err().kind(),
            io::ErrorKind::InvalidInput
        );
        drop(reader);
        assert_eq!(
            server.finish(),
            vec![
                Some("bytes=0-".to_owned()),
                Some("bytes=2-".to_owned()),
                Some("bytes=4-".to_owned()),
                Some("bytes=5-".to_owned()),
                Some("bytes=4-".to_owned()),
            ]
        );
    }

    #[test]
    fn http_range_reader_does_not_reconnect_for_zero_offset_seek() {
        // Given: one complete range response and no server capacity for a second request.
        let server = serve_http(vec![TestResponse {
            status: "206 Partial Content",
            content_range: Some("bytes 0-5/6"),
            body: b"abcdef",
        }]);
        let client = test_client();
        let mut reader = HttpRangeReader::open(&client, server.url.clone(), None).unwrap();
        let mut first = [0_u8; 1];
        reader.read_exact(&mut first).unwrap();

        // When: the logical position is queried and reselected without an offset.
        assert_eq!(reader.stream_position().unwrap(), 1);
        assert_eq!(reader.seek(SeekFrom::Start(1)).unwrap(), 1);
        drop(reader);

        // Then: the original response remains active and no second connection is attempted.
        assert_eq!(&first, b"a");
        assert_eq!(server.finish(), vec![Some("bytes=0-".to_owned())]);
    }

    #[test]
    fn http_range_reader_rejects_ignored_reseek_without_moving_position() {
        // Given: a seekable initial response followed by a server that ignores a reseek.
        let server = serve_http(vec![
            TestResponse {
                status: "206 Partial Content",
                content_range: Some("bytes 0-5/6"),
                body: b"abcdef",
            },
            TestResponse {
                status: "200 OK",
                content_range: None,
                body: b"abcdef",
            },
        ]);
        let client = test_client();
        let mut reader = HttpRangeReader::open(&client, server.url.clone(), None).unwrap();
        let mut first = [0_u8; 1];
        reader.read_exact(&mut first).unwrap();

        // When: a nonzero reseek receives an ignored Range request.
        let error = reader.seek(SeekFrom::Start(3)).unwrap_err();
        let mut next = [0_u8; 1];
        reader.read_exact(&mut next).unwrap();
        drop(reader);

        // Then: the failed seek reports invalid data and leaves the active stream position intact.
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
        assert_eq!(&first, b"a");
        assert_eq!(&next, b"b");
        assert_eq!(
            server.finish(),
            vec![Some("bytes=0-".to_owned()), Some("bytes=3-".to_owned())]
        );
    }

    #[test]
    fn http_range_reader_streams_when_initial_range_is_unparseable() {
        // Given: a server that claims partial content without a parseable total length.
        let server = serve_http(vec![TestResponse {
            status: "206 Partial Content",
            content_range: Some("bytes 0-5/*"),
            body: b"abcdef",
        }]);
        let client = test_client();
        let mut reader = HttpRangeReader::open(&client, server.url.clone(), None).unwrap();

        // When: the stream is consumed and a seek is attempted.
        let mut body = Vec::new();
        reader.read_to_end(&mut body).unwrap();
        let seek_error = reader.seek(SeekFrom::Start(1)).unwrap_err();
        drop(reader);

        // Then: playback still works forward while random access is refused.
        assert_eq!(&body, b"abcdef");
        assert_eq!(seek_error.kind(), io::ErrorKind::Unsupported);
        assert_eq!(server.finish(), vec![Some("bytes=0-".to_owned())]);
    }

    #[test]
    fn http_range_reader_rejects_mismatched_reseek_range() {
        // Given: a valid initial range and a later response beginning at the wrong byte.
        let server = serve_http(vec![
            TestResponse {
                status: "206 Partial Content",
                content_range: Some("bytes 0-5/6"),
                body: b"abcdef",
            },
            TestResponse {
                status: "206 Partial Content",
                content_range: Some("bytes 2-5/6"),
                body: b"cdef",
            },
        ]);
        let client = test_client();
        let mut reader = HttpRangeReader::open(&client, server.url.clone(), None).unwrap();
        let mut first = [0_u8; 1];
        reader.read_exact(&mut first).unwrap();

        // When: the server returns a mismatched Content-Range for a requested byte.
        let error = reader.seek(SeekFrom::Start(3)).unwrap_err();
        let mut next = [0_u8; 1];
        reader.read_exact(&mut next).unwrap();
        drop(reader);

        // Then: the reader preserves its original response and position.
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
        assert_eq!(&first, b"a");
        assert_eq!(&next, b"b");
        assert_eq!(
            server.finish(),
            vec![Some("bytes=0-".to_owned()), Some("bytes=3-".to_owned())]
        );
    }

    #[test]
    fn http_range_reader_rejects_changed_total_length() {
        // Given: an initial range with a known total and a reseek response claiming another one.
        let server = serve_http(vec![
            TestResponse {
                status: "206 Partial Content",
                content_range: Some("bytes 0-5/6"),
                body: b"abcdef",
            },
            TestResponse {
                status: "206 Partial Content",
                content_range: Some("bytes 3-6/7"),
                body: b"defg",
            },
        ]);
        let client = test_client();
        let mut reader = HttpRangeReader::open(&client, server.url.clone(), None).unwrap();
        let mut first = [0_u8; 1];
        reader.read_exact(&mut first).unwrap();

        // When: the server changes its declared total length on a reseek.
        let error = reader.seek(SeekFrom::Start(3)).unwrap_err();
        let mut next = [0_u8; 1];
        reader.read_exact(&mut next).unwrap();
        drop(reader);

        // Then: the changed total is rejected without advancing the original response.
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
        assert_eq!(&first, b"a");
        assert_eq!(&next, b"b");
        assert_eq!(
            server.finish(),
            vec![Some("bytes=0-".to_owned()), Some("bytes=3-".to_owned())]
        );
    }

    #[test]
    fn http_ok_stream_remains_non_seekable() {
        // Given: a server that streams an OK response instead of partial content.
        let server = serve_http(vec![TestResponse {
            status: "200 OK",
            content_range: None,
            body: b"abcdef",
        }]);
        let client = test_client();
        let mut reader = HttpRangeReader::open(&client, server.url.clone(), None).unwrap();

        // When: random access is requested.
        let no_op_error = reader.seek(SeekFrom::Start(0)).unwrap_err();
        let error = reader.seek(SeekFrom::Start(1)).unwrap_err();
        let mut bytes = [0_u8; 2];
        reader.read_exact(&mut bytes).unwrap();
        drop(reader);

        // Then: seeking is rejected but sequential playback continues from byte zero.
        assert_eq!(no_op_error.kind(), io::ErrorKind::Unsupported);
        assert_eq!(error.kind(), io::ErrorKind::Unsupported);
        assert_eq!(&bytes, b"ab");
        assert_eq!(server.finish(), vec![Some("bytes=0-".to_owned())]);
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
}
