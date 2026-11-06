//! Java's start-up cache for Minecraft (the "AOT cache" of Java 25, JEP 483/514/515).
//!
//! Most of a Minecraft start is Java loading, checking and linking tens of thousands of classes, one at a time, on the
//! game's main thread. Java 25 can keep the classes one launch loaded, already parsed, verified and linked, with the
//! profiles of their hot methods, in a cache file that later launches map instead of redoing that work. On Minecraft
//! 26.3 with Flint that brought the title screen about 1.5-2 s sooner (QA, 6-core PC, warm launches).
//!
//! How Flint uses it, per (Java build, JVM flags, class path) — so one cache serves every profile of a Minecraft version:
//! - A launch without a cache records which classes the game loads (`-XX:AOTMode=record`); Java writes that record when
//!   the game exits.
//! - Once the game has exited cleanly, Flint builds the cache from the record in the background, at low priority
//!   (`-XX:AOTMode=create`), never while that version is starting.
//! - Later launches use it (`-XX:AOTCache`). Java itself checks that the cache matches the launch (same Java build, flags,
//!   class path, unchanged jars) and quietly ignores one that doesn't, so a stale cache can't stop the game; Flint's own key
//!   covers the same things, so a changed launch simply records and builds a new cache. The newest few caches are kept.
//!
//! Java's cache code reads every class path jar's manifest and is extremely slow on Mojang's signed client jar, whose
//! manifest lists a digest for each of its ~20,000 files (5 MB on 26.3: 40-60 s per record or build). So a launch that uses
//! the cache runs the game from a copy of the client jar without the signature (`unsigned_client_jar`): the same classes
//! and assets byte for byte. Only Fabric profiles do, where Fabric's class loader already loads the game without
//! checking that signature; vanilla profiles keep Mojang's jar.
//!
//! Every Flint line runs on Java 25: 26.x asks for it, and Flint runs 1.21.x and 1.8.9 on it too (`runtime::JAVA_FOR_LINE`),
//! so every line gets the cache. (Java 21's own dynamic class data sharing archives were tried on 1.21.11 first: starts
//! were about 1.2 s sooner, but the launch that records one reached the title screen after 54 s instead of ~10 s, and
//! after 3 minutes with Mojang's signed jar; Java 25 records with no visible slowdown.)

use crate::core::*;
use crate::data::Profile;
use sha1::{Digest, Sha1};
use std::collections::HashSet;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::{LazyLock, Mutex};

/// The first Java with the AOT cache as used here (`-XX:AOTMode=record|create`, `-XX:AOTCache`; Java 24 brought the cache,
/// 25 the method profiles), which Minecraft 26.x runs on.
const MIN_JAVA: u32 = 25;
/// Caches kept: one per Minecraft version (and Java) the player started recently. Each is 100-200 MB.
const KEEP: usize = 3;
/// Recordings of one key that ended without a cache (the game crashed or was stopped, or the build failed) before Flint
/// stops recording it, so a launch that never ends cleanly doesn't pay for recording every time.
const MAX_ATTEMPTS: u32 = 3;
/// A recording nothing built (Flint closed with the game) waits this long for a launch of its version to build it.
const STALE_RECORDING: std::time::Duration = std::time::Duration::from_secs(14 * 24 * 3600);
/// Changes when the way Flint launches with the cache changes, so older caches are rebuilt rather than trusted.
const FORMAT: &str = "flint-java-start-cache 1";

/// Keys being recorded or built by this Flint, so two games of one version don't write the same record and a game never
/// starts on a cache that is still being written.
static BUSY: LazyLock<Mutex<HashSet<String>>> = LazyLock::new(Default::default);

/// What a launch does with the cache.
#[derive(Debug, Clone, PartialEq)]
pub enum Mode {
    /// No cache for this launch (an older Java, a vanilla profile, the player's own CDS flags, or this key is busy).
    Off,
    /// No cache yet: record the classes this launch loads.
    Record { config: PathBuf },
    /// Start from the cache.
    Use { cache: PathBuf },
    /// A recording the launch that made it never built (Flint was closed with the game, or while it built) is being built in
    /// the background now; this launch goes without the cache, the next one uses it.
    Building,
}

/// This launch's decision, kept until the game exits (a recorded launch then builds the cache). A recording's key stays
/// busy for as long as its plan lives, so a launch that fails before Java starts frees it again.
#[derive(Debug)]
pub struct Plan {
    key: String,
    pub mode: Mode,
}

impl Plan {
    pub fn off() -> Self {
        Plan { key: String::new(), mode: Mode::Off }
    }

    /// For the launch log: what this launch does with the cache.
    pub fn describe(&self) -> &'static str {
        match self.mode {
            Mode::Off => "off",
            Mode::Record { .. } => "recording (built after the game exits)",
            Mode::Use { .. } => "in use",
            Mode::Building => "being built from the last recording (used from the next start)",
        }
    }

    /// The JVM options the plan adds before the main class. Java's own cache messages (a stale cache it ignores, the classes
    /// a record leaves out) are kept out of the launch log, where they would read like errors.
    pub fn jvm_args(&self) -> Vec<String> {
        let quiet = ["-Xlog:aot*=off".to_owned(), "-Xlog:cds*=off".to_owned()];
        match &self.mode {
            Mode::Off | Mode::Building => Vec::new(),
            Mode::Record { config } => [vec!["-XX:AOTMode=record".to_owned(), format!("-XX:AOTConfiguration={}", config.display())], quiet.to_vec()].concat(),
            Mode::Use { cache } => [vec![format!("-XX:AOTCache={}", cache.display())], quiet.to_vec()].concat(),
        }
    }
}

