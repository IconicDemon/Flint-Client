//! Where Flint keeps its data: one folder named "Flint Client" in the system's application data folder
//! (`%APPDATA%\Flint Client`, `~/Library/Application Support/Flint Client`, `~/.local/share/Flint Client`): profiles,
//! games, settings, and the window's own web data (WebView2's `EBWebView` on Windows) beside them.
//!
//! Older versions of Flint named that folder after the app's bundle identifier (`com.flintclient.desktop`) and kept the web
//! data in a second one in the local application data folder. At start, before anything opens a file in it, the old
//! folder is renamed to the new name: one step on the same disk, so nothing is copied and nothing can be left half-moved.
//! It is only done while nothing uses the old folder (no other Flint, no game started from it; on Windows a running game
//! also keeps the rename from happening at all): otherwise this start keeps using it as it is, and the next start tries
//! again. Nothing is ever deleted but the old web data (a cache), and only once the new folder has its own.

use std::path::{Path, PathBuf};

/// The data folder's name ("Flint Client"; a staging test build keeps its own, "Flint Client Staging": channel.rs).
pub const FOLDER: &str = crate::channel::DATA_FOLDER;
/// The folder older versions of Flint used (named after the bundle identifier, which stays as it is).
const LEGACY: &str = "com.flintclient.desktop";
/// WebView2's folder in the web data directory.
const WEB_DATA: &str = "EBWebView";

/// The folders this run uses.
#[derive(Debug, PartialEq, Eq)]
pub struct DataDirs {
    /// Profiles, games and settings (Tauri's app data directory).
    pub data: PathBuf,
    /// The window's web data (Tauri's app local data directory, where WebView2 keeps its folder).
    pub local: PathBuf,
}

/// The folders for this run, after moving the old folder to the new name when it can be. None when the system has no
/// application data folder (then Tauri's defaults stand).
pub fn settle() -> Option<DataDirs> {
    let base = dirs::data_dir()?;
    let local_base = dirs::data_local_dir()?;
    // A staging test build never adopts an older Flint's folder: that data belongs to the real app.
    if crate::channel::STAGING {
        let root = base.join(FOLDER);
        return Some(DataDirs { data: root.clone(), local: root });
    }
    let legacy = base.join(LEGACY);
    Some(settle_in(&base, &local_base, || legacy_in_use(&legacy)))
}

fn settle_in(base: &Path, local_base: &Path, legacy_in_use: impl FnOnce() -> Option<String>) -> DataDirs {
    let root = base.join(FOLDER);
    let legacy = base.join(LEGACY);
    let legacy_local = local_base.join(LEGACY);
    if legacy.is_dir() && !has_entries(&root) {
        if let Some(why) = legacy_in_use() {
            eprintln!("Flint: keeping the data folder at {} for now ({why}); it moves to {} on a later start", legacy.display(), root.display());
            return DataDirs { data: legacy, local: legacy_local };
        }
        // An empty folder of the new name (made by hand, or left by an uninstall) is in the way of the rename.
        if root.is_dir() {
            let _ = std::fs::remove_dir(&root);
        }
        if let Err(error) = std::fs::rename(&legacy, &root) {
            eprintln!("Flint: couldn't move the data folder to {} ({error}); using {} for now", root.display(), legacy.display());
            return DataDirs { data: legacy, local: legacy_local };
        }
        eprintln!("Flint: moved the data folder to {}", root.display());
    }
    if legacy_local != root {
        adopt_web_data(&legacy_local, &root);
    }
    DataDirs { data: root.clone(), local: root }
}

/// The old web data folder: moved in beside the rest; once the new folder has web data of its own, the old copy (a cache:
/// it holds no more than the Library's grid or list view) is removed, and the old folder with it once empty.
fn adopt_web_data(legacy_local: &Path, root: &Path) {
    let from = legacy_local.join(WEB_DATA);
    let to = root.join(WEB_DATA);
    if from.is_dir() {
        if to.exists() {
            let _ = std::fs::remove_dir_all(&from);
        } else if std::fs::create_dir_all(root).is_err() || std::fs::rename(&from, &to).is_err() {
            // In use, or on another disk: it stays, and the window starts a fresh one.
            return;
        }
    }
    let _ = std::fs::remove_dir(legacy_local);
}

