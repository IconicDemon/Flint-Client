//! Instant Swap: a mod the in-game Library adds or takes out works as soon as the player closes the Library, though Fabric
//! can't load or unload a mod in a running game (its mixins, access wideners, resources and entrypoints are fixed at start).
//!
//! 1. A change to the profile's mods ([`changed`]) has the launcher start a second game of the same profile in the
//!    background, with the new mod set ([`crate::game::launch_standby`]): its window hidden (Flint's runtime creates it so),
//!    at the lowest process priority, silent and drawing at 1 FPS until it takes over, so the player's game keeps its FPS.
//!    Changes a moment apart are one standby game (a newer change replaces a standby that loaded the older set). A mod the
//!    running game has loaded stays in `mods` (Windows keeps it locked) and the standby game loads a copy of the folder
//!    without it (`fabric.modsFolder`).
//! 2. The standby game says when it is at its main menu ([`ready`]) and waits ([`wait`]).
//! 3. The player closes the Library: the old game asks [`status`] until the standby game is ready (or failed: then it keeps
//!    playing and says why the mod couldn't be loaded), saves and leaves its world or server, and hands over where it was and
//!    its window ([`go`]); then it quits. The standby game gets that at once, takes the window's place and shape, shows
//!    itself at normal priority and joins the same world or server: the player sees a world load.
//! 4. Once the old game has ended, the mods taken out are deleted and the new game is the profile's running game.
//!
//! Not enough free memory for two games: nothing starts early ([`Phase::Deferred`]); the old game hands over at once and the
//! new game starts after it ends, then joins where the player was, the same way.

use crate::core::*;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::Mutex;
use std::time::Duration;
use tauri::{AppHandle, Manager};

/// Tells Flint's runtime that its game is a standby game (hidden, silent, slow until it takes over).
pub const ENV_STANDBY: &str = "FLINT_SWAP_STANDBY";
/// Changes this close together go into one standby game.
const SETTLE: Duration = Duration::from_millis(1500);
/// Room the system keeps beside the standby game's whole heap in what can still be committed, in MB.
const MARGIN_MB: u64 = 1536;
/// How long a standby game may take to reach its main menu before the swap is given up (the player keeps the old game).
pub const READY_WITHIN: Duration = Duration::from_secs(360);
/// How long a handed-over game is waited for to end before the new one counts as the profile's game anyway.
const OLD_GAME_ENDS: Duration = Duration::from_secs(90);

#[derive(Clone, Debug, PartialEq)]
pub enum Phase {
    /// A change came; the standby game starts once changes settle.
    Settling,
    /// The standby game is starting.
    Warming,
    /// The standby game is at its main menu, waiting.
    Ready,
    /// Not enough memory for two games: the new one starts when the old one has ended.
    Deferred,
    /// The standby game couldn't start: the player keeps the old game.
    Failed(String),
    /// The old game handed over; the new game takes it from here.
    Go,
    /// The old game ended and the new one is the profile's game (its handover stays readable for it).
    Done,
}

#[derive(Debug)]
struct Swap {
    generation: u64,
    phase: Phase,
    /// The standby game's link secret and process, once started.
    secret: Option<String>,
    pid: Option<u32>,
    kill: Option<tokio::sync::oneshot::Sender<()>>,
    /// Set once the standby game is the profile's game: its end is then the profile's end, not a failed swap.
    taken: Option<std::sync::Arc<std::sync::atomic::AtomicBool>>,
    /// The new game is in the world and in front: the old one ends (its status says `taken`).
    joined: bool,
    /// Where the player was and the old window (from [`go`]), for the new game.
    handover: Option<Value>,
    /// What changed, for the old game's notice.
    titles: Vec<String>,
    /// The player closed the Library and waits for the new game: it runs at normal priority from now on.
    promoted: bool,
}

/// Every profile's swap, at most one each.
#[derive(Default)]
pub struct Swaps {
    swaps: Mutex<HashMap<String, Swap>>,
    changed: tokio::sync::Notify,
}

impl Swaps {
    fn with<T>(&self, profile: &str, f: impl FnOnce(&mut Swap) -> T) -> Option<T> {
        self.swaps.lock().unwrap().get_mut(profile).map(f)
    }

