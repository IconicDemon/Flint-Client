//! Discord Rich Presence: "Playing Flint Client · Minecraft 26.3 · Fabric" on the player's profile
//! while a game launched through Flint runs.
//!
//! Flint talks to the Discord desktop app over its local IPC socket (`discord-ipc-N`), the same
//! channel Discord's own SDKs use for presence, so there is no login, no token and no native
//! library. One background thread owns the connection: it stays dormant (disconnected) unless a
//! game is running and the setting is on, sends an update only when the activity changes, and
//! retries with backoff while Discord is closed. Game start and exit come from the launch watcher in
//! game.rs, so there is no second process tracker.
//!
//! Each activity is published for the game's own process id (the `pid` of SET_ACTIVITY), not
//! Flint's. Discord detects Minecraft by itself too; tying the activity to the same process lets
//! Discord treat them as one game instead of two competing activities, and lets Discord itself drop
//! the activity if that process disappears.
//!
//! If Flint quits while a game runs, a tiny headless copy of this binary (`--flint-discord-keeper`,
//! no window and no web view) keeps the presence until that game exits, then clears it and exits.
//! The next Flint start stops the keeper and takes its sessions back, so only one connection ever
//! publishes.

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::mpsc::{self, Receiver, RecvTimeoutError, Sender};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// The public id of the "Flint Client" application in the Discord Developer Portal.
pub const APPLICATION_ID: &str = "1559823688407195729";
/// The Rich Presence art asset uploaded for that application (verified on 2026-10-14).
const LARGE_IMAGE: &str = "flint_logo";
const LARGE_TEXT: &str = "Fast where it matters.";
/// A game must stay up this long before it's shown, so a launch that crashes at once never flashes.
const SETTLE: Duration = Duration::from_secs(4);
/// Reconnect delays while a game runs and Discord can't be reached; the last one repeats.
const RETRY_SECS: [u64; 5] = [5, 15, 30, 60, 120];
/// How often an open connection is checked for Discord having quit or restarted.
const HEARTBEAT: Duration = Duration::from_secs(30);
#[cfg_attr(not(unix), allow(dead_code))] // Windows pipes are opened as files, which have no timeouts
const IO_TIMEOUT: Duration = Duration::from_secs(3);
const KEEPER_FLAG: &str = "--flint-discord-keeper";
const KEEPER_FILE: &str = "discord-keeper.json";
/// A keeper never outlives this, whatever happens to the game it watches.
const KEEPER_MAX: Duration = Duration::from_secs(48 * 3600);
/// What the game reports about the player (written by Flint's Discord mod while it's on), in the game's folder.
const REPORT_FILE: &str = "flint-presence.json";
/// How often a shown game's report is read again. Discord takes five updates per 20 seconds; this stays under that.
const REPORT_POLL: Duration = Duration::from_secs(5);
/// Discord cuts an activity's details and state at this many characters.
const LINE_MAX: usize = 128;

/// One running game, as Discord shows it.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Session {
    pub id: String,
    pub pid: u32,
    pub version: String,
    pub loader: String,
    /// When the game started, in Unix seconds (Discord's elapsed timer counts from here).
    pub started_at: u64,
    /// The game's folder, where it writes its report (`REPORT_FILE`).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub game_dir: Option<PathBuf>,
}

#[derive(Clone, Copy, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum Status {
    /// The setting is off.
    Off,
    /// On, with nothing shown on Discord (only while it stops showing; Flint idles on Discord when no game runs).
    Idle,
    /// A game is running and Discord accepted its activity. Discord still decides what the profile
    /// shows (for example, when it has also detected the game by itself).
    Connected,
    /// A game is running but Discord isn't running (or refused the connection).
    Unavailable,
}

/// The activity for a session. With `details` off it says only that Minecraft is being played.
pub fn activity(session: &Session, details: bool) -> Value {
    let mut activity = json!({
        "timestamps": { "start": session.started_at },
        "assets": { "large_image": LARGE_IMAGE, "large_text": LARGE_TEXT },
        "instance": false,
    });
    if details {
        let loader = match session.loader.as_str() {
            "fabric" => "Fabric",
            "vanilla" => "Vanilla",
            other => other,
        };
        match report(session) {
            Some(report) => {
                let (details, state) = described(&report);
                activity["details"] = json!(details);
                if let Some(state) = state {
                    activity["state"] = json!(state);
                }
                if let Some(buttons) = buttons(&report) {
                    activity["buttons"] = buttons;
                }
            }
            None => {
                activity["details"] = json!(format!("Minecraft {}", session.version));
                activity["state"] = json!(format!("{loader} · Playing Minecraft"));
            }
        }
    } else {
        activity["details"] = json!("Playing Minecraft");
    }
    activity
}

/// The activity while Flint is open and no game is running: Flint idling, timed from when Flint opened.
pub fn idle_activity(opened_at: u64, details: bool) -> Value {
    json!({
        "details": if details { "In the launcher" } else { "Flint Client" },
        "state": "Idling",
        "timestamps": { "start": opened_at },
        "assets": { "large_image": LARGE_IMAGE, "large_text": LARGE_TEXT },
        "instance": false,
    })
}

