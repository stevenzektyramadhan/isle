use std::sync::mpsc::{Receiver, Sender};
use std::time::{Duration, Instant};

use eframe::egui::{self, pos2, vec2, Color32, Pos2, Rect, Rounding, Sense, Stroke, Vec2};
#[cfg(windows)]
use raw_window_handle::{HasWindowHandle, RawWindowHandle};

#[cfg(windows)]
use crate::media_keys::MediaKeyManager;
use crate::models::{AppConfig, AudioCommand, PlayerState};

pub const PILL_WIDTH: f32 = 290.0;
pub const PILL_HEIGHT: f32 = 54.0;
pub const EXPANDED_WIDTH: f32 = 360.0;
pub const EXPANDED_HEIGHT: f32 = 320.0;

pub const BG_ISLAND: Color32 = Color32::from_rgb(0x0c, 0x0a, 0x16);
pub const BORDER_ISLAND: Color32 = Color32::from_rgb(0x23, 0x1f, 0x33);
pub const SURFACE_SUBTLE: Color32 = Color32::from_rgb(0x15, 0x12, 0x22);
pub const SURFACE_BORDER: Color32 = Color32::from_rgb(0x28, 0x23, 0x3b);
pub const ACCENT_PRIMARY: Color32 = Color32::from_rgb(0x7c, 0x3a, 0xed);
pub const ACCENT_GLOW: Color32 = Color32::from_rgba_premultiplied(0x1d, 0x0d, 0x37, 60);
pub const ACCENT_MUTED: Color32 = Color32::from_rgb(0x38, 0x1a, 0x6e);
pub const ACCENT_LIGHT: Color32 = Color32::from_rgb(0xb7, 0x8a, 0xfc);
pub const TEXT_PRIMARY: Color32 = Color32::from_rgb(0xf3, 0xf4, 0xf6);
pub const TEXT_SECONDARY: Color32 = Color32::from_rgb(0x9a, 0x90, 0xad);
pub const TEXT_MUTED: Color32 = Color32::from_rgb(0x66, 0x5d, 0x78);
pub const TRACK_BG: Color32 = Color32::from_rgb(0x22, 0x1d, 0x33);

pub const ISLAND_BG: Color32 = BG_ISLAND;
pub const ISLAND_BORDER: Color32 = BORDER_ISLAND;

pub fn format_duration(secs: u64) -> String {
    format!("{:02}:{:02}", secs / 60, secs % 60)
}
pub fn target_window_size(expanded: bool) -> Vec2 {
    if expanded {
        vec2(EXPANDED_WIDTH, EXPANDED_HEIGHT)
    } else {
        vec2(PILL_WIDTH, PILL_HEIGHT)
    }
}
pub fn expand_progress(raw_t: f32) -> f32 {
    let clamped = raw_t.clamp(0.0, 1.0);
    clamped * clamped * (3.0 - 2.0 * clamped)
}
pub fn island_dimensions(progress: f32) -> (Vec2, f32) {
    let t = expand_progress(progress);
    let width = PILL_WIDTH + (EXPANDED_WIDTH - PILL_WIDTH) * t;
    let height = PILL_HEIGHT + (EXPANDED_HEIGHT - PILL_HEIGHT) * t;
    let rounding = 27.0 + (26.0 - 27.0) * t;
    (vec2(width, height), rounding)
}
pub fn transport_labels(autoplay: bool, queue_len: usize) -> (&'static str, String) {
    (
        if autoplay { "AUTO ON" } else { "AUTO OFF" },
        if queue_len == 0 {
            "CLEAR".to_owned()
        } else {
            format!("CLR ({queue_len})")
        },
    )
}
pub fn truncate_title(title: &str, max_chars: usize) -> String {
    if max_chars == 0 {
        return String::new();
    }
    let chars: Vec<char> = title.chars().collect();
    if chars.len() <= max_chars {
        title.to_owned()
    } else {
        chars.into_iter().take(max_chars - 1).chain(['…']).collect()
    }
}

fn pill_badge(ui: &mut egui::Ui, text: &str, dark: bool) {
    let (fill, stroke, fg) = if dark {
        (SURFACE_SUBTLE, SURFACE_BORDER, ACCENT_LIGHT)
    } else {
        (ACCENT_MUTED, ACCENT_PRIMARY, TEXT_PRIMARY)
    };
    egui::Frame::none()
        .fill(fill)
        .stroke(Stroke::new(1.0_f32, stroke))
        .rounding(Rounding::same(8.0))
        .inner_margin(egui::Margin::symmetric(6.0, 2.0))
        .show(ui, |ui| {
            ui.label(egui::RichText::new(text).size(9.5).strong().color(fg));
        });
}

fn paint_play_pause(painter: &egui::Painter, center: Pos2, playing: bool) {
    if playing {
        for dx in [-3.0_f32, 3.0] {
            painter.rect_filled(
                Rect::from_center_size(pos2(center.x + dx, center.y), vec2(2.5, 9.5)),
                1.2,
                TEXT_PRIMARY,
            );
        }
    } else {
        painter.add(egui::Shape::convex_polygon(
            vec![
                pos2(center.x - 3.5, center.y - 5.2),
                pos2(center.x - 3.5, center.y + 5.2),
                pos2(center.x + 5.2, center.y),
            ],
            TEXT_PRIMARY,
            Stroke::NONE,
        ));
    }
}