    /// Whether the standby game of `generation` is still starting (not ready, not handed over, not replaced).
    pub fn still_warming(&self, profile: &str, generation: u64) -> bool {
        self.with(profile, |s| s.generation == generation && s.phase == Phase::Warming).unwrap_or(false)
    }

    /// Whether the profile's game is handing over to a new one (its end isn't the profile's end).
    pub fn handing_over(&self, profile: &str) -> bool {
        self.with(profile, |s| s.phase == Phase::Go).unwrap_or(false)
    }

    /// The standby game of `generation` started: its secret, process and kill switch.
    pub fn started(
        &self,
        profile: &str,
        generation: u64,
        secret: String,
        pid: Option<u32>,
        kill: tokio::sync::oneshot::Sender<()>,
        taken: std::sync::Arc<std::sync::atomic::AtomicBool>,
    ) -> bool {
        self.with(profile, |s| {
            if s.generation != generation || !matches!(s.phase, Phase::Warming | Phase::Go) {
                return Err(kill);
            }
            s.secret = Some(secret);
            s.pid = pid;
            if s.promoted {
                if let Some(pid) = pid {
                    normal_priority(pid);
                }
            }
            s.kill = Some(kill);
            s.taken = Some(taken);
            Ok(())
        })
        .map(|r| r.map_err(|kill| { let _ = kill.send(()); }).is_ok())
        .unwrap_or(false)
    }

    /// The standby game of `generation` couldn't start or ended before it took over.
    pub fn failed(&self, profile: &str, generation: u64, reason: String) -> bool {
        let mut still_down = false;
        self.with(profile, |s| {
            if s.generation == generation && s.phase != Phase::Failed(reason.clone()) {
                eprintln!("[flint] instant swap: the new game for {} couldn't start: {reason}", s.titles.join(", "));
                still_down = true;
                s.phase = Phase::Failed(reason);
                s.secret = None;
                s.pid = None;
                s.kill = None;
            }
        });
        self.changed.notify_waiters();
        still_down
    }

    /// The new game took over and the old one ended: the swap is done. The new game's process and kill switch.
    pub fn finish(&self, profile: &str, generation: u64) -> Option<(Option<u32>, Option<tokio::sync::oneshot::Sender<()>>)> {
        self.with(profile, |s| {
            if s.generation != generation || s.phase != Phase::Go {
                return None;
            }
            s.phase = Phase::Done;
            s.titles.clear();
            if let Some(taken) = &s.taken {
                taken.store(true, std::sync::atomic::Ordering::SeqCst);
            }
            Some((s.pid, s.kill.take()))
        })
        .flatten()
    }

    /// The swap of `generation` didn't go through after the handover (the new game couldn't start): forgotten.
    pub fn drop_swap(&self, profile: &str, generation: u64) {
        let mut swaps = self.swaps.lock().unwrap();
        if swaps.get(profile).is_some_and(|s| s.generation == generation) {
            swaps.remove(profile);
        }
    }

}

fn swaps(app: &AppHandle) -> std::sync::Arc<Swaps> {
    app.state::<AppState>().game_link.swaps.clone()
}

/// The profile's mods changed in its running game (the in-game Library): a standby game with the new set is prepared.
pub fn changed(app: &AppHandle, profile: &str, title: &str) {
    let all = swaps(app);
    let generation = {
        let mut map = all.swaps.lock().unwrap();
        let swap = map.entry(profile.to_owned()).or_insert(Swap { generation: 0, phase: Phase::Settling, secret: None, pid: None, kill: None, taken: None, joined: false, handover: None, titles: Vec::new(), promoted: false });
        if swap.phase == Phase::Go {
            // Already handing over: the new game picks the change up at its next start (as any change would).
            return;
        }
        swap.generation += 1;
        swap.phase = Phase::Settling;
        swap.secret = None;
        swap.pid = None;
        swap.taken = None;
        swap.joined = false;
        swap.handover = None;
        swap.promoted = false;
        if let Some(kill) = swap.kill.take() {
            // A standby game with the older set isn't the one to switch to.
            let _ = kill.send(());
        }
        if !title.is_empty() && !swap.titles.iter().any(|t| t == title) {
            swap.titles.push(title.to_owned());
        }
        swap.generation
    };
    all.changed.notify_waiters();
    let (app, profile) = (app.clone(), profile.to_owned());
    tauri::async_runtime::spawn(async move {
        tokio::time::sleep(SETTLE).await;
        let all = swaps(&app);
        let enough = enough_memory(&app, &profile).await;
        let go = all.with(&profile, |s| {
            if s.generation != generation || s.phase != Phase::Settling {
                return false;
            }
            s.phase = if enough { Phase::Warming } else { Phase::Deferred };
            enough
        });
        all.changed.notify_waiters();
        if go != Some(true) {
            if go == Some(false) && !enough {
                eprintln!("[flint] instant swap: not enough free memory for a second game; the new one starts when this one hands over");
            }
            return;
        }
        start(app, profile, generation).await;
    });
}

