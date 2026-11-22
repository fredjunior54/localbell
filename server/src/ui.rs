use crate::phone::Controller;
use anyhow::Result;
use eframe::egui::{self, Color32, FontFamily, Stroke, ViewportCommand};
use ksni::{MenuItem, blocking::TrayMethods, menu::StandardItem};
use std::{
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    time::Duration,
};

// Catppuccin Mocha (https://github.com/catppuccin/catppuccin).
const PROJECT_PAGE: &str = "https://github.com/fredjunior54/localbell";
const GREEN: Color32 = Color32::from_rgb(166, 227, 161);
const INFO_ICON: char = '\u{f05a}';
const STATUS_ICON: char = '\u{f080}';
const NETWORK_ICON: char = '\u{f0ac}';
const PORT_ICON: char = '\u{f023}';
const CONNECTED_ICON: char = '\u{f058}';
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

fn wordmark(prefix: &str, size: f32) -> egui::text::LayoutJob {
    let mut label = egui::text::LayoutJob::default();
    let font = egui::FontId::proportional(size);
    for (text, color) in [
        (prefix, MAUVE),
        ("Local", Color32::WHITE),
        ("Bell", Color32::from_rgb(137, 180, 250)),
    ] {
        label.append(text, 0.0, egui::TextFormat::simple(font.clone(), color));
    }
    label
}

fn section(ui: &mut egui::Ui, symbol: char, text: &str) {
    section_with(ui, symbol, |ui| {
        ui.label(egui::RichText::new(text).size(SECTION_SIZE).color(MAUVE));
    });
}

fn section_with(ui: &mut egui::Ui, symbol: char, title: impl FnOnce(&mut egui::Ui)) {
    ui.horizontal(|ui| {
        ui.label(
            egui::RichText::new(symbol.to_string())
                .family(symbol_family())
                .size(SECTION_SIZE)
                .color(MAUVE),
        );
        title(ui);
    });
}

fn action(
    ui: &mut egui::Ui,
    symbol: char,
    text: &str,
    color: Color32,
    enabled: bool,
) -> egui::Response {
    ui.add_enabled(
        enabled,
        egui::Button::new(action_label(symbol, text, color))
            .min_size(egui::vec2(0.0, ACTION_HEIGHT)),
    )
}

fn action_label(symbol: char, text: &str, color: Color32) -> egui::text::LayoutJob {
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
    label
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
const INSTANCE_INTERFACE: &str = "io.github.fredjunior54.LocalBell";
const INSTANCE_PATH: &str = "/io/github/fredjunior54/LocalBell";

struct Instance(Arc<Window>);

#[zbus::interface(name = "io.github.fredjunior54.LocalBell")]
impl Instance {
    fn show_window(&self) {
        self.0.visible.store(true, Ordering::Relaxed);
    }
}

pub async fn show_running_window() -> Result<()> {
    tokio::time::timeout(Duration::from_secs(2), async {
        let connection = zbus::Connection::session().await?;
        connection
            .call_method(
                Some(INSTANCE_INTERFACE),
                INSTANCE_PATH,
                Some(INSTANCE_INTERFACE),
                "ShowWindow",
                &(),
            )
            .await?;
        Ok::<(), zbus::Error>(())
    })
    .await??;
    Ok(())
}

async fn register_instance(window: Arc<Window>) -> zbus::Result<zbus::Connection> {
    zbus::connection::Builder::session()?
        .serve_at(INSTANCE_PATH, Instance(window))?
        .name(INSTANCE_INTERFACE)?
        .build()
        .await
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
    about_open: bool,
    about_artwork: Option<egui::TextureHandle>,
    project_open: Option<mpsc::Receiver<Result<()>>>,
    project_error: bool,
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
                    ui.horizontal(|ui| {
                        ui.label(wordmark("", 24.0));
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            if action(ui, INFO_ICON, "About", MAUVE, true).clicked() {
                                self.about_open = true;
                            }
                        });
                    });
                    ui.add_space(SECTION_GAP);
                    self.phone_controls(ui);
                    let connected = self.phone.status.lock().unwrap().connected;
                    if let Some(session) = connected {
                        ui.add_space(SECTION_GAP);
                        connected_status(ui, session);
                    }
                });
            });
        if self.phone.status.lock().unwrap().approval.is_some() {
            self.about_open = false;
            self.pairing_dialog(ui.ctx());
        } else if self.about_open {
            self.about_dialog(ui.ctx());
        }
    }
}

