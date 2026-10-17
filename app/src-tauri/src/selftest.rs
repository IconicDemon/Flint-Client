use crate::core::AppState;
use crate::{auth, content, data, game};
use serde_json::{json, Value};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tauri::{AppHandle, Listener, Manager};

fn installable(plan: &Value) -> Vec<content::InstallItem> {
    plan["items"]
        .as_array()
        .into_iter()
        .flatten()
        .filter(|i| i["status"] == "ok")
        .filter_map(|i| serde_json::from_value(json!({ "versionId": i["versionId"], "kind": i["kind"], "source": i["source"] })).ok())
        .collect()
}

/// `FLINT_SELFTEST=resolve:<version>:<requests.json>` runs only Flint's real Modrinth resolver (the
/// one behind "Find mods with Flint") on a list of requests and prints the checked plan as one JSON
/// line. Nothing is installed. Used to verify live AI answers end to end.
fn resolve_only(app: AppHandle, version: String, file: String) {
    tauri::async_runtime::spawn(async move {
        let requests: Result<Vec<content::ResolveRequest>, String> = std::fs::read(&file).map_err(|e| e.to_string()).and_then(|b| serde_json::from_slice(&b).map_err(|e| e.to_string()));
        let result = match requests {
            Ok(requests) => content::mods_resolve(app.state(), None, Some(version), requests).await,
            Err(e) => Err(e),
        };
        println!("[selftest] plan: {}", result.unwrap_or_else(|e| json!({ "error": e })));
        app.exit(0);
    });
}

/// Every `profile-state` and `job` event, with milliseconds since the test started, plus markers
/// the test adds around each command (`{"ev":"mark"}`).
#[derive(Clone)]
struct Recorder {
    start: Instant,
    events: Arc<Mutex<Vec<Value>>>,
}

impl Recorder {
    fn new(app: &AppHandle) -> Self {
        let rec = Recorder { start: Instant::now(), events: Arc::default() };
        for name in ["profile-state", "job"] {
            let rec = rec.clone();
            app.listen_any(name, move |event| {
                let mut value: Value = serde_json::from_str(event.payload()).unwrap_or_default();
                value["t"] = json!(rec.start.elapsed().as_millis() as u64);
                value["ev"] = json!(name);
                rec.events.lock().unwrap().push(value);
            });
        }
        rec
    }

    fn mark(&self, name: &str) {
        self.events.lock().unwrap().push(json!({ "t": self.start.elapsed().as_millis() as u64, "ev": "mark", "name": name }));
    }

    fn latest(&self, id: &str) -> Option<Value> {
        self.events.lock().unwrap().iter().rev().find(|e| e["ev"] == "profile-state" && e["profileId"] == id).cloned()
    }

    /// Events since the marker `name`.
    fn since(&self, name: &str) -> Vec<Value> {
        let events = self.events.lock().unwrap();
        let start = events.iter().rposition(|e| e["ev"] == "mark" && e["name"] == name).unwrap_or(0);
        events[start..].to_vec()
    }
}

const BUSY: [&str; 4] = ["checking", "downloading", "installing", "launching"];