impl Drop for Plan {
    fn drop(&mut self) {
        if matches!(self.mode, Mode::Record { .. }) {
            BUSY.lock().unwrap_or_else(|e| e.into_inner()).remove(&self.key);
        }
    }
}

/// Where the caches, their logs and the unsigned client jars are kept.
pub fn dir(paths: &Paths) -> PathBuf {
    paths.cache().join("java-start")
}

/// The Java home of a `java` binary (`<home>/bin/java[w][.exe]`).
fn java_home(java: &Path) -> Option<&Path> {
    java.parent()?.parent()
}

/// The major version of the Java at `java`, from its home's `release` file (`JAVA_VERSION="25.0.1"`).
pub fn java_major(java: &Path) -> Option<u32> {
    let release = std::fs::read_to_string(java_home(java)?.join("release")).ok()?;
    parse_java_major(&release)
}

fn parse_java_major(release: &str) -> Option<u32> {
    let line = release.lines().find_map(|l| l.trim().strip_prefix("JAVA_VERSION="))?;
    let version = line.trim().trim_matches('"');
    // "1.8.0_402" is Java 8; "25.0.1", "25" and "26-ea" are 25 and 26.
    let mut parts = version.split(|c: char| !c.is_ascii_digit());
    match parts.next()?.parse::<u32>().ok()? {
        1 => parts.next()?.parse().ok(),
        major => Some(major),
    }
}

/// Whether a JVM option already asks for class data sharing of its own; Flint then leaves the cache to the player.
fn touches_class_sharing(arg: &str) -> bool {
    let lower = arg.to_ascii_lowercase();
    ["-xx:aot", "-xx:+aot", "-xx:-aot", "-xx:sharedarchivefile", "-xx:archiveclassesatexit", "-xx:+autocreatesharedarchive", "-xx:sharedclasslistfile", "-xshare", "-xx:dumploadedclasslist"]
        .iter()
        .any(|prefix| lower.starts_with(prefix))
}

/// Whether this launch gets the cache: only when turned on (`"javaStartCache": true` in settings.json), for a Fabric profile
/// on Java 25 or newer whose player passes no class data sharing options of their own.
///
/// Off by default since 2026-11-03: both garbage-collector crashes seen on the owner's PC (an access violation in a G1
/// worker thread, on Mojang's Java 25.0.1 and on Temurin 25.0.5) were launches using the cache with compact object
/// headers, while about 50 heavy benchmark sessions and 24 test starts without it never crashed that way. It saved about
/// 2 seconds per start; a crash costs far more.
pub fn applies(profile: &Profile, java_major: Option<u32>, extra_jvm_args: &[String], turned_on: bool) -> bool {
    turned_on && profile.loader == "fabric" && java_major.is_some_and(|major| major >= MIN_JAVA) && !extra_jvm_args.iter().any(|a| touches_class_sharing(a))
}

/// The newest Java the cache options were tried with. A newer one is first asked whether it still takes them.
const TESTED_JAVA: u32 = 25;

/// Answers of Javas newer than `TESTED_JAVA`, asked once per run.
static PROBED: LazyLock<Mutex<std::collections::HashMap<PathBuf, bool>>> = LazyLock::new(Default::default);

/// Whether the Java at `java` takes the cache options. A Java that didn't would refuse to start the game, so one newer than
/// `TESTED_JAVA` is asked first (`-XX:AOTMode=off -version`, a fraction of a second, once per run), and the launch goes
/// without the cache if it says no. Blocking.
pub fn accepts_options(java: &Path, major: u32) -> bool {
    if major <= TESTED_JAVA {
        return true;
    }
    if let Some(known) = PROBED.lock().unwrap_or_else(|e| e.into_inner()).get(java) {
        return *known;
    }
    let mut command = std::process::Command::new(java);
    command.args(["-XX:AOTMode=off", "-version"]).stdin(std::process::Stdio::null()).stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x0800_0000);
    }
    let accepted = command.status().is_ok_and(|status| status.success());
    PROBED.lock().unwrap_or_else(|e| e.into_inner()).insert(java.to_owned(), accepted);
    accepted
}

/// Size and modification time, to tell a changed file from the one a cache was made with.
fn stamp(path: &Path) -> Option<(u64, u128)> {
    let meta = std::fs::metadata(path).ok()?;
    let modified = meta.modified().ok()?.duration_since(std::time::UNIX_EPOCH).ok()?.as_nanos();
    Some((meta.len(), modified))
}

/// The class path in JVM options (`-cp X`, `-classpath X`, `--class-path X` or `--class-path=X`).
fn class_path(jvm_args: &[String]) -> Vec<String> {
    let separator = if cfg!(windows) { ';' } else { ':' };
    let mut found = None;
    for (i, arg) in jvm_args.iter().enumerate() {
        if matches!(arg.as_str(), "-cp" | "-classpath" | "--class-path") {
            found = jvm_args.get(i + 1).cloned();
        } else if let Some(value) = arg.strip_prefix("--class-path=") {
            found = Some(value.to_owned());
        }
    }
    found.map(|cp| cp.split(separator).filter(|p| !p.is_empty()).map(str::to_owned).collect()).unwrap_or_default()
}

