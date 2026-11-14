//! Instant Swap's still window (swap.rs): while the old game hands over to the new one, the player sees the old game's last
//! frame exactly where its window was, never a desktop, a menu, a loading screen or a black window. The old game writes that
//! frame (`.flint-swap-frame.png` in the profile folder) just before it leaves; the launcher shows it in a borderless window
//! on top that takes no focus, has no taskbar entry and shows nothing else. It closes as soon as the new game is in front (or
//! the swap is given up, or after two minutes at most).
//!
//! Windows: a native layered window whose picture is set before it is shown (`UpdateLayeredWindow`), so its very first
//! frame on screen is the game's frame, placed over the old game's client area in physical pixels. Elsewhere: a webview page
//! from the launcher itself (the `flint-still` scheme) that is built hidden and shows itself only once it has decoded the
//! frame (it then asks for `shown`). Either way nothing shows when there is no frame: the old game's window still holds it.

use serde_json::Value;
use std::sync::Mutex;
use std::time::Duration;
use tauri::{AppHandle, Manager};

pub const SCHEME: &str = "flint-still";
pub const LABEL: &str = "swap-still";
/// The old game's frame file in the profile folder (game-runtime InstantSwap.FRAME_FILE).
pub const FRAME_FILE: &str = ".flint-swap-frame.png";
const MAX_FRAME: u64 = 64 * 1024 * 1024;
const LONGEST: Duration = Duration::from_secs(120);

static FRAME: Mutex<Option<Vec<u8>>> = Mutex::new(None);
/// Told once the frame is on screen, so a caller can wait for it.
static SHOWN: Mutex<Option<tokio::sync::oneshot::Sender<()>>> = Mutex::new(None);

/// The page (not Windows): the frame filling the window, nothing else. It says `shown` once the frame is decoded.
const PAGE: &str = r#"<!doctype html><html><head><meta charset="utf-8"><style>
html,body{margin:0;height:100%;overflow:hidden;background:transparent;cursor:default}
img{display:block;width:100vw;height:100vh;object-fit:fill;user-select:none;-webkit-user-drag:none}
</style></head><body><img id="f" alt=""><script>
const f=document.getElementById("f");f.src="frame.png";
f.decode().then(()=>fetch("shown"),()=>{});
</script></body></html>"#;

/// What the scheme serves: the page, or the frame; `shown` (the page has the frame) shows the window.
pub fn serve(app: &AppHandle, path: &str) -> Option<(Vec<u8>, &'static str)> {
    match path.trim_start_matches('/') {
        "" | "index.html" => Some((PAGE.as_bytes().to_vec(), "text/html; charset=utf-8")),
        "frame.png" => FRAME.lock().unwrap().clone().map(|bytes| (bytes, "image/png")),
        "shown" => {
            let handle = app.clone();
            tauri::async_runtime::spawn(async move {
                if let Some(still) = handle.get_webview_window(LABEL) {
                    let _ = still.show();
                }
                told();
            });
            Some((Vec::new(), "text/plain"))
        }
        _ => None,
    }
}

fn told() {
    if let Some(tell) = SHOWN.lock().unwrap().take() {
        let _ = tell.send(());
    }
}

/// A PNG's width and height from its header.
pub fn png_size(bytes: &[u8]) -> Option<(u32, u32)> {
    if bytes.len() < 24 || &bytes[..8] != b"\x89PNG\r\n\x1a\n" || &bytes[12..16] != b"IHDR" {
        return None;
    }
    let w = u32::from_be_bytes(bytes[16..20].try_into().ok()?);
    let h = u32::from_be_bytes(bytes[20..24].try_into().ok()?);
    (w > 0 && h > 0 && w <= 16_384 && h <= 16_384).then_some((w, h))
}

/// A PNG as top-down BGRA rows with full alpha (what a 32-bit DIB holds), or None when it can't be decoded.
pub fn png_bgra(bytes: &[u8]) -> Option<(u32, u32, Vec<u8>)> {
    let mut decoder = png::Decoder::new(std::io::Cursor::new(bytes));
    decoder.set_transformations(png::Transformations::normalize_to_color8());
    let mut reader = decoder.read_info().ok()?;
    let mut buf = vec![0; reader.output_buffer_size()?];
    let info = reader.next_frame(&mut buf).ok()?;
    let (w, h) = (info.width, info.height);
    let channels = match info.color_type {
        png::ColorType::Rgba => 4,
        png::ColorType::Rgb => 3,
        png::ColorType::GrayscaleAlpha => 2,
        png::ColorType::Grayscale => 1,
        png::ColorType::Indexed => return None,
    };
    let mut out = Vec::with_capacity(w as usize * h as usize * 4);
    for row in buf[..info.buffer_size()].chunks_exact(info.line_size) {
        for px in row[..w as usize * channels].chunks_exact(channels) {
            let (r, g, b) = match channels {
                1 | 2 => (px[0], px[0], px[0]),
                _ => (px[0], px[1], px[2]),
            };
            // The game's frame is opaque: the window shows it as it is, never see-through.
            out.extend_from_slice(&[b, g, r, 255]);
        }
    }
    Some((w, h, out))
}

