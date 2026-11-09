pub mod audio;
pub mod config;
pub mod extractor;
pub mod media_keys;
pub mod models;
pub mod ui;

fn main() -> eframe::Result<()> {
    let config = config::load_config();
    let (cmd_tx, state_rx) = audio::spawn_audio_worker(config.clone());
    let mut viewport = eframe::egui::ViewportBuilder::default()
        .with_decorations(false)
        .with_transparent(true)
        .with_always_on_top()
        .with_inner_size([ui::PILL_WIDTH, ui::PILL_HEIGHT])
        .with_resizable(false);
    if let (Some(x), Some(y)) = (config.window_x, config.window_y) { viewport = viewport.with_position([x, y]); }
    let native_options = eframe::NativeOptions { viewport, ..Default::default() };
    eframe::run_native("Isle", native_options, Box::new(move |cc| Ok(Box::new(ui::IsleApp::new(cc, cmd_tx, state_rx, config)))))
}