/// The cache's key: everything Java checks a cache against (its build, the options, the class path and its jars), so a
/// launch that differs gets a cache of its own instead of one Java would refuse.
pub fn key(java: &Path, jvm_args: &[String]) -> String {
    let mut hash = Sha1::new();
    let mut add = |part: &str| {
        hash.update(part.as_bytes());
        hash.update([0]);
    };
    add(FORMAT);
    add(&java.display().to_string());
    let home = java_home(java);
    add(&home.and_then(|h| std::fs::read_to_string(h.join("release")).ok()).unwrap_or_default());
    let vm = home.and_then(|h| {
        ["bin/server/jvm.dll", "lib/server/libjvm.so", "lib/server/libjvm.dylib"].iter().map(|p| h.join(p)).find(|p| p.is_file())
    });
    add(&format!("{:?}", vm.as_deref().and_then(stamp).or_else(|| stamp(java))));
    for arg in jvm_args {
        add(arg);
    }
    for jar in class_path(jvm_args) {
        add(&format!("{jar} {:?}", stamp(Path::new(&jar))));
    }
    hex::encode(&hash.finalize()[..10])
}

/// Recordings of a key that haven't produced a cache yet.
fn attempts(dir: &Path, key: &str) -> u32 {
    std::fs::read_to_string(dir.join(format!("{key}.attempts"))).ok().and_then(|t| t.trim().parse().ok()).unwrap_or(0)
}

/// Decides what this launch does, from its final JVM options (up to the main class). Never fails: anything unexpected
/// means a launch without the cache.
pub fn plan(paths: &Paths, java: &Path, jvm_args: &[String]) -> Plan {
    let dir = dir(paths);
    if std::fs::create_dir_all(&dir).is_err() {
        return Plan::off();
    }
    prune(&dir, KEEP);
    let key = key(java, jvm_args);
    let cache = dir.join(format!("{key}.aot"));
    let mut busy = BUSY.lock().unwrap_or_else(|e| e.into_inner());
    if busy.contains(&key) {
        return Plan::off();
    }
    if cache.is_file() {
        // Marks it as recently used, for `prune`.
        let _ = std::fs::File::options().append(true).open(&cache).and_then(|f| f.set_modified(std::time::SystemTime::now()));
        return Plan { key, mode: Mode::Use { cache } };
    }
    let config = dir.join(format!("{key}.aotconf"));
    // A clean recording whose launch never built it (the launcher closed with the game, or before the build finished):
    // built now in the background, and used from the next launch on.
    if std::fs::metadata(&config).is_ok_and(|m| m.len() > 0) {
        busy.insert(key.clone());
        drop(busy);
        let (dir, key, java, jvm_args) = (dir.clone(), key.clone(), java.to_owned(), jvm_args.to_vec());
        tauri::async_runtime::spawn(async move {
            let built = build(&dir, &key, &java, &jvm_args, &config).await;
            let _ = std::fs::remove_file(&config);
            match built {
                Ok(()) => {
                    let _ = std::fs::remove_file(dir.join(format!("{key}.attempts")));
                }
                Err(error) => eprintln!("[flint] Java's start-up cache wasn't built from the last recording: {error}"),
            }
            BUSY.lock().unwrap_or_else(|e| e.into_inner()).remove(&key);
            prune(&dir, KEEP);
        });
        return Plan { key: String::new(), mode: Mode::Building };
    }
    let tried = attempts(&dir, &key);
    if tried >= MAX_ATTEMPTS {
        return Plan::off();
    }
    let _ = std::fs::remove_file(&config);
    let _ = std::fs::write(dir.join(format!("{key}.attempts")), (tried + 1).to_string());
    remember_java(&dir, &key, java);
    busy.insert(key.clone());
    Plan { key, mode: Mode::Record { config } }
}

/// The options to build the cache with: the launch's own (the cache must match them), without the record's.
fn create_args(jvm_args: &[String], config: &Path, part: &Path) -> Vec<String> {
    let mut args: Vec<String> = jvm_args.iter().filter(|a| !a.starts_with("-XX:AOTMode=") && !a.starts_with("-XX:AOTConfiguration=")).cloned().collect();
    args.push("-XX:AOTMode=create".into());
    args.push(format!("-XX:AOTConfiguration={}", config.display()));
    args.push(format!("-XX:AOTCache={}", part.display()));
    args
}

/// The options that check a built cache before it is used: the launch's own, with the cache required
/// (`-XX:AOTMode=on`, which makes Java fail instead of ignoring a cache it can't use), and `-version` to exit right away.
fn check_args(jvm_args: &[String], cache: &Path) -> Vec<String> {
    let mut args: Vec<String> = jvm_args.iter().filter(|a| !a.starts_with("-XX:AOTMode=") && !a.starts_with("-XX:AOTConfiguration=")).cloned().collect();
    args.push(format!("-XX:AOTCache={}", cache.display()));
    args.push("-XX:AOTMode=on".into());
    args.push("-version".into());
    args
}