/// Looks for the glitches the interface showed: progress going backwards within one download,
/// a state that switches away and back within a second, busy states left at the end, events that
/// arrive after a command already returned, and labels that change while the state stays the same.
fn analyse(events: &[Value]) -> Value {
    let states: Vec<&Value> = events.iter().filter(|e| e["ev"] == "profile-state").collect();
    let mut backwards = Vec::new();
    let mut flaps = Vec::new();
    let mut label_changes = 0;
    let unordered = states.windows(2).filter(|p| p[1]["seq"].as_u64() < p[0]["seq"].as_u64()).count();
    for pair in states.windows(2) {
        let (a, b) = (pair[0], pair[1]);
        if a["state"] == "downloading" && b["state"] == "downloading" && b["bytesDone"].as_u64() < a["bytesDone"].as_u64() {
            backwards.push(json!([b["t"], a["bytesDone"], b["bytesDone"]]));
        }
        if a["state"] == b["state"] && a["label"] != b["label"] {
            label_changes += 1;
        }
    }
    for triple in states.windows(3) {
        let (a, b, c) = (triple[0], triple[1], triple[2]);
        if a["state"] == c["state"] && a["state"] != b["state"] && c["t"].as_u64().unwrap_or(0).saturating_sub(a["t"].as_u64().unwrap_or(0)) < 1000 {
            flaps.push(json!([a["t"], a["state"], b["state"], c["state"]]));
        }
    }
    // Events after a command returned (marker "<step> returned") and before the next marker.
    let mut late = Vec::new();
    let mut after_return = false;
    for e in events {
        if e["ev"] == "mark" {
            after_return = e["name"].as_str().is_some_and(|n| n.ends_with(" returned"));
        } else if after_return && e["ev"] == "profile-state" {
            late.push(json!([e["t"], e["state"], e["label"]]));
        }
    }
    // A compact sequence: consecutive events with the same state, stage and label are merged.
    let mut sequence: Vec<Value> = Vec::new();
    for e in events {
        if e["ev"] == "mark" {
            sequence.push(json!(format!("@{} {}", e["t"], e["name"].as_str().unwrap_or_default())));
            continue;
        }
        if e["ev"] == "job" {
            sequence.push(json!(format!("@{} job {} {}", e["t"], e["state"].as_str().unwrap_or_default(), e["stage"].as_str().unwrap_or_default())));
            continue;
        }
        let key = format!("{} {} {}", e["state"].as_str().unwrap_or_default(), e["stage"].as_str().unwrap_or("-"), e["label"].as_str().unwrap_or("-"));
        let pct = e["progress"].as_f64().map(|p| (p * 100.0).round() as u64);
        let detail = match (e["bytesDone"].as_u64(), e["bytesTotal"].as_u64()) {
            (Some(d), Some(t)) => format!(" {}/{}MB {}%", d / 1_000_000, t / 1_000_000, pct.unwrap_or(0)),
            _ => e["message"].as_str().map(|m| format!(" «{}»", m.chars().take(70).collect::<String>())).unwrap_or_default(),
        };
        match sequence.last_mut() {
            Some(Value::Object(last)) if last["key"] == key.as_str() => {
                let n = last["n"].as_u64().unwrap_or(1) + 1;
                last.insert("n".into(), json!(n));
                last.insert("last".into(), json!(format!("@{}{}", e["t"], detail)));
            }
            _ => sequence.push(json!({ "key": key, "n": 1, "first": format!("@{}{}", e["t"], detail) })),
        }
    }
    json!({
        "events": states.len(),
        "backwards": backwards,
        "flaps": flaps,
        "sameStateLabelChanges": label_changes,
        "seqOutOfOrder": unordered,
        "late": late,
        "endsBusy": states.last().is_some_and(|e| BUSY.contains(&e["state"].as_str().unwrap_or_default())),
        "sequence": sequence,
    })
}

fn dir_bytes(path: &std::path::Path) -> u64 {
    let Ok(entries) = std::fs::read_dir(path) else { return 0 };
    entries
        .flatten()
        .map(|e| match e.file_type() {
            Ok(t) if t.is_dir() => dir_bytes(&e.path()),
            Ok(t) if t.is_file() => e.metadata().map(|m| m.len()).unwrap_or(0),
            _ => 0,
        })
        .sum()
}

/// Whether the profile is still reserved by an install, check or launch.
fn locked(state: &AppState, id: &str) -> bool {
    state.install_cancel.lock().unwrap().contains_key(id) || state.running.lock().unwrap().contains_key(id)
}