impl App {
    fn about_dialog(&mut self, ctx: &egui::Context) {
        if let Some(pending) = &self.project_open {
            match pending.try_recv() {
                Ok(result) => {
                    self.project_error = result.is_err();
                    if let Err(error) = result {
                        tracing::warn!(%error, "Could not open the LocalBell project page");
                    }
                    self.project_open = None;
                }
                Err(mpsc::TryRecvError::Disconnected) => {
                    self.project_open = None;
                    self.project_error = true;
                }
                Err(mpsc::TryRecvError::Empty) => (),
            }
        }
        let artwork = &mut self.about_artwork;
        let frame =
            egui::Frame::popup(&ctx.style_of(ctx.theme())).inner_margin(egui::Margin::same(INSET));
        let modal = egui::Modal::new(egui::Id::new("about-localbell")).frame(frame).show(ctx, |ui| {
            let available = ctx.content_rect().size();
            let width = (available.x - 2.0 * (SECTION_GAP + f32::from(INSET))).clamp(1.0, 360.0);
            ui.set_width(width);
            section_with(ui, INFO_ICON, |ui| {
                ui.label(wordmark("About ", SECTION_SIZE));
            });
            ui.add_space(GAP);
            egui::ScrollArea::vertical()
                .max_height((available.y - 120.0).max(1.0))
                .show(ui, |ui| {
                    if artwork.is_none()
                        && let Ok(icon) = eframe::icon_data::from_png_bytes(include_bytes!(
                            "../../assets/localbell.png"
                        ))
                    {
                        let image = egui::ColorImage::from_rgba_unmultiplied(
                            [icon.width as usize, icon.height as usize],
                            &icon.rgba,
                        );
                        *artwork = Some(ctx.load_texture(
                            "localbell-about",
                            image,
                            egui::TextureOptions::LINEAR,
                        ));
                    }
                    if let Some(artwork) = artwork {
                        let width = ui.available_width().min(220.0);
                        let size = artwork.size_vec2();
                        ui.vertical_centered(|ui| {
                            ui.image((artwork.id(), egui::vec2(width, width * size.y / size.x)))
                        });
                    }
                    ui.add_space(GAP);
                    ui.vertical_centered(|ui| {
                        ui.label("Forward notifications from your Android phone to your Linux desktop.");
                        ui.label(concat!("Version ", env!("CARGO_PKG_VERSION")));
                        ui.label("Maintained by Fred Junior");
                    });
                    if self.project_error {
                        ui.vertical_centered(|ui| {
                            ui.colored_label(RED, "Could not open the default browser.");
                        });
                    }
                });
            ui.add_space(SECTION_GAP);
            let labels = [(NETWORK_ICON, "Project page", MAUVE), (CANCEL_ICON, "Close", TEXT)];
            let buttons_width = ui.fonts_mut(|fonts| {
                labels.iter().map(|&(icon, text, color)| fonts.layout_job(action_label(icon, text, color)).size().x).sum::<f32>()
            }) + 4.0 * ui.spacing().button_padding.x + ui.spacing().item_spacing.x;
            let mut buttons = |ui: &mut egui::Ui| {
                if action(ui, NETWORK_ICON, "Project page", MAUVE, self.project_open.is_none()).clicked() {
                    self.project_error = false;
                    let (sender, receiver) = mpsc::channel();
                    self.project_open = Some(receiver);
                    std::thread::spawn(move || {
                        let result = std::process::Command::new("xdg-open").arg(PROJECT_PAGE).status()
                            .map_err(anyhow::Error::from)
                            .and_then(|status| if status.success() { Ok(()) } else { anyhow::bail!("xdg-open exited with {status}") });
                        let _ = sender.send(result);
                    });
                }
                action(ui, CANCEL_ICON, "Close", TEXT, true).clicked()
            };
            ui.vertical_centered(|ui| {
                if buttons_width <= ui.available_width() {
                    ui.allocate_ui_with_layout(egui::vec2(buttons_width.ceil(), ACTION_HEIGHT), egui::Layout::left_to_right(egui::Align::Center), buttons).inner
                } else {
                    buttons(ui)
                }
            }).inner
        });
        self.about_open = !(modal.inner || modal.should_close());
    }

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
                ui.label(egui::RichText::new(number).size(24.0).strong().color(GREEN));
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
                phone_field(
                    ui,
                    "Phone IP address or hostname",
                    &mut self.phone_host,
                    "192.168.1.42",
                    !busy,
                );
                phone_field(ui, "Port", &mut self.phone_port, "", !busy);
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

fn phone_field(ui: &mut egui::Ui, label: &str, value: &mut String, hint: &str, enabled: bool) {
    let label = ui.label(label);
    ui.scope(|ui| {
        let visuals = ui.visuals_mut();
        visuals.widgets.inactive.bg_stroke = Stroke::new(1.0, OVERLAY);
        visuals.widgets.hovered.bg_stroke = Stroke::new(1.0, OVERLAY);
        visuals.selection.stroke = Stroke::new(1.0, MAUVE);
        ui.add_enabled(
            enabled,
            egui::TextEdit::singleline(value)
                .hint_text(egui::RichText::new(hint).color(Color32::from_rgb(166, 173, 200)))
                .background_color(SURFACE)
                .desired_width(ui.available_width())
                .margin(egui::Margin::same(GAP as i8)),
        )
        .labelled_by(label.id);
    });
}

fn connected_status(ui: &mut egui::Ui, session: crate::phone::Connected) {
    surface(ui, |ui| {
        section(ui, STATUS_ICON, "Status");
        ui.add_space(GAP);
        ui.horizontal(|ui| {
            detail_icon(ui, CONNECTED_ICON, GREEN);
            ui.colored_label(GREEN, "Connected");
        });
        ui.add_space(GAP);
        ui.label(
            egui::RichText::new(session.forwarded.to_string())
                .size(24.0)
                .strong(),
        );
        ui.label("Forwarded this session");
        ui.add_space(GAP);
        ui.horizontal_wrapped(|ui| {
            detail_icon(ui, NETWORK_ICON, TEXT);
            ui.label(format!("Phone IP address: {}", session.endpoint.ip()));
        });
        ui.horizontal_wrapped(|ui| {
            detail_icon(ui, PORT_ICON, TEXT);
            ui.label(format!("Port: {}", session.endpoint.port()));
        });
    });
}

fn detail_icon(ui: &mut egui::Ui, glyph: char, color: Color32) {
    ui.label(
        egui::RichText::new(glyph.to_string())
            .family(symbol_family())
            .color(color),
    );
}

pub fn run(
    phone: Arc<Controller>,
    start_minimized: bool,
    runtime: &tokio::runtime::Runtime,
) -> Result<()> {
    let window = Arc::new(Window::default());
    window.visible.store(!start_minimized, Ordering::Relaxed);
    let _instance = runtime
        .block_on(register_instance(Arc::clone(&window)))
        .inspect_err(|error| {
            tracing::debug!(%error, "Could not register the LocalBell window activation service");
        });
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
    let tray = match (Tray {
        window: Arc::clone(&window),
        icon: tray_icon,
    })
    .spawn()
    {
        Ok(tray) => Some(tray),
        Err(error) => {
            if start_minimized {
                tracing::warn!(%error, "System tray unavailable; opening the main window instead");
            }
            window.visible.store(true, Ordering::Relaxed);
            None
        }
    };
    while !window.quit.load(Ordering::Relaxed) {
        if window.visible.load(Ordering::Relaxed) {
            open_window(Arc::clone(&window), icon.clone(), Arc::clone(&phone))?;
            let Some(tray) = &tray else {
                break;
            };
            tray.update(|_| ());
        }
        while !window.visible.load(Ordering::Relaxed) && !window.quit.load(Ordering::Relaxed) {
            if phone.status.lock().unwrap().approval.is_some() {
                window.visible.store(true, Ordering::Relaxed);
                break;
            }
            std::thread::sleep(Duration::from_millis(100));
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
                about_open: false,
                about_artwork: None,
                project_open: None,
                project_error: false,
                phone,
            }))
        }),
    )
    .map_err(|e| anyhow::anyhow!("Open desktop window: {e}"))
}
