//! Flint AI: the gateway (a Cloudflare Worker) turns a player's request into goals; Flint itself
//! searches, verifies and installs real Modrinth projects. The gateway's answer is untrusted and is
//! validated here field by field before the interface sees it.

use crate::auth::{active_account, minecraft_token};
use crate::content::{installed_slugs, target_profile};
use crate::core::*;
use crate::data::gateway_url;
use futures_util::StreamExt;
use serde_json::{json, Value};
use std::time::{Duration, Instant};
use tauri::{AppHandle, Emitter, State};

const INTENTS_TIMEOUT: Duration = Duration::from_secs(25);
const HEALTH_TIMEOUT: Duration = Duration::from_secs(4);
const QUOTA_TIMEOUT: Duration = Duration::from_secs(10);
/// How long to wait before asking again about a request the gateway is still working on.
const BUSY_RETRY_DELAY: Duration = Duration::from_secs(3);
/// The longest quota cooldown Flint believes (the gateway's is 12 hours).
const MAX_RESET_MS: u64 = 48 * 3600 * 1000;
/// A valid answer is a few kilobytes; anything near this is not one.
const MAX_RESPONSE_BYTES: usize = 64 * 1024;
const MAX_GOALS: usize = 8;
const MAX_SEARCHES: usize = 4;
const MAX_PROJECTS: usize = 6;
const MAX_EXCLUDE: usize = 8;
const MAX_INSTALLED: usize = 40;
const CATEGORIES: [&str; 15] = [
    "performance", "lowEnd", "pvp", "hud", "inventory", "qol", "visual", "shaders", "minimap", "building", "replay", "survival", "exploration", "ui", "other",
];

/// A staging gateway's access key, from the environment at run time only (never compiled in and
/// never stored): `FLINT_AI_ACCESS_KEY`. Production gateways rely on Mojang-verified sessions.
fn access_key() -> Option<String> {
    std::env::var("FLINT_AI_ACCESS_KEY").ok().map(|k| k.trim().to_owned()).filter(|k| k.len() >= 32 && k.len() <= 256 && k.chars().all(|c| c.is_ascii_graphic()))
}

fn with_access(request: reqwest::RequestBuilder) -> reqwest::RequestBuilder {
    match access_key() {
        Some(key) => request.header("x-flint-access", key),
        None => request,
    }
}

/// QA builds only (debug, isolated data, FLINT_QA=1): the local test identity can't join a Minecraft
/// server, so with a staging access key it uses the staging gateway's direct mode as a synthetic test
/// player instead of a session. Release builds never have `state.qa`.
const QA_DIRECT: &str = "qa-direct:";

/// Adds the session (or, for the QA identity, the staging test player) to a gateway request.
fn authorize(request: reqwest::RequestBuilder, session: &str) -> reqwest::RequestBuilder {
    match session.strip_prefix(QA_DIRECT) {
        Some(player) => request.header("x-flint-test-player", player),
        None => request.bearer_auth(session),
    }
}

async fn gateway_session(app: &AppHandle, state: &AppState, gateway: &str) -> CmdResult<String> {
    if let Some(cached) = state.gateway_session.lock().unwrap().as_ref() {
        if cached.expires > Instant::now() {
            return Ok(cached.token.clone());
        }
    }
    let account = active_account(state).ok_or("AI_UNAVAILABLE: Connect a Minecraft account first.")?;
    if account.qa {
        if !state.qa {
            return Err("AI_UNAVAILABLE".into());
        }
        let player = account.uuid.replace('-', "").to_ascii_lowercase();
        if access_key().is_some() && player.len() == 32 && player.chars().all(|c| c.is_ascii_hexdigit()) {
            return Ok(format!("{QA_DIRECT}{player}"));
        }
        return Err("AI_UNAVAILABLE: the local test identity can't sign in to Flint AI".into());
    }
    let uuid = account.uuid.replace('-', "").to_ascii_lowercase();
    let token = minecraft_token(app, state, &account.uuid).await?;

    // Preferred: a signed certificate proof. Mojang's hasJoined check is refused from Cloudflare,
    // and the proof needs no call to Mojang at all; no token is sent to the gateway either way.
    if let Ok(cert) = crate::mcproof::certificate(state, &uuid, &token).await {
        let mut issued_at = crate::mcproof::now_millis();
        for attempt in 0..2 {
            let proof = cert.proof(&uuid, &random_hex(16), issued_at);
            let response = with_access(state.http.post(format!("{gateway}/v1/session")))
                .json(&json!({ "proof": proof }))
                .send()
                .await
                .map_err(|e| format!("AI_UNAVAILABLE: {}", friendly_network(&e)))?;
            match response.status().as_u16() {
                // A refused proof may only mean this computer's clock is off: retry once at the
                // gateway's own time (from its Date header).
                401 if attempt == 0 => {
                    let server_now = response.headers().get(reqwest::header::DATE).and_then(|d| d.to_str().ok()).and_then(crate::mcproof::http_date_millis);
                    match server_now {
                        Some(now) if (now - issued_at).abs() > 60_000 => issued_at = now,
                        _ => return Err("AI_UNAVAILABLE: Could not verify your Minecraft account.".into()),
                    }
                }
                // An older gateway that doesn't know proofs: use the hasJoined path below.
                400 => break,
                _ => return store_session(state, response).await,
            }
        }
    }

    // Fallback: Mojang's hasJoined check. The access token only goes to Mojang's own join
    // endpoint; the gateway checks the join with Mojang and never sees it.
    let server_id = random_hex(20);
    let joined = state
        .http
        .post("https://sessionserver.mojang.com/session/minecraft/join")
        .json(&json!({ "accessToken": token, "selectedProfile": account.uuid, "serverId": server_id }))
        .send()
        .await
        .map_err(|e| format!("AI_UNAVAILABLE: {}", friendly_network(&e)))?;
    if !joined.status().is_success() {
        return Err("AI_UNAVAILABLE: Could not verify your Minecraft session.".into());
    }
    let response = with_access(state.http.post(format!("{gateway}/v1/session")))
        .json(&json!({ "username": account.username, "serverId": server_id }))
        .send()
        .await
        .map_err(|e| format!("AI_UNAVAILABLE: {}", friendly_network(&e)))?;
    store_session(state, response).await
}