fn paint_skip(painter: &egui::Painter, center: Pos2, next: bool) {
    let (dir, col) = (if next { 1.0 } else { -1.0 }, TEXT_PRIMARY);
    painter.add(egui::Shape::convex_polygon(
        vec![
            pos2(center.x - 4.0 * dir, center.y - 5.0),
            pos2(center.x - 4.0 * dir, center.y + 5.0),
            pos2(center.x + 3.0 * dir, center.y),
        ],
        col,
        Stroke::NONE,
    ));
    painter.rect_filled(
        Rect::from_center_size(pos2(center.x + 4.5 * dir, center.y), vec2(2.0, 10.0)),
        1.0,
        col,
    );
}

fn paint_speaker(painter: &egui::Painter, center: Pos2, high: bool, muted: bool) {
    let col = if muted { TEXT_MUTED } else { TEXT_SECONDARY };
    painter.rect_filled(
        Rect::from_center_size(pos2(center.x - 3.0, center.y), vec2(3.0, 5.0)),
        1.0,
        col,
    );
    painter.add(egui::Shape::convex_polygon(
        vec![
            pos2(center.x - 1.5, center.y - 2.5),
            pos2(center.x + 2.0, center.y - 5.5),
            pos2(center.x + 2.0, center.y + 5.5),
            pos2(center.x - 1.5, center.y + 2.5),
        ],
        col,
        Stroke::NONE,
    ));
    if muted {
        painter.line_segment(
            [
                pos2(center.x + 3.5, center.y - 3.5),
                pos2(center.x + 7.5, center.y + 3.5),
            ],
            Stroke::new(1.2_f32, col),
        );
        painter.line_segment(
            [
                pos2(center.x + 3.5, center.y + 3.5),
                pos2(center.x + 7.5, center.y - 3.5),
            ],
            Stroke::new(1.2_f32, col),
        );
    } else if high {
        painter.line_segment(
            [
                pos2(center.x + 4.5, center.y - 3.5),
                pos2(center.x + 4.5, center.y + 3.5),
            ],
            Stroke::new(1.2_f32, col),
        );
        painter.line_segment(
            [
                pos2(center.x + 7.0, center.y - 6.0),
                pos2(center.x + 7.0, center.y + 6.0),
            ],
            Stroke::new(1.2_f32, col),
        );
    }
}

fn paint_search(painter: &egui::Painter, center: Pos2) {
    let col = TEXT_MUTED;
    painter.circle(
        pos2(center.x - 1.5, center.y - 1.5),
        4.5,
        Color32::TRANSPARENT,
        Stroke::new(1.3_f32, col),
    );
    painter.line_segment(
        [
            pos2(center.x + 1.8, center.y + 1.8),
            pos2(center.x + 5.0, center.y + 5.0),
        ],
        Stroke::new(1.5_f32, col),
    );
}

fn paint_mode_btn(ui: &mut egui::Ui, label: (&str, &str), active: bool) -> egui::Response {
    let (rect, resp) = ui.allocate_exact_size(vec2(56.0, 38.0), Sense::click());
    let hovered = resp.hovered();
    let (fill, stroke, fg) = if active {
        (
            if hovered {
                ACCENT_PRIMARY
            } else {
                ACCENT_MUTED
            },
            ACCENT_LIGHT,
            TEXT_PRIMARY,
        )
    } else {
        (
            if hovered {
                Color32::from_rgb(0x22, 0x1f, 0x30)
            } else {
                SURFACE_SUBTLE
            },
            SURFACE_BORDER,
            if hovered { TEXT_PRIMARY } else { TEXT_MUTED },
        )
    };
    ui.painter().rect(
        rect,
        Rounding::same(10.0),
        fill,
        Stroke::new(if active || hovered { 1.2_f32 } else { 1.0_f32 }, stroke),
    );
    for (dy, txt, sz) in [(-6.0_f32, label.0, 12.5_f32), (8.0, label.1, 8.5)] {
        ui.painter().text(
            pos2(rect.center().x, rect.center().y + dy),
            egui::Align2::CENTER_CENTER,
            txt,
            egui::FontId::proportional(sz),
            fg,
        );
    }
    resp
}

fn paint_disc(painter: &egui::Painter, rect: Rect, anim_t: Option<f64>) {
    let (center, square) = (rect.center(), rect.width() > 40.0);
    if square {
        painter.rect(
            rect,
            Rounding::same(14.0),
            SURFACE_SUBTLE,
            Stroke::new(1.0_f32, SURFACE_BORDER),
        );
        painter.circle(center, 18.0, BG_ISLAND, Stroke::new(1.5_f32, ACCENT_MUTED));
        let badge = Rect::from_min_size(
            pos2(rect.right() - 16.0, rect.bottom() - 16.0),
            vec2(18.0, 18.0),
        );
        painter.rect_filled(badge, Rounding::same(6.0), ACCENT_PRIMARY);
        let note_pos = badge.center();
        painter.circle_filled(pos2(note_pos.x - 2.0, note_pos.y + 2.0), 2.0, TEXT_PRIMARY);
        painter.line_segment(
            [
                pos2(note_pos.x, note_pos.y + 2.0),
                pos2(note_pos.x, note_pos.y - 3.5),
            ],
            Stroke::new(1.2_f32, TEXT_PRIMARY),
        );
        painter.line_segment(
            [
                pos2(note_pos.x, note_pos.y - 3.5),
                pos2(note_pos.x + 2.5, note_pos.y - 2.5),
            ],
            Stroke::new(1.2_f32, TEXT_PRIMARY),
        );
    } else {
        painter.circle(
            center,
            15.5,
            SURFACE_SUBTLE,
            Stroke::new(2.0_f32, ACCENT_PRIMARY),
        );
        painter.circle_filled(center, 3.5, ACCENT_LIGHT);
    }
    for (idx, (dx, base)) in [(-4.0_f32, 6.0_f32), (0.0, 11.0), (4.0, 8.0)]
        .into_iter()
        .enumerate()
    {
        let phase = anim_t.map_or(0.25, |t| ((t as f32) * 5.5 + idx as f32 * 1.3).sin().abs());
        painter.rect_filled(
            Rect::from_center_size(
                pos2(center.x + dx, center.y + if square { 16.0 } else { 0.0 }),
                vec2(2.4, base * (0.45 + phase * 0.55)),
            ),
            1.2,
            ACCENT_LIGHT,
        );
    }
}