/// After the game exited: a recorded launch that ended cleanly builds its cache in the background, at low priority. The
/// key stays busy until then, so no launch starts on a half-written cache. Without a cache, a later launch records again,
/// up to `MAX_ATTEMPTS` recordings.
pub async fn after_exit(dir: PathBuf, plan: Plan, java: PathBuf, jvm_args: Vec<String>, clean_exit: bool) {
    let Mode::Record { config } = &plan.mode else { return };
    let built = if clean_exit && config.is_file() { build(&dir, &plan.key, &java, &jvm_args, config).await } else { Err("the game didn't exit cleanly".into()) };
    let _ = std::fs::remove_file(config);
    match built {
        Ok(()) => {
            let _ = std::fs::remove_file(dir.join(format!("{}.attempts", plan.key)));
            prune(&dir, KEEP);
        }
        Err(error) => eprintln!("[flint] Java's start-up cache wasn't built: {error}"),
    }
    // Dropping the plan frees the key.
}

/// Runs Java in the background with its output appended to the key's log: no window, below normal priority (the player
/// may already be doing something else).
async fn run_java(java: &Path, args: &[String], log: &Path) -> CmdResult<std::process::ExitStatus> {
    let log = std::fs::File::options().create(true).append(true).open(log).map_err(|e| e.to_string())?;
    let log_err = log.try_clone().map_err(|e| e.to_string())?;
    let mut command = tokio::process::Command::new(java);
    command.args(args).stdin(std::process::Stdio::null()).stdout(log).stderr(log_err);
    #[cfg(windows)]
    {
        command.creation_flags(0x0800_0000 | 0x0000_4000);
    }
    command.status().await.map_err(|e| e.to_string())
}

/// Builds the cache from a record, then has Java confirm it would use it for this launch before it is put in place.
async fn build(dir: &Path, key: &str, java: &Path, jvm_args: &[String], config: &Path) -> CmdResult<()> {
    let part = dir.join(format!("{key}.aot.part"));
    let log = dir.join(format!("{key}.log"));
    let _ = std::fs::remove_file(&log);
    let built = run_java(java, &create_args(jvm_args, config, &part), &log).await?;
    let checked = if built.success() && part.is_file() { run_java(java, &check_args(jvm_args, &part), &log).await?.success() } else { false };
    if !checked {
        let _ = std::fs::remove_file(&part);
        return Err(format!("Java couldn't build a cache it would use (exit {built}; see {key}.log)"));
    }
    std::fs::rename(&part, dir.join(format!("{key}.aot"))).map_err(|e| e.to_string())
}

/// Keeps the `keep` most recently used caches and removes the rest (and their logs), along with leftovers of builds that
/// never finished.
fn prune(dir: &Path, keep: usize) {
    sweep_orphans(dir);
    let Ok(entries) = std::fs::read_dir(dir) else { return };
    let busy = BUSY.lock().unwrap_or_else(|e| e.into_inner()).clone();
    let mut caches = Vec::new();
    for entry in entries.flatten() {
        let path = entry.path();
        let name = entry.file_name().to_string_lossy().into_owned();
        let key = name.split('.').next().unwrap_or_default().to_owned();
        if busy.contains(&key) {
            continue;
        }
        let modified = entry.metadata().and_then(|m| m.modified()).ok();
        if name.ends_with(".aot") {
            caches.push((modified, key, path));
        } else if name.ends_with(".aot.part") {
            let _ = std::fs::remove_file(&path);
        } else if name.ends_with(".aotconf") && modified.is_some_and(|m| m.elapsed().is_ok_and(|age| age > STALE_RECORDING)) {
            // A recording waits for its build (`plan`), unless that version hasn't been played for weeks.
            let _ = std::fs::remove_file(&path);
        }
    }
    caches.sort_by_key(|c| std::cmp::Reverse(c.0));
    for (_, key, path) in caches.into_iter().skip(keep) {
        let _ = std::fs::remove_file(&path);
        let _ = std::fs::remove_file(dir.join(format!("{key}.log")));
    }
}

/// Notes which Java a key's files were made for (`<key>.java`), so `sweep_orphans` can tell when that Java is gone.
fn remember_java(dir: &Path, key: &str, java: &Path) {
    let _ = std::fs::write(dir.join(format!("{key}.java")), java.display().to_string());
}

/// Removes every file of a key whose Java no longer exists where it was: a Java Flint replaced, or Flint's whole data folder
/// moved (every path in the launch then changed, so those caches could never be used again; the launch after the move
/// records anew under a new key).
fn sweep_orphans(dir: &Path) {
    let Ok(entries) = std::fs::read_dir(dir) else { return };
    let busy = BUSY.lock().unwrap_or_else(|e| e.into_inner()).clone();
    let names: Vec<String> = entries.flatten().map(|e| e.file_name().to_string_lossy().into_owned()).collect();
    for name in &names {
        let Some(key) = name.strip_suffix(".java") else { continue };
        let Ok(java) = std::fs::read_to_string(dir.join(name)) else { continue };
        if busy.contains(key) || Path::new(java.trim()).exists() {
            continue;
        }
        for file in names.iter().filter(|n| n.split('.').next() == Some(key)) {
            let _ = std::fs::remove_file(dir.join(file));
        }
    }
}

/// Whether a jar entry is part of a jar signature (`META-INF/*.SF|RSA|DSA|EC`), which the unsigned copy leaves out.
fn is_signature_file(name: &str) -> bool {
    let upper = name.to_ascii_uppercase();
    upper.starts_with("META-INF/") && !upper["META-INF/".len()..].contains('/') && [".SF", ".RSA", ".DSA", ".EC"].iter().any(|ext| upper.ends_with(ext))
}

