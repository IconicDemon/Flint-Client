use crate::core::*;
use futures_util::StreamExt;
use serde::Serialize;
use serde_json::Value;
use sha1::Sha1;
use sha2::{Digest, Sha512};
use std::collections::HashMap;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tauri::{AppHandle, Emitter, Manager, State};

pub const PART_SUFFIX: &str = ".flint-part";

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct Job {
    pub id: String,
    pub kind: String,
    pub profile_id: Option<String>,
    pub title: String,
    pub state: String,
    pub stage: String,
    pub file: Option<String>,
    pub bytes_done: u64,
    pub bytes_total: Option<u64>,
    pub items_done: usize,
    pub items_total: usize,
    pub message: Option<String>,
    pub result: Option<Value>,
    pub created_at: u64,
}

pub struct Jobs {
    map: Mutex<Vec<Job>>,
    cancel: Mutex<HashMap<String, Arc<AtomicBool>>>,
    last_emit: Mutex<HashMap<String, Instant>>,
    pub slots: Arc<tokio::sync::Semaphore>,
}

impl Default for Jobs {
    fn default() -> Self {
        Self {
            map: Mutex::new(Vec::new()),
            cancel: Mutex::new(HashMap::new()),
            last_emit: Mutex::new(HashMap::new()),
            slots: Arc::new(tokio::sync::Semaphore::new(2)),
        }
    }
}

impl Jobs {
    pub fn create(&self, app: &AppHandle, kind: &str, profile_id: Option<&str>, title: &str, items_total: usize) -> (String, Arc<AtomicBool>) {
        let id = format!("j{}", random_hex(5));
        let job = Job {
            id: id.clone(),
            kind: kind.to_owned(),
            profile_id: profile_id.map(str::to_owned),
            title: title.chars().take(120).collect(),
            state: "queued".into(),
            stage: "Waiting".into(),
            file: None,
            bytes_done: 0,
            bytes_total: None,
            items_done: 0,
            items_total,
            message: None,
            result: None,
            created_at: now_secs(),
        };
        let flag = Arc::new(AtomicBool::new(false));
        {
            let mut map = self.map.lock().unwrap();
            map.retain(|j| j.state == "queued" || j.state == "running" || now_secs().saturating_sub(j.created_at) < 3600);
            if map.len() > 60 {
                map.remove(0);
            }
            map.push(job.clone());
        }
        self.cancel.lock().unwrap().insert(id.clone(), flag.clone());
        let _ = app.emit("job", &job);
        (id, flag)
    }

    /// Applies a change and emits it. Byte-progress updates are throttled; state changes are not.
    pub fn update(&self, app: &AppHandle, id: &str, throttle: bool, f: impl FnOnce(&mut Job)) {
        let snapshot = {
            let mut map = self.map.lock().unwrap();
            let Some(job) = map.iter_mut().find(|j| j.id == id) else { return };
            f(job);
            job.clone()
        };
        if throttle {
            let mut last = self.last_emit.lock().unwrap();
            let now = Instant::now();
            if last.get(id).is_some_and(|t| now.duration_since(*t) < Duration::from_millis(140)) {
                return;
            }
            last.insert(id.to_owned(), now);
        }
        let _ = app.emit("job", &snapshot);
    }

    pub fn finish(&self, app: &AppHandle, id: &str, outcome: &CmdResult<Value>) {
        let cancelled = self.is_cancelled(id);
        self.update(app, id, false, |job| match outcome {
            Ok(result) => {
                job.state = "done".into();
                job.stage = "Done".into();
                job.result = Some(result.clone());
            }
            Err(_) if cancelled => {
                job.state = "cancelled".into();
                job.stage = "Cancelled".into();
                job.message = Some("Cancelled. Nothing partial was kept.".into());
            }
            Err(e) => {
                job.state = "failed".into();
                job.stage = "Failed".into();
                job.message = Some(e.clone());
            }
        });
        self.cancel.lock().unwrap().remove(id);
        self.last_emit.lock().unwrap().remove(id);
    }

    pub fn is_cancelled(&self, id: &str) -> bool {
        self.cancel.lock().unwrap().get(id).is_some_and(|f| f.load(Ordering::Relaxed))
    }

    pub fn list(&self) -> Vec<Job> {
        self.map.lock().unwrap().clone()
    }

    pub fn cancel(&self, id: &str) {
        if let Some(flag) = self.cancel.lock().unwrap().get(id) {
            flag.store(true, Ordering::Relaxed);
        }
    }

    pub fn dismiss(&self, id: &str) {
        self.map.lock().unwrap().retain(|j| j.id != id || j.state == "queued" || j.state == "running");
    }
}

pub enum Expected<'a> {
    Sha512(&'a str),
    Sha1(&'a str),
}

pub struct Fetch<'a> {
    pub url: &'a str,
    pub expected: Expected<'a>,
    pub max_bytes: u64,
    pub allowed_hosts: &'a [&'a str],
}

pub fn host_allowed(url: &str, hosts: &[&str]) -> CmdResult<url::Url> {
    let parsed = url::Url::parse(url).map_err(|_| "Invalid download address".to_string())?;
    if parsed.scheme() != "https" || !parsed.host_str().is_some_and(|h| hosts.contains(&h)) {
        return Err(format!("Refused a download from an unverified source ({})", parsed.host_str().unwrap_or("unknown")));
    }
    Ok(parsed)
}