/// Shows the old game's last frame over its window (`window`: x, y in screen pixels, and `pid`, the old game's process,
/// whose window gives the exact place on Windows; the size is the frame's own). Nothing happens when there is no frame or it
/// can't be read: the old game's window holds the frame and the swap goes on. The receiver hears once the frame is on screen
/// (never, when there's nothing to show).
pub fn show(app: &AppHandle, profile_dir: &std::path::Path, window: &Value) -> tokio::sync::oneshot::Receiver<()> {
    let (tell, shown) = tokio::sync::oneshot::channel();
    let path = profile_dir.join(FRAME_FILE);
    let bytes = match std::fs::metadata(&path) {
        Ok(meta) if meta.len() <= MAX_FRAME => std::fs::read(&path).ok(),
        _ => None,
    };
    let _ = std::fs::remove_file(&path);
    let Some(bytes) = bytes else { return shown };
    let Some((w, h)) = png_size(&bytes) else { return shown };
    let (x, y) = (window["x"].as_i64().unwrap_or(0) as i32, window["y"].as_i64().unwrap_or(0) as i32);
    // An earlier still goes first: closing it after would forget this frame (1.0.4's first test build showed a broken picture
    // on black that way).
    close(app);
    *SHOWN.lock().unwrap() = Some(tell);
    #[cfg(windows)]
    {
        let pid = window["pid"].as_u64().map(|p| p as u32);
        match png_bgra(&bytes) {
            Some((w, h, pixels)) => native::show(pixels, w, h, pid, (x, y)),
            None => {
                SHOWN.lock().unwrap().take();
            }
        }
        let _ = (w, h, app);
        shown
    }
    #[cfg(not(windows))]
    {
        *FRAME.lock().unwrap() = Some(bytes);
        let Ok(url) = format!("http://{SCHEME}.localhost/").parse() else { return shown };
        let built = tauri::WebviewWindowBuilder::new(app, LABEL, tauri::WebviewUrl::External(url))
            .title("Minecraft")
            .decorations(false)
            .always_on_top(true)
            .skip_taskbar(true)
            .focused(false)
            .resizable(false)
            .shadow(false)
            .visible(false)
            .build();
        match built {
            Ok(still) => {
                let _ = still.set_size(tauri::PhysicalSize::new(w, h));
                let _ = still.set_position(tauri::PhysicalPosition::new(x, y));
                // It shows itself once its page has the frame (`shown`).
                let handle = app.clone();
                tauri::async_runtime::spawn(async move {
                    tokio::time::sleep(LONGEST).await;
                    close(&handle);
                });
            }
            Err(error) => {
                eprintln!("[flint] instant swap: no still window: {error}");
                *FRAME.lock().unwrap() = None;
                SHOWN.lock().unwrap().take();
            }
        }
        shown
    }
}

/// Closes the still window, if one shows.
pub fn close(app: &AppHandle) {
    #[cfg(windows)]
    native::close();
    if let Some(still) = app.get_webview_window(LABEL) {
        let _ = still.close();
    }
    *FRAME.lock().unwrap() = None;
    SHOWN.lock().unwrap().take();
}

/// Closes it a moment after the new game is in front: that game has drawn several frames on screen under the still by then
/// (InstantSwap.revealTick), so nothing white or empty ever shows.
pub fn close_soon(app: &AppHandle) {
    let handle = app.clone();
    tauri::async_runtime::spawn(async move {
        tokio::time::sleep(Duration::from_millis(120)).await;
        close(&handle);
    });
}

#[cfg(windows)]
mod native {
    //! The still on Windows: a layered, topmost, no-activate popup with its own thread and message loop. Its picture is set
    //! with UpdateLayeredWindow before it is shown, so it never draws anything but the frame.
    use std::sync::atomic::{AtomicIsize, Ordering};
    use windows_sys::Win32::Foundation::{HWND, LPARAM, LRESULT, POINT, RECT, SIZE, WPARAM};
    use windows_sys::Win32::Graphics::Gdi::{
        ClientToScreen, CreateCompatibleDC, CreateDIBSection, DeleteDC, DeleteObject, GetDC, ReleaseDC, SelectObject, AC_SRC_ALPHA,
        AC_SRC_OVER, BITMAPINFO, BITMAPINFOHEADER, BI_RGB, BLENDFUNCTION, DIB_RGB_COLORS,
    };
    use windows_sys::Win32::UI::HiDpi::{SetThreadDpiAwarenessContext, DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2};
    use windows_sys::Win32::UI::WindowsAndMessaging::*;

