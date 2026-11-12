use std::{
    io::{ErrorKind, Read},
    net::{Ipv4Addr, Ipv6Addr},
    process::{Command, ExitStatus, Stdio},
    thread,
    time::{Duration, Instant},
};

use url::{Host, Url};

use crate::models::TrackItem;

const METADATA_FORMAT: &str = "%(id)s:::%(title)s:::%(uploader)s:::%(webpage_url)s:::%(duration)s";
const SEARCH_METADATA_FORMAT: &str = "%(id)s:::%(title)s:::%(uploader)s:::%(url)s:::%(duration)s";
const FLAT_PLAYLIST_FORMAT: &str = "%(id)s:::%(title)s:::%(url)s";
const STREAM_FORMAT: &str =
    "ba[ext=m4a][protocol^=http]/ba[ext=mp3][protocol^=http]/ba[acodec^=vorbis][protocol^=http]/ba[protocol^=http]/ba/b[protocol^=http]/b";
const SPOTIFY_OEMBED_URL: &str = "https://open.spotify.com/oembed";
const YTDLP_TIMEOUT: Duration = Duration::from_secs(30);
const YTDLP_POLL_INTERVAL: Duration = Duration::from_millis(50);
const YTDLP_BASE_ARGS: &[&str] = &[
    "--socket-timeout",
    "10",
    "--retries",
    "1",
    "--extractor-retries",
    "1",
];