/// Streams a file to `<target>.flint-part`, verifies its published hash, then moves it into place.
/// A partial or mismatched file is always removed, so an interrupted download never looks installed.
pub async fn download_verified(app: &AppHandle, state: &AppState, job: &str, fetch: Fetch<'_>, target: &Path) -> CmdResult<u64> {
    let parsed = host_allowed(fetch.url, fetch.allowed_hosts)?;
    let file_name = target.file_name().and_then(|n| n.to_str()).unwrap_or("file").to_owned();
    let part = part_path(target);
    if let Some(parent) = target.parent() {
        std::fs::create_dir_all(parent).map_err(err("Could not create the folder"))?;
    }
    let response = state
        .stream_http
        .get(parsed)
        .send()
        .await
        .map_err(|e| friendly_network(&e))?
        .error_for_status()
        .map_err(|e| format!("Download failed: {e}"))?;
    let total = response.content_length();
    if total.is_some_and(|t| t > fetch.max_bytes) {
        return Err(format!("{file_name} is larger than Flint allows for this kind of file"));
    }
    state.jobs.update(app, job, false, |j| {
        j.state = "running".into();
        j.stage = "Downloading".into();
        j.file = Some(file_name.clone());
        j.bytes_done = 0;
        j.bytes_total = total;
    });
    let result: CmdResult<u64> = async {
        let mut out = std::fs::File::create(&part).map_err(err("Could not write the download"))?;
        let mut sha512 = Sha512::new();
        let mut sha1 = Sha1::new();
        let mut done: u64 = 0;
        let mut stream = response.bytes_stream();
        while let Some(chunk) = stream.next().await {
            if state.jobs.is_cancelled(job) {
                return Err("Cancelled".into());
            }
            let chunk = chunk.map_err(|e| friendly_network(&e))?;
            done += chunk.len() as u64;
            if done > fetch.max_bytes {
                return Err(format!("{file_name} is larger than Flint allows for this kind of file"));
            }
            sha512.update(&chunk);
            sha1.update(&chunk);
            out.write_all(&chunk).map_err(err("Could not write the download"))?;
            state.jobs.update(app, job, true, |j| j.bytes_done = done);
        }
        out.flush().map_err(err("Could not write the download"))?;
        drop(out);
        state.jobs.update(app, job, false, |j| {
            j.stage = "Verifying".into();
            j.bytes_done = done;
        });
        let ok = match fetch.expected {
            Expected::Sha512(h) => hex::encode(sha512.finalize()).eq_ignore_ascii_case(h),
            Expected::Sha1(h) => hex::encode(sha1.finalize()).eq_ignore_ascii_case(h),
        };
        if !ok {
            return Err(format!("{file_name} failed its integrity check, so it wasn't installed"));
        }
        std::fs::rename(&part, target).map_err(err("Could not finish installing"))?;
        Ok(done)
    }
    .await;
    if result.is_err() {
        let _ = std::fs::remove_file(&part);
    }
    result
}

pub fn part_path(target: &Path) -> PathBuf {
    let name = target.file_name().and_then(|n| n.to_str()).unwrap_or("file");
    target.with_file_name(format!(".{name}{PART_SUFFIX}"))
}

/// Removes leftovers from downloads that were interrupted by a crash or forced quit.
pub fn clean_partials(root: &Path) {
    let Ok(profiles) = std::fs::read_dir(root.join("profiles")) else { return };
    for profile in profiles.flatten() {
        let dir = profile.path();
        let name = profile.file_name().to_string_lossy().to_string();
        if name.ends_with(".flint-importing") && dir.is_dir() {
            let _ = std::fs::remove_dir_all(&dir);
            continue;
        }
        let mut folders = vec![dir.join("mods"), dir.join("resourcepacks"), dir.join("shaderpacks")];
        if let Ok(worlds) = std::fs::read_dir(dir.join("saves")) {
            folders.extend(worlds.flatten().map(|w| w.path().join("datapacks")));
        }
        for folder in folders {
            if let Ok(entries) = std::fs::read_dir(&folder) {
                for entry in entries.flatten() {
                    if entry.file_name().to_string_lossy().ends_with(PART_SUFFIX) {
                        let _ = std::fs::remove_file(entry.path());
                    }
                }
            }
        }
    }
}

/// Runs a job in the background, limited to a couple at a time so installs never saturate the network.
pub fn spawn<F, Fut>(app: &AppHandle, id: String, work: F)
where
    F: FnOnce(AppHandle, String) -> Fut + Send + 'static,
    Fut: std::future::Future<Output = CmdResult<Value>> + Send + 'static,
{
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let state = app.state::<AppState>();
        let permit = state.jobs.slots.clone().acquire_owned().await;
        let outcome = if state.jobs.is_cancelled(&id) {
            Err("Cancelled".to_string())
        } else {
            // Run as its own task so a panic inside the work still finishes the job (as failed)
            // instead of leaving it "running" forever.
            tauri::async_runtime::spawn(work(app.clone(), id.clone()))
                .await
                .unwrap_or_else(|_| Err("Something went wrong while working on this. Try again.".to_string()))
        };
        drop(permit);
        let state = app.state::<AppState>();
        state.jobs.finish(&app, &id, &outcome);
    });
}

#[tauri::command]
pub fn jobs_list(state: State<'_, AppState>) -> Vec<Job> {
    state.jobs.list()
}

#[tauri::command]
pub fn job_cancel(state: State<'_, AppState>, id: String) {
    state.jobs.cancel(&id);
}

#[tauri::command]
pub fn job_dismiss(state: State<'_, AppState>, id: String) {
    state.jobs.dismiss(&id);
}