/// The game's report, if it wrote one during this session (one left from an earlier run is ignored).
fn report(session: &Session) -> Option<Value> {
    let path = session.game_dir.as_ref()?.join(REPORT_FILE);
    let written = std::fs::metadata(&path).ok()?.modified().ok()?.duration_since(std::time::UNIX_EPOCH).ok()?.as_secs();
    if written + 1 < session.started_at {
        return None;
    }
    serde_json::from_slice::<Value>(&std::fs::read(&path).ok()?).ok().filter(Value::is_object)
}

/// The report's profile buttons (the Discord mod's Simple Discord Rich Presence options): at most two, each a label of up to
/// 32 characters and an http(s) address, as Discord takes them; None when there are none.
fn buttons(report: &Value) -> Option<Value> {
    let list: Vec<Value> = report
        .get("buttons")?
        .as_array()?
        .iter()
        .filter_map(|b| {
            let label = b.get("label")?.as_str()?.trim();
            let url = b.get("url")?.as_str()?.trim();
            let ok = !label.is_empty() && label.chars().count() <= 32 && url.len() <= 512 && (url.starts_with("https://") || url.starts_with("http://"));
            ok.then(|| json!({ "label": label, "url": url }))
        })
        .take(2)
        .collect();
    (!list.is_empty()).then_some(Value::Array(list))
}

/// A report as Discord's two lines: what is being played ("Minecraft 26.3 · Multiplayer"), then where ("Hypixel · In
/// Overworld · Plains"), which is left out when the report has none of it. A server address never shows.
fn described(report: &Value) -> (String, Option<String>) {
    let text = |key: &str| report.get(key).and_then(Value::as_str).map(str::trim).filter(|s| !s.is_empty()).map(str::to_owned);
    let mut first: Vec<String> = Vec::new();
    first.extend(text("version").map(|v| format!("Minecraft {v}")));
    first.extend(text("state"));
    // The world or public server's name leads the second line, then the Discord mod's status ("In Overworld").
    let mut second: Vec<String> = Vec::new();
    second.extend(text("world").or_else(|| text("server").filter(|s| !looks_like_address(s))));
    second.extend(["status", "dimension", "biome"].into_iter().filter_map(text));
    second.extend(text("riding").map(|r| format!("Riding {r}")));
    let first = if first.is_empty() { "Playing Minecraft".to_owned() } else { line(&first) };
    (first, (!second.is_empty()).then(|| line(&second)))
}

/// Whether a server "name" is really an address (older Flint runtimes sent it): an IP, a host name or a port. Shown
/// to everyone on Discord, a private server's address would be its owner's.
fn looks_like_address(name: &str) -> bool {
    name.contains(['.', ':', '/']) || name.chars().all(|c| c.is_ascii_digit() || c == '.' || c == ':')
}

/// Parts joined with " · ", cut to what Discord shows.
fn line(parts: &[String]) -> String {
    let joined = parts.join(" · ");
    if joined.chars().count() <= LINE_MAX {
        return joined;
    }
    let mut cut: String = joined.chars().take(LINE_MAX - 1).collect();
    cut.push('…');
    cut
}

/// The session Discord shows: the most recently started one that has settled.
fn shown(sessions: &[(Session, Instant)], now: Instant) -> Option<&Session> {
    sessions.iter().filter(|(_, since)| now.duration_since(*since) >= SETTLE).map(|(s, _)| s).max_by_key(|s| (s.started_at, s.pid))
}

// ---------- the IPC connection ----------

#[cfg_attr(not(unix), allow(dead_code))] // the Windows pipe can't peek, so it only reports Nothing
enum Pending {
    Nothing,
    Data,
    Closed,
}

trait Stream: Read + Write + Send {
    /// Whether Discord has sent something, closed its end, or neither (without blocking).
    fn pending(&mut self) -> Pending;
}

#[cfg(unix)]
impl Stream for std::os::unix::net::UnixStream {
    fn pending(&mut self) -> Pending {
        use std::os::fd::AsRawFd;
        // Peek one byte without blocking: 0 means Discord closed its end; "would block" means nothing yet.
        let mut byte = 0u8;
        let n = unsafe { libc::recv(self.as_raw_fd(), (&mut byte as *mut u8).cast(), 1, libc::MSG_PEEK | libc::MSG_DONTWAIT) };
        match n {
            1.. => Pending::Data,
            0 => Pending::Closed,
            _ if std::io::Error::last_os_error().kind() == std::io::ErrorKind::WouldBlock => Pending::Nothing,
            _ => Pending::Closed,
        }
    }
}

#[cfg(windows)]
impl Stream for std::fs::File {
    fn pending(&mut self) -> Pending {
        Pending::Nothing
    }
}

struct Ipc {
    stream: Box<dyn Stream>,
}

/// Where the Discord desktop app listens.
fn socket_paths() -> Vec<PathBuf> {
    #[cfg(unix)]
    {
        let mut dirs: Vec<PathBuf> = ["XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"].iter().filter_map(std::env::var_os).map(PathBuf::from).collect();
        dirs.push(PathBuf::from("/tmp"));
        let mut out = Vec::new();
        for dir in dirs {
            for sub in ["", "app/com.discordapp.Discord", "snap.discord"] {
                for n in 0..10 {
                    out.push(dir.join(sub).join(format!("discord-ipc-{n}")));
                }
            }
        }
        out
    }
    #[cfg(windows)]
    {
        (0..10).map(|n| PathBuf::from(format!(r"\\.\pipe\discord-ipc-{n}"))).collect()
    }
}

