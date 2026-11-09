# Product Requirements Document (PRD): Isle

> **Project Name:** Isle  
> **Tagline:** Ultra-lightweight, ad-free, floating Dynamic Island music player written in Rust.  
> **Target Path:** `~/Documents/isle`  
> **Primary Audience:** Developers, power users, and focus workers who want background music with zero bloat and seamless multi-tasking.

---

## 1. Executive Summary & Goals

### 1.1 Problem Statement
Mainstream desktop music clients (Spotify Desktop, YouTube Web on Chrome) consume massive system resources (350 MB to 1+ GB RAM, high CPU/GPU spikes, background telemetry) and disrupt workflow with ads and disruptive UI context switches. Existing CLI players lack quick visual state indicators and smooth desktop integration.

### 1.2 Solution
**Isle** is an ultra-lightweight desktop audio player built with Rust. It renders an ad-free, always-on-top, frameless floating "Dynamic Island" overlay on screen. It extracts raw audio streams on demand via `yt-dlp` and feeds them directly into the native OS sound subsystem via `rodio`.

### 1.3 Key Metrics & Non-Negotiables
* **Memory Footprint:** $\le$ 50 MB resident RAM during playback.
* **CPU Utilization:** $\le$ 1% on idle/playback.
* **No Ads:** Direct extraction of raw Opus/AAC audio streams.
* **Non-intrusive:** Minimalist pill widget (`~160x36px`) expanding on-demand to search/queue dashboard (`~360x130px`).
* **Zero Disruption:** Keyboard-first control, OS media keys (MPRIS/SMTC), global toggle shortcut.

---

## 2. System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                       Desktop OS Surface                    │
│   (Always-On-Top, Transparent Frameless Window, Wayland/X11)│
│                                                             │
│    [ Mini Pill Mode: 160x36 ]  <──Toggle──> [ Expanded: 360x130 ]
└──────────────┬──────────────────────────────────────────────┘
               │ Event Loop / State Sync (egui / eframe)
               ▼
┌─────────────────────────────────────────────────────────────┐
│                        Isle Core Loop                       │
│  - PlayerState (Current Track, VecDeque<Track> Queue, Hist) │
│  - OS Media Integration (souvlaki: MPRIS / SMTC)            │
│  - Config & Persistence (~/.config/isle/config.json)         │
└───────┬─────────────────────────────────────────────┬───────┘
        │ Async/Thread Channels (std::sync::mpsc)     │ Subprocess Stream Extraction
        ▼                                             ▼
┌───────────────────────────────┐     ┌────────────────────────────────┐
│      Audio Engine (rodio)     │     │       Extractor (yt-dlp)       │
│  - Background worker thread   │     │  - Parse URL / Search Query    │
│  - Native output (PipeWire/   │     │  - Fast URL resolve (-g -f ba) │
│    PulseAudio/WASAPI)         │     │  - Playlist dump (--flat)      │
│  - Buffer HTTP stream reader  │     │  - Related track fetch (Radio) │
└───────────────────────────────┘     └────────────────────────────────┘
```

---

## 3. Core Feature Requirements

### 3.1 Floating Dynamic Island UI (`egui` + `eframe`)
* **Pill State (Collapsed - Default):**
  * Size: `160x36` dp, rounded corners (`radius: 18`), dark solid pill (`#121212`).
  * Displays: Play/Pause icon indicator, truncated current track title, subtle animated wave/visualizer bar when playing.
  * Interaction: Click pill or press shortcut to expand.
* **Expanded State (Dashboard):**
  * Size: `360x130` dp.
  * Displays:
    * Title & Artist.
    * Progress indicator / elapsed time.
    * Controls: Previous, Play/Pause, Next, Volume slider.
    * Unified Input Box: Accepts search queries, YouTube URLs, YouTube Playlists, Spotify Playlist/Track URLs.
    * Queue preview (next 2-3 songs in line).
  * Interaction: Pressing `Esc` or clicking outside/close collapses back to Pill.
* **Window Properties:**
  * Frameless / undecorated (`with_decorations(false)`).
  * Transparent background (`with_transparent(true)`).
  * Always on top (`with_always_on_top()`).
  * Draggable position on desktop (retains coordinates across sessions).

### 3.2 Audio & Streaming Engine (`rodio` + `reqwest`)
* **Ad-Free Playback:** Decodes Opus/AAC stream directly from resolved HTTP URL using `rodio::Decoder`.
* **Zero Local File Storage:** Streams over HTTP buffer (`BufReader<reqwest::blocking::Response>`) without dumping audio files to disk.
* **Playback Controls:** Play, Pause, Resume, Stop, Next, Previous, Volume scale (0.0 to 1.5).

### 3.3 Universal Input & Playlist Parser (`yt-dlp`)
The unified text bar handles multiple input formats transparently:
1. **Search Query (e.g. `lofi hip hop`):** Runs `ytsearch1:<query>` to immediately play.
2. **Direct Track URL (YouTube / SoundCloud):** Resolves stream directly.
3. **YouTube Playlist URL (e.g. `playlist?list=...`):**
   * Uses `--flat-playlist --print "%(title)s:::%(url)s"` to instantly populate `VecDeque<TrackItem>` without downloading or resolving all audio streams upfront.
   * Plays the first track immediately; resolves subsequent tracks lazily when their turn arrives.