/// A manifest's main section (up to the first empty line), without the per-file digests that follow it.
fn main_section(manifest: &str) -> String {
    let mut out = String::new();
    for line in manifest.split_inclusive('\n') {
        if line.trim_end_matches(['\r', '\n']).is_empty() {
            break;
        }
        out.push_str(line);
    }
    if !out.ends_with('\n') {
        out.push_str("\r\n");
    }
    out.push_str("\r\n");
    out
}

/// Copies a jar without its signature: the signature files are left out, the manifest keeps only its main section, and
/// every other entry is copied as it is stored (no recompression, same bytes).
fn write_unsigned(source: &Path, target: &Path) -> CmdResult<()> {
    let io = |e: std::io::Error| e.to_string();
    let zip = |e: zip::result::ZipError| e.to_string();
    let mut archive = zip::ZipArchive::new(std::io::BufReader::new(std::fs::File::open(source).map_err(io)?)).map_err(zip)?;
    let part = target.with_extension("jar.part");
    let mut writer = zip::ZipWriter::new(std::io::BufWriter::new(std::fs::File::create(&part).map_err(io)?));
    for i in 0..archive.len() {
        let name = archive.by_index_raw(i).map_err(zip)?.name().to_owned();
        if is_signature_file(&name) {
            continue;
        }
        if name.eq_ignore_ascii_case("META-INF/MANIFEST.MF") {
            let mut manifest = String::new();
            archive.by_index(i).map_err(zip)?.read_to_string(&mut manifest).map_err(io)?;
            writer.start_file(name, zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated)).map_err(zip)?;
            writer.write_all(main_section(&manifest).as_bytes()).map_err(io)?;
            continue;
        }
        writer.raw_copy_file(archive.by_index_raw(i).map_err(zip)?).map_err(zip)?;
    }
    writer.finish().map_err(zip)?.flush().map_err(io)?;
    std::fs::rename(&part, target).map_err(io)
}

