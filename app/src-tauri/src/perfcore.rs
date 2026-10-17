//! Flint Performance Core, the launcher's half: what a launch passes to Java, the per-launch config
//! the in-game runtime (`game-runtime/`) reads, and a check of the jars in a profile's `mods` folder.
//!
//! Settings → "Automatic Optimization" is its one switch. Off, a launch passes Java exactly the
//! flags Flint 0.4.0 did (`JvmPolicy::Legacy`). On, versions that run on Java 25 or newer also get
//! compact object headers (`JvmPolicy::CompactHeaders`), the one change benchmarks supported; see
//! `choose_policy`.
//!
//! Hardware detection is local and kept in memory for the app run: no GPU queries, no identifiers,
//! no network. The only file written is the per-launch config in the profile's `config` folder.

use crate::core::*;
use crate::data::Profile;
use serde::Serialize;
use serde_json::{json, Value};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::path::Path;
use std::sync::OnceLock;

/// The runtime's config, in the profile's `config` folder (the game's `FabricLoader.getConfigDir()`).
pub const CONFIG_FILE: &str = "flint-performance.json";
const CONFIG_SCHEMA: u32 = 1;
/// A `fabric.mod.json` larger than this isn't read (real ones are a few KB).
const MAX_MOD_JSON: u64 = 1024 * 1024;

/// Settings → "Automatic Optimization" (on unless turned off). Off: Flint 0.4.0's Java flags, and
/// the runtime's optimization modules switched off. The runtime's lifecycle fixes and the mod checks
/// don't depend on it.
pub fn automatic_optimization_enabled(state: &AppState) -> bool {
    crate::data::load_settings(state)["automaticOptimization"].as_bool().unwrap_or(true)
}

// ---------- hardware ----------

#[derive(Serialize, Clone, Debug, Default, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HardwareInfo {
    pub memory_mb: Option<u64>,
    pub logical_cpus: Option<u32>,
    /// Physical cores of each kind (Apple silicon's performance and efficiency cores); None where
    /// the system doesn't report them.
    pub performance_cores: Option<u32>,
    pub efficiency_cores: Option<u32>,
    pub arch: String,
    pub os: String,
}

/// macOS keys read in one `sysctl -n` call. The perflevel keys are last because a Mac without them
/// (one kind of core, or an older macOS) simply leaves them out of the output.
const SYSCTL_KEYS: [&str; 4] = ["hw.memsize", "hw.logicalcpu", "hw.perflevel0.physicalcpu", "hw.perflevel1.physicalcpu"];

static HARDWARE: OnceLock<HardwareInfo> = OnceLock::new();

/// This computer's memory and processors, detected on first use and kept for the app run.
pub fn hardware() -> &'static HardwareInfo {
    HARDWARE.get_or_init(detect_hardware)
}

fn detect_hardware() -> HardwareInfo {
    let mut info = if cfg!(target_os = "macos") {
        crate::data::sysctl(&SYSCTL_KEYS).map(|out| parse_sysctl(&out)).unwrap_or_default()
    } else {
        HardwareInfo { memory_mb: crate::data::total_memory().map(|bytes| bytes / (1024 * 1024)), ..Default::default() }
    };
    if info.logical_cpus.is_none() {
        info.logical_cpus = std::thread::available_parallelism().ok().and_then(|n| u32::try_from(n.get()).ok());
    }
    info.arch = std::env::consts::ARCH.into();
    info.os = std::env::consts::OS.into();
    info
}

/// Reads `sysctl -n` output for `SYSCTL_KEYS`: one value per line, in order, with missing keys
/// left out at the end.
fn parse_sysctl(output: &str) -> HardwareInfo {
    let mut lines = output.lines().map(str::trim);
    let mut next = || lines.next().and_then(|line| line.parse::<u64>().ok());
    let memory = next();
    let count = |n: Option<u64>| n.and_then(|n| u32::try_from(n).ok()).filter(|&n| n > 0);
    HardwareInfo {
        memory_mb: memory.map(|bytes| bytes / (1024 * 1024)).filter(|&mb| mb > 0),
        logical_cpus: count(next()),
        performance_cores: count(next()),
        efficiency_cores: count(next()),
        ..Default::default()
    }
}

// ---------- Java ----------

/// Which Java flags (garbage collector and the like) a launch uses. Recorded in the runtime's
/// config as `jvm.policy`: "legacy" or "compactHeaders".
#[derive(Serialize, Clone, Copy, Debug, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum JvmPolicy {
    /// Flint 0.4.0's flags (`gc_args`).
    Legacy,
    /// `Legacy` plus compact object headers (`COMPACT_HEADERS`).
    CompactHeaders,
}