4. **Spotify URLs (Playlist / Track / Album):**
   * Extracted via `yt-dlp` metadata scraper or Spotify public web endpoint to get track title and artist name.
   * Resolved dynamically via `ytsearch1:<Title> <Artist>`.

### 3.4 Queue & Smart Autoplay (Radio Mode)
* **Queue Management:** FIFO queue (`VecDeque<TrackItem>`).
* **Auto-advance:** Detects track completion via `sink.empty()`, auto-dequeues and plays the next item.
* **Infinite Radio / Autoplay:**
  * When queue becomes empty and `autoplay = true`:
  * Fetches related videos from YouTube using `yt-dlp --flat-playlist -I 2:3 "%(id)s %(title)s" <last_video_url>`.
  * Enqueues the top recommendation automatically to keep music flowing without user intervention.

### 3.5 OS System & Hardware Integration
* **Volume Synchronization:** Uses native platform audio sink (PipeWire/PulseAudio on Linux, WASAPI on Windows, CoreAudio on macOS). Responds to master OS volume adjustments.
* **Media Keys & Notifications (`souvlaki`):**
  * Listens to hardware keyboard media keys (Play/Pause, Next, Previous).
  * Publishes track metadata (Title, Artist) to system notifications and OS media widgets (Linux MPRIS D-Bus, Windows SMTC).

---

## 4. Technical Stack

| Component | Library / Tool | Purpose / Why Chosen |
|---|---|---|
| **Language** | Rust (Edition 2021) | Ultra-low memory footprint, high performance, zero runtime garbage collection. |
| **GUI Framework** | `eframe` + `egui` (v0.29+) | Immediate mode GUI, native transparency, minimal binary overhead, <40MB RAM. |
| **Audio Engine** | `rodio` (v0.19+) | Cross-platform audio playback on top of `cpal`. |
| **Stream Extraction** | `yt-dlp` (Subprocess) | Battle-tested scraper for YouTube/Spotify/SoundCloud, immune to API breaks when updated. |
| **HTTP Client** | `reqwest` (blocking feature) | Fetching remote audio stream chunks. |
| **Media Keys & MPRIS** | `souvlaki` (v0.7+) | Cross-platform OS media control (MPRIS for Linux, SMTC for Windows). |
| **Serialization** | `serde` + `serde_json` | For local configuration and cached history/playlists. |

---

## 5. Data Structures & State Model

```rust
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct TrackItem {
    pub id: String,
    pub title: String,
    pub artist: Option<String>,
    pub original_url: String,
    pub duration_secs: Option<u64>,
}

pub struct PlayerState {
    pub current_track: Option<TrackItem>,
    pub queue: std::collections::VecDeque<TrackItem>,
    pub history: Vec<TrackItem>,
    pub is_playing: bool,
    pub volume: f32, // 0.0 - 1.0 (default: 1.0)
    pub autoplay_enabled: bool,
}

pub enum AudioCommand {
    PlayTrack(TrackItem),
    TogglePlayPause,
    NextTrack,
    PreviousTrack,
    SetVolume(f32),
    Enqueue(TrackItem),
    EnqueueBatch(Vec<TrackItem>),
    ClearQueue,
}
```

---

## 6. Implementation Milestones

### Phase 1: Core Audio Engine & Streamer (CLI Proof of Concept)
- [ ] Initialize Cargo workspace at `Documents/isle`.
- [ ] Implement `yt-dlp` stream extraction wrapper with error handling.
- [ ] Implement `rodio` streaming loop over buffered HTTP response.
- [ ] Validate memory consumption ($\le$ 30 MB) and audio stability.

### Phase 2: Dynamic Island GUI (Frameless & Transparent Window)
- [ ] Setup `eframe` window with transparent backdrop and no OS borders.
- [ ] Build Collapsed Pill UI (`160x36`) with hover and click state.
- [ ] Build Expanded Card UI (`360x130`) with search bar and playback buttons.
- [ ] Implement smooth expand/collapse transition and window dragging.

### Phase 3: Playlist Parser & Queue Engine
- [ ] Implement single query search (`ytsearch1:`).
- [ ] Implement `--flat-playlist` extraction for YouTube & Spotify playlist URLs.
- [ ] Implement `VecDeque` queue state and automatic next-track playback.
- [ ] Implement Related Track Autoplay (Radio Mode) when queue reaches empty state.

### Phase 4: System Integration & Polish
- [ ] Wire `souvlaki` for global media keys and MPRIS/SMTC notifications.
- [ ] Add global toggle hotkey (e.g. `Ctrl + Shift + Space`) to show/hide the Island.
- [ ] Add persistence for window position and config in `~/.config/isle/config.json`.
- [ ] Final memory and CPU benchmarking profile.

---

## 7. Development Guidelines for AI Agents ("Vibe Coding Rules")

1. **Keep it Lean:** Never introduce heavyweight dependencies (avoid web engines, WebView, or C++ heavy GUI toolkits).
2. **Non-blocking GUI:** Never execute `yt-dlp` or network I/O on the UI thread (`eframe::update`). All extraction and audio ops must run inside an isolated worker thread communicating via `mpsc` or `crossbeam` channels.
3. **Graceful Fallbacks:** If `yt-dlp` is missing on user system, display a friendly prompt on the pill rather than crashing.
4. **Idempotent State:** UI states should be purely derived from `PlayerState`.