/// Debug builds only: `FLINT_SELFTEST=pausetest:<version>:<loader>:<phase>` drives the install
/// pipeline the way the Pause and Resume buttons do and records every state event. Phases, run as
/// separate processes against the same FLINT_DATA_DIR:
/// - `pause`: create a profile, install, pause at about 25%, check the lock is released, run and
///   interrupt a check.
/// - `offline` (run with HTTPS_PROXY pointing at a closed port): retrying must end in an error
///   state with a message and no lock.
/// - `resume`: install again, measure how much is downloaded a second time, then verify every
///   file's SHA-1 (a repair), and interrupt a check of the installed profile.
fn pause_test(app: AppHandle, version: String, loader: String, phase: String) {
    let rec = Recorder::new(&app);
    tauri::async_runtime::spawn(async move {
        let state = app.state::<AppState>();
        let report = |name: &str, value: Value| println!("[pausetest] {name}: {value}");
        let existing = data::load_profiles(&state).profiles.into_iter().find(|p| p.name == "Pausetest");
        let profile = match existing {
            Some(p) => p,
            None => match data::profiles_create(app.state(), "Pausetest".into(), version.clone(), loader.clone(), true).await {
                Ok(p) => p,
                Err(e) => {
                    report("create", json!(e));
                    app.exit(1);
                    return;
                }
            },
        };
        let id = profile.id.clone();
        let snapshot = |state: &AppState| json!({ "state": game::profile_states(app.state())[&id].clone(), "locked": locked(state, &id) });
        let disk = |state: &AppState| dir_bytes(&state.paths.game()) + dir_bytes(&state.paths.runtimes());
        // Lets late events arrive before the next step starts.
        let settle = || tokio::time::sleep(Duration::from_millis(700));
        let mut summary = serde_json::Map::new();

        match phase.as_str() {
            "pause" => {
                rec.mark("probe");
                let probe = game::profile_check(app.clone(), app.state(), id.clone(), Some(true)).await;
                rec.mark("probe returned");
                summary.insert("probeBefore".into(), json!(probe));
                settle().await;

                rec.mark("install");
                let started = Instant::now();
                let task = {
                    let (app, id) = (app.clone(), id.clone());
                    tauri::async_runtime::spawn(async move { game::profile_install(app.clone(), app.state(), id, None).await })
                };
                let mut paused_at = None;
                while started.elapsed() < Duration::from_secs(600) {
                    tokio::time::sleep(Duration::from_millis(50)).await;
                    let latest = rec.latest(&id).unwrap_or_default();
                    if latest["state"] == "downloading" && latest["progress"].as_f64().unwrap_or(0.0) >= 0.25 {
                        paused_at = Some(latest.clone());
                        rec.mark("pause");
                        game::profile_cancel(app.state(), id.clone());
                        break;
                    }
                    if task.inner().is_finished() {
                        break;
                    }
                }
                let result = task.await.map_err(|e| e.to_string()).and_then(|r| r);
                rec.mark("install returned");
                settle().await;
                summary.insert("pausedAt".into(), json!({ "bytesDone": paused_at.as_ref().map(|p| p["bytesDone"].clone()), "bytesTotal": paused_at.as_ref().map(|p| p["bytesTotal"].clone()), "progress": paused_at.as_ref().map(|p| p["progress"].clone()) }));
                summary.insert("installResult".into(), json!(result));
                summary.insert("afterPause".into(), snapshot(&state));
                summary.insert("diskAfterPause".into(), json!(disk(&state)));
                let leftovers = walk_parts(&state.paths.root);
                summary.insert("partFilesAfterPause".into(), json!(leftovers));

                rec.mark("probe2");
                let probe = game::profile_check(app.clone(), app.state(), id.clone(), Some(true)).await;
                rec.mark("probe2 returned");
                summary.insert("probeAfterPause".into(), json!(probe));
                summary.insert("afterProbe2".into(), snapshot(&state));
                settle().await;

                rec.mark("check");
                let check = game::profile_check(app.clone(), app.state(), id.clone(), None).await;
                rec.mark("check returned");
                summary.insert("checkAfterPause".into(), json!(check));
                summary.insert("afterCheck".into(), snapshot(&state));
                settle().await;

                summary.insert("interruptedCheck".into(), interrupted_check(&app, &rec, &id, phase == "resume").await);
                summary.insert("afterInterruptedCheck".into(), snapshot(&state));
            }
            "offline" => {
                summary.insert("proxy".into(), json!(std::env::var("HTTPS_PROXY").ok()));
                rec.mark("install");
                let result = game::profile_install(app.clone(), app.state(), id.clone(), None).await;
                rec.mark("install returned");
                settle().await;
                summary.insert("installResult".into(), json!(result));
                summary.insert("afterInstall".into(), snapshot(&state));
                summary.insert("partFiles".into(), json!(walk_parts(&state.paths.root)));
                // The lock is free: another attempt can start straight away (and fails the same way).
                rec.mark("retry");
                let retry = game::profile_install(app.clone(), app.state(), id.clone(), None).await;
                rec.mark("retry returned");
                settle().await;
                summary.insert("retryResult".into(), json!(retry));
                summary.insert("afterRetry".into(), snapshot(&state));
            }
            "resume" => {
                summary.insert("before".into(), snapshot(&state));
                let disk_before = disk(&state);
                summary.insert("diskBefore".into(), json!(disk_before));
                rec.mark("install");
                let started = Instant::now();
                let result = game::profile_install(app.clone(), app.state(), id.clone(), None).await;
                rec.mark("install returned");
                settle().await;
                let downloads: Vec<Value> = rec.since("install").into_iter().filter(|e| e["state"] == "downloading").collect();
                let first = downloads.first().cloned().unwrap_or_default();
                let last = downloads.last().cloned().unwrap_or_default();
                let disk_after = disk(&state);
                summary.insert("installResult".into(), json!(result));
                summary.insert("seconds".into(), json!(started.elapsed().as_secs()));
                summary.insert("firstProgress".into(), json!({ "bytesDone": first["bytesDone"], "bytesTotal": first["bytesTotal"], "progress": first["progress"] }));
                summary.insert("lastProgress".into(), json!({ "bytesDone": last["bytesDone"], "bytesTotal": last["bytesTotal"], "progress": last["progress"] }));
                summary.insert("diskAfter".into(), json!(disk_after));
                summary.insert("diskGrowth".into(), json!(disk_after.saturating_sub(disk_before)));
                summary.insert("afterInstall".into(), snapshot(&state));

                rec.mark("repair");
                let repair = game::profile_install(app.clone(), app.state(), id.clone(), Some(true)).await;
                rec.mark("repair returned");
                settle().await;
                let redownloaded = rec.since("repair").iter().filter(|e| e["state"] == "downloading").map(|e| e["bytesDone"].as_u64().unwrap_or(0)).max();
                summary.insert("repairResult".into(), json!(repair));
                summary.insert("repairDownloads".into(), json!(redownloaded));
                summary.insert("afterRepair".into(), snapshot(&state));

                summary.insert("interruptedCheck".into(), interrupted_check(&app, &rec, &id, phase == "resume").await);
                summary.insert("afterInterruptedCheck".into(), snapshot(&state));
            }
            other => {
                report("error", json!(format!("unknown phase {other}")));
            }
        }
        let events = rec.events.lock().unwrap().clone();
        summary.insert("analysis".into(), analyse(&events));
        report("summary", Value::Object(summary));
        println!("[pausetest] done");
        app.exit(0);
    });
}

