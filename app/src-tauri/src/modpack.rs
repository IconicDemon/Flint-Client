use crate::content::MODRINTH_CDN;
use crate::core::*;
use crate::data::{load_profiles, save_profiles, Profile};
use crate::jobs::{self, download_verified, Expected, Fetch};
use crate::modrinth::*;
use serde_json::{json, Value};
use futures_util::TryStreamExt;
use std::io::Read;
use std::path::{Component, Path, PathBuf};
use tauri::{AppHandle, Manager, State};

/// Hosts the Modrinth modpack format allows pack files to be downloaded from.
const PACK_HOSTS: &[&str] = &["cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com"];

/// A modpack file checked and ready to download: where it goes, from where, its published hash (SHA-512, else SHA-1) and size cap.
struct PlannedFile<'a> {
    target: PathBuf,
    url: &'a str,
    hash: &'a str,
    sha512: bool,
    cap: u64,
}

/// One bundled override file, and all of them together (a pack can carry large resource packs and worlds).
const MAX_FILE: u64 = 4 * 1024 * 1024 * 1024;
const MAX_OVERRIDES: u64 = 32 * 1024 * 1024 * 1024;

fn relative_safe(path: &str) -> Option<PathBuf> {
    let p = Path::new(path);
    if path.is_empty() || path.len() > 400 || p.is_absolute() || path.contains('\\') || path.contains(':') {
        return None;
    }
    let mut out = PathBuf::new();
    for c in p.components() {
        match c {
            Component::Normal(part) => out.push(part),
            _ => return None,
        }
    }
    (!out.as_os_str().is_empty()).then_some(out)
}

/// Files Flint keeps in a profile for itself: its content records (which name the files that roll back,
/// update and removal act on), backups, staging and kept-aside folders. A modpack never ships these, and
/// one that tries is ignored for them, so a pack can't plant records that point Flint at other files.
fn flint_owned(rel: &Path) -> bool {
    let top_level_record = rel.components().count() == 1 && matches!(rel.to_str(), Some("flint-content.json" | "flint-mods.json"));
    top_level_record || rel.components().any(|c| c.as_os_str().to_str().is_none_or(|part| part.starts_with(".flint")))
}

/// Writes the pack's `overrides/` and `client-overrides/` into `staging`. `limit` caps the bytes actually
/// written (a file's declared size can lie), and no single file may exceed `MAX_FILE`.
fn extract_overrides<R: Read + std::io::Seek>(archive: &mut zip::ZipArchive<R>, staging: &Path, limit: u64) -> CmdResult<()> {
    let too_large = || "The modpack's bundled files are unexpectedly large.".to_string();
    let mut extracted: u64 = 0;
    for i in 0..archive.len() {
        let mut entry = archive.by_index(i).map_err(|_| "The modpack file is damaged.".to_string())?;
        let name = entry.name().to_owned();
        let rel = name.strip_prefix("overrides/").or_else(|| name.strip_prefix("client-overrides/"));
        let Some(rel) = rel.and_then(relative_safe).filter(|r| !flint_owned(r)) else { continue };
        let target = staging.join(&rel);
        if entry.is_dir() {
            std::fs::create_dir_all(&target).map_err(err("Could not apply pack settings"))?;
            continue;
        }
        if extracted.saturating_add(entry.size()) > limit {
            return Err(too_large());
        }
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent).map_err(err("Could not apply pack settings"))?;
        }
        let mut out = std::fs::File::create(&target).map_err(err("Could not apply pack settings"))?;
        let room = MAX_FILE.min(limit - extracted);
        let copied = std::io::copy(&mut entry.by_ref().take(room + 1), &mut out).map_err(err("Could not apply pack settings"))?;
        extracted += copied;
        if copied > room {
            return Err(too_large());
        }
    }
    Ok(())
}

fn pick_pack_version(list: &[Value]) -> Option<Value> {
    let packs: Vec<Value> = list
        .iter()
        .filter(|v| primary_file(v).and_then(|f| f["filename"].as_str()).is_some_and(|n| n.ends_with(".mrpack")))
        .cloned()
        .collect();
    pick_version(&packs)
}

