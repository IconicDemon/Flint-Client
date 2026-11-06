//! Mojang's `session/minecraft/join`, as a Minecraft client sends it before joining a server: how Friends and the Flint Store
//! prove an account (their server then asks Mojang's `hasJoined`). The access token goes to Mojang's session server only and
//! is never logged.
//!
//! Measured from the owner's PC on 2026-11-04, that endpoint answers 503 to about one request in seven even at one every
//! 6 seconds, in bursts that last a second or two (and 503, then 429, once more than about fifteen came in a minute), each
//! within a tenth of a second. So a 5xx or 429 is asked again after 1.5 s and then 3 s; no answer at all (a timeout of up
//! to 15 s) is not, so a player never waits through three of those.

use reqwest::Client;
use serde_json::json;
use std::time::Duration;
use url::Url;

/// How a join ended.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Joined {
    Yes,
    /// Mojang refused it (401/403: the session expired; another 4xx).
    Refused(u16),
    /// Mojang was busy (5xx) or limiting sign-ins (429) on every try.
    Busy(u16),
    /// No answer.
    Unreachable,
}

#[cfg(not(test))]
const RETRY_WAITS: [Duration; 2] = [Duration::from_millis(1500), Duration::from_millis(3000)];
#[cfg(test)]
const RETRY_WAITS: [Duration; 2] = [Duration::from_millis(5), Duration::from_millis(5)];

pub async fn join(http: &Client, url: Url, timeout: Duration, access_token: &str, uuid: &str, server_id: &str) -> Joined {
    let body = json!({ "accessToken": access_token, "selectedProfile": uuid, "serverId": server_id });
    let mut tries = 0;
    loop {
        let joined = match http.post(url.clone()).timeout(timeout).json(&body).send().await {
            Ok(response) => match response.status().as_u16() {
                200..=299 => Joined::Yes,
                status @ (429 | 500..=599) => Joined::Busy(status),
                status => Joined::Refused(status),
            },
            Err(_) => Joined::Unreachable,
        };
        match joined {
            Joined::Busy(_) if tries < RETRY_WAITS.len() => {
                tokio::time::sleep(RETRY_WAITS[tries]).await;
                tries += 1;
            }
            _ => return joined,
        }
    }
}
