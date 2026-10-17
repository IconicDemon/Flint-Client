use reqwest::{Client, Response};
use serde::{de::DeserializeOwned, Serialize};
use serde_json::Value;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

pub const USER_AGENT: &str = concat!(
    "FlintClient/",
    env!("CARGO_PKG_VERSION"),
    " (+https://github.com/flintclient; desktop launcher)"
);

pub struct Paths {
    pub root: PathBuf,
}

impl Paths {
    pub fn settings(&self) -> PathBuf {
        self.root.join("settings.json")
    }
    pub fn onboarding(&self) -> PathBuf {
        self.root.join("onboarding.json")
    }
    pub fn accounts(&self) -> PathBuf {
        self.root.join("accounts.json")
    }
    pub fn profiles(&self) -> PathBuf {
        self.root.join("profiles.json")
    }
    pub fn profile_dir(&self, id: &str) -> PathBuf {
        self.root.join("profiles").join(id)
    }
    pub fn game(&self) -> PathBuf {
        self.root.join("minecraft")
    }
    pub fn runtimes(&self) -> PathBuf {
        self.root.join("runtimes")
    }
    pub fn cache(&self) -> PathBuf {
        self.root.join("cache")
    }
}

#[derive(Clone)]
pub struct CachedToken {
    pub token: String,
    pub expires: Instant,
}

pub struct RunningGame {
    pub kill: Option<tokio::sync::oneshot::Sender<()>>,
}

/// Lets concurrent callers share one in-flight operation per key. Whoever arrives while another
/// caller is working waits for it and then reuses its result (read back through `cached`) or its
/// failure, instead of repeating the work. Used so that simultaneous requests for an account's
/// token cause a single keychain read and a single Microsoft refresh.
#[derive(Default)]
pub struct SingleFlight {
    locks: Mutex<HashMap<String, Arc<tokio::sync::Mutex<()>>>>,
    failures: Mutex<HashMap<String, (Instant, String)>>,
}

impl SingleFlight {
    pub async fn run<T, Fut>(&self, key: &str, cached: impl Fn() -> Option<T>, work: impl FnOnce() -> Fut) -> CmdResult<T>
    where
        Fut: std::future::Future<Output = CmdResult<T>>,
    {
        if let Some(value) = cached() {
            return Ok(value);
        }
        let asked = Instant::now();
        let lock = self.locks.lock().unwrap().entry(key.to_owned()).or_default().clone();
        let _turn = lock.lock().await;
        if let Some(value) = cached() {
            return Ok(value);
        }
        if let Some((at, error)) = self.failures.lock().unwrap().get(key) {
            if *at >= asked {
                return Err(error.clone());
            }
        }
        let result = work().await;
        match &result {
            Ok(_) => {
                self.failures.lock().unwrap().remove(key);
            }
            Err(error) => {
                self.failures.lock().unwrap().insert(key.to_owned(), (Instant::now(), error.clone()));
            }
        }
        result
    }
}

pub struct AppState {
    pub http: Client,
    pub stream_http: Client,
    pub paths: Paths,
    pub qa: bool,
    pub force_onboarding: bool,
    pub keyring_service: String,
    pub file_lock: tokio::sync::Mutex<()>,
    pub sign_in_cancel: Mutex<Option<tokio::sync::oneshot::Sender<()>>>,
    pub minecraft_tokens: Mutex<HashMap<String, CachedToken>>,
    pub token_refresh: SingleFlight,
    pub credentials: Box<dyn crate::auth::CredentialStore>,
    /// Refresh tokens already read from (or written to) the credential store during this run.
    pub refresh_tokens: Mutex<HashMap<String, String>>,
    /// Accounts whose keychain read macOS refused this run; not retried automatically.
    pub keychain_blocked: Mutex<std::collections::HashSet<String>>,
    pub gateway_session: Mutex<Option<CachedToken>>,
    pub profile_states: Mutex<HashMap<String, Value>>,
    pub install_cancel: Mutex<HashMap<String, Arc<AtomicBool>>>,
    pub running: Mutex<HashMap<String, RunningGame>>,
    pub ai_cancel: Mutex<HashMap<String, tokio::sync::oneshot::Sender<()>>>,
    pub modrinth: crate::modrinth::Modrinth,
    pub jobs: crate::jobs::Jobs,
}

pub type CmdResult<T> = Result<T, String>;

pub fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

pub fn err<E: std::fmt::Display>(context: &str) -> impl Fn(E) -> String + '_ {
    move |error| format!("{context}: {error}")
}