#[cfg(windows)]
fn configure_windows_window(hwnd: *mut std::ffi::c_void) {
    if hwnd.is_null() {
        return;
    }
    type DwmSetWindowAttributeFn = unsafe extern "system" fn(
        hwnd: *mut std::ffi::c_void,
        dw_attribute: u32,
        pv_attribute: *const std::ffi::c_void,
        cb_attribute: u32,
    ) -> i32;

    extern "system" {
        fn LoadLibraryA(lp_lib_file_name: *const u8) -> *mut std::ffi::c_void;
        fn GetProcAddress(
            h_module: *mut std::ffi::c_void,
            lp_proc_name: *const u8,
        ) -> Option<DwmSetWindowAttributeFn>;
    }

    unsafe {
        let dwmapi = LoadLibraryA(b"dwmapi.dll\0".as_ptr());
        if !dwmapi.is_null() {
            if let Some(set_attr) = GetProcAddress(dwmapi, b"DwmSetWindowAttribute\0".as_ptr()) {
                const DWMWA_WINDOW_CORNER_PREFERENCE: u32 = 33;
                const DWMWCP_DONOTROUND: u32 = 1;
                let corner_pref: u32 = DWMWCP_DONOTROUND;
                let _ = set_attr(
                    hwnd,
                    DWMWA_WINDOW_CORNER_PREFERENCE,
                    &corner_pref as *const u32 as *const std::ffi::c_void,
                    std::mem::size_of::<u32>() as u32,
                );

                const DWMWA_BORDER_COLOR: u32 = 34;
                const DWMWA_COLOR_NONE: u32 = 0xFFFFFFFE;
                let border_color: u32 = DWMWA_COLOR_NONE;
                let _ = set_attr(
                    hwnd,
                    DWMWA_BORDER_COLOR,
                    &border_color as *const u32 as *const std::ffi::c_void,
                    std::mem::size_of::<u32>() as u32,
                );
            }
        }
    }
}

pub struct IsleApp {
    cmd_tx: Sender<AudioCommand>,
    state_rx: Receiver<PlayerState>,
    state: PlayerState,
    config: AppConfig,
    expanded: bool,
    current_viewport_is_expanded: bool,
    search_query: String,
    last_window_pos: Option<Pos2>,
    muted_volume: Option<f32>,
    config_dirty: bool,
    last_config_save: Instant,
    #[cfg(windows)]
    media_keys: MediaKeyManager,
}

impl IsleApp {
    pub fn new(
        cc: &eframe::CreationContext<'_>,
        cmd_tx: Sender<AudioCommand>,
        state_rx: Receiver<PlayerState>,
        config: AppConfig,
    ) -> Self {
        #[cfg(not(windows))]
        let _ = cc;
        #[cfg(windows)]
        let hwnd = cc
            .window_handle()
            .ok()
            .and_then(|handle| match handle.as_raw() {
                RawWindowHandle::Win32(handle) => {
                    Some(handle.hwnd.get() as *mut std::ffi::c_void)
                }
                _ => None,
            });
        #[cfg(windows)]
        if let Some(h) = hwnd {
            configure_windows_window(h);
        }
        #[cfg(windows)]
        let mut media_keys = MediaKeyManager::new(cmd_tx.clone(), hwnd);
        #[cfg(windows)]
        media_keys.sync_state(&PlayerState::default());
        Self {
            cmd_tx,
            state_rx,
            state: PlayerState::default(),
            config,
            expanded: false,
            current_viewport_is_expanded: false,
            search_query: String::new(),
            last_window_pos: None,
            muted_volume: None,
            config_dirty: false,
            last_config_save: Instant::now(),
            #[cfg(windows)]
            media_keys,
        }
    }

    fn resize(&self, ctx: &egui::Context, expanded: bool) {
        ctx.send_viewport_cmd(egui::ViewportCommand::InnerSize(target_window_size(
            expanded,
        )));
    }

    fn set_expanded(&mut self, ctx: &egui::Context, expanded: bool) {
        if self.expanded != expanded {
            self.expanded = expanded;
            if expanded && !self.current_viewport_is_expanded {
                self.resize(ctx, true);
                self.current_viewport_is_expanded = true;
            }
        }
    }

    fn mark_config_dirty(&mut self) {
        self.config_dirty = true;
    }