fn has_entries(dir: &Path) -> bool {
    std::fs::read_dir(dir).is_ok_and(|mut entries| entries.next().is_some())
}

/// Why the old folder must stay where it is for now, if it must: another Flint is running (it keeps using the old path),
/// or a game started from it is.
fn legacy_in_use(legacy: &Path) -> Option<String> {
    #[cfg(windows)]
    {
        // The single-instance plugin's mutex (tauri-plugin-single-instance: "<identifier>-sim"): a Flint is running.
        use windows_sys::Win32::System::Threading::{OpenMutexW, SYNCHRONIZATION_SYNCHRONIZE};
        let name: Vec<u16> = format!("{LEGACY}-sim").encode_utf16().chain(std::iter::once(0)).collect();
        // SAFETY: a valid, nul-terminated name; the handle is closed at once.
        let handle = unsafe { OpenMutexW(SYNCHRONIZATION_SYNCHRONIZE, 0, name.as_ptr()) };
        if !handle.is_null() {
            unsafe { windows_sys::Win32::Foundation::CloseHandle(handle) };
            return Some("Flint is already running".into());
        }
        // Another copy of Flint (a development build holds no mutex) may be using it. And Windows can rename a folder a
        // running program was started from, and a game would then lose the folder it saves into: so no program may run
        // from it (Flint's own Java runtimes are in it) and no Java may name it.
        windows_processes_using(legacy, own_exe_name().as_deref())
    }
    #[cfg(not(windows))]
    {
        // Everything running that names the old folder (a game, its Java) or is this same app, wherever it was started
        // from (another Flint, or a development build of it).
        let scan = crate::core::ProcessScan::take();
        let exe = own_exe_name();
        match scan.lines() {
            Err(error) => Some(format!("couldn't check what is running: {error}")),
            Ok(lines) => {
                let own = std::process::id().to_string();
                let folder = legacy.display().to_string();
                lines.iter().find_map(|line| {
                    let (pid, command) = line.trim_start().split_once(' ').unwrap_or((line, ""));
                    let command = command.trim_start();
                    if pid == own {
                        None
                    } else if command.contains(&folder) {
                        Some("a game started from it is running".to_owned())
                    } else if exe.as_deref().is_some_and(|exe| runs_program(command, exe)) {
                        Some("Flint is already running".to_owned())
                    } else {
                        None
                    }
                })
            }
        }
    }
}

/// Windows: a game (a Java naming `folder` on its command line) or a program started from it is running. Elsewhere
/// `core::ProcessScan` sees games, so this is None.
pub fn game_using(folder: &Path) -> Option<String> {
    #[cfg(windows)]
    {
        windows_processes_using(folder, None)
    }
    #[cfg(not(windows))]
    {
        let _ = folder;
        None
    }
}

/// This program's file name, lowercased ("flint-app.exe").
fn own_exe_name() -> Option<String> {
    std::env::current_exe().ok()?.file_name().map(|name| name.to_string_lossy().to_lowercase())
}

/// Whether a command line (as `ps` shows it, unquoted: "/Applications/Flint Client.app/Contents/MacOS/flint-app") runs
/// a program of the file name `exe`: a word of it ends in "/<exe>". A stray match only puts the move off to a later start.
#[cfg(any(not(windows), test))]
fn runs_program(command: &str, exe: &str) -> bool {
    command.split_whitespace().any(|word| word.rsplit('/').next().is_some_and(|name| name.eq_ignore_ascii_case(exe)) && word.contains('/'))
}