#[derive(Debug, thiserror::Error)]
pub enum ExtractorError {
    #[error("yt-dlp was not found in PATH")]
    MissingBinary,
    #[error("input is empty")]
    EmptyInput,
    #[error("private/loopback addresses are not allowed")]
    PrivateNetwork,
    #[error("yt-dlp I/O error: {0}")]
    SubprocessIo(#[from] std::io::Error),
    #[error("yt-dlp failed: {0}")]
    SubprocessFailed(String),
    #[error("yt-dlp timed out after {0:?}")]
    Timeout(Duration),
    #[error("HTTP request failed: {0}")]
    Http(#[from] reqwest::Error),
    #[error("no tracks found")]
    NoResults,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum InputKind {
    SearchQuery(String),
    DirectUrl(String),
    YouTubePlaylist(String),
    SpotifyUrl(String),
}

fn is_private_host(raw: &str) -> bool {
    let normalized = raw.trim().to_ascii_lowercase();
    if !(normalized.starts_with("http://") || normalized.starts_with("https://")) {
        return false;
    }
    let Ok(url) = Url::parse(&normalized) else {
        return true;
    };
    let Some(host) = url.host() else {
        return true;
    };
    match host {
        Host::Domain(domain) => is_private_domain(domain),
        Host::Ipv4(address) => is_private_ipv4(address),
        Host::Ipv6(address) => is_private_ipv6(address),
    }
}

fn is_private_domain(domain: &str) -> bool {
    let domain = domain.trim_end_matches('.');
    domain == "localhost"
        || domain.ends_with(".localhost")
        || domain.ends_with(".local")
        || domain.ends_with(".internal")
}

fn is_private_ipv4(address: Ipv4Addr) -> bool {
    let octets = address.octets();
    address.is_loopback()
        || address.is_private()
        || address.is_link_local()
        || address.is_unspecified()
        || address.is_broadcast()
        || (octets[0] == 100 && (64..=127).contains(&octets[1]))
}

fn is_private_ipv6(address: Ipv6Addr) -> bool {
    if address.is_loopback() || address.is_unspecified() {
        return true;
    }
    if let Some(mapped) = address.to_ipv4_mapped().or_else(|| address.to_ipv4()) {
        return is_private_ipv4(mapped);
    }
    (address.segments()[0] & 0xfe00) == 0xfc00 || (address.segments()[0] & 0xffc0) == 0xfe80
}

pub fn classify_input(raw: &str) -> Result<InputKind, ExtractorError> {
    let input = raw.trim();
    if input.is_empty() {
        return Err(ExtractorError::EmptyInput);
    }
    let value = input.to_owned();
    if input.starts_with("http://")
        || input.starts_with("https://")
        || input.starts_with("spotify:")
    {
        if is_private_host(input) {
            return Err(ExtractorError::PrivateNetwork);
        }
        if input.contains("open.spotify.com") || input.starts_with("spotify:") {
            Ok(InputKind::SpotifyUrl(value))
        } else if input.contains("list=") || input.contains("/playlist") {
            Ok(InputKind::YouTubePlaylist(value))
        } else {
            Ok(InputKind::DirectUrl(value))
        }
    } else {
        Ok(InputKind::SearchQuery(value))
    }
}

pub fn is_ytdlp_available() -> bool {
    matches!(
        Command::new("yt-dlp").arg("--version").stdout(Stdio::null()).stderr(Stdio::null()).status(),
        Ok(status) if status.success()
    )
}

pub fn resolve_input(raw: &str) -> Result<Vec<TrackItem>, ExtractorError> {
    match classify_input(raw)? {
        InputKind::SearchQuery(query) => resolve_search(&query),
        InputKind::DirectUrl(url) => Ok(vec![resolve_metadata(&url)?]),
        InputKind::YouTubePlaylist(url) => {
            require_tracks(flat_tracks(&run_ytdlp(&playlist_flags(), &url)?))
        }
        InputKind::SpotifyUrl(url) => resolve_spotify(&url),
    }
}

pub const BROWSER_CANDIDATES: &[&str] = &["firefox", "brave", "chrome", "chromium"];

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StreamInfo {
    pub url: String,
    pub user_agent: Option<String>,
    pub browser_used: Option<String>,
}

#[derive(serde::Deserialize)]
struct YtdlpStreamJson {
    url: Option<String>,
    http_headers: Option<std::collections::HashMap<String, String>>,
}

pub fn parse_stream_json(raw: &str) -> Option<StreamInfo> {
    for line in raw.lines() {
        let trimmed = line.trim();
        if trimmed.starts_with('{') {
            if let Ok(data) = serde_json::from_str::<YtdlpStreamJson>(trimmed) {
                if let Some(url) = data.url.filter(|u| !u.trim().is_empty()) {
                    let user_agent = data.http_headers.and_then(|mut headers| {
                        headers
                            .remove("User-Agent")
                            .or_else(|| headers.remove("user-agent"))
                    });
                    return Some(StreamInfo {
                        url,
                        user_agent,
                        browser_used: None,
                    });
                }
            }
        }
    }
    raw.lines()
        .map(str::trim)
        .find(|line| line.starts_with("http://") || line.starts_with("https://"))
        .map(|url| StreamInfo {
            url: url.to_owned(),
            user_agent: None,
            browser_used: None,
        })
}

/// Must stay cookie-free: yt-dlp skips the `visionos` client whenever cookies
/// are supplied, then falls back to a WEB_EMBEDDED_PLAYER URL that 403s on Range.
fn primary_stream_flags() -> [&'static str; 6] {
    [
        "-j",
        "-f",
        STREAM_FORMAT,
        "--no-playlist",
        "--extractor-args",
        "youtube:player_client=visionos,default",
    ]
}

/// `c=TVHTML5` URLs answer Range requests with 403.
fn is_usable_stream_url(url: &str) -> bool {
    !url.contains("c=TVHTML5")
}

pub fn resolve_stream_info(
    original_url: &str,
    cookies_browser: Option<&str>,
) -> Result<StreamInfo, ExtractorError> {
    let mut last_error = None;

    let flags = primary_stream_flags();
    match run_ytdlp(&flags, original_url) {
        Ok(output) => {
            if let Some(info) = parse_stream_json(&output) {
                if is_usable_stream_url(&info.url) {
                    return Ok(info);
                }
            }
        }
        Err(e) => last_error = Some(e),
    }

    if let Some(browser) = cookies_browser {
        let flags = [
            "-j",
            "-f",
            STREAM_FORMAT,
            "--no-playlist",
            "--cookies-from-browser",
            browser,
        ];
        match run_ytdlp(&flags, original_url) {
            Ok(output) => {
                if let Some(mut info) = parse_stream_json(&output) {
                    if is_usable_stream_url(&info.url) {
                        info.browser_used = Some(browser.to_owned());
                        return Ok(info);
                    }
                }
            }
            Err(e) => last_error = Some(e),
        }
    }

    for &candidate in BROWSER_CANDIDATES {
        if cookies_browser == Some(candidate) {
            continue;
        }
        let flags = [
            "-j",
            "-f",
            STREAM_FORMAT,
            "--no-playlist",
            "--cookies-from-browser",
            candidate,
        ];
        match run_ytdlp(&flags, original_url) {
            Ok(output) => {
                if let Some(mut info) = parse_stream_json(&output) {
                    if is_usable_stream_url(&info.url) {
                        info.browser_used = Some(candidate.to_owned());
                        return Ok(info);
                    }
                }
            }
            Err(e) => {
                let is_missing_browser = match &e {
                    ExtractorError::SubprocessFailed(msg) => {
                        let lower = msg.to_ascii_lowercase();
                        lower.contains("could not find") && lower.contains("cookies")
                    }
                    _ => false,
                };
                if !is_missing_browser {
                    last_error = Some(e);
                }
            }
        }
    }

    let fallback_flags = [
        "--extractor-args",
        "youtube:player_client=android,web",
        "-j",
        "-f",
        STREAM_FORMAT,
        "--no-playlist",
    ];
    if let Ok(output) = run_ytdlp(&fallback_flags, original_url) {
        if let Some(info) = parse_stream_json(&output) {
            return Ok(info);
        }
    }

    Err(last_error.unwrap_or(ExtractorError::NoResults))
}

pub fn canonical_title(title: &str) -> String {
    let base = title.split(['(', '[']).next().unwrap_or(title);
    base.chars()
        .map(|ch| {
            if ch.is_alphanumeric() {
                ch.to_ascii_lowercase()
            } else {
                ' '
            }
        })
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

pub fn fetch_related_tracks(last_track: &TrackItem) -> Result<Vec<TrackItem>, ExtractorError> {
    let mix = format!(
        "https://www.youtube.com/watch?v={}&list=RD{}",
        last_track.id, last_track.id
    );
    let raw = run_ytdlp(
        &[
            "--flat-playlist",
            "-I",
            "2:10",
            "--print",
            SEARCH_METADATA_FORMAT,
        ],
        &mix,
    )
    .or_else(|_| {
        let seed = last_track.artist.as_deref().unwrap_or(&last_track.title);
        run_ytdlp(
            &[
                "--flat-playlist",
                "--match-filter",
                "live_status!=is_live",
                "--print",
                SEARCH_METADATA_FORMAT,
            ],
            &format!("ytsearch6:{seed} audio"),
        )
    })?;
    let mut seen = vec![canonical_title(&last_track.title)];
    let tracks = raw
        .lines()
        .filter_map(parse_metadata_line)
        .filter(|track| {
            let canon = canonical_title(&track.title);
            let fresh = track.id != last_track.id && !canon.is_empty() && !seen.contains(&canon);
            if fresh {
                seen.push(canon);
            }
            fresh
        })
        .take(4)
        .collect();
    require_tracks(tracks)
}

fn resolve_search(query: &str) -> Result<Vec<TrackItem>, ExtractorError> {
    run_ytdlp(
        &[
            "--flat-playlist",
            "--match-filter",
            "live_status!=is_live",
            "--print",
            SEARCH_METADATA_FORMAT,
        ],
        &format!("ytsearch3:{query}"),
    )?
    .lines()
    .find_map(parse_metadata_line)
    .map(|track| vec![track])
    .ok_or(ExtractorError::NoResults)
}

fn resolve_metadata(target: &str) -> Result<TrackItem, ExtractorError> {
    run_ytdlp(&["--no-playlist", "--print", METADATA_FORMAT], target)?
        .lines()
        .find_map(parse_metadata_line)
        .ok_or(ExtractorError::NoResults)
}

fn resolve_spotify(url: &str) -> Result<Vec<TrackItem>, ExtractorError> {
    let client = reqwest::blocking::Client::builder()
        .user_agent("Mozilla/5.0")
        .timeout(Duration::from_secs(5))
        .build()?;
    if let Some((kind, id)) = parse_spotify_resource(url) {
        let embed_url = format!("https://open.spotify.com/embed/{kind}/{id}");
        if let Ok(html) = client
            .get(&embed_url)
            .send()
            .and_then(reqwest::blocking::Response::error_for_status)
            .and_then(reqwest::blocking::Response::text)
        {
            if let Some(tracks) = parse_spotify_embed_tracks(&html) {
                return Ok(tracks);
            }
        }
    }
    let json = client
        .get(SPOTIFY_OEMBED_URL)
        .query(&[("url", url)])
        .send()?
        .error_for_status()?
        .text()?;
    resolve_search(&parse_spotify_oembed_title(&json).ok_or(ExtractorError::NoResults)?)
}

pub fn parse_spotify_resource(url: &str) -> Option<(&str, &str)> {
    let clean = url.trim().split(['?', '#']).next()?;
    let rest = clean
        .strip_prefix("spotify:")
        .or_else(|| clean.split("open.spotify.com/").nth(1))?;
    let mut parts = rest
        .split(['/', ':'])
        .filter(|s| !s.is_empty() && *s != "embed" && !s.starts_with("intl-"));
    let (kind, id) = (parts.next()?, parts.next()?);
    matches!(kind, "track" | "playlist" | "album")
        .then_some(())
        .filter(|_| id.bytes().all(|b| b.is_ascii_alphanumeric()))
        .map(|_| (kind, id))
}

pub fn parse_spotify_embed_tracks(html: &str) -> Option<Vec<TrackItem>> {
    let json_str = html
        .split(r#"<script id="__NEXT_DATA__" type="application/json">"#)
        .nth(1)?
        .split("</script>")
        .next()?;
    let value: serde_json::Value = serde_json::from_str(json_str).ok()?;
    let entity = value.pointer("/props/pageProps/state/data/entity")?;
    if let Some(list) = entity.get("trackList").and_then(|v| v.as_array()) {
        let tracks: Vec<TrackItem> = list.iter().filter_map(spotify_track_item).collect();
        (!tracks.is_empty()).then_some(tracks)
    } else {
        spotify_track_item(entity).map(|track| vec![track])
    }
}

fn spotify_track_item(entry: &serde_json::Value) -> Option<TrackItem> {
    let title = entry
        .get("title")
        .or_else(|| entry.get("name"))
        .and_then(|v| v.as_str())?
        .trim();
    if title.is_empty() {
        return None;
    }
    let raw_artist = entry
        .get("subtitle")
        .and_then(|v| v.as_str())
        .or_else(|| entry.pointer("/artists/0/name").and_then(|v| v.as_str()))
        .unwrap_or("");
    let artist_clean = raw_artist.replace('\u{a0}', " ");
    let artist = (!artist_clean.trim().is_empty()).then(|| artist_clean.trim().to_owned());
    let id = entry
        .get("uri")
        .and_then(|v| v.as_str())
        .and_then(|u| u.rsplit(':').next())
        .filter(|s| !s.is_empty())
        .unwrap_or(title)
        .to_owned();
    let original_url = match artist.as_deref() {
        Some(a) => format!("ytsearch1:{title} {a} Official Audio"),
        None => format!("ytsearch1:{title} Official Audio"),
    };
    Some(TrackItem {
        id,
        title: title.to_owned(),
        artist,
        original_url,
        duration_secs: entry
            .get("duration")
            .and_then(|v| v.as_u64())
            .map(|ms| ms / 1000),
    })
}

fn flat_tracks(output: &str) -> Vec<TrackItem> {
    output
        .lines()
        .filter_map(parse_flat_playlist_line)
        .collect()
}

fn playlist_flags() -> [&'static str; 5] {
    [
        "--flat-playlist",
        "--playlist-end",
        "200",
        "--print",
        FLAT_PLAYLIST_FORMAT,
    ]
}

fn require_tracks(tracks: Vec<TrackItem>) -> Result<Vec<TrackItem>, ExtractorError> {
    if tracks.is_empty() {
        Err(ExtractorError::NoResults)
    } else {
        Ok(tracks)
    }
}

struct ProcessOutput {
    status: ExitStatus,
    stdout: Vec<u8>,
    stderr: Vec<u8>,
}

fn drain_pipe<R: Read + Send + 'static>(pipe: Option<R>) -> Option<thread::JoinHandle<Vec<u8>>> {
    pipe.map(|mut pipe| {
        thread::spawn(move || {
            let mut buffer = Vec::new();
            let _ = pipe.read_to_end(&mut buffer);
            buffer
        })
    })
}

fn join_pipe(reader: Option<thread::JoinHandle<Vec<u8>>>) -> Vec<u8> {
    reader
        .and_then(|reader| reader.join().ok())
        .unwrap_or_default()
}

/// Run a command with a hard wall-clock deadline. Output pipes are drained on
/// background threads so a chatty child cannot deadlock on a full pipe buffer.
fn run_command_with_timeout(
    mut command: Command,
    timeout: Duration,
) -> Result<ProcessOutput, ExtractorError> {
    command
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    let mut child = command.spawn()?;
    let stdout_reader = drain_pipe(child.stdout.take());
    let stderr_reader = drain_pipe(child.stderr.take());
    let deadline = Instant::now() + timeout;
    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break status,
            Ok(None) if Instant::now() >= deadline => {
                let _ = child.kill();
                let _ = child.wait();
                drop(stdout_reader);
                drop(stderr_reader);
                return Err(ExtractorError::Timeout(timeout));
            }
            Ok(None) => thread::sleep(YTDLP_POLL_INTERVAL),
            Err(error) => {
                let _ = child.kill();
                let _ = child.wait();
                drop(stdout_reader);
                drop(stderr_reader);
                return Err(error.into());
            }
        }
    };
    Ok(ProcessOutput {
        status,
        stdout: join_pipe(stdout_reader),
        stderr: join_pipe(stderr_reader),
    })
}

/// Run yt-dlp with hardened defaults. `target` is the positional arg (URL/query)
/// inserted after a `--` delimiter to prevent flag injection.
fn run_ytdlp(flags: &[&str], target: &str) -> Result<String, ExtractorError> {
    let mut command = Command::new("yt-dlp");
    command
        .args(YTDLP_BASE_ARGS)
        .args(flags)
        .arg("--")
        .arg(target);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000);
    }
    let output = run_command_with_timeout(command, YTDLP_TIMEOUT).map_err(|error| match error {
        ExtractorError::SubprocessIo(io_error) if io_error.kind() == ErrorKind::NotFound => {
            ExtractorError::MissingBinary
        }
        other => other,
    })?;
    if output.status.success() {
        Ok(String::from_utf8_lossy(&output.stdout).into_owned())
    } else {
        let stderr = String::from_utf8_lossy(&output.stderr).trim().to_owned();
        Err(ExtractorError::SubprocessFailed(if stderr.is_empty() {
            output.status.to_string()
        } else {
            stderr
        }))
    }
}