pub fn read_json<T: DeserializeOwned + Default>(path: &Path) -> T {
    std::fs::read(path)
        .ok()
        .and_then(|bytes| serde_json::from_slice(&bytes).ok())
        .unwrap_or_default()
}

pub fn read_json_opt<T: DeserializeOwned>(path: &Path) -> Option<T> {
    std::fs::read(path)
        .ok()
        .and_then(|bytes| serde_json::from_slice(&bytes).ok())
}

pub fn write_json<T: Serialize>(path: &Path, value: &T) -> CmdResult<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(err("Could not create the data folder"))?;
    }
    let bytes = serde_json::to_vec_pretty(value).map_err(|e| e.to_string())?;
    let tmp = path.with_extension("json.tmp");
    std::fs::write(&tmp, bytes).map_err(err("Could not save data"))?;
    std::fs::rename(&tmp, path).map_err(err("Could not save data"))
}

/// Suffix of the temporary file `write_atomic` writes beside its target (`.<name>.flint-tmp`).
pub const TMP_SUFFIX: &str = ".flint-tmp";

/// Writes `bytes` to `path` so the file is always either the old one or the complete new one, even after a
/// crash or power cut: written beside it as `.<name>.flint-tmp`, flushed to disk, renamed over it, and the
/// folder flushed. The new file keeps the old one's permissions. A link is never replaced (that would quietly
/// unlink a file shared with something else). The temporary file never outlives a failed write; one left by
/// a crash is removed at the next start (`gamesettings::clean_interrupted_writes`).
pub fn write_atomic(path: &Path, bytes: &[u8]) -> CmdResult<()> {
    write_atomic_with(path, bytes, || Ok(()))
}

/// `write_atomic` with a step between writing the temporary file and renaming it, which tests use to
/// simulate a failure at that point.
pub fn write_atomic_with(path: &Path, bytes: &[u8], before_rename: impl FnOnce() -> std::io::Result<()>) -> CmdResult<()> {
    use std::io::Write;
    let name = path.file_name().and_then(|n| n.to_str()).ok_or("Invalid file name")?;
    let parent = path.parent().ok_or("Invalid file name")?;
    let existing = std::fs::symlink_metadata(path).ok();
    if existing.as_ref().is_some_and(|m| m.file_type().is_symlink()) {
        return Err(format!("{name} is a link to another file, so Flint won't replace it."));
    }
    std::fs::create_dir_all(parent).map_err(err("Could not create the folder"))?;
    let tmp = parent.join(format!(".{name}{TMP_SUFFIX}"));
    let written = (|| {
        let mut file = std::fs::File::create(&tmp)?;
        file.write_all(bytes)?;
        file.sync_all()?;
        drop(file);
        if let Some(meta) = &existing {
            std::fs::set_permissions(&tmp, meta.permissions())?;
        }
        before_rename()?;
        std::fs::rename(&tmp, path)?;
        sync_dir(parent);
        Ok::<_, std::io::Error>(())
    })();
    if written.is_err() {
        let _ = std::fs::remove_file(&tmp);
    }
    written.map_err(|e| format!("Could not save {name}: {e}"))
}

/// Flushes a folder's entries (a rename or a new file in it) to disk, where the system allows it.
pub fn sync_dir(dir: &Path) {
    #[cfg(unix)]
    if let Ok(handle) = std::fs::File::open(dir) {
        let _ = handle.sync_all();
    }
    #[cfg(not(unix))]
    let _ = dir;
}

/// The command lines of the processes running right now, read once per operation so several profiles can
/// be checked against one list.
pub struct ProcessScan {
    commands: Result<Vec<String>, String>,
}

impl ProcessScan {
    /// macOS and Linux: `ps -axww -o pid=,command=`. Windows has no such check yet; there only games this
    /// Flint session started (and profiles it is preparing) count as running.
    pub fn take() -> Self {
        #[cfg(unix)]
        {
            let run = |ps: &str| std::process::Command::new(ps).args(["-axww", "-o", "pid=,command="]).output();
            let commands = match run("/bin/ps").or_else(|_| run("ps")) {
                Ok(out) if out.status.success() => Ok(String::from_utf8_lossy(&out.stdout).lines().map(str::to_owned).collect()),
                Ok(out) => Err(format!("ps exited with {}", out.status)),
                Err(e) => Err(e.to_string()),
            };
            Self { commands }
        }
        #[cfg(not(unix))]
        {
            Self { commands: Ok(Vec::new()) }
        }
    }