/// Compact object headers (JEP 519, a product flag since Java 25; Mojang's own launch settings for
/// 26.x turn it on too) shrink Java's object headers to 64 bits, from 96 or 128. Measured on one
/// MacBook Air (Apple M2, 16 GB, plugged in, Low Power Mode off; Minecraft 26.3, Java 25.0.1,
/// Sodium and the rest of the performance set, two to four runs per configuration) on top of
/// `gc_args`: frame rate and frame-time percentiles unchanged within run-to-run spread; the heap left
/// after young collections while standing, turning and travelling 5% smaller at the median
/// (647 → 615 MB) and 4% at p90 on the performance set, 3% and 1% with Flint's runtime added. Mojang's
/// complete 26.x set (ZGC, a 2 GB starting heap, pre-touched memory, string deduplication) was
/// measured the same way and rejected: it was slower and its peak memory was about 1.1 GB higher
/// (3.5 vs 2.4 GB).
const COMPACT_HEADERS: &str = "-XX:+UseCompactObjectHeaders";
/// The first Java with `COMPACT_HEADERS` as a product flag. Java 24 had it only as an experimental
/// option, and older Javas don't know it and refuse to start.
const COMPACT_HEADERS_JAVA: u64 = 25;

/// The policy for a launch: `CompactHeaders` when Automatic Optimization is on and the version
/// needs Java 25 or newer (`javaVersion.majorVersion` in its vanilla metadata; Fabric's profile
/// doesn't carry it), `Legacy` otherwise. Flint runs such a version with its own Java of at least
/// that version, or with a custom Java that `game::java_runtime` turns down when it reports an older
/// one. A custom Java whose version can't be read is still accepted; if it were older than 25 the
/// game couldn't run on it with or without the flag. 1.21.11 (Java 21), older versions and metadata
/// without a Java version keep `Legacy`.
///
/// `hardware` isn't used by any rule yet: the measurements come from a single machine, which is no
/// evidence for hardware-specific choices.
pub fn choose_policy(automatic: bool, _hardware: &HardwareInfo, version_json: &Value) -> JvmPolicy {
    let java = version_json["javaVersion"]["majorVersion"].as_u64();
    if automatic && java.is_some_and(|major| major >= COMPACT_HEADERS_JAVA) {
        JvmPolicy::CompactHeaders
    } else {
        JvmPolicy::Legacy
    }
}

/// Flint 0.4.0's garbage-collector settings: the G1 set the official Minecraft Launcher passed by
/// default before 26.x, and still passes on Windows older than 10 version 1803 (26.x metadata,
/// `default-user-jvm`). Compared with the JVM's defaults (a 200 ms pause goal), they ask G1 for
/// short pauses (50 ms), keep a larger young generation for Minecraft's many short-lived objects
/// and hold back headroom so collections don't fall behind, which is what causes long stalls.
/// G1 itself is the default collector on Java 9+, and is selected explicitly for Java 8.
fn gc_args() -> Vec<String> {
    [
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+UseG1GC",
        "-XX:G1NewSizePercent=20",
        "-XX:G1ReservePercent=20",
        "-XX:MaxGCPauseMillis=50",
        "-XX:G1HeapRegionSize=32M",
    ]
    .map(str::to_owned)
    .to_vec()
}

/// The policy's Java flags, apart from the heap size (see `heap_args`).
pub fn tuning_args(policy: JvmPolicy) -> Vec<String> {
    let mut args = gc_args();
    if policy == JvmPolicy::CompactHeaders {
        args.push(COMPACT_HEADERS.to_owned());
    }
    args
}

/// The heap flag: `-Xmx` with the profile's memory, as Flint has always set it, whatever the policy.
pub fn heap_args(memory_mb: u64) -> Vec<String> {
    vec![format!("-Xmx{memory_mb}M")]
}

// ---------- the runtime's config ----------

/// What a launch decided, recorded for the in-game runtime.
pub struct LaunchTuning {
    pub automatic: bool,
    pub policy: JvmPolicy,
    pub heap_max_mb: u64,
}

/// Developer use only, never shown in the app: `FLINT_DEV_FRAMESTATS=1` for the launcher makes the
/// runtime log frame statistics.
pub fn frame_stats_log_requested() -> bool {
    std::env::var("FLINT_DEV_FRAMESTATS").is_ok_and(|v| v == "1")
}

/// The config file's contents (schema 1). `modules` holds per-module on/off overrides; empty means
/// each module's own default.
pub fn runtime_config(profile: &Profile, tuning: &LaunchTuning, hardware: &HardwareInfo, frame_stats_log: bool) -> Value {
    json!({
        "schema": CONFIG_SCHEMA,
        "writtenBy": format!("Flint {}", env!("CARGO_PKG_VERSION")),
        "automaticOptimization": tuning.automatic,
        "profile": { "gameVersion": profile.game_version, "loader": profile.loader },
        "hardware": hardware,
        "jvm": { "policy": tuning.policy, "heapMaxMb": tuning.heap_max_mb },
        "modules": {},
        "dev": { "frameStatsLog": frame_stats_log },
    })
}