impl Ipc {
    fn connect() -> std::io::Result<Ipc> {
        let mut last = std::io::Error::new(std::io::ErrorKind::NotFound, "Discord isn't running");
        for path in socket_paths() {
            match Ipc::open(&path) {
                Ok(mut ipc) => match ipc.handshake() {
                    Ok(()) => return Ok(ipc),
                    Err(e) => last = e,
                },
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                Err(e) => last = e,
            }
        }
        Err(last)
    }

    #[cfg(unix)]
    fn open(path: &Path) -> std::io::Result<Ipc> {
        if !path.exists() {
            return Err(std::io::ErrorKind::NotFound.into());
        }
        let stream = std::os::unix::net::UnixStream::connect(path)?;
        stream.set_read_timeout(Some(IO_TIMEOUT))?;
        stream.set_write_timeout(Some(IO_TIMEOUT))?;
        Ok(Ipc { stream: Box::new(stream) })
    }

    #[cfg(windows)]
    fn open(path: &Path) -> std::io::Result<Ipc> {
        let file = std::fs::OpenOptions::new().read(true).write(true).open(path)?;
        Ok(Ipc { stream: Box::new(file) })
    }

    fn send(&mut self, op: u32, payload: &Value) -> std::io::Result<()> {
        let body = payload.to_string();
        let mut frame = Vec::with_capacity(8 + body.len());
        frame.extend_from_slice(&op.to_le_bytes());
        frame.extend_from_slice(&(body.len() as u32).to_le_bytes());
        frame.extend_from_slice(body.as_bytes());
        self.stream.write_all(&frame)
    }

    fn receive(&mut self) -> std::io::Result<(u32, Value)> {
        let mut header = [0u8; 8];
        self.stream.read_exact(&mut header)?;
        let op = u32::from_le_bytes(header[0..4].try_into().unwrap());
        let len = u32::from_le_bytes(header[4..8].try_into().unwrap()) as usize;
        if len > 64 * 1024 {
            return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "oversized Discord frame"));
        }
        let mut body = vec![0u8; len];
        self.stream.read_exact(&mut body)?;
        Ok((op, serde_json::from_slice(&body).unwrap_or(Value::Null)))
    }

    fn handshake(&mut self) -> std::io::Result<()> {
        self.send(0, &json!({ "v": 1, "client_id": APPLICATION_ID }))?;
        match self.receive()? {
            (1, reply) if reply["evt"] == "READY" => Ok(()),
            (_, reply) => Err(std::io::Error::other(format!("Discord refused the connection: {}", reply["message"].as_str().unwrap_or("unknown reason")))),
        }
    }

    /// Sets (or with `None`, clears) the activity for the game process `pid`.
    fn set_activity(&mut self, pid: u32, activity: Option<&Value>) -> std::io::Result<()> {
        let nonce = crate::core::random_hex(8);
        self.send(1, &json!({ "cmd": "SET_ACTIVITY", "args": { "pid": pid, "activity": activity }, "nonce": nonce }))?;
        // Skip anything that isn't the answer to this request.
        for _ in 0..4 {
            let (op, reply) = self.receive()?;
            if op == 2 {
                return Err(std::io::Error::new(std::io::ErrorKind::ConnectionAborted, "Discord closed the connection"));
            }
            if op == 3 {
                self.send(4, &reply)?;
                continue;
            }
            if reply["nonce"] == nonce {
                #[cfg(debug_assertions)]
                {
                    *LAST_REPLY.lock().unwrap() = Some(reply.clone());
                }
                return if reply["evt"] == "ERROR" { Err(std::io::Error::other(reply["data"]["message"].as_str().unwrap_or("Discord rejected the activity").to_owned())) } else { Ok(()) };
            }
        }
        Err(std::io::Error::other("no answer from Discord"))
    }

    /// Between updates: answers any ping Discord sent and reports whether the connection is still
    /// open. Never blocks when Discord has sent nothing.
    fn healthy(&mut self) -> bool {
        for _ in 0..8 {
            match self.stream.pending() {
                Pending::Nothing => return true,
                Pending::Closed => return false,
                Pending::Data => match self.receive() {
                    Ok((3, ping)) => {
                        if self.send(4, &ping).is_err() {
                            return false;
                        }
                    }
                    Ok((2, _)) | Err(_) => return false,
                    Ok(_) => {}
                },
            }
        }
        true
    }
}

/// Debug builds: Discord's last answer to SET_ACTIVITY, printed by the self-test as evidence.
#[cfg(debug_assertions)]
pub static LAST_REPLY: Mutex<Option<Value>> = Mutex::new(None);

// ---------- the publisher (shared by the app and the keeper) ----------

/// What Discord should show: the game's process id and its activity.
type Desired = (u32, Value);

/// Keeps Discord showing `desired`: connects only when there is something to show, retries with
/// backoff, sends only changes, and disconnects (which also clears) when there's nothing to show.
struct Publisher {
    ipc: Option<Ipc>,
    sent: Option<Desired>,
    retry_at: Option<Instant>,
    attempt: usize,
    checked_at: Instant,
}