    #[cfg(test)]
    pub fn from(commands: Result<Vec<String>, String>) -> Self {
        Self { commands }
    }

    /// Whether a Java process was started with `--gameDir <dir>`: a game that outlived a Flint restart, or
    /// one another launcher started on this folder.
    fn runs_in(&self, dir: &Path) -> Result<bool, String> {
        let commands = self.commands.as_ref().map_err(String::clone)?;
        let mut forms = vec![dir.display().to_string()];
        if let Ok(real) = dir.canonicalize() {
            forms.push(real.display().to_string());
        }
        Ok(commands.iter().any(|command| forms.iter().any(|form| game_dir_in_command(command, form))))
    }
}

/// True when `command` is a Java process given `--gameDir <dir>` (exactly that folder, with or without a
/// trailing slash).
pub fn game_dir_in_command(command: &str, dir: &str) -> bool {
    let dir = dir.trim_end_matches('/');
    let needle = format!("--gameDir {dir}");
    command.match_indices(&needle).any(|(at, _)| {
        let after = &command[at + needle.len()..];
        let after = after.strip_prefix('/').unwrap_or(after);
        let whole = after.is_empty() || after.starts_with(char::is_whitespace);
        whole && command[..at].to_ascii_lowercase().contains("java")
    })
}

/// Why a profile's files must not be written now: its game is running (or may be), or Flint is preparing
/// it. None when it is safe. Anything Flint can't rule out counts as running.
pub fn profile_running(state: &AppState, id: &str, scan: &ProcessScan) -> Option<String> {
    if state.running.lock().unwrap().contains_key(id) {
        return Some("Minecraft is running in this profile. Close it first, or Minecraft would overwrite these settings.".into());
    }
    if state.install_cancel.lock().unwrap().contains_key(id) {
        return Some("Flint is preparing this profile to play. Try again when it's done.".into());
    }
    started_elsewhere(state, id, scan)
}

/// The operating-system half of `profile_running`, for a profile Flint already holds (`ProfileHold`).
pub fn started_elsewhere(state: &AppState, id: &str, scan: &ProcessScan) -> Option<String> {
    match scan.runs_in(&state.paths.profile_dir(id)) {
        Ok(false) => None,
        Ok(true) => Some("Minecraft is running in this profile (started before Flint opened, or by another launcher). Close it first, or Minecraft would overwrite these settings.".into()),
        Err(e) => Some(format!("Flint couldn't check whether Minecraft is running in this profile ({e}), so it won't change its files.")),
    }
}

pub fn random_hex(bytes: usize) -> String {
    use rand::RngCore;
    let mut buf = vec![0u8; bytes];
    rand::thread_rng().fill_bytes(&mut buf);
    hex::encode(buf)
}

pub fn is_safe_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 64
        && id
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

pub async fn response_json(response: Response, context: &str) -> CmdResult<Value> {
    let status = response.status();
    let body = response.text().await.map_err(|e| format!("{context}: {e}"))?;
    if !status.is_success() {
        let detail = serde_json::from_str::<Value>(&body)
            .ok()
            .and_then(|value| {
                value
                    .get("error_description")
                    .or_else(|| value.get("errorMessage"))
                    .or_else(|| value.get("message"))
                    .or_else(|| value.get("description"))
                    .and_then(Value::as_str)
                    .map(str::to_owned)
            })
            .unwrap_or_else(|| format!("HTTP {}", status.as_u16()));
        return Err(format!("{context}: {detail}"));
    }
    serde_json::from_str(&body).map_err(|e| format!("{context}: {e}"))
}

pub async fn get_json(http: &Client, url: &str, context: &str) -> CmdResult<Value> {
    let response = http
        .get(url)
        .header(reqwest::header::USER_AGENT, USER_AGENT)
        .header(reqwest::header::ACCEPT, "application/json")
        .send()
        .await
        .map_err(|e| format!("{context}: {}", friendly_network(&e)))?;
    response_json(response, context).await
}

pub fn friendly_network(error: &reqwest::Error) -> String {
    if error.is_timeout() {
        "the request timed out".into()
    } else if error.is_connect() {
        "could not connect (check your internet connection)".into()
    } else {
        error.to_string()
    }
}

