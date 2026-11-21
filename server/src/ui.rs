use crate::{Network, Shared};
use anyhow::Result;
use eframe::egui::{self, Color32, FontFamily, Stroke, ViewportCommand};
use ksni::{MenuItem, blocking::TrayMethods, menu::StandardItem};
use std::{
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};

const PANEL: Color32 = Color32::from_rgb(12, 15, 13);
const BG: Color32 = Color32::from_rgb(7, 9, 8);
const CARD: Color32 = Color32::from_rgb(18, 22, 19);
const ACCENT: Color32 = Color32::from_rgb(57, 255, 106);

#[derive(Default)]
struct Window {
    visible: AtomicBool,
    quit: AtomicBool,
}
struct Tray {
    window: Arc<Window>,
    icon: ksni::Icon,
}
impl ksni::Tray for Tray {
    fn id(&self) -> String {
        "localbell".into()
    }
    fn title(&self) -> String {
        "LocalBell".into()
    }
    fn icon_pixmap(&self) -> Vec<ksni::Icon> {
        vec![self.icon.clone()]
    }
    fn activate(&mut self, _: i32, _: i32) {
        self.window.visible.fetch_xor(true, Ordering::Relaxed);
    }
    fn menu(&self) -> Vec<MenuItem<Self>> {
        let visible = self.window.visible.load(Ordering::Relaxed);
        vec![
            StandardItem {
                label: "Show LocalBell".into(),
                visible: !visible,
                activate: Box::new(|t: &mut Self| t.window.visible.store(true, Ordering::Relaxed)),
                ..Default::default()
            }
            .into(),
            StandardItem {
                label: "Hide LocalBell".into(),
                visible,
                activate: Box::new(|t: &mut Self| t.window.visible.store(false, Ordering::Relaxed)),
                ..Default::default()
            }
            .into(),
            MenuItem::Separator,
            StandardItem {
                label: "Quit".into(),
                activate: Box::new(|t: &mut Self| t.window.quit.store(true, Ordering::Relaxed)),
                ..Default::default()
            }
            .into(),
        ]
    }
}

struct App {
    state: Shared,
    status: Arc<Mutex<Network>>,
    window: Arc<Window>,
    error: String,
}
impl eframe::App for App {
    fn logic(&mut self, ctx: &egui::Context, _: &mut eframe::Frame) {
        ctx.request_repaint_after(Duration::from_millis(150));
        let close = ctx.input(|i| i.viewport().close_requested());
        if close {
            self.window.visible.store(false, Ordering::Relaxed);
        }
        if !close
            && (!self.window.visible.load(Ordering::Relaxed)
                || self.window.quit.load(Ordering::Relaxed))
        {
            ctx.send_viewport_cmd(ViewportCommand::Close);
        }
    }
    fn ui(&mut self, ui: &mut egui::Ui, _: &mut eframe::Frame) {
        let Ok(status) = self.status.lock() else {
            return;
        };
        let Ok(mut state) = self.state.lock() else {
            return;
        };
        ui.heading(
            egui::RichText::new("LocalBell")
                .family(FontFamily::Name("display".into()))
                .size(36.0)
                .color(ACCENT),
        );
        ui.label(&status.message);
        ui.separator();
        state.expire();
        if let Some(pair) = state.pairing.as_mut() {
            pair.shown = true;
            ui.heading("Pairing request");
            ui.label(&pair.name);
            ui.label("Accept only if all ten words match the phone, in order.");
            ui.label(
                egui::RichText::new(phrase(&status.fingerprint, &pair.code, &pair.nonce))
                    .monospace(),
            );
            ui.label(format!(
                "{}s remaining",
                pair.until
                    .saturating_duration_since(Instant::now())
                    .as_secs()
            ));
            let mut reject = false;
            ui.horizontal(|ui| {
                reject = ui.button("Reject").clicked();
                if ui
                    .add_enabled(!pair.approved, egui::Button::new("Accept"))
                    .clicked()
                {
                    pair.approved = true;
                }
            });
            if pair.approved {
                ui.label("Waiting for the phone to finish pairing…");
            }
            if reject {
                state.pairing = None;
            }
        } else if state.devices.is_empty() {
            ui.label("Enter the listening address on your phone to pair.");
        }
        ui.separator();
        ui.heading("Paired devices");
        if state.devices.is_empty() {
            ui.label("No devices paired.");
        }
        let mut remove = None;
        for device in &state.devices {
            ui.horizontal(|ui| {
                ui.label(&device.name);
                if ui.button("Remove").clicked() {
                    remove = Some(device.id.clone());
                }
            });
        }
        if let Some(id) = remove {
            self.error = state
                .revoke(&id)
                .err()
                .map_or_else(String::new, |e| e.to_string());
        }
        if !self.error.is_empty() {
            ui.colored_label(Color32::LIGHT_RED, &self.error);
        }
    }
}