pub fn parse_metadata_line(line: &str) -> Option<TrackItem> {
    let mut fields = line.trim().split(":::");
    let (id, title, artist, original_url, duration) = (
        fields.next()?.trim(),
        fields.next()?.trim(),
        fields.next()?.trim(),
        fields.next()?.trim(),
        fields.next()?.trim(),
    );
    if fields.next().is_some()
        || id.is_empty()
        || title.is_empty()
        || original_url.is_empty()
        || original_url == "NA"
    {
        return None;
    }
    let duration_secs = duration.parse::<u64>().ok().or_else(|| {
        duration
            .parse::<f64>()
            .ok()
            .filter(|&secs| secs >= 0.0)
            .map(|secs| secs as u64)
    });
    Some(TrackItem {
        id: id.to_owned(),
        title: title.to_owned(),
        artist: (!artist.is_empty() && artist != "NA").then(|| artist.to_owned()),
        original_url: original_url.to_owned(),
        duration_secs,
    })
}

pub fn parse_flat_playlist_line(line: &str) -> Option<TrackItem> {
    let mut fields = line.trim().split(":::");
    let (id, title, original_url) = (
        fields.next()?.trim(),
        fields.next()?.trim(),
        fields.next()?.trim(),
    );
    if fields.next().is_some()
        || id.is_empty()
        || title.is_empty()
        || original_url.is_empty()
        || original_url == "NA"
    {
        return None;
    }
    Some(TrackItem {
        id: id.to_owned(),
        title: title.to_owned(),
        artist: None,
        original_url: original_url.to_owned(),
        duration_secs: None,
    })
}

