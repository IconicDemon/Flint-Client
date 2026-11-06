//! Playing on a partner's Minecraft server, only after the player confirmed exactly which server goes into which
//! profile. Nothing here runs because a listing was shown or opened.
//!
//! The server is looked up by listing id in the launcher's own copy of the feed (the page can't hand it another address),
//! and checked again: a Minecraft server listing running now, Java Edition, a public host (rules.rs, also after DNS) and
//! a port, and a profile whose version the server takes. Then the launch (game.rs) adds it to that profile's Multiplayer
//! list once Auto Sync has prepared the profile and just before Java starts (gamesettings::serverlist: never twice, never
//! changing an entry), and tells the game to join it (rules::join_args).

use super::rules::{self, Versions};
use crate::core::{AppState, CmdResult, ProcessScan};
use serde::Serialize;
use serde_json::Value;
use tauri::{AppHandle, State};

/// The server a confirmed Play joins.
#[derive(Clone, Debug, PartialEq)]
pub struct Join {
    pub name: String,
    pub host: String,
    pub port: u16,
    /// `host` or `host:port`, as the server list and Quick Play write it.
    pub address: String,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Target {
    pub join: Join,
    pub versions: Option<Versions>,
}

/// What the launch did with the server list, for the page.
#[derive(Serialize, Clone, Debug, Default, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Listing {
    pub address: String,
    pub added: bool,
    pub already_listed: bool,
    /// Why the server couldn't be added (the game still joins it).
    pub problem: Option<String>,
}

const NOT_LISTED: &str = "This partner isn't listed any more. Open Library → Partnerships to see the current listings.";

fn versions(raw: &Value) -> Option<Versions> {
    let one = |v: &Value| v.as_str().filter(|s| rules::is_version(s)).map(str::to_owned);
    let list: Option<Vec<String>> = raw["list"].as_array().map(|l| l.iter().filter_map(one).take(64).collect::<Vec<_>>()).filter(|l| !l.is_empty());
    let v = Versions { min: one(&raw["min"]), max: one(&raw["max"]), list };
    (v.min.is_some() || v.max.is_some() || v.list.is_some()).then_some(v)
}

/// The Minecraft server of listing `id` in `body`, checked (see the module notes) at `now` (Unix seconds).
pub fn target_in(body: &Value, id: &str, now: i64, loopback: bool) -> CmdResult<Target> {
    if !crate::store::is_uuid(id) {
        return Err(NOT_LISTED.into());
    }
    let partner = body["partners"].as_array().into_iter().flatten().find(|p| p["id"].as_str() == Some(id)).ok_or(NOT_LISTED)?;
    if partner["kind"].as_str() != Some("minecraft_server") {
        return Err("This partner isn't a Minecraft server.".into());
    }
    let time = |key: &str| -> CmdResult<Option<i64>> {
        match &partner[key] {
            Value::Null => Ok(None),
            Value::String(s) => crate::store::rfc3339_secs(s).map(Some).ok_or_else(|| NOT_LISTED.to_owned()),
            _ => Err(NOT_LISTED.into()),
        }
    };
    let (starts, ends) = (time("starts_at")?, time("ends_at")?);
    if starts.is_some_and(|s| now < s) || ends.is_some_and(|e| now >= e) {
        return Err("This promotion isn't running now, so Flint won't join its server.".into());
    }
    let server = &partner["server"];
    let edition = server["edition"].as_str().unwrap_or("java");
    if edition != "java" {
        return Err("This server is for another edition of Minecraft. Flint plays Java Edition.".into());
    }
    let host = server["host"].as_str().ok_or("This listing has no server address.")?;
    let host = rules::check_host(host, loopback).map_err(|problem| match problem {
        rules::HostProblem::Private => rules::PRIVATE.to_owned(),
        rules::HostProblem::Invalid => "This listing's server address isn't one Flint can join.".to_owned(),
    })?;
    let port = match &server["port"] {
        Value::Null => rules::DEFAULT_PORT,
        v => v.as_u64().and_then(|p| u16::try_from(p).ok()).filter(|p| *p > 0).ok_or("This listing's server port isn't valid.")?,
    };
    let name = partner["name"].as_str().and_then(rules::server_name).ok_or("This listing has no usable name.")?;
    let address = rules::address(&host, port);
    Ok(Target { join: Join { name, host, port, address }, versions: versions(&server["versions"]) })
}

fn target(state: &AppState, id: &str) -> CmdResult<Target> {
    let body = super::cached_body(state).ok_or(NOT_LISTED)?;
    target_in(&body, id, super::now(), rules::allow_loopback())
}

fn compatible(target: &Target, version: &str) -> CmdResult<()> {
    if rules::version_fits(version, target.versions.as_ref()) {
        Ok(())
    } else {
        Err(format!("{} doesn't take Minecraft {version}. Pick a profile on a version it lists.", target.join.name))
    }
}