/// Starts a full (not quiet) check and pauses it as soon as it holds the profile. With `rehash`,
/// the record of already-hashed files is removed first, so the check spends a while hashing jars.
async fn interrupted_check(app: &AppHandle, rec: &Recorder, id: &str, rehash: bool) -> Value {
    if rehash {
        let _ = std::fs::remove_file(app.state::<AppState>().paths.game().join(".flint-verified.json"));
    }
    rec.mark("check2");
    let started = Instant::now();
    let task = {
        let (app, id) = (app.clone(), id.to_owned());
        tauri::async_runtime::spawn(async move { game::profile_check(app.clone(), app.state(), id, None).await })
    };
    // Pauses as soon as the check holds the profile (it can finish within a few milliseconds).
    let state = app.state::<AppState>();
    while started.elapsed() < Duration::from_secs(5) && !locked(&state, id) && !task.inner().is_finished() {
        tokio::task::yield_now().await;
    }
    tokio::time::sleep(Duration::from_millis(if rehash { 300 } else { 0 })).await;
    rec.mark("cancel check");
    let cancelled = Instant::now();
    game::profile_cancel(app.state(), id.to_owned());
    let result = task.await.map_err(|e| e.to_string()).and_then(|r| r);
    rec.mark("check2 returned");
    let latency = cancelled.elapsed().as_millis() as u64;
    tokio::time::sleep(Duration::from_millis(700)).await;
    json!({ "result": result, "ms": started.elapsed().as_millis() as u64, "cancelToReturnMs": latency, "lockedAfter": locked(&state, id) })
}