/// The client jar a cached launch runs from: an unsigned copy of Mojang's (see the module notes), made on first use, about
/// half a second, and replaced when the original changes. Older copies of the same version are removed.
pub fn unsigned_client_jar(paths: &Paths, client_jar: &Path) -> CmdResult<PathBuf> {
    let (size, modified) = stamp(client_jar).ok_or("the client jar is missing")?;
    let stem = client_jar.file_stem().and_then(|s| s.to_str()).ok_or("unexpected client jar name")?;
    let mut hash = Sha1::new();
    hash.update(format!("{}\0{size}\0{modified}", client_jar.display()).as_bytes());
    let dir = dir(paths).join("client");
    let target = dir.join(format!("{stem}-{}.jar", hex::encode(&hash.finalize()[..6])));
    if target.is_file() {
        return Ok(target);
    }
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    write_unsigned(client_jar, &target)?;
    if let Ok(entries) = std::fs::read_dir(&dir) {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            let old_copy = name.strip_prefix(stem).and_then(|rest| rest.strip_prefix('-')).is_some_and(|rest| rest.len() == 16 && rest.ends_with(".jar"));
            if old_copy && entry.path() != target {
                let _ = std::fs::remove_file(entry.path());
            }
        }
    }
    Ok(target)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::TestDir;

    /// A file where a Java would be (`sweep_orphans` keeps caches whose Java exists); it can't run.
    fn fake_java(root: &Path) -> PathBuf {
        let java = root.join("jdk").join("bin").join("java");
        std::fs::create_dir_all(java.parent().unwrap()).unwrap();
        std::fs::write(&java, b"").unwrap();
        java
    }

    fn profile(loader: &str) -> Profile {
        Profile { id: "p".into(), name: "P".into(), game_version: "26.3".into(), loader: loader.into(), ..Default::default() }
    }

    #[test]
    fn java_versions_are_read_from_the_release_file() {
        assert_eq!(parse_java_major("IMPLEMENTOR=\"Microsoft\"\nJAVA_VERSION=\"25.0.1\"\nMODULES=\"java.base\""), Some(25));
        assert_eq!(parse_java_major("JAVA_VERSION=\"21.0.7\""), Some(21));
        assert_eq!(parse_java_major("JAVA_VERSION=\"1.8.0_402\""), Some(8));
        assert_eq!(parse_java_major("JAVA_VERSION=\"26-ea\""), Some(26));
        assert_eq!(parse_java_major("JAVA_VERSION=25"), Some(25));
        assert_eq!(parse_java_major("MODULES=\"java.base\""), None);
    }

    #[test]
    fn only_fabric_profiles_on_java_25_or_newer_get_the_cache_and_only_when_turned_on() {
        assert!(!applies(&profile("fabric"), Some(25), &[], false), "off unless turned on (the GC crashes)");
        assert!(applies(&profile("fabric"), Some(25), &[], true));
        assert!(applies(&profile("fabric"), Some(26), &["-XX:+UseZGC".into()], true), "a future Java keeps it");
        assert!(!applies(&profile("fabric"), Some(21), &[], true), "Java 21 has no AOT cache");
        assert!(!applies(&profile("fabric"), None, &[], true), "an unknown Java is left alone");
        assert!(!applies(&profile("vanilla"), Some(25), &[], true), "vanilla keeps Mojang's signed jar");
        for own in ["-XX:AOTCache=x.aot", "-XX:SharedArchiveFile=a.jsa", "-Xshare:off", "-XX:+AutoCreateSharedArchive", "-XX:+AOTClassLinking"] {
            assert!(!applies(&profile("fabric"), Some(25), &[own.into()], true), "{own} is the player's own");
        }
    }

    #[test]
    fn a_java_newer_than_tested_must_take_the_options_first() {
        let missing = Path::new("no-such-java/bin/java");
        assert!(accepts_options(missing, TESTED_JAVA), "the tested Java isn't asked");
        assert!(!accepts_options(missing, TESTED_JAVA + 1), "a newer Java that can't answer gets no cache");
        assert_eq!(PROBED.lock().unwrap().get(missing), Some(&false), "asked once per run");
    }

    #[test]
    fn the_class_path_is_found_in_every_spelling() {
        let sep = if cfg!(windows) { ";" } else { ":" };
        let cp = format!("a.jar{sep}b.jar");
        for args in [vec!["-cp".to_owned(), cp.clone()], vec!["-classpath".to_owned(), cp.clone()], vec![format!("--class-path={cp}")]] {
            assert_eq!(class_path(&args), vec!["a.jar".to_owned(), "b.jar".to_owned()]);
        }
        assert!(class_path(&["-Xmx2G".to_owned()]).is_empty());
    }

    #[test]
    fn the_key_changes_with_the_options_and_the_jars() {
        let dir = TestDir::new();
        let jar = dir.0.join("lib.jar");
        std::fs::write(&jar, b"one").unwrap();
        let java = fake_java(&dir.0);
        let args = |extra: &str| vec!["-Xmx4096M".to_owned(), extra.to_owned(), "-cp".to_owned(), jar.display().to_string()];
        let first = key(&java, &args("-XX:+UseG1GC"));
        assert_eq!(first, key(&java, &args("-XX:+UseG1GC")), "stable for the same launch");
        assert_ne!(first, key(&java, &args("-XX:+UseZGC")), "other options");
        assert_ne!(first, key(&dir.0.join("other").join("bin").join("java"), &args("-XX:+UseG1GC")), "another Java");
        std::fs::write(&jar, b"changed").unwrap();
        assert_ne!(first, key(&java, &args("-XX:+UseG1GC")), "a changed jar");
    }

    #[test]
    fn a_launch_records_then_uses_the_cache_and_never_shares_a_busy_key() {
        let dir = TestDir::new();
        let paths = Paths::new(dir.0.clone());
        let java = fake_java(&dir.0);
        let args = vec!["-Xmx2G".to_owned(), format!("-Dunique={}", random_hex(6))];
        let first = plan(&paths, &java, &args);
        let Mode::Record { config } = first.mode.clone() else { panic!("no cache yet: record, got {:?}", first.mode) };
        assert!(first.jvm_args().contains(&"-XX:AOTMode=record".to_owned()));
        assert!(first.jvm_args().contains(&format!("-XX:AOTConfiguration={}", config.display())));
        assert_eq!(plan(&paths, &java, &args).mode, Mode::Off, "a second game of the same version while the first records");
        // The build itself needs a real Java; here the record is "built" by hand.
        let cache = config.with_extension("aot");
        std::fs::write(&cache, b"cache").unwrap();
        drop(first);
        let next = plan(&paths, &java, &args);
        assert_eq!(next.mode, Mode::Use { cache: cache.clone() });
        assert_eq!(next.jvm_args()[0], format!("-XX:AOTCache={}", cache.display()));
        assert!(next.jvm_args().contains(&"-Xlog:aot*=off".to_owned()));
        assert!(Plan::off().jvm_args().is_empty());
    }

    /// The launcher closed with the game (or the "close Flint when the game starts" setting): the recording stayed, unbuilt.
    /// The next launch builds it in the background and goes without; nothing records over it meanwhile.
    #[tokio::test]
    async fn a_recording_nothing_built_is_built_by_the_next_launch() {
        let dir = TestDir::new();
        let paths = Paths::new(dir.0.clone());
        let java = fake_java(&dir.0);
        let args = vec![format!("-Dunique={}", random_hex(6))];
        let first = plan(&paths, &java, &args);
        let Mode::Record { config } = first.mode.clone() else { panic!() };
        std::fs::write(&config, b"record").unwrap();
        drop(first);
        let next = plan(&paths, &java, &args);
        assert_eq!(next.mode, Mode::Building);
        assert!(next.jvm_args().is_empty(), "this launch goes without the cache");
        assert_eq!(next.describe(), "being built from the last recording (used from the next start)");
        // The fake Java fails as soon as it starts, so the build may already be over here. Still marked busy after the
        // call means it was busy during it: then nothing records over it.
        let during = plan(&paths, &java, &args).mode.clone();
        if BUSY.lock().unwrap().contains(&key(&java, &args)) {
            assert_eq!(during, Mode::Off, "busy while it builds: nothing records over it");
        }
        // The fake Java can't build: once the build has failed, the key is free again and the next launch records.
        for _ in 0..100 {
            tokio::time::sleep(std::time::Duration::from_millis(50)).await;
            if !BUSY.lock().unwrap().contains(&key(&java, &args)) {
                break;
            }
        }
        assert!(!config.exists(), "the record is used up either way");
        assert!(matches!(plan(&paths, &java, &args).mode, Mode::Record { .. }));
    }

    #[test]
    fn repeated_recordings_without_a_cache_stop() {
        let dir = TestDir::new();
        let paths = Paths::new(dir.0.clone());
        let java = fake_java(&dir.0);
        let args = vec![format!("-Dunique={}", random_hex(6))];
        let k = key(&java, &args);
        std::fs::create_dir_all(super::dir(&paths)).unwrap();
        for _ in 0..MAX_ATTEMPTS {
            let recording = plan(&paths, &java, &args);
            assert!(matches!(recording.mode, Mode::Record { .. }));
        }
        assert_eq!(attempts(&super::dir(&paths), &k), MAX_ATTEMPTS);
        assert_eq!(plan(&paths, &java, &args).mode, Mode::Off);
    }

    #[tokio::test]
    async fn an_unclean_exit_builds_nothing_and_frees_the_key() {
        let dir = TestDir::new();
        let paths = Paths::new(dir.0.clone());
        let java = fake_java(&dir.0);
        let args = vec![format!("-Dunique={}", random_hex(6))];
        let first = plan(&paths, &java, &args);
        let Mode::Record { config } = first.mode.clone() else { panic!() };
        std::fs::write(&config, b"record").unwrap();
        after_exit(super::dir(&paths), first, java.clone(), args.clone(), false).await;
        assert!(!config.exists(), "the record is dropped");
        assert!(matches!(plan(&paths, &java, &args).mode, Mode::Record { .. }), "the next launch records again");
    }

    #[test]
    fn building_keeps_the_launch_options_and_swaps_record_for_create() {
        let args = vec!["-Xmx4G".to_owned(), "-XX:AOTMode=record".to_owned(), "-XX:AOTConfiguration=a.aotconf".to_owned(), "-cp".to_owned(), "x.jar".to_owned()];
        let built = create_args(&args, Path::new("a.aotconf"), Path::new("k.aot.part"));
        assert_eq!(built, ["-Xmx4G", "-cp", "x.jar", "-XX:AOTMode=create", "-XX:AOTConfiguration=a.aotconf", "-XX:AOTCache=k.aot.part"]);
        let checked = check_args(&args, Path::new("k.aot.part"));
        assert_eq!(checked, ["-Xmx4G", "-cp", "x.jar", "-XX:AOTCache=k.aot.part", "-XX:AOTMode=on", "-version"]);
    }

    #[test]
    fn only_the_newest_caches_are_kept() {
        let dir = TestDir::new();
        for (i, key) in ["aaaa", "bbbb", "cccc", "dddd"].iter().enumerate() {
            let cache = dir.0.join(format!("{key}.aot"));
            std::fs::write(&cache, b"x").unwrap();
            std::fs::write(dir.0.join(format!("{key}.log")), b"log").unwrap();
            let when = std::time::SystemTime::now() - std::time::Duration::from_secs(1000 - i as u64 * 100);
            std::fs::File::options().append(true).open(&cache).unwrap().set_modified(when).unwrap();
        }
        std::fs::write(dir.0.join("eeee.aot.part"), b"half").unwrap();
        prune(&dir.0, 2);
        let mut left: Vec<String> = std::fs::read_dir(&dir.0).unwrap().flatten().map(|e| e.file_name().to_string_lossy().into_owned()).collect();
        left.sort();
        assert_eq!(left, ["cccc.aot", "cccc.log", "dddd.aot", "dddd.log"]);
    }

    #[test]
    fn caches_made_for_a_java_that_is_gone_are_removed() {
        let dir = TestDir::new();
        let live_java = dir.0.join("java.exe");
        std::fs::write(&live_java, b"").unwrap();
        // The data folder moved ("com.flintclient.desktop" to "Flint Client"): the old key's Java path no longer exists.
        let old_java = dir.0.join("com.flintclient.desktop").join("runtimes").join("bin").join("javaw.exe");
        for (key, java) in [("oldkey", &old_java), ("newkey", &live_java), ("olderkey", &old_java)] {
            remember_java(&dir.0, key, java);
        }
        for name in ["oldkey.aot", "oldkey.log", "oldkey.attempts", "olderkey.aotconf", "newkey.aot", "newkey.log"] {
            std::fs::write(dir.0.join(name), b"x").unwrap();
        }
        prune(&dir.0, KEEP);
        let mut left: Vec<String> = std::fs::read_dir(&dir.0).unwrap().flatten().map(|e| e.file_name().to_string_lossy().into_owned()).collect();
        left.sort();
        assert_eq!(left, ["java.exe", "newkey.aot", "newkey.java", "newkey.log"]);
    }

    #[test]
    fn signature_files_and_manifest_digests_are_recognised() {
        assert!(is_signature_file("META-INF/MOJANGCS.SF"));
        assert!(is_signature_file("META-INF/MOJANGCS.RSA"));
        assert!(is_signature_file("META-INF/mojang_c.dsa"));
        assert!(!is_signature_file("META-INF/MANIFEST.MF"));
        assert!(!is_signature_file("META-INF/LICENSE"));
        assert!(!is_signature_file("META-INF/versions/9/x.SF"), "only the signature directory itself");
        assert_eq!(main_section("Manifest-Version: 1.0\r\nMain-Class: net.minecraft.client.Main\r\n\r\nName: a.class\r\nSHA-384-Digest: x\r\n"), "Manifest-Version: 1.0\r\nMain-Class: net.minecraft.client.Main\r\n\r\n");
        assert_eq!(main_section("Manifest-Version: 1.0\n"), "Manifest-Version: 1.0\n\r\n");
    }

    fn jar_with(path: &Path, entries: &[(&str, &[u8], zip::CompressionMethod)]) {
        let mut writer = zip::ZipWriter::new(std::fs::File::create(path).unwrap());
        for (name, bytes, method) in entries {
            writer.start_file(*name, zip::write::SimpleFileOptions::default().compression_method(*method)).unwrap();
            writer.write_all(bytes).unwrap();
        }
        writer.finish().unwrap();
    }

    #[test]
    fn the_unsigned_copy_keeps_every_class_and_asset_byte_for_byte() {
        let dir = TestDir::new();
        let paths = Paths::new(dir.0.clone());
        let original = dir.0.join("26.3.jar");
        let class = vec![0xCAu8; 4096];
        jar_with(&original, &[
            ("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\r\nMain-Class: net.minecraft.client.Main\r\n\r\nName: a/B.class\r\nSHA-384-Digest: abc\r\n", zip::CompressionMethod::Deflated),
            ("META-INF/MOJANGCS.SF", b"sig", zip::CompressionMethod::Deflated),
            ("META-INF/MOJANGCS.RSA", b"sig", zip::CompressionMethod::Stored),
            ("a/B.class", &class, zip::CompressionMethod::Deflated),
            ("assets/minecraft/x.json", b"{}", zip::CompressionMethod::Stored),
        ]);
        let copy = unsigned_client_jar(&paths, &original).unwrap();
        assert_ne!(copy, original);
        assert_eq!(unsigned_client_jar(&paths, &original).unwrap(), copy, "made once");
        let mut archive = zip::ZipArchive::new(std::fs::File::open(&copy).unwrap()).unwrap();
        let names: Vec<String> = archive.file_names().map(str::to_owned).collect();
        assert_eq!(names.len(), 3);
        assert!(!names.iter().any(|n| n.ends_with(".SF") || n.ends_with(".RSA")));
        let mut read = |name: &str| {
            let mut bytes = Vec::new();
            archive.by_name(name).unwrap().read_to_end(&mut bytes).unwrap();
            bytes
        };
        assert_eq!(read("a/B.class"), class);
        assert_eq!(read("assets/minecraft/x.json"), b"{}");
        assert_eq!(String::from_utf8(read("META-INF/MANIFEST.MF")).unwrap(), "Manifest-Version: 1.0\r\nMain-Class: net.minecraft.client.Main\r\n\r\n");
        // A changed original gets a new copy, and the old one goes.
        std::thread::sleep(std::time::Duration::from_millis(20));
        jar_with(&original, &[("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\r\n", zip::CompressionMethod::Stored), ("a/B.class", b"new", zip::CompressionMethod::Stored)]);
        let newer = unsigned_client_jar(&paths, &original).unwrap();
        assert_ne!(newer, copy);
        assert!(newer.is_file() && !copy.exists());
    }
}