/// Reads a /v1/session answer and keeps the token for this run (until shortly before it expires).
async fn store_session(state: &AppState, response: reqwest::Response) -> CmdResult<String> {
    match response.status().as_u16() {
        429 => return Err("AI_RATE_LIMIT".into()),
        401 => return Err("AI_UNAVAILABLE: Could not verify your Minecraft account.".into()),
        403 => return Err(status_error(403)),
        _ => {}
    }
    let body = response_json(response, "AI_UNAVAILABLE").await?;
    let token = body["token"].as_str().filter(|t| !t.is_empty() && t.len() <= 4096).ok_or("AI_UNAVAILABLE: gateway returned no session")?.to_owned();
    let ttl = body["expiresIn"].as_u64().unwrap_or(900).clamp(60, 3600);
    *state.gateway_session.lock().unwrap() = Some(CachedToken {
        token: token.clone(),
        expires: Instant::now() + Duration::from_secs(ttl - 30),
    });
    Ok(token)
}

fn clean(text: &str, max: usize) -> String {
    text.chars().filter(|c| !c.is_control()).take(max).collect::<String>().trim().to_owned()
}

fn valid_slug(slug: &str) -> bool {
    (1..=64).contains(&slug.len())
        && slug.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || "-_.".contains(c))
        && slug.chars().any(|c| c.is_ascii_alphanumeric())
}

/// A Modrinth search phrase: letters, digits and a little punctuation, 2-40 characters.
fn search_phrase(text: &str) -> Option<String> {
    let kept: String = text.chars().map(|c| if c.is_alphanumeric() || " '&+-.".contains(c) { c } else { ' ' }).collect();
    let phrase = kept.split_whitespace().collect::<Vec<_>>().join(" ");
    let phrase: String = phrase.chars().take(40).collect::<String>().trim().to_owned();
    (phrase.chars().count() >= 2).then_some(phrase)
}

/// What the player remembers of a name ("L", "lumi"): letters, digits and spaces, at most 24 characters.
fn name_hint(text: &str) -> String {
    let kept: String = text.chars().map(|c| if c.is_alphanumeric() || c == ' ' { c } else { ' ' }).collect();
    kept.split_whitespace().collect::<Vec<_>>().join(" ").chars().take(24).collect::<String>().trim().to_owned()
}

fn strings(value: &Value) -> impl Iterator<Item = &str> {
    value.as_array().into_iter().flatten().take(32).filter_map(Value::as_str)
}