impl Publisher {
    fn new() -> Publisher {
        Publisher { ipc: None, sent: None, retry_at: None, attempt: 0, checked_at: Instant::now() }
    }

    fn drop_connection(&mut self, now: Instant) {
        self.ipc = None;
        self.sent = None;
        let wait = RETRY_SECS[self.attempt.min(RETRY_SECS.len() - 1)];
        self.attempt += 1;
        self.retry_at = Some(now + Duration::from_secs(wait));
    }

    /// Brings Discord in line with `desired` and reports the resulting status (for an enabled setting).
    fn apply(&mut self, desired: Option<&Desired>, now: Instant) -> Status {
        let Some((pid, activity)) = desired else {
            if let (Some(ipc), Some((pid, _))) = (self.ipc.as_mut(), self.sent.as_ref()) {
                let _ = ipc.set_activity(*pid, None);
            }
            self.ipc = None;
            self.sent = None;
            self.retry_at = None;
            self.attempt = 0;
            return Status::Idle;
        };
        if self.ipc.is_some() && now.duration_since(self.checked_at) >= HEARTBEAT {
            self.checked_at = now;
            if self.ipc.as_mut().is_some_and(|ipc| !ipc.healthy()) {
                // Discord quit or restarted: reconnect right away rather than after a backoff.
                self.ipc = None;
                self.sent = None;
                self.retry_at = None;
            }
        }
        if self.ipc.is_none() {
            if self.retry_at.is_some_and(|at| now < at) {
                return Status::Unavailable;
            }
            match Ipc::connect() {
                Ok(ipc) => {
                    self.ipc = Some(ipc);
                    self.attempt = 0;
                    self.retry_at = None;
                    self.checked_at = now;
                }
                Err(_error) => {
                    #[cfg(debug_assertions)]
                    eprintln!("[discord] connect failed: {_error}");
                    self.drop_connection(now);
                    return Status::Unavailable;
                }
            }
        }
        if self.sent.as_ref().is_some_and(|(p, a)| p == pid && a == activity) {
            return Status::Connected;
        }
        // Another game is now shown: take the activity off the previous game's process first.
        if let (Some(ipc), Some((old, _))) = (self.ipc.as_mut(), self.sent.as_ref()) {
            if old != pid && ipc.set_activity(*old, None).is_err() {
                self.drop_connection(now);
                return Status::Unavailable;
            }
        }
        match self.ipc.as_mut().map(|ipc| ipc.set_activity(*pid, Some(activity))) {
            Some(Ok(())) => {
                self.sent = Some((*pid, activity.clone()));
                Status::Connected
            }
            #[cfg_attr(not(debug_assertions), allow(unused_variables))]
            other => {
                #[cfg(debug_assertions)]
                eprintln!("[discord] update failed: {other:?}");
                self.drop_connection(now);
                Status::Unavailable
            }
        }
    }

    /// When the publisher next needs to act on its own (a retry or a heartbeat).
    fn next_wake(&self, now: Instant, showing: bool) -> Option<Instant> {
        if !showing {
            return None;
        }
        match (&self.ipc, self.retry_at) {
            (None, Some(at)) => Some(at.max(now)),
            (Some(_), _) => Some(self.checked_at + HEARTBEAT),
            _ => None,
        }
    }
}

// ---------- in the app ----------

enum Msg {
    Started(Session),
    Ended(String),
    Configure { enabled: bool, details: bool },
}

#[derive(Clone)]
struct Shared {
    status: Arc<Mutex<Status>>,
    sessions: Arc<Mutex<Vec<Session>>>,
    settings: Arc<Mutex<(bool, bool)>>,
}

/// Flint's Rich Presence, managed by Tauri. Cheap to call from anywhere; the work happens on its thread.
pub struct Presence {
    tx: Mutex<Sender<Msg>>,
    shared: Shared,
    root: PathBuf,
    handed_off: std::sync::atomic::AtomicBool,
}

/// The two settings: Rich Presence on (default) and showing the version and loader (default).
pub fn settings_from(settings: &Value) -> (bool, bool) {
    (settings["discordPresence"].as_bool().unwrap_or(true), settings["discordDetails"].as_bool().unwrap_or(true))
}

impl Presence {
    /// Starts the publisher thread. `on_status` is told whenever the status changes.
    pub fn start(root: &Path, settings: &Value, on_status: impl Fn(Status) + Send + 'static) -> Presence {
        let (enabled, details) = settings_from(settings);
        let shared = Shared {
            status: Arc::new(Mutex::new(if enabled { Status::Idle } else { Status::Off })),
            sessions: Arc::new(Mutex::new(Vec::new())),
            settings: Arc::new(Mutex::new((enabled, details))),
        };
        let (tx, rx) = mpsc::channel();
        // A keeper left from a previous run hands its sessions back to this one.
        let adopted = take_over_keeper(root);
        let worker = shared.clone();
        let _ = std::thread::Builder::new().name("flint-discord".into()).spawn(move || run(rx, worker, adopted, on_status));
        Presence { tx: Mutex::new(tx), shared, root: root.to_owned(), handed_off: std::sync::atomic::AtomicBool::new(false) }
    }

