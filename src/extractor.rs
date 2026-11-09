use std::{
    io::ErrorKind,
    process::{Command, Stdio},
    time::Duration,
};

use crate::models::TrackItem;

const METADATA_FORMAT: &str = "%(id)s:::%(title)s:::%(uploader)s:::%(webpage_url)s:::%(duration)s";
const SEARCH_METADATA_FORMAT: &str = "%(id)s:::%(title)s:::%(uploader)s:::%(url)s:::%(duration)s";
const FLAT_PLAYLIST_FORMAT: &str = "%(id)s:::%(title)s:::%(url)s";
const STREAM_FORMAT: &str =
    "ba[ext=m4a][protocol^=http]/ba[ext=mp3][protocol^=http]/ba[acodec^=vorbis][protocol^=http]/ba[protocol^=http]/ba";
const SPOTIFY_OEMBED_URL: &str = "https://open.spotify.com/oembed";

#[derive(Debug, thiserror::Error)]
pub enum ExtractorError {
    #[error("yt-dlp was not found in PATH")]
    MissingBinary,
    #[error("input is empty")]
    EmptyInput,
    #[error("yt-dlp I/O error: {0}")]
    SubprocessIo(#[from] std::io::Error),
    #[error("yt-dlp failed: {0}")]
    SubprocessFailed(String),
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

pub fn classify_input(raw: &str) -> Result<InputKind, ExtractorError> {
    let input = raw.trim();
    if input.is_empty() {
        return Err(ExtractorError::EmptyInput);
    }
    let value = input.to_owned();
    if input.starts_with("http://") || input.starts_with("https://") || input.starts_with("spotify:") {
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
        InputKind::YouTubePlaylist(url) => require_tracks(flat_tracks(&run_ytdlp(&[
            "--flat-playlist", "--print", FLAT_PLAYLIST_FORMAT, &url,
        ])?)),
        InputKind::SpotifyUrl(url) => resolve_spotify(&url),
    }
}

pub fn resolve_stream_url(original_url: &str) -> Result<String, ExtractorError> {
    run_ytdlp(&["--extractor-args", "youtube:player_client=visionos,default", "-g", "-f", STREAM_FORMAT, "--no-playlist", original_url])?
        .lines().find(|line| !line.trim().is_empty()).map(ToOwned::to_owned).ok_or(ExtractorError::NoResults)
}

pub fn canonical_title(title: &str) -> String {
    let base = title.split(['(', '[']).next().unwrap_or(title);
    base.chars()
        .map(|ch| if ch.is_alphanumeric() { ch.to_ascii_lowercase() } else { ' ' })
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

pub fn fetch_related_tracks(last_track: &TrackItem) -> Result<Vec<TrackItem>, ExtractorError> {
    let mix = format!("https://www.youtube.com/watch?v={}&list=RD{}", last_track.id, last_track.id);
    let raw = run_ytdlp(&["--flat-playlist", "-I", "2:10", "--print", SEARCH_METADATA_FORMAT, &mix]).or_else(|_| {
        let seed = last_track.artist.as_deref().unwrap_or(&last_track.title);
        run_ytdlp(&["--flat-playlist", "--match-filter", "live_status!=is_live", "--print", SEARCH_METADATA_FORMAT, &format!("ytsearch6:{seed} audio")])
    })?;
    let mut seen = vec![canonical_title(&last_track.title)];
    let tracks = raw.lines().filter_map(parse_metadata_line).filter(|track| {
        let canon = canonical_title(&track.title);
        let fresh = track.id != last_track.id && !canon.is_empty() && !seen.contains(&canon);
        if fresh { seen.push(canon); }
        fresh
    }).take(4).collect();
    require_tracks(tracks)
}

fn resolve_search(query: &str) -> Result<Vec<TrackItem>, ExtractorError> {
    run_ytdlp(&["--flat-playlist", "--match-filter", "live_status!=is_live", "--print", SEARCH_METADATA_FORMAT, &format!("ytsearch3:{query}")])?
        .lines().find_map(parse_metadata_line).map(|track| vec![track]).ok_or(ExtractorError::NoResults)
}

fn resolve_metadata(target: &str) -> Result<TrackItem, ExtractorError> {
    run_ytdlp(&["--no-playlist", "--print", METADATA_FORMAT, target])?
        .lines().find_map(parse_metadata_line).ok_or(ExtractorError::NoResults)
}

fn resolve_spotify(url: &str) -> Result<Vec<TrackItem>, ExtractorError> {
    let client = reqwest::blocking::Client::builder().user_agent("Mozilla/5.0").timeout(Duration::from_secs(5)).build()?;
    if let Some((kind, id)) = parse_spotify_resource(url) {
        let embed_url = format!("https://open.spotify.com/embed/{kind}/{id}");
        if let Ok(html) = client.get(&embed_url).send().and_then(reqwest::blocking::Response::error_for_status).and_then(reqwest::blocking::Response::text) {
            if let Some(tracks) = parse_spotify_embed_tracks(&html) {
                return Ok(tracks);
            }
        }
    }
    let json = client.get(SPOTIFY_OEMBED_URL).query(&[("url", url)]).send()?.error_for_status()?.text()?;
    resolve_search(&parse_spotify_oembed_title(&json).ok_or(ExtractorError::NoResults)?)
}

pub fn parse_spotify_resource(url: &str) -> Option<(&str, &str)> {
    let clean = url.trim().split(['?', '#']).next()?;
    let rest = clean.strip_prefix("spotify:").or_else(|| clean.split("open.spotify.com/").nth(1))?;
    let mut parts = rest.split(['/', ':']).filter(|s| !s.is_empty() && *s != "embed" && !s.starts_with("intl-"));
    let (kind, id) = (parts.next()?, parts.next()?);
    matches!(kind, "track" | "playlist" | "album").then_some((kind, id))
}

pub fn parse_spotify_embed_tracks(html: &str) -> Option<Vec<TrackItem>> {
    let json_str = html.split(r#"<script id="__NEXT_DATA__" type="application/json">"#).nth(1)?.split("</script>").next()?;
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
    let title = entry.get("title").or_else(|| entry.get("name")).and_then(|v| v.as_str())?.trim();
    if title.is_empty() { return None; }
    let raw_artist = entry.get("subtitle").and_then(|v| v.as_str()).or_else(|| entry.pointer("/artists/0/name").and_then(|v| v.as_str())).unwrap_or("");
    let artist_clean = raw_artist.replace('\u{a0}', " ");
    let artist = (!artist_clean.trim().is_empty()).then(|| artist_clean.trim().to_owned());
    let id = entry.get("uri").and_then(|v| v.as_str()).and_then(|u| u.rsplit(':').next()).filter(|s| !s.is_empty()).unwrap_or(title).to_owned();
    let original_url = match artist.as_deref() {
        Some(a) => format!("ytsearch1:{title} {a} Official Audio"),
        None => format!("ytsearch1:{title} Official Audio"),
    };
    Some(TrackItem { id, title: title.to_owned(), artist, original_url, duration_secs: entry.get("duration").and_then(|v| v.as_u64()).map(|ms| ms / 1000) })
}

fn flat_tracks(output: &str) -> Vec<TrackItem> {
    output.lines().filter_map(parse_flat_playlist_line).collect()
}

fn require_tracks(tracks: Vec<TrackItem>) -> Result<Vec<TrackItem>, ExtractorError> {
    if tracks.is_empty() { Err(ExtractorError::NoResults) } else { Ok(tracks) }
}

fn run_ytdlp(args: &[&str]) -> Result<String, ExtractorError> {
    let output = match Command::new("yt-dlp").args(args).output() {
        Ok(output) => output,
        Err(error) if error.kind() == ErrorKind::NotFound => return Err(ExtractorError::MissingBinary),
        Err(error) => return Err(error.into()),
    };
    if output.status.success() {
        Ok(String::from_utf8_lossy(&output.stdout).into_owned())
    } else {
        let stderr = String::from_utf8_lossy(&output.stderr).trim().to_owned();
        Err(ExtractorError::SubprocessFailed(if stderr.is_empty() { output.status.to_string() } else { stderr }))
    }
}

pub fn parse_metadata_line(line: &str) -> Option<TrackItem> {
    let mut fields = line.trim().split(":::");
    let (id, title, artist, original_url, duration) = (
        fields.next()?.trim(), fields.next()?.trim(), fields.next()?.trim(), fields.next()?.trim(), fields.next()?.trim(),
    );
    if fields.next().is_some() || id.is_empty() || title.is_empty() || original_url.is_empty() || original_url == "NA" {
        return None;
    }
    let duration_secs = duration.parse::<u64>().ok().or_else(|| {
        duration.parse::<f64>().ok().filter(|&secs| secs >= 0.0).map(|secs| secs as u64)
    });
    Some(TrackItem {
        id: id.to_owned(), title: title.to_owned(), artist: (!artist.is_empty() && artist != "NA").then(|| artist.to_owned()), original_url: original_url.to_owned(), duration_secs,
    })
}

pub fn parse_flat_playlist_line(line: &str) -> Option<TrackItem> {
    let mut fields = line.trim().split(":::");
    let (id, title, original_url) = (fields.next()?.trim(), fields.next()?.trim(), fields.next()?.trim());
    if fields.next().is_some() || id.is_empty() || title.is_empty() || original_url.is_empty() || original_url == "NA" {
        return None;
    }
    Some(TrackItem {
        id: id.to_owned(), title: title.to_owned(), artist: None, original_url: original_url.to_owned(), duration_secs: None,
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
        assert!(matches!(classify_input(" \tlofi beats\n"), Ok(InputKind::SearchQuery(value)) if value == "lofi beats"));
        assert!(matches!(classify_input("https://soundcloud.com/a/b"), Ok(InputKind::DirectUrl(_))));
        assert!(matches!(classify_input("https://youtube.com/playlist?list=abc"), Ok(InputKind::YouTubePlaylist(_))));
        assert!(matches!(classify_input("https://youtube.com/watch?v=a&list=b"), Ok(InputKind::YouTubePlaylist(_))));
        assert!(matches!(classify_input("https://open.spotify.com/track/abc"), Ok(InputKind::SpotifyUrl(_))));
        assert!(matches!(classify_input("spotify:track:abc"), Ok(InputKind::SpotifyUrl(_))));
        assert!(matches!(classify_input("  \n"), Err(ExtractorError::EmptyInput)));
    }

    #[test]
    fn parses_metadata_with_optional_fields_and_rejects_bad_rows() {
        assert!(matches!(parse_metadata_line("id:::Title:::Artist:::https://example.test/v:::203.0").as_ref(), Some(track) if track.id == "id" && track.title == "Title" && track.artist.as_deref() == Some("Artist") && track.duration_secs == Some(203)));
        assert!(matches!(parse_metadata_line("id:::Title::::::https://example.test/v:::NA").as_ref(), Some(track) if track.artist.is_none() && track.duration_secs.is_none()));
        assert!(parse_metadata_line("id:::Title:::Artist:::https://example.test/v").is_none());
        assert!(parse_metadata_line("id:::Title:::Artist:::NA:::203").is_none());
        assert!(parse_metadata_line(":::Title:::Artist:::https://example.test/v:::1").is_none());
    }

    #[test]
    fn parses_flat_playlist_rows_and_rejects_bad_rows() {
        assert!(matches!(parse_flat_playlist_line("id:::Title:::https://example.test/v").as_ref(), Some(track) if track.id == "id" && track.original_url == "https://example.test/v" && track.artist.is_none() && track.duration_secs.is_none()));
        assert!(parse_flat_playlist_line("id:::Title").is_none());
        assert!(parse_flat_playlist_line("id:::Title:::NA").is_none());
        assert!(parse_flat_playlist_line("id::::::https://example.test/v").is_none());
    }

    #[test]
    fn parses_spotify_oembed_resources_and_embed_payloads() {
        assert_eq!(parse_spotify_oembed_title(r#"{"title":"  Playlist Name  "}"#).as_deref(), Some("Playlist Name"));
        assert_eq!(canonical_title("@coldplay - Yellow (Lyrics)"), canonical_title("Coldplay - Yellow (Official Video)"));
        assert!(parse_spotify_oembed_title(r#"{"title":null}"#).is_none());
        assert!(parse_spotify_oembed_title(r#"{"title":" "}"#).is_none());
        assert!(parse_spotify_oembed_title("not json").is_none());
        assert_eq!(parse_spotify_resource("https://open.spotify.com/playlist/0MN6xHeLC01zApGeng8IrZ?si=abc"), Some(("playlist", "0MN6xHeLC01zApGeng8IrZ")));
        assert_eq!(parse_spotify_resource("https://open.spotify.com/intl-id/track/7tL?si=1"), Some(("track", "7tL")));
        assert_eq!(parse_spotify_resource("spotify:album:xyz"), Some(("album", "xyz")));
        let html = r#"<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":{"trackList":[{"uri":"spotify:track:t1","title":"Terpukau","subtitle":"Astrid","duration":257706},{"uri":"spotify:track:t2","title":"Sesi Potret","subtitle":"eńau,\u00a0Ari Lesmana","duration":274000}]}}}}}}</script>"#;
        let tracks = parse_spotify_embed_tracks(html).unwrap();
        assert_eq!((tracks.len(), tracks[0].id.as_str(), tracks[0].title.as_str(), tracks[0].artist.as_deref(), tracks[0].original_url.as_str(), tracks[0].duration_secs, tracks[1].artist.as_deref()), (2, "t1", "Terpukau", Some("Astrid"), "ytsearch1:Terpukau Astrid Official Audio", Some(257), Some("eńau, Ari Lesmana")));
    }
}