/// Checks an answer from the gateway against the contract. Unknown categories, unsafe slugs and
/// oversized text are dropped or clamped; an answer with no usable goal is rejected (`None`).
fn validate_intents(body: &Value) -> Option<Value> {
    if !body.is_object() {
        return None;
    }
    let mut goals: Vec<Value> = Vec::new();
    for goal in body["goals"].as_array()?.iter().take(16) {
        let Some(category) = goal["category"].as_str().filter(|c| CATEGORIES.contains(c)) else { continue };
        let mut searches: Vec<String> = Vec::new();
        for s in strings(&goal["searches"]).filter_map(search_phrase) {
            if searches.len() < MAX_SEARCHES && !searches.iter().any(|x| x.eq_ignore_ascii_case(&s)) {
                searches.push(s);
            }
        }
        let mut projects: Vec<String> = Vec::new();
        for p in strings(&goal["projects"]).map(|p| p.trim().to_ascii_lowercase()).filter(|p| valid_slug(p)) {
            if projects.len() < MAX_PROJECTS && !projects.contains(&p) {
                projects.push(p);
            }
        }
        if searches.is_empty() && projects.is_empty() {
            continue;
        }
        let label = clean(goal["label"].as_str().unwrap_or_default(), 40);
        let why = goal["why"].as_str().map(|w| clean(w, 140)).filter(|w| !w.is_empty());
        // Shader packs are their own kind, for shader and visual goals only; anything unknown is a mod.
        let kind = match goal["kind"].as_str() {
            Some("resourcepack") => "resourcepack",
            Some("shader") if matches!(category, "shaders" | "visual") => "shader",
            _ => "mod",
        };
        // A lookup is one project the player described but can't name; its searches are its features.
        let lookup = goal["lookup"].as_bool() == Some(true) && !searches.is_empty();
        let name_hint = goal["nameHint"].as_str().map(name_hint).filter(|h| lookup && !h.is_empty());
        goals.push(json!({ "category": category, "kind": kind, "label": label, "why": why, "searches": searches, "projects": projects, "lookup": lookup, "nameHint": name_hint }));
        if goals.len() == MAX_GOALS {
            break;
        }
    }
    if goals.is_empty() {
        return None;
    }
    let constraints = &body["constraints"];
    let ram = constraints["ramGb"].as_f64().filter(|r| r.is_finite() && (0.5..=1024.0).contains(r));
    let mut exclude: Vec<String> = Vec::new();
    for e in strings(&body["exclude"]).map(|e| clean(e, 40)).filter(|e| !e.is_empty()) {
        if exclude.len() < MAX_EXCLUDE && !exclude.contains(&e) {
            exclude.push(e);
        }
    }
    let note = body["note"].as_str().map(|n| clean(n, 240)).filter(|n| !n.is_empty());
    let model = body["model"]
        .as_str()
        .filter(|m| m.len() <= 80 && m.chars().all(|c| c.is_ascii_alphanumeric() || "@/._-:".contains(c)))
        .map(str::to_owned);
    Some(json!({
        "goals": goals,
        "constraints": {
            "lowEndHardware": constraints["lowEndHardware"].as_bool().unwrap_or(false),
            "ramGb": ram,
            "keepVanilla": constraints["keepVanilla"].as_bool().unwrap_or(false),
        },
        "exclude": exclude,
        "note": note,
        "model": model,
        "quota": validate_quota(&body["quota"]),
        "replayed": body["replayed"].as_bool().unwrap_or(false),
    }))
}

/// A whole, non-negative number (JSON integers, or floats without a fraction).
fn whole(value: &Value) -> Option<u64> {
    value.as_u64().or_else(|| value.as_f64().filter(|n| n.is_finite() && *n >= 0.0 && n.fract() == 0.0 && *n <= 9.0e15).map(|n| n as u64))
}

/// The gateway's quota for this player: `{limit, remaining, resetInMs}`, or `None` when any part
/// is out of range (limit 1-100, remaining at most the limit, a cooldown of at most 48 hours).
fn validate_quota(value: &Value) -> Option<Value> {
    if !value.is_object() {
        return None;
    }
    let limit = whole(&value["limit"]).filter(|l| (1..=100).contains(l))?;
    let remaining = whole(&value["remaining"]).filter(|r| *r <= limit)?;
    let reset = &value["resetInMs"];
    let reset_in_ms = if reset.is_null() { None } else { Some(whole(reset).filter(|r| *r <= MAX_RESET_MS)?) };
    Some(json!({ "limit": limit, "remaining": remaining, "resetInMs": reset_in_ms }))
}

/// Maps a gateway status to Flint's AI error codes.
fn status_error(code: u16) -> String {
    match code {
        429 => "AI_RATE_LIMIT".into(),
        502 => "AI_INVALID".into(),
        504 => "AI_TIMEOUT".into(),
        503 => "AI_UNAVAILABLE: the assistant is switched off right now".into(),
        409 => "AI_UNAVAILABLE: busy".into(),
        403 => "AI_UNAVAILABLE: this gateway is restricted".into(),
        code => format!("AI_UNAVAILABLE: gateway error {code}"),
    }
}

