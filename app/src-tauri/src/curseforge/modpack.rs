//! CurseForge modpacks: a pack's zip holds `manifest.json` (Minecraft version, mod loader, and the CurseForge files to
//! download by project and file id) and an `overrides` folder (configs and bundled files). Flint installs Fabric packs
//! as a new profile, like a Modrinth pack: every file verified against CurseForge's SHA-1, then the overrides, then the
//! profile. Forge, NeoForge and Quilt packs are refused with a clear message, as are packs with files whose authors only
//! allow downloads on CurseForge.

use super::*;
use crate::content::ContentEntry;
use crate::jobs::{self, download_verified, Expected, Fetch};
use crate::modpack::{extract_folders, flint_owned, relative_safe, MAX_OVERRIDES};
use futures_util::TryStreamExt;
use std::io::Read;
use std::path::PathBuf;
use tauri::AppHandle;

/// What a pack's `manifest.json` asks for.
#[derive(Debug, PartialEq)]
pub struct Manifest {
    pub name: String,
    pub game_version: String,
    pub loader_version: String,
    /// Required files, as (project id, file id).
    pub files: Vec<(u64, u64)>,
    /// The folder in the zip whose contents go into the profile.
    pub overrides: String,
}

/// Reads a CurseForge pack manifest. Fabric only; other loaders are named in the refusal.
pub fn parse_manifest(v: &Value) -> Result<Manifest, String> {
    if v["manifestType"].as_str().is_some_and(|t| t != "minecraftModpack") {
        return Err("This file isn't a Minecraft modpack.".into());
    }
    let game_version = v["minecraft"]["version"].as_str().filter(|g| valid_game_version(g)).ok_or("The modpack doesn't say which Minecraft version it needs.")?.to_owned();
    let loaders: Vec<&Value> = v["minecraft"]["modLoaders"].as_array().map(|l| l.iter().collect()).unwrap_or_default();
    let loader = loaders.iter().find(|l| l["primary"].as_bool() == Some(true)).or(loaders.first()).and_then(|l| l["id"].as_str()).ok_or("The modpack doesn't say which mod loader it uses.")?;
    let lower = loader.to_ascii_lowercase();
    let loader_version = match lower.split_once('-') {
        Some(("fabric", version)) => version.to_owned(),
        Some(("neoforge", _)) => return Err("This modpack needs NeoForge, which Flint doesn't support. Flint runs Fabric packs.".into()),
        Some(("forge", _)) => return Err("This modpack needs Forge, which Flint doesn't support. Flint runs Fabric packs.".into()),
        Some(("quilt", _)) => return Err("This modpack needs Quilt, which Flint doesn't support. Flint runs Fabric packs.".into()),
        _ => return Err(format!("This modpack needs {loader}, which Flint doesn't support. Flint runs Fabric packs.")),
    };
    if loader_version.is_empty() || !loader_version.chars().all(|c| c.is_ascii_alphanumeric() || ".+-".contains(c)) {
        return Err("Unexpected Fabric loader version in the modpack.".into());
    }
    let files = v["files"]
        .as_array()
        .into_iter()
        .flatten()
        .filter(|f| f["required"].as_bool() != Some(false))
        .map(|f| match (f["projectID"].as_u64(), f["fileID"].as_u64()) {
            (Some(p), Some(file)) if p > 0 && file > 0 => Ok((p, file)),
            _ => Err("The modpack lists a file Flint can't read.".to_string()),
        })
        .collect::<Result<Vec<_>, _>>()?;
    let overrides = v["overrides"].as_str().unwrap_or("overrides");
    let overrides = relative_safe(overrides).filter(|p| p.components().count() == 1).and_then(|p| p.to_str().map(str::to_owned)).ok_or("The modpack names an unsafe overrides folder.")?;
    Ok(Manifest { name: v["name"].as_str().unwrap_or_default().chars().take(80).collect(), game_version, loader_version, files, overrides })
}

/// Where a pack's file goes in the profile, by its project's class; None for content Flint doesn't place from packs.
pub fn folder_for(class_id: Option<u64>) -> Option<(&'static str, &'static str)> {
    match class_id {
        Some(CLASS_MOD) => Some(("mods", "mod")),
        Some(CLASS_RESOURCEPACK) => Some(("resourcepacks", "resourcepack")),
        Some(CLASS_SHADER) => Some(("shaderpacks", "shader")),
        _ => None,
    }
}