/// End to end with a real Java 25, which the normal test run doesn't have: records, builds and checks, then uses a cache
/// for a small program, all under a folder named "Flint Client" (a space in every path the options name). Run by hand with
/// `cargo test --lib real_java -- --ignored --nocapture`, with FLINT_TEST_HELLO_JAR (a jar whose `Hello` class has a main
/// method) and FLINT_TEST_JAVA25 (the path of a Java 25 `java` binary).
#[cfg(test)]
mod real_java {
    use super::*;

    #[tokio::test]
    #[ignore]
    async fn java_25_records_builds_and_uses_an_aot_cache() {
        let java = PathBuf::from(std::env::var("FLINT_TEST_JAVA25").unwrap());
        let dir = crate::core::TestDir::new();
        let paths = Paths::new(dir.0.join("Flint Client"));
        let jar = paths.root.join("lib with space").join("hello.jar");
        std::fs::create_dir_all(jar.parent().unwrap()).unwrap();
        std::fs::copy(std::env::var("FLINT_TEST_HELLO_JAR").unwrap(), &jar).unwrap();
        let args = vec!["-Xmx256M".to_owned(), "-XX:+UseG1GC".to_owned(), "-cp".to_owned(), jar.display().to_string()];
        for round in 0..2 {
            let plan = plan(&paths, &java, &args);
            eprintln!("round {round}: {:?}", plan.mode);
            if round == 0 {
                assert!(matches!(plan.mode, Mode::Record { .. }), "no cache yet");
            } else {
                assert!(matches!(plan.mode, Mode::Use { .. }), "the cache built after round 0");
            }
            let mut full = args.clone();
            full.extend(plan.jvm_args());
            full.push("Hello".into());
            let out = std::process::Command::new(&java).args(&full).output().unwrap();
            assert!(out.status.success(), "{}", String::from_utf8_lossy(&out.stderr));
            after_exit(super::dir(&paths), plan, java.clone(), args.clone(), true).await;
        }
        assert!(matches!(plan(&paths, &java, &args).mode, Mode::Use { .. }));
    }
}