/// What to do with one gateway reply.
#[derive(Debug, PartialEq)]
enum Reply {
    Success,
    /// The session token was refused: drop it and sign in to the gateway again.
    RenewSession,
    /// The same request is still being worked on (409 in_progress).
    Busy,
    Fail(String),
}

/// Reads a gateway status together with its JSON body (`Value::Null` when it had none).
fn classify(status: u16, body: &Value) -> Reply {
    let error = body["error"].as_str().unwrap_or_default();
    match status {
        200 => Reply::Success,
        401 => Reply::RenewSession,
        409 => Reply::Busy,
        429 if error == "quota_exhausted" => {
            let quota = validate_quota(&body["quota"]).unwrap_or_else(|| json!({ "limit": 3, "remaining": 0, "resetInMs": null }));
            Reply::Fail(format!("AI_QUOTA:{quota}"))
        }
        503 if error == "capacity" => Reply::Fail("AI_CAPACITY".into()),
        code => Reply::Fail(status_error(code)),
    }
}

/// The configured gateway without a trailing slash.
fn configured_gateway(state: &AppState) -> CmdResult<String> {
    let gateway = gateway_url(state).ok_or("AI_NOT_CONFIGURED")?;
    Ok(gateway.trim_end_matches('/').to_owned())
}

/// The loader Flint tells the gateway about: only Fabric profiles can use mods.
fn gateway_loader(profile_loader: &str) -> &'static str {
    if profile_loader == "fabric" {
        "fabric"
    } else {
        "vanilla"
    }
}

/// A request id the gateway accepts: 8-64 characters of `[A-Za-z0-9_-]`.
fn valid_request_id(id: &str) -> bool {
    id.len() >= 8 && is_safe_id(id)
}

fn network_error(error: &reqwest::Error) -> String {
    if error.is_timeout() {
        "AI_TIMEOUT".into()
    } else {
        format!("AI_UNAVAILABLE: {}", friendly_network(error))
    }
}

async fn read_capped(response: reqwest::Response) -> CmdResult<Vec<u8>> {
    if response.content_length().is_some_and(|n| n > MAX_RESPONSE_BYTES as u64) {
        return Err("AI_INVALID".into());
    }
    let mut body = Vec::new();
    let mut stream = response.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| network_error(&e))?;
        if body.len() + chunk.len() > MAX_RESPONSE_BYTES {
            return Err("AI_INVALID".into());
        }
        body.extend_from_slice(&chunk);
    }
    Ok(body)
}

/// The status and JSON body of a gateway reply. A body that isn't JSON reads as `Value::Null`;
/// `Err` only when the body could not be received (or was far too large).
async fn read_reply(response: reqwest::Response) -> (u16, CmdResult<Value>) {
    let status = response.status().as_u16();
    let body = read_capped(response).await.map(|bytes| serde_json::from_slice(&bytes).unwrap_or(Value::Null));
    (status, body)
}

/// Settings → General → Flint Assist. When it's off, no AI request (or quota check) leaves the app,
/// even if a screen still asks.
fn assist_enabled(state: &AppState) -> CmdResult<()> {
    if crate::data::load_settings(state)["flintAssist"].as_bool().unwrap_or(true) {
        Ok(())
    } else {
        Err("Flint Assist is turned off. Turn it on in Settings → General to use Flint AI.".into())
    }
}