    fn send(&self, msg: Msg) {
        let _ = self.tx.lock().unwrap().send(msg);
    }

    /// The game process for profile `id` has started.
    pub fn started(&self, session: Session) {
        self.send(Msg::Started(session));
    }

    /// The game for profile `id` has exited (normally, crashed or was stopped).
    pub fn ended(&self, id: &str) {
        self.send(Msg::Ended(id.to_owned()));
    }

    pub fn configure(&self, settings: &Value) {
        let (enabled, details) = settings_from(settings);
        if *self.shared.settings.lock().unwrap() != (enabled, details) {
            self.send(Msg::Configure { enabled, details });
        }
    }

    pub fn status(&self) -> Status {
        *self.shared.status.lock().unwrap()
    }

    /// Flint is quitting: if games are still running and presence is on, a headless keeper takes over.
    pub fn hand_off(&self) {
        let sessions = self.shared.sessions.lock().unwrap().clone();
        let (enabled, details) = *self.shared.settings.lock().unwrap();
        // Only ever one keeper, however many times quitting is reported.
        if !enabled || sessions.is_empty() || self.handed_off.swap(true, std::sync::atomic::Ordering::SeqCst) {
            return;
        }
        let _ = spawn_keeper(&self.root, &sessions, details);
    }
}

fn run(rx: Receiver<Msg>, shared: Shared, adopted: Vec<Session>, on_status: impl Fn(Status)) {
    let mut sessions: Vec<(Session, Instant)> = Vec::new();
    // Adopted sessions were already running (and shown) before this run: no settle delay.
    let long_ago = Instant::now().checked_sub(SETTLE).unwrap_or_else(Instant::now);
    let mut watched: Vec<u32> = adopted.iter().map(|s| s.pid).collect();
    sessions.extend(adopted.into_iter().map(|s| (s, long_ago)));
    let mut publisher = Publisher::new();
    let mut status = *shared.status.lock().unwrap();
    // With no game running, Flint itself shows as idling, timed from when it opened.
    let opened_at = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_secs());
    let me = std::process::id();
    loop {
        let now = Instant::now();
        let (enabled, details) = *shared.settings.lock().unwrap();
        // Adopted games aren't watched by game.rs; check them while they last.
        if !watched.is_empty() {
            let before = sessions.len();
            sessions.retain(|(s, _)| !watched.contains(&s.pid) || process_alive(s.pid));
            if sessions.len() != before {
                watched.retain(|pid| sessions.iter().any(|(s, _)| s.pid == *pid));
            }
        }
        *shared.sessions.lock().unwrap() = sessions.iter().map(|(s, _)| s.clone()).collect();
        let game = if enabled { shown(&sessions, now).map(|s| (s.pid, activity(s, details))) } else { None };
        let showing_game = game.is_some();
        let desired = if enabled { game.or_else(|| Some((me, idle_activity(opened_at, details)))) } else { None };
        let next = if enabled {
            publisher.apply(desired.as_ref(), now)
        } else {
            // Off: clear anything shown and stay disconnected.
            publisher.apply(None, now);
            Status::Off
        };
        if next != status {
            status = next;
            *shared.status.lock().unwrap() = status;
            on_status(status);
        }

        // Sleep until a message, a session settling, a retry/heartbeat, or a watched game check.
        let settling = sessions.iter().map(|(_, since)| *since + SETTLE).filter(|at| *at > now).min();
        // A shown game's report is read again every few seconds (only a change is sent).
        let reread = (details && showing_game).then(|| now + REPORT_POLL);
        let wake = [settling, publisher.next_wake(now, desired.is_some()), (!watched.is_empty()).then(|| now + Duration::from_secs(5)), reread]
            .into_iter()
            .flatten()
            .min();
        let msg = match wake {
            Some(at) => match rx.recv_timeout(at.saturating_duration_since(Instant::now())) {
                Ok(msg) => Some(msg),
                Err(RecvTimeoutError::Timeout) => None,
                Err(RecvTimeoutError::Disconnected) => break,
            },
            None => match rx.recv() {
                Ok(msg) => Some(msg),
                Err(_) => break,
            },
        };
        match msg {
            Some(Msg::Started(session)) => {
                sessions.retain(|(s, _)| s.id != session.id);
                sessions.push((session, Instant::now()));
            }
            Some(Msg::Ended(id)) => sessions.retain(|(s, _)| s.id != id),
            Some(Msg::Configure { enabled, details }) => *shared.settings.lock().unwrap() = (enabled, details),
            None => {}
        }
    }
}

#[tauri::command]
pub fn discord_status(presence: tauri::State<'_, Presence>) -> Status {
    presence.status()
}

// ---------- the keeper (after Flint quits) ----------

#[derive(Serialize, Deserialize)]
struct KeeperState {
    pid: u32,
    details: bool,
    sessions: Vec<Session>,
}

/// Whether `pid` (read back from a file) names one process: `kill` takes 0 as Flint's own process group, 1 is
/// init, and an id past `pid_t` turns negative, a whole process group (or, as -1, every process the user owns).
#[cfg_attr(not(unix), allow(dead_code))] // only Unix signals processes
fn single_process(pid: u32) -> bool {
    pid > 1 && pid <= i32::MAX as u32
}

