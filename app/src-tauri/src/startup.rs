//! Window start-up safeguards: a development build whose frontend server isn't running shows an
//! explanation instead of an empty window, and self-test runs never show a window at all.

use std::net::{TcpStream, ToSocketAddrs};
use std::time::Duration;
use tauri::{AppHandle, Manager};

/// Custom URL scheme that serves Flint's built-in diagnostic pages.
pub const SCHEME: &str = "flint-diagnostic";

const DEV_SERVER_PAGE: &str = r##"<!doctype html><html><head><meta charset="utf-8"><title>Flint Client (development build)</title>
<style>:root{color-scheme:dark}body{margin:0;min-height:100vh;display:grid;place-items:center;background:#0a0908;color:#fef3e2;font:15px/1.55 -apple-system,"Segoe UI",sans-serif}
main{max-width:520px;padding:40px}h1{font-size:20px;font-weight:600;margin:18px 0 8px}p{margin:0 0 12px;color:rgba(254,243,226,.7)}
code{font:13px ui-monospace,SFMono-Regular,Menlo,monospace;background:rgba(254,243,226,.08);padding:2px 6px;border-radius:5px;color:#fef3e2}</style></head>
<body><main><svg width="36" height="36" viewBox="150 50 212 370" aria-hidden="true"><polygon points="256,60 351,228 256,312 161,228" fill="#B45309"/><polygon points="256,60 351,228 256,186" fill="#D97706"/><polygon points="239,172 318,284 239,410 194,284" fill="#FEF3E2"/></svg>
<h1>This is a development build of Flint</h1>
<p>It loads its interface from the development server at <code>{{URL}}</code>, which isn't running.</p>
<p>Start it with <code>npm run tauri dev</code> in the <code>app</code> folder, or use a packaged build. Your installed Flint Client is not affected.</p>
</main></body></html>"##;

/// Serves the diagnostic pages registered under `SCHEME`.
pub fn diagnostic_page(path: &str, dev_url: &str) -> Option<String> {
    match path {
        "/dev-server" => Some(DEV_SERVER_PAGE.replace("{{URL}}", &html_escape(dev_url))),
        _ => None,
    }
}

fn html_escape(text: &str) -> String {
    text.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;")
}

/// Whether something accepts connections at the URL's host and port.
pub fn reachable(url: &url::Url, timeout: Duration) -> bool {
    let (Some(host), Some(port)) = (url.host_str(), url.port_or_known_default()) else { return false };
    let Ok(addresses) = (host, port).to_socket_addrs() else { return false };
    addresses.into_iter().any(|address| TcpStream::connect_timeout(&address, timeout).is_ok())
}

/// The URL of a diagnostic page, in the form the platform's webview expects for custom schemes.
fn page_url(path: &str) -> url::Url {
    let base = if cfg!(windows) || cfg!(target_os = "android") { format!("http://{SCHEME}.localhost") } else { format!("{SCHEME}://localhost") };
    url::Url::parse(&format!("{base}{path}")).expect("diagnostic URL is valid")
}

/// Runs once the main window exists.
pub fn prepare_window(app: &AppHandle, selftest: bool) {
    let Some(window) = app.get_webview_window("main") else { return };
    if selftest {
        // Self-tests exercise the backend only. Never show a window that could be mistaken for
        // the real app, and keep the test process out of the Dock.
        let _ = window.hide();
        let _ = window.set_title("Flint Client (self-test)");
        #[cfg(target_os = "macos")]
        let _ = app.set_activation_policy(tauri::ActivationPolicy::Accessory);
        return;
    }
    if !tauri::is_dev() {
        return;
    }
    let Some(dev_url) = app.config().build.dev_url.clone() else { return };
    let _ = window.set_title("Flint Client (development build)");
    std::thread::spawn(move || {
        if !reachable(&dev_url, Duration::from_millis(500)) {
            let _ = window.navigate(page_url("/dev-server"));
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detects_whether_a_server_is_listening() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let url = url::Url::parse(&format!("http://127.0.0.1:{port}/")).unwrap();
        assert!(reachable(&url, Duration::from_millis(300)));
        drop(listener);
        assert!(!reachable(&url, Duration::from_millis(300)), "nothing listens once the server is gone");
    }

    #[test]
    fn serves_only_known_pages_with_escaped_content() {
        let page = diagnostic_page("/dev-server", "http://localhost:1420/<x>").unwrap();
        assert!(page.contains("http://localhost:1420/&lt;x&gt;"));
        assert!(page.contains("development build"));
        assert!(diagnostic_page("/anything-else", "").is_none());
    }
}