#[tauri::command]
pub async fn ai_suggest(
    app: AppHandle,
    state: State<'_, AppState>,
    request_id: String,
    profile_id: Option<String>,
    game_version: Option<String>,
    prompt: String,
) -> CmdResult<Value> {
    if !valid_request_id(&request_id) {
        return Err("Invalid request".into());
    }
    assist_enabled(&state)?;
    // The gateway counts UTF-16 units (JavaScript string length), so emoji count twice.
    let mut prompt = clean(&prompt, 600);
    while prompt.encode_utf16().count() > 600 {
        prompt.pop();
    }
    if prompt.chars().count() < 2 {
        return Err("Describe what you'd like to add.".into());
    }
    let gateway = configured_gateway(&state)?;
    let profile = target_profile(&state, profile_id.as_deref(), game_version.as_deref())?;
    // Vanilla profiles can still use resource packs; the gateway only suggests those for them.
    let loader = gateway_loader(&profile.loader);
    let installed: Vec<String> = profile_id
        .as_deref()
        .map(|id| installed_slugs(&state, id))
        .unwrap_or_default()
        .into_iter()
        .map(|s| s.to_ascii_lowercase())
        .filter(|s| valid_slug(s))
        .take(MAX_INSTALLED)
        .collect();

    let (cancel_tx, mut cancel_rx) = tokio::sync::oneshot::channel();
    state.ai_cancel.lock().unwrap().insert(request_id.clone(), cancel_tx);
    let emit = |kind: &str| {
        let _ = app.emit("ai-event", json!({ "requestId": request_id, "type": kind }));
    };

    let request = json!({
        "requestId": request_id,
        "prompt": prompt,
        "gameVersion": profile.game_version,
        "loader": loader,
        "installed": installed,
    });
    let work = async {
        // Each recovery happens at most once, so this loop ends after four attempts:
        // - a session the gateway no longer accepts is dropped and renewed;
        // - a request lost on the network is sent again with the same requestId (the gateway
        //   answers a finished one from its record and never charges twice);
        // - a request the gateway is still working on is asked about again after a short wait.
        let (mut renewed, mut resent, mut waited) = (false, false, false);
        loop {
            let session = gateway_session(&app, &state, &gateway).await?;
            emit("connected");
            let sent = authorize(with_access(state.http.post(format!("{gateway}/v1/intents"))), &session).timeout(INTENTS_TIMEOUT).json(&request).send().await;
            let response = match sent {
                Ok(response) => response,
                Err(_) if !resent => {
                    resent = true;
                    continue;
                }
                Err(e) => return Err(network_error(&e)),
            };
            let (status, body) = read_reply(response).await;
            let body = match body {
                Ok(body) => body,
                Err(e) if status == 200 && e != "AI_INVALID" && !resent => {
                    resent = true;
                    continue;
                }
                Err(e) if status == 200 => return Err(e),
                Err(_) => Value::Null,
            };
            match classify(status, &body) {
                Reply::Success => return validate_intents(&body).ok_or_else(|| "AI_INVALID".to_string()),
                Reply::RenewSession => {
                    state.gateway_session.lock().unwrap().take();
                    if renewed {
                        return Err("AI_UNAVAILABLE: session expired".to_string());
                    }
                    renewed = true;
                }
                Reply::Busy => {
                    if waited {
                        return Err(status_error(409));
                    }
                    waited = true;
                    tokio::time::sleep(BUSY_RETRY_DELAY).await;
                }
                Reply::Fail(error) => return Err(error),
            }
        }
    };

    let result = tokio::select! {
        result = work => result,
        _ = &mut cancel_rx => Err("AI_CANCELLED".to_string()),
    };
    state.ai_cancel.lock().unwrap().remove(&request_id);
    result
}

#[tauri::command]
pub fn ai_cancel(state: State<'_, AppState>, request_id: String) {
    if let Some(cancel) = state.ai_cancel.lock().unwrap().remove(&request_id) {
        let _ = cancel.send(());
    }
}

/// Whether Flint AI is set up and its gateway answers. Only the public health check is asked:
/// no session, no Minecraft account and no Keychain access.
#[tauri::command]
pub async fn ai_status(state: State<'_, AppState>) -> CmdResult<Value> {
    let Ok(gateway) = configured_gateway(&state) else {
        return Ok(json!({ "configured": false, "reachable": false }));
    };
    let reachable = state.http.get(format!("{gateway}/v1/health")).timeout(HEALTH_TIMEOUT).send().await.is_ok_and(|r| r.status().is_success());
    Ok(json!({ "configured": true, "reachable": reachable }))
}

