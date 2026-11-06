//! Mods put into a profile's mods folder in a form Fabric can't load, made loadable before the game starts (Fabric reads
//! the mods folder once, as the game starts, so this is the launcher's to do; the game itself does the same for resource
//! packs, shader packs and worlds while it runs: game-runtime's `files/PackFixer`).
//!
//! - A .zip, .rar or .7z holding Fabric mods (a download that came zipped, or several mods in one archive): every Fabric
//!   mod jar in it (a jar with `fabric.mod.json`, a few folders down at most) is put into the mods folder. Library jars
//!   and mods for other loaders inside it are left out: Fabric would refuse or ignore them.
//! - A resource pack or shader pack put into the mods folder by mistake (an archive with `pack.mcmeta`, or a `shaders`
//!   folder, and no mod in it) goes to the folder it belongs in.
//!
//! The archive itself is kept as it was in `flint/originals/mods` of the profile, never deleted. .rar and .7z are read
//! with the computer's own archiver (bsdtar: Windows 10 and 11, macOS); without one they stay where they are.

use std::io::{Read, Seek};
use std::path::{Path, PathBuf};

/// The most an archive may unpack to, and the most jars taken from one: past them it is left as it is.
const MAX_BYTES: u64 = 4 << 30;
const MAX_JARS: usize = 400;

/// What was done, for the launch log.
#[derive(Debug, Default, PartialEq)]
pub struct Report {
    pub lines: Vec<String>,
}

/// Looks at `profile_dir`'s mods folder and makes what it can loadable. Errors only skip the file they concern.
pub fn tidy(profile_dir: &Path) -> Report {
    let mods = profile_dir.join("mods");
    let mut report = Report::default();
    let Ok(entries) = std::fs::read_dir(&mods) else { return report };
    let mut archives: Vec<PathBuf> = entries
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.is_file() && archive_kind(p).is_some())
        .collect();
    archives.sort();
    for archive in archives {
        let name = archive.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        match take(profile_dir, &archive) {
            Ok(Some(line)) => report.lines.push(format!("{name}: {line}")),
            Ok(None) => {}
            Err(error) => report.lines.push(format!("{name} was left as it is: {error}")),
        }
    }
    report
}

#[derive(Debug, Clone, Copy, PartialEq)]
enum Kind {
    Zip,
    Other,
}

fn archive_kind(path: &Path) -> Option<Kind> {
    let name = path.file_name()?.to_string_lossy().to_lowercase();
    if name.starts_with('.') {
        None
    } else if name.ends_with(".zip") {
        Some(Kind::Zip)
    } else if name.ends_with(".rar") || name.ends_with(".7z") {
        Some(Kind::Other)
    } else {
        None
    }
}