/// Writes the config into a profile's game folder (atomically), or removes it when the runtime
/// isn't installed there. Never stops a launch.
pub fn sync_runtime_config(game_dir: &Path, config: Option<&Value>) -> CmdResult<()> {
    let path = game_dir.join("config").join(CONFIG_FILE);
    match config {
        Some(config) => {
            // Module switches and developer flags someone set by hand for diagnosis survive the
            // rewrite at each Play (a flag the launcher turns on itself stays on).
            let mut config = config.clone();
            let old = read_json_opt::<Value>(&path).unwrap_or_default();
            for section in ["modules", "dev"] {
                let Some(kept) = old[section].as_object() else { continue };
                let Some(target) = config.get_mut(section).and_then(Value::as_object_mut) else { continue };
                for (key, on) in kept.iter().filter(|(_, on)| on.as_bool() == Some(true) || (section == "modules" && on.is_boolean())) {
                    if target.get(key).and_then(Value::as_bool) != Some(true) {
                        target.insert(key.clone(), on.clone());
                    }
                }
            }
            // A `config` folder Flint can't write to (locked, owned by another user) didn't stop a
            // launch before this file existed. The runtime then reads the previous launch's copy, or
            // its defaults when there is none.
            if let Err(e) = write_json(&path, &config) {
                eprintln!("[flint] couldn't save {CONFIG_FILE} for the game: {e}");
            }
            Ok(())
        }
        None => {
            // Nothing reads it without the runtime, so a copy that can't be removed doesn't stop a launch.
            let _ = std::fs::remove_file(&path);
            Ok(())
        }
    }
}

// ---------- mod audit ----------

/// What a jar in the `mods` folder declares in its `fabric.mod.json`.
#[derive(Clone, Debug)]
pub struct ModJar {
    pub file: String,
    pub id: String,
    pub version: String,
    /// Other mod ids this jar stands in for.
    pub provides: Vec<String>,
    /// Mod id → version predicate (a string or a list of them) this mod can't run with.
    pub breaks: BTreeMap<String, Value>,
    /// The `depends.minecraft` predicate (a string or a list of them), if any.
    pub minecraft: Value,
}

impl ModJar {
    fn ids(&self) -> impl Iterator<Item = &str> {
        std::iter::once(self.id.as_str()).chain(self.provides.iter().map(String::as_str).filter(|p| *p != self.id))
    }
}

/// The Fabric mods Fabric Loader would load from `dir`: its top-level `.jar` files (not
/// `.disabled` ones, hidden files or subfolders, which Fabric skips too), sorted by file name.
/// Jars that aren't readable Fabric mods are left out.
pub fn scan_mods(dir: &Path) -> Vec<ModJar> {
    let Ok(entries) = std::fs::read_dir(dir) else { return Vec::new() };
    let mut names: Vec<String> = entries
        .flatten()
        .filter_map(|e| e.file_name().into_string().ok())
        .filter(|name| name.ends_with(".jar") && !name.starts_with('.'))
        .collect();
    names.sort();
    names
        .into_iter()
        .filter(|name| dir.join(name).is_file())
        .filter_map(|name| read_mod_json(&dir.join(&name)).and_then(|bytes| parse_mod_json(name, &bytes)))
        .collect()
}

fn read_mod_json(jar: &Path) -> Option<Vec<u8>> {
    use std::io::Read;
    let file = std::fs::File::open(jar).ok()?;
    let mut archive = zip::ZipArchive::new(file).ok()?;
    let entry = archive.by_name("fabric.mod.json").ok()?;
    if entry.size() > MAX_MOD_JSON {
        return None;
    }
    let mut bytes = Vec::new();
    // The declared size can lie; never read past the limit.
    entry.take(MAX_MOD_JSON + 1).read_to_end(&mut bytes).ok()?;
    (bytes.len() as u64 <= MAX_MOD_JSON).then_some(bytes)
}

fn parse_mod_json(file: String, bytes: &[u8]) -> Option<ModJar> {
    let bytes = bytes.strip_prefix(b"\xEF\xBB\xBF").unwrap_or(bytes);
    let json: Value = serde_json::from_slice(bytes).ok()?;
    // Server-only mods aren't loaded on the client, so they can't clash with anything there.
    if json["environment"] == "server" {
        return None;
    }
    Some(ModJar {
        file,
        id: json["id"].as_str().filter(|id| !id.is_empty())?.to_owned(),
        version: json["version"].as_str().unwrap_or_default().to_owned(),
        provides: json["provides"].as_array().into_iter().flatten().filter_map(Value::as_str).map(str::to_owned).collect(),
        breaks: json["breaks"].as_object().map(|o| o.iter().map(|(k, v)| (k.clone(), v.clone())).collect()).unwrap_or_default(),
        minecraft: json["depends"]["minecraft"].clone(),
    })
}

/// Whether a jar plainly says it is built for this Minecraft version: a `depends.minecraft` entry
/// such as `26.3`, `~26.3`, `>=26.3` or `26.3.x`. Ranges written any other way (`>=26.1`, `*`) count
/// as no, so Flint installs its own copy beside it as 0.4.0 did; Fabric then loads the one that fits.
pub fn declares_minecraft(jar: &ModJar, version: &str) -> bool {
    let fits = |p: &str| {
        let p = p.trim().trim_start_matches(['~', '^', '=', '>', '<']).trim();
        p == version || p.strip_prefix(version).is_some_and(|rest| rest.starts_with(['.', '-', '+']))
    };
    match &jar.minecraft {
        Value::String(p) => fits(p),
        Value::Array(list) => list.iter().filter_map(Value::as_str).any(fits),
        _ => false,
    }
}

