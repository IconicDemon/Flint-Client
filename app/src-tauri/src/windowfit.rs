//! The main window looks the same on every screen. Flint's interface is laid out for a 1440 × 900 window (in logical
//! pixels); a bigger window zooms the whole interface up evenly, by the smaller of its width and height ratios, so a
//! maximized window on a 1920 × 1080 monitor and on a 2560 × 1440 one at 125% shows the same layout, only larger and
//! sharper. A window at or below the design size isn't zoomed out: the pages already fit down to the minimum size.
//! Extra width beyond that (a wide or ultrawide screen) is spread evenly on both sides of the pages (CSS
//! `--page-max`), never left as a gap on the right.
//!
//! On start the window takes a size that fits its monitor (`default_size`) and is centred there, instead of one
//! fixed size that is large on a laptop and small on a big monitor. It is shown only after that, so it never jumps.

use tauri::{LogicalSize, WebviewWindow, WindowEvent};

const DESIGN_WIDTH: f64 = 1440.0;
const DESIGN_HEIGHT: f64 = 900.0;
const MIN_WIDTH: f64 = 1080.0;
const MIN_HEIGHT: f64 = 660.0;
/// The share of the screen's free width a new window takes, at 16:10.
const SCREEN_SHARE: f64 = 0.78;
/// Its height stays within this share of the free height (room for the window's own edge and shadow).
const HEIGHT_SHARE: f64 = 0.9;
const RATIO: f64 = 16.0 / 10.0;
const MAX_ZOOM: f64 = 2.0;
/// Zoom moves in steps this big, so dragging a window's edge doesn't re-zoom the page on every pixel.
const ZOOM_STEP: f64 = 0.02;

/// A new window's size (logical pixels) for a monitor whose free area (without the taskbar or Dock) is `work_width`
/// × `work_height` logical pixels: 78% of the width at 16:10, at most 90% of the height, never below Flint's minimum
/// and never larger than the screen.
pub fn default_size(work_width: f64, work_height: f64) -> (f64, f64) {
    let mut width = (work_width * SCREEN_SHARE).max(MIN_WIDTH);
    let mut height = width / RATIO;
    let tallest = (work_height * HEIGHT_SHARE).max(MIN_HEIGHT);
    if height > tallest {
        height = tallest;
        width = (height * RATIO).max(MIN_WIDTH);
    }
    (width.min(work_width).round(), height.min(work_height).max(MIN_HEIGHT.min(work_height)).round())
}

/// The interface zoom for a window of `width` × `height` logical pixels: how many times larger than the design size
/// it is, by its tighter side, from 1 (the design size and smaller) to 2, in `ZOOM_STEP`s.
pub fn zoom_for(width: f64, height: f64) -> f64 {
    if !(width > 0.0 && height > 0.0) {
        return 1.0;
    }
    let raw = (width / DESIGN_WIDTH).min(height / DESIGN_HEIGHT).clamp(1.0, MAX_ZOOM);
    ((raw / ZOOM_STEP).floor() * ZOOM_STEP * 100.0).round() / 100.0
}

/// The window's size in logical pixels.
fn logical_size(window: &WebviewWindow) -> Option<(f64, f64)> {
    let scale = window.scale_factor().ok()?;
    let size = window.inner_size().ok()?;
    Some((size.width as f64 / scale, size.height as f64 / scale))
}

fn apply_zoom(window: &WebviewWindow, current: &std::sync::Mutex<f64>) {
    let Some((width, height)) = logical_size(window) else { return };
    let zoom = zoom_for(width, height);
    let mut last = current.lock().unwrap_or_else(|e| e.into_inner());
    if (zoom - *last).abs() >= ZOOM_STEP / 2.0 && window.set_zoom(zoom).is_ok() {
        *last = zoom;
    }
}

/// Sizes the main window for its monitor, centres and shows it, and keeps the interface's zoom in step with the
/// window's size from then on (resizing, maximizing, moving to a screen with another scale).
pub fn start(window: WebviewWindow, show: bool) {
    if let Some(monitor) = window.current_monitor().ok().flatten().or_else(|| window.primary_monitor().ok().flatten()) {
        let scale = monitor.scale_factor();
        let work = monitor.work_area();
        let (width, height) = default_size(work.size.width as f64 / scale, work.size.height as f64 / scale);
        let _ = window.set_size(LogicalSize::new(width, height));
        let _ = window.center();
    }
    let current = std::sync::Arc::new(std::sync::Mutex::new(1.0));
    apply_zoom(&window, &current);
    let follower = window.clone();
    window.on_window_event(move |event| {
        if matches!(event, WindowEvent::Resized(_) | WindowEvent::ScaleFactorChanged { .. }) {
            apply_zoom(&follower, &current);
        }
    });
    if show {
        let _ = window.show();
        let _ = window.set_focus();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_new_window_fits_its_monitor() {
        // 1920 × 1080 at 100%, taskbar 48 px: 78% of the width at 16:10, within the height.
        assert_eq!(default_size(1920.0, 1032.0), (1486.0, 929.0));
        // 2560 × 1440 at 125% (2048 × 1152 logical, taskbar 38 logical px): the same share of a larger screen.
        assert_eq!(default_size(2048.0, 1114.0), (1597.0, 998.0));
        // A small laptop (1366 × 728 free): Flint's minimum, which still fits.
        assert_eq!(default_size(1366.0, 728.0), (1080.0, 660.0));
        // A screen smaller than the minimum: the window never outgrows it.
        let (w, h) = default_size(1024.0, 600.0);
        assert!(w <= 1024.0 && h <= 600.0, "{w}×{h}");
    }

    #[test]
    fn the_interface_zooms_with_the_window_by_its_tighter_side() {
        assert_eq!(zoom_for(1440.0, 900.0), 1.0);
        // Smaller than the design size: never zoomed out.
        assert_eq!(zoom_for(1080.0, 660.0), 1.0);
        // Maximized on 1920 × 1080 (1920 × 1032 free) and on 2560 × 1440 at 125% (2048 × 1114 free): both lay out about
        // 900 logical pixels tall, so the same page shows the same way on both screens.
        let a = zoom_for(1920.0, 1032.0);
        let b = zoom_for(2048.0, 1114.0);
        assert!((1032.0 / a - 900.0).abs() < 20.0 && (1114.0 / b - 900.0).abs() < 20.0, "{a} {b}");
        // An ultrawide screen is limited by its height; a 4K screen at 100% stops at 2.
        assert_eq!(zoom_for(3440.0, 1392.0), zoom_for(2600.0, 1392.0));
        assert_eq!(zoom_for(3840.0, 2112.0), 2.0);
        // Steps, so a drag doesn't re-zoom on every pixel.
        assert_eq!(zoom_for(1500.0, 950.0), zoom_for(1501.0, 951.0));
        assert_eq!(zoom_for(0.0, 0.0), 1.0);
    }
}