    fn flush_config(&mut self) {
        if self.config_dirty {
            let _ = crate::config::save_config(&self.config);
            self.config_dirty = false;
            self.last_config_save = Instant::now();
        }
    }

    fn save_config_if_dirty(&mut self) {
        if self.last_config_save.elapsed() >= Duration::from_millis(500) {
            self.flush_config();
        }
    }

    fn track_meta(&self) -> (String, String) {
        if !self.state.ytdlp_available {
            return (
                "yt-dlp missing".to_owned(),
                "Install yt-dlp in PATH".to_owned(),
            );
        }
        if let Some(track) = self.state.current_track.as_ref() {
            let clean = track.title.trim_start_matches('@');
            if let Some((artist, song)) = clean.split_once(" - ") {
                return (
                    song.split(['(', '['])
                        .next()
                        .unwrap_or(song)
                        .trim()
                        .to_owned(),
                    artist.trim().to_owned(),
                );
            }
            return (
                clean.to_owned(),
                track
                    .artist
                    .as_deref()
                    .unwrap_or("Isle Audio")
                    .trim_start_matches('@')
                    .to_owned(),
            );
        }
        (
            "Isle Ready".to_owned(),
            self.state
                .status_message
                .as_deref()
                .unwrap_or("Search or paste URL")
                .to_owned(),
        )
    }

    fn submit_search_if_present(&mut self) -> bool {
        if self.search_query.trim().is_empty() {
            return false;
        }
        let query = std::mem::take(&mut self.search_query);
        let _ = self
            .cmd_tx
            .send(AudioCommand::SubmitInput(query.trim().to_owned()));
        true
    }

