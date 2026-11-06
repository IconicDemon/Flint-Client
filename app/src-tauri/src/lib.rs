mod ai;
mod auth;
mod channel;
mod content;
mod core;
mod data;
mod datadir;
mod discord;
mod diagnostics;
mod flintpack;
mod game;
mod gamelink;
mod gamewindow;
mod gamesettings;
mod import;
mod jobs;
mod javaplan;
mod jvmcache;
mod mcproof;
mod media;
mod modarchives;
mod modicons;
mod modpack;
mod modrinth;
mod mojangjoin;
mod netprobe;
mod partners;
mod perfcore;
mod profilefolder;
mod profileicon;
mod redact;
mod runtime;
#[cfg(debug_assertions)]
mod selftest;
mod skins;
mod social;
mod startup;
mod storage;
mod store;
mod timing;
mod troubleshoot;
mod windowfit;

use crate::core::{build_clients, AppState, Paths};
use std::collections::HashMap;
use std::sync::Mutex;
use tauri::{Emitter, Manager};
use tauri_plugin_autostart::MacosLauncher;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let isolated = std::env::var_os("FLINT_DATA_DIR").is_some();
    let mut context = tauri::generate_context!();
    // Flint's data and the window's web data, in one folder named "Flint Client" (moved there from the folder older
    // versions used, when nothing is using it: datadir.rs). Before the builder: the window's web data folder is chosen
    // as the window is made. Isolated runs keep their web data with the rest of their data, away from the app's.
    let dirs = match std::env::var_os("FLINT_DATA_DIR").map(std::path::PathBuf::from) {
        Some(dir) if dir.is_absolute() => Some(datadir::DataDirs { data: dir.clone(), local: dir }),
        _ => datadir::settle(),
    };
    if let Some(dirs) = dirs {
        context.config_mut().app.app_directories_override =
            Some(tauri::utils::config::AppDirectoriesOverride::Directories(tauri::utils::config::AppDirectoryOverrides {
                data: Some(dirs.data),
                local_data: Some(dirs.local),
                ..Default::default()
            }));
    }
    let builder = tauri::Builder::default();
    // One Flint at a time: opening it again (a double-clicked .flintpack, the Start menu) brings the
    // running window forward and hands it the file. Development and isolated QA runs stay separate so
    // they can run beside the installed app.
    let builder = if cfg!(debug_assertions) || isolated {
        builder
    } else {
        builder.plugin(tauri_plugin_single_instance::init(|app, args, _cwd| {
            if let Some(path) = flintpack::pack_in_args(args) {
                flintpack::open(app, path);
            }
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.unminimize();
                let _ = window.show();
                let _ = window.set_focus();
            }
        }))
    };
    builder
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
        // A mod's own logo, read from its jar (modicons.rs), off the main thread.
        .register_asynchronous_uri_scheme_protocol(modicons::SCHEME, |ctx, request, responder| {
            let app = ctx.app_handle().clone();
            let path = request.uri().path().to_owned();
            std::thread::spawn(move || {
                let state = app.state::<AppState>();
                let response = match modicons::requested_jar(&state, &path).and_then(|jar| modicons::jar_icon(&jar)) {
                    Some(png) => tauri::http::Response::builder().header("Content-Type", "image/png").header("Cache-Control", "max-age=3600").body(png),
                    None => tauri::http::Response::builder().status(404).body(Vec::new()),
                };
                responder.respond(response.expect("static response is valid"));
            });
        })
        // Partner pictures (partners/media.rs): only checksums the current feed lists, fetched and checked by Flint.
        .register_asynchronous_uri_scheme_protocol(partners::media::SCHEME, |ctx, request, responder| {
            let app = ctx.app_handle().clone();
            let path = request.uri().path().to_owned();
            tauri::async_runtime::spawn(async move {
                let state = app.state::<AppState>();
                let response = match partners::media::serve(&state, &path).await {
                    Some((bytes, kind)) => tauri::http::Response::builder().header("Content-Type", kind).header("Cache-Control", "max-age=86400, immutable").body(bytes),
                    None => tauri::http::Response::builder().status(404).body(Vec::new()),
                };
                responder.respond(response.expect("static response is valid"));
            });
        })
        .setup(move |app| {
            let root = data::app_data_root(app.handle());
            std::fs::create_dir_all(&root)?;
            jobs::clean_partials(&root);
            gamesettings::clean_interrupted_writes(&root);
            // Started by opening a .flintpack: the window picks it up once it is ready.
            app.manage(flintpack::Opened::default());
            if let Some(path) = flintpack::pack_in_args(std::env::args()) {
                flintpack::open(app.handle(), path);
            }
            // QA tooling (test identity, staging data) exists only in debug and measurement builds.
            let qa = (cfg!(debug_assertions) || cfg!(feature = "perf")) && isolated && std::env::var("FLINT_QA").is_ok_and(|v| v == "1");
            let force_onboarding = (cfg!(debug_assertions) || isolated)
                && (std::env::args().any(|a| a == "--onboarding") || std::env::var("FLINT_ONBOARDING").is_ok_and(|v| v == "1"));
            let (http, stream_http) = build_clients();
            // Friends (social.rs): Flint's Supabase project as the build names it; development builds may name another.
            let social = social::Social::new(http.clone(), social::Endpoints::from_build(cfg!(debug_assertions) || isolated));
            // Settings → Storage → "Installs at once", read now (it applies from the next start).
            let install_slots = storage::install_slots(&core::read_json_opt(&root.join("settings.json")).unwrap_or_default());
            app.manage(AppState {
                http,
                stream_http,
                paths: Paths::new(root),
                qa,
                force_onboarding,
                // Isolated QA data and debug builds get their own items, so a rebuilt development
                // binary never changes who may read the installed app's saved sign-in.
                keyring_service: if isolated {
                    "com.flintclient.desktop.test".into()
                } else if cfg!(debug_assertions) {
                    "com.flintclient.desktop.dev".into()
                } else {
                    crate::channel::KEYRING_SERVICE.into()
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
                jobs: jobs::Jobs::with_slots(install_slots),
                game_link: Default::default(),
                // The Flint Store; development and isolated builds may point at another one (FLINT_STORE_URL).
                store: store::StoreState::new(store::Endpoints::from_env(cfg!(debug_assertions) || isolated)),
                social,
            });
            // Profile folders take their profiles' names ("Daemon-26.2") before anything opens one.
            profilefolder::settle_all(&app.state::<AppState>());
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
            // The window takes a size that fits its screen, then shows; its interface zooms with it (windowfit.rs).
            if let (false, Some(window)) = (selftest, app.get_webview_window("main")) {
                windowfit::start(window, true);
            }
            if !selftest {
                game::prepare_first_play(app.handle());
                // Friends keeps in sync in the background (only while an account is signed in).
                social::start(app.handle());
            }
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
            storage::storage_overview,
            storage::storage_plan,
            storage::storage_clean,
            troubleshoot::error_history,
            troubleshoot::error_history_clear,
            troubleshoot::environment_info,
            troubleshoot::diagnostics_export,
            troubleshoot::diagnostics_reveal,
            data::profiles_list,
            data::profiles_create,
            data::profiles_rename,
            data::profiles_set_memory,
            profileicon::profiles_set_icon,
            profileicon::profiles_choose_icon_picture,
            profileicon::profiles_icon_picture,
            profileicon::profiles_set_pinned,
            profileicon::profile_icon_textures,
            data::profiles_select,
            data::profiles_inspect,
            data::profiles_duplicate,
            data::profiles_delete,
            data::system_info,
            data::diagnostics_text,
            game::java_args_check,
            javaplan::java_overview,
            javaplan::java_args_review,
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
            content::updates::content_updates,
            content::updates::content_update,
            content::updates::content_pin,
            content::profile_worlds,
            content::profile_files,
            content::profile_logs,
            content::profile_log_read,
            modpack::modpack_install,
            flintpack::flintpack_scan,
            flintpack::flintpack_pick_save,
            flintpack::flintpack_pick_open,
            flintpack::flintpack_export,
            flintpack::flintpack_inspect,
            flintpack::flintpack_import,
            flintpack::flintpack_reveal,
            flintpack::flintpack_take_opened,
            import::import_launchers,
            import::import_profiles,
            import::import_pick_folder,
            import::import_preview,
            import::import_start,
            import::import_progress,
            skins::skin_profile,
            skins::skin_upload,
            skins::skin_reset,
            skins::cape_set,
            ai::ai_suggest,
            ai::ai_cancel,
            ai::ai_status,
            ai::ai_quota,
            netprobe::server_ping,
            partners::partners_feed,
            partners::partners_favorites,
            partners::partners_set_favorite,
            partners::play::partners_play_preview,
            partners::play::partners_play,
            partners::play::partners_check_server,
            gamesettings::autosync::auto_sync_set_enabled,
            gamesettings::autosync::auto_sync_status,
            gamesettings::autosync::auto_sync_dismiss,
            gamesettings::autosync::auto_sync_overview,
            gamesettings::autosync::auto_sync_set_switch,
            gamesettings::autosync::auto_sync_set_excluded,
            gamesettings::autosync::auto_sync_open_backups,
            store::store_info,
            store::store_inventory,
            store::store_sign_in,
            store::store_sign_out,
            store::store_changes,
            store::store_equip,
            store::store_asset,
            store::anim::store_cape_strip,
            store::store_wallet,
            store::store_orders,
            store::store_approve_link,
            store::community::store_connections,
            store::community::store_discord_link_start,
            store::community::store_discord_link_reopen,
            store::community::store_discord_link_attempt,
            store::community::store_discord_unlink,
            store::community::store_rewards,
            store::community::store_rewards_recheck,
            store::community::store_community_catalog,
            store::community::store_reward_asset,
            store::community::store_reward_strip,
            store::community::store_open_discord_invite,
            social::social_snapshot,
            social::social_focus,
            social::social_refresh,
            social::social_settings,
            social::social_search,
            social::social_request,
            social::social_respond,
            social::social_cancel,
            social::social_remove,
            social::social_pin,
            social::social_send,
            social::social_history,
            social::social_read,
        ])
        .build(context)
        .expect("Flint Client failed to start")
        .run(|app, event| {
            // Games keep running after Flint quits; a headless keeper keeps their Discord presence.
            if let tauri::RunEvent::Exit = event {
                if let Some(presence) = app.try_state::<discord::Presence>() {
                    presence.hand_off();
                }
            }
            // macOS hands a double-clicked .flintpack to the running app instead of starting it again.
            #[cfg(target_os = "macos")]
            if let tauri::RunEvent::Opened { urls } = &event {
                let paths = urls.iter().filter_map(|u| u.to_file_path().ok()).map(|p| p.display().to_string());
                if let Some(path) = flintpack::pack_in_args(std::iter::once(String::new()).chain(paths)) {
                    flintpack::open(app, path);
                }
            }
        });
}