/// Another copy of this program (`own_exe`, by file name: a development build runs from elsewhere), a running program
/// started from `folder`, or a Java given it on its command line (a game, with a Java of its own).
#[cfg(windows)]
fn windows_processes_using(folder: &Path, own_exe: Option<&str>) -> Option<String> {
    use windows_sys::Wdk::System::Threading::{NtQueryInformationProcess, ProcessCommandLineInformation};
    use windows_sys::Win32::Foundation::{CloseHandle, INVALID_HANDLE_VALUE, UNICODE_STRING};
    use windows_sys::Win32::System::Diagnostics::ToolHelp::{CreateToolhelp32Snapshot, Process32FirstW, Process32NextW, PROCESSENTRY32W, TH32CS_SNAPPROCESS};
    use windows_sys::Win32::System::Threading::{OpenProcess, QueryFullProcessImageNameW, PROCESS_QUERY_LIMITED_INFORMATION};

    let needle = folder.display().to_string().to_lowercase();
    let own = std::process::id();
    // SAFETY: every handle is checked before use and closed; buffers are sized as the calls are told.
    unsafe {
        let snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
        if snapshot == INVALID_HANDLE_VALUE {
            return Some("couldn't check what is running".into());
        }
        let mut entry: PROCESSENTRY32W = std::mem::zeroed();
        entry.dwSize = std::mem::size_of::<PROCESSENTRY32W>() as u32;
        let mut found = None;
        let mut more = Process32FirstW(snapshot, &mut entry) != 0;
        while more && found.is_none() {
            let pid = entry.th32ProcessID;
            let exe_len = entry.szExeFile.iter().position(|&c| c == 0).unwrap_or(entry.szExeFile.len());
            let exe = String::from_utf16_lossy(&entry.szExeFile[..exe_len]).to_lowercase();
            if pid != own && own_exe.is_some_and(|name| name == exe) {
                found = Some("Flint is already running".into());
                break;
            }
            let process = if pid == own || pid == 0 { std::ptr::null_mut() } else { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid) };
            if !process.is_null() {
                let mut path = [0u16; 1024];
                let mut len = path.len() as u32;
                if QueryFullProcessImageNameW(process, 0, path.as_mut_ptr(), &mut len) != 0
                    && String::from_utf16_lossy(&path[..len as usize]).to_lowercase().starts_with(&needle)
                {
                    found = Some(format!("{exe} is running from it"));
                } else if exe == "java.exe" || exe == "javaw.exe" {
                    let mut needed = 0u32;
                    NtQueryInformationProcess(process, ProcessCommandLineInformation, std::ptr::null_mut(), 0, &mut needed);
                    if needed > 0 && needed < 1 << 20 {
                        // u64s: the UNICODE_STRING at its start is pointer-aligned.
                        let mut buffer = vec![0u64; (needed as usize).div_ceil(8)];
                        if NtQueryInformationProcess(process, ProcessCommandLineInformation, buffer.as_mut_ptr().cast(), needed, &mut needed) >= 0 {
                            let text = &*(buffer.as_ptr() as *const UNICODE_STRING);
                            if !text.Buffer.is_null() {
                                let line = std::slice::from_raw_parts(text.Buffer, text.Length as usize / 2);
                                if String::from_utf16_lossy(line).to_lowercase().contains(&needle) {
                                    found = Some("a game started from it is running".into());
                                }
                            }
                        }
                    }
                }
                CloseHandle(process);
            }
            more = Process32NextW(snapshot, &mut entry) != 0;
        }
        CloseHandle(snapshot);
        found
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::TestDir;

    /// Nothing runs from a fresh folder; a program started from it (a copy of Windows' ping, waiting) is seen.
    #[cfg(windows)]
    #[test]
    fn programs_running_from_a_folder_are_seen() {
        let dir = TestDir::new();
        assert_eq!(windows_processes_using(&dir.0, None), None);
        let system = std::env::var("SystemRoot").unwrap_or_else(|_| r"C:\Windows".into());
        let ping = dir.0.join("ping.exe");
        std::fs::copy(Path::new(&system).join(r"System32\PING.EXE"), &ping).unwrap();
        let mut child = std::process::Command::new(&ping).args(["-n", "4", "127.0.0.1"]).stdout(std::process::Stdio::null()).spawn().unwrap();
        std::thread::sleep(std::time::Duration::from_millis(200));
        let seen = windows_processes_using(&dir.0, None);
        let _ = child.kill();
        let _ = child.wait();
        assert!(seen.is_some_and(|why| why.contains("running from it")));
    }

    #[test]
    fn a_command_line_running_the_app_is_recognised() {
        assert!(runs_program("/Applications/Flint Client.app/Contents/MacOS/flint-app", "flint-app"));
        assert!(runs_program("/home/me/flint/target/debug/flint-app --onboarding", "flint-app"));
        assert!(!runs_program("/usr/bin/java -jar flint-app-helper.jar", "flint-app"));
        assert!(!runs_program("grep flint-app", "flint-app"), "a bare word is no program path");
    }

    /// Another copy of this program is seen by its file name, wherever it runs from (a development build elsewhere).
    #[cfg(windows)]
    #[test]
    fn another_copy_of_the_app_is_seen_wherever_it_runs_from() {
        let dir = TestDir::new();
        let system = std::env::var("SystemRoot").unwrap_or_else(|_| r"C:\Windows".into());
        let copy = dir.0.join("flint-copy-test.exe");
        std::fs::copy(Path::new(&system).join(r"System32\PING.EXE"), &copy).unwrap();
        let elsewhere = TestDir::new();
        assert_eq!(windows_processes_using(&elsewhere.0, Some("flint-copy-test.exe")), None);
        let mut child = std::process::Command::new(&copy).args(["-n", "4", "127.0.0.1"]).stdout(std::process::Stdio::null()).spawn().unwrap();
        std::thread::sleep(std::time::Duration::from_millis(200));
        let seen = windows_processes_using(&elsewhere.0, Some("flint-copy-test.exe"));
        let _ = child.kill();
        let _ = child.wait();
        assert_eq!(seen.as_deref(), Some("Flint is already running"));
    }

    /// A Java whose command line names the folder (as a game's `--gameDir` does) is seen. Needs a JDK on the PATH (it runs a
    /// one-file program that waits): `cargo test --lib datadir -- --ignored`.
    #[cfg(windows)]
    #[test]
    #[ignore]
    fn a_java_naming_the_folder_is_seen() {
        let dir = TestDir::new();
        let folder = dir.0.join("Some Data");
        std::fs::create_dir_all(&folder).unwrap();
        let program = dir.0.join("Wait.java");
        std::fs::write(&program, "class Wait { public static void main(String[] a) throws Exception { Thread.sleep(8000); } }").unwrap();
        let mut java = std::process::Command::new("java")
            .arg(&program)
            .arg("--gameDir")
            .arg(folder.join("profiles").join("p1"))
            .spawn()
            .expect("a java on the PATH");
        std::thread::sleep(std::time::Duration::from_millis(1500));
        let seen = windows_processes_using(&folder, None);
        let _ = java.kill();
        let _ = java.wait();
        assert_eq!(seen.as_deref(), Some("a game started from it is running"));
    }

    fn layout() -> (TestDir, PathBuf, PathBuf) {
        let dir = TestDir::new();
        let base = dir.0.join("Roaming");
        let local = dir.0.join("Local");
        std::fs::create_dir_all(&base).unwrap();
        std::fs::create_dir_all(&local).unwrap();
        (dir, base, local)
    }

    fn old_install(base: &Path, local: &Path) {
        let profile = base.join(LEGACY).join("profiles/p1/saves/World");
        std::fs::create_dir_all(&profile).unwrap();
        std::fs::write(profile.join("level.dat"), "world").unwrap();
        std::fs::write(base.join(LEGACY).join("settings.json"), "{}").unwrap();
        std::fs::create_dir_all(local.join(LEGACY).join(WEB_DATA).join("Default")).unwrap();
        std::fs::write(local.join(LEGACY).join(WEB_DATA).join("Default/Preferences"), "{}").unwrap();
    }

    #[test]
    fn the_old_folder_moves_whole_and_the_web_data_comes_along() {
        let (_dir, base, local) = layout();
        old_install(&base, &local);
        let dirs = settle_in(&base, &local, || None);
        let root = base.join(FOLDER);
        assert_eq!(dirs, DataDirs { data: root.clone(), local: root.clone() });
        assert_eq!(std::fs::read_to_string(root.join("profiles/p1/saves/World/level.dat")).unwrap(), "world");
        assert!(root.join("settings.json").is_file());
        assert!(root.join(WEB_DATA).join("Default/Preferences").is_file(), "the web data moved in beside the rest");
        assert!(!base.join(LEGACY).exists() && !local.join(LEGACY).exists(), "no old folder is left");
        // The next start finds everything in place and changes nothing.
        assert_eq!(settle_in(&base, &local, || panic!("nothing to move, nothing to check")), dirs);
        assert!(root.join("profiles/p1/saves/World/level.dat").is_file());
    }

    #[test]
    fn a_folder_in_use_stays_where_it_is_until_a_later_start() {
        let (_dir, base, local) = layout();
        old_install(&base, &local);
        let dirs = settle_in(&base, &local, || Some("Flint is already running".into()));
        assert_eq!(dirs, DataDirs { data: base.join(LEGACY), local: local.join(LEGACY) });
        assert!(base.join(LEGACY).join("settings.json").is_file() && !base.join(FOLDER).exists());
        assert!(local.join(LEGACY).join(WEB_DATA).is_dir(), "the web data stays with it");
        let dirs = settle_in(&base, &local, || None);
        assert_eq!(dirs.data, base.join(FOLDER));
        assert!(base.join(FOLDER).join("settings.json").is_file());
    }

    #[test]
    fn a_new_folder_with_data_is_never_replaced() {
        let (_dir, base, local) = layout();
        old_install(&base, &local);
        let root = base.join(FOLDER);
        std::fs::create_dir_all(&root).unwrap();
        std::fs::write(root.join("settings.json"), "new").unwrap();
        let dirs = settle_in(&base, &local, || panic!("no move is tried"));
        assert_eq!(dirs.data, root);
        assert_eq!(std::fs::read_to_string(root.join("settings.json")).unwrap(), "new");
        assert!(base.join(LEGACY).join("profiles/p1/saves/World/level.dat").is_file(), "the old folder is left alone");
    }

    #[test]
    fn an_empty_new_folder_does_not_stop_the_move() {
        let (_dir, base, local) = layout();
        old_install(&base, &local);
        std::fs::create_dir_all(base.join(FOLDER)).unwrap();
        assert_eq!(settle_in(&base, &local, || None).data, base.join(FOLDER));
        assert!(base.join(FOLDER).join("settings.json").is_file());
    }

    #[test]
    fn a_fresh_install_uses_the_new_folder() {
        let (_dir, base, local) = layout();
        let dirs = settle_in(&base, &local, || panic!("nothing to move"));
        assert_eq!(dirs, DataDirs { data: base.join(FOLDER), local: base.join(FOLDER) });
    }

    #[test]
    fn stale_old_web_data_goes_once_the_new_folder_has_its_own() {
        let (_dir, base, local) = layout();
        std::fs::create_dir_all(base.join(FOLDER).join(WEB_DATA)).unwrap();
        std::fs::write(base.join(FOLDER).join("settings.json"), "{}").unwrap();
        std::fs::create_dir_all(local.join(LEGACY).join(WEB_DATA).join("Default")).unwrap();
        settle_in(&base, &local, || None);
        assert!(!local.join(LEGACY).exists());
        assert!(base.join(FOLDER).join(WEB_DATA).is_dir());
    }

    #[test]
    fn other_things_in_the_old_local_folder_are_kept() {
        let (_dir, base, local) = layout();
        old_install(&base, &local);
        std::fs::write(local.join(LEGACY).join("notes.txt"), "mine").unwrap();
        settle_in(&base, &local, || None);
        assert_eq!(std::fs::read_to_string(local.join(LEGACY).join("notes.txt")).unwrap(), "mine");
        assert!(base.join(FOLDER).join(WEB_DATA).is_dir());
    }
}
