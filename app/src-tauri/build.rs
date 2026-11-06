fn main() {
    println!("cargo:rerun-if-env-changed=FLINT_AI_GATEWAY_URL");
    println!("cargo:rerun-if-env-changed=FLINT_DISCORD_URL");
    // Friends' server (src/social.rs), as the game runtime's services get it (game-runtime/flint-services.gradle).
    println!("cargo:rerun-if-env-changed=FLINT_SUPABASE_URL");
    println!("cargo:rerun-if-env-changed=FLINT_SUPABASE_ANON_KEY");
    // Set by scripts/release-build.mjs; plain `cargo build` and dev builds have no build number.
    println!("cargo:rerun-if-env-changed=FLINT_BUILD");
    println!("cargo:rerun-if-env-changed=FLINT_COMMIT");
    // Which services a build talks to (src/channel.rs): production, or Flint's staging project and staging Store.
    println!("cargo:rerun-if-env-changed=FLINT_CHANNEL");
    println!("cargo:rerun-if-env-changed=FLINT_STORE_URL");
    check_channel();
    embed_game_runtime();
    tauri_build::build()
}

/// Flint's production Supabase project and Store, and its staging project: a build never mixes them up.
const PRODUCTION_SUPABASE_REF: &str = "vkqzlqjbpvxrskwpvijt";
const STAGING_SUPABASE_REF: &str = "mxgeolovubvnjjhwihsi";
const PRODUCTION_STORE_HOST: &str = "store.flintclient.com";

/// A staging build (`FLINT_CHANNEL=staging`) must have every staging service and none of production's, or it stops here:
/// it can never fall back to production because something was left unset. A release build of the production channel
/// refuses the staging project, so a test build can't go out as a release by mistake.
fn check_channel() {
    let env = |name: &str| std::env::var(name).unwrap_or_default().trim().to_owned();
    let channel = env("FLINT_CHANNEL");
    let supabase = env("FLINT_SUPABASE_URL");
    let key = env("FLINT_SUPABASE_ANON_KEY");
    let store = env("FLINT_STORE_URL");
    let release = std::env::var("PROFILE").is_ok_and(|p| p == "release");
    match channel.as_str() {
        "staging" => {
            let mut missing = Vec::new();
            if supabase.is_empty() || key.is_empty() {
                missing.push("FLINT_SUPABASE_URL and FLINT_SUPABASE_ANON_KEY (the staging project's address and publishable key)");
            }
            if store.is_empty() {
                missing.push("FLINT_STORE_URL (the staging Store's https origin)");
            }
            if !missing.is_empty() {
                panic!("A staging build (FLINT_CHANNEL=staging) needs {}: stopping rather than talk to production.", missing.join(" and "));
            }
            if supabase.contains(PRODUCTION_SUPABASE_REF) || store.contains(PRODUCTION_STORE_HOST) {
                panic!("A staging build (FLINT_CHANNEL=staging) was given a production address: stopping.");
            }
            if !store.starts_with("https://") {
                panic!("FLINT_STORE_URL must be the staging Store's https origin.");
            }
        }
        "" | "production" => {
            if release && supabase.contains(STAGING_SUPABASE_REF) {
                panic!("A release build was given Flint's staging project: set FLINT_CHANNEL=staging for a staging build.");
            }
        }
        other => panic!("Unknown FLINT_CHANNEL {other:?}: use staging, or leave it unset for production."),
    }
}

/// The Minecraft lines `game-runtime/` builds a jar for (src/runtime.rs embeds one per line), each with
/// the Minecraft version it is compiled against. 26.3 is the main build (`./gradlew build`); the other
/// 26.x lines are built with `-Pminecraft_version=<version>` (26.1's one jar, built against 26.1.2, serves
/// every 26.1 release), and the obfuscated 1.21.x lines by the `legacy` build
/// (`./gradlew -p legacy build -Pminecraft_version=<version>`); 1.8.9 has a code base of its own (`./gradlew -p legacy189 build`).
const RUNTIME_LINES: &[(&str, &str)] =
    &[("26.3", "26.3"), ("26.2", "26.2"), ("26.1", "26.1.2"), ("1.21.11", "1.21.11"), ("1.21.10", "1.21.10"), ("1.21.1", "1.21.1"), ("1.8.9", "1.8.9")];

/// The order the jars are packed in. src/runtime.rs unpacks a line by reading the stream up to the end of its jar, so the
/// lines players start most come first (the newest line, then 1.8.9), and the rest sit beside the lines they share most
/// of their files with.
const PACK_ORDER: &[&str] = &["26.3", "1.8.9", "26.2", "26.1", "1.21.11", "1.21.10", "1.21.1"];

/// Brotli at quality 9 with a 32 MiB window ("large window" brotli, which src/runtime.rs's decoder reads): each jar's
/// files are stored uncompressed first (`stored_jar`), so brotli sees the class files themselves and finds what a line
/// shares with the lines packed before it, even where the bytecode differs a little. The seven jars (about 66 MB stored,
/// 33 MB as Gradle builds them) pack into about 5 MB; packing the jars as built, with a 16 MiB window, gave 11.7 MB.
/// Quality 11 saves under 1% more and takes seven times as long.
const PACK_QUALITY: i32 = 9;
const PACK_WINDOW_BITS: i32 = 25;