/// The player's remaining Flint AI searches, `{limit, remaining, resetInMs}`. This signs in to the
/// gateway (and so may read the Minecraft account), so it runs only when the player opens or uses
/// Flint AI. Errors use the same codes as `ai_suggest`.
#[tauri::command]
pub async fn ai_quota(app: AppHandle, state: State<'_, AppState>) -> CmdResult<Value> {
    assist_enabled(&state)?;
    let gateway = configured_gateway(&state)?;
    let mut renewed = false;
    loop {
        let session = gateway_session(&app, &state, &gateway).await?;
        let response = authorize(with_access(state.http.get(format!("{gateway}/v1/quota"))), &session)
            .timeout(QUOTA_TIMEOUT)
            .send()
            .await
            .map_err(|e| network_error(&e))?;
        let (status, body) = read_reply(response).await;
        let body = match body {
            Ok(body) => body,
            Err(e) if status == 200 => return Err(e),
            Err(_) => Value::Null,
        };
        match classify(status, &body) {
            Reply::Success => return validate_quota(&body).ok_or_else(|| "AI_INVALID".to_string()),
            Reply::RenewSession => {
                state.gateway_session.lock().unwrap().take();
                if renewed {
                    return Err("AI_UNAVAILABLE: session expired".to_string());
                }
                renewed = true;
            }
            Reply::Busy => return Err(status_error(409)),
            Reply::Fail(error) => return Err(error),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn flint_assist_off_blocks_every_ai_request() {
        let dir = TestDir::new();
        let state = AppState::for_tests(dir.0.clone());
        assert!(assist_enabled(&state).is_ok(), "on by default (no settings file)");
        write_json(&state.paths.settings(), &json!({ "flintAssist": false })).unwrap();
        assert!(assist_enabled(&state).unwrap_err().contains("Settings → General"));
        write_json(&state.paths.settings(), &json!({ "flintAssist": true })).unwrap();
        assert!(assist_enabled(&state).is_ok());
    }

    fn answer(goals: Value) -> Value {
        json!({
            "source": "ai",
            "model": "@cf/openai/gpt-oss-20b",
            "goals": goals,
            "constraints": { "lowEndHardware": false, "ramGb": null, "keepVanilla": false },
            "exclude": [],
            "note": null,
        })
    }

    #[test]
    fn a_valid_answer_keeps_its_goals() {
        let body = answer(json!([
            { "category": "performance", "label": "Performance essentials", "searches": ["fps boost"], "projects": ["sodium", "lithium"] },
            { "category": "pvp", "label": "PvP & HUD", "searches": ["toggle sprint", "armor hud"], "projects": ["zoomify"] },
        ]));
        let v = validate_intents(&body).expect("valid");
        assert_eq!(v["goals"].as_array().unwrap().len(), 2);
        assert_eq!(v["goals"][0]["projects"], json!(["sodium", "lithium"]));
        assert_eq!(v["goals"][1]["label"], "PvP & HUD");
        assert_eq!(v["model"], "@cf/openai/gpt-oss-20b");
    }

    #[test]
    fn unknown_categories_and_bad_slugs_are_dropped() {
        let body = answer(json!([
            { "category": "hacks", "label": "Cheats", "searches": ["x"], "projects": ["wurst"] },
            { "category": "minimap", "label": "Maps", "searches": [], "projects": ["../../etc/passwd", "Xaeros-Minimap", "..", "a b", "", 42, "journeymap"] },
            { "category": "shaders", "label": "Empty", "searches": [], "projects": [".."] },
        ]));
        let v = validate_intents(&body).expect("one goal survives");
        let goals = v["goals"].as_array().unwrap();
        assert_eq!(goals.len(), 1, "unknown category and goal without usable entries are dropped");
        assert_eq!(goals[0]["category"], "minimap");
        assert_eq!(goals[0]["projects"], json!(["xaeros-minimap", "journeymap"]));
    }

    #[test]
    fn oversized_and_malicious_text_is_clamped() {
        let long = "a".repeat(5000);
        let body = json!({
            "goals": (0..20).map(|i| json!({
                "category": "qol",
                "label": format!("{long}\u{0007}{i}"),
                "searches": ["<script>alert(1)</script>", long.clone(), "ok", "one", "two", "three", "four"],
                "projects": (0..20).map(|n| format!("mod-{n}")).collect::<Vec<_>>(),
            })).collect::<Vec<_>>(),
            "constraints": { "lowEndHardware": "yes", "ramGb": 1e9, "keepVanilla": true },
            "exclude": (0..20).map(|n| format!("{n}{long}")).collect::<Vec<_>>(),
            "note": format!("{long}\u{0000}"),
            "model": "evil\nheader",
        });
        let v = validate_intents(&body).expect("clamped, not rejected");
        let goals = v["goals"].as_array().unwrap();
        assert_eq!(goals.len(), MAX_GOALS);
        let g = &goals[0];
        assert!(g["label"].as_str().unwrap().chars().count() <= 40);
        let searches: Vec<&str> = g["searches"].as_array().unwrap().iter().map(|s| s.as_str().unwrap()).collect();
        assert_eq!(searches.len(), MAX_SEARCHES);
        assert!(searches.iter().all(|s| s.chars().count() <= 40 && !s.contains('<') && !s.contains('>')));
        assert_eq!(searches, vec!["script alert 1 script", &"a".repeat(40), "ok", "one"], "markup characters become spaces");
        assert_eq!(g["projects"].as_array().unwrap().len(), MAX_PROJECTS);
        assert_eq!(v["constraints"]["lowEndHardware"], false, "non-booleans are ignored");
        assert_eq!(v["constraints"]["ramGb"], Value::Null, "absurd memory sizes are ignored");
        assert_eq!(v["constraints"]["keepVanilla"], true);
        assert_eq!(v["exclude"].as_array().unwrap().len(), MAX_EXCLUDE);
        assert!(v["exclude"].as_array().unwrap().iter().all(|e| e.as_str().unwrap().chars().count() <= 40));
        assert!(v["note"].as_str().unwrap().chars().count() <= 240);
        assert_eq!(v["model"], Value::Null);
    }

    #[test]
    fn answers_without_usable_goals_are_rejected() {
        assert!(validate_intents(&json!("I can't help with that")).is_none());
        assert!(validate_intents(&json!({ "goals": [] })).is_none());
        assert!(validate_intents(&json!({ "goals": "performance" })).is_none());
        assert!(validate_intents(&json!({ "mods": [{ "name": "Sodium" }] })).is_none());
        assert!(validate_intents(&answer(json!([{ "category": "other", "label": "x", "searches": ["a"], "projects": [] }]))).is_none());
    }

    #[test]
    fn gateway_statuses_map_to_flint_codes() {
        assert_eq!(status_error(429), "AI_RATE_LIMIT");
        assert_eq!(status_error(502), "AI_INVALID");
        assert_eq!(status_error(504), "AI_TIMEOUT");
        assert!(status_error(503).starts_with("AI_UNAVAILABLE"));
        assert!(status_error(400).starts_with("AI_UNAVAILABLE"));
        assert_eq!(status_error(409), "AI_UNAVAILABLE: busy");
    }

    #[test]
    fn gateway_replies_are_classified_by_status_and_error() {
        assert_eq!(classify(200, &json!({ "goals": [] })), Reply::Success);
        assert_eq!(classify(401, &Value::Null), Reply::RenewSession);
        assert_eq!(classify(409, &json!({ "error": "in_progress" })), Reply::Busy);
        let quota = json!({ "error": "quota_exhausted", "quota": { "limit": 3, "remaining": 0, "resetInMs": 3_600_000, "serverTime": 1 } });
        assert_eq!(classify(429, &quota), Reply::Fail(r#"AI_QUOTA:{"limit":3,"remaining":0,"resetInMs":3600000}"#.into()));
        // A quota the client can't trust still reads as exhausted, without a countdown.
        let bad = json!({ "error": "quota_exhausted", "quota": { "limit": 3, "remaining": 9 } });
        assert_eq!(classify(429, &bad), Reply::Fail(r#"AI_QUOTA:{"limit":3,"remaining":0,"resetInMs":null}"#.into()));
        assert_eq!(classify(429, &json!({ "error": "rate_limited" })), Reply::Fail("AI_RATE_LIMIT".into()));
        assert_eq!(classify(429, &Value::Null), Reply::Fail("AI_RATE_LIMIT".into()));
        assert_eq!(classify(503, &json!({ "error": "capacity" })), Reply::Fail("AI_CAPACITY".into()));
        assert!(matches!(classify(503, &json!({ "error": "ai_unavailable" })), Reply::Fail(e) if e.starts_with("AI_UNAVAILABLE")));
        assert_eq!(classify(502, &json!({ "error": "ai_invalid" })), Reply::Fail("AI_INVALID".into()));
        assert_eq!(classify(504, &json!({ "error": "ai_timeout" })), Reply::Fail("AI_TIMEOUT".into()));
        assert_eq!(classify(403, &json!({ "error": "access_denied" })), Reply::Fail("AI_UNAVAILABLE: this gateway is restricted".into()));
    }

    #[test]
    fn quotas_are_range_checked() {
        let q = |v: Value| validate_quota(&v);
        assert_eq!(q(json!({ "limit": 3, "remaining": 2, "resetInMs": null, "serverTime": 5 })), Some(json!({ "limit": 3, "remaining": 2, "resetInMs": null })));
        assert_eq!(q(json!({ "limit": 3, "remaining": 0, "resetInMs": 43_200_000.0 })), Some(json!({ "limit": 3, "remaining": 0, "resetInMs": 43_200_000u64 })));
        assert_eq!(q(json!({ "limit": 3, "remaining": 3 }))
            .unwrap()["resetInMs"], Value::Null, "a missing cooldown is no cooldown");
        assert_eq!(q(json!({ "limit": 100, "remaining": 0, "resetInMs": MAX_RESET_MS })).unwrap()["limit"], 100);
        for bad in [
            json!(null),
            json!("3/3"),
            json!({ "limit": 0, "remaining": 0 }),
            json!({ "limit": 101, "remaining": 1 }),
            json!({ "limit": 3, "remaining": 4 }),
            json!({ "limit": 3, "remaining": -1 }),
            json!({ "limit": 3.5, "remaining": 1 }),
            json!({ "limit": "3", "remaining": 1 }),
            json!({ "limit": 3, "remaining": 1, "resetInMs": MAX_RESET_MS + 1 }),
            json!({ "limit": 3, "remaining": 1, "resetInMs": -5 }),
            json!({ "limit": 3, "remaining": 1, "resetInMs": "soon" }),
        ] {
            assert_eq!(q(bad.clone()), None, "{bad}");
        }
    }

    #[test]
    fn goals_carry_a_kind_and_answers_carry_quota_and_replay() {
        let mut body = answer(json!([
            { "category": "visual", "kind": "resourcepack", "label": "Packs", "searches": ["faithful"], "projects": ["faithful-32x"] },
            { "category": "performance", "label": "Speed", "searches": [], "projects": ["sodium"] },
            { "category": "hud", "kind": "shader", "label": "Odd", "searches": ["hud"], "projects": [] },
            { "category": "hud", "kind": 7, "label": "Odder", "searches": ["armor hud"], "projects": [] },
        ]));
        body["quota"] = json!({ "limit": 3, "remaining": 1, "resetInMs": null, "serverTime": 1_791_849_600_000u64 });
        body["replayed"] = json!(true);
        let v = validate_intents(&body).unwrap();
        let kinds: Vec<&str> = v["goals"].as_array().unwrap().iter().map(|g| g["kind"].as_str().unwrap()).collect();
        assert_eq!(kinds, ["resourcepack", "mod", "mod", "mod"], "kind defaults to mod; a shader kind outside shader goals becomes mod");
        assert_eq!(v["quota"], json!({ "limit": 3, "remaining": 1, "resetInMs": null }));
        assert_eq!(v["replayed"], true);

        // Without (or with a broken) quota the answer still stands.
        let mut body = answer(json!([{ "category": "qol", "label": "x", "searches": ["zoom"], "projects": [] }]));
        body["quota"] = json!({ "limit": 3, "remaining": 7 });
        body["replayed"] = json!("yes");
        let v = validate_intents(&body).unwrap();
        assert_eq!(v["quota"], Value::Null);
        assert_eq!(v["replayed"], false);
    }

    #[test]
    fn shader_goals_and_lookups_pass_validation() {
        let v = validate_intents(&answer(json!([
            { "category": "shaders", "kind": "shader", "label": "Shader packs", "searches": [], "projects": ["complementary-reimagined"] },
            { "category": "visual", "kind": "shader", "label": "Looks", "searches": ["vanilla shaders"], "projects": [] },
            { "category": "other", "kind": "mod", "label": "Possible match", "lookup": true, "nameHint": "L<script>", "searches": ["low fire", "low shield", "no pumpkin blur", "no fog"], "projects": [] },
            { "category": "pvp", "label": "PvP", "lookup": "yes", "nameHint": "Z", "searches": ["zoom"], "projects": [] },
        ])))
        .unwrap();
        let goals = v["goals"].as_array().unwrap();
        let kinds: Vec<&str> = goals.iter().map(|g| g["kind"].as_str().unwrap()).collect();
        assert_eq!(kinds, ["shader", "shader", "mod", "mod"]);
        assert_eq!(goals[2]["lookup"], true);
        assert_eq!(goals[2]["nameHint"], "L script");
        assert_eq!(goals[2]["searches"].as_array().unwrap().len(), 4);
        assert_eq!(goals[3]["lookup"], false, "only a real true is a lookup");
        assert_eq!(goals[3]["nameHint"], Value::Null, "a name hint belongs to a lookup");
    }

    #[test]
    fn request_ids_and_loaders_follow_the_contract() {
        assert!(valid_request_id("amgp1x2y3"));
        assert!(valid_request_id(&"a".repeat(64)));
        assert!(!valid_request_id("short"));
        assert!(!valid_request_id(&"a".repeat(65)));
        assert!(!valid_request_id("has space1"));
        assert!(!valid_request_id("dots.are.out"));
        assert_eq!(gateway_loader("fabric"), "fabric");
        assert_eq!(gateway_loader("vanilla"), "vanilla");
        assert_eq!(gateway_loader(""), "vanilla");
    }

    #[test]
    fn exploration_and_interface_goals_keep_a_short_why() {
        let v = validate_intents(&answer(json!([
            { "category": "exploration", "label": "Exploration", "why": "Find biomes faster.\u{0007}", "searches": ["structure finder"], "projects": ["natures-compass"] },
            { "category": "ui", "label": "Interface", "why": "y".repeat(500), "searches": [], "projects": ["modmenu"] },
            { "category": "pvp", "label": "PvP", "searches": ["zoom"], "projects": [] },
        ])))
        .unwrap();
        let goals = v["goals"].as_array().unwrap();
        assert_eq!(goals.iter().map(|g| g["category"].as_str().unwrap()).collect::<Vec<_>>(), ["exploration", "ui", "pvp"]);
        assert_eq!(goals[0]["why"], "Find biomes faster.");
        assert_eq!(goals[1]["why"].as_str().unwrap().chars().count(), 140);
        assert!(goals[2]["why"].is_null());
    }
}