#[cfg(unix)]
pub fn process_alive(pid: u32) -> bool {
    if !single_process(pid) {
        return false;
    }
    // Signal 0 checks the process exists without touching it; EPERM still means it exists.
    let result = unsafe { libc::kill(pid as libc::pid_t, 0) };
    result == 0 || std::io::Error::last_os_error().raw_os_error() == Some(libc::EPERM)
}

#[cfg(not(unix))]
pub fn process_alive(_pid: u32) -> bool {
    false
}

#[cfg(unix)]
fn spawn_keeper(root: &Path, sessions: &[Session], details: bool) -> std::io::Result<()> {
    use std::os::unix::process::CommandExt;
    let exe = std::env::current_exe()?;
    let payload = serde_json::to_string(&json!({ "details": details, "sessions": sessions, "root": root })).map_err(std::io::Error::other)?;
    let mut command = std::process::Command::new(exe);
    command.arg(KEEPER_FLAG).arg(payload).stdin(std::process::Stdio::null()).stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null());
    // Its own session, so it isn't tied to a terminal or to Flint's process group.
    unsafe {
        command.pre_exec(|| {
            libc::setsid();
            Ok(())
        });
    }
    let child = command.spawn()?;
    let state = KeeperState { pid: child.id(), details, sessions: sessions.to_vec() };
    crate::core::write_json(&root.join(KEEPER_FILE), &state).map_err(std::io::Error::other)
}

#[cfg(not(unix))]
fn spawn_keeper(_root: &Path, _sessions: &[Session], _details: bool) -> std::io::Result<()> {
    // Not yet available on Windows: presence ends when Flint closes.
    Ok(())
}

