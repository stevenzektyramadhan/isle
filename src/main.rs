// Release builds are GUI-only: without this, Windows opens a console window
// next to the island whenever isle.exe is double-clicked. Debug builds keep the
// console attached so logs stay visible.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

pub mod audio;
pub mod config;
pub mod extractor;
pub mod media_keys;
pub mod models;
pub mod ui;
pub mod updater;

fn main() -> eframe::Result<()> {
    #[cfg(windows)]
    unsafe {
        std::env::set_var("WGPU_DX12_PRESENTATION_SYSTEM", "Visual");
    }
    let config = config::load_config();
    let (cmd_tx, state_rx) = audio::spawn_audio_worker(config.clone());
    let mut viewport = eframe::egui::ViewportBuilder::default()
        .with_decorations(false)
        .with_transparent(true)
        .with_always_on_top()
        .with_icon(eframe::egui::IconData {
            rgba: include_bytes!("../assets/isle_64.rgba").to_vec(),
            width: 64,
            height: 64,
        })
        .with_inner_size([ui::PILL_WIDTH, ui::PILL_HEIGHT])
        .with_resizable(false);
    if let (Some(x), Some(y)) = (config.window_x, config.window_y) {
        viewport = viewport.with_position([x, y]);
    }
    let native_options = eframe::NativeOptions {
        viewport,
        renderer: eframe::Renderer::Glow,
        ..Default::default()
    };
    eframe::run_native(
        "Isle",
        native_options,
        Box::new(move |cc| Ok(Box::new(ui::IsleApp::new(cc, cmd_tx, state_rx, config)))),
    )
}
