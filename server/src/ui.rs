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

const TEXT_SIZE: f32 = 14.0;
const SECTION_SIZE: f32 = 16.0;
const GAP: f32 = 8.0;
const SECTION_GAP: f32 = 16.0;
const INSET: i8 = 16;
const ACTION_HEIGHT: f32 = 32.0;
const SETTINGS_ICON: char = '\u{f013}';
const TRASH_ICON: char = '\u{f014}';
const PHONE_ICON: char = '\u{f10b}';
const SERVER_ICON: char = '\u{f233}';
const PAIR_ICON: char = '\u{f023}';

fn symbol_family() -> FontFamily {
    FontFamily::Name("nerd-symbols".into())
}

fn section(ui: &mut egui::Ui, symbol: char, text: &str) {
    ui.horizontal(|ui| {
        ui.label(
            egui::RichText::new(symbol.to_string())
                .family(symbol_family())
                .size(SECTION_SIZE)
                .color(MAUVE),
        );
        ui.label(egui::RichText::new(text).size(SECTION_SIZE).color(MAUVE));
    });
}

fn action(
    ui: &mut egui::Ui,
    symbol: char,
    text: &str,
    color: Color32,
    enabled: bool,
) -> egui::Response {
    let mut label = egui::text::LayoutJob::default();
    label.append(
        &symbol.to_string(),
        0.0,
        egui::TextFormat::simple(egui::FontId::new(TEXT_SIZE, symbol_family()), color),
    );
    label.append(
        text,
        GAP,
        egui::TextFormat::simple(egui::FontId::proportional(TEXT_SIZE), color),
    );
    ui.add_enabled(
        enabled,
        egui::Button::new(label).min_size(egui::vec2(0.0, ACTION_HEIGHT)),
    )
}

fn device_row<R>(
    ui: &mut egui::Ui,
    content: impl FnOnce(&mut egui::Ui) -> R,
) -> egui::InnerResponse<R> {
    ui.scope(|ui| {
        ui.spacing_mut().interact_size.y = ACTION_HEIGHT;
        ui.horizontal_wrapped(content).inner
    })
}