pub fn parse_spotify_oembed_title(json_str: &str) -> Option<String> {
    let value: serde_json::Value = serde_json::from_str(json_str).ok()?;
    let title = value.get("title")?.as_str()?.trim();
    (!title.is_empty()).then(|| title.to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classifies_trimmed_input_and_every_kind() {
        assert!(
            matches!(classify_input(" \tlofi beats\n"), Ok(InputKind::SearchQuery(value)) if value == "lofi beats")
        );
        assert!(matches!(
            classify_input("https://soundcloud.com/a/b"),
            Ok(InputKind::DirectUrl(_))
        ));
        assert!(matches!(
            classify_input("https://youtube.com/playlist?list=abc"),
            Ok(InputKind::YouTubePlaylist(_))
        ));
        assert!(matches!(
            classify_input("https://youtube.com/watch?v=a&list=b"),
            Ok(InputKind::YouTubePlaylist(_))
        ));
        assert!(matches!(
            classify_input("https://open.spotify.com/track/abc"),
            Ok(InputKind::SpotifyUrl(_))
        ));
        assert!(matches!(
            classify_input("spotify:track:abc"),
            Ok(InputKind::SpotifyUrl(_))
        ));
        assert!(matches!(
            classify_input("  \n"),
            Err(ExtractorError::EmptyInput)
        ));
        assert!(matches!(
            classify_input("http://127.0.0.1:8080/stream"),
            Err(ExtractorError::PrivateNetwork)
        ));
        assert!(matches!(
            classify_input("http://127.0.0.1:8080/playlist?list=123"),
            Err(ExtractorError::PrivateNetwork)
        ));
        assert!(matches!(
            classify_input("https://localhost/stream"),
            Err(ExtractorError::PrivateNetwork)
        ));
        assert!(matches!(
            classify_input("http://192.168.1.1/audio"),
            Err(ExtractorError::PrivateNetwork)
        ));
        assert!(matches!(
            classify_input("http://10.0.0.1/audio"),
            Err(ExtractorError::PrivateNetwork)
        ));
    }

    #[test]
    fn ytdlp_base_args_enable_single_retry_and_extractor_retry() {
        // Given/When: the hardened base arguments used for every yt-dlp call.
        // Then: retries stay capped so failures are bounded by the watchdog.
        assert!(YTDLP_BASE_ARGS
            .windows(2)
            .any(|pair| pair[0] == "--retries" && pair[1] == "1"));
        assert!(YTDLP_BASE_ARGS
            .windows(2)
            .any(|pair| pair[0] == "--extractor-retries" && pair[1] == "1"));
    }

    #[cfg(unix)]
    #[test]
    fn watchdog_kills_a_command_that_outlives_the_timeout() {
        // Given: a command that sleeps far longer than the watchdog window.
        let started = Instant::now();
        let mut command = Command::new("sh");
        command.args(["-c", "exec sleep 5"]);

        // When: it is run with a short timeout.
        let result = run_command_with_timeout(command, Duration::from_millis(150));

        // Then: the watchdog kills it promptly and reports a timeout.
        assert!(matches!(result, Err(ExtractorError::Timeout(_))));
        assert!(started.elapsed() < Duration::from_secs(2));
    }

    #[cfg(unix)]
    #[test]
    fn watchdog_returns_without_waiting_for_a_grandchild_holding_the_pipes() {
        // Given: a shell that leaves a background child holding the inherited pipes.
        let started = Instant::now();
        let mut command = Command::new("sh");
        command.args(["-c", "sleep 5 & wait"]);

        // When: the shell is killed by the watchdog.
        let result = run_command_with_timeout(command, Duration::from_millis(150));

        // Then: the watchdog returns without joining the still-open pipe readers.
        assert!(matches!(result, Err(ExtractorError::Timeout(_))));
        assert!(
            started.elapsed() < Duration::from_secs(2),
            "watchdog waited for the grandchild"
        );
    }

    #[cfg(unix)]
    #[test]
    fn captures_output_larger_than_the_pipe_buffer_without_deadlock() {
        // Given: a command that writes far more than the OS pipe buffer to stdout.
        let script = "i=0; while [ $i -lt 5000 ]; do echo 0123456789012345678901234567890123456789012345678901234567890123; i=$((i+1)); done; echo err >&2";
        let mut command = Command::new("sh");
        command.args(["-c", script]);

        // When: it is run through the watchdog-aware helper.
        let output = run_command_with_timeout(command, Duration::from_secs(10))
            .expect("command should complete");

        // Then: both streams are drained fully instead of blocking the child.
        assert!(output.status.success());
        assert!(output.stdout.len() > 64 * 1024);
        assert_eq!(String::from_utf8_lossy(&output.stderr).trim(), "err");
    }

    #[test]
    fn playlist_resolution_caps_entries_at_200() {
        // Given/When: the flags used for playlist resolution.
        // Then: large playlists are truncated so loading stays bounded.
        let flags = playlist_flags();
        assert!(flags
            .windows(2)
            .any(|pair| pair[0] == "--playlist-end" && pair[1] == "200"));
    }

    #[test]
    fn rejects_private_hosts_after_url_normalization() {
        for input in [
            "http://[::1]/",
            "http://x@127.0.0.1/",
            "http://127.1/",
            "http://2130706433/",
            "http://LOCALHOST/",
            "http://localhost./",
            "http://[::ffff:127.0.0.1]/",
            "http://[fd00::1]/",
            "http://169.254.169.254/",
            "http://[fe80::1]/",
            "http://100.64.0.1/",
            "http://service.local/",
            "http://metadata.google.internal/",
            "http://10.1.2.3/",
            "http://192.168.1.1/",
        ] {
            assert!(
                matches!(classify_input(input), Err(ExtractorError::PrivateNetwork)),
                "{input} should be rejected"
            );
        }
    }

    #[test]
    fn allows_public_hosts_and_non_http_schemes() {
        for input in [
            "https://youtube.com/watch?v=abc",
            "https://soundcloud.com/a/b",
            "https://www.youtube.com/playlist?list=x",
        ] {
            assert!(!is_private_host(input), "{input} should pass");
            assert!(
                !matches!(classify_input(input), Err(ExtractorError::PrivateNetwork)),
                "{input} should classify"
            );
        }
        assert!(!is_private_host("spotify:track:abc"));
        assert!(!is_private_host("spotify:playlist:abc"));
    }

    #[test]
    fn parses_metadata_with_optional_fields_and_rejects_bad_rows() {
        assert!(
            matches!(parse_metadata_line("id:::Title:::Artist:::https://example.test/v:::203.0").as_ref(), Some(track) if track.id == "id" && track.title == "Title" && track.artist.as_deref() == Some("Artist") && track.duration_secs == Some(203))
        );
        assert!(
            matches!(parse_metadata_line("id:::Title::::::https://example.test/v:::NA").as_ref(), Some(track) if track.artist.is_none() && track.duration_secs.is_none())
        );
        assert!(parse_metadata_line("id:::Title:::Artist:::https://example.test/v").is_none());
        assert!(parse_metadata_line("id:::Title:::Artist:::NA:::203").is_none());
        assert!(parse_metadata_line(":::Title:::Artist:::https://example.test/v:::1").is_none());
    }

    #[test]
    fn parses_flat_playlist_rows_and_rejects_bad_rows() {
        assert!(
            matches!(parse_flat_playlist_line("id:::Title:::https://example.test/v").as_ref(), Some(track) if track.id == "id" && track.original_url == "https://example.test/v" && track.artist.is_none() && track.duration_secs.is_none())
        );
        assert!(parse_flat_playlist_line("id:::Title").is_none());
        assert!(parse_flat_playlist_line("id:::Title:::NA").is_none());
        assert!(parse_flat_playlist_line("id::::::https://example.test/v").is_none());
    }

    #[test]
    fn parses_spotify_oembed_resources_and_embed_payloads() {
        assert_eq!(
            parse_spotify_oembed_title(r#"{"title":"  Playlist Name  "}"#).as_deref(),
            Some("Playlist Name")
        );
        assert_eq!(
            canonical_title("@coldplay - Yellow (Lyrics)"),
            canonical_title("Coldplay - Yellow (Official Video)")
        );
        assert!(parse_spotify_oembed_title(r#"{"title":null}"#).is_none());
        assert!(parse_spotify_oembed_title(r#"{"title":" "}"#).is_none());
        assert!(parse_spotify_oembed_title("not json").is_none());
        assert_eq!(
            parse_spotify_resource(
                "https://open.spotify.com/playlist/0MN6xHeLC01zApGeng8IrZ?si=abc"
            ),
            Some(("playlist", "0MN6xHeLC01zApGeng8IrZ"))
        );
        assert_eq!(
            parse_spotify_resource("https://open.spotify.com/intl-id/track/7tL?si=1"),
            Some(("track", "7tL"))
        );
        assert_eq!(
            parse_spotify_resource("spotify:album:xyz"),
            Some(("album", "xyz"))
        );
        assert_eq!(parse_spotify_resource("spotify:track:../../../etc"), None);
        assert_eq!(parse_spotify_resource("spotify:track:a b"), None);
        let html = r#"<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":{"trackList":[{"uri":"spotify:track:t1","title":"Terpukau","subtitle":"Astrid","duration":257706},{"uri":"spotify:track:t2","title":"Sesi Potret","subtitle":"eńau,\u00a0Ari Lesmana","duration":274000}]}}}}}}</script>"#;
        let tracks = parse_spotify_embed_tracks(html).unwrap();
        assert_eq!(
            (
                tracks.len(),
                tracks[0].id.as_str(),
                tracks[0].title.as_str(),
                tracks[0].artist.as_deref(),
                tracks[0].original_url.as_str(),
                tracks[0].duration_secs,
                tracks[1].artist.as_deref()
            ),
            (
                2,
                "t1",
                "Terpukau",
                Some("Astrid"),
                "ytsearch1:Terpukau Astrid Official Audio",
                Some(257),
                Some("eńau, Ari Lesmana")
            )
        );
    }

    #[test]
    fn primary_stream_attempt_requests_visionos_and_never_sends_cookies() {
        // Given/When: the flags used for the first, cookie-free resolution attempt.
        let flags = primary_stream_flags();

        // Then: the visionos client is requested, matching the pre-regression main path.
        assert!(flags.windows(2).any(|pair| {
            pair[0] == "--extractor-args" && pair[1] == "youtube:player_client=visionos,default"
        }));
        assert!(flags
            .windows(2)
            .any(|pair| pair[0] == "-f" && pair[1] == STREAM_FORMAT));
        assert!(flags.contains(&"-j") && flags.contains(&"--no-playlist"));

        // And: no cookie flag is present, because yt-dlp skips visionos when cookies are supplied.
        assert!(!flags.iter().any(|flag| flag.contains("cookies")));
    }

    #[test]
    fn rejects_only_tvhtml5_stream_urls() {
        // Given: googlevideo URLs from different player clients.
        let tvhtml5 = "https://rr1---sn-x.googlevideo.com/videoplayback?id=a&c=TVHTML5&n=b";
        let visionos = "https://rr1---sn-x.googlevideo.com/videoplayback?id=a&c=VISIONOS&n=b";
        let embedded =
            "https://rr1---sn-x.googlevideo.com/videoplayback?id=a&c=WEB_EMBEDDED_PLAYER&n=b";

        // When/Then: only the TVHTML5 URL is rejected; the rest pass the resolver's filter.
        assert!(!is_usable_stream_url(tvhtml5));
        assert!(is_usable_stream_url(visionos));
        assert!(is_usable_stream_url(embedded));
    }

    #[test]
    fn parses_stream_json_with_user_agent_and_fallback_line() {
        let json = r#"{"url":"https://example.com/audio.webm","http_headers":{"User-Agent":"Custom/1.0"}}"#;
        let info = parse_stream_json(json).expect("should parse json");
        assert_eq!(info.url, "https://example.com/audio.webm");
        assert_eq!(info.user_agent.as_deref(), Some("Custom/1.0"));
        assert_eq!(info.browser_used, None);

        let plain = "https://example.com/plain.m4a\n";
        let info2 = parse_stream_json(plain).expect("should parse plain url");
        assert_eq!(info2.url, "https://example.com/plain.m4a");
        assert_eq!(info2.user_agent, None);
        assert_eq!(info2.browser_used, None);
    }
}
