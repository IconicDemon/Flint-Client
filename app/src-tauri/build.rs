fn main() {
    println!("cargo:rerun-if-env-changed=FLINT_AI_GATEWAY_URL");
    println!("cargo:rerun-if-env-changed=FLINT_DISCORD_URL");
    // Set by scripts/release-build.mjs; plain `cargo build` and dev builds have no build number.
    println!("cargo:rerun-if-env-changed=FLINT_BUILD");
    println!("cargo:rerun-if-env-changed=FLINT_COMMIT");
    embed_game_runtime();
    tauri_build::build()
}

/// Embeds Flint's in-game runtime jar (built by `game-runtime/`, versioned with the app). Without it
/// the app still builds; profiles then simply don't get Flint's in-game menu.
fn embed_game_runtime() {
    let manifest = std::path::PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap());
    let version = std::env::var("CARGO_PKG_VERSION").unwrap();
    let jar = manifest.join(format!("../../game-runtime/build/libs/flint-game-runtime-{version}.jar"));
    let out = std::path::PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("flint-runtime.jar");
    println!("cargo:rerun-if-changed={}", jar.display());
    match std::fs::read(&jar) {
        Ok(bytes) => std::fs::write(&out, bytes).expect("write the runtime jar"),
        Err(_) => {
            println!("cargo:warning=game-runtime jar not found at {}; building without Flint's in-game menu (run ./gradlew build in game-runtime/)", jar.display());
            std::fs::write(&out, []).expect("write the empty runtime placeholder");
        }
    }
}