/// One archive: its mods put in place (and it kept in originals), or it moved to where it belongs; None when it is none
/// of these (left alone).
fn take(profile_dir: &Path, archive: &Path) -> Result<Option<String>, String> {
    let scratch = profile_dir.join("flint").join("unpacking").join(format!("mods-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&scratch);
    std::fs::create_dir_all(&scratch).map_err(|e| e.to_string())?;
    let result = take_in(profile_dir, archive, &scratch);
    let _ = std::fs::remove_dir_all(&scratch);
    result
}

fn take_in(profile_dir: &Path, archive: &Path, scratch: &Path) -> Result<Option<String>, String> {
    let zip_path = match archive_kind(archive) {
        Some(Kind::Zip) => archive.to_path_buf(),
        Some(Kind::Other) => {
            let converted = scratch.join("archive.zip");
            to_zip(archive, &converted, scratch)?;
            converted
        }
        None => return Ok(None),
    };
    let file = std::fs::File::open(&zip_path).map_err(|e| e.to_string())?;
    let mut zip = zip::ZipArchive::new(std::io::BufReader::new(file)).map_err(|e| format!("it can't be read ({e})"))?;
    let contents = Contents::of(&mut zip);
    let mods = profile_dir.join("mods");
    if !contents.jars.is_empty() {
        let mut taken = Vec::new();
        let mut total = 0u64;
        for name in contents.jars.iter().take(MAX_JARS) {
            let mut entry = zip.by_name(name).map_err(|e| e.to_string())?;
            total += entry.size();
            if total > MAX_BYTES {
                return Err("it unpacks to more than 4 GB".into());
            }
            let mut bytes = Vec::with_capacity(entry.size() as usize);
            entry.read_to_end(&mut bytes).map_err(|e| e.to_string())?;
            if is_fabric_mod(&bytes) {
                taken.push((file_name(name), bytes));
            }
        }
        if taken.is_empty() {
            return Err("it has no Fabric mods in it".into());
        }
        let kept = keep(profile_dir, archive)?;
        let mut placed = Vec::new();
        for (name, bytes) in &taken {
            let target = mods.join(name);
            if target.exists() && std::fs::read(&target).is_ok_and(|existing| existing == *bytes) {
                continue;
            }
            let target = unique(&mods, name);
            if let Err(error) = write_new(&target, bytes) {
                for path in &placed {
                    let _ = std::fs::remove_file(path);
                }
                let _ = std::fs::rename(&kept, archive);
                return Err(error);
            }
            placed.push(target);
        }
        let names: Vec<String> = taken.iter().map(|(n, _)| n.clone()).collect();
        return Ok(Some(format!("unpacked {} (the archive is kept in flint/originals/mods)", names.join(", "))));
    }
    // No mod in it: a pack put into the wrong folder goes where it belongs (the game makes it readable there).
    let destination = if contents.pack {
        Some("resourcepacks")
    } else if contents.shaders {
        Some("shaderpacks")
    } else {
        None
    };
    let Some(folder) = destination else { return Ok(None) };
    let dir = profile_dir.join(folder);
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let target = unique(&dir, &archive.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default());
    move_file(archive, &target)?;
    Ok(Some(format!("moved to {folder} (it's a {} pack, not a mod)", if folder == "resourcepacks" { "resource" } else { "shader" })))
}

/// What an archive holds, by entry name.
struct Contents {
    /// Jars, a few folders down at most (macOS's metadata folders left out).
    jars: Vec<String>,
    /// A resource pack's `pack.mcmeta` at any depth.
    pack: bool,
    /// A shader pack's `shaders` folder at any depth.
    shaders: bool,
}

impl Contents {
    fn of<R: Read + Seek>(zip: &mut zip::ZipArchive<R>) -> Self {
        let mut contents = Contents { jars: Vec::new(), pack: false, shaders: false };
        for i in 0..zip.len() {
            let Ok(entry) = zip.by_index_raw(i) else { continue };
            let name = entry.name().replace('\\', "/");
            if name.split('/').any(|part| part == "__MACOSX" || part == "..") {
                continue;
            }
            let lower = name.to_lowercase();
            if lower.ends_with(".jar") && !entry.is_dir() && name.matches('/').count() <= 3 {
                contents.jars.push(entry.name().to_owned());
            }
            contents.pack |= lower == "pack.mcmeta" || lower.ends_with("/pack.mcmeta");
            contents.shaders |= lower.starts_with("shaders/") || lower.contains("/shaders/");
        }
        contents
    }
}

/// Whether a jar is a Fabric (or Quilt) mod: it has `fabric.mod.json` (or `quilt.mod.json`) at its top.
fn is_fabric_mod(bytes: &[u8]) -> bool {
    let Ok(mut jar) = zip::ZipArchive::new(std::io::Cursor::new(bytes)) else { return false };
    let found = jar.by_name("fabric.mod.json").is_ok() || jar.by_name("quilt.mod.json").is_ok();
    found
}

fn file_name(entry: &str) -> String {
    let name = entry.replace('\\', "/");
    let base = name.rsplit('/').next().unwrap_or(&name);
    base.chars().map(|c| if matches!(c, '<' | '>' | ':' | '"' | '|' | '?' | '*') || c.is_control() { '_' } else { c }).collect()
}

/// `name` in `dir`, or "name (2).jar"... when that is taken.
fn unique(dir: &Path, name: &str) -> PathBuf {
    let path = dir.join(name);
    if !path.exists() {
        return path;
    }
    let (stem, extension) = match name.rfind('.') {
        Some(dot) if dot > 0 => (&name[..dot], &name[dot..]),
        _ => (name, ""),
    };
    (2..).map(|i| dir.join(format!("{stem} ({i}){extension}"))).find(|p| !p.exists()).expect("a free name")
}

/// Writes a new file whole or not at all (a part file, then renamed).
fn write_new(target: &Path, bytes: &[u8]) -> Result<(), String> {
    let part = target.with_extension("jar.flint-part");
    std::fs::write(&part, bytes).map_err(|e| e.to_string())?;
    std::fs::rename(&part, target).map_err(|e| {
        let _ = std::fs::remove_file(&part);
        e.to_string()
    })
}

fn move_file(from: &Path, to: &Path) -> Result<(), String> {
    if std::fs::rename(from, to).is_ok() {
        return Ok(());
    }
    std::fs::copy(from, to).map_err(|e| e.to_string())?;
    std::fs::remove_file(from).map_err(|e| e.to_string())
}

/// Moves the archive to `flint/originals/mods`, under a name not taken there.
fn keep(profile_dir: &Path, archive: &Path) -> Result<PathBuf, String> {
    let dir = profile_dir.join("flint").join("originals").join("mods");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let kept = unique(&dir, &archive.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default());
    move_file(archive, &kept)?;
    Ok(kept)
}

/// A .rar or .7z as a .zip, with bsdtar (Windows' own tar.exe, macOS's tar, or a bsdtar on Linux). Plain names on its
/// command line: Windows' tar reads them in the system code page.
fn to_zip(archive: &Path, zip: &Path, scratch: &Path) -> Result<(), String> {
    let tar = bsdtar().ok_or("this computer can't open .rar or .7z files; unpack it, or save it as a .zip")?;
    let extension = archive.extension().map(|e| e.to_string_lossy().to_lowercase()).unwrap_or_default();
    let input = scratch.join(format!("input.{extension}"));
    if std::fs::hard_link(archive, &input).is_err() {
        std::fs::copy(archive, &input).map_err(|e| e.to_string())?;
    }
    let mut command = std::process::Command::new(&tar);
    command
        .args(["--format", "zip", "--options", "zip:compression=store,zip:hdrcharset=UTF-8", "-cf", "output.zip"])
        .arg(format!("@input.{extension}"))
        .current_dir(scratch)
        .stdin(std::process::Stdio::null());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x0800_0000);
    }
    let output = command.output().map_err(|e| e.to_string())?;
    let _ = std::fs::remove_file(&input);
    if !output.status.success() {
        let said = String::from_utf8_lossy(&output.stderr).to_lowercase();
        return Err(if said.contains("encrypt") || said.contains("passphrase") || said.contains("password") {
            "it's password-protected".into()
        } else {
            "it couldn't be unpacked; unpack it, or save it as a .zip".into()
        });
    }
    std::fs::rename(scratch.join("output.zip"), zip).map_err(|e| e.to_string())
}