/// `flint-app --flint-discord-keeper …`: runs the Rich Presence keeper instead of the app.
pub fn run_discord_keeper() -> bool {
    discord::run_keeper_if_requested()
}

#[cfg(test)]
mod tests {
    use serde_json::Value;

    fn directives(csp: &str) -> Vec<(String, Vec<String>)> {
        csp.split(';')
            .map(str::trim)
            .filter(|d| !d.is_empty())
            .map(|d| {
                let mut words = d.split_whitespace().map(str::to_owned);
                (words.next().unwrap(), words.collect())
            })
            .collect()
    }

    /// The window's content security policy: scripts only from the app itself (no inline or eval'd code),
    /// network access only to the app's own IPC, and no plugins, `<base>` or form posts at all. Markdown from
    /// Modrinth is sanitized before it's shown; this is the layer behind that.
    #[test]
    fn the_content_security_policy_stays_strict() {
        let config: Value = serde_json::from_str(include_str!("../tauri.conf.json")).unwrap();
        for key in ["csp", "devCsp"] {
            let csp = config["app"]["security"][key].as_str().unwrap_or_else(|| panic!("{key} is set"));
            let list = directives(csp);
            let get = |name: &str| list.iter().find(|(n, _)| n == name).map(|(_, v)| v.clone());
            assert_eq!(get("default-src"), Some(vec!["'self'".to_owned()]), "{key}");
            assert!(get("script-src").is_none_or(|v| v == ["'self'"]), "{key}: scripts come from the app only");
            assert!(!csp.contains("unsafe-eval") && !csp.contains("wasm-unsafe-eval"), "{key}");
            let connect = get("connect-src").unwrap();
            assert!(connect.iter().all(|s| s == "'self'" || s == "ipc:" || s == "http://ipc.localhost" || s == "ws://localhost:1421"), "{key}: {connect:?}");
            for (name, value) in [("object-src", "'none'"), ("base-uri", "'none'"), ("form-action", "'none'")] {
                assert_eq!(get(name), Some(vec![value.to_owned()]), "{key}: {name}");
            }
        }
    }

    /// The window may only minimize, maximize, close, drag, update and restart; no shell, file system or
    /// opener access is granted to the interface (Flint's own commands check every path and link).
    #[test]
    fn the_window_gets_no_shell_file_or_opener_permissions() {
        let capability: Value = serde_json::from_str(include_str!("../capabilities/default.json")).unwrap();
        assert_eq!(capability["windows"], serde_json::json!(["main"]));
        assert!(capability.get("remote").is_none(), "no remote origin may call Flint");
        for permission in capability["permissions"].as_array().unwrap() {
            let name = permission.as_str().or_else(|| permission["identifier"].as_str()).unwrap();
            assert!(!["shell:", "fs:", "opener:", "http:"].iter().any(|p| name.starts_with(p)), "{name}");
        }
    }
}
