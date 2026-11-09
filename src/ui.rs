use std::sync::mpsc::{Receiver, Sender};
use std::time::Duration;

use eframe::egui::{self, Color32, Pos2, Rect, Rounding, Sense, Stroke, Vec2, pos2, vec2};

use crate::models::{AppConfig, AudioCommand, PlayerState};

pub const PILL_WIDTH: f32 = 290.0;
pub const PILL_HEIGHT: f32 = 54.0;
pub const EXPANDED_WIDTH: f32 = 360.0;
pub const EXPANDED_HEIGHT: f32 = 320.0;
pub const ISLAND_BG: Color32 = Color32::from_rgb(0x0b, 0x09, 0x1e);
pub const ISLAND_BORDER: Color32 = Color32::from_rgb(0x26, 0x24, 0x3b);

pub fn format_duration(secs: u64) -> String { format!("{:02}:{:02}", secs / 60, secs % 60) }
pub fn target_window_size(expanded: bool) -> Vec2 { if expanded { vec2(EXPANDED_WIDTH, EXPANDED_HEIGHT) } else { vec2(PILL_WIDTH, PILL_HEIGHT) } }
pub fn transport_labels(autoplay: bool, queue_len: usize) -> (&'static str, String) { (if autoplay { "AUTO ON" } else { "AUTO OFF" }, if queue_len == 0 { "CLEAR".to_owned() } else { format!("CLR ({queue_len})") }) }
pub fn truncate_title(title: &str, max_chars: usize) -> String {
    if max_chars == 0 { return String::new(); }
    let chars: Vec<char> = title.chars().collect();
    if chars.len() <= max_chars { title.to_owned() } else { chars.into_iter().take(max_chars - 1).chain(['…']).collect() }
}

fn pill_badge(ui: &mut egui::Ui, text: &str, dark: bool) {
    let (fill, stroke, fg) = if dark { (Color32::from_rgb(0x1e, 0x19, 0x34), Color32::from_rgb(0x32, 0x27, 0x4c), Color32::from_rgb(0xd4, 0xaf, 0xfc)) } else { (Color32::from_rgb(0x2b, 0x18, 0x4b), Color32::from_rgb(0x5f, 0x45, 0x85), Color32::from_rgb(0xd8, 0xb4, 0xff)) };
    egui::Frame::none().fill(fill).stroke(Stroke::new(1.0_f32, stroke)).rounding(Rounding::same(8.0)).inner_margin(egui::Margin::symmetric(6.0, 2.0)).show(ui, |ui| { ui.label(egui::RichText::new(text).size(9.5).strong().color(fg)); });
}

fn paint_play_pause(painter: &egui::Painter, center: Pos2, playing: bool) {
    if playing { for dx in [-3.0_f32, 3.0] { painter.rect_filled(Rect::from_center_size(pos2(center.x + dx, center.y), vec2(2.5, 9.5)), 1.2, Color32::WHITE); } }
    else { painter.add(egui::Shape::convex_polygon(vec![pos2(center.x - 3.5, center.y - 5.2), pos2(center.x - 3.5, center.y + 5.2), pos2(center.x + 5.2, center.y)], Color32::WHITE, Stroke::NONE)); }
}

fn paint_skip(painter: &egui::Painter, center: Pos2, next: bool) {
    let (dir, col) = (if next { 1.0 } else { -1.0 }, Color32::from_rgb(0xcf, 0xd9, 0xe5));
    painter.add(egui::Shape::convex_polygon(vec![pos2(center.x - 4.0 * dir, center.y - 5.0), pos2(center.x - 4.0 * dir, center.y + 5.0), pos2(center.x + 3.0 * dir, center.y)], col, Stroke::NONE));
    painter.rect_filled(Rect::from_center_size(pos2(center.x + 4.5 * dir, center.y), vec2(2.0, 10.0)), 1.0, col);
}

fn paint_mode_btn(ui: &mut egui::Ui, label: (&str, &str), active: bool) -> egui::Response {
    let (rect, resp) = ui.allocate_exact_size(vec2(56.0, 38.0), Sense::click());
    let (fill, stroke, fg) = if active { (Color32::from_rgb(0x4c, 0x1d, 0x95), Color32::from_rgb(0xc0, 0x84, 0xfc), Color32::WHITE) } else { (Color32::from_rgb(0x14, 0x12, 0x22), Color32::from_rgb(0x28, 0x24, 0x38), Color32::from_rgb(0x5e, 0x58, 0x73)) };
    ui.painter().rect(rect, Rounding::same(10.0), fill, Stroke::new(if active { 1.2_f32 } else { 1.0_f32 }, stroke));
    for (dy, txt, sz) in [(-6.0_f32, label.0, 12.5_f32), (8.0, label.1, 8.5)] { ui.painter().text(pos2(rect.center().x, rect.center().y + dy), egui::Align2::CENTER_CENTER, txt, egui::FontId::proportional(sz), fg); }
    resp
}

fn paint_disc(painter: &egui::Painter, rect: Rect, anim_t: Option<f64>) {
    let (center, square) = (rect.center(), rect.width() > 40.0);
    if square {
        painter.rect(rect, Rounding::same(14.0), Color32::from_rgb(0x14, 0x17, 0x28), Stroke::new(1.0_f32, Color32::from_rgb(0x34, 0x25, 0x57)));
        painter.circle(center, 18.0, Color32::from_rgb(0x28, 0x24, 0x38), Stroke::new(1.5_f32, Color32::from_rgb(0xd4, 0xbc, 0x72)));
        let badge = Rect::from_min_size(pos2(rect.right() - 16.0, rect.bottom() - 16.0), vec2(18.0, 18.0));
        painter.rect_filled(badge, Rounding::same(6.0), Color32::from_rgb(0x6d, 0x3d, 0xad));
        painter.text(badge.center(), egui::Align2::CENTER_CENTER, "♫", egui::FontId::proportional(10.0), Color32::WHITE);
    } else {
        painter.circle(center, 15.5, Color32::from_rgb(0x0e, 0x14, 0x28), Stroke::new(2.0_f32, Color32::from_rgb(0x95, 0x78, 0xfe)));
        painter.circle_filled(center, 3.5, Color32::from_rgb(0xc0, 0x84, 0xfc));
    }
    for (idx, (dx, base)) in [(-4.0_f32, 6.0_f32), (0.0, 11.0), (4.0, 8.0)].into_iter().enumerate() {
        let phase = anim_t.map_or(0.25, |t| ((t as f32) * 5.5 + idx as f32 * 1.3).sin().abs());
        painter.rect_filled(Rect::from_center_size(pos2(center.x + dx, center.y + if square { 16.0 } else { 0.0 }), vec2(2.4, base * (0.45 + phase * 0.55))), 1.2, Color32::from_rgb(0xc0, 0x84, 0xfc));
    }
}

pub struct IsleApp {
    cmd_tx: Sender<AudioCommand>, state_rx: Receiver<PlayerState>, state: PlayerState,
    config: AppConfig, expanded: bool, search_query: String, last_window_pos: Option<Pos2>,
}

impl IsleApp {
    pub fn new(_cc: &eframe::CreationContext<'_>, cmd_tx: Sender<AudioCommand>, state_rx: Receiver<PlayerState>, config: AppConfig) -> Self {
        Self { cmd_tx, state_rx, state: PlayerState::default(), config, expanded: false, search_query: String::new(), last_window_pos: None }
    }

    fn resize(&self, ctx: &egui::Context, expanded: bool) { ctx.send_viewport_cmd(egui::ViewportCommand::InnerSize(target_window_size(expanded))); }

    fn track_meta(&self) -> (String, String) {
        if !self.state.ytdlp_available { return ("yt-dlp missing".to_owned(), "Install yt-dlp in PATH".to_owned()); }
        if let Some(track) = self.state.current_track.as_ref() {
            let clean = track.title.trim_start_matches('@');
            if let Some((artist, song)) = clean.split_once(" - ") { return (song.split(['(', '[']).next().unwrap_or(song).trim().to_owned(), artist.trim().to_owned()); }
            return (clean.to_owned(), track.artist.as_deref().unwrap_or("Isle Audio").trim_start_matches('@').to_owned());
        }
        ("Isle Ready".to_owned(), self.state.status_message.as_deref().unwrap_or("Search or paste URL").to_owned())
    }

    fn submit_search_if_present(&mut self) -> bool {
        if self.search_query.trim().is_empty() { return false; }
        let query = std::mem::take(&mut self.search_query);
        let _ = self.cmd_tx.send(AudioCommand::SubmitInput(query.trim().to_owned()));
        true
    }

    fn collapsed(&mut self, ui: &mut egui::Ui, ctx: &egui::Context) {
        let anim_t = self.state.is_playing.then(|| ctx.input(|i| i.time));
        if self.state.is_playing { ctx.request_repaint_after(Duration::from_millis(50)); }
        let (full, (title, artist)) = (ui.max_rect(), self.track_meta());
        let btn_rect = Rect::from_center_size(pos2(full.right() - 28.0, full.center().y), vec2(30.0, 30.0));
        let bg_resp = ui.allocate_rect(Rect::from_min_max(full.min, pos2(btn_rect.left() - 8.0, full.max.y)), Sense::click_and_drag());
        if bg_resp.drag_started() { ctx.send_viewport_cmd(egui::ViewportCommand::StartDrag); }
        if bg_resp.clicked() { self.expanded = true; self.resize(ctx, true); }
        if ui.allocate_rect(btn_rect, Sense::click()).on_hover_text("Play / Pause").clicked() { let _ = self.cmd_tx.send(AudioCommand::TogglePlayPause); }
        let sub = self.state.status_message.as_deref().unwrap_or(&artist);
        paint_disc(ui.painter(), Rect::from_center_size(pos2(full.left() + 27.0, full.center().y), vec2(34.0, 34.0)), anim_t);
        let info_rect = Rect::from_min_max(pos2(full.left() + 52.0, full.top() + 8.0), pos2(btn_rect.left() - 14.0, full.bottom() - 8.0));
        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(info_rect), |ui| {
            ui.vertical(|ui| {
                ui.spacing_mut().item_spacing.y = 2.0;
                ui.horizontal(|ui| { ui.label(egui::RichText::new(truncate_title(&title, 15)).strong().size(13.0).color(Color32::WHITE)); pill_badge(ui, "Hi-Res", false); });
                ui.label(egui::RichText::new(truncate_title(sub, 25)).size(11.0).color(Color32::from_rgb(0x8e, 0x82, 0xa2)));
            });
        });
        let (div_x, p) = (btn_rect.left() - 8.0, ui.painter());
        p.line_segment([pos2(div_x, full.top() + 12.0), pos2(div_x, full.bottom() - 12.0)], Stroke::new(1.0_f32, Color32::from_rgb(0x25, 0x23, 0x36)));
        p.circle(btn_rect.center(), 15.0, Color32::from_rgb(0x1c, 0x1a, 0x2e), Stroke::new(1.0_f32, Color32::from_rgb(0x28, 0x26, 0x3d)));
        paint_play_pause(p, btn_rect.center(), self.state.is_playing);
    }

    fn expanded(&mut self, ui: &mut egui::Ui, ctx: &egui::Context) {
        let anim_t = self.state.is_playing.then(|| ctx.input(|i| i.time));
        if self.state.is_playing { ctx.request_repaint_after(Duration::from_millis(50)); }
        let row_w = ui.max_rect().width();
        ui.spacing_mut().item_spacing.y = 11.0;
        ui.horizontal(|ui| {
            let drag = ui.allocate_response(vec2(170.0, 22.0), Sense::click_and_drag());
            if drag.drag_started() { ctx.send_viewport_cmd(egui::ViewportCommand::StartDrag); }
            ui.painter().circle_filled(pos2(drag.rect.left() + 6.0, drag.rect.center().y), 4.0, Color32::from_rgb(0x10, 0xb9, 0x81));
            ui.painter().text(pos2(drag.rect.left() + 16.0, drag.rect.center().y), egui::Align2::LEFT_CENTER, "Isle  •  Rust Core v2.4", egui::FontId::proportional(11.5), Color32::from_rgb(0xc5, 0xcf, 0xdb));
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                let (c_rect, c_resp) = ui.allocate_exact_size(vec2(22.0, 22.0), Sense::click());
                ui.painter().circle(c_rect.center(), 11.0, Color32::from_rgb(0x25, 0x1a, 0x3e), Stroke::new(1.0_f32, Color32::from_rgb(0x32, 0x27, 0x4c)));
                ui.painter().text(c_rect.center(), egui::Align2::CENTER_CENTER, "⌄", egui::FontId::proportional(12.0), Color32::from_rgb(0xc7, 0xc8, 0xe4));
                if c_resp.on_hover_text("Minimize to Pill (Esc)").clicked() { self.expanded = false; self.resize(ctx, false); }
                pill_badge(ui, "24-BIT | 96KHZ", true);
            });
        });
        let (title, artist) = self.track_meta();
        ui.horizontal(|ui| {
            let (art_rect, _) = ui.allocate_exact_size(vec2(64.0, 64.0), Sense::hover());
            paint_disc(ui.painter(), art_rect, anim_t);
            ui.add_space(6.0);
            ui.vertical(|ui| {
                ui.spacing_mut().item_spacing.y = 4.0;
                ui.label(egui::RichText::new(truncate_title(&title, 20)).strong().size(16.0).color(Color32::WHITE));
                ui.label(egui::RichText::new(truncate_title(&format!("{artist}  •  Single"), 26)).size(12.0).color(Color32::from_rgb(0xa6, 0x98, 0xbc)));
                ui.horizontal(|ui| { pill_badge(ui, "● LOSSLESS", false); ui.label(egui::RichText::new("Stereo  •  48kHz").size(10.5).color(Color32::from_rgb(0x78, 0x64, 0x99))); });
            });
        });
        let duration = self.state.current_track.as_ref().and_then(|t| t.duration_secs).unwrap_or(0);
        let ratio = if duration > 0 { (self.state.elapsed_secs as f32 / duration as f32).clamp(0.0, 1.0) } else if self.state.is_playing { 0.35 } else { 0.0 };
        ui.vertical(|ui| {
            ui.spacing_mut().item_spacing.y = 5.0;
            let (bar_rect, _) = ui.allocate_exact_size(vec2(row_w, 8.0), Sense::hover());
            let track = Rect::from_center_size(bar_rect.center(), vec2(row_w, 4.5));
            ui.painter().rect_filled(track, 2.5, Color32::from_rgb(0x27, 0x23, 0x3c));
            let fill_w = (track.width() * ratio).max(if ratio > 0.0 { 6.0 } else { 0.0 });
            if fill_w > 0.0 {
                ui.painter().rect_filled(Rect::from_min_size(track.min, vec2(fill_w, track.height())), 2.5, Color32::from_rgb(0xa4, 0x56, 0xf7));
                let knob = pos2(track.left() + fill_w, track.center().y);
                ui.painter().circle_filled(knob, 7.0, Color32::from_rgba_unmultiplied(0xa4, 0x56, 0xf7, 70));
                ui.painter().circle_filled(knob, 4.5, Color32::WHITE);
            }
            let (ts_rect, _) = ui.allocate_exact_size(vec2(row_w, 14.0), Sense::hover());
            let mode = self.state.status_message.as_deref().unwrap_or(if self.state.autoplay_enabled { "AUTOPLAY  •  RADIO MIX" } else { "STREAMING  •  HI-RES" });
            ui.painter().text(ts_rect.left_center(), egui::Align2::LEFT_CENTER, format_duration(self.state.elapsed_secs), egui::FontId::proportional(10.5), Color32::from_rgb(0x88, 0x7b, 0x9d));
            ui.painter().text(ts_rect.center(), egui::Align2::CENTER_CENTER, truncate_title(&mode.to_uppercase(), 24), egui::FontId::proportional(9.5), Color32::from_rgb(0x9c, 0x6c, 0xd2));
            ui.painter().text(ts_rect.right_center(), egui::Align2::RIGHT_CENTER, format_duration(duration), egui::FontId::proportional(10.5), Color32::from_rgb(0x88, 0x7b, 0x9d));
        });
        ui.horizontal(|ui| {
            ui.add_space((row_w - 272.0).max(0.0) * 0.5);
            ui.spacing_mut().item_spacing.x = 14.0;
            let (auto_lbl, q_lbl) = transport_labels(self.state.autoplay_enabled, self.state.queue.len());
            if paint_mode_btn(ui, ("∞", auto_lbl), self.state.autoplay_enabled).on_hover_text("Toggle Autoplay Radio").clicked() {
                self.state.autoplay_enabled = !self.state.autoplay_enabled; self.config.autoplay_enabled = self.state.autoplay_enabled;
                let _ = self.cmd_tx.send(AudioCommand::SetAutoplay(self.state.autoplay_enabled));
                let _ = crate::config::save_config(&self.config);
            }
            let (prev_rect, prev_resp) = ui.allocate_exact_size(vec2(28.0, 44.0), Sense::click());
            paint_skip(ui.painter(), prev_rect.center(), false);
            if prev_resp.on_hover_text("Previous Track").clicked() { let _ = self.cmd_tx.send(AudioCommand::PreviousTrack); }
            let (play_rect, play_resp) = ui.allocate_exact_size(vec2(48.0, 48.0), Sense::click());
            ui.painter().circle_filled(play_rect.center(), 24.0, Color32::from_rgba_unmultiplied(0x75, 0x3d, 0xed, 60));
            ui.painter().circle_filled(play_rect.center(), 20.0, Color32::from_rgb(0x75, 0x3d, 0xed));
            paint_play_pause(ui.painter(), play_rect.center(), self.state.is_playing && self.search_query.trim().is_empty());
            if play_resp.on_hover_text("Play / Pause").clicked() && !self.submit_search_if_present() { let _ = self.cmd_tx.send(AudioCommand::TogglePlayPause); }
            let (next_rect, next_resp) = ui.allocate_exact_size(vec2(28.0, 44.0), Sense::click());
            paint_skip(ui.painter(), next_rect.center(), true);
            if next_resp.on_hover_text("Next Track").clicked() { let _ = self.cmd_tx.send(AudioCommand::NextTrack); }
            if paint_mode_btn(ui, ("✕", &q_lbl), !self.state.queue.is_empty()).on_hover_text("Clear Track Queue").clicked() { let _ = self.cmd_tx.send(AudioCommand::ClearQueue); }
        });
        let (vol_box, _) = ui.allocate_exact_size(vec2(row_w, 30.0), Sense::hover());
        ui.painter().rect(vol_box, Rounding::same(12.0), Color32::from_rgb(0x10, 0x0f, 0x1b), Stroke::new(1.0_f32, Color32::from_rgb(0x1b, 0x1a, 0x25)));
        ui.painter().text(pos2(vol_box.left() + 16.0, vol_box.center().y), egui::Align2::CENTER_CENTER, "🔈", egui::FontId::proportional(12.0), Color32::from_rgb(0x88, 0x72, 0xa3));
        ui.painter().text(pos2(vol_box.right() - 16.0, vol_box.center().y), egui::Align2::CENTER_CENTER, "🔊", egui::FontId::proportional(12.0), Color32::from_rgb(0x8b, 0x74, 0xa6));
        let v_rect = Rect::from_min_max(pos2(vol_box.left() + 30.0, vol_box.top() + 4.0), pos2(vol_box.right() - 30.0, vol_box.bottom() - 4.0));
        let v_resp = ui.interact(v_rect, ui.id().with("vol"), Sense::click_and_drag());
        if (v_resp.clicked() || v_resp.dragged()) && v_rect.width() > 0.0 {
            if let Some(pos) = v_resp.interact_pointer_pos() {
                let vol = ((pos.x - v_rect.left()) / v_rect.width()).clamp(0.0, 1.0) * 1.5;
                self.state.volume = vol; self.config.volume = vol;
                let _ = self.cmd_tx.send(AudioCommand::SetVolume(vol)); let _ = crate::config::save_config(&self.config);
            }
        }
        let v_track = Rect::from_center_size(v_rect.center(), vec2(v_rect.width(), 4.0));
        let vw = v_track.width() * (self.state.volume / 1.5).clamp(0.0, 1.0);
        ui.painter().rect_filled(v_track, 2.0, Color32::from_rgb(0x3b, 0x36, 0x47));
        ui.painter().rect_filled(Rect::from_min_size(v_track.min, vec2(vw, 4.0)), 2.0, Color32::from_rgb(0xc0, 0x84, 0xfc));
        ui.painter().circle_filled(pos2(v_track.left() + vw, v_track.center().y), 5.0, Color32::WHITE);
        let (s_box, _) = ui.allocate_exact_size(vec2(row_w, 36.0), Sense::hover());
        ui.painter().rect(s_box, Rounding::same(14.0), Color32::from_rgb(0x0b, 0x09, 0x14), Stroke::new(1.0_f32, Color32::from_rgb(0x1f, 0x1f, 0x25)));
        ui.painter().text(pos2(s_box.left() + 16.0, s_box.center().y), egui::Align2::CENTER_CENTER, "🔍", egui::FontId::proportional(12.0), Color32::from_rgb(0x5c, 0x4c, 0x6f));
        let edit_rect = Rect::from_min_max(pos2(s_box.left() + 28.0, s_box.top() + 7.0), pos2(s_box.right() - 88.0, s_box.bottom() - 7.0));
        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(edit_rect), |ui| {
            let input = ui.add_sized(edit_rect.size(), egui::TextEdit::singleline(&mut self.search_query).frame(false).text_color(Color32::WHITE).hint_text("Search, YouTube, or Spotify URL..."));
            if input.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter)) { self.submit_search_if_present(); }
        });
        ui.painter().circle_filled(pos2(s_box.right() - 72.0, s_box.center().y), 7.0, Color32::from_rgb(0xb1, 0x1e, 0x1f));
        ui.painter().circle_filled(pos2(s_box.right() - 52.0, s_box.center().y), 7.0, Color32::from_rgb(0x0e, 0x94, 0x68));
        let go_rect = Rect::from_center_size(pos2(s_box.right() - 22.0, s_box.center().y), vec2(32.0, 24.0));
        ui.painter().rect_filled(go_rect, Rounding::same(8.0), Color32::from_rgb(0x5c, 0x21, 0x93));
        ui.painter().text(go_rect.center(), egui::Align2::CENTER_CENTER, "→", egui::FontId::proportional(13.0), Color32::WHITE);
        if ui.interact(go_rect, ui.id().with("go"), Sense::click()).on_hover_text("Play / Submit URL or Search").clicked() && !self.submit_search_if_present() { let _ = self.cmd_tx.send(AudioCommand::TogglePlayPause); }
    }
}