async fn start(app: AppHandle, profile: String, generation: u64) {
    let state = app.state::<AppState>();
    if let Err(reason) = crate::game::launch_standby(&app, &state, &profile, generation, false).await {
        if swaps(&app).failed(&profile, generation, reason) {
            crate::swapstill::close(&app);
        }
    }
}

/// Whether a standby game fits beside the running one: what it really takes (not its whole heap, which 1.0.4's first test
/// build asked for, so on a 16 GB PC every swap waited for the old game to end and the player saw a cold start) in the memory
/// free now, and its whole heap in what the system can still commit.
async fn enough_memory(app: &AppHandle, profile: &str) -> bool {
    let state = app.state::<AppState>();
    let Ok(p) = crate::data::find_profile(&state, profile) else { return false };
    let heap = crate::data::launch_memory_mb(&p, crate::data::load_settings(&state)["memoryMb"].as_u64(), crate::data::max_memory_mb());
    let fits = memory_mb().is_none_or(|(_, commit)| fits_beside(heap, commit));
    eprintln!("[flint] instant swap: memory {:?} MB free/committable for a {heap} MB game: {}", memory_mb(), if fits { "standby now" } else { "after the old game" });
    fits
}

/// Whether the standby game fits beside the running one: its whole heap and a margin in what the system can still commit.
/// Free physical memory isn't the gate: on a 16 GB PC with a 4 GB game and everyday apps it is often 1.5-2.5 GB, and the
/// final tour's swaps all went sequential (20-25 s on a still). The standby starts small (-Xms256m, no pre-touch) and below
/// normal priority, so Windows makes room for it from caches and idle pages while the player's game keeps its memory.
pub fn fits_beside(heap_mb: u64, commit_mb: u64) -> bool {
    commit_mb >= heap_mb + MARGIN_MB
}

#[cfg(windows)]
fn memory_mb() -> Option<(u64, u64)> {
    use windows_sys::Win32::System::SystemInformation::{GlobalMemoryStatusEx, MEMORYSTATUSEX};
    let mut status: MEMORYSTATUSEX = unsafe { std::mem::zeroed() };
    status.dwLength = std::mem::size_of::<MEMORYSTATUSEX>() as u32;
    // SAFETY: a zeroed MEMORYSTATUSEX with its length set, as the call requires.
    (unsafe { GlobalMemoryStatusEx(&mut status) } != 0).then_some((status.ullAvailPhys / (1024 * 1024), status.ullAvailPageFile / (1024 * 1024)))
}

#[cfg(not(windows))]
fn memory_mb() -> Option<(u64, u64)> {
    None
}