/// Redirects may never downgrade to plain HTTP. Downloads may additionally only be redirected to
/// hosts that are trusted for downloads; file contents are still verified against their hashes.
fn redirect_policy(hosts: Option<&'static [&'static str]>) -> reqwest::redirect::Policy {
    reqwest::redirect::Policy::custom(move |attempt| {
        let url = attempt.url();
        if attempt.previous().len() >= 5 {
            return attempt.error("too many redirects");
        }
        let host_ok = hosts.is_none_or(|list| url.host_str().is_some_and(|h| list.contains(&h)));
        if url.scheme() != "https" || !host_ok {
            return attempt.stop();
        }
        attempt.follow()
    })
}

pub const DOWNLOAD_REDIRECT_HOSTS: &[&str] = &[
    "piston-data.mojang.com",
    "piston-meta.mojang.com",
    "launchermeta.mojang.com",
    "launcher.mojang.com",
    "libraries.minecraft.net",
    "resources.download.minecraft.net",
    "maven.fabricmc.net",
    "cdn.modrinth.com",
    "github.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
    "raw.githubusercontent.com",
    "gitlab.com",
];

pub fn build_clients() -> (Client, Client) {
    let http = Client::builder()
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(45))
        .user_agent(USER_AGENT)
        .redirect(redirect_policy(None))
        .build()
        .expect("HTTP client configuration is valid");
    let stream_http = Client::builder()
        .connect_timeout(Duration::from_secs(10))
        .read_timeout(Duration::from_secs(60))
        .user_agent(USER_AGENT)
        .redirect(redirect_policy(Some(DOWNLOAD_REDIRECT_HOSTS)))
        .build()
        .expect("HTTP client configuration is valid");
    (http, stream_http)
}

pub fn mojang_os() -> &'static str {
    match std::env::consts::OS {
        "macos" => "osx",
        "windows" => "windows",
        _ => "linux",
    }
}

#[cfg(test)]
impl AppState {
    /// A real application state rooted in a throwaway directory, for tests.
    pub fn for_tests(root: PathBuf) -> Self {
        let (http, stream_http) = build_clients();
        Self {
            http,
            stream_http,
            paths: Paths { root },
            qa: false,
            force_onboarding: false,
            keyring_service: "com.flintclient.desktop.unit-test".into(),
            file_lock: tokio::sync::Mutex::new(()),
            sign_in_cancel: Mutex::new(None),
            minecraft_tokens: Mutex::new(HashMap::new()),
            token_refresh: SingleFlight::default(),
            credentials: Box::new(MemoryCredentials::default()),
            refresh_tokens: Mutex::new(HashMap::new()),
            keychain_blocked: Mutex::new(Default::default()),
            gateway_session: Mutex::new(None),
            profile_states: Mutex::new(HashMap::new()),
            install_cancel: Mutex::new(HashMap::new()),
            running: Mutex::new(HashMap::new()),
            ai_cancel: Mutex::new(HashMap::new()),
            modrinth: crate::modrinth::Modrinth::new(false),
            jobs: crate::jobs::Jobs::default(),
        }
    }
}

/// An in-memory credential store for tests. Counts reads and can be told to refuse access, like a
/// denied macOS keychain prompt.
#[cfg(test)]
#[derive(Default)]
pub struct MemoryCredentials {
    pub secrets: Mutex<HashMap<String, String>>,
    pub reads: std::sync::atomic::AtomicUsize,
    pub writes: std::sync::atomic::AtomicUsize,
    pub refuse: AtomicBool,
    pub refuse_writes: AtomicBool,
}

#[cfg(test)]
impl crate::auth::CredentialStore for std::sync::Arc<MemoryCredentials> {
    fn read(&self, _service: &str, account: &str) -> Result<Option<String>, String> {
        self.reads.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        if self.refuse.load(std::sync::atomic::Ordering::SeqCst) {
            return Err("User canceled the operation.".into());
        }
        Ok(self.secrets.lock().unwrap().get(account).cloned())
    }
    fn write(&self, _service: &str, account: &str, secret: &str) -> Result<(), String> {
        self.writes.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        if self.refuse_writes.load(std::sync::atomic::Ordering::SeqCst) {
            return Err("The specified item already exists in the keychain.".into());
        }
        self.secrets.lock().unwrap().insert(account.to_owned(), secret.to_owned());
        Ok(())
    }
    fn delete(&self, _service: &str, account: &str) -> Result<(), String> {
        self.secrets.lock().unwrap().remove(account);
        Ok(())
    }
}