    fn collapsed(&mut self, ui: &mut egui::Ui, ctx: &egui::Context) {
        let anim_t = self.state.is_playing.then(|| ctx.input(|i| i.time));
        if self.state.is_playing {
            ctx.request_repaint_after(Duration::from_millis(50));
        }
        let (full, (title, artist)) = (ui.max_rect(), self.track_meta());
        let btn_rect =
            Rect::from_center_size(pos2(full.right() - 28.0, full.center().y), vec2(30.0, 30.0));
        let bg_resp = ui.allocate_rect(
            Rect::from_min_max(full.min, pos2(btn_rect.left() - 8.0, full.max.y)),
            Sense::click_and_drag(),
        );
        if bg_resp.drag_started() {
            ctx.send_viewport_cmd(egui::ViewportCommand::StartDrag);
        }
        if bg_resp.clicked() {
            self.set_expanded(ctx, true);
        }
        let play_resp = ui.allocate_rect(btn_rect, Sense::click());
        let play_hovered = play_resp.hovered();
        if play_resp.on_hover_text("Play / Pause").clicked() {
            let _ = self.cmd_tx.send(AudioCommand::TogglePlayPause);
        }
        let sub = self.state.status_message.as_deref().unwrap_or(&artist);
        paint_disc(
            ui.painter(),
            Rect::from_center_size(pos2(full.left() + 27.0, full.center().y), vec2(34.0, 34.0)),
            anim_t,
        );
        let info_rect = Rect::from_min_max(
            pos2(full.left() + 52.0, full.top() + 8.0),
            pos2(btn_rect.left() - 14.0, full.bottom() - 8.0),
        );
        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(info_rect), |ui| {
            ui.vertical(|ui| {
                ui.spacing_mut().item_spacing.y = 2.0;
                ui.horizontal(|ui| {
                    ui.label(
                        egui::RichText::new(truncate_title(&title, 16))
                            .strong()
                            .size(13.0)
                            .color(TEXT_PRIMARY),
                    );
                    if self.state.autoplay_enabled {
                        pill_badge(ui, "AUTO", false);
                    }
                });
                ui.label(
                    egui::RichText::new(truncate_title(sub, 25))
                        .size(11.0)
                        .color(TEXT_SECONDARY),
                );
            });
        });
        let (div_x, p) = (btn_rect.left() - 8.0, ui.painter());
        p.line_segment(
            [
                pos2(div_x, full.top() + 12.0),
                pos2(div_x, full.bottom() - 12.0),
            ],
            Stroke::new(1.0_f32, SURFACE_BORDER),
        );
        let btn_fill = if play_hovered {
            ACCENT_MUTED
        } else {
            SURFACE_SUBTLE
        };
        p.circle(
            btn_rect.center(),
            15.0,
            btn_fill,
            Stroke::new(1.0_f32, SURFACE_BORDER),
        );
        paint_play_pause(p, btn_rect.center(), self.state.is_playing);
    }

    fn expanded(&mut self, ui: &mut egui::Ui, ctx: &egui::Context) {
        let anim_t = self.state.is_playing.then(|| ctx.input(|i| i.time));
        if self.state.is_playing {
            ctx.request_repaint_after(Duration::from_millis(50));
        }
        let row_w = ui.max_rect().width();
        ui.spacing_mut().item_spacing.y = 11.0;
        ui.horizontal(|ui| {
            let drag = ui.allocate_response(vec2(170.0, 22.0), Sense::click_and_drag());
            if drag.drag_started() {
                ctx.send_viewport_cmd(egui::ViewportCommand::StartDrag);
            }
            ui.painter().circle_filled(
                pos2(drag.rect.left() + 6.0, drag.rect.center().y),
                3.5,
                if self.state.is_playing {
                    Color32::from_rgb(0x10, 0xb9, 0x81)
                } else {
                    TEXT_MUTED
                },
            );
            ui.painter().text(
                pos2(drag.rect.left() + 16.0, drag.rect.center().y),
                egui::Align2::LEFT_CENTER,
                "Isle",
                egui::FontId::proportional(12.5),
                TEXT_PRIMARY,
            );
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                let (c_rect, c_resp) = ui.allocate_exact_size(vec2(22.0, 22.0), Sense::click());
                ui.painter().circle(
                    c_rect.center(),
                    11.0,
                    SURFACE_SUBTLE,
                    Stroke::new(1.0_f32, SURFACE_BORDER),
                );
                ui.painter().text(
                    c_rect.center(),
                    egui::Align2::CENTER_CENTER,
                    "⌄",
                    egui::FontId::proportional(12.0),
                    TEXT_SECONDARY,
                );
                if c_resp.on_hover_text("Minimize to Pill (Esc)").clicked() {
                    self.set_expanded(ctx, false);
                }
                let status_label = if self.state.is_playing {
                    "PLAYING"
                } else {
                    "IDLE"
                };
                pill_badge(ui, status_label, true);
            });
        });
        let (title, artist) = self.track_meta();
        ui.horizontal(|ui| {
            let (art_rect, _) = ui.allocate_exact_size(vec2(64.0, 64.0), Sense::hover());
            paint_disc(ui.painter(), art_rect, anim_t);
            ui.add_space(6.0);
            ui.vertical(|ui| {
                ui.spacing_mut().item_spacing.y = 4.0;
                ui.label(
                    egui::RichText::new(truncate_title(&title, 20))
                        .strong()
                        .size(16.0)
                        .color(TEXT_PRIMARY),
                );
                ui.label(
                    egui::RichText::new(truncate_title(&artist, 26))
                        .size(12.0)
                        .color(TEXT_SECONDARY),
                );
                if let Some(msg) = self.state.status_message.as_deref() {
                    ui.label(
                        egui::RichText::new(truncate_title(msg, 32))
                            .size(10.5)
                            .color(ACCENT_LIGHT),
                    );
                }
            });
        });
        let duration = self
            .state
            .current_track
            .as_ref()
            .and_then(|t| t.duration_secs)
            .unwrap_or(0);
        let ratio = if duration > 0 {
            (self.state.elapsed_secs as f32 / duration as f32).clamp(0.0, 1.0)
        } else if self.state.is_playing {
            0.35
        } else {
            0.0
        };
        ui.vertical(|ui| {
            ui.spacing_mut().item_spacing.y = 5.0;
            let (bar_rect, bar_resp) =
                ui.allocate_exact_size(vec2(row_w, 14.0), Sense::click_and_drag());
            let track = Rect::from_center_size(bar_rect.center(), vec2(row_w, 4.5));
            if (bar_resp.clicked() || bar_resp.dragged()) && duration > 0 {
                if let Some(pos) = bar_resp.interact_pointer_pos() {
                    let seek_ratio = ((pos.x - track.left()) / track.width()).clamp(0.0, 1.0);
                    let seek_secs = (seek_ratio * duration as f32) as u64;
                    let _ = self
                        .cmd_tx
                        .send(AudioCommand::Seek(Duration::from_secs(seek_secs)));
                }
            }
            ui.painter().rect_filled(track, 2.5, TRACK_BG);
            let fill_w = (track.width() * ratio).max(if ratio > 0.0 { 6.0 } else { 0.0 });
            if fill_w > 0.0 {
                ui.painter().rect_filled(
                    Rect::from_min_size(track.min, vec2(fill_w, track.height())),
                    2.5,
                    ACCENT_PRIMARY,
                );
                let knob = pos2(track.left() + fill_w, track.center().y);
                ui.painter().circle_filled(knob, 7.0, ACCENT_GLOW);
                ui.painter().circle_filled(knob, 4.5, TEXT_PRIMARY);
            }
            let (ts_rect, _) = ui.allocate_exact_size(vec2(row_w, 14.0), Sense::hover());
            let mode =
                self.state
                    .status_message
                    .as_deref()
                    .unwrap_or(if self.state.autoplay_enabled {
                        "AUTOPLAY MIX"
                    } else {
                        "STANDARD"
                    });
            ui.painter().text(
                ts_rect.left_center(),
                egui::Align2::LEFT_CENTER,
                format_duration(self.state.elapsed_secs),
                egui::FontId::proportional(10.5),
                TEXT_MUTED,
            );
            ui.painter().text(
                ts_rect.center(),
                egui::Align2::CENTER_CENTER,
                truncate_title(&mode.to_uppercase(), 24),
                egui::FontId::proportional(9.5),
                ACCENT_LIGHT,
            );
            ui.painter().text(
                ts_rect.right_center(),
                egui::Align2::RIGHT_CENTER,
                format_duration(duration),
                egui::FontId::proportional(10.5),
                TEXT_MUTED,
            );
        });
        ui.horizontal(|ui| {
            ui.add_space((row_w - 272.0).max(0.0) * 0.5);
            ui.spacing_mut().item_spacing.x = 14.0;
            let (auto_lbl, q_lbl) =
                transport_labels(self.state.autoplay_enabled, self.state.queue.len());
            if paint_mode_btn(ui, ("∞", auto_lbl), self.state.autoplay_enabled)
                .on_hover_text("Toggle Autoplay Radio")
                .clicked()
            {
                self.state.autoplay_enabled = !self.state.autoplay_enabled;
                self.config.autoplay_enabled = self.state.autoplay_enabled;
                let _ = self
                    .cmd_tx
                    .send(AudioCommand::SetAutoplay(self.state.autoplay_enabled));
                self.mark_config_dirty();
            }
            let (prev_rect, prev_resp) = ui.allocate_exact_size(vec2(28.0, 44.0), Sense::click());
            if prev_resp.hovered() {
                ui.painter().rect_filled(
                    prev_rect.shrink(4.0),
                    Rounding::same(8.0),
                    SURFACE_SUBTLE,
                );
            }
            paint_skip(ui.painter(), prev_rect.center(), false);
            if prev_resp.on_hover_text("Previous Track").clicked() {
                let _ = self.cmd_tx.send(AudioCommand::PreviousTrack);
            }
            let (play_rect, play_resp) = ui.allocate_exact_size(vec2(48.0, 48.0), Sense::click());
            let (glow_sz, base_sz, play_col) = if play_resp.is_pointer_button_down_on() {
                (22.0, 18.0, ACCENT_MUTED)
            } else if play_resp.hovered() {
                (26.0, 21.0, ACCENT_LIGHT)
            } else {
                (24.0, 20.0, ACCENT_PRIMARY)
            };
            ui.painter()
                .circle_filled(play_rect.center(), glow_sz, ACCENT_GLOW);
            ui.painter()
                .circle_filled(play_rect.center(), base_sz, play_col);
            paint_play_pause(
                ui.painter(),
                play_rect.center(),
                self.state.is_playing && self.search_query.trim().is_empty(),
            );
            if play_resp.on_hover_text("Play / Pause").clicked() && !self.submit_search_if_present()
            {
                let _ = self.cmd_tx.send(AudioCommand::TogglePlayPause);
            }
            let (next_rect, next_resp) = ui.allocate_exact_size(vec2(28.0, 44.0), Sense::click());
            if next_resp.hovered() {
                ui.painter().rect_filled(
                    next_rect.shrink(4.0),
                    Rounding::same(8.0),
                    SURFACE_SUBTLE,
                );
            }
            paint_skip(ui.painter(), next_rect.center(), true);
            if next_resp.on_hover_text("Next Track").clicked() {
                let _ = self.cmd_tx.send(AudioCommand::NextTrack);
            }

            let q_btn = paint_mode_btn(ui, ("✕", &q_lbl), !self.state.queue.is_empty());
            let q_tooltip = if self.state.queue.is_empty() {
                "Track Queue (Empty)".to_owned()
            } else {
                let mut tip = format!("Clear Queue ({} tracks):\n", self.state.queue.len());
                for (i, t) in self.state.queue.iter().take(5).enumerate() {
                    tip.push_str(&format!("{}. {}\n", i + 1, truncate_title(&t.title, 32)));
                }
                if self.state.queue.len() > 5 {
                    tip.push_str(&format!("...and {} more", self.state.queue.len() - 5));
                }
                tip.trim_end().to_owned()
            };
            if q_btn.on_hover_text(q_tooltip).clicked() {
                let _ = self.cmd_tx.send(AudioCommand::ClearQueue);
            }
        });
        let (vol_box, _) = ui.allocate_exact_size(vec2(row_w, 30.0), Sense::hover());
        ui.painter().rect(
            vol_box,
            Rounding::same(12.0),
            SURFACE_SUBTLE,
            Stroke::new(1.0_f32, SURFACE_BORDER),
        );
        let is_muted = self.state.volume <= 0.001;
        let mute_rect = Rect::from_center_size(
            pos2(vol_box.left() + 16.0, vol_box.center().y),
            vec2(22.0, 22.0),
        );
        let mute_resp = ui.interact(mute_rect, ui.id().with("mute"), Sense::click());
        if mute_resp.hovered() {
            ui.painter().circle_filled(
                mute_rect.center(),
                10.0,
                Color32::from_rgb(0x22, 0x1f, 0x30),
            );
        }
        paint_speaker(
            ui.painter(),
            pos2(vol_box.left() + 16.0, vol_box.center().y),
            false,
            is_muted,
        );
        if mute_resp
            .on_hover_text(if is_muted { "Unmute" } else { "Mute" })
            .clicked()
        {
            if is_muted {
                self.state.volume = self.muted_volume.take().unwrap_or(0.75);
            } else {
                self.muted_volume = Some(self.state.volume);
                self.state.volume = 0.0;
            }
            self.config.volume = self.state.volume;
            let _ = self.cmd_tx.send(AudioCommand::SetVolume(self.state.volume));
            self.mark_config_dirty();
        }
        paint_speaker(
            ui.painter(),
            pos2(vol_box.right() - 16.0, vol_box.center().y),
            true,
            false,
        );
        let v_rect = Rect::from_min_max(
            pos2(vol_box.left() + 30.0, vol_box.top() + 4.0),
            pos2(vol_box.right() - 30.0, vol_box.bottom() - 4.0),
        );
        let v_resp = ui.interact(v_rect, ui.id().with("vol"), Sense::click_and_drag());
        if (v_resp.clicked() || v_resp.dragged()) && v_rect.width() > 0.0 {
            if let Some(pos) = v_resp.interact_pointer_pos() {
                let vol = ((pos.x - v_rect.left()) / v_rect.width()).clamp(0.0, 1.0) * 1.5;
                self.state.volume = vol;
                self.config.volume = vol;
                if vol > 0.001 {
                    self.muted_volume = None;
                }
                let _ = self.cmd_tx.send(AudioCommand::SetVolume(vol));
                self.mark_config_dirty();
            }
        }
        let vol_pct = (self.state.volume * 100.0).round() as u32;
        let knob_col = if v_resp.hovered() || v_resp.dragged() {
            ACCENT_LIGHT
        } else {
            TEXT_PRIMARY
        };
        v_resp.on_hover_text(format!("Volume: {vol_pct}%"));
        let v_track = Rect::from_center_size(v_rect.center(), vec2(v_rect.width(), 4.0));
        let vw = v_track.width() * (self.state.volume / 1.5).clamp(0.0, 1.0);
        ui.painter().rect_filled(v_track, 2.0, TRACK_BG);
        ui.painter().rect_filled(
            Rect::from_min_size(v_track.min, vec2(vw, 4.0)),
            2.0,
            ACCENT_PRIMARY,
        );
        ui.painter()
            .circle_filled(pos2(v_track.left() + vw, v_track.center().y), 5.0, knob_col);
        let (s_box, _) = ui.allocate_exact_size(vec2(row_w, 36.0), Sense::hover());
        ui.painter().rect(
            s_box,
            Rounding::same(14.0),
            SURFACE_SUBTLE,
            Stroke::new(1.0_f32, SURFACE_BORDER),
        );
        paint_search(ui.painter(), pos2(s_box.left() + 16.0, s_box.center().y));
        let has_query = !self.search_query.is_empty();
        let right_offset = if has_query { 64.0 } else { 44.0 };
        let edit_rect = Rect::from_min_max(
            pos2(s_box.left() + 32.0, s_box.top() + 7.0),
            pos2(s_box.right() - right_offset, s_box.bottom() - 7.0),
        );
        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(edit_rect), |ui| {
            let input = ui.add_sized(
                edit_rect.size(),
                egui::TextEdit::singleline(&mut self.search_query)
                    .frame(false)
                    .text_color(TEXT_PRIMARY)
                    .hint_text("Search song, YouTube, or Spotify URL..."),
            );
            if input.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter)) {
                self.submit_search_if_present();
            }
        });
        if has_query {
            let clr_rect = Rect::from_center_size(
                pos2(s_box.right() - 48.0, s_box.center().y),
                vec2(18.0, 18.0),
            );
            let clr_resp = ui.interact(clr_rect, ui.id().with("clr_search"), Sense::click());
            if clr_resp.hovered() {
                ui.painter().circle_filled(
                    clr_rect.center(),
                    9.0,
                    Color32::from_rgb(0x22, 0x1f, 0x30),
                );
            }
            ui.painter().text(
                clr_rect.center(),
                egui::Align2::CENTER_CENTER,
                "×",
                egui::FontId::proportional(13.0),
                TEXT_MUTED,
            );
            if clr_resp.on_hover_text("Clear Input").clicked() {
                self.search_query.clear();
            }
        }
        let go_rect = Rect::from_center_size(
            pos2(s_box.right() - 20.0, s_box.center().y),
            vec2(30.0, 24.0),
        );
        let go_resp = ui.interact(go_rect, ui.id().with("go"), Sense::click());
        let go_fill = if go_resp.hovered() {
            ACCENT_PRIMARY
        } else {
            ACCENT_MUTED
        };
        ui.painter()
            .rect_filled(go_rect, Rounding::same(8.0), go_fill);
        ui.painter().text(
            go_rect.center(),
            egui::Align2::CENTER_CENTER,
            "→",
            egui::FontId::proportional(13.0),
            TEXT_PRIMARY,
        );
        if go_resp
            .on_hover_text("Play / Submit URL or Search")
            .clicked()
            && !self.submit_search_if_present()
        {
            let _ = self.cmd_tx.send(AudioCommand::TogglePlayPause);
        }
    }
}

