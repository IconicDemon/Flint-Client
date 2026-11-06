//! Settings → Java and memory, read-only: the Java each Minecraft version runs on with Flint (the same rules a launch
//! uses: `runtime::java_override` for Flint's own choice, else the version's own file), the Java runtimes Flint keeps
//! under `runtimes/`, how a custom Java fits, and a review of extra Java arguments before they are saved.
//!
//! What the review refuses or warns about was checked against real Javas (Temurin 25.0.4 and Oracle 8u503 on
//! Windows, `java <flags> -version`): two garbage collectors, or G1 turned off with none chosen, stop Java from
//! starting; the CMS and permanent-generation options below no longer exist in Java 25, which refuses to start with
//! them; Java 8 has no ZGC, Shenandoah, compact object headers or `--enable-native-access`.

use crate::core::*;
use crate::data::{load_settings, Profile, SUPPORTED_VERSIONS};
use crate::perfcore::{self, JvmPolicy};
use serde::Serialize;
use serde_json::Value;
use std::path::Path;
use tauri::AppHandle;

/// The Java a version runs on in one situation.
#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct JavaUse {
    /// Mojang's runtime component ("java-runtime-epsilon").
    pub component: String,
    /// The Java it is.
    pub major: u64,
    /// The oldest Java a custom Java in Settings may be.
    pub minimum: u64,
    /// The Java used instead where Mojang has no build of `major` for this computer.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub fallback: Option<u64>,
}

#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct VersionJava {
    pub version: String,
    /// A Fabric profile with Flint's in-game menu.
    pub with_menu: JavaUse,
    /// Every other profile of the version (vanilla, or the menu turned off).
    pub without_menu: JavaUse,
    /// Something a launch of it can't do on this computer.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub note: Option<String>,
}

/// A Java runtime Flint downloaded from Mojang.
#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Runtime {
    pub component: String,
    /// Its full version ("25.0.1"), from the runtime's `release` file.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub version: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub major: Option<u32>,
    pub size: u64,
}

#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct CustomJava {
    pub path: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub version: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub major: Option<u32>,
    /// Why it can't be used at all (it moved, it doesn't run).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub problem: Option<String>,
}

#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct JavaOverview {
    pub versions: Vec<VersionJava>,
    pub runtimes: Vec<Runtime>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub custom: Option<CustomJava>,
    /// Settings → "Flint main menu in Minecraft": off, every profile runs on its version's own Java.
    pub in_game_menu: bool,
    /// Automatic Optimization (perfcore): whether Flint picks Java flags per version.
    pub automatic: bool,
}

/// The Java a version's own file asks for: the cached version file when there is one, else what Mojang's files for
/// each line say (1.8.9 Java 8, 1.21.x Java 21, 26.x and anything newer Java 25).
fn version_java(game_root: &Path, version: &str) -> (String, u64) {
    let file = game_root.join("versions").join(version).join(format!("{version}.json"));
    let cached: Option<Value> = read_json_opt(&file);
    if let Some(java) = cached.as_ref().map(|v| &v["javaVersion"]) {
        if let (Some(component), Some(major)) = (java["component"].as_str(), java["majorVersion"].as_u64()) {
            return (component.to_owned(), major);
        }
    }
    let (component, major) = if version.starts_with("1.8") {
        ("jre-legacy", 8)
    } else if version.starts_with("1.21") {
        ("java-runtime-delta", 21)
    } else {
        ("java-runtime-epsilon", 25)
    };
    (component.to_owned(), major)
}

/// What Java every version Flint offers runs on, both ways.
pub fn version_plans(game_root: &Path) -> Vec<VersionJava> {
    SUPPORTED_VERSIONS
        .iter()
        .map(|version| {
            let (component, major) = version_java(game_root, version);
            let without_menu = JavaUse { component, major, minimum: major, fallback: None };
            let fabric = Profile { game_version: (*version).to_owned(), loader: "fabric".into(), ..Default::default() };
            let with_menu = match crate::runtime::java_override(true, &fabric) {
                Some(choice) => JavaUse { component: choice.component.to_owned(), major: choice.major, minimum: choice.minimum, fallback: fallback_major(choice.fallback) },
                None => without_menu.clone(),
            };
            let legacy_on_arm = cfg!(all(target_os = "macos", target_arch = "aarch64")) && without_menu.component == "jre-legacy";
            let note = legacy_on_arm.then(|| format!("Minecraft {version} without Flint's in-game menu isn't supported on Apple silicon Macs yet."));
            VersionJava { version: (*version).to_owned(), with_menu, without_menu, note }
        })
        .collect()
}