async fn run(app: &AppHandle, state: &AppState, job: &str, project_id: &str, version_id: Option<String>, name: Option<String>) -> CmdResult<Value> {
    state.jobs.update(app, job, false, |j| {
        j.state = "running".into();
        j.stage = "Reading the modpack".into();
    });
    let project = state.modrinth.get_fresh(&state.http, &format!("/project/{project_id}"), PROJECT_TTL, "Couldn't load the modpack").await?;
    if project["project_type"] != "modpack" {
        return Err("That project isn't a modpack.".into());
    }
    let version = match version_id.filter(|v| valid_id(v)) {
        Some(id) => state.modrinth.get_fresh(&state.http, &format!("/version/{id}"), VERSIONS_TTL, "Couldn't load the modpack version").await?,
        None => {
            let list = state
                .modrinth
                .get_fresh(&state.http, &format!("/project/{project_id}/version{}", version_query(&["fabric"], None)), VERSIONS_TTL, "Couldn't load modpack versions")
                .await?;
            pick_pack_version(list.as_array().map(Vec::as_slice).unwrap_or_default()).ok_or("This modpack has no Fabric release Flint can install.")?
        }
    };
    if !version["loaders"].as_array().is_some_and(|l| l.iter().any(|x| x == "fabric")) {
        return Err("This modpack needs a loader other than Fabric, which Flint doesn't support yet.".into());
    }
    let file = primary_file(&version).ok_or("This modpack release has no file")?;
    let pack_name = file["filename"].as_str().unwrap_or_default();
    if !pack_name.ends_with(".mrpack") {
        return Err("This release isn't a Modrinth modpack file.".into());
    }
    let sha512 = file["hashes"]["sha512"].as_str().ok_or("Modrinth published no hash for this modpack")?.to_owned();
    let url = file["url"].as_str().ok_or("No download address")?.to_owned();

    let id = random_hex(6);
    let staging = state.paths.root.join("profiles").join(format!("{id}.flint-importing"));
    std::fs::create_dir_all(&staging).map_err(err("Could not prepare the new profile"))?;
    let result = async {
        let pack_path = staging.join(".pack.mrpack");
        download_verified(app, state, job, Fetch { url: &url, expected: Expected::Sha512(&sha512), max_bytes: jobs::listed_cap(file["size"].as_u64()), allowed_hosts: MODRINTH_CDN, quiet: false }, &pack_path).await?;
        let pack = std::fs::File::open(&pack_path).map_err(err("Could not read the modpack"))?;
        let mut archive = zip::ZipArchive::new(std::io::BufReader::new(pack)).map_err(|_| "The modpack file is damaged.".to_string())?;
        let index: Value = {
            let entry = archive.by_name("modrinth.index.json").map_err(|_| "The modpack has no index.".to_string())?;
            let mut text = String::new();
            entry.take(8 * 1024 * 1024).read_to_string(&mut text).map_err(|_| "The modpack index is unreadable.".to_string())?;
            serde_json::from_str(&text).map_err(|_| "The modpack index is invalid.".to_string())?
        };
        if index["game"] != "minecraft" {
            return Err("This pack isn't for Minecraft: Java Edition.".into());
        }
        let deps = &index["dependencies"];
        let game_version = deps["minecraft"].as_str().filter(|v| valid_game_version(v)).ok_or("The modpack doesn't say which Minecraft version it needs.")?.to_owned();
        for unsupported in ["forge", "neoforge", "quilt-loader"] {
            if deps.get(unsupported).is_some() {
                return Err(format!("This modpack needs {unsupported}, which Flint doesn't support yet."));
            }
        }
        let loader_version = deps["fabric-loader"].as_str().ok_or("This modpack doesn't use Fabric.")?.to_owned();
        if !loader_version.chars().all(|c| c.is_ascii_alphanumeric() || ".+-".contains(c)) {
            return Err("Unexpected Fabric loader version in the modpack.".into());
        }

        let files: Vec<Value> = index["files"].as_array().cloned().unwrap_or_default();
        let wanted: Vec<&Value> = files.iter().filter(|f| f["env"]["client"].as_str() != Some("unsupported")).collect();
        // Every file is checked first (path, host, hash), then they download a few at a time (Settings → Storage, at most
        // `storage::MODRINTH_MAX`), each verified.
        let parallel = crate::storage::modrinth_download_limit(&crate::data::load_settings(state));
        let mut planned: Vec<PlannedFile> = Vec::new();
        let mut installed_mods = Vec::new();
        for f in &wanted {
            let rel = f["path"].as_str().and_then(relative_safe).ok_or("The modpack lists an unsafe file path.")?;
            if flint_owned(&rel) {
                eprintln!("[flint] the modpack lists {}, a file Flint keeps for itself; left out", rel.display());
                continue;
            }
            let downloads: Vec<&str> = f["downloads"].as_array().map(|d| d.iter().filter_map(Value::as_str).collect()).unwrap_or_default();
            let url = *downloads.iter().find(|u| jobs::host_allowed(u, PACK_HOSTS).is_ok()).ok_or("The modpack points to a download host Flint doesn't trust.")?;
            let hash = match (f["hashes"]["sha512"].as_str(), f["hashes"]["sha1"].as_str()) {
                (Some(h), _) => (h, true),
                (None, Some(h)) => (h, false),
                _ => return Err("A modpack file has no published hash.".into()),
            };
            if rel.starts_with("mods") {
                installed_mods.push(rel.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default());
            }
            planned.push(PlannedFile { target: staging.join(&rel), url, hash: hash.0, sha512: hash.1, cap: jobs::listed_cap(f["fileSize"].as_u64()) });
        }
        let total = planned.len();
        state.jobs.update(app, job, false, |j| {
            j.state = "running".into();
            j.stage = format!("Downloading pack files (0 of {total})");
            j.file = None;
            j.items_done = 0;
            j.items_total = total;
        });
        let finished = std::sync::atomic::AtomicUsize::new(0);
        futures_util::stream::iter(planned.iter().map(Ok::<&PlannedFile, String>))
            .try_for_each_concurrent(parallel, |p| {
                let finished = &finished;
                async move {
                    let expected = if p.sha512 { Expected::Sha512(p.hash) } else { Expected::Sha1(p.hash) };
                    download_verified(app, state, job, Fetch { url: p.url, expected, max_bytes: p.cap, allowed_hosts: PACK_HOSTS, quiet: true }, &p.target).await?;
                    let n = finished.fetch_add(1, std::sync::atomic::Ordering::Relaxed) + 1;
                    state.jobs.update(app, job, true, |j| {
                        j.stage = format!("Downloading pack files ({n} of {total})");
                        j.items_done = n;
                    });
                    Ok(())
                }
            })
            .await?;

        state.jobs.update(app, job, false, |j| {
            j.stage = "Applying pack settings".into();
            j.items_done = total;
        });
        extract_overrides(&mut archive, &staging, MAX_OVERRIDES)?;
        drop(archive);
        let _ = std::fs::remove_file(&pack_path);
        std::fs::create_dir_all(staging.join("mods")).map_err(err("Could not prepare the new profile"))?;
        Ok::<(String, String, usize), String>((game_version, loader_version, installed_mods.len()))
    }
    .await;

    let (game_version, loader_version, mod_count) = match result {
        Ok(v) => v,
        Err(e) => {
            let _ = std::fs::remove_dir_all(&staging);
            return Err(e);
        }
    };
    let title = project["title"].as_str().unwrap_or("Modpack").to_owned();
    let profile_name: String = name.map(|n| n.trim().to_owned()).filter(|n| !n.is_empty()).unwrap_or_else(|| title.clone()).chars().take(40).collect();
    let mut profile = Profile {
        id: id.clone(),
        name: profile_name,
        game_version,
        loader: "fabric".into(),
        loader_version: Some(loader_version),
        created_at: now_secs(),
        last_played: None,
        installed: false,
        performance: None,
        memory_mb: None,
        modpack: Some(json!({
            "projectId": project["id"],
            "versionId": version["id"],
            "title": title,
            "versionNumber": version["version_number"],
            "iconUrl": project["icon_url"],
        })),
        ..Profile::default()
    };
    let _guard = state.file_lock.lock().await;
    let mut file = load_profiles(state);
    crate::profilefolder::assign(state, &file, &mut profile);
    if let Err(e) = std::fs::rename(&staging, state.paths.profile_dir(&id)) {
        let _ = std::fs::remove_dir_all(&staging);
        return Err(format!("Could not finish the new profile: {e}"));
    }
    file.profiles.push(profile.clone());
    save_profiles(state, &file)?;
    Ok(json!({ "profile": profile, "mods": mod_count }))
}