    /// The still's window, 0 when none.
    static HWND_NOW: AtomicIsize = AtomicIsize::new(0);

    pub fn show(pixels: Vec<u8>, w: u32, h: u32, pid: Option<u32>, fallback: (i32, i32)) {
        std::thread::Builder::new()
            .name("flint still".into())
            .spawn(move || run(pixels, w, h, pid, fallback))
            .ok();
    }

    pub fn close() {
        let hwnd = HWND_NOW.swap(0, Ordering::SeqCst);
        if hwnd != 0 {
            // SAFETY: posting a message to a window handle is safe even if the window has gone meanwhile.
            unsafe { PostMessageW(hwnd as HWND, FADE, 0, 0) };
        }
    }

    /// Fade out (~100 ms), then close: the window under it (the new game, already drawn) comes through gradually, and no
    /// frame on screen is ever without a picture.
    const FADE: u32 = WM_APP + 1;

    unsafe extern "system" fn proc(hwnd: HWND, msg: u32, w: WPARAM, l: LPARAM) -> LRESULT {
        match msg {
            FADE => {
                for alpha in [208u8, 160, 112, 64, 16] {
                    let blend = BLENDFUNCTION { BlendOp: AC_SRC_OVER as u8, BlendFlags: 0, SourceConstantAlpha: alpha, AlphaFormat: AC_SRC_ALPHA as u8 };
                    // No source DC: only the window's opacity changes, its picture stays.
                    UpdateLayeredWindow(hwnd, std::ptr::null_mut(), std::ptr::null(), std::ptr::null(), std::ptr::null_mut(), std::ptr::null(), 0, &blend, ULW_ALPHA);
                    std::thread::sleep(std::time::Duration::from_millis(20));
                }
                DestroyWindow(hwnd);
                0
            }
            WM_MOUSEACTIVATE => MA_NOACTIVATE as LRESULT,
            WM_NCHITTEST => HTTRANSPARENT as LRESULT,
            WM_CLOSE => {
                DestroyWindow(hwnd);
                0
            }
            WM_DESTROY => {
                PostQuitMessage(0);
                0
            }
            _ => DefWindowProcW(hwnd, msg, w, l),
        }
    }

    struct Found {
        pid: u32,
        best: Option<(HWND, i64)>,
    }

    unsafe extern "system" fn each(hwnd: HWND, data: LPARAM) -> i32 {
        let found = &mut *(data as *mut Found);
        let mut pid = 0u32;
        GetWindowThreadProcessId(hwnd, &mut pid);
        if pid == found.pid && IsWindowVisible(hwnd) != 0 {
            let mut r: RECT = std::mem::zeroed();
            GetClientRect(hwnd, &mut r);
            let area = i64::from(r.right - r.left) * i64::from(r.bottom - r.top);
            if found.best.is_none_or(|(_, a)| area > a) {
                found.best = Some((hwnd, area));
            }
        }
        1
    }

    /// The old game's client area's top left corner on the screen, in physical pixels.
    unsafe fn client_origin(pid: u32) -> Option<(i32, i32)> {
        let mut found = Found { pid, best: None };
        EnumWindows(Some(each), &mut found as *mut Found as LPARAM);
        let (hwnd, _) = found.best?;
        let mut p = POINT { x: 0, y: 0 };
        (ClientToScreen(hwnd, &mut p) != 0).then_some((p.x, p.y))
    }

