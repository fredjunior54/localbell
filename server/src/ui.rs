use crate::{ListenUpdates, Network, Shared, listener};
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

// Catppuccin Mocha (https://github.com/catppuccin/catppuccin).
const BASE: Color32 = Color32::from_rgb(30, 30, 46);
const MANTLE: Color32 = Color32::from_rgb(24, 24, 37);
const SURFACE: Color32 = Color32::from_rgb(49, 50, 68);
const OVERLAY: Color32 = Color32::from_rgb(108, 112, 134);
const TEXT: Color32 = Color32::from_rgb(205, 214, 244);
const MAUVE: Color32 = Color32::from_rgb(203, 166, 247);
const RED: Color32 = Color32::from_rgb(243, 139, 168);

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

struct ListenForm {
    address: String,
    port: String,
    error: Option<String>,
}

impl ListenForm {
    fn validate(&self) -> Result<listener::Settings> {
        listener::Settings {
            address: self
                .address
                .trim()
                .parse()
                .map_err(|_| anyhow::anyhow!("Enter a valid IPv4 address."))?,
            port: self
                .port
                .trim()
                .parse()
                .map_err(|_| anyhow::anyhow!("Port must be between 1 and 65535."))?,
        }
        .validate()
    }
}

struct App {
    state: Shared,
    status: Arc<Mutex<Network>>,
    window: Arc<Window>,
    error: String,
    configure: ListenUpdates,
    listen_form: Option<ListenForm>,
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
    fn clear_color(&self, _: &egui::Visuals) -> [f32; 4] {
        BASE.to_normalized_gamma_f32()
    }

    fn ui(&mut self, ui: &mut egui::Ui, _: &mut eframe::Frame) {
        egui::CentralPanel::no_frame()
            .frame(
                egui::Frame::new()
                    .fill(BASE)
                    .inner_margin(egui::Margin::same(20)),
            )
            .show(ui, |ui| self.content(ui));
        self.listen_dialog(ui.ctx());
    }
}

impl App {
    fn listen_dialog(&mut self, ctx: &egui::Context) {
        let Some(mut form) = self.listen_form.take() else {
            return;
        };
        let mut apply = false;
        let mut cancel = false;
        let response = egui::Modal::new(egui::Id::new("listen-settings")).show(ctx, |ui| {
            ui.heading("Listen settings");
            ui.label("Listen address");
            ui.text_edit_singleline(&mut form.address);
            ui.label("Port");
            ui.text_edit_singleline(&mut form.port);
            ui.label("Use 0.0.0.0 for all IPv4 interfaces, or enter one LAN or Tailscale address.");
            if let Some(error) = &form.error {
                ui.colored_label(RED, error);
            }
            ui.horizontal(|ui| {
                apply = ui.button("Apply").clicked();
                cancel = ui.button("Cancel").clicked();
            });
        });
        if cancel || response.should_close() {
            return;
        }
        if apply {
            let result = form.validate().and_then(|settings| {
                let mut status = self
                    .status
                    .lock()
                    .map_err(|_| anyhow::anyhow!("Receiver status is unavailable."))?;
                self.configure.try_send(settings).map_err(|_| {
                    anyhow::anyhow!("Receiver is unavailable or already applying settings.")
                })?;
                status.applying = true;
                status.error = None;
                Ok(())
            });
            match result {
                Ok(()) => return,
                Err(error) => form.error = Some(error.to_string()),
            }
        }
        self.listen_form = Some(form);
    }

    fn content(&mut self, ui: &mut egui::Ui) {
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
                .color(MAUVE),
        );
        ui.label(&status.message);
        if status.applying {
            ui.label("Applying listen settings…");
        }
        if let Some(error) = &status.error {
            ui.colored_label(RED, error);
        }
        if ui
            .add_enabled(!status.applying, egui::Button::new("Listen settings"))
            .clicked()
        {
            self.listen_form = Some(ListenForm {
                address: status.settings.address.to_string(),
                port: status.settings.port.to_string(),
                error: None,
            });
        }
        ui.separator();
        state.expire();
        if let Some(pair) = state.pairing.as_mut().filter(|p| p.comparison.is_some()) {
            pair.shown = true;
            ui.heading("Pairing request");
            ui.label(&pair.name);
            ui.label("Accept only if the six-digit number matches the phone.");
            ui.label(
                egui::RichText::new(pair.comparison.as_deref().unwrap_or_default()).monospace(),
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
            ui.colored_label(RED, &self.error);
        }
    }
}

pub fn run(state: Shared, status: Arc<Mutex<Network>>, configure: ListenUpdates) -> Result<()> {
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
            configure.clone(),
        )?;
        if let Some(tray) = &tray {
            tray.update(|_| ());
        } else {
            break;
        }
        while !window.visible.load(Ordering::Relaxed) && !window.quit.load(Ordering::Relaxed) {
            if let Ok(mut state) = state.lock() {
                state.expire();
                if state
                    .pairing
                    .as_ref()
                    .is_some_and(|p| !p.shown && p.comparison.is_some())
                {
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
    configure: ListenUpdates,
) -> Result<()> {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_app_id("localbell")
            .with_transparent(false)
            .with_inner_size([520.0, 620.0])
            .with_icon(icon),
        ..Default::default()
    };
    eframe::run_native(
        "LocalBell",
        options,
        Box::new(move |cc| {
            let mut visuals = egui::Visuals::dark();
            visuals.panel_fill = BASE;
            visuals.window_fill = BASE;
            visuals.extreme_bg_color = MANTLE;
            visuals.override_text_color = Some(TEXT);
            visuals.hyperlink_color = MAUVE;
            visuals.error_fg_color = RED;
            visuals.widgets.noninteractive.bg_stroke = Stroke::new(1.0, OVERLAY);
            visuals.widgets.inactive.bg_fill = SURFACE;
            visuals.widgets.inactive.weak_bg_fill = SURFACE;
            visuals.widgets.hovered.bg_fill = SURFACE;
            visuals.widgets.hovered.weak_bg_fill = SURFACE;
            visuals.widgets.hovered.bg_stroke = Stroke::new(1.0, MAUVE);
            visuals.widgets.active.bg_stroke = Stroke::new(1.0, MAUVE);
            visuals.selection.bg_fill = SURFACE;
            visuals.selection.stroke = Stroke::new(1.0, MAUVE);
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
                configure,
                listen_form: None,
            }))
        }),
    )
    .map_err(|e| anyhow::anyhow!("Open desktop window: {e}"))
}

#[cfg(test)]
#[test]
fn comparison_vector_preserves_leading_zero_and_binds_leaf() {
    let (code, nonce, challenge) = (
        "1".repeat(64),
        "2".repeat(64),
        "0000000000000000000000000000000000000000000000000000000000000000",
    );
    assert_eq!(
        crate::state::commitment(&"0".repeat(64), &code, challenge),
        "e4a52592cf444899d4d5a8dd5b0fa2964e6908a323b8b43720ffb2dcffdce2d0"
    );
    assert_eq!(
        crate::state::comparison(&"0".repeat(64), &code, &nonce, challenge),
        "011659"
    );
    assert_ne!(
        crate::state::comparison(&"3".repeat(64), &code, &nonce, challenge),
        "011659"
    );
}
