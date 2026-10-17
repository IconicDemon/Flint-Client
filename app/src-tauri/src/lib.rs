mod ai;
mod auth;
mod content;
mod core;
mod data;
mod discord;
mod diagnostics;
mod game;
mod gamesettings;
mod jobs;
mod mcproof;
mod modpack;
mod modrinth;
mod netprobe;
mod perfcore;
mod runtime;
#[cfg(debug_assertions)]
mod selftest;
mod skins;
mod startup;

use crate::core::{build_clients, AppState, Paths};
use std::collections::HashMap;
use std::sync::Mutex;
use tauri::{Emitter, Manager};
use tauri_plugin_autostart::MacosLauncher;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        // Signed self-updates from Flint's release channel (verified against the public key in tauri.conf.json).
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_process::init())
        .plugin(tauri_plugin_autostart::init(MacosLauncher::LaunchAgent, None))
        .register_uri_scheme_protocol(startup::SCHEME, |ctx, request| {
            let dev_url = ctx.app_handle().config().build.dev_url.as_ref().map(|u| u.to_string()).unwrap_or_default();
            match startup::diagnostic_page(request.uri().path(), &dev_url) {
                Some(page) => tauri::http::Response::builder()
                    .header("Content-Type", "text/html; charset=utf-8")
                    .body(page.into_bytes())
                    .expect("static response is valid"),
                None => tauri::http::Response::builder().status(404).body(Vec::new()).expect("static response is valid"),
            }
        })
        .setup(|app| {
            let root = data::app_data_root(app.handle());
            std::fs::create_dir_all(&root)?;
            jobs::clean_partials(&root);
            gamesettings::clean_interrupted_writes(&root);
            let isolated = std::env::var_os("FLINT_DATA_DIR").is_some();
            // QA tooling (test identity, staging data) exists only in debug and measurement builds.
            let qa = (cfg!(debug_assertions) || cfg!(feature = "perf")) && isolated && std::env::var("FLINT_QA").is_ok_and(|v| v == "1");
            let force_onboarding = (cfg!(debug_assertions) || isolated)
                && (std::env::args().any(|a| a == "--onboarding") || std::env::var("FLINT_ONBOARDING").is_ok_and(|v| v == "1"));
            let (http, stream_http) = build_clients();
            app.manage(AppState {
                http,
                stream_http,
                paths: Paths { root },
                qa,
                force_onboarding,
                // Isolated QA data and debug builds get their own items, so a rebuilt development
                // binary never changes who may read the installed app's saved sign-in.
                keyring_service: if isolated {
                    "com.flintclient.desktop.test".into()
                } else if cfg!(debug_assertions) {
                    "com.flintclient.desktop.dev".into()
                } else {
                    "com.flintclient.desktop".into()
                },
                file_lock: tokio::sync::Mutex::new(()),
                sign_in_cancel: Mutex::new(None),
                minecraft_tokens: Mutex::new(HashMap::new()),
                token_refresh: Default::default(),
                credentials: Box::new(auth::SystemKeychain),
                refresh_tokens: Mutex::new(HashMap::new()),
                keychain_blocked: Mutex::new(Default::default()),
                gateway_session: Mutex::new(None),
                profile_states: Mutex::new(HashMap::new()),
                install_cancel: Mutex::new(HashMap::new()),
                running: Mutex::new(HashMap::new()),
                ai_cancel: Mutex::new(HashMap::new()),
                modrinth: modrinth::Modrinth::new(qa || cfg!(debug_assertions)),
                jobs: jobs::Jobs::default(),
            });
            // Discord Rich Presence: dormant until a game runs (and the setting is on).
            let (settings, root) = {
                let state = app.state::<AppState>();
                (data::load_settings(&state), state.paths.root.clone())
            };
            let emitter = app.handle().clone();
            let presence = discord::Presence::start(&root, &settings, move |status| {
                let _ = emitter.emit("discord-status", status);
            });
            app.manage(presence);
            let selftest = cfg!(debug_assertions) && isolated && std::env::var_os("FLINT_SELFTEST").is_some();
            startup::prepare_window(app.handle(), selftest);
            #[cfg(debug_assertions)]
            if let (Ok(spec), true) = (std::env::var("FLINT_SELFTEST"), isolated) {
                selftest::run(app.handle().clone(), spec);
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            auth::auth_sign_in,
            auth::auth_cancel,
            auth::auth_qa_account,
            auth::accounts_list,
            auth::accounts_set_active,
            auth::accounts_refresh,
            auth::accounts_remove,
            discord::discord_status,
            data::settings_get,
            data::settings_set,
            data::onboarding_get,
            data::onboarding_set,
            data::app_info,
            data::perf_write,
            game::perf_emit_load,
            data::open_external,
            data::reveal_folder,
            data::launch_at_login_get,
            data::launch_at_login_set,
            data::storage_usage,
            data::cache_clear,
            data::profiles_list,
            data::profiles_create,
            data::profiles_rename,
            data::profiles_set_memory,
            data::profiles_select,
            data::profiles_inspect,
            data::profiles_duplicate,
            data::profiles_delete,
            data::system_info,
            data::java_validate,
            data::version_catalog,
            data::skin_texture,
            data::news_feed,
            data::session_summary,
            game::profile_check,
            game::profile_install,
            game::profile_cancel,
            game::profile_states,
            game::profile_launch,
            game::profile_stop,
            modrinth::library_search,
            modrinth::library_project,
            modrinth::library_versions,
            modrinth::library_tags,
            modrinth::library_status,
            jobs::jobs_list,
            jobs::job_cancel,
            jobs::job_dismiss,
            content::content_plan,
            content::mods_resolve,
            content::mods_install,
            content::content_install,
            content::content_list,
            content::content_identify,
            content::content_set_enabled,
            content::content_remove,
            content::content_rollback,
            content::content_updates,
            content::content_update,
            content::profile_worlds,
            content::profile_files,
            content::profile_logs,
            content::profile_log_read,
            modpack::modpack_install,
            skins::skin_profile,
            skins::skin_upload,
            skins::skin_reset,
            skins::cape_set,
            ai::ai_suggest,
            ai::ai_cancel,
            ai::ai_status,
            ai::ai_quota,
            netprobe::server_ping,
            gamesettings::autosync::auto_sync_set_enabled,
            gamesettings::autosync::auto_sync_status,
            gamesettings::autosync::auto_sync_dismiss,
        ])
        .build(tauri::generate_context!())
        .expect("Flint Client failed to start")
        .run(|app, event| {
            // Games keep running after Flint quits; a headless keeper keeps their Discord presence.
            if let tauri::RunEvent::Exit = event {
                if let Some(presence) = app.try_state::<discord::Presence>() {
                    presence.hand_off();
                }
            }
        });
}

/// `flint-app --flint-discord-keeper …`: runs the Rich Presence keeper instead of the app.
pub fn run_discord_keeper() -> bool {
    discord::run_keeper_if_requested()
}