fn walk_parts(path: &std::path::Path) -> usize {
    let Ok(entries) = std::fs::read_dir(path) else { return 0 };
    entries
        .flatten()
        .map(|e| match e.file_type() {
            Ok(t) if t.is_dir() => walk_parts(&e.path()),
            _ => usize::from(e.file_name().to_string_lossy().ends_with(crate::jobs::PART_SUFFIX)),
        })
        .sum()
}

/// Debug builds only: `FLINT_SELFTEST=<version>:<loader>[:launch]` exercises the real install,
/// mod and launch pipeline against an isolated FLINT_DATA_DIR, printing results to stdout.
/// `FLINT_SELFTEST=discord`: drives the real Rich Presence service against the running Discord app,
/// with `sleep` processes standing in for games (so exits and crashes can be timed exactly).
fn discord_lifecycle(app: AppHandle) {
    use crate::discord::{Presence, Session};
    std::thread::spawn(move || {
        let presence = app.state::<Presence>();
        let say = |step: &str| {
            let reply = crate::discord::LAST_REPLY.lock().unwrap().take();
            println!("[selftest] discord {step}: status={:?} reply={}", presence.status(), reply.map(|r| r.to_string()).unwrap_or_else(|| "-".into()));
        };
        let game = |secs: &str| std::process::Command::new("sleep").arg(secs).spawn().unwrap();
        let now = || std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_secs();
        let pause = |s: u64| std::thread::sleep(Duration::from_secs(s));
        let set = |enabled: bool, details: bool| presence.configure(&json!({ "discordPresence": enabled, "discordDetails": details }));
        say("1 start (no game)");
        let a = game("600");
        presence.started(Session { id: "A".into(), pid: a.id(), version: "26.3".into(), loader: "fabric".into(), started_at: now() - 90 });
        pause(1);
        say("2 game A started, before settling (expect idle, nothing published)");
        pause(6);
        say("3 game A settled (expect Minecraft 26.3, Fabric)");
        pause(12);
        let mut b = game("600");
        presence.started(Session { id: "B".into(), pid: b.id(), version: "1.21.11".into(), loader: "vanilla".into(), started_at: now() });
        pause(7);
        say("4 game B started (newest wins: Minecraft 1.21.11, Vanilla)");
        pause(12);
        let _ = b.kill();
        let _ = b.wait();
        presence.ended("B");
        pause(2);
        say("5 game B crashed/killed (back to A)");
        pause(10);
        set(true, false);
        pause(2);
        say("6 details off (expect Playing Minecraft only)");
        pause(10);
        set(false, false);
        pause(2);
        say("7 presence off mid-game (expect cleared, off)");
        pause(10);
        set(true, true);
        pause(2);
        say("8 presence back on");
        pause(10);
        println!("[selftest] discord 9 Flint quits with game A running: handing off to the keeper (game A pid {})", a.id());
        presence.hand_off();
        std::mem::forget(a);
        app.exit(0);
    });
}

