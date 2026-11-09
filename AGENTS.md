# AGENTS.md

## Multi Brain (MANDATORY)
- Read `.multibrain/session.md` before starting work.
- Use `.multibrain/session.md` as the master index only.
- Open only the `.multibrain/indexes/*.md` bucket files that match the current task.
- Open `.multibrain/context/*.md` only when the selected bucket points to deeper context that matters.
- After meaningful work, update the relevant named bucket and refresh the master index if needed.

## Status & Source of Truth
- **Implemented (Phase 1–4):** Cargo manifest (`Cargo.toml`) and modular Rust codebase (`src/main.rs`, `src/models.rs`, `src/config.rs`, `src/extractor.rs`, `src/audio.rs`, `src/media_keys.rs`, `src/ui.rs`) implement `PRD.md` milestones.
- **Verification commands:** `cargo test`, `cargo clippy --all-targets -- -D warnings`, `cargo build --release`.

## Architecture & Hard Constraints
- **Stack:** Rust (Edition 2021), `eframe`/`egui` (v0.29+), `rodio` (v0.19+), `reqwest` (`blocking`), `souvlaki` (v0.7+), `serde`/`serde_json`, and external `yt-dlp` binary.
- **Resource budgets:** $\le$ 50 MB resident RAM during playback ($\le$ 30 MB for core streamer) and $\le$ 1% CPU on idle/playback. Never add WebView, browser engines, or heavyweight GUI toolkits.
- **Non-blocking UI thread:** Never run `yt-dlp` subprocesses or network I/O inside `eframe::update`. Run stream extraction and `rodio` playback on background worker threads communicating via `std::sync::mpsc` channels.
- **Zero disk caching for audio:** Stream audio in memory (`BufReader<reqwest::blocking::Response>` into `rodio::Decoder`); never write temporary audio files to disk.
- **Lazy playlist resolution:** Populate `VecDeque<TrackItem>` with `yt-dlp --flat-playlist --print "%(title)s:::%(url)s"` and resolve stream URLs (`-g -f ba`) only when a track is about to play.
- **External binary resilience:** If `yt-dlp` is missing from `PATH`, surface a prompt in the pill UI instead of panicking.
- **Idempotent UI state:** Derive UI rendering purely from `PlayerState`; persist config and window coordinates to `~/.config/isle/config.json`.
- **Window geometry:** Frameless (`with_decorations(false)`), transparent (`with_transparent(true)`), always-on-top (`with_always_on_top()`); collapsed pill is `160x36` dp (`#121212`, radius `18`), expanded dashboard is `360x130` dp.
