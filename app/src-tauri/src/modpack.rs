use crate::content::MODRINTH_CDN;
use crate::core::*;
use crate::data::{load_profiles, save_profiles, Profile};
use crate::jobs::{self, download_verified, Expected, Fetch};
use crate::modrinth::*;
use serde_json::{json, Value};
use std::io::Read;
use std::path::{Component, Path, PathBuf};
use tauri::{AppHandle, Manager, State};

/// Hosts the Modrinth modpack format allows pack files to be downloaded from.
const PACK_HOSTS: &[&str] = &["cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com"];
const MAX_PACK: u64 = 256 * 1024 * 1024;
const MAX_FILE: u64 = 256 * 1024 * 1024;
const MAX_OVERRIDES: u64 = 1024 * 1024 * 1024;

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
        download_verified(app, state, job, Fetch { url: &url, expected: Expected::Sha512(&sha512), max_bytes: MAX_PACK, allowed_hosts: MODRINTH_CDN }, &pack_path).await?;
        let bytes = std::fs::read(&pack_path).map_err(err("Could not read the modpack"))?;
        let mut archive = zip::ZipArchive::new(std::io::Cursor::new(bytes)).map_err(|_| "The modpack file is damaged.".to_string())?;
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
        let total = wanted.len();
        let mut installed_mods = Vec::new();
        for (i, f) in wanted.iter().enumerate() {
            state.jobs.update(app, job, false, |j| {
                j.stage = format!("Downloading pack files ({} of {total})", i + 1);
                j.items_done = i;
                j.items_total = total;
            });
            let rel = f["path"].as_str().and_then(relative_safe).ok_or("The modpack lists an unsafe file path.")?;
            let target = staging.join(&rel);
            let size = f["fileSize"].as_u64().unwrap_or(0);
            if size > MAX_FILE {
                return Err("The modpack lists a file that is unexpectedly large.".into());
            }
            let downloads: Vec<&str> = f["downloads"].as_array().map(|d| d.iter().filter_map(Value::as_str).collect()).unwrap_or_default();
            let url = downloads.iter().find(|u| jobs::host_allowed(u, PACK_HOSTS).is_ok()).ok_or("The modpack points to a download host Flint doesn't trust.")?;
            let expected = match (f["hashes"]["sha512"].as_str(), f["hashes"]["sha1"].as_str()) {
                (Some(h), _) => Expected::Sha512(h),
                (None, Some(h)) => Expected::Sha1(h),
                _ => return Err("A modpack file has no published hash.".into()),
            };
            download_verified(app, state, job, Fetch { url, expected, max_bytes: MAX_FILE, allowed_hosts: PACK_HOSTS }, &target).await?;
            if rel.starts_with("mods") {
                installed_mods.push(rel.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default());
            }
        }

        state.jobs.update(app, job, false, |j| {
            j.stage = "Applying pack settings".into();
            j.items_done = total;
        });
        let mut extracted: u64 = 0;
        for i in 0..archive.len() {
            let mut entry = archive.by_index(i).map_err(|_| "The modpack file is damaged.".to_string())?;
            let name = entry.name().to_owned();
            let rel = name.strip_prefix("overrides/").or_else(|| name.strip_prefix("client-overrides/"));
            let Some(rel) = rel.and_then(relative_safe) else { continue };
            let target = staging.join(&rel);
            if entry.is_dir() {
                std::fs::create_dir_all(&target).map_err(err("Could not apply pack settings"))?;
                continue;
            }
            extracted += entry.size();
            if extracted > MAX_OVERRIDES {
                return Err("The modpack's bundled files are unexpectedly large.".into());
            }
            if let Some(parent) = target.parent() {
                std::fs::create_dir_all(parent).map_err(err("Could not apply pack settings"))?;
            }
            let mut out = std::fs::File::create(&target).map_err(err("Could not apply pack settings"))?;
            std::io::copy(&mut entry.by_ref().take(MAX_FILE), &mut out).map_err(err("Could not apply pack settings"))?;
        }
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
    let final_dir = state.paths.profile_dir(&id);
    std::fs::rename(&staging, &final_dir).map_err(err("Could not finish the new profile"))?;
    let title = project["title"].as_str().unwrap_or("Modpack").to_owned();
    let profile_name: String = name.map(|n| n.trim().to_owned()).filter(|n| !n.is_empty()).unwrap_or_else(|| title.clone()).chars().take(40).collect();
    let profile = Profile {
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
    };
    let _guard = state.file_lock.lock().await;
    let mut file = load_profiles(state);
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
}