impl eframe::App for IsleApp {
    fn clear_color(&self, _visuals: &egui::Visuals) -> [f32; 4] { [0.0, 0.0, 0.0, 0.0] }
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        while let Ok(next_state) = self.state_rx.try_recv() { self.state = next_state; }
        ctx.request_repaint_after(Duration::from_millis(250));
        let (escape, toggle) = ctx.input(|i| (i.key_pressed(egui::Key::Escape), i.modifiers.ctrl && i.modifiers.shift && i.key_pressed(egui::Key::Space)));
        if escape { self.expanded = false; self.resize(ctx, false); }
        if toggle { self.expanded = !self.expanded; self.resize(ctx, self.expanded); }
        if let Some(pos) = ctx.input(|i| i.viewport().outer_rect.map(|r| r.min)) {
            if self.last_window_pos != Some(pos) {
                self.last_window_pos = Some(pos); self.config.window_x = Some(pos.x); self.config.window_y = Some(pos.y);
                let _ = crate::config::save_config(&self.config);
            }
        }
        egui::CentralPanel::default().frame(egui::Frame::none()).show(ctx, |ui| {
            let panel = ui.max_rect().shrink(1.0);
            let (rounding, content) = if self.expanded { (26.0, panel.shrink(16.0)) } else { (27.0, panel) };
            ui.painter().rect(panel, Rounding::same(rounding), ISLAND_BG, Stroke::new(1.2_f32, ISLAND_BORDER));
            ui.allocate_new_ui(egui::UiBuilder::new().max_rect(content), |ui| {
                if self.expanded { self.expanded(ui, ctx); } else { self.collapsed(ui, ctx); }
            });
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn formats_duration_and_truncates_titles() {
        assert_eq!((format_duration(225).as_str(), truncate_title("abcdef", 5).as_str(), truncate_title("abc", 5).as_str()), ("03:45", "abcd…", "abc"));
    }
    #[test]
    fn selects_window_size_and_transport_labels() {
        assert_eq!((target_window_size(false), target_window_size(true)), (vec2(PILL_WIDTH, PILL_HEIGHT), vec2(EXPANDED_WIDTH, EXPANDED_HEIGHT)));
        assert_eq!(transport_labels(true, 12), ("AUTO ON", "CLR (12)".to_owned()));
        assert_eq!(transport_labels(false, 0), ("AUTO OFF", "CLEAR".to_owned()));
    }
}