/// Adds the server to the profile's list during its launch (game.rs calls this once the profile is ready, before Java
/// starts). A failure is reported and the game still joins the server.
pub fn list_server(state: &AppState, profile_id: &str, join: &Join) -> Listing {
    let mut out = Listing { address: join.address.clone(), ..Default::default() };
    if let Some(running) = crate::core::started_elsewhere(state, profile_id, &ProcessScan::take()) {
        out.problem = Some(running);
        return out;
    }
    match crate::gamesettings::serverlist::add_server(&state.paths.profile_dir(profile_id), &join.name, &join.address) {
        Ok(crate::gamesettings::serverlist::Listed::Added) => out.added = true,
        Ok(crate::gamesettings::serverlist::Listed::AlreadyListed { ip }) => {
            out.already_listed = true;
            // Quick Play finds the entry by its own spelling of the address.
            out.address = ip;
        }
        Err(problem) => out.problem = Some(problem),
    }
    out
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Preview {
    pub address: String,
    /// The profile's list already has it: nothing would be added.
    pub listed: bool,
    /// Why the server list can't be read (it would be left as it is).
    pub list_problem: Option<String>,
    /// Why this profile can't play it now (its game runs, an install is going on).
    pub blocked: Option<String>,
    /// Other profiles whose games are running.
    pub others_running: Vec<String>,
}

/// What Play would do for this listing and profile, for the confirmation. Changes nothing.
#[tauri::command]
pub async fn partners_play_preview(state: State<'_, AppState>, partner_id: String, profile_id: String) -> CmdResult<Preview> {
    let target = target(&state, &partner_id)?;
    let profile = crate::data::find_profile(&state, &profile_id)?;
    compatible(&target, &profile.game_version)?;
    let dir = state.paths.profile_dir(&profile_id);
    let (listed, list_problem) = match crate::gamesettings::serverlist::listed(&dir, &target.join.address) {
        Ok(found) => (found.is_some(), None),
        Err(problem) => (false, Some(problem)),
    };
    let running: Vec<String> = state.running.lock().unwrap().keys().cloned().collect();
    let blocked = if running.contains(&profile_id) {
        Some("Minecraft is already running in this profile. Close it first, or join the server from the game's Multiplayer screen.".to_owned())
    } else if state.install_cancel.lock().unwrap().contains_key(&profile_id) {
        Some("Flint is preparing this profile. Try again when it's done.".to_owned())
    } else {
        None
    };
    let profiles = crate::data::load_profiles(&state).profiles;
    let others_running = running.iter().filter(|id| **id != profile_id).filter_map(|id| profiles.iter().find(|p| &p.id == id).map(|p| p.name.clone())).collect();
    Ok(Preview { address: target.join.address, listed, list_problem, blocked, others_running })
}

/// Plays the listing's server in the profile the player confirmed: checks it all again, then launches, adding the server
/// to the profile's list and joining it. Returns once the game has started.
#[tauri::command]
pub async fn partners_play(app: AppHandle, state: State<'_, AppState>, partner_id: String, profile_id: String) -> CmdResult<Listing> {
    let target = target(&state, &partner_id)?;
    let profile = crate::data::find_profile(&state, &profile_id)?;
    compatible(&target, &profile.game_version)?;
    if state.running.lock().unwrap().contains_key(&profile_id) {
        return Err("Minecraft is already running in this profile. Close it first, or join the server from the game's Multiplayer screen.".into());
    }
    rules::resolves_publicly(&target.join.host, target.join.port, rules::allow_loopback()).await?;
    let listing = crate::game::launch_joining(&app, &state, &profile_id, &target.join).await?;
    Ok(listing)
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct ServerCheck {
    pub reachable: bool,
    pub ping_ms: Option<f64>,
    pub version: Option<String>,
}

/// The launcher's own look at a listing's server (a status ping from this computer), for the confirmation. It is
/// Flint's own measurement, never the backend's verified numbers.
#[tauri::command]
pub async fn partners_check_server(state: State<'_, AppState>, partner_id: String) -> CmdResult<ServerCheck> {
    let target = target(&state, &partner_id)?;
    rules::resolves_publicly(&target.join.host, target.join.port, rules::allow_loopback()).await?;
    Ok(match crate::netprobe::server_ping(rules::address(&target.join.host, target.join.port)).await {
        Ok(status) => ServerCheck {
            reachable: true,
            ping_ms: status["pingMs"].as_f64(),
            version: status["version"].as_str().map(|v| v.chars().filter(|c| !c.is_control() && *c != '§').take(40).collect()),
        },
        Err(_) => ServerCheck { reachable: false, ping_ms: None, version: None },
    })
}