/// The jar as Gradle built it, with every entry stored instead of deflated: the same files, names, order and times, which
/// Fabric and Java load exactly as before (a stored entry is only read, never inflated). This is the jar the app installs
/// in a profile's `mods` folder, about twice the size of Gradle's on disk.
fn stored_jar(line: &str, jar: &[u8]) -> Vec<u8> {
    use std::io::{Read, Write};
    if jar.is_empty() {
        return Vec::new();
    }
    let mut archive = zip::ZipArchive::new(std::io::Cursor::new(jar)).unwrap_or_else(|e| panic!("the {line} runtime jar can't be read: {e}"));
    let mut out = zip::ZipWriter::new(std::io::Cursor::new(Vec::with_capacity(jar.len() * 2)));
    for i in 0..archive.len() {
        let mut entry = archive.by_index(i).expect("a runtime jar entry");
        let mut options = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored);
        if let Some(time) = entry.last_modified() {
            options = options.last_modified_time(time);
        }
        let name = entry.name().to_owned();
        if entry.is_dir() {
            out.add_directory(name, options).expect("store a runtime jar folder");
        } else {
            let mut bytes = Vec::with_capacity(entry.size() as usize);
            entry.read_to_end(&mut bytes).expect("read a runtime jar entry");
            out.start_file(name, options).expect("store a runtime jar entry");
            out.write_all(&bytes).expect("store a runtime jar entry");
        }
    }
    out.finish().expect("write the stored runtime jar").into_inner()
}

/// Embeds Flint's in-game runtime jars (built by `game-runtime/`, versioned with the app), one per Minecraft line, packed
/// into one brotli stream (`flint-runtimes.br`) with the list of what is in it (`flint-runtimes.rs`: each line's jar size
/// and SHA-256, in stream order). The stream is unpacked again here and checked against that list before the app is
/// built. Without a jar the app still builds; profiles on that line then simply don't get Flint's in-game menu.
fn embed_game_runtime() {
    use sha2::Digest;
    use std::io::Read;

    assert_eq!(PACK_ORDER.len(), RUNTIME_LINES.len(), "every runtime line is packed once");
    let manifest = std::path::PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap());
    let version = std::env::var("CARGO_PKG_VERSION").unwrap();
    let out_dir = std::path::PathBuf::from(std::env::var("OUT_DIR").unwrap());
    let mut jars = Vec::new();
    let mut listing = String::from("&[\n");
    for line in PACK_ORDER {
        let i = RUNTIME_LINES.iter().position(|(l, _)| l == line).expect("every packed line is a runtime line");
        let minecraft = RUNTIME_LINES[i].1;
        let jar = if i == 0 {
            manifest.join(format!("../../game-runtime/build/libs/flint-game-runtime-{version}.jar"))
        } else {
            manifest.join(format!("../../game-runtime/build/mc-{line}/libs/flint-game-runtime-{version}+{line}.jar"))
        };
        println!("cargo:rerun-if-changed={}", jar.display());
        let bytes = std::fs::read(&jar).unwrap_or_else(|_| {
            let build = if i == 0 {
                "./gradlew build".to_string()
            } else if minecraft == "1.8.9" {
                "./gradlew -p legacy189 build".to_string()
            } else if minecraft.starts_with("1.") {
                format!("./gradlew -p legacy build -Pminecraft_version={minecraft}")
            } else {
                format!("./gradlew build -Pminecraft_version={minecraft}")
            };
            println!("cargo:warning=game-runtime jar for Minecraft {line} not found at {}; building without Flint's in-game menu on {line} (run {build} in game-runtime/)", jar.display());
            Vec::new()
        });
        let bytes = stored_jar(line, &bytes);
        let sha256: [u8; 32] = sha2::Sha256::digest(&bytes).into();
        listing.push_str(&format!("    ({line:?}, {}, {sha256:?}),\n", bytes.len()));
        jars.extend_from_slice(&bytes);
    }
    listing.push(']');

    let params = brotli::enc::BrotliEncoderParams { quality: PACK_QUALITY, lgwin: PACK_WINDOW_BITS, large_window: true, size_hint: jars.len(), ..Default::default() };
    let mut packed = Vec::new();
    brotli::BrotliCompress(&mut jars.as_slice(), &mut packed, &params).expect("pack the runtime jars");
    let mut unpacked = Vec::with_capacity(jars.len());
    brotli::Decompressor::new(packed.as_slice(), 64 * 1024).read_to_end(&mut unpacked).expect("unpack the runtime jars");
    assert!(unpacked == jars, "the packed runtime jars unpack to exactly what was packed");

    std::fs::write(out_dir.join("flint-runtimes.br"), &packed).expect("write the packed runtime jars");
    std::fs::write(out_dir.join("flint-runtimes.rs"), listing).expect("write the runtime jar listing");
}
