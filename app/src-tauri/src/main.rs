// Prevents additional console window on Windows in release, DO NOT REMOVE!!
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    // A Rich Presence keeper (started when Flint quits during a game) never opens a window.
    if flint_app_lib::run_discord_keeper() {
        return;
    }
    flint_app_lib::run()
}