/// Mod ids declared by jars that are built for this Minecraft version (see `declares_minecraft`).
pub fn mod_ids_for(mods: &[ModJar], version: &str) -> HashSet<String> {
    mods.iter().filter(|m| declares_minecraft(m, version)).flat_map(ModJar::ids).map(str::to_owned).collect()
}

/// Every mod id the jars declare, directly or through `provides`.
#[cfg(test)]
pub fn mod_ids(mods: &[ModJar]) -> HashSet<String> {
    mods.iter().flat_map(ModJar::ids).map(str::to_owned).collect()
}

/// A `breaks` predicate that rules out every version of the other mod.
fn breaks_every_version(predicate: &Value) -> bool {
    match predicate {
        Value::String(s) => s.trim() == "*",
        Value::Array(list) => list.iter().any(|p| p.as_str().is_some_and(|s| s.trim() == "*")),
        _ => false,
    }
}

/// Pairs of jars claiming the same mod id (directly or through `provides`), naming both files.
/// Not a reason to stop a launch: Fabric Loader keeps one of them (the newest) and silently
/// ignores the other, and the game starts as it did before Flint checked.
pub fn duplicate_mods(mods: &[ModJar]) -> Vec<String> {
    let mut duplicates = Vec::new();
    let mut owners: HashMap<&str, &ModJar> = HashMap::new();
    let mut reported: HashSet<(&str, &str)> = HashSet::new();
    for jar in mods {
        for id in jar.ids() {
            match owners.get(id) {
                Some(first) if first.file == jar.file => {}
                Some(first) => {
                    if reported.insert((first.file.as_str(), jar.file.as_str())) {
                        duplicates.push(format!("{} and {} are the same mod ({id}); Fabric loads only one of them", first.file, jar.file));
                    }
                }
                None => {
                    owners.insert(id, jar);
                }
            }
        }
    }
    duplicates
}

/// Mods that can't run together, as messages for the player: a mod whose `breaks` names another
/// present mod for every version (`"*"`). Fabric refuses to start then. Narrower predicates
/// (`"<2.0"`, …) are left to Fabric: telling whether one matches needs Fabric's own version
/// semantics, and it explains those itself.
pub fn mod_conflicts(mods: &[ModJar]) -> Vec<String> {
    let claimed_elsewhere = |id: &str, jar: &ModJar| mods.iter().find(|o| o.file != jar.file && o.ids().any(|other| other == id));
    let mut problems = Vec::new();
    for jar in mods {
        // Fabric may load another jar with this id instead (see `duplicate_mods`), and then these
        // `breaks` never apply; only a launch Fabric is sure to refuse is stopped.
        if claimed_elsewhere(&jar.id, jar).is_some() {
            continue;
        }
        for (id, predicate) in &jar.breaks {
            if !breaks_every_version(predicate) || jar.ids().any(|own| own == id) {
                continue;
            }
            if let Some(other) = claimed_elsewhere(id, jar) {
                problems.push(format!(
                    "{} ({} {}) doesn't work with {} ({id}), and Minecraft won't start with both. Remove or switch off one of them.",
                    jar.file, jar.id, jar.version, other.file
                ));
            }
        }
    }
    problems
}