    fn run(pixels: Vec<u8>, w: u32, h: u32, pid: Option<u32>, fallback: (i32, i32)) {
        // SAFETY: plain Win32 calls on this thread's own window and GDI objects, each released before the thread ends.
        unsafe {
            SetThreadDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
            let (x, y) = pid.and_then(|p| client_origin(p)).unwrap_or(fallback);
            let class: Vec<u16> = "FlintSwapStill\0".encode_utf16().collect();
            let title: Vec<u16> = "Minecraft\0".encode_utf16().collect();
            let wc = WNDCLASSW { lpfnWndProc: Some(proc), lpszClassName: class.as_ptr(), ..std::mem::zeroed() };
            RegisterClassW(&wc);
            let hwnd = CreateWindowExW(
                WS_EX_LAYERED | WS_EX_TOPMOST | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE | WS_EX_TRANSPARENT,
                class.as_ptr(),
                title.as_ptr(),
                WS_POPUP,
                x,
                y,
                w as i32,
                h as i32,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                std::ptr::null(),
            );
            if hwnd.is_null() {
                super::SHOWN.lock().unwrap().take();
                return;
            }
            let screen = GetDC(std::ptr::null_mut());
            let mem = CreateCompatibleDC(screen);
            let mut info: BITMAPINFO = std::mem::zeroed();
            info.bmiHeader = BITMAPINFOHEADER {
                biSize: std::mem::size_of::<BITMAPINFOHEADER>() as u32,
                biWidth: w as i32,
                biHeight: -(h as i32),
                biPlanes: 1,
                biBitCount: 32,
                biCompression: BI_RGB,
                ..std::mem::zeroed()
            };
            let mut bits: *mut core::ffi::c_void = std::ptr::null_mut();
            let dib = CreateDIBSection(mem, &info, DIB_RGB_COLORS, &mut bits, std::ptr::null_mut(), 0);
            let mut ok = false;
            if !dib.is_null() && !bits.is_null() {
                std::ptr::copy_nonoverlapping(pixels.as_ptr(), bits as *mut u8, pixels.len().min(w as usize * h as usize * 4));
                let old = SelectObject(mem, dib);
                let blend = BLENDFUNCTION { BlendOp: AC_SRC_OVER as u8, BlendFlags: 0, SourceConstantAlpha: 255, AlphaFormat: AC_SRC_ALPHA as u8 };
                let at = POINT { x, y };
                let size = SIZE { cx: w as i32, cy: h as i32 };
                let from = POINT { x: 0, y: 0 };
                ok = UpdateLayeredWindow(hwnd, screen, &at, &size, mem, &from, 0, &blend, ULW_ALPHA) != 0;
                SelectObject(mem, old);
                DeleteObject(dib);
            }
            DeleteDC(mem);
            ReleaseDC(std::ptr::null_mut(), screen);
            drop(pixels);
            if !ok {
                DestroyWindow(hwnd);
                super::SHOWN.lock().unwrap().take();
                return;
            }
            // Shown with its picture already set: its first frame on screen is the game's frame.
            SetWindowPos(hwnd, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_SHOWWINDOW);
            let previous = HWND_NOW.swap(hwnd as isize, Ordering::SeqCst);
            if previous != 0 {
                PostMessageW(previous as HWND, WM_CLOSE, 0, 0);
            }
            super::told();
            // At most two minutes, whatever happens.
            SetTimer(hwnd, 1, super::LONGEST.as_millis() as u32, None);
            let mut msg: MSG = std::mem::zeroed();
            while GetMessageW(&mut msg, std::ptr::null_mut(), 0, 0) > 0 {
                if msg.message == WM_TIMER {
                    DestroyWindow(hwnd);
                    continue;
                }
                TranslateMessage(&msg);
                DispatchMessageW(&msg);
            }
            let _ = HWND_NOW.compare_exchange(hwnd as isize, 0, Ordering::SeqCst, Ordering::SeqCst);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_page_shows_only_the_frame_once_it_is_decoded() {
        assert!(PAGE.contains("frame.png") && PAGE.contains("decode()") && PAGE.contains("fetch(\"shown\")"), "shown only once decoded");
        assert!(!PAGE.contains("http"), "nothing from elsewhere");
        assert!(!PAGE.contains("background:#000"), "never a black page");
        assert!(!PAGE.contains("<i>"), "no spinner: nothing but the frame");
        assert!(FRAME.lock().unwrap().is_none(), "no frame before a swap");
    }

    #[test]
    fn a_png_header_gives_the_size() {
        let mut png = b"\x89PNG\r\n\x1a\n\0\0\0\rIHDR".to_vec();
        png.extend_from_slice(&1920u32.to_be_bytes());
        png.extend_from_slice(&1080u32.to_be_bytes());
        assert_eq!(png_size(&png), Some((1920, 1080)));
        assert_eq!(png_size(b"not a png at all, really not"), None);
    }

    #[test]
    fn a_frame_decodes_to_opaque_bgra() {
        let mut bytes = Vec::new();
        {
            let mut encoder = png::Encoder::new(&mut bytes, 2, 1);
            encoder.set_color(png::ColorType::Rgba);
            let mut writer = encoder.write_header().unwrap();
            writer.write_image_data(&[10, 20, 30, 0, 200, 100, 50, 255]).unwrap();
        }
        let (w, h, px) = png_bgra(&bytes).unwrap();
        assert_eq!((w, h), (2, 1));
        assert_eq!(px, vec![30, 20, 10, 255, 50, 100, 200, 255], "BGRA, never see-through");
        assert!(png_bgra(b"nope").is_none());
    }
}