fn fallback_major(component: &str) -> Option<u64> {
    match component {
        "java-runtime-delta" => Some(21),
        "java-runtime-epsilon" => Some(25),
        "java-runtime-gamma" => Some(17),
        "jre-legacy" => Some(8),
        _ => None,
    }
}

fn dir_size(path: &Path) -> u64 {
    let Ok(meta) = std::fs::symlink_metadata(path) else { return 0 };
    if !meta.is_dir() {
        return meta.len();
    }
    std::fs::read_dir(path).map(|entries| entries.flatten().map(|e| dir_size(&e.path())).sum()).unwrap_or(0)
}

/// The runtimes under `runtimes/<component>/<platform>/`, with the version their `release` file states.
pub fn runtimes_on_disk(root: &Path) -> Vec<Runtime> {
    let Ok(components) = std::fs::read_dir(root) else { return Vec::new() };
    let mut out: Vec<Runtime> = components
        .flatten()
        .filter(|e| e.file_type().is_ok_and(|t| t.is_dir()))
        .filter_map(|component| {
            let dir = component.path();
            let platforms: Vec<_> = std::fs::read_dir(&dir).ok()?.flatten().filter(|e| e.file_type().is_ok_and(|t| t.is_dir())).map(|e| e.path()).collect();
            let release = platforms.iter().find_map(|p| {
                ["release", "jre.bundle/Contents/Home/release"].iter().find_map(|rel| std::fs::read_to_string(p.join(rel)).ok())
            });
            let version = release.as_deref().and_then(|text| text.lines().find(|l| l.starts_with("JAVA_VERSION=")).map(crate::data::parse_java_version));
            if platforms.is_empty() {
                return None;
            }
            Some(Runtime {
                component: component.file_name().to_string_lossy().into_owned(),
                version: version.as_ref().map(|(v, _)| v.clone()).filter(|v| !v.is_empty()),
                major: version.and_then(|(_, major)| major),
                size: dir_size(&dir),
            })
        })
        .collect();
    out.sort_by_key(|r| std::cmp::Reverse(r.major));
    out
}

#[tauri::command]
pub async fn java_overview(app: AppHandle) -> CmdResult<JavaOverview> {
    let (versions, runtimes, settings) = crate::data::blocking(&app, |state| (version_plans(&state.paths.game()), runtimes_on_disk(&state.paths.runtimes()), load_settings(state))).await?;
    let custom = match settings["javaPath"].as_str().map(str::trim).filter(|p| !p.is_empty()) {
        None => None,
        Some(path) => Some(match crate::data::inspect_java(path).await {
            Ok(info) => CustomJava { path: path.to_owned(), version: Some(info.version).filter(|v| !v.is_empty()), major: info.major, problem: None },
            Err(problem) => CustomJava { path: path.to_owned(), version: None, major: None, problem: Some(problem) },
        }),
    };
    Ok(JavaOverview {
        versions,
        runtimes,
        custom,
        in_game_menu: settings["inGameMenu"].as_bool().unwrap_or(true),
        automatic: settings["automaticOptimization"].as_bool().unwrap_or(true),
    })
}

// ---------- extra Java arguments ----------

/// Options Java 25 no longer has: it refuses to start with them.
const GONE_IN_JAVA_25: &[&str] = &[
    "UseConcMarkSweepGC",
    "UseParNewGC",
    "CMSIncrementalMode",
    "UseCMSInitiatingOccupancyOnly",
    "CMSInitiatingOccupancyFraction",
    "CMSClassUnloadingEnabled",
    "AggressiveOpts",
    "UseFastAccessorMethods",
    "PermSize",
    "MaxPermSize",
    "UseLargePagesInMetaspace",
    "UseAdaptiveGCBoundary",
    "UseBiasedLocking",
];

/// Options an older Java doesn't have and refuses to start with, and the first Java that has each.
const NEEDS_JAVA: &[(&str, u64)] = &[
    ("UseZGC", 15),
    ("UseShenandoahGC", 12),
    ("UseCompactObjectHeaders", 25),
    ("--enable-native-access", 17),
    ("--sun-misc-unsafe-memory-access", 23),
];