pub fn run(state: Shared, status: Arc<Mutex<Network>>) -> Result<()> {
    let window = Arc::new(Window::default());
    window.visible.store(true, Ordering::Relaxed);
    let icon = eframe::icon_data::from_png_bytes(include_bytes!("../assets/localbell-icon.png"))?;
    let tray_icon = ksni::Icon {
        width: icon.width as i32,
        height: icon.height as i32,
        data: icon
            .rgba
            .as_chunks::<4>()
            .0
            .iter()
            .flat_map(|p| [p[3], p[0], p[1], p[2]])
            .collect(),
    };
    let tray = (Tray {
        window: Arc::clone(&window),
        icon: tray_icon,
    })
    .spawn()
    .ok();
    loop {
        open_window(
            Arc::clone(&state),
            Arc::clone(&status),
            Arc::clone(&window),
            icon.clone(),
        )?;
        if let Some(tray) = &tray {
            tray.update(|_| ());
        } else {
            break;
        }
        while !window.visible.load(Ordering::Relaxed) && !window.quit.load(Ordering::Relaxed) {
            if let Ok(mut state) = state.lock() {
                state.expire();
                if state.pairing.as_ref().is_some_and(|p| !p.shown) {
                    window.visible.store(true, Ordering::Relaxed);
                }
            }
            std::thread::sleep(Duration::from_millis(100));
        }
        if window.quit.load(Ordering::Relaxed) {
            break;
        }
        if let Some(tray) = &tray {
            tray.update(|_| ());
        }
    }
    if let Some(tray) = tray {
        tray.shutdown();
    }
    Ok(())
}

fn open_window(
    state: Shared,
    status: Arc<Mutex<Network>>,
    window: Arc<Window>,
    icon: egui::IconData,
) -> Result<()> {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_app_id("localbell")
            .with_inner_size([520.0, 620.0])
            .with_icon(icon),
        ..Default::default()
    };
    eframe::run_native(
        "LocalBell",
        options,
        Box::new(move |cc| {
            let mut visuals = egui::Visuals::dark();
            visuals.panel_fill = PANEL;
            visuals.window_fill = PANEL;
            visuals.extreme_bg_color = BG;
            visuals.widgets.inactive.bg_fill = CARD;
            visuals.widgets.inactive.weak_bg_fill = CARD;
            visuals.widgets.hovered.bg_stroke = Stroke::new(1.0, ACCENT.gamma_multiply(0.6));
            visuals.widgets.active.bg_stroke = Stroke::new(1.0, ACCENT);
            visuals.selection.bg_fill = ACCENT.gamma_multiply(0.35);
            cc.egui_ctx.set_theme(egui::Theme::Dark);
            cc.egui_ctx.set_visuals_of(egui::Theme::Dark, visuals);
            let mut fonts = egui::FontDefinitions::default();
            fonts.font_data.insert(
                "rajdhani".into(),
                egui::FontData::from_static(include_bytes!("../assets/Rajdhani-Bold.ttf")).into(),
            );
            fonts
                .families
                .insert(FontFamily::Name("display".into()), vec!["rajdhani".into()]);
            cc.egui_ctx.set_fonts(fonts);
            Ok(Box::new(App {
                state,
                status,
                window,
                error: String::new(),
            }))
        }),
    )
    .map_err(|e| anyhow::anyhow!("Open desktop window: {e}"))
}

fn phrase(fingerprint: &str, secret: &str, nonce: &str) -> String {
    const WORDS: [&str; 64] = [
        "acorn", "amber", "apple", "arrow", "atlas", "basil", "beach", "birch", "bloom", "blue",
        "brick", "brook", "cedar", "chalk", "charm", "cherry", "cloud", "coast", "comet", "coral",
        "crane", "creek", "crown", "daisy", "dawn", "dove", "drift", "dune", "eagle", "earth",
        "fern", "field", "flame", "flute", "frost", "grape", "green", "grove", "harbor", "hazel",
        "honey", "iris", "ivy", "jade", "lake", "leaf", "lemon", "lily", "maple", "mint", "moon",
        "moss", "north", "oak", "ocean", "olive", "pearl", "pine", "plum", "rain", "reed", "river",
        "rose", "sage",
    ];
    use sha2::{Digest, Sha256};
    Sha256::digest(format!("localbell-pair-v2:{fingerprint}:{secret}:{nonce}").as_bytes())[..10]
        .iter()
        .map(|byte| WORDS[usize::from(byte & 63)])
        .collect::<Vec<_>>()
        .join(" ")
}

#[cfg(test)]
#[test]
fn comparison_vector_binds_leaf() {
    let code = "1".repeat(64);
    let nonce = "2".repeat(64);
    let expected = "eagle creek iris rose pine moon reed mint grove basil";
    assert_eq!(phrase(&"0".repeat(64), &code, &nonce), expected);
    assert_ne!(phrase(&"3".repeat(64), &code, &nonce), expected);
}