/// Writes a minimal mod jar containing `mod_json` as its `fabric.mod.json`, for tests.
#[cfg(test)]
pub fn test_jar(dir: &Path, name: &str, mod_json: &str) {
    use std::io::Write;
    std::fs::create_dir_all(dir).unwrap();
    let mut zip = zip::ZipWriter::new(std::fs::File::create(dir.join(name)).unwrap());
    zip.start_file("fabric.mod.json", zip::write::SimpleFileOptions::default()).unwrap();
    zip.write_all(mod_json.as_bytes()).unwrap();
    zip.finish().unwrap();
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Version metadata needing `component` (Mojang's Java runtime) of the given major version.
    fn needs_java(component: &str, major: u64) -> Value {
        json!({ "javaVersion": { "component": component, "majorVersion": major }, "arguments": { "jvm": [], "game": [] } })
    }

    const LEGACY: [&str; 6] = [
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+UseG1GC",
        "-XX:G1NewSizePercent=20",
        "-XX:G1ReservePercent=20",
        "-XX:MaxGCPauseMillis=50",
        "-XX:G1HeapRegionSize=32M",
    ];

    #[test]
    fn automatic_optimization_is_on_unless_turned_off() {
        let dir = TestDir::new();
        let state = AppState::for_tests(dir.0.clone());
        assert!(automatic_optimization_enabled(&state), "no settings file");
        write_json(&state.paths.settings(), &json!({ "memoryMb": 4096 })).unwrap();
        assert!(automatic_optimization_enabled(&state), "settings saved before the switch existed");
        write_json(&state.paths.settings(), &json!({ "automaticOptimization": "no" })).unwrap();
        assert!(automatic_optimization_enabled(&state), "a value that isn't a boolean is ignored");
        write_json(&state.paths.settings(), &json!({ "automaticOptimization": false })).unwrap();
        assert!(!automatic_optimization_enabled(&state));
        write_json(&state.paths.settings(), &json!({ "automaticOptimization": true })).unwrap();
        assert!(automatic_optimization_enabled(&state));
    }

    #[test]
    fn sysctl_output_is_read_in_key_order() {
        // An Apple M2 with 16 GB.
        let m2 = parse_sysctl("17179869184\n8\n4\n4\n");
        assert_eq!((m2.memory_mb, m2.logical_cpus, m2.performance_cores, m2.efficiency_cores), (Some(16384), Some(8), Some(4), Some(4)));
        // One kind of core: sysctl leaves the missing perflevel1 out.
        let one_kind = parse_sysctl("8589934592\n4\n4\n");
        assert_eq!((one_kind.memory_mb, one_kind.logical_cpus, one_kind.performance_cores, one_kind.efficiency_cores), (Some(8192), Some(4), Some(4), None));
        let old_macos = parse_sysctl("8589934592\n4\n");
        assert_eq!((old_macos.performance_cores, old_macos.efficiency_cores), (None, None));
        let garbage = parse_sysctl("sysctl: unknown oid\n\n");
        assert_eq!(garbage, HardwareInfo::default());
        assert_eq!(parse_sysctl(""), HardwareInfo::default());
        assert_eq!(parse_sysctl("0\n0\n").logical_cpus, None, "zero isn't a real count");
    }

    #[test]
    fn detected_hardware_names_this_system() {
        let info = hardware();
        assert_eq!(info.os, std::env::consts::OS);
        assert_eq!(info.arch, std::env::consts::ARCH);
        assert!(info.logical_cpus.is_some_and(|n| n > 0));
        assert!(std::ptr::eq(info, hardware()), "detected once per run");
    }

    #[test]
    fn compact_headers_need_automatic_optimization_and_java_25() {
        let mac = HardwareInfo { memory_mb: Some(16384), logical_cpus: Some(8), performance_cores: Some(4), efficiency_cores: Some(4), arch: "aarch64".into(), os: "macos".into() };
        let java_25 = needs_java("java-runtime-epsilon", 25);
        let java_21 = needs_java("java-runtime-delta", 21);
        let java_8 = json!({ "javaVersion": { "component": "jre-legacy", "majorVersion": 8 }, "minecraftArguments": "--username ${auth_player_name}" });
        let no_java = json!({ "arguments": { "jvm": [], "game": [] } });
        // Hardware plays no part yet.
        for hardware in [mac, HardwareInfo::default()] {
            assert_eq!(choose_policy(true, &hardware, &java_25), JvmPolicy::CompactHeaders, "26.x");
            assert_eq!(choose_policy(true, &hardware, &needs_java("future-runtime", 26)), JvmPolicy::CompactHeaders, "a later Java");
            assert_eq!(choose_policy(true, &hardware, &java_21), JvmPolicy::Legacy, "1.21.11: Java 21 refuses the flag");
            assert_eq!(choose_policy(true, &hardware, &java_8), JvmPolicy::Legacy, "1.8.9");
            assert_eq!(choose_policy(true, &hardware, &no_java), JvmPolicy::Legacy, "no Java version in the metadata");
            assert_eq!(choose_policy(true, &hardware, &json!({ "javaVersion": { "majorVersion": "25" } })), JvmPolicy::Legacy, "not a number");
            for version in [&java_25, &java_21, &java_8, &no_java] {
                assert_eq!(choose_policy(false, &hardware, version), JvmPolicy::Legacy, "switched off: as Flint 0.4.0");
            }
        }
    }

    #[test]
    fn legacy_tuning_is_flint_0_4_0s_g1_set() {
        assert_eq!(tuning_args(JvmPolicy::Legacy), LEGACY);
        let unlock = LEGACY.iter().position(|a| *a == "-XX:+UnlockExperimentalVMOptions").unwrap();
        let experimental = LEGACY.iter().position(|a| a.starts_with("-XX:G1NewSizePercent")).unwrap();
        assert!(unlock < experimental, "experimental options must be unlocked first");
    }

    #[test]
    fn compact_headers_add_one_flag_to_the_g1_set() {
        let args = tuning_args(JvmPolicy::CompactHeaders);
        assert_eq!(args[..LEGACY.len()], LEGACY);
        assert_eq!(args[LEGACY.len()..], ["-XX:+UseCompactObjectHeaders"]);
    }

    #[test]
    fn the_heap_flag_is_flints_maximum() {
        assert_eq!(heap_args(4096), ["-Xmx4096M"], "as Flint 0.4.0");
        assert_eq!(heap_args(1024), ["-Xmx1024M"]);
    }

    fn fabric_26_3() -> Profile {
        Profile { id: "aaaaaaaaaaaa".into(), name: "Test".into(), game_version: "26.3".into(), loader: "fabric".into(), ..Default::default() }
    }

    #[test]
    fn the_runtime_config_records_the_launch() {
        let hardware = HardwareInfo { memory_mb: Some(16384), logical_cpus: Some(8), performance_cores: Some(4), efficiency_cores: Some(4), arch: "aarch64".into(), os: "macos".into() };
        let tuning = LaunchTuning { automatic: true, policy: JvmPolicy::Legacy, heap_max_mb: 4096 };
        let config = runtime_config(&fabric_26_3(), &tuning, &hardware, false);
        assert_eq!(
            config,
            json!({
                "schema": 1,
                "writtenBy": format!("Flint {}", env!("CARGO_PKG_VERSION")),
                "automaticOptimization": true,
                "profile": { "gameVersion": "26.3", "loader": "fabric" },
                "hardware": { "memoryMb": 16384, "logicalCpus": 8, "performanceCores": 4, "efficiencyCores": 4, "arch": "aarch64", "os": "macos" },
                "jvm": { "policy": "legacy", "heapMaxMb": 4096 },
                "modules": {},
                "dev": { "frameStatsLog": false },
            })
        );
        let compact = runtime_config(&fabric_26_3(), &LaunchTuning { automatic: true, policy: JvmPolicy::CompactHeaders, heap_max_mb: 4096 }, &hardware, false);
        assert_eq!(compact["jvm"], json!({ "policy": "compactHeaders", "heapMaxMb": 4096 }));
        let off = runtime_config(&fabric_26_3(), &LaunchTuning { automatic: false, policy: JvmPolicy::Legacy, heap_max_mb: 2048 }, &HardwareInfo::default(), true);
        assert_eq!(off["automaticOptimization"], false);
        assert_eq!(off["jvm"], json!({ "policy": "legacy", "heapMaxMb": 2048 }));
        assert_eq!(off["hardware"]["performanceCores"], Value::Null, "unknown values are written as null");
        assert_eq!(off["dev"]["frameStatsLog"], true);
    }

    #[test]
    fn the_runtime_config_is_written_and_removed() {
        let dir = TestDir::new();
        let path = dir.0.join("config").join(CONFIG_FILE);
        let config = json!({ "schema": 1, "automaticOptimization": false });
        sync_runtime_config(&dir.0, Some(&config)).unwrap();
        assert_eq!(read_json_opt::<Value>(&path), Some(config));
        assert!(!dir.0.join("config").join(format!("{CONFIG_FILE}.tmp")).exists(), "written atomically");
        std::fs::write(dir.0.join("config/flintclient.json"), "{}").unwrap();
        sync_runtime_config(&dir.0, None).unwrap();
        assert!(!path.exists());
        assert!(dir.0.join("config/flintclient.json").exists(), "the runtime's own config is left alone");
        sync_runtime_config(&dir.0, None).unwrap();

        // A `config` that can't hold the file doesn't stop the launch.
        let blocked = TestDir::new();
        std::fs::write(blocked.0.join("config"), b"not a folder").unwrap();
        assert!(sync_runtime_config(&blocked.0, Some(&json!({ "schema": 1 }))).is_ok());
    }

    #[test]
    fn mods_are_read_from_their_fabric_mod_json() {
        let dir = TestDir::new();
        test_jar(&dir.0, "sodium-a.jar", r#"{"schemaVersion":1,"id":"sodium","version":"0.9.2+mc26.3","provides":["indium"],"breaks":{"embeddium":"*","iris":"<=1.11.2"}}"#);
        test_jar(&dir.0, "lithium-a.jar", "\u{feff}{\"id\":\"lithium\",\"version\":\"0.26.2\"}");
        let mods = scan_mods(&dir.0);
        assert_eq!(mods.iter().map(|m| m.file.as_str()).collect::<Vec<_>>(), ["lithium-a.jar", "sodium-a.jar"], "sorted by file name");
        assert_eq!(mods[0].id, "lithium", "a byte-order mark is tolerated");
        let sodium = &mods[1];
        assert_eq!((sodium.id.as_str(), sodium.version.as_str()), ("sodium", "0.9.2+mc26.3"));
        assert_eq!(sodium.provides, ["indium"]);
        assert_eq!(sodium.breaks["embeddium"], "*");
        assert_eq!(sodium.breaks["iris"], "<=1.11.2");
        assert_eq!(mod_ids(&mods), ["sodium", "indium", "lithium"].map(str::to_owned).into_iter().collect::<HashSet<_>>());
    }

    #[test]
    fn only_jars_fabric_would_load_are_scanned() {
        let dir = TestDir::new();
        let json = |id: &str| format!(r#"{{"id":"{id}","version":"1"}}"#);
        test_jar(&dir.0, "on.jar", &json("on"));
        test_jar(&dir.0, "off.jar.disabled", &json("off"));
        test_jar(&dir.0, ".hidden.jar", &json("hidden"));
        test_jar(&dir.0, "Upper.JAR", &json("upper"));
        test_jar(&dir.0.join(".flint-replaced"), "old.jar", &json("old"));
        test_jar(&dir.0.join("sub"), "nested.jar", &json("nested"));
        test_jar(&dir.0, "server.jar", r#"{"id":"server","version":"1","environment":"server"}"#);
        test_jar(&dir.0, "client.jar", r#"{"id":"client","version":"1","environment":"client"}"#);
        // Broken or foreign jars are skipped, not errors.
        std::fs::write(dir.0.join("random-bytes.jar"), b"not a zip at all").unwrap();
        test_jar(&dir.0, "no-id.jar", r#"{"version":"1"}"#);
        test_jar(&dir.0, "bad-json.jar", r#"{"id": "#);
        test_jar(&dir.0, "huge.jar", &format!(r#"{{"id":"huge","pad":"{}"}}"#, "x".repeat(MAX_MOD_JSON as usize)));
        let mut quilt = zip::ZipWriter::new(std::fs::File::create(dir.0.join("quilt.jar")).unwrap());
        quilt.start_file("quilt.mod.json", zip::write::SimpleFileOptions::default()).unwrap();
        quilt.finish().unwrap();
        std::fs::create_dir_all(dir.0.join("folder.jar")).unwrap();
        let ids: Vec<String> = scan_mods(&dir.0).into_iter().map(|m| m.id).collect();
        assert_eq!(ids, ["client", "on"]);
        assert!(scan_mods(&dir.0.join("missing")).is_empty());
    }

    fn jar(file: &str, id: &str, provides: &[&str], breaks: Value) -> ModJar {
        ModJar {
            file: file.into(),
            id: id.into(),
            version: "1.0".into(),
            provides: provides.iter().map(|p| p.to_string()).collect(),
            breaks: breaks.as_object().map(|o| o.iter().map(|(k, v)| (k.clone(), v.clone())).collect()).unwrap_or_default(),
            minecraft: Value::Null,
        }
    }

    #[test]
    fn only_jars_built_for_the_profiles_version_count_as_present() {
        let built_for = |minecraft: Value| ModJar { minecraft, ..jar("sodium.jar", "sodium", &[], json!({})) };
        for fits in [json!("26.3"), json!("~26.3"), json!(">=26.3"), json!("26.3.x"), json!(["1.21.11", "~26.3"]), json!("26.3-beta.1")] {
            assert!(declares_minecraft(&built_for(fits.clone()), "26.3"), "{fits}");
        }
        // Another version, an open range or no statement: Flint installs its own copy as 0.4.0 did.
        for other in [json!("~26.1"), json!(">=26.1"), json!("*"), json!("26.30"), Value::Null, json!(5)] {
            assert!(!declares_minecraft(&built_for(other.clone()), "26.3"), "{other}");
        }
        let mods = [built_for(json!("~26.1")), ModJar { file: "lithium.jar".into(), ..built_for(json!("~26.3")) }.with_id("lithium")];
        assert_eq!(mod_ids_for(&mods, "26.3"), ["lithium".to_owned()].into());
    }

    impl ModJar {
        fn with_id(mut self, id: &str) -> Self {
            self.id = id.into();
            self
        }
    }

    #[test]
    fn module_switches_set_by_hand_survive_the_next_launch() {
        let dir = TestDir::new();
        let path = dir.0.join("config").join(CONFIG_FILE);
        write_json(&path, &json!({ "schema": 1, "modules": { "atlasBatching": false, "junk": "yes" } })).unwrap();
        sync_runtime_config(&dir.0, Some(&json!({ "schema": 1, "modules": {} }))).unwrap();
        let written: Value = read_json_opt(&path).unwrap();
        assert_eq!(written["modules"], json!({ "atlasBatching": false }), "booleans are kept, anything else is dropped");

        // Developer flags turned on by hand stay on; the launcher's own values are kept otherwise.
        write_json(&path, &json!({ "schema": 1, "modules": {}, "dev": { "fontTiming": true, "gpuResources": false } })).unwrap();
        sync_runtime_config(&dir.0, Some(&json!({ "schema": 1, "modules": {}, "dev": { "frameStatsLog": false } }))).unwrap();
        let written: Value = read_json_opt(&path).unwrap();
        assert_eq!(written["dev"], json!({ "frameStatsLog": false, "fontTiming": true }));
    }

    #[test]
    fn duplicate_mod_ids_are_reported_with_both_files() {
        let fine = [jar("sodium.jar", "sodium", &["indium"], json!({})), jar("lithium.jar", "lithium", &["lithium"], json!({})), jar("multi.jar", "multi", &["alias", "alias"], json!({}))];
        assert!(duplicate_mods(&fine).is_empty(), "providing your own id (or one id twice) is not a duplicate");

        let twice = [jar("sodium-0.9.1.jar", "sodium", &[], json!({})), jar("sodium-0.9.2.jar", "sodium", &[], json!({}))];
        let duplicates = duplicate_mods(&twice);
        assert_eq!(duplicates.len(), 1);
        assert!(duplicates[0].contains("sodium-0.9.1.jar") && duplicates[0].contains("sodium-0.9.2.jar") && duplicates[0].contains("(sodium)"), "{}", duplicates[0]);
        assert!(mod_conflicts(&twice).is_empty(), "Fabric loads one copy, so this doesn't stop a launch");

        let provided = [jar("indium.jar", "indium", &[], json!({})), jar("sodium.jar", "sodium", &["indium"], json!({}))];
        let duplicates = duplicate_mods(&provided);
        assert_eq!(duplicates.len(), 1);
        assert!(duplicates[0].contains("indium.jar") && duplicates[0].contains("sodium.jar") && duplicates[0].contains("(indium)"), "{}", duplicates[0]);

        // Two copies sharing an id and a provided id are one duplicate, not two.
        let copies = [jar("a.jar", "sodium", &["indium"], json!({})), jar("b.jar", "sodium", &["indium"], json!({}))];
        assert_eq!(duplicate_mods(&copies).len(), 1);
    }

    #[test]
    fn breaks_for_every_version_is_reported() {
        let breaking = jar("culling.jar", "entityculling", &[], json!({ "tlskincape": "*" }));
        assert!(mod_conflicts(std::slice::from_ref(&breaking)).is_empty(), "the other mod isn't there");
        let problems = mod_conflicts(&[breaking.clone(), jar("skins.jar", "tlskincape", &[], json!({}))]);
        assert_eq!(problems.len(), 1);
        assert!(problems[0].contains("culling.jar") && problems[0].contains("skins.jar"), "{}", problems[0]);
        // Also through `provides`, and with "*" inside a list.
        let listed = jar("lithium.jar", "lithium", &[], json!({ "optifabric": [">=1.0", "*"] }));
        assert_eq!(mod_conflicts(&[listed, jar("bridge.jar", "bridge", &["optifabric"], json!({}))]).len(), 1);
        // Version ranges are Fabric's to judge.
        let ranged = jar("ferritecore.jar", "ferritecore", &[], json!({ "hydrogen": "<=0.3" }));
        assert!(mod_conflicts(&[ranged, jar("hydrogen.jar", "hydrogen", &[], json!({}))]).is_empty());
        // A mod never conflicts with itself.
        assert!(mod_conflicts(&[jar("odd.jar", "odd", &["alias"], json!({ "alias": "*", "odd": "*" }))]).is_empty());
        // With a second copy of the breaking mod, Fabric can load that copy and start.
        let other_copy = jar("culling-old.jar", "entityculling", &[], json!({}));
        assert!(mod_conflicts(&[breaking.clone(), other_copy, jar("skins.jar", "tlskincape", &[], json!({}))]).is_empty());
        let provider = jar("bundle.jar", "bundle", &["entityculling"], json!({}));
        assert!(mod_conflicts(&[breaking, provider, jar("skins.jar", "tlskincape", &[], json!({}))]).is_empty(), "another jar providing its id can stand in too");
    }

    #[test]
    fn the_real_performance_mods_declare_the_expected_ids() {
        // fabric.mod.json contents of the jars Flint's performance set installs for 26.3 (trimmed to
        // the fields Flint reads), checked against the real files.
        let dir = TestDir::new();
        test_jar(&dir.0, "sodium-fabric-0.9.2+mc26.3.jar", r#"{"id":"sodium","version":"0.9.2+mc26.3","provides":["indium"],"breaks":{"embeddium":"*","optifabric":"*","iris":"<=1.11.2"}}"#);
        test_jar(&dir.0, "lithium-fabric-0.26.2+mc26.3.jar", r#"{"id":"lithium","version":"0.26.2+mc26.3","breaks":{"optifabric":"*"}}"#);
        test_jar(&dir.0, "ferritecore-9.0.0-fabric.jar", r#"{"id":"ferritecore","version":"9.0.0","breaks":{"hydrogen":"<=0.3"}}"#);
        test_jar(&dir.0, "ImmediatelyFast-Fabric-1.17.1+26.3.jar", r#"{"id":"immediatelyfast","version":"1.17.1+26.3"}"#);
        test_jar(&dir.0, "entityculling-fabric-1.11.3-mc26.3.jar", r#"{"id":"entityculling","version":"1.11.3","breaks":{"tlskincape":"*"}}"#);
        test_jar(&dir.0, "dynamic-fps-3.11.10+minecraft-26.3.0-fabric.jar", r#"{"id":"dynamic_fps","version":"3.11.10","conflicts":{"optifabric":"*"}}"#);
        test_jar(&dir.0, "fabric-api-0.163.0+26.3.jar", r#"{"id":"fabric-api","version":"0.163.0+26.3"}"#);
        let mods = scan_mods(&dir.0);
        assert_eq!(mods.len(), 7);
        assert!(mod_conflicts(&mods).is_empty(), "the set works together");
        let ids = mod_ids(&mods);
        for id in ["sodium", "lithium", "ferritecore", "immediatelyfast", "entityculling", "dynamic_fps", "fabric-api"] {
            assert!(ids.contains(id), "{id}");
        }
    }
}