/// An option's name: `UseZGC` for `-XX:+UseZGC`, `MaxGCPauseMillis` for `-XX:MaxGCPauseMillis=50`,
/// `-Dfoo` for `-Dfoo=bar`, the option itself otherwise ("--enable-native-access", "-Xincgc").
fn option_name(arg: &str) -> &str {
    if let Some(rest) = arg.strip_prefix("-XX:") {
        let rest = rest.strip_prefix(['+', '-']).unwrap_or(rest);
        return rest.split('=').next().unwrap_or(rest);
    }
    arg.split('=').next().unwrap_or(arg)
}

#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ArgsReview {
    /// The arguments as a launch uses them.
    pub args: Vec<String>,
    /// What they change or where they won't work, one sentence each.
    pub notes: Vec<String>,
}

/// "1.21.11, 1.21.10 and 1.21.1".
fn and_list(items: &[String]) -> String {
    match items {
        [] => String::new(),
        [one] => one.clone(),
        [rest @ .., last] => format!("{} and {last}", rest.join(", ")),
    }
}

/// Where each Java older than the newest runs Minecraft with Flint: "Java 21 (1.21.11, 1.21.10 and 1.21.1 without
/// Flint's in-game menu, and wherever Mojang has no Java 25)".
fn older_javas(plans: &[VersionJava], below: u64) -> Vec<String> {
    let mut javas: Vec<u64> = plans.iter().flat_map(|p| [Some(p.without_menu.major), p.with_menu.fallback, Some(p.with_menu.major)]).flatten().filter(|m| *m < below).collect();
    javas.sort_unstable_by(|a, b| b.cmp(a));
    javas.dedup();
    javas
        .into_iter()
        .map(|java| {
            let versions = |keep: &dyn Fn(&VersionJava) -> bool| -> Vec<String> { plans.iter().filter(|p| keep(p)).map(|p| p.version.clone()).collect() };
            let always = versions(&|p| p.with_menu.major == java && p.without_menu.major == java);
            let with = versions(&|p| p.with_menu.major == java && p.without_menu.major != java);
            let without = versions(&|p| p.without_menu.major == java && p.with_menu.major != java);
            let mut places = Vec::new();
            if !always.is_empty() {
                places.push(and_list(&always));
            }
            if !with.is_empty() {
                places.push(format!("{} with Flint's in-game menu", and_list(&with)));
            }
            if !without.is_empty() {
                places.push(format!("{} without Flint's in-game menu", and_list(&without)));
            }
            if plans.iter().any(|p| p.with_menu.fallback == Some(java) && p.with_menu.major != java) {
                places.push(format!("wherever Mojang has no Java {below} for the computer"));
            }
            format!("Java {java} ({})", places.join(", and "))
        })
        .collect()
}

/// Checks extra Java arguments before Settings saves them: what a launch refuses (`game::parse_extra_jvm_args`),
/// plus what would stop Java from starting, and notes on what they change. `custom_java` is the custom Java's major
/// version when one is set (every launch then runs on it).
pub fn review_args(text: &str, plans: &[VersionJava], custom_java: Option<u32>) -> Result<ArgsReview, String> {
    let args = crate::game::parse_extra_jvm_args(text)?;
    let collectors: Vec<&str> = args.iter().map(String::as_str).filter(|a| *a == "-XX:+UseG1GC" || perfcore::OTHER_COLLECTORS.iter().any(|(flag, _)| flag == a)).collect();
    if collectors.len() > 1 && collectors.iter().any(|c| *c != collectors[0]) {
        return Err(format!("{} and {} each choose a garbage collector, and Java won't start with two. Keep one.", collectors[0], collectors.iter().find(|c| **c != collectors[0]).unwrap()));
    }
    for arg in &args {
        let name = option_name(arg);
        if name == "UseEpsilonGC" {
            return Err(format!("{arg} never frees memory, so Minecraft would run out of it within minutes."));
        }
        if arg == "-XX:-UseG1GC" && collectors.is_empty() {
            return Err(format!("{arg} turns Java's garbage collector off without choosing another, and Java won't start that way."));
        }
        if GONE_IN_JAVA_25.contains(&name) || arg == "-Xincgc" {
            return Err(format!("{arg} no longer exists in Java 25, which Minecraft 26.x runs on, so those versions wouldn't start. Remove it."));
        }
    }
    let mut notes = Vec::new();
    if let Some(collector) = perfcore::chosen_collector(&args) {
        notes.push(format!("Minecraft runs with {collector} instead of the collector the official launcher uses (ZGC on 26.x, G1 on 1.21 and 1.8.9), which is left out while this is here."));
    } else {
        // Flint's own flags the player's replace (theirs come later, so they win).
        let flint = perfcore::tuning_args(JvmPolicy::CompactHeaders);
        for arg in &args {
            if let Some(own) = flint.iter().find(|f| option_name(f) == option_name(arg) && *f != arg) {
                notes.push(format!("{arg} replaces the default {own}."));
            }
        }
    }
    for arg in &args {
        let Some((_, need)) = NEEDS_JAVA.iter().find(|(name, _)| option_name(arg) == *name) else { continue };
        match custom_java {
            Some(major) if u64::from(major) < *need => notes.push(format!("{arg} needs Java {need} or newer, and your custom Java is Java {major}, so Minecraft won't start with it.")),
            Some(_) => {}
            None => {
                let older = older_javas(plans, *need);
                if !older.is_empty() {
                    notes.push(format!("{arg} needs Java {need} or newer. Minecraft won't start with it on {}.", and_list(&older)));
                }
            }
        }
    }
    Ok(ArgsReview { args, notes })
}

