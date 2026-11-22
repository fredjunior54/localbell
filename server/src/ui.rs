use crate::phone::Controller;
use anyhow::Result;
use eframe::egui::{self, Color32, FontFamily, Stroke, ViewportCommand};
use ksni::{MenuItem, blocking::TrayMethods, menu::StandardItem};
use std::{
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
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
const CANCEL_ICON: char = '\u{f00d}';
const TRASH_ICON: char = '\u{f014}';
const PHONE_ICON: char = '\u{f10b}';
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

struct App {
    window: Arc<Window>,
    phone: Arc<Controller>,
    phone_host: String,
    phone_port: String,
    phone_error: String,
}

impl eframe::App for App {
    fn logic(&mut self, ctx: &egui::Context, _: &mut eframe::Frame) {
        ctx.request_repaint_after(Duration::from_millis(150));
        let close = ctx.input(|i| i.viewport().close_requested());
        if close {
            let mut status = self.phone.status.lock().unwrap();
            if let Some(response) = status.approval.take() {
                status.comparison = None;
                let _ = response.send(false);
            }
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
                egui::ScrollArea::vertical().show(ui, |ui| {
                    ui.heading(egui::RichText::new("LocalBell").size(24.0).color(MAUVE));
                    ui.add_space(SECTION_GAP);
                    self.phone_controls(ui);
                });
            });
        self.pairing_dialog(ui.ctx());
    }
}

impl App {
    fn pairing_dialog(&self, ctx: &egui::Context) {
        let mut status = self.phone.status.lock().unwrap();
        let Some(number) = status
            .comparison
            .as_ref()
            .filter(|_| status.approval.is_some())
        else {
            return;
        };
        let modal = egui::Modal::new(egui::Id::new("pairing-confirmation")).show(ctx, |ui| {
            ui.vertical_centered(|ui| {
                section(ui, PAIR_ICON, "Confirm pairing");
                ui.add_space(GAP);
                ui.label(
                    egui::RichText::new(number)
                        .size(24.0)
                        .strong()
                        .color(Color32::from_rgb(166, 227, 161)),
                );
                ui.add_space(GAP);
            });
            ui.horizontal(|ui| {
                if action(ui, PAIR_ICON, "Yes", MAUVE, true).clicked() {
                    return Some(true);
                }
                if action(ui, CANCEL_ICON, "No", TEXT, true).clicked() {
                    return Some(false);
                }
                None
            })
            .inner
        });
        if let Some(approved) = modal
            .inner
            .or_else(|| modal.should_close().then_some(false))
        {
            status.comparison = None;
            if let Some(response) = status.approval.take() {
                let _ = response.send(approved);
            }
        }
    }

    fn phone_controls(&mut self, ui: &mut egui::Ui) {
        let phone = &self.phone;
        let (message, busy, saved) = {
            let status = phone.status.lock().unwrap();
            (status.message, status.busy, status.saved)
        };
        surface(ui, |ui| {
            section(ui, PHONE_ICON, "Phone Configuration");
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
            } else if action(ui, TRASH_ICON, "Remove Pairing", RED, true).clicked() {
                self.phone_error = phone
                    .forget()
                    .err()
                    .map_or_else(String::new, |error| error.to_string());
            }
            let waiting = {
                let status = phone.status.lock().unwrap();
                status
                    .comparison
                    .clone()
                    .filter(|_| status.approval.is_none() && !status.saved)
            };
            if let Some(number) = waiting {
                ui.label(egui::RichText::new(number).size(24.0).strong());
                ui.label("Waiting for phone confirmation…");
            }
            if !self.phone_error.is_empty() {
                ui.colored_label(RED, &self.phone_error);
            }
        });
    }
}

pub fn run(phone: Arc<Controller>) -> Result<()> {
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
        open_window(Arc::clone(&window), icon.clone(), Arc::clone(&phone))?;
        if let Some(tray) = &tray {
            tray.update(|_| ());
        } else {
            break;
        }
        while !window.visible.load(Ordering::Relaxed) && !window.quit.load(Ordering::Relaxed) {
            if phone.status.lock().unwrap().approval.is_some() {
                window.visible.store(true, Ordering::Relaxed);
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

fn open_window(window: Arc<Window>, icon: egui::IconData, phone: Arc<Controller>) -> Result<()> {
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
                window,
                phone_host: phone.host.clone(),
                phone_port: phone.port.to_string(),
                phone_error: String::new(),
                phone,
            }))
        }),
    )
    .map_err(|e| anyhow::anyhow!("Open desktop window: {e}"))
}
