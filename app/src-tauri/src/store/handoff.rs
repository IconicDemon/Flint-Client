//! The launcher → game Store handoff (docs/STORE-INTEGRATION.md): a small file, `.flint-store.json`, in the profile folder
//! (the game directory) that says which Flint Store the launcher uses and when the player last changed what they wear
//! through the launcher. The game reads it to fetch **public** equipped cosmetics from that Store
//! (`/api/v1/players/cosmetics/?uuids=`) and to refetch its own after a change. It carries no token, no account and
//! nothing private: the game never needs the Store session.
//!
//! Like Flint's other per-machine files in a profile (`.flint-identified.json`), a dot file at the top of the folder is never
//! exported in a `.flintpack`, copied by Auto Sync or duplicated with a profile.
use super::{now_millis, CONTRACT_VERSION};
use crate::core::*;
use serde::Serialize;
use std::sync::atomic::Ordering;

pub const FILE: &str = ".flint-store.json";
/// The version of this file's layout. A game that doesn't know it ignores the file.
pub const SCHEMA: u32 = 1;

#[derive(Serialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Handoff {
    pub schema: u32,
    /// The Store launcher API version the launcher speaks (`/api/v1/`).
    pub contract_version: u32,
    /// The Store's origin, without a trailing slash: `https://store.flintclient.com`.
    pub store_base_url: String,
    /// The public lookup the game uses: `<storeBaseUrl>/api/v1/players/cosmetics/`.
    pub public_cosmetics_url: String,
    /// When the player last changed what they wear through this launcher (ms since the epoch), 0 if not since it started.
    /// A game that sees it change refetches its own player's cosmetics.
    pub equip_revision: u64,
}

pub fn handoff(state: &AppState) -> Handoff {
    let base = state.store.endpoints.store_base();
    Handoff {
        schema: SCHEMA,
        contract_version: CONTRACT_VERSION,
        public_cosmetics_url: format!("{base}/api/v1/players/cosmetics/"),
        store_base_url: base,
        equip_revision: state.store.equip_revision.load(Ordering::SeqCst),
    }
}

/// Writes the handoff into a profile's folder (at launch). A failure never stops the game: it then uses its built-in Store.
pub fn write(state: &AppState, profile_id: &str) -> CmdResult<()> {
    let bytes = serde_json::to_vec_pretty(&handoff(state)).map_err(|e| e.to_string())?;
    write_atomic(&state.paths.profile_dir(profile_id).join(FILE), &bytes)
}

/// The player changed what they wear: newer revision, rewritten for every game Flint started that is still running.
pub fn equip_changed(state: &AppState) {
    let previous = state.store.equip_revision.load(Ordering::SeqCst);
    state.store.equip_revision.store(now_millis().max(previous + 1), Ordering::SeqCst);
    let running: Vec<String> = state.running.lock().unwrap().keys().cloned().collect();
    for id in running {
        if let Err(error) = write(state, &id) {
            eprintln!("[flint] couldn't tell a running game about a Store change: {error}");
        }
    }
}