/// Stops a keeper left by a previous run and returns its still-running sessions.
fn take_over_keeper(root: &Path) -> Vec<Session> {
    let path = root.join(KEEPER_FILE);
    let Some(state) = crate::core::read_json_opt::<KeeperState>(&path) else { return Vec::new() };
    let _ = std::fs::remove_file(&path);
    if !process_alive(state.pid) {
        return Vec::new();
    }
    #[cfg(unix)]
    unsafe {
        libc::kill(state.pid as libc::pid_t, libc::SIGTERM);
    }
    // Wait (briefly) for it to go, so its Discord connection is closed before this run opens one.
    for _ in 0..20 {
        if !process_alive(state.pid) {
            break;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    state.sessions.into_iter().filter(|s| process_alive(s.pid)).collect()
}

/// Returns true (after running the keeper) when this process was started as one.
pub fn run_keeper_if_requested() -> bool {
    let mut args = std::env::args().skip(1);
    if args.next().as_deref() != Some(KEEPER_FLAG) {
        return false;
    }
    if let Some(payload) = args.next().and_then(|p| serde_json::from_str::<Value>(&p).ok()) {
        let details = payload["details"].as_bool().unwrap_or(true);
        let sessions: Vec<Session> = serde_json::from_value(payload["sessions"].clone()).unwrap_or_default();
        let root = payload["root"].as_str().map(PathBuf::from);
        keep(sessions, details);
        // Only remove the hand-off file if it still names this keeper.
        if let Some(path) = root.map(|r| r.join(KEEPER_FILE)) {
            if crate::core::read_json_opt::<KeeperState>(&path).is_some_and(|s| s.pid == std::process::id()) {
                let _ = std::fs::remove_file(path);
            }
        }
    }
    true
}

/// Shows the newest live session until every game has exited, then clears and returns.
fn keep(mut sessions: Vec<Session>, details: bool) {
    let started = Instant::now();
    let mut publisher = Publisher::new();
    let long_ago = Instant::now().checked_sub(SETTLE).unwrap_or_else(Instant::now);
    while started.elapsed() < KEEPER_MAX {
        sessions.retain(|s| process_alive(s.pid));
        let now = Instant::now();
        let timed: Vec<(Session, Instant)> = sessions.iter().map(|s| (s.clone(), long_ago)).collect();
        let desired = shown(&timed, now).map(|s| (s.pid, activity(s, details)));
        publisher.apply(desired.as_ref(), now);
        if sessions.is_empty() {
            return;
        }
        std::thread::sleep(Duration::from_secs(3));
    }
    publisher.apply(None, Instant::now());
}

#[cfg(test)]
mod tests {
    use super::*;

    fn session(id: &str, version: &str, loader: &str, started_at: u64) -> Session {
        Session { id: id.into(), pid: 1, version: version.into(), loader: loader.into(), started_at, game_dir: None }
    }

    #[test]
    fn the_games_report_says_where_the_player_is() {
        let dir = std::env::temp_dir().join(format!("flint-presence-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let mut s = session("p", "26.3", "fabric", 1);
        s.game_dir = Some(dir.clone());
        let a = activity(&s, true);
        assert_eq!(a["details"], "Minecraft 26.3", "no report yet: the plain activity");
        std::fs::write(dir.join(REPORT_FILE), r#"{"version":"26.3","state":"Singleplayer","world":"New World","dimension":"Overworld","biome":"Plains","riding":"Horse"}"#).unwrap();
        let a = activity(&s, true);
        assert_eq!(a["details"], "Minecraft 26.3 · Singleplayer");
        assert_eq!(a["state"], "New World · Overworld · Plains · Riding Horse");
        std::fs::write(dir.join(REPORT_FILE), r#"{"version":"26.3","state":"Multiplayer","server":"power-1.mantranodes.fun:19195","status":"In Overworld"}"#).unwrap();
        let a = activity(&s, true);
        assert_eq!(a["details"], "Minecraft 26.3 · Multiplayer");
        assert_eq!(a["state"], "In Overworld", "a server's address never shows");
        std::fs::write(dir.join(REPORT_FILE), r#"{"version":"26.3","state":"Multiplayer","server":"Minemen Club"}"#).unwrap();
        assert_eq!(activity(&s, true)["state"], "Minemen Club");
        for address in ["127.0.0.1", "play.example.org", "10.0.0.2:25565", "[::1]:25565", "25565"] {
            assert!(looks_like_address(address), "{address}");
        }
        assert!(!looks_like_address("Hypixel"));
        assert_eq!(activity(&s, false)["details"], "Playing Minecraft", "details off: the report isn't shown");
        std::fs::write(dir.join(REPORT_FILE), r#"{"state":"In the menus"}"#).unwrap();
        let a = activity(&s, true);
        assert_eq!(a["details"], "In the menus");
        assert!(a.get("state").is_none());
        // A report from before this game started is an earlier run's.
        s.started_at = now_secs_for_test() + 3600;
        assert_eq!(activity(&s, true)["details"], "Minecraft 26.3");
        std::fs::remove_dir_all(&dir).unwrap();
        assert_eq!(line(&["x".repeat(200)]).chars().count(), LINE_MAX);
    }

    #[test]
    fn the_discord_mods_status_and_buttons_show() {
        let report = json!({"version":"26.3","state":"Singleplayer","status":"In Overworld","biome":"Plains",
            "buttons":[{"label":"Join us","url":"https://example.com"},{"label":"","url":"https://x"},{"label":"Bad","url":"ftp://x"}]});
        let (_, state) = described(&report);
        assert_eq!(state.as_deref(), Some("In Overworld · Plains"));
        assert_eq!(buttons(&report).unwrap(), json!([{"label":"Join us","url":"https://example.com"}]));
        assert!(buttons(&json!({})).is_none());
        let many = json!({"buttons":[{"label":"a","url":"https://a"},{"label":"b","url":"https://b"},{"label":"c","url":"https://c"}]});
        assert_eq!(buttons(&many).unwrap().as_array().unwrap().len(), 2, "Discord shows two at most");
    }

    fn now_secs_for_test() -> u64 {
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_secs()
    }

    #[test]
    fn the_activity_names_the_real_version_and_loader() {
        let a = activity(&session("p", "26.3", "fabric", 1_792_000_000), true);
        assert_eq!(a["details"], "Minecraft 26.3");
        assert_eq!(a["state"], "Fabric · Playing Minecraft");
        assert_eq!(a["timestamps"]["start"], 1_792_000_000u64);
        assert_eq!(a["assets"]["large_image"], "flint_logo");
        assert_eq!(a["assets"]["large_text"], "Fast where it matters.");
        assert_eq!(activity(&session("p", "1.21.11", "vanilla", 1), true)["state"], "Vanilla · Playing Minecraft");
        let private = activity(&session("p", "26.3", "fabric", 1), false);
        assert_eq!(private["details"], "Playing Minecraft");
        assert!(private.get("state").is_none(), "no version or loader when that's turned off");
    }

    #[test]
    fn flint_idles_while_no_game_runs() {
        let a = idle_activity(1_792_000_000, true);
        assert_eq!(a["details"], "In the launcher");
        assert_eq!(a["state"], "Idling");
        assert_eq!(a["timestamps"]["start"], 1_792_000_000u64);
        assert_eq!(a["assets"]["large_image"], "flint_logo");
        assert_eq!(idle_activity(1, false)["details"], "Flint Client", "details off: only that Flint is open");
    }

    #[test]
    fn the_newest_settled_game_is_shown() {
        let now = Instant::now();
        let old = now - SETTLE - Duration::from_secs(1);
        let sessions = vec![(session("a", "26.3", "fabric", 100), old), (session("b", "1.21.11", "vanilla", 200), old), (session("c", "26.2", "fabric", 300), now)];
        assert_eq!(shown(&sessions, now).unwrap().id, "b", "c hasn't settled yet, b is the newest that has");
        assert_eq!(shown(&sessions, now + SETTLE).unwrap().id, "c");
        assert!(shown(&[], now).is_none());
    }

    #[test]
    fn settings_default_to_on_and_follow_the_switches() {
        assert_eq!(settings_from(&json!({})), (true, true));
        assert_eq!(settings_from(&json!({ "discordPresence": false, "discordDetails": false })), (false, false));
    }

    #[cfg(unix)]
    #[test]
    fn frames_round_trip_and_a_refused_handshake_is_an_error() {
        let (a, b) = std::os::unix::net::UnixStream::pair().unwrap();
        let mut client = Ipc { stream: Box::new(a) };
        let mut server = Ipc { stream: Box::new(b) };
        let reply = std::thread::spawn(move || {
            let (op, hello) = server.receive().unwrap();
            assert_eq!(op, 0);
            assert_eq!(hello["client_id"], APPLICATION_ID);
            server.send(2, &json!({ "code": 4000, "message": "Invalid Client ID" })).unwrap();
        });
        let error = client.handshake().unwrap_err();
        assert!(error.to_string().contains("Invalid Client ID"));
        reply.join().unwrap();
    }

    /// A stand-in Discord on the other end of a socket pair: answers every SET_ACTIVITY and reports
    /// each one as (pid, activity) on the returned channel.
    #[cfg(unix)]
    fn fake_discord() -> (Ipc, mpsc::Receiver<(u64, Value)>) {
        let (a, b) = std::os::unix::net::UnixStream::pair().unwrap();
        a.set_read_timeout(Some(IO_TIMEOUT)).unwrap();
        let (tx, rx) = mpsc::channel();
        std::thread::spawn(move || {
            let mut server = Ipc { stream: Box::new(b) };
            while let Ok((op, request)) = server.receive() {
                if op != 1 {
                    continue;
                }
                let _ = tx.send((request["args"]["pid"].as_u64().unwrap_or(0), request["args"]["activity"].clone()));
                let reply = json!({ "cmd": "SET_ACTIVITY", "evt": null, "nonce": request["nonce"], "data": request["args"]["activity"] });
                if server.send(1, &reply).is_err() {
                    break;
                }
            }
        });
        (Ipc { stream: Box::new(a) }, rx)
    }

    #[cfg(unix)]
    #[test]
    fn the_activity_belongs_to_the_game_process_and_is_sent_only_on_change() {
        let (ipc, sent) = fake_discord();
        let mut publisher = Publisher::new();
        publisher.ipc = Some(ipc);
        let now = Instant::now();
        let game_a = (4242, activity(&session("a", "1.21.11", "fabric", 100), true));
        let game_b = (5151, activity(&session("b", "26.3", "fabric", 200), true));
        let next = |rx: &mpsc::Receiver<(u64, Value)>| rx.recv_timeout(Duration::from_secs(2)).expect("a SET_ACTIVITY");

        assert_eq!(publisher.apply(Some(&game_a), now), Status::Connected);
        let (pid, shown) = next(&sent);
        assert_eq!(pid, 4242, "published for the game's process, not Flint's");
        assert_eq!(shown["details"], "Minecraft 1.21.11");

        // Nothing changed: Discord isn't sent anything (no repeated updates, no rate limits).
        for _ in 0..5 {
            assert_eq!(publisher.apply(Some(&game_a), now), Status::Connected);
        }
        assert!(sent.recv_timeout(Duration::from_millis(200)).is_err());

        // Another game takes over: the first game's activity is cleared before the second is set.
        assert_eq!(publisher.apply(Some(&game_b), now), Status::Connected);
        assert_eq!(next(&sent), (4242, Value::Null));
        let (pid, shown) = next(&sent);
        assert_eq!((pid, shown["details"].as_str()), (5151, Some("Minecraft 26.3")));

        // No game left: cleared for the game that was shown, then disconnected.
        assert_eq!(publisher.apply(None, now), Status::Idle);
        assert_eq!(next(&sent), (5151, Value::Null));
        assert!(publisher.ipc.is_none());
    }

    #[cfg(unix)]
    #[test]
    fn pings_are_answered_and_a_closed_connection_is_noticed() {
        let (a, b) = std::os::unix::net::UnixStream::pair().unwrap();
        a.set_read_timeout(Some(IO_TIMEOUT)).unwrap();
        let mut client = Ipc { stream: Box::new(a) };
        let mut server = Ipc { stream: Box::new(b) };
        assert!(client.healthy(), "an idle connection is healthy");
        server.send(3, &json!({ "ping": 1 })).unwrap();
        assert!(client.healthy());
        let (op, pong) = server.receive().unwrap();
        assert_eq!((op, pong["ping"].as_u64()), (4, Some(1)), "a ping gets a pong with the same payload");
        drop(server);
        assert!(!client.healthy(), "Discord quitting is noticed");
    }

    #[cfg(unix)]
    #[test]
    fn our_own_process_counts_as_alive() {
        assert!(process_alive(std::process::id()));
        assert!(!process_alive(999_999));
    }

    #[test]
    fn a_pid_read_back_from_a_file_must_name_one_process() {
        assert!(single_process(std::process::id()));
        assert!(single_process(2) && single_process(i32::MAX as u32));
        // 0 is Flint's own process group, 1 is init, and past i32::MAX the id turns negative: a group, or (-1)
        // every process the user owns. None of them is ever a keeper or a game.
        for bad in [0, 1, i32::MAX as u32 + 1, u32::MAX] {
            assert!(!single_process(bad), "{bad}");
        }
    }

    #[cfg(unix)]
    #[test]
    fn a_corrupt_keeper_file_signals_nothing() {
        // `take_over_keeper` only signals a pid `process_alive` accepts; these would have reached many processes.
        for bad in [0, 1, u32::MAX] {
            assert!(!process_alive(bad), "{bad}");
        }
    }
}