#[tauri::command]
pub async fn java_args_review(app: AppHandle, text: String) -> CmdResult<ArgsReview> {
    let (plans, settings) = crate::data::blocking(&app, |state| (version_plans(&state.paths.game()), load_settings(state))).await?;
    let custom = match settings["javaPath"].as_str().map(str::trim).filter(|p| !p.is_empty()) {
        Some(path) => crate::data::inspect_java(path).await.ok().and_then(|info| info.major),
        None => None,
    };
    review_args(&text, &plans, custom)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn plans() -> Vec<VersionJava> {
        version_plans(Path::new("/nonexistent"))
    }

    #[test]
    fn every_version_s_java_follows_the_launch_rules() {
        let plans = plans();
        assert_eq!(plans.iter().map(|p| p.version.as_str()).collect::<Vec<_>>(), SUPPORTED_VERSIONS);
        let of = |v: &str| plans.iter().find(|p| p.version == v).unwrap().clone();
        for v in ["26.3", "26.2", "26.1.2", "26.1.1", "26.1"] {
            let p = of(v);
            assert_eq!((p.with_menu.major, p.with_menu.minimum, p.without_menu.major), (25, 25, 25), "{v}");
            assert_eq!(p.with_menu.fallback, None);
        }
        for v in ["1.21.11", "1.21.10", "1.21.1"] {
            let p = of(v);
            assert_eq!((p.with_menu.major, p.with_menu.minimum, p.with_menu.fallback), (21, 21, None), "{v}: its own Java 21 with Flint's menu, as the official launcher");
            assert_eq!((p.without_menu.major, p.without_menu.component.as_str()), (21, "java-runtime-delta"));
        }
        let legacy = of("1.8.9");
        assert_eq!((legacy.with_menu.major, legacy.with_menu.minimum), (21, 21), "1.8.9 with Flint's menu: Mojang's Java 21");
        assert_eq!((legacy.without_menu.major, legacy.without_menu.component.as_str()), (8, "jre-legacy"));
    }

    #[test]
    fn a_cached_version_file_is_the_truth() {
        let dir = TestDir::new();
        let file = dir.0.join("versions/1.21.11/1.21.11.json");
        std::fs::create_dir_all(file.parent().unwrap()).unwrap();
        std::fs::write(&file, br#"{"id":"1.21.11","javaVersion":{"component":"java-runtime-delta","majorVersion":21}}"#).unwrap();
        assert_eq!(version_java(&dir.0, "1.21.11"), ("java-runtime-delta".to_owned(), 21));
        assert_eq!(version_java(&dir.0, "26.4"), ("java-runtime-epsilon".to_owned(), 25), "a newer version: 26.x's Java");
    }

    #[test]
    fn runtimes_are_listed_with_their_release_version_and_size() {
        let dir = TestDir::new();
        let home = dir.0.join("java-runtime-epsilon/windows-x64");
        std::fs::create_dir_all(home.join("bin")).unwrap();
        std::fs::write(home.join("release"), "IMPLEMENTOR=\"Microsoft\"\nJAVA_VERSION=\"25.0.1\"\n").unwrap();
        std::fs::write(home.join("bin/javaw.exe"), vec![0; 1000]).unwrap();
        std::fs::write(dir.0.join("java-runtime-epsilon/windows-x64.manifest.json"), b"{}").unwrap();
        let legacy = dir.0.join("jre-legacy/mac-os/jre.bundle/Contents/Home");
        std::fs::create_dir_all(&legacy).unwrap();
        std::fs::write(legacy.join("release"), "JAVA_VERSION=\"1.8.0_51\"\n").unwrap();
        std::fs::create_dir_all(dir.0.join("empty-component")).unwrap();
        let found = runtimes_on_disk(&dir.0);
        assert_eq!(found.len(), 2, "a component folder without a runtime isn't listed: {found:?}");
        assert_eq!((found[0].component.as_str(), found[0].version.as_deref(), found[0].major), ("java-runtime-epsilon", Some("25.0.1"), Some(25)));
        assert!(found[0].size >= 1000);
        assert_eq!((found[1].version.as_deref(), found[1].major), (Some("1.8.0_51"), Some(8)));
        assert!(runtimes_on_disk(&dir.0.join("missing")).is_empty());
    }

    #[test]
    fn arguments_that_stop_java_from_starting_are_refused() {
        let plans = plans();
        for (text, why) in [
            ("-XX:+UseG1GC -XX:+UseZGC", "two"),
            ("-XX:+UseParallelGC -XX:+UseShenandoahGC", "two"),
            ("-XX:-UseG1GC", "without choosing another"),
            ("-XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC", "never frees memory"),
            ("-XX:+UseConcMarkSweepGC", "Java 25"),
            ("-XX:MaxPermSize=256M", "Java 25"),
            ("-XX:+AggressiveOpts", "Java 25"),
            ("-Xincgc", "Java 25"),
            ("-Xmx8G", "Memory"),
        ] {
            let error = review_args(text, &plans, None).unwrap_err();
            assert!(error.contains(why), "{text}: {error}");
        }
        assert!(review_args("-XX:+UseG1GC -XX:+UseG1GC", &plans, None).is_ok(), "the same collector twice is fine");
        assert!(review_args("-XX:-UseG1GC -XX:+UseZGC", &plans, None).is_ok());
    }

    #[test]
    fn notes_say_what_arguments_change_and_where_they_won_t_start() {
        let plans = plans();
        let review = review_args("-XX:+UseZGC -Dfile.encoding=UTF-8", &plans, None).unwrap();
        assert_eq!(review.args, ["-XX:+UseZGC", "-Dfile.encoding=UTF-8"]);
        assert!(review.notes[0].contains("ZGC instead of the collector the official launcher uses"), "{:?}", review.notes);
        assert!(review.notes[1].contains("needs Java 15") && review.notes[1].contains("Java 8 (1.8.9 without Flint's in-game menu)"), "{:?}", review.notes);
        assert!(!review.notes[1].contains("Java 21"), "Java 21 has ZGC: {:?}", review.notes);
        let review = review_args("-XX:MaxGCPauseMillis=200 -XX:+UseG1GC -XX:+AlwaysPreTouch", &plans, None).unwrap();
        assert_eq!(review.notes, ["-XX:MaxGCPauseMillis=200 replaces the default -XX:MaxGCPauseMillis=50."]);
        let review = review_args("-XX:+UseCompactObjectHeaders", &plans, None).unwrap();
        let note = &review.notes[0];
        assert!(note.contains("Java 21 (1.21.11, 1.21.10 and 1.21.1, and 1.8.9 with Flint's in-game menu)") && note.contains("Java 8 (1.8.9 without Flint's in-game menu)"), "{note}");
        // A custom Java runs every launch: only its version counts.
        assert!(review_args("-XX:+UseZGC", &plans, Some(21)).unwrap().notes.iter().all(|n| !n.contains("needs Java")));
        assert!(review_args("-XX:+UseCompactObjectHeaders", &plans, Some(21)).unwrap().notes[0].contains("your custom Java is Java 21"));
        assert!(review_args("", &plans, None).unwrap().notes.is_empty());
    }

    #[test]
    fn a_collector_the_player_chooses_replaces_flint_s_g1_settings_at_launch() {
        let zgc = ["-XX:+UseZGC".to_owned()];
        assert_eq!(perfcore::tuning_args_with(JvmPolicy::CompactHeaders, &zgc), ["-XX:+UseCompactObjectHeaders"]);
        assert!(perfcore::tuning_args_with(JvmPolicy::Legacy, &zgc).is_empty());
        let g1 = ["-XX:+UseG1GC".to_owned(), "-XX:MaxGCPauseMillis=200".to_owned()];
        assert_eq!(perfcore::tuning_args_with(JvmPolicy::Legacy, &g1), perfcore::tuning_args(JvmPolicy::Legacy), "G1 tuning keeps Flint's set (theirs comes later)");
        assert_eq!(perfcore::chosen_collector(&zgc), Some("ZGC"));
    }
}
