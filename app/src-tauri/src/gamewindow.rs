//! What happens to the windows when Minecraft starts: Flint steps aside (minimized, hidden to the tray, or
//! left open, as Settings → General asks) and the game opens maximized (windowed, filling the work area;
//! never exclusive fullscreen) unless the player set Minecraft's own fullscreen option.
//!
//! The game's window is found from outside, by its process, so it works the same on every Minecraft
//! version (GLFW on 1.21.x and 26.1/26.2, SDL3 on 26.3, LWJGL 2 on 1.8.9) without anything in the game.
//! On Windows Flint waits for the game's window to appear and then acts, so the player never looks at an
//! empty desktop while Minecraft loads; elsewhere Flint steps aside as soon as the game starts.

use crate::core::*;
use crate::data::load_settings;
use serde_json::Value;
use std::path::Path;
use tauri::{AppHandle, Manager};

/// How long Flint waits for the game's window before leaving the windows as they are (Windows waits for it; elsewhere
/// Flint steps aside at once).
#[cfg(windows)]
pub const WINDOW_WAIT: std::time::Duration = std::time::Duration::from_secs(60);
const TRAY_ID: &str = "flint-game";

/// Settings → General → "When the game starts".
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum OnStart {
    Minimize,
    KeepOpen,
    Tray,
}

impl OnStart {
    /// Minimize unless the player chose otherwise (also for settings saved before the choice existed).
    pub fn from_settings(settings: &Value) -> OnStart {
        match settings["gameStartBehavior"].as_str() {
            Some("keep") => OnStart::KeepOpen,
            Some("tray") if tray_supported() => OnStart::Tray,
            _ => OnStart::Minimize,
        }
    }
}

/// The tray (Windows' notification area, the menu bar, a Linux status area) is where a hidden Flint waits.
pub fn tray_supported() -> bool {
    cfg!(any(windows, target_os = "macos", target_os = "linux"))
}

/// Settings → General → "Open the game maximized" (on unless turned off), unless Minecraft's own options
/// ask for fullscreen: then the game's choice stands.
pub fn should_maximize(settings: &Value, game_dir: &Path) -> bool {
    settings["gameMaximized"].as_bool().unwrap_or(true) && !wants_fullscreen(&std::fs::read(game_dir.join("options.txt")).unwrap_or_default())
}

/// Whether options.txt has `fullscreen:true` (the same key on 1.8.9 and on every newer version).
pub fn wants_fullscreen(options: &[u8]) -> bool {
    String::from_utf8_lossy(options).lines().any(|line| line.trim_end_matches('\r').split_once(':').is_some_and(|(key, value)| key == "fullscreen" && value.trim() == "true"))
}

/// What the launch decided, carried to the game's exit.
#[derive(Clone, Copy, Debug, Default)]
pub struct SteppedAside {
    pub minimized: bool,
    pub hidden: bool,
}

/// Steps Flint aside as the game starts: on Windows once the game's window shows (maximizing it first when
/// asked), elsewhere right away. Returns at once; the work happens on its own thread.
pub fn on_game_started(app: &AppHandle, pid: Option<u32>, on_start: OnStart, maximize: bool, done: impl FnOnce(SteppedAside) + Send + 'static) {
    let app = app.clone();
    #[cfg(windows)]
    if let Some(pid) = pid {
        std::thread::spawn(move || {
            let found = windows::wait_for_game_window(pid, WINDOW_WAIT);
            if let Some(hwnd) = found {
                if maximize {
                    windows::maximize(hwnd);
                }
            }
            // A game that never showed a window (it failed while loading) leaves Flint where it is.
            done(if found.is_some() { step_aside(&app, on_start) } else { SteppedAside::default() });
        });
        return;
    }
    let _ = (pid, maximize);
    done(step_aside(&app, on_start));
}

fn step_aside(app: &AppHandle, on_start: OnStart) -> SteppedAside {
    let Some(window) = app.get_webview_window("main") else { return SteppedAside::default() };
    match on_start {
        OnStart::KeepOpen => SteppedAside::default(),
        OnStart::Minimize => SteppedAside { minimized: window.minimize().is_ok(), hidden: false },
        OnStart::Tray => {
            if show_tray(app) && window.hide().is_ok() {
                SteppedAside { minimized: false, hidden: true }
            } else {
                SteppedAside { minimized: window.minimize().is_ok(), hidden: false }
            }
        }
    }
}