fn surface(ui: &mut egui::Ui, content: impl FnOnce(&mut egui::Ui)) {
    egui::Frame::new()
        .fill(MANTLE)
        .corner_radius(8)
        .inner_margin(egui::Margin::same(INSET))
        .show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            content(ui);
        });
}

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
    phone: Option<Arc<crate::phone::Controller>>,
    phone_host: String,
    phone_port: String,
    phone_error: String,
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
            .show(ui, |ui| {
                egui::ScrollArea::vertical().show(ui, |ui| self.content(ui));
            });
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
            ui.set_max_width(380.0);
            section(ui, SETTINGS_ICON, "Listen Settings");
            ui.add_space(SECTION_GAP);
            ui.label("Listen address");
            ui.text_edit_singleline(&mut form.address);
            ui.add_space(GAP);
            ui.label("Port");
            ui.text_edit_singleline(&mut form.port);
            ui.add_space(GAP);
            ui.label("Use 0.0.0.0 for all IPv4 interfaces, or enter one LAN or Tailscale address.");
            if let Some(error) = &form.error {
                ui.colored_label(RED, error);
            }
            ui.add_space(SECTION_GAP);
            ui.horizontal(|ui| {
                apply = action(ui, '\u{f00c}', "Apply", MAUVE, true).clicked();
                cancel = action(ui, '\u{f00d}', "Cancel", TEXT, true).clicked();
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

    fn phone_controls(&mut self, ui: &mut egui::Ui) {
        let Some(phone) = &self.phone else {
            return;
        };
        let (message, busy, saved) = {
            let status = phone.status.lock().unwrap();
            (status.message, status.busy, status.saved)
        };
        surface(ui, |ui| {
            section(ui, SERVER_ICON, "Phone Configuration");
            ui.label(message);
            if !saved {
                ui.label("Phone address");
                ui.add_enabled(!busy, egui::TextEdit::singleline(&mut self.phone_host));
                ui.label("Port");
                ui.add_enabled(!busy, egui::TextEdit::singleline(&mut self.phone_port));
                if action(ui, SETTINGS_ICON, "Pair", MAUVE, !busy).clicked() {
                    let result = self
                        .phone_port
                        .trim()
                        .parse()
                        .map_err(|_| anyhow::anyhow!("Enter a valid port."))
                        .and_then(|port| phone.pair(&self.phone_host, port));
                    self.phone_error = result
                        .err()
                        .map_or_else(String::new, |error| error.to_string());
                }
            } else if action(ui, SETTINGS_ICON, "Remove Pairing", RED, true).clicked() {
                self.phone_error = phone
                    .forget()
                    .err()
                    .map_or_else(String::new, |error| error.to_string());
            }
            let mut status = phone.status.lock().unwrap();
            if let Some((number, _)) = &status.comparison {
                ui.label(format!("Compare this number with the phone: {number}"));
                let accept = action(ui, SETTINGS_ICON, "Numbers Match", MAUVE, true).clicked();
                let decline = action(ui, SETTINGS_ICON, "Cancel", RED, true).clicked();
                if (accept || decline)
                    && let Some((_, response)) = status.comparison.take()
                {
                    let _ = response.send(accept);
                }
            }
            if !self.phone_error.is_empty() {
                ui.colored_label(RED, &self.phone_error);
            }
        });
    }

    fn content(&mut self, ui: &mut egui::Ui) {
        let status_arc = Arc::clone(&self.status);
        let state_arc = Arc::clone(&self.state);
        let Ok(status) = status_arc.lock() else {
            return;
        };
        let Ok(mut state) = state_arc.lock() else {
            return;
        };
        ui.heading(egui::RichText::new("LocalBell").size(36.0).color(MAUVE));
        ui.add_space(SECTION_GAP);
        self.phone_controls(ui);
        ui.add_space(SECTION_GAP);
        surface(ui, |ui| {
            section(ui, SERVER_ICON, "Receiver");
            ui.add_space(GAP);
            ui.label(&status.message);
            if status.applying {
                ui.label("Applying listen settings…");
            }
            if let Some(error) = &status.error {
                ui.colored_label(RED, error);
            }
            ui.add_space(GAP);
            if action(
                ui,
                SETTINGS_ICON,
                "Listen Settings",
                MAUVE,
                !status.applying,
            )
            .clicked()
            {
                self.listen_form = Some(ListenForm {
                    address: status.settings.address.to_string(),
                    port: status.settings.port.to_string(),
                    error: None,
                });
            }
        });
        ui.add_space(SECTION_GAP);
        state.expire();
        if let Some(pair) = state.pairing.as_mut().filter(|p| p.comparison.is_some()) {
            pair.shown = true;
            section(ui, PAIR_ICON, "Pairing Request");
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
        ui.add_space(SECTION_GAP);
        let mut remove = None;
        surface(ui, |ui| {
            section(ui, PHONE_ICON, "Paired Devices");
            ui.add_space(GAP);
            if state.devices.is_empty() {
                ui.label("No devices paired.");
            }
            for device in &state.devices {
                device_row(ui, |ui| {
                    ui.label(&device.name);
                    if action(ui, TRASH_ICON, "Remove", RED, true).clicked() {
                        remove = Some(device.id.clone());
                    }
                });
            }
        });
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

pub fn run(
    state: Shared,
    status: Arc<Mutex<Network>>,
    configure: ListenUpdates,
    phone: Option<Arc<crate::phone::Controller>>,
) -> Result<()> {
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
            phone.clone(),
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
    phone: Option<Arc<crate::phone::Controller>>,
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
                "caskaydia".into(),
                egui::FontData::from_static(include_bytes!("../assets/CaskaydiaCove-UI.ttf"))
                    .into(),
            );
            fonts.font_data.insert(
                "nerd-symbols".into(),
                egui::FontData::from_static(include_bytes!("../assets/Symbols-Nerd-UI.ttf")).into(),
            );
            for family in [FontFamily::Proportional, FontFamily::Monospace] {
                fonts
                    .families
                    .entry(family)
                    .or_default()
                    .insert(0, "caskaydia".into());
            }
            fonts
                .families
                .insert(symbol_family(), vec!["nerd-symbols".into()]);
            cc.egui_ctx.set_fonts(fonts);
            cc.egui_ctx.style_mut_of(egui::Theme::Dark, |style| {
                for (role, font) in &mut style.text_styles {
                    font.size = if *role == egui::TextStyle::Heading {
                        SECTION_SIZE
                    } else {
                        TEXT_SIZE
                    };
                }
                style.spacing.button_padding = egui::vec2(12.0, 6.0);
                style.spacing.item_spacing = egui::vec2(GAP, GAP);
                style.spacing.window_margin = egui::Margin::same(INSET);
            });
            Ok(Box::new(App {
                state,
                status,
                window,
                error: String::new(),
                configure,
                listen_form: None,
                phone_host: phone
                    .as_ref()
                    .map_or_else(String::new, |value| value.host.clone()),
                phone_port: phone
                    .as_ref()
                    .map_or_else(|| "19420".to_owned(), |value| value.port.to_string()),
                phone_error: String::new(),
                phone,
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

#[test]
fn device_name_and_remove_share_a_vertical_center() {
    egui::__run_test_ui(|ui| {
        let (name, button) = device_row(ui, |ui| {
            (
                ui.label("Android phone"),
                ui.add(egui::Button::new("Remove").min_size(egui::vec2(0.0, ACTION_HEIGHT))),
            )
        })
        .inner;
        assert!((name.rect.center().y - button.rect.center().y).abs() <= 0.5);
    });
}