impl eframe::App for IsleApp {
    fn clear_color(&self, _visuals: &egui::Visuals) -> [f32; 4] {
        [0.0, 0.0, 0.0, 0.0]
    }
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        #[cfg(windows)]
        let mut media_state_changed = false;
        while let Ok(next_state) = self.state_rx.try_recv() {
            #[cfg(windows)]
            {
                media_state_changed |= crate::audio::media_state_changed(&self.state, &next_state);
            }
            self.state = next_state;
        }
        #[cfg(windows)]
        if media_state_changed {
            self.media_keys.sync_state(&self.state);
        }
        ctx.request_repaint_after(Duration::from_millis(250));
        let (escape, toggle) = ctx.input(|i| {
            (
                i.key_pressed(egui::Key::Escape),
                i.modifiers.ctrl && i.modifiers.shift && i.key_pressed(egui::Key::Space),
            )
        });
        if escape {
            self.set_expanded(ctx, false);
        }
        if toggle {
            self.set_expanded(ctx, !self.expanded);
        }
        if !ctx.wants_keyboard_input() {
            if ctx.input(|i| i.key_pressed(egui::Key::Space)) {
                let _ = self.cmd_tx.send(AudioCommand::TogglePlayPause);
            }
            if ctx.input(|i| i.key_pressed(egui::Key::ArrowLeft)) {
                let new_secs = self.state.elapsed_secs.saturating_sub(5);
                let _ = self
                    .cmd_tx
                    .send(AudioCommand::Seek(Duration::from_secs(new_secs)));
            }
            if ctx.input(|i| i.key_pressed(egui::Key::ArrowRight)) {
                let duration = self
                    .state
                    .current_track
                    .as_ref()
                    .and_then(|t| t.duration_secs)
                    .unwrap_or(u64::MAX);
                let new_secs = (self.state.elapsed_secs + 5).min(duration);
                let _ = self
                    .cmd_tx
                    .send(AudioCommand::Seek(Duration::from_secs(new_secs)));
            }
            if ctx.input(|i| i.key_pressed(egui::Key::ArrowUp)) {
                let vol = (self.state.volume + 0.075).clamp(0.0, 1.5);
                self.state.volume = vol;
                self.config.volume = vol;
                if vol > 0.001 {
                    self.muted_volume = None;
                }
                let _ = self.cmd_tx.send(AudioCommand::SetVolume(vol));
                self.mark_config_dirty();
            }
            if ctx.input(|i| i.key_pressed(egui::Key::ArrowDown)) {
                let vol = (self.state.volume - 0.075).clamp(0.0, 1.5);
                self.state.volume = vol;
                self.config.volume = vol;
                let _ = self.cmd_tx.send(AudioCommand::SetVolume(vol));
                self.mark_config_dirty();
            }
        }
        if let Some(pos) = ctx.input(|i| i.viewport().outer_rect.map(|r| r.min)) {
            if self.last_window_pos != Some(pos) {
                self.last_window_pos = Some(pos);
                self.config.window_x = Some(pos.x);
                self.config.window_y = Some(pos.y);
                self.mark_config_dirty();
            }
        }