/// Flint's icon in the tray while it is hidden; a click brings Flint back.
fn show_tray(app: &AppHandle) -> bool {
    use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
    if app.tray_by_id(TRAY_ID).is_some() {
        return true;
    }
    let Some(icon) = app.default_window_icon().cloned() else { return false };
    TrayIconBuilder::with_id(TRAY_ID)
        .icon(icon)
        .tooltip("Flint Client: Minecraft is running. Click to open Flint.")
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = event {
                bring_back(tray.app_handle());
            }
        })
        .build(app)
        .is_ok()
}

/// Brings Flint back to the front (and takes its tray icon away).
pub fn bring_back(app: &AppHandle) {
    let _ = app.remove_tray_by_id(TRAY_ID);
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

/// Whether Flint is hidden in the tray right now (a hidden Flint must come back when its game ends).
pub fn hidden_in_tray(app: &AppHandle) -> bool {
    app.tray_by_id(TRAY_ID).is_some() && app.get_webview_window("main").is_some_and(|w| !w.is_visible().unwrap_or(true))
}

/// The settings for this launch, read once.
pub fn launch_choices(state: &AppState, game_dir: &Path) -> (OnStart, bool) {
    let settings = load_settings(state);
    (OnStart::from_settings(&settings), should_maximize(&settings, game_dir))
}

/// For the self-test: the Minecraft windows on screen (class, maximized) and whether Flint is minimized or
/// hidden, as the launch left them.
pub fn report(app: &AppHandle) -> Value {
    #[cfg(windows)]
    let games: Vec<Value> = windows::game_windows_now().into_iter().map(|(class, zoomed)| serde_json::json!({ "class": class, "maximized": zoomed })).collect();
    #[cfg(not(windows))]
    let games: Vec<Value> = Vec::new();
    let window = app.get_webview_window("main");
    serde_json::json!({
        "gameWindows": games,
        "flintMinimized": window.as_ref().and_then(|w| w.is_minimized().ok()),
        "flintVisible": window.as_ref().and_then(|w| w.is_visible().ok()),
    })
}

#[cfg(windows)]
mod windows {
    use std::time::{Duration, Instant};
    use windows_sys::Win32::Foundation::{HWND, LPARAM, RECT};
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        EnumWindows, GetClassNameW, GetWindow, GetWindowLongW, GetWindowRect, GetWindowThreadProcessId, IsIconic, IsWindowVisible, IsZoomed, ShowWindow, GWL_EXSTYLE, GW_OWNER, SW_MAXIMIZE,
        WS_EX_TOOLWINDOW,
    };

    /// The window classes Minecraft's own window has: GLFW (1.21.x, 26.1, 26.2), SDL3 (26.3 and later) and
    /// LWJGL 2 (1.8.9). A loader's error dialog (Swing) or any other window the process opens is never
    /// taken for the game.
    const GAME_CLASSES: &[&str] = &["GLFW30", "SDL_app", "LWJGL"];

    struct Search {
        pid: u32,
        found: HWND,
    }

    fn class_of(hwnd: HWND) -> String {
        let mut buffer = [0u16; 64];
        // SAFETY: the buffer's length is passed with it.
        let len = unsafe { GetClassNameW(hwnd, buffer.as_mut_ptr(), buffer.len() as i32) };
        String::from_utf16_lossy(&buffer[..len.max(0) as usize])
    }

    unsafe extern "system" fn visit(hwnd: HWND, data: LPARAM) -> i32 {
        // SAFETY: `data` is the `Search` handed to EnumWindows below, alive for the whole call.
        let search = unsafe { &mut *(data as *mut Search) };
        let mut pid = 0u32;
        unsafe { GetWindowThreadProcessId(hwnd, &mut pid) };
        if pid != search.pid || unsafe { IsWindowVisible(hwnd) } == 0 || !unsafe { GetWindow(hwnd, GW_OWNER) }.is_null() {
            return 1;
        }
        if unsafe { GetWindowLongW(hwnd, GWL_EXSTYLE) } as u32 & WS_EX_TOOLWINDOW != 0 {
            return 1;
        }
        let mut rect: RECT = unsafe { std::mem::zeroed() };
        let sized = unsafe { GetWindowRect(hwnd, &mut rect) } != 0 && rect.right > rect.left && rect.bottom > rect.top;
        if sized && GAME_CLASSES.contains(&class_of(hwnd).as_str()) {
            search.found = hwnd;
            return 0;
        }
        1
    }

    /// The game's top-level window, once it is visible.
    fn game_window(pid: u32) -> Option<HWND> {
        let mut search = Search { pid, found: std::ptr::null_mut() };
        // SAFETY: `visit` only reads `search` through the pointer while EnumWindows runs.
        unsafe { EnumWindows(Some(visit), &mut search as *mut Search as LPARAM) };
        (!search.found.is_null()).then_some(search.found)
    }

    unsafe extern "system" fn collect(hwnd: HWND, data: LPARAM) -> i32 {
        // SAFETY: `data` is the list handed to EnumWindows in `game_windows_now`, alive for the whole call.
        let list = unsafe { &mut *(data as *mut Vec<(String, bool)>) };
        let class = class_of(hwnd);
        if unsafe { IsWindowVisible(hwnd) } != 0 && GAME_CLASSES.contains(&class.as_str()) {
            list.push((class, unsafe { IsZoomed(hwnd) } != 0));
        }
        1
    }

    /// Every Minecraft window on screen now, by class, and whether it is maximized (for the self-test).
    pub fn game_windows_now() -> Vec<(String, bool)> {
        let mut list: Vec<(String, bool)> = Vec::new();
        // SAFETY: `collect` only touches `list` while EnumWindows runs.
        unsafe { EnumWindows(Some(collect), &mut list as *mut Vec<(String, bool)> as LPARAM) };
        list
    }

    /// Polls for the game's window until `limit`: every 50 ms (a window list costs well under a millisecond), so the
    /// game is maximized within a frame or three of showing instead of a quarter second later.
    pub fn wait_for_game_window(pid: u32, limit: Duration) -> Option<HWND> {
        let start = Instant::now();
        while start.elapsed() < limit {
            if let Some(hwnd) = game_window(pid) {
                return Some(hwnd);
            }
            std::thread::sleep(Duration::from_millis(50));
        }
        None
    }

    /// Maximizes the game's window. Minecraft sizes its window once more while it starts on some versions,
    /// so the maximize is checked again a moment later (unless the player minimized it meanwhile).
    pub fn maximize(hwnd: HWND) {
        // SAFETY: ShowWindow and the checks take a window handle; a handle that has since closed fails
        // harmlessly.
        unsafe { ShowWindow(hwnd, SW_MAXIMIZE) };
        for _ in 0..6 {
            std::thread::sleep(Duration::from_millis(500));
            if unsafe { IsWindowVisible(hwnd) } == 0 || unsafe { IsIconic(hwnd) } != 0 {
                return;
            }
            if unsafe { IsZoomed(hwnd) } == 0 {
                unsafe { ShowWindow(hwnd, SW_MAXIMIZE) };
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn minecraft_s_own_fullscreen_wins_over_maximized() {
        assert!(wants_fullscreen(b"version:4671\nfullscreen:true\n"));
        assert!(wants_fullscreen(b"invertYMouse:false\r\nfullscreen:true\r\n"), "1.8.9 writes CRLF on Windows");
        assert!(!wants_fullscreen(b"version:5023\nfullscreen:false\n"));
        assert!(!wants_fullscreen(b"exclusiveFullscreen:true\n"), "another key");
        assert!(!wants_fullscreen(b""), "a profile that hasn't been played");

        let dir = TestDir::new();
        assert!(should_maximize(&json!({}), &dir.0), "on unless turned off");
        assert!(!should_maximize(&json!({ "gameMaximized": false }), &dir.0));
        std::fs::write(dir.0.join("options.txt"), "fullscreen:true\n").unwrap();
        assert!(!should_maximize(&json!({}), &dir.0));
    }

    #[test]
    fn flint_minimizes_unless_told_otherwise() {
        assert_eq!(OnStart::from_settings(&json!({})), OnStart::Minimize);
        assert_eq!(OnStart::from_settings(&json!({ "minimizeOnLaunch": false })), OnStart::Minimize, "the old switch is retired");
        assert_eq!(OnStart::from_settings(&json!({ "gameStartBehavior": "keep" })), OnStart::KeepOpen);
        assert_eq!(OnStart::from_settings(&json!({ "gameStartBehavior": "tray" })), if tray_supported() { OnStart::Tray } else { OnStart::Minimize });
        assert_eq!(OnStart::from_settings(&json!({ "gameStartBehavior": "nonsense" })), OnStart::Minimize);
    }
}