/// One pack file checked and ready to download.
struct Planned {
    target: PathBuf,
    url: String,
    sha1: String,
    cap: u64,
    entry: ContentEntry,
}

/// Picks the pack file to install: the one asked for, else the newest Fabric release (or, when CurseForge's files don't
/// name a loader, the newest release; its manifest decides).
fn pick_pack(list: &[Value]) -> Option<Value> {
    let fabric: Vec<Value> = list.iter().filter(|v| v["loaders"].as_array().is_some_and(|l| l.iter().any(|x| x == "fabric"))).cloned().collect();
    crate::modrinth::pick_version(if fabric.is_empty() { list } else { &fabric })
}

pub async fn run(app: &AppHandle, state: &AppState, job: &str, mod_id: u64, version_id: Option<String>, name: Option<String>) -> CmdResult<Value> {
    state.jobs.update(app, job, false, |j| {
        j.state = "running".into();
        j.stage = "Reading the modpack".into();
    });
    let project = super::project(state, mod_id).await?;
    if project["project_type"] != "modpack" {
        return Err("That project isn't a modpack.".into());
    }
    let version = match version_id.as_deref().and_then(parse_ref) {
        Some(file_id) => super::version(state, file_id).await?,
        None => {
            let list = super::versions(state, mod_id, "modpack", None).await?;
            pick_pack(list.as_array().map(Vec::as_slice).unwrap_or_default()).ok_or("This modpack has no Fabric release Flint can install.")?
        }
    };
    if version["project_id"] != project["id"] {
        return Err("That file belongs to a different project".into());
    }
    let file = &version["files"][0];
    let url = file["url"].as_str().ok_or("This modpack's author allows downloads only on CurseForge, so Flint can't install it. Open it on CurseForge instead.")?.to_owned();
    let sha1 = file["hashes"]["sha1"].as_str().ok_or("CurseForge published no hash for this modpack")?.to_owned();
    if !file["filename"].as_str().is_some_and(|n| n.to_ascii_lowercase().ends_with(".zip")) {
        return Err("This release isn't a CurseForge modpack file.".into());
    }
    let hosts = state.curseforge.download_hosts();

    let id = random_hex(6);
    let staging = state.paths.root.join("profiles").join(format!("{id}.flint-importing"));
    std::fs::create_dir_all(&staging).map_err(err("Could not prepare the new profile"))?;
    let result = async {
        let pack_path = staging.join(".pack.zip");
        download_verified(app, state, job, Fetch { url: &url, expected: Expected::Sha1(&sha1), max_bytes: jobs::listed_cap(file["size"].as_u64()), allowed_hosts: hosts, quiet: false }, &pack_path).await?;
        let pack = std::fs::File::open(&pack_path).map_err(err("Could not read the modpack"))?;
        let mut archive = zip::ZipArchive::new(std::io::BufReader::new(pack)).map_err(|_| "The modpack file is damaged.".to_string())?;
        let manifest: Value = {
            let entry = archive.by_name("manifest.json").map_err(|_| "The modpack has no manifest.".to_string())?;
            let mut text = String::new();
            entry.take(8 * 1024 * 1024).read_to_string(&mut text).map_err(|_| "The modpack manifest is unreadable.".to_string())?;
            serde_json::from_str(text.trim_start_matches('\u{feff}')).map_err(|_| "The modpack manifest is invalid.".to_string())?
        };
        let manifest = parse_manifest(&manifest)?;

        // What each listed file is, and which project it belongs to (for its folder and its name in the profile).
        state.jobs.update(app, job, false, |j| j.stage = "Checking the modpack's files".into());
        let file_ids: Vec<u64> = manifest.files.iter().map(|(_, f)| *f).collect();
        let mod_ids: Vec<u64> = manifest.files.iter().map(|(p, _)| *p).collect::<HashSet<_>>().into_iter().collect();
        let (files, mods) = futures_util::join!(super::files(state, &file_ids), super::mods(state, &mod_ids));
        let files: HashMap<u64, Value> = files?.into_iter().filter_map(|f| Some((f["id"].as_u64()?, f))).collect();
        let mods: HashMap<u64, Value> = mods?.into_iter().filter_map(|m| Some((m["id"].as_u64()?, m))).collect();

        let mut planned: Vec<Planned> = Vec::new();
        let mut kept_on_curseforge: Vec<String> = Vec::new();
        let mut names: HashSet<PathBuf> = HashSet::new();
        for (project_id, file_id) in &manifest.files {
            let found = files.get(file_id).ok_or("CurseForge no longer has some of this modpack's files.")?;
            let m = mods.get(project_id);
            let title = m.and_then(|m| m["name"].as_str()).unwrap_or_else(|| found["displayName"].as_str().unwrap_or("A file")).to_owned();
            let Some((folder, kind)) = folder_for(m.and_then(|m| m["classId"].as_u64())) else {
                eprintln!("[flint] the CurseForge modpack lists {title}, which isn't a mod, resource pack or shader pack; left out");
                continue;
            };
            let v = version_of(found);
            let Some(url) = v["files"][0]["url"].as_str() else {
                kept_on_curseforge.push(title);
                continue;
            };
            let name = found["fileName"].as_str().unwrap_or_default();
            let rel = relative_safe(&format!("{folder}/{name}")).filter(|r| r.components().count() == 2 && !flint_owned(r)).ok_or("The modpack lists an unsafe file name.")?;
            let fits = if kind == "mod" { name.to_ascii_lowercase().ends_with(".jar") } else { name.to_ascii_lowercase().ends_with(".zip") };
            if !fits || !names.insert(rel.clone()) {
                eprintln!("[flint] the CurseForge modpack lists {name} twice or as the wrong type; left out");
                continue;
            }
            let sha1 = v["files"][0]["hashes"]["sha1"].as_str().ok_or("A modpack file has no published hash.")?.to_owned();
            jobs::host_allowed(url, hosts)?;
            planned.push(Planned {
                target: staging.join(&rel),
                url: url.to_owned(),
                sha1: sha1.clone(),
                cap: jobs::listed_cap(found["fileLength"].as_u64()),
                entry: ContentEntry {
                    kind: kind.into(),
                    project_id: reference(*project_id),
                    version_id: reference(*file_id),
                    slug: m.and_then(|m| m["slug"].as_str()).unwrap_or_default().to_owned(),
                    title,
                    version_number: v["version_number"].as_str().unwrap_or_default().to_owned(),
                    file_name: name.to_owned(),
                    sha1,
                    size: found["fileLength"].as_u64().unwrap_or(0),
                    source: "modpack".into(),
                    icon_url: m.and_then(|m| icon_of(m).as_str().map(str::to_owned)),
                    installed_at: now_secs(),
                    requires: v["dependencies"].as_array().into_iter().flatten().filter(|d| d["dependency_type"] == "required").filter_map(|d| d["project_id"].as_str().map(str::to_owned)).collect(),
                    ..Default::default()
                },
            });
        }
        if !kept_on_curseforge.is_empty() {
            kept_on_curseforge.sort();
            let more = kept_on_curseforge.len().saturating_sub(5);
            let listed = kept_on_curseforge.iter().take(5).cloned().collect::<Vec<_>>().join(", ");
            return Err(format!(
                "This modpack includes files whose authors allow downloads only on CurseForge ({listed}{}), so Flint can't install it.",
                if more > 0 { format!(" and {more} more") } else { String::new() }
            ));
        }

        let total = planned.len();
        state.jobs.update(app, job, false, |j| {
            j.state = "running".into();
            j.stage = format!("Downloading pack files (0 of {total})");
            j.file = None;
            j.items_done = 0;
            j.items_total = total;
        });
        let parallel = crate::storage::modrinth_download_limit(&crate::data::load_settings(state));
        let finished = std::sync::atomic::AtomicUsize::new(0);
        futures_util::stream::iter(planned.iter().map(Ok::<&Planned, String>))
            .try_for_each_concurrent(parallel, |p| {
                let finished = &finished;
                async move {
                    download_verified(app, state, job, Fetch { url: &p.url, expected: Expected::Sha1(&p.sha1), max_bytes: p.cap, allowed_hosts: hosts, quiet: true }, &p.target).await?;
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
        let prefix = format!("{}/", manifest.overrides);
        extract_folders(&mut archive, &staging, MAX_OVERRIDES, &[prefix.as_str()])?;
        drop(archive);
        let _ = std::fs::remove_file(&pack_path);
        std::fs::create_dir_all(staging.join("mods")).map_err(err("Could not prepare the new profile"))?;
        crate::ornithe::check_pack(&manifest.game_version, staging.join("mods")).await?;
        // Flint's records of what came from CurseForge: labelled in the profile, and checked for updates like any install.
        let entries: Vec<ContentEntry> = planned.into_iter().map(|p| p.entry).collect();
        let mod_count = entries.iter().filter(|e| e.kind == "mod").count();
        write_json(&staging.join("flint-content.json"), &entries)?;
        Ok::<(Manifest, usize), String>((manifest, mod_count))
    }
    .await;

    let (manifest, mod_count) = match result {
        Ok(v) => v,
        Err(e) => {
            let _ = std::fs::remove_dir_all(&staging);
            return Err(e);
        }
    };
    let title = project["title"].as_str().filter(|t| !t.is_empty()).map(str::to_owned).unwrap_or(if manifest.name.is_empty() { "Modpack".to_owned() } else { manifest.name.clone() });
    let record = json!({
        "projectId": project["id"],
        "versionId": version["id"],
        "title": title,
        "versionNumber": version["version_number"],
        "iconUrl": project["icon_url"],
        "source": "curseforge",
    });
    crate::modpack::finish(state, staging, id, &title, name, manifest.game_version, manifest.loader_version, record, mod_count).await
}

#[cfg(test)]
mod tests {
    use super::*;

    fn manifest(loader: &str) -> Value {
        json!({
            "minecraft": { "version": "1.21.1", "modLoaders": [{ "id": loader, "primary": true }] },
            "manifestType": "minecraftModpack", "manifestVersion": 1, "name": "Fabulously Optimized", "version": "6.1.0",
            "files": [{ "projectID": 394468, "fileID": 5001, "required": true }, { "projectID": 306612, "fileID": 5002 }, { "projectID": 9, "fileID": 9, "required": false }],
            "overrides": "overrides",
        })
    }

    #[test]
    fn fabric_manifests_are_read_and_other_loaders_refused() {
        let m = parse_manifest(&manifest("fabric-0.16.9")).unwrap();
        assert_eq!(m, Manifest { name: "Fabulously Optimized".into(), game_version: "1.21.1".into(), loader_version: "0.16.9".into(), files: vec![(394468, 5001), (306612, 5002)], overrides: "overrides".into() });
        for (loader, name) in [("forge-47.2.0", "Forge"), ("neoforge-21.1.200", "NeoForge"), ("quilt-0.25.0", "Quilt")] {
            let error = parse_manifest(&manifest(loader)).unwrap_err();
            assert!(error.contains(name) && error.contains("Fabric"), "{error}");
        }
        let mut bad = manifest("fabric-0.16.9");
        bad["overrides"] = json!("../outside");
        assert!(parse_manifest(&bad).is_err());
        bad["overrides"] = json!("overrides");
        bad["minecraft"]["version"] = json!("..");
        assert!(parse_manifest(&bad).is_err());
        let mut odd = manifest("fabric-0.16.9;rm");
        assert!(parse_manifest(&odd).is_err());
        odd = manifest("fabric-0.16.9");
        odd["files"] = json!([{ "projectID": "x" }]);
        assert!(parse_manifest(&odd).is_err());
    }

    #[test]
    fn pack_files_go_to_their_folders() {
        assert_eq!(folder_for(Some(CLASS_MOD)), Some(("mods", "mod")));
        assert_eq!(folder_for(Some(CLASS_RESOURCEPACK)), Some(("resourcepacks", "resourcepack")));
        assert_eq!(folder_for(Some(CLASS_SHADER)), Some(("shaderpacks", "shader")));
        assert_eq!(folder_for(Some(17)), None, "worlds aren't placed from packs");
        assert_eq!(folder_for(None), None);
    }

    #[test]
    fn the_fabric_pack_release_is_picked() {
        let v = |id: &str, loaders: &[&str], kind: &str, date: &str| json!({ "id": id, "loaders": loaders, "version_type": kind, "date_published": date });
        let list = [v("cf:3", &["forge"], "release", "3"), v("cf:2", &["fabric"], "beta", "2"), v("cf:1", &["fabric"], "release", "1")];
        assert_eq!(pick_pack(&list).unwrap()["id"], "cf:1");
        let untagged = [v("cf:5", &["minecraft", "iris", "datapack"], "release", "5")];
        assert_eq!(pick_pack(&untagged).unwrap()["id"], "cf:5", "untagged files: the manifest decides");
    }
}