#[tauri::command]
pub async fn modpack_install(app: AppHandle, state: State<'_, AppState>, project_id: String, version_id: Option<String>, name: Option<String>, title: String) -> CmdResult<String> {
    if !valid_id(&project_id) {
        return Err("Invalid modpack".into());
    }
    let (id, _) = state.jobs.create(&app, "modpack", None, &title, 0);
    jobs::spawn(&app, id.clone(), move |app, job| async move {
        let state = app.state::<AppState>();
        run(&app, &state, &job, &project_id, version_id, name).await
    });
    Ok(id)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn modpack_paths_must_stay_inside_the_profile() {
        assert_eq!(relative_safe("mods/sodium.jar"), Some(PathBuf::from("mods/sodium.jar")));
        assert_eq!(relative_safe("config/./a.txt"), Some(PathBuf::from("config/a.txt")));
        for bad in ["", "/etc/passwd", "../outside", "mods/../../x", "C:\\x", "mods\\x.jar", "c:/x"] {
            assert_eq!(relative_safe(bad), None, "{bad:?} must be refused");
        }
    }

    #[test]
    fn a_modpack_cannot_ship_flints_own_records() {
        for owned in ["flint-content.json", "flint-mods.json", ".flint-identified.json", ".flint-backup/mod/x.jar", ".flint-staging-j1/a.jar", "mods/.flint-replaced/x.jar", ".flint-backup/sync/1/files/options.txt"] {
            assert!(flint_owned(&relative_safe(owned).unwrap()), "{owned:?} belongs to Flint");
        }
        for pack in ["mods/sodium.jar", "config/flint-content.json", "options.txt", "config/sodium-options.json", "resourcepacks/Flint Pack.zip", ".fabric/remapped.jar"] {
            assert!(!flint_owned(&relative_safe(pack).unwrap()), "{pack:?} is ordinary pack content");
        }
    }

    fn pack(entries: &[(&str, usize)]) -> zip::ZipArchive<std::io::Cursor<Vec<u8>>> {
        use std::io::Write;
        let mut writer = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
        for (name, size) in entries {
            writer.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
            writer.write_all(&vec![b'x'; *size]).unwrap();
        }
        zip::ZipArchive::new(std::io::Cursor::new(writer.finish().unwrap().into_inner())).unwrap()
    }

    #[test]
    fn overrides_skip_flints_records_and_stay_inside_their_limit() {
        let dir = TestDir::new();
        let staging = dir.0.join("staging");
        let mut archive = pack(&[("overrides/config/a.txt", 10), ("overrides/flint-content.json", 10), ("client-overrides/.flint-identified.json", 10), ("overrides/../escape.txt", 10), ("modrinth.index.json", 2)]);
        extract_overrides(&mut archive, &staging, 1000).unwrap();
        assert_eq!(std::fs::read(staging.join("config/a.txt")).unwrap().len(), 10);
        assert!(!staging.join("flint-content.json").exists() && !staging.join(".flint-identified.json").exists());
        assert!(!dir.0.join("escape.txt").exists() && !staging.join("modrinth.index.json").exists());

        // The limit counts what is really written, across files.
        let mut archive = pack(&[("overrides/a.bin", 100), ("overrides/b.bin", 100), ("overrides/c.bin", 100)]);
        let error = extract_overrides(&mut archive, &dir.0.join("big"), 250).unwrap_err();
        assert!(error.contains("unexpectedly large"), "{error}");
    }
}
