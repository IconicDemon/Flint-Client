//! Which of Flint's services this build talks to, fixed when it is built (`FLINT_CHANNEL`, checked by build.rs).
//!
//! - Production (the default): Flint's production project and store.flintclient.com.
//! - Staging (`FLINT_CHANNEL=staging`): a private test build. It talks to Flint's staging project (`FLINT_SUPABASE_URL`)
//!   and the staging Store (`FLINT_STORE_URL`), both baked in, and installs beside the real Flint as "Flint Client Staging"
//!   (tauri.staging.conf.json, applied by scripts/release-build.mjs) with its own data folder and its own saved sign-ins, so
//!   it never touches the real app's data. build.rs refuses a staging build missing any staging address or given a
//!   production one, so it can't fall back to production.

/// A staging test build.
pub const STAGING: bool = match option_env!("FLINT_CHANNEL") {
    Some(channel) => same(channel, "staging"),
    None => false,
};

/// String equality a constant can use.
const fn same(a: &str, b: &str) -> bool {
    let (a, b) = (a.as_bytes(), b.as_bytes());
    if a.len() != b.len() {
        return false;
    }
    let mut i = 0;
    while i < a.len() {
        if a[i] != b[i] {
            return false;
        }
        i += 1;
    }
    true
}

/// The staging Store's origin (staging builds only; empty otherwise).
pub const STAGING_STORE: &str = match option_env!("FLINT_STORE_URL") {
    Some(url) if STAGING => url,
    _ => "",
};

/// The data folder's name (datadir.rs): the staging build keeps its own.
pub const DATA_FOLDER: &str = if STAGING { "Flint Client Staging" } else { "Flint Client" };

/// The system keychain's service for saved sign-ins: the staging build keeps its own.
pub const KEYRING_SERVICE: &str = if STAGING { "com.flintclient.desktop.staging" } else { "com.flintclient.desktop" };