/// The old game asks how the swap goes: `none` (nothing to swap), `preparing`, `ready` (hand over now; `sequential`: the
/// new game starts after this one ends), or `failed` with the reason (keep playing).
pub fn status(app: &AppHandle, profile: &str) -> Value {
    let all = swaps(app);
    // The old game asks only once the Library has closed: the player is waiting for the mod now, so the standby game
    // stops stepping back and loads at full speed (it started below normal so the player's game kept its frames meanwhile).
    all.with(profile, |s| {
        if !s.promoted && matches!(s.phase, Phase::Settling | Phase::Warming) {
            s.promoted = true;
            if let Some(pid) = s.pid {
                normal_priority(pid);
                eprintln!("[flint] instant swap: the Library closed; the new game loads at normal priority");
            }
        }
    });
    let reply = all.with(profile, |s| match &s.phase {
        Phase::Settling | Phase::Warming => json!({ "state": "preparing", "titles": s.titles }),
        Phase::Ready => json!({ "state": "ready", "sequential": false, "titles": s.titles }),
        Phase::Deferred => json!({ "state": "ready", "sequential": true, "titles": s.titles }),
        Phase::Failed(reason) => json!({ "state": "failed", "reason": reason, "titles": s.titles }),
        Phase::Go if s.joined => json!({ "state": "taken" }),
        Phase::Go => json!({ "state": "handing-over" }),
        Phase::Done => json!({ "state": "none" }),
    });
    // A failure is said once; the next change tries again.
    if reply.as_ref().is_some_and(|r| r["state"] == "failed") {
        all.swaps.lock().unwrap().remove(profile);
    }
    reply.unwrap_or_else(|| json!({ "state": "none" }))
}

/// The mods the running game added went into it live (its HotLoader): the standby game started for them isn't needed and
/// ends, unless the handover already began.
pub fn drop_unneeded(app: &AppHandle, profile: &str) -> Value {
    let all = swaps(app);
    let dropped = {
        let mut map = all.swaps.lock().unwrap();
        let unneeded = map.get(profile).is_some_and(|s| !matches!(s.phase, Phase::Go | Phase::Done));
        if unneeded {
            if let Some(mut swap) = map.remove(profile) {
                if let Some(kill) = swap.kill.take() {
                    let _ = kill.send(());
                }
                eprintln!("[flint] instant swap: {} loaded live; no new game needed", swap.titles.join(", "));
            }
        }
        unneeded
    };
    all.changed.notify_waiters();
    json!({ "dropped": dropped })
}

/// The standby game is at its main menu.
pub fn ready(app: &AppHandle, profile: &str, secret: &str) -> Value {
    let all = swaps(app);
    let ok = all.with(profile, |s| {
        if s.secret.as_deref() == Some(secret) && s.phase == Phase::Warming {
            s.phase = Phase::Ready;
        }
        s.secret.as_deref() == Some(secret)
    });
    all.changed.notify_waiters();
    json!({ "ready": ok == Some(true) })
}

/// The new game is in the world and showing in the old window's place: the old game, holding its last frame until now, ends.
/// The longest the still waits for the old game to have ended before it fades anyway.
const OLD_WINDOW_GONE: Duration = Duration::from_secs(4);

/// The still fades only on proof: the new game reports at least this many frames presented in front, showing the world.
pub const FRAMES_FOR_STILL: u32 = 3;

pub fn joined(app: &AppHandle, profile: &str, secret: &str, frames: u32) -> Value {
    let ok = swaps(app).with(profile, |s| {
        let mine = s.secret.as_deref() == Some(secret) && matches!(s.phase, Phase::Go | Phase::Done);
        if mine {
            s.joined = true;
        }
        mine
    });
    if ok == Some(true) && still_may_go(frames) {
        // The old game hides its window and ends as soon as it hears "taken"; the still goes only once it has ended (at
        // most a few seconds), so its frozen window can never show over the new game when the still fades.
        let (app, profile) = (app.clone(), profile.to_owned());
        tauri::async_runtime::spawn(async move {
            let until = tokio::time::Instant::now() + OLD_WINDOW_GONE;
            while tokio::time::Instant::now() < until && swaps(&app).with(&profile, |s| s.phase != Phase::Done).unwrap_or(false) {
                tokio::time::sleep(Duration::from_millis(50)).await;
            }
            crate::swapstill::close_soon(&app);
        });
    } else if ok == Some(true) {
        eprintln!("[flint] instant swap: the new game reported {frames} frames on screen; the still stays up");
    }
    json!({ "joined": ok == Some(true) })
}