fn bsdtar() -> Option<PathBuf> {
    let candidates: Vec<PathBuf> = if cfg!(windows) {
        vec![PathBuf::from(std::env::var("SystemRoot").unwrap_or_else(|_| r"C:\Windows".into())).join(r"System32\tar.exe")]
    } else if cfg!(target_os = "macos") {
        vec!["/usr/bin/bsdtar".into(), "/usr/bin/tar".into()]
    } else {
        vec!["/usr/bin/bsdtar".into(), "/usr/local/bin/bsdtar".into()]
    };
    candidates.into_iter().find(|p| p.is_file())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::TestDir;
    use std::io::Write;

    fn zip_of(entries: &[(&str, Vec<u8>)]) -> Vec<u8> {
        let mut out = std::io::Cursor::new(Vec::new());
        {
            let mut zip = zip::ZipWriter::new(&mut out);
            for (name, bytes) in entries {
                zip.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
                zip.write_all(bytes).unwrap();
            }
            zip.finish().unwrap();
        }
        out.into_inner()
    }

    fn fabric_mod(id: &str) -> Vec<u8> {
        zip_of(&[("fabric.mod.json", format!("{{\"id\":\"{id}\"}}").into_bytes()), ("a/B.class", vec![0xCA, 0xFE])])
    }

    fn profile() -> (TestDir, PathBuf) {
        let dir = TestDir::new();
        let profile = dir.0.join("p");
        std::fs::create_dir_all(profile.join("mods")).unwrap();
        (dir, profile)
    }

    #[test]
    fn mods_in_a_zip_are_put_in_the_mods_folder_and_the_zip_is_kept() {
        let (_dir, p) = profile();
        let library = zip_of(&[("com/x/Lib.class", vec![1])]);
        let archive = zip_of(&[("Pack of mods/sodium.jar", fabric_mod("sodium")), ("Pack of mods/lib/library.jar", library), ("__MACOSX/._sodium.jar", vec![0])]);
        std::fs::write(p.join("mods/My mods.zip"), &archive).unwrap();
        let report = tidy(&p);
        assert_eq!(report.lines.len(), 1, "{report:?}");
        assert!(report.lines[0].contains("sodium.jar"), "{report:?}");
        assert_eq!(std::fs::read(p.join("mods/sodium.jar")).unwrap(), fabric_mod("sodium"));
        assert!(!p.join("mods/library.jar").exists(), "a jar that is no Fabric mod is left out");
        assert!(!p.join("mods/My mods.zip").exists());
        assert_eq!(std::fs::read(p.join("flint/originals/mods/My mods.zip")).unwrap(), archive);
        assert!(!p.join("flint/unpacking").read_dir().is_ok_and(|mut d| d.next().is_some()), "no scratch left");
        // Nothing left to do the next time.
        assert_eq!(tidy(&p), Report::default());
    }

    #[test]
    fn a_mod_already_there_is_not_copied_twice_and_a_different_one_of_the_same_name_gets_a_number() {
        let (_dir, p) = profile();
        std::fs::write(p.join("mods/same.jar"), fabric_mod("same")).unwrap();
        std::fs::write(p.join("mods/other.jar"), fabric_mod("old")).unwrap();
        std::fs::write(p.join("mods/two.zip"), zip_of(&[("same.jar", fabric_mod("same")), ("other.jar", fabric_mod("new"))])).unwrap();
        tidy(&p);
        assert!(!p.join("mods/same (2).jar").exists());
        assert_eq!(std::fs::read(p.join("mods/other (2).jar")).unwrap(), fabric_mod("new"));
        assert_eq!(std::fs::read(p.join("mods/other.jar")).unwrap(), fabric_mod("old"));
    }

    #[test]
    fn packs_put_into_mods_go_where_they_belong() {
        let (_dir, p) = profile();
        let pack = zip_of(&[("pack.mcmeta", b"{}".to_vec())]);
        let shaders = zip_of(&[("Cool/shaders/composite.fsh", b"x".to_vec())]);
        std::fs::write(p.join("mods/Faithful.zip"), &pack).unwrap();
        std::fs::write(p.join("mods/Cool Shaders.zip"), &shaders).unwrap();
        let report = tidy(&p);
        assert_eq!(report.lines.len(), 2, "{report:?}");
        assert_eq!(std::fs::read(p.join("resourcepacks/Faithful.zip")).unwrap(), pack);
        assert_eq!(std::fs::read(p.join("shaderpacks/Cool Shaders.zip")).unwrap(), shaders);
        assert!(!p.join("mods/Faithful.zip").exists() && !p.join("mods/Cool Shaders.zip").exists());
    }

    #[test]
    fn archives_that_are_neither_are_left_alone() {
        let (_dir, p) = profile();
        std::fs::write(p.join("mods/notes.zip"), zip_of(&[("readme.txt", b"hi".to_vec())])).unwrap();
        std::fs::write(p.join("mods/forge-only.zip"), zip_of(&[("forgemod.jar", zip_of(&[("META-INF/mods.toml", b"x".to_vec())]))])).unwrap();
        std::fs::write(p.join("mods/real.jar"), fabric_mod("real")).unwrap();
        let report = tidy(&p);
        assert!(p.join("mods/notes.zip").exists() && p.join("mods/forge-only.zip").exists() && p.join("mods/real.jar").exists());
        assert_eq!(report.lines.len(), 1, "the archive with jars but no Fabric mod is mentioned: {report:?}");
        assert!(report.lines[0].contains("no Fabric mods"));
    }

    /// The real thing on Windows 10/11 and macOS: a .7z made by this computer's bsdtar.
    #[test]
    fn mods_in_a_7z_are_unpacked_with_the_computers_archiver() {
        let Some(tar) = bsdtar() else { return };
        let (dir, p) = profile();
        let source = dir.0.join("src");
        std::fs::create_dir_all(&source).unwrap();
        std::fs::write(source.join("lithium.jar"), fabric_mod("lithium")).unwrap();
        let made = std::process::Command::new(&tar).args(["--format", "7zip", "-cf", "../made.7z", "lithium.jar"]).current_dir(&source).output().unwrap();
        if !made.status.success() {
            return;
        }
        std::fs::rename(dir.0.join("made.7z"), p.join("mods/Lithium ✦.7z")).unwrap();
        let report = tidy(&p);
        assert_eq!(std::fs::read(p.join("mods/lithium.jar")).unwrap(), fabric_mod("lithium"), "{report:?}");
        assert!(p.join("flint/originals/mods/Lithium ✦.7z").is_file());
    }
}