/// `FLINT_SELFTEST=discord-watch:<seconds>`: prints the presence status every 3 s (for the
/// Discord-starts-late and keeper-takeover checks, which change things from outside).
fn discord_watch(app: AppHandle, secs: u64, with_game: bool, keep: bool) {
    use crate::discord::{Presence, Session};
    std::thread::spawn(move || {
        let presence = app.state::<Presence>();
        let game = with_game.then(|| std::process::Command::new("sleep").arg("600").spawn().unwrap());
        if let Some(g) = &game {
            let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_secs();
            presence.started(Session { id: "W".into(), pid: g.id(), version: "26.3".into(), loader: "fabric".into(), started_at: now });
            println!("[selftest] discord-watch game pid {}", g.id());
        }
        let start = Instant::now();
        while start.elapsed().as_secs() < secs {
            std::thread::sleep(Duration::from_secs(3));
            let reply = crate::discord::LAST_REPLY.lock().unwrap().take();
            println!("[selftest] discord-watch t={}s status={:?} details={}", start.elapsed().as_secs(), presence.status(), reply.map(|r| r["data"]["details"].to_string()).unwrap_or_else(|| "-".into()));
        }
        match game {
            // Quit like a player closing Flint mid-game: the game keeps running and a keeper takes over.
            Some(g) if keep => std::mem::forget(g),
            Some(mut g) => {
                let _ = g.kill();
            }
            None => {}
        }
        app.exit(0);
    });
}