/// The standby game waits (at most `wait`) for the old game to hand over: the handover, or none yet.
pub async fn wait(app: &AppHandle, profile: &str, secret: &str, wait: Duration) -> Value {
    let all = swaps(app);
    let deadline = tokio::time::Instant::now() + wait;
    loop {
        let notified = all.changed.notified();
        let now = all.with(profile, |s| (s.secret.as_deref() == Some(secret), s.handover.clone()));
        match now {
            Some((true, Some(handover))) => return json!({ "go": handover }),
            Some((true, None)) => {}
            // Not this profile's standby game any more (replaced, or failed): it quits.
            _ => return json!({ "go": null, "quit": true }),
        }
        if tokio::time::timeout_at(deadline, notified).await.is_err() {
            return json!({ "go": null });
        }
    }
}

/// The old game left its world or server and hands over (`handover`: where it was and its window). It quits next.
pub async fn go(app: &AppHandle, profile: &str, handover: Value) -> CmdResult<Value> {
    let all = swaps(app);
    let (generation, sequential, pid) = all
        .with(profile, |s| match s.phase {
            Phase::Ready | Phase::Deferred => {
                let sequential = s.phase == Phase::Deferred;
                s.phase = Phase::Go;
                s.handover = Some(handover);
                Ok((s.generation, sequential, s.pid))
            }
            _ => Err("not ready".to_owned()),
        })
        .ok_or("not ready")??;
    all.changed.notify_waiters();
    // The old game's last frame stays on screen, where its window was, until the new game is in front. The old game holds
    // that frame in its own window meanwhile; when it is about to end first (sequential), it waits for the still to be up.
    if let Some(window) = all.with(profile, |s| s.handover.as_ref().map(|h| h["window"].clone())).flatten().filter(|w| w["frame"] == true) {
        let shown = crate::swapstill::show(app, &app.state::<AppState>().paths.profile_dir(profile), &window);
        if sequential {
            let _ = tokio::time::timeout(Duration::from_millis(1500), shown).await;
        }
    }
    if let Some(pid) = pid {
        normal_priority(pid);
    }
    let (app, profile) = (app.clone(), profile.to_owned());
    tauri::async_runtime::spawn(async move {
        // The old game quits now; once it has ended its locked jars can go and the new game is the profile's game.
        let state = app.state::<AppState>();
        let until = tokio::time::Instant::now() + OLD_GAME_ENDS;
        while state.running.lock().unwrap().contains_key(&profile) && tokio::time::Instant::now() < until {
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        crate::content::ingame::settle_pending(&state, &profile).await;
        if sequential {
            // Not enough memory to have started it before: it starts now and joins as soon as it is ready.
            if let Some(()) = swaps(&app).with(&profile, |s| s.phase = Phase::Go) {
                if let Err(reason) = crate::game::launch_standby(&app, &state, &profile, generation, true).await {
                    eprintln!("[flint] instant swap: the new game couldn't start after the old one ended: {reason}");
                    swaps(&app).drop_swap(&profile, generation);
                    crate::swapstill::close(&app);
                    crate::game::emit_ready(&app, &state, &profile);
                    return;
                }
            }
        }
        crate::game::took_over(&app, &state, &profile, generation);
    });
    Ok(json!({ "sequential": sequential }))
}

/// The new game shows itself at the player's priority, not the background's.
#[cfg(windows)]
fn normal_priority(pid: u32) {
    use windows_sys::Win32::Foundation::CloseHandle;
    use windows_sys::Win32::System::Threading::{OpenProcess, SetPriorityClass, NORMAL_PRIORITY_CLASS, PROCESS_SET_INFORMATION};
    // SAFETY: a handle opened for this one call and closed after it.
    unsafe {
        let handle = OpenProcess(PROCESS_SET_INFORMATION, 0, pid);
        if !handle.is_null() {
            SetPriorityClass(handle, NORMAL_PRIORITY_CLASS);
            CloseHandle(handle);
        }
    }
}

#[cfg(not(windows))]
fn normal_priority(_pid: u32) {}

/// The process creation flags of a standby game: no console, and below-normal CPU priority (the player's game comes first)
/// with normal disk and memory priority. Idle priority with Windows' background mode starved its disk reads and trimmed its
/// memory: on a 16 GB PC it was still loading textures two minutes in. It goes to normal once the Library closes.
pub const STANDBY_FLAGS: u32 = 0x0800_0000 | 0x0000_4000;
/// A new game the player is waiting for (the old one has ended): no console, normal priority.
pub const URGENT_FLAGS: u32 = 0x0800_0000;

/// A copy of the mods folder without the mods waiting to be removed (hard links where the disk allows, else copies), for a
/// standby game: the running game still has those jars loaded and locked. None when nothing waits to be removed.
pub fn mods_without_pending(game_dir: &std::path::Path, pending: &std::collections::HashSet<String>) -> CmdResult<Option<std::path::PathBuf>> {
    if pending.is_empty() {
        return Ok(None);
    }
    let mods = game_dir.join("mods");
    let target = game_dir.join(".flint-swap-mods");
    let _ = std::fs::remove_dir_all(&target);
    std::fs::create_dir_all(&target).map_err(err("Could not prepare the new game's mods"))?;
    for entry in std::fs::read_dir(&mods).map_err(err("Could not read the mods folder"))?.flatten() {
        let name = entry.file_name().to_string_lossy().into_owned();
        let path = entry.path();
        if !path.is_file() || !name.to_ascii_lowercase().ends_with(".jar") || name.starts_with('.') || pending.contains(&name) {
            continue;
        }
        let to = target.join(&name);
        if std::fs::hard_link(&path, &to).is_err() {
            std::fs::copy(&path, &to).map_err(err("Could not prepare the new game's mods"))?;
        }
    }
    // Fabric reads mod configs from `config`, not the mods folder, so nothing else is needed.
    Ok(Some(target))
}

/// Whether the new game's report proves it is on screen with pictures (else the still stays: never a white window).
pub fn still_may_go(frames: u32) -> bool {
    frames >= FRAMES_FOR_STILL
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_still_goes_only_on_proof_of_frames() {
        assert!(!still_may_go(0), "an older or silent game: the still stays");
        assert!(!still_may_go(2));
        assert!(still_may_go(3));
    }

    #[test]
    fn the_standby_copy_leaves_out_the_mods_taken_out() {
        let dir = std::env::temp_dir().join(format!("flint-swap-{}", random_hex(4)));
        std::fs::create_dir_all(dir.join("mods")).unwrap();
        for name in ["appleskin.jar", "sodium.jar", "notes.txt", ".hidden.jar"] {
            std::fs::write(dir.join("mods").join(name), name).unwrap();
        }
        assert_eq!(mods_without_pending(&dir, &Default::default()).unwrap(), None, "nothing to leave out: the mods folder itself");
        let pending = std::collections::HashSet::from(["appleskin.jar".to_owned()]);
        let copy = mods_without_pending(&dir, &pending).unwrap().unwrap();
        let mut names: Vec<String> = std::fs::read_dir(&copy).unwrap().flatten().map(|e| e.file_name().to_string_lossy().into_owned()).collect();
        names.sort();
        assert_eq!(names, ["sodium.jar"]);
        assert_eq!(std::fs::read_to_string(copy.join("sodium.jar")).unwrap(), "sodium.jar");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_standby_game_starts_when_it_really_fits() {
        // The owner's 16 GB PC with a 4 GB game running: about 5.4 GB free. 1.0.4's first build wanted 5.6 GB.
        // The final tour's gates (free/committable MB): 1468/11883, 2700/13427, 2365/13196 all went sequential before.
        assert!(fits_beside(4096, 11_883), "commit allows: the standby starts at Install");
        assert!(fits_beside(4096, 5632));
        assert!(!fits_beside(4096, 5000), "the heap couldn't be committed beside the system's margin: sequential");
        assert!(!fits_beside(8192, 9000));
    }

    #[test]
    fn a_standby_game_starts_quietly_and_last() {
        assert_eq!(STANDBY_FLAGS & 0x4000, 0x4000, "BELOW_NORMAL_PRIORITY_CLASS");
        assert_eq!(STANDBY_FLAGS & 0x40, 0, "never IDLE_PRIORITY_CLASS: it starved the standby's loading");
        assert_eq!(STANDBY_FLAGS & 0x0800_0000, 0x0800_0000, "CREATE_NO_WINDOW");
    }
}