#[cfg(test)]
impl crate::auth::CredentialStore for MemoryCredentials {
    fn read(&self, _service: &str, account: &str) -> Result<Option<String>, String> {
        Ok(self.secrets.lock().unwrap().get(account).cloned())
    }
    fn write(&self, _service: &str, account: &str, secret: &str) -> Result<(), String> {
        self.secrets.lock().unwrap().insert(account.to_owned(), secret.to_owned());
        Ok(())
    }
    fn delete(&self, _service: &str, account: &str) -> Result<(), String> {
        self.secrets.lock().unwrap().remove(account);
        Ok(())
    }
}

/// A unique empty directory under the system temp folder, removed when dropped.
#[cfg(test)]
pub struct TestDir(pub PathBuf);

#[cfg(test)]
impl TestDir {
    pub fn new() -> Self {
        let dir = std::env::temp_dir().join(format!("flint-test-{}", random_hex(8)));
        std::fs::create_dir_all(&dir).unwrap();
        Self(dir)
    }
}

#[cfg(test)]
impl Drop for TestDir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    #[tokio::test]
    async fn single_flight_shares_one_refresh_between_concurrent_callers() {
        let flight = Arc::new(SingleFlight::default());
        let cache: Arc<Mutex<Option<String>>> = Arc::new(Mutex::new(None));
        let calls = Arc::new(AtomicUsize::new(0));
        let mut tasks = Vec::new();
        for _ in 0..8 {
            let (flight, cache, calls) = (flight.clone(), cache.clone(), calls.clone());
            tasks.push(tokio::spawn(async move {
                let read = cache.clone();
                flight
                    .run("account", move || read.lock().unwrap().clone(), || async move {
                        calls.fetch_add(1, Ordering::SeqCst);
                        tokio::time::sleep(Duration::from_millis(40)).await;
                        *cache.lock().unwrap() = Some("token-1".into());
                        Ok("token-1".to_string())
                    })
                    .await
            }));
        }
        for task in tasks {
            assert_eq!(task.await.unwrap().unwrap(), "token-1");
        }
        assert_eq!(calls.load(Ordering::SeqCst), 1, "the refresh must run exactly once");
    }

    #[tokio::test]
    async fn single_flight_shares_a_failure_with_waiting_callers_but_retries_later() {
        let flight = Arc::new(SingleFlight::default());
        let calls = Arc::new(AtomicUsize::new(0));
        let mut tasks = Vec::new();
        for _ in 0..5 {
            let (flight, calls) = (flight.clone(), calls.clone());
            tasks.push(tokio::spawn(async move {
                flight
                    .run("account", || None::<String>, || async move {
                        calls.fetch_add(1, Ordering::SeqCst);
                        tokio::time::sleep(Duration::from_millis(40)).await;
                        Err("offline".to_string())
                    })
                    .await
            }));
        }
        for task in tasks {
            assert_eq!(task.await.unwrap().unwrap_err(), "offline");
        }
        assert_eq!(calls.load(Ordering::SeqCst), 1, "callers waiting on a failed refresh reuse its error");

        // A request made after the failure is a new attempt.
        let again = flight.run("account", || None::<String>, || async { Ok("token-2".to_string()) }).await;
        assert_eq!(again.unwrap(), "token-2");
    }

    #[tokio::test]
    async fn single_flight_keys_are_independent() {
        let flight = SingleFlight::default();
        let a = flight.run("a", || None::<u8>, || async { Ok(1) }).await.unwrap();
        let b = flight.run("b", || None::<u8>, || async { Ok(2) }).await.unwrap();
        assert_eq!((a, b), (1, 2));
    }

    #[test]
    fn safe_ids() {
        assert!(is_safe_id("9296a900e257"));
        assert!(is_safe_id("a-b_c"));
        assert!(!is_safe_id(""));
        assert!(!is_safe_id("../x"));
        assert!(!is_safe_id("a/b"));
        assert!(!is_safe_id(&"a".repeat(65)));
    }

    #[test]
    fn write_atomic_replaces_the_file_and_leaves_no_temporary_file() {
        let dir = TestDir::new();
        let path = dir.0.join("nested/options.txt");
        write_atomic(&path, b"version:5023\n").unwrap();
        write_atomic(&path, b"version:5023\nfov:0.5\n").unwrap();
        assert_eq!(std::fs::read(&path).unwrap(), b"version:5023\nfov:0.5\n");
        assert!(!dir.0.join("nested/.options.txt.flint-tmp").exists());
    }

    #[test]
    fn a_failure_before_the_rename_keeps_the_original_and_removes_the_temporary_file() {
        let dir = TestDir::new();
        let path = dir.0.join("options.txt");
        std::fs::write(&path, b"original").unwrap();
        let tmp = dir.0.join(".options.txt.flint-tmp");
        let result = write_atomic_with(&path, b"replacement", || {
            assert_eq!(std::fs::read(&tmp).unwrap(), b"replacement", "the new bytes are on disk beside the file");
            Err(std::io::Error::other("simulated power cut"))
        });
        assert!(result.unwrap_err().contains("simulated power cut"));
        assert_eq!(std::fs::read(&path).unwrap(), b"original");
        assert!(!tmp.exists());
    }

    #[cfg(unix)]
    #[test]
    fn write_atomic_keeps_permissions_and_never_replaces_a_link() {
        use std::os::unix::fs::PermissionsExt;
        let dir = TestDir::new();
        let path = dir.0.join("options.txt");
        std::fs::write(&path, b"original").unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600)).unwrap();
        write_atomic(&path, b"replacement").unwrap();
        assert_eq!(std::fs::metadata(&path).unwrap().permissions().mode() & 0o777, 0o600);
        let shared = dir.0.join("shared.txt");
        std::fs::write(&shared, b"shared").unwrap();
        let link = dir.0.join("linked.txt");
        std::os::unix::fs::symlink(&shared, &link).unwrap();
        assert!(write_atomic(&link, b"new").unwrap_err().contains("link"));
        assert_eq!(std::fs::read(&shared).unwrap(), b"shared");
        assert!(std::fs::symlink_metadata(&link).unwrap().file_type().is_symlink());
        assert!(!dir.0.join(".linked.txt.flint-tmp").exists());
    }

    #[test]
    fn a_java_process_counts_only_for_its_exact_game_folder() {
        let dir = "/Users/x/Library/Application Support/com.flintclient.desktop/profiles/abc123";
        let java = format!("4242 /Users/x/Library/Application Support/com.flintclient.desktop/runtimes/java-runtime-epsilon/bin/java -Xmx4G net.fabricmc.loader.impl.launch.knot.KnotClient --username Steve --gameDir {dir} --assetsDir /a");
        assert!(game_dir_in_command(&java, dir));
        assert!(game_dir_in_command(&format!("1 /usr/bin/java --gameDir {dir}"), dir), "at the end of the line");
        assert!(game_dir_in_command(&format!("1 /usr/bin/java --gameDir {dir}/ --width 854"), dir), "with a trailing slash");
        assert!(!game_dir_in_command(&format!("1 /usr/bin/java --gameDir {dir}4 --width 854"), dir), "another profile whose id starts the same");
        assert!(!game_dir_in_command(&format!("1 /usr/bin/grep --gameDir {dir}"), dir), "not Java");
        assert!(!game_dir_in_command("1 /usr/bin/java --gameDir /elsewhere", dir));
    }

    #[test]
    fn running_profiles_are_found_in_memory_and_in_the_process_list() {
        let dir = TestDir::new();
        let state = AppState::for_tests(dir.0.clone());
        let none = ProcessScan::from(Ok(Vec::new()));
        assert_eq!(profile_running(&state, "p1", &none), None);
        state.running.lock().unwrap().insert("p1".into(), RunningGame { kill: None });
        assert!(profile_running(&state, "p1", &none).unwrap().contains("running"));
        state.running.lock().unwrap().clear();
        state.install_cancel.lock().unwrap().insert("p1".into(), Arc::new(AtomicBool::new(false)));
        assert!(profile_running(&state, "p1", &none).unwrap().contains("preparing"));
        state.install_cancel.lock().unwrap().clear();
        let game = format!("77 /opt/java/bin/java -cp x Main --gameDir {}", state.paths.profile_dir("p1").display());
        let scan = ProcessScan::from(Ok(vec![game]));
        assert!(profile_running(&state, "p1", &scan).unwrap().contains("another launcher"));
        assert_eq!(profile_running(&state, "p2", &scan), None);
        let failed = ProcessScan::from(Err("ps: not permitted".into()));
        assert!(profile_running(&state, "p2", &failed).unwrap().contains("couldn't check"), "doubt counts as running");
    }

    #[test]
    fn write_json_is_atomic_and_readable() {
        let dir = TestDir::new();
        let path = dir.0.join("nested/file.json");
        write_json(&path, &serde_json::json!({ "a": 1 })).unwrap();
        assert!(!path.with_extension("json.tmp").exists());
        let back: Value = read_json(&path);
        assert_eq!(back["a"], 1);
    }
}