pub fn run(app: AppHandle, spec: String) {
    if spec == "discord" {
        return discord_lifecycle(app);
    }
    if let Some(rest) = spec.strip_prefix("discord-watch:") {
        let mut parts = rest.split(':');
        let secs = parts.next().and_then(|s| s.parse().ok()).unwrap_or(30);
        let flags: Vec<&str> = parts.collect();
        return discord_watch(app, secs, flags.contains(&"game"), flags.contains(&"keep"));
    }
    if let Some(rest) = spec.strip_prefix("resolve:") {
        if let Some((version, file)) = rest.split_once(':') {
            return resolve_only(app, version.to_owned(), file.to_owned());
        }
    }
    if let Some(rest) = spec.strip_prefix("pausetest:") {
        let parts: Vec<&str> = rest.split(':').collect();
        if let [version, loader, phase] = parts.as_slice() {
            return pause_test(app, (*version).to_owned(), (*loader).to_owned(), (*phase).to_owned());
        }
    }
    let last = std::sync::Mutex::new(Instant::now() - Duration::from_secs(5));
    app.listen_any("profile-state", move |event| {
        let value: Value = serde_json::from_str(event.payload()).unwrap_or_default();
        let state = value["state"].as_str().unwrap_or_default();
        let mut last = last.lock().unwrap();
        if state != "downloading" || last.elapsed() > Duration::from_secs(3) {
            *last = Instant::now();
            println!("[selftest] state {}", event.payload());
        }
    });
    tauri::async_runtime::spawn(async move {
        let parts: Vec<&str> = spec.split(':').collect();
        let (version, loader) = (parts[0].to_owned(), parts.get(1).copied().unwrap_or("fabric").to_owned());
        let launch = parts.get(2) == Some(&"launch");
        let state = app.state::<AppState>();
        let report = |name: &str, value: Value| println!("[selftest] {name}: {value}");

        report("catalog", data::version_catalog(app.state()).await.unwrap_or_else(|e| json!(e)));
        // `reuse`: keep using the latest selftest profile for this version (its worlds, servers, options).
        let existing = parts.contains(&"reuse").then(|| data::load_profiles(&state).profiles.into_iter().rev().find(|p| p.name == format!("Selftest {version}") && p.loader == loader)).flatten();
        let created = match existing {
            Some(p) => Ok(p),
            None => data::profiles_create(app.state(), format!("Selftest {version}"), version.clone(), loader.clone(), true).await,
        };
        let profile = match created {
            Ok(p) => p,
            Err(e) => {
                report("create", json!(e));
                app.exit(1);
                return;
            }
        };
        report("create", json!(profile.id));
        let started = Instant::now();
        let timed = Instant::now();
        let check = game::profile_check(app.clone(), app.state(), profile.id.clone(), None).await.unwrap_or_else(|e| json!(e));
        report("check", json!({ "result": check, "ms": timed.elapsed().as_millis() as u64 }));
        let install = game::profile_install(app.clone(), app.state(), profile.id.clone(), None).await;
        report("install", json!({ "ok": install.is_ok(), "error": install.err(), "seconds": started.elapsed().as_secs() }));
        let timed = Instant::now();
        let recheck = game::profile_check(app.clone(), app.state(), profile.id.clone(), None).await.unwrap_or_else(|e| json!(e));
        report("recheck", json!({ "result": recheck, "ms": timed.elapsed().as_millis() as u64 }));

        // `bare`: install and launch only, skipping the content checks.
        let bare = parts.contains(&"bare");
        if loader == "fabric" && !bare {
            let requests: Vec<content::ResolveRequest> = serde_json::from_value(json!([
                { "query": "sodium", "source": "user" },
                { "query": "xaero's minimap", "source": "assistant", "reason": "selftest" },
                { "project": "lithium", "source": "assistant" },
                { "query": "zzzz-no-such-mod-qqq", "source": "assistant" }
            ]))
            .unwrap();
            let plan = content::mods_resolve(app.state(), Some(profile.id.clone()), None, requests).await;
            let plan = plan.unwrap_or_else(|e| json!({ "error": e }));
            let summary: Vec<Value> = plan["items"].as_array().into_iter().flatten().map(|i| json!([i["slug"], i["status"], i["source"], i["versionNumber"]])).collect();
            report("resolve", json!({ "items": summary, "unmatched": plan["unmatched"] }));
            let installs = installable(&plan);
            report("mods_install", content::mods_install(app.clone(), app.state(), profile.id.clone(), installs).await.unwrap_or_else(|e| json!(e)));
            let bad: Vec<content::InstallItem> = serde_json::from_value(json!([{ "versionId": "../../etc", "source": "user" }])).unwrap();
            report("mods_install_bad", content::mods_install(app.clone(), app.state(), profile.id.clone(), bad).await.unwrap_or_else(|e| json!(e)));

            let requests: Vec<content::PlanRequest> = serde_json::from_value(json!([
                { "kind": "shader", "project": "complementary-reimagined" },
                { "kind": "resourcepack", "project": "fresh-animations" }
            ]))
            .unwrap();
            let plan = content::content_plan(app.state(), profile.id.clone(), requests).await.unwrap_or_else(|e| json!({ "error": e }));
            let summary: Vec<Value> = plan["items"].as_array().into_iter().flatten().map(|i| json!([i["kind"], i["slug"], i["status"], i["reason"]])).collect();
            report("content_plan", json!({ "items": summary, "blockers": plan["blockers"] }));
            let installs = installable(&plan);
            report("content_install", content::mods_install(app.clone(), app.state(), profile.id.clone(), installs).await.unwrap_or_else(|e| json!(e)));

            let listed = content::list_content(&state, &profile.id).unwrap_or_else(|e| json!(e));
            let names = |kind: &str| -> Vec<Value> { listed[kind].as_array().into_iter().flatten().map(|m| json!([m["fileName"], m["enabled"], m["source"]])).collect() };
            report("content_list", json!({ "mods": names("mods"), "resourcepacks": names("resourcepacks"), "shaders": names("shaders"), "iris": listed["irisInstalled"] }));

            if let Some(first) = listed["mods"].as_array().and_then(|m| m.iter().find(|x| x["source"] == "user")).and_then(|m| m["fileName"].as_str()) {
                let first = first.to_owned();
                report("disable", json!(content::content_set_enabled(app.state(), profile.id.clone(), vec![first.clone()], false).await.err()));
                report("preflight_disabled", json!(content::launch_preflight(&state, &profile.id).err()));
                report("enable", json!(content::content_set_enabled(app.state(), profile.id.clone(), vec![first], true).await.err()));
                report("preflight", json!(content::launch_preflight(&state, &profile.id).err()));
            }
            report("updates", content::content_updates(app.state(), profile.id.clone()).await.unwrap_or_else(|e| json!(e)));
            report("files", content::profile_files(app.state(), profile.id.clone()).await.unwrap_or_else(|e| json!(e)));
        }

        if let Some(pack) = parts.iter().find_map(|p| p.strip_prefix("pack=")) {
            let job = crate::modpack::modpack_install(app.clone(), app.state(), pack.to_owned(), None, None, format!("Modpack {pack}")).await;
            match job {
                Ok(id) => {
                    for _ in 0..600 {
                        tokio::time::sleep(Duration::from_millis(500)).await;
                        let Some(j) = state.jobs.list().into_iter().find(|j| j.id == id) else { break };
                        if !matches!(j.state.as_str(), "queued" | "running") {
                            report("modpack", json!({ "state": j.state, "message": j.message, "result": j.result }));
                            break;
                        }
                    }
                }
                Err(e) => report("modpack", json!(e)),
            }
            report("profiles_after_pack", json!(data::load_profiles(&state).profiles.iter().map(|p| json!([p.name, p.game_version, p.loader_version, p.modpack.is_some()])).collect::<Vec<_>>()));
        }

        if launch && state.qa {
            report("qa_account", json!(auth::auth_qa_account(app.state()).map(|a| a.username)));
            let timed = Instant::now();
            let launched = game::profile_launch(app.clone(), app.state(), profile.id.clone()).await;
            report("launch", json!({ "ok": launched.is_ok(), "error": launched.err(), "msToSpawn": timed.elapsed().as_millis() as u64 }));
            // Rich Presence while the real game runs (published once it has stayed up for a few seconds).
            tokio::time::sleep(Duration::from_secs(12)).await;
            let presence = app.state::<crate::discord::Presence>();
            report("discord_while_running", json!({ "status": presence.status(), "reply": crate::discord::LAST_REPLY.lock().unwrap().take() }));
            // `hold=<seconds>` keeps the game up longer (to watch Discord over time); default 45 s.
            let hold = parts.iter().find_map(|p| p.strip_prefix("hold=")).and_then(|s| s.parse::<u64>().ok()).unwrap_or(45);
            for second in 0..hold {
                tokio::time::sleep(Duration::from_secs(1)).await;
                if second % 15 == 14 {
                    println!("[selftest] hold t={}s status={:?} reply={}", second + 13, presence.status(), crate::discord::LAST_REPLY.lock().unwrap().take().map(|r| r.to_string()).unwrap_or_else(|| "-".into()));
                }
                if !state.running.lock().unwrap().contains_key(&profile.id) {
                    break;
                }
            }
            let still_running = state.running.lock().unwrap().contains_key(&profile.id);
            report("running_after_wait", json!(still_running));
            let played = data::find_profile(&state, &profile.id).ok().and_then(|p| p.last_played);
            report("last_played_recorded", json!(played.is_some()));
            let log = state.paths.profile_dir(&profile.id).join("logs/flint-launch.log");
            let text = std::fs::read_to_string(&log).unwrap_or_default();
            let lines: Vec<&str> = text.lines().collect();
            println!("[selftest] log tail:\n{}", lines[lines.len().saturating_sub(25)..].join("\n"));
            game::profile_stop(app.state(), profile.id.clone());
            tokio::time::sleep(Duration::from_secs(3)).await;
            report("discord_after_stop", json!({ "status": presence.status(), "reply": crate::discord::LAST_REPLY.lock().unwrap().take() }));
        }
        println!("[selftest] done");
        app.exit(0);
    });
}