        let anim_id = egui::Id::new("isle_expansion_anim");
        let raw_t = ctx.animate_bool_with_time(anim_id, self.expanded, 0.22);
        let t = expand_progress(raw_t);

        if !self.expanded && self.current_viewport_is_expanded && raw_t <= 0.001 {
            self.resize(ctx, false);
            self.current_viewport_is_expanded = false;
        }

        egui::CentralPanel::default()
            .frame(egui::Frame::none())
            .show(ctx, |ui| {
                let (current_size, rounding) = island_dimensions(raw_t);
                let origin = ui.max_rect().min + vec2(1.0, 1.0);
                let island_rect = Rect::from_min_size(origin, current_size - vec2(2.0, 2.0));

                ui.painter().rect(
                    island_rect,
                    Rounding::same(rounding),
                    ISLAND_BG,
                    Stroke::new(1.2_f32, ISLAND_BORDER),
                );

                if t < 0.35 {
                    let pill_content_rect = Rect::from_min_size(
                        island_rect.min,
                        vec2(PILL_WIDTH - 2.0, PILL_HEIGHT - 2.0),
                    );
                    let opacity = if t <= 0.1 {
                        1.0
                    } else {
                        ((0.35 - t) / 0.25).clamp(0.0, 1.0)
                    };
                    ui.allocate_new_ui(
                        egui::UiBuilder::new().max_rect(pill_content_rect),
                        |ui| {
                            ui.set_clip_rect(island_rect);
                            ui.set_opacity(opacity);
                            self.collapsed(ui, ctx);
                        },
                    );
                } else {
                    let content_rect = island_rect.shrink(16.0);
                    let opacity = if t >= 0.7 {
                        1.0
                    } else {
                        ((t - 0.35) / 0.35).clamp(0.0, 1.0)
                    };
                    ui.allocate_new_ui(egui::UiBuilder::new().max_rect(content_rect), |ui| {
                        ui.set_clip_rect(island_rect);
                        ui.set_opacity(opacity);
                        self.expanded(ui, ctx);
                    });
                }
            });
        self.save_config_if_dirty();
    }

    fn on_exit(&mut self, _gl: Option<&eframe::glow::Context>) {
        self.flush_config();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn formats_duration_and_truncates_titles() {
        assert_eq!(
            (
                format_duration(225).as_str(),
                truncate_title("abcdef", 5).as_str(),
                truncate_title("abc", 5).as_str()
            ),
            ("03:45", "abcd…", "abc")
        );
    }
    #[test]
    fn selects_window_size_and_transport_labels() {
        assert_eq!(
            (target_window_size(false), target_window_size(true)),
            (
                vec2(PILL_WIDTH, PILL_HEIGHT),
                vec2(EXPANDED_WIDTH, EXPANDED_HEIGHT)
            )
        );
        assert_eq!(
            transport_labels(true, 12),
            ("AUTO ON", "CLR (12)".to_owned())
        );
        assert_eq!(transport_labels(false, 0), ("AUTO OFF", "CLEAR".to_owned()));
    }
    #[test]
    fn calculates_expansion_progress_and_dimensions() {
        assert_eq!(expand_progress(0.0), 0.0);
        assert_eq!(expand_progress(1.0), 1.0);
        assert_eq!(expand_progress(0.5), 0.5);

        let (size_collapsed, radius_collapsed) = island_dimensions(0.0);
        assert_eq!(size_collapsed, vec2(PILL_WIDTH, PILL_HEIGHT));
        assert_eq!(radius_collapsed, 27.0);

        let (size_expanded, radius_expanded) = island_dimensions(1.0);
        assert_eq!(size_expanded, vec2(EXPANDED_WIDTH, EXPANDED_HEIGHT));
        assert_eq!(radius_expanded, 26.0);
    }
}
