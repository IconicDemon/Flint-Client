//! Takes out of text what could sign in as the player or tell who they are, before Flint shows a log or the player saves
//! or copies diagnostics: sign-in tokens (Microsoft, Xbox, Minecraft, the Flint Store's), JWTs, session ids,
//! `Authorization` and `Cookie` headers, secrets in URLs and JSON, e-mail addresses, IP addresses (but this computer's
//! own), the signed-in players' UUIDs, and the user's home folder and name (shown as `~`). What remains is what support
//! needs: versions, errors, stack traces, mod names, the Minecraft username.

/// Replaces a hidden value.
const HIDDEN: &str = "[hidden]";
/// Replaces a token found on its own.
const TOKEN: &str = "[hidden token]";

/// Account names that are words, not people: never replaced on their own.
const GENERIC_USERS: [&str; 10] = ["user", "users", "admin", "administrator", "owner", "guest", "default", "public", "home", "root"];

/// Folders under `C:\Users`, `/Users` that belong to no one.
const SHARED_HOMES: [&str; 5] = ["public", "default", "default user", "all users", "shared"];

/// Command line options whose next word is a secret or identifies the player.
const SECRET_OPTIONS: [&str; 5] = ["--accesstoken", "--session", "--uuid", "--xuid", "--clientid"];

pub struct Redactor {
    /// This user's home folder as text may spell it (`C:\Users\x`, `C:/Users/x`, JSON's `C:\\Users\\x`).
    homes: Vec<String>,
    /// The user's account name, replaced where it stands as a word.
    user: Option<String>,
    /// Signed-in players' UUIDs (32 lowercase hex digits).
    players: Vec<String>,
}

impl Redactor {
    /// For this computer: its user's home folder and account name.
    pub fn local() -> Self {
        let home = dirs::home_dir().map(|h| h.display().to_string());
        let user = std::env::var("USERNAME").or_else(|_| std::env::var("USER")).ok();
        Self::new(home.as_deref(), user.as_deref())
    }

    pub fn new(home: Option<&str>, user: Option<&str>) -> Self {
        let mut homes = Vec::new();
        if let Some(home) = home.map(|h| h.trim_end_matches(['/', '\\'])).filter(|h| h.len() > 3) {
            homes.push(home.to_owned());
            if home.contains('\\') {
                homes.push(home.replace('\\', "/"));
                homes.push(home.replace('\\', "\\\\"));
            }
        }
        let user = user
            .map(str::trim)
            .filter(|u| u.chars().count() >= 3 && !GENERIC_USERS.contains(&u.to_ascii_lowercase().as_str()))
            .map(str::to_owned);
        Self { homes, user, players: Vec::new() }
    }

    /// Also hides these players' UUIDs, in either spelling.
    pub fn with_players(mut self, uuids: impl IntoIterator<Item = String>) -> Self {
        for uuid in uuids {
            let hex = uuid.replace('-', "").to_ascii_lowercase();
            if hex.len() == 32 && hex.bytes().all(|b| b.is_ascii_hexdigit()) {
                self.players.push(hex);
            }
        }
        self
    }

    pub fn apply(&self, text: &str) -> String {
        let mut out = String::with_capacity(text.len());
        for line in text.lines() {
            out.push_str(&line_secrets(line));
            out.push('\n');
        }
        for home in &self.homes {
            out = replace_word_ci(&out, home, "~");
        }
        out = other_homes(&out);
        for uuid in &self.players {
            let dashed = format!("{}-{}-{}-{}-{}", &uuid[..8], &uuid[8..12], &uuid[12..16], &uuid[16..20], &uuid[20..]);
            out = replace_ci(&out, &dashed, "[player uuid]");
            out = replace_ci(&out, uuid, "[player uuid]");
        }
        if let Some(user) = &self.user {
            out = replace_word_ci(&out, user, "[user]");
        }
        out
    }
}

/// Redacts text for this computer (see the module notes).
pub fn redact(text: &str) -> String {
    Redactor::local().apply(text)
}

fn line_secrets(line: &str) -> String {
    let lower = line.to_ascii_lowercase();
    // A header: everything after its name.
    let header = ["proxy-authorization:", "authorization:", "set-cookie:", "cookie:"].iter().filter_map(|h| lower.find(h).map(|at| at + h.len())).min();
    if let Some(end) = header {
        return format!("{} {HIDDEN}", &line[..end]);
    }
    let line = options(line);
    let line = key_values(&line);
    let line = tokens(&line);
    let line = emails(&line);
    addresses(&line)
}

/// `--accessToken <x>`, `--uuid <x>`, `Bearer <x>`, `XBL3.0 <x>`: the next word.
fn options(line: &str) -> String {
    let mut words: Vec<&str> = Vec::new();
    let mut hide_next: Option<&str> = None;
    for word in line.split(' ') {
        if let Some(with) = hide_next.filter(|_| !word.is_empty()) {
            words.push(with);
            hide_next = None;
            continue;
        }
        let lower = word.to_ascii_lowercase();
        if SECRET_OPTIONS.contains(&lower.as_str()) {
            hide_next = Some(HIDDEN);
        } else if lower == "bearer" || lower == "xbl3.0" {
            hide_next = Some(TOKEN);
        }
        words.push(word);
    }
    words.join(" ")
}

fn is_key_byte(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'_' || b == b'-'
}

/// Keys whose value is a secret wherever they appear (`key=value`, `"key": "value"`).
fn secret_key(key: &str) -> bool {
    ["token", "secret", "password", "passwd"].iter().any(|end| key.ends_with(end))
        || matches!(key, "session" | "sessionid" | "session_id" | "session-id" | "sid" | "authorization" | "auth" | "cookie" | "xuid" | "uhs" | "apikey" | "api_key" | "api-key")
}

/// Keys that are only secrets in a URL's query (`?code=…`, `&sig=…`): elsewhere they are ordinary words.
fn query_key(key: &str) -> bool {
    matches!(key, "code" | "sig" | "signature" | "key" | "state" | "client_info")
}

/// The values of secret keys: `access_token=…`, `?code=…`, `"refreshToken": "…"`.
fn key_values(line: &str) -> String {
    let bytes = line.as_bytes();
    let mut out = String::with_capacity(line.len());
    let mut copied = 0;
    let mut i = 0;
    while i < bytes.len() {
        let b = bytes[i];
        if b != b'=' && b != b':' {
            i += 1;
            continue;
        }
        // The key before it, perhaps quoted (`"key":`, `"key" :`, JSON inside a string's `\"key\":`).
        let mut k = i;
        while k > 0 && i - k < 2 && bytes[k - 1] == b' ' {
            k -= 1;
        }
        let after_key = k;
        while k > 0 && after_key - k < 3 && matches!(bytes[k - 1], b'"' | b'\'' | b'\\') {
            k -= 1;
        }
        let quoted = k < after_key;
        let key_end = k;
        while k > 0 && is_key_byte(bytes[k - 1]) {
            k -= 1;
        }
        let key = line[k..key_end].to_ascii_lowercase();
        let in_query = k > 0 && matches!(bytes[k - 1], b'?' | b'&');
        let secret = !key.is_empty()
            && if b == b'=' {
                secret_key(&key) || (in_query && query_key(&key))
            } else {
                // `token: x` in prose is left alone; a quoted key, or a compound one (`accessToken:`), is not.
                secret_key(&key) && (quoted || key.len() > 6)
            };
        if !secret {
            i += 1;
            continue;
        }
        // The value: quoted up to its closing quote, or up to the next delimiter.
        let mut v = i + 1;
        while v < bytes.len() && bytes[v] == b' ' {
            v += 1;
        }
        let mut end;
        if v < bytes.len() && matches!(bytes[v], b'"' | b'\'' | b'\\') {
            while v < bytes.len() && matches!(bytes[v], b'"' | b'\'' | b'\\') {
                v += 1;
            }
            end = v;
            while end < bytes.len() && !matches!(bytes[end], b'"' | b'\'' | b'\\') {
                end += 1;
            }
        } else {
            end = v;
            while end < bytes.len() && !matches!(bytes[end], b'&' | b' ' | b'\t' | b',' | b';' | b'"' | b'\'' | b'<' | b'>' | b')' | b']' | b'}') {
                end += 1;
            }
        }
        if end > v && &line[v..end] != HIDDEN {
            out.push_str(&line[copied..v]);
            out.push_str(HIDDEN);
            copied = end;
        }
        i = end.max(i + 1);
    }
    out.push_str(&line[copied..]);
    out
}

fn is_token_byte(b: u8) -> bool {
    b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-')
}

/// Tokens standing on their own: JWTs and JWEs (`eyJ…`, Minecraft's and Xbox's), Microsoft's (`M.C…`, `M.R3_…`), and long
/// opaque strings mixing upper and lower case letters and digits (Microsoft access tokens). Hashes (lower case hex) stay.
fn tokens(line: &str) -> String {
    let bytes = line.as_bytes();
    let run = |from: usize, extra: &[u8]| {
        let mut end = from;
        while end < bytes.len() && (is_token_byte(bytes[end]) || extra.contains(&bytes[end])) {
            end += 1;
        }
        end
    };
    let mut out = String::with_capacity(line.len());
    let mut copied = 0;
    let mut i = 0;
    while i < bytes.len() {
        if !is_token_byte(bytes[i]) || (i > 0 && is_token_byte(bytes[i - 1])) {
            i += 1;
            continue;
        }
        // A JWT or JWE: base64url parts joined by dots.
        let dotted = run(i, b".");
        let parts: Vec<&str> = line[i..dotted].split('.').collect();
        let jwt = line[i..].starts_with("eyJ") && parts.len() >= 3 && dotted - i >= 30 && parts[..parts.len() - 1].iter().all(|p| !p.is_empty());
        // Microsoft's tokens and codes, which mix in a few marks.
        let marked = run(i, b".!*$");
        let microsoft = (line[i..].starts_with("M.C") || line[i..].starts_with("M.R3_")) && marked - i >= 24;
        // Long opaque strings (base64's `+/=` included).
        let plain = run(i, b"+/=");
        let word = &line[i..plain];
        let opaque = word.len() >= 100 && [u8::is_ascii_uppercase, u8::is_ascii_lowercase, u8::is_ascii_digit].iter().all(|class| word.bytes().any(|b| class(&b)));
        let end = if jwt {
            dotted
        } else if microsoft {
            marked
        } else if opaque {
            plain
        } else {
            i += 1;
            continue;
        };
        out.push_str(&line[copied..i]);
        out.push_str(TOKEN);
        copied = end;
        i = end;
    }
    out.push_str(&line[copied..]);
    out
}

/// `name@example.com` becomes `[email]`. Java's `module@version` (`java.base@25.0.1`) is not an address: its last part
/// isn't letters.
fn emails(line: &str) -> String {
    let bytes = line.as_bytes();
    let mut out = String::with_capacity(line.len());
    let mut copied = 0;
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] != b'@' || i < copied {
            i += 1;
            continue;
        }
        let mut start = i;
        while start > copied && (bytes[start - 1].is_ascii_alphanumeric() || matches!(bytes[start - 1], b'.' | b'_' | b'%' | b'+' | b'-')) {
            start -= 1;
        }
        let mut end = i + 1;
        while end < bytes.len() && (bytes[end].is_ascii_alphanumeric() || matches!(bytes[end], b'.' | b'-')) {
            end += 1;
        }
        let domain = line[i + 1..end].trim_end_matches('.');
        let end = i + 1 + domain.len();
        let tld = domain.rsplit('.').next().unwrap_or_default();
        if start < i && domain.contains('.') && tld.len() >= 2 && tld.bytes().all(|b| b.is_ascii_alphabetic()) {
            out.push_str(&line[copied..start]);
            out.push_str("[email]");
            copied = end;
            i = end;
        } else {
            i += 1;
        }
    }
    out.push_str(&line[copied..]);
    out
}

/// IPv4 addresses become `[ip]`, except this computer's own (127.x, 0.0.0.0). Version numbers stay: a part above 255, or
/// a fifth part, means it isn't an address.
fn addresses(line: &str) -> String {
    let bytes = line.as_bytes();
    let mut out = String::with_capacity(line.len());
    let mut copied = 0;
    let mut i = 0;
    while i < bytes.len() {
        let boundary = i == 0 || !(bytes[i - 1].is_ascii_alphanumeric() || bytes[i - 1] == b'.');
        if !boundary || !bytes[i].is_ascii_digit() {
            i += 1;
            continue;
        }
        let mut parts = Vec::new();
        let mut j = i;
        loop {
            let from = j;
            while j < bytes.len() && bytes[j].is_ascii_digit() && j - from < 4 {
                j += 1;
            }
            let Ok(part) = line[from..j].parse::<u32>() else { break };
            if j - from > 3 || part > 255 {
                parts.clear();
                break;
            }
            parts.push(part);
            if parts.len() == 4 || j >= bytes.len() || bytes[j] != b'.' || !bytes.get(j + 1).is_some_and(u8::is_ascii_digit) {
                break;
            }
            j += 1;
        }
        let ends_here = j >= bytes.len() || !(bytes[j].is_ascii_alphanumeric() || (bytes[j] == b'.' && bytes.get(j + 1).is_some_and(u8::is_ascii_digit)));
        if parts.len() == 4 && ends_here {
            let local = parts[0] == 127 || parts.iter().all(|p| *p == 0);
            if !local {
                out.push_str(&line[copied..i]);
                out.push_str("[ip]");
                copied = j;
            }
            i = j;
        } else {
            // Skip the rest of this number so its later parts aren't read as an address of their own.
            while j < bytes.len() && (bytes[j].is_ascii_digit() || bytes[j] == b'.') {
                j += 1;
            }
            i = j.max(i + 1);
        }
    }
    out.push_str(&line[copied..]);
    out
}

/// Any user's home folder (`C:\Users\name`, `/Users/name`, `/home/name`) becomes `~`, for logs written on another
/// account or computer.
fn other_homes(text: &str) -> String {
    let bytes = text.as_bytes();
    let lower = text.to_ascii_lowercase();
    let mut out = String::with_capacity(text.len());
    let mut copied = 0;
    let mut from = 0;
    // Each word's next place, looked for again only once passed, so a long log is read once per word.
    const WORDS: [&str; 2] = ["users", "home"];
    let mut next: [Option<usize>; 2] = [None; 2];
    loop {
        for (slot, word) in next.iter_mut().zip(WORDS) {
            if slot.is_none_or(|at| at < from) {
                *slot = lower.get(from..).and_then(|rest| rest.find(word)).map(|at| from + at).or(Some(usize::MAX));
            }
        }
        let Some((at, len)) = next.iter().zip(WORDS).filter_map(|(at, w)| at.filter(|at| *at != usize::MAX).map(|at| (at, w.len()))).min() else { break };
        from = at + len;
        if at == 0 || at < copied {
            continue;
        }
        // The separator before: `\`, `\\` (JSON) or `/`.
        let mut sep = at - 1;
        if !matches!(bytes[sep], b'\\' | b'/') {
            continue;
        }
        if bytes[sep] == b'\\' && sep > 0 && bytes[sep - 1] == b'\\' {
            sep -= 1;
        }
        let drive = sep >= 2 && bytes[sep - 1] == b':' && bytes[sep - 2].is_ascii_alphabetic() && (sep == 2 || !bytes[sep - 3].is_ascii_alphanumeric());
        let start = if drive && len == 5 {
            sep - 2
        } else if bytes[at - 1] == b'/' && (sep == 0 || !(bytes[sep - 1].is_ascii_alphanumeric() || matches!(bytes[sep - 1], b'.' | b':' | b'/' | b'\\'))) {
            sep
        } else {
            continue;
        };
        // The separator after, then the name up to the next separator.
        let mut name_start = at + len;
        let mut seps = 0;
        while name_start < bytes.len() && matches!(bytes[name_start], b'\\' | b'/') && seps < 2 {
            name_start += 1;
            seps += 1;
        }
        if seps == 0 {
            continue;
        }
        let mut end = name_start;
        while end < bytes.len() && !matches!(bytes[end], b'\\' | b'/' | b'"' | b'\'' | b'<' | b'>' | b'|' | b':' | b'*' | b'?' | b'\n' | b'\r') {
            end += 1;
        }
        // A name with a space runs to the next separator; at the end of a path, the first word is the name.
        if end >= bytes.len() || !matches!(bytes[end], b'\\' | b'/') {
            if let Some(space) = text[name_start..end].find(char::is_whitespace) {
                end = name_start + space;
            }
        }
        let name = &lower[name_start..end];
        if name.is_empty() || SHARED_HOMES.contains(&name) {
            continue;
        }
        out.push_str(&text[copied..start]);
        out.push('~');
        copied = end;
        from = end;
    }
    out.push_str(&text[copied..]);
    out
}

/// `text` with every `needle` (ASCII letters in any case) replaced.
fn replace_ci(text: &str, needle: &str, with: &str) -> String {
    if needle.is_empty() {
        return text.to_owned();
    }
    let lower = text.to_ascii_lowercase();
    let needle = needle.to_ascii_lowercase();
    let mut out = String::with_capacity(text.len());
    let mut copied = 0;
    for (at, _) in lower.match_indices(&needle) {
        if at < copied {
            continue;
        }
        out.push_str(&text[copied..at]);
        out.push_str(with);
        copied = at + needle.len();
    }
    out.push_str(&text[copied..]);
    out
}

/// `replace_ci` only where `word` stands on its own.
fn replace_word_ci(text: &str, word: &str, with: &str) -> String {
    let lower = text.to_ascii_lowercase();
    let needle = word.to_ascii_lowercase();
    let bytes = text.as_bytes();
    let mut out = String::with_capacity(text.len());
    let mut copied = 0;
    for (at, _) in lower.match_indices(&needle) {
        let end = at + needle.len();
        let alone = (at == 0 || !(bytes[at - 1].is_ascii_alphanumeric() || bytes[at - 1] == b'_')) && (end >= bytes.len() || !(bytes[end].is_ascii_alphanumeric() || bytes[end] == b'_'));
        if at < copied || !alone {
            continue;
        }
        out.push_str(&text[copied..at]);
        out.push_str(with);
        copied = end;
    }
    out.push_str(&text[copied..]);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const JWT: &str = "eyJhbGciOiJIUzI1NiJ9.eyJ4dWlkIjoiMjUzNTQxMjM0NTY3ODkwMSIsInN1YiI6IjA2OWE3OWY0In0.dGhpcy1pcy1hLXNpZ25hdHVyZQ";
    const PLAYER: &str = "069a79f444e94726a5befca90e38aaf5";

    fn windows() -> Redactor {
        Redactor::new(Some(r"C:\Users\jordan"), Some("jordan")).with_players([PLAYER.to_owned()])
    }

    fn check(input: &str, gone: &[&str], kept: &[&str]) -> String {
        let out = windows().apply(input);
        for secret in gone {
            assert!(!out.contains(secret), "{secret:?} is still in: {out}");
        }
        for text in kept {
            assert!(out.contains(text), "{text:?} was lost from: {out}");
        }
        out
    }

    #[test]
    fn launch_arguments_lose_their_secrets_but_keep_the_rest() {
        let out = check(
            &format!("--username IconicDemon --version 26.3 --uuid {PLAYER} --accessToken {JWT} --clientId abc123 --xuid 2535412345678901 --userType msa --session other456"),
            &[JWT, "abc123", "2535412345678901", "other456", PLAYER],
            &["--username IconicDemon", "--version 26.3", "--userType msa", "--accessToken [hidden]"],
        );
        assert_eq!(out.matches(HIDDEN).count(), 5, "{out}");
    }

    #[test]
    fn headers_and_bearer_tokens_are_hidden() {
        check("POST /authenticate/login_with_xbox Authorization: Bearer eyJabc.def.ghi", &["eyJabc", "Bearer"], &["Authorization: [hidden]"]);
        check("authorization: XBL3.0 x=1234567890;eyJlbmMiOi.abc.def", &["1234567890", "eyJlbmMiOi"], &["authorization:"]);
        check("Cookie: session=abc123; csrftoken=zzz", &["abc123", "zzz"], &["Cookie: [hidden]"]);
        check("Set-Cookie: __Secure-session=s3cr3t; Path=/; HttpOnly", &["s3cr3t"], &["Set-Cookie:"]);
        check("[Flint] retrying with bearer abcdef123456 after 401", &["abcdef123456"], &["bearer [hidden token] after 401"]);
        // Another account whose name starts the same is a home folder of its own, not this one's with "han" after it.
        check(r"C:\Users\jordanmills\Desktop is someone else's", &["jordanmills"], &[r"~\Desktop is someone else's"]);
    }

    #[test]
    fn secrets_in_urls_and_json_are_hidden() {
        check(
            "GET https://login.live.com/oauth20_desktop.srf?code=M.C507_BAY.2.U.8b2c1d4e-aaaa&lc=1033&state=xyz789",
            &["M.C507", "8b2c1d4e", "xyz789"],
            &["https://login.live.com/oauth20_desktop.srf?code=[hidden]", "&lc=1033"],
        );
        check("https://cdn.example.net/pack.zip?sv=2023-11-03&sig=AbC%2Bdef%3D&se=2026-11-02", &["AbC%2Bdef"], &["sv=2023-11-03", "&se=2026-11-02"]);
        check(
            r#"{"access_token":"EwBIA8l6BAAUs","refresh_token":"M.C507_BAY.0.U.-CdVq","expires_in":86400,"user_id":"x"}"#,
            &["EwBIA8l6BAAUs", "M.C507_BAY"],
            &[r#""expires_in":86400"#, r#""user_id":"x""#],
        );
        check(r#"{"accessToken": "abc.def", "refreshToken" : "ghi"}"#, &["abc.def", "ghi"], &[r#""accessToken": "[hidden]""#]);
        check(r#"body: {\"xuid\":\"2535412345678901\",\"gamertag\":\"Iconic\"}"#, &["2535412345678901"], &[r#"\"gamertag\":\"Iconic\""#]);
        check("FLINT_STORE_SESSION_TOKEN=st_live_abcdef api_key=k-123", &["st_live_abcdef", "k-123"], &["FLINT_STORE_SESSION_TOKEN=[hidden]"]);
    }

    #[test]
    fn tokens_on_their_own_are_hidden_but_hashes_and_prose_are_not() {
        let sha512 = "a".repeat(64) + &"0123456789abcdef".repeat(4);
        let opaque = "EwB4A8l6BAAU".to_owned() + &"Xy9Zq2Lk7Pw4".repeat(10);
        check(&format!("token {JWT} expired"), &[JWT], &["token [hidden token] expired"]);
        check("refresh with M.R3_BAY.-CdVqzVQZs!Mz2fK*3aB$xyz now", &["M.R3_BAY"], &["refresh with [hidden token] now"]);
        check(&format!("Microsoft said {opaque}"), &[&opaque], &["Microsoft said [hidden token]"]);
        check(&format!("sodium-0.6.jar sha512 {sha512}"), &[], &[&sha512]);
        check("Token refresh failed: invalid_grant (the sign-in expired)", &[], &["Token refresh failed: invalid_grant (the sign-in expired)"]);
        check("Process exited with code=1 after 3 s", &[], &["code=1"]);
    }

    #[test]
    fn email_and_ip_addresses_are_hidden_but_versions_stay() {
        check("Signed in as steve.miner+mc@outlook.co.uk.", &["steve.miner", "outlook"], &["Signed in as [email]."]);
        check("\tat java.base@25.0.1/java.lang.Thread.run(Thread.java:1474)", &[], &["java.base@25.0.1/java.lang.Thread.run"]);
        check("Connecting to 203.0.113.42, 25565 (mc.example.net/198.51.100.7:25565)", &["203.0.113.42", "198.51.100.7"], &["[ip], 25565", "mc.example.net/[ip]:25565"]);
        check("gateway on 127.0.0.1:8787, bound 0.0.0.0", &[], &["127.0.0.1:8787", "0.0.0.0"]);
        check("Windows 10.0.26300.1 Java 25.0.1 LWJGL 3.4.1 build 1.2.3.4.5", &[], &["10.0.26300.1", "25.0.1", "3.4.1", "1.2.3.4.5"]);
    }

    #[test]
    fn home_folders_and_the_user_name_become_tilde() {
        check(
            r"Loading C:\Users\jordan\AppData\Roaming\Flint Client\profiles\PvP-26.2\mods\sodium.jar",
            &["jordan"],
            &[r"~\AppData\Roaming\Flint Client\profiles\PvP-26.2\mods\sodium.jar"],
        );
        check(r#"{"javaPath":"C:\\Users\\JORDAN\\Java\\bin\\java.exe"}"#, &["JORDAN"], &[r#""javaPath":"~\\Java\\bin\\java.exe""#]);
        check("cwd C:/Users/jordan/Desktop", &["jordan"], &["cwd ~/Desktop"]);
        check(r"Opened D:\Users\Someone Else\Downloads\pack.zip", &["Someone Else"], &[r"Opened ~\Downloads\pack.zip"]);
        check("/Users/steve/Library/Application Support/Flint Client/minecraft", &["steve"], &["~/Library/Application Support/Flint Client/minecraft"]);
        check("--gameDir /home/alex/.local/share/Flint Client/profiles/x", &["alex"], &["--gameDir ~/.local/share/Flint Client/profiles/x"]);
        check(r"C:\Users\Public\Documents and /Users/Shared/x stay", &[], &[r"C:\Users\Public\Documents", "/Users/Shared/x"]);
        check("Computer user: jordan, launcher by jordanmills", &[], &["Computer user: [user], launcher by jordanmills"]);
    }

    #[test]
    fn signed_in_players_uuids_are_hidden_in_either_spelling() {
        check(
            "UUID of player IconicDemon is 069a79f4-44e9-4726-a5be-fca90e38aaf5; skin 069A79F444E94726A5BEFCA90E38AAF5.png; world 3f2a0000-0000-4000-8000-000000000001",
            &["069a79f4-44e9", "069A79F4"],
            &["UUID of player IconicDemon is [player uuid]", "skin [player uuid].png", "world 3f2a0000-0000-4000-8000-000000000001"],
        );
    }

    #[test]
    fn a_long_log_is_read_once() {
        // 512 KB of paths: every one is found, in one pass per word (a search from the start each time took minutes).
        let line = "[17:55:10] [Worker/INFO]: Loaded /Users/steve/Library/x.png and C:\\Users\\Alex\\y.jar from home\n";
        let log = line.repeat(512 * 1024 / line.len());
        let started = std::time::Instant::now();
        let out = windows().apply(&log);
        assert_eq!(out.matches("~/Library/x.png and ~\\y.jar from home").count(), log.lines().count());
        assert!(started.elapsed() < std::time::Duration::from_secs(20), "{:?}", started.elapsed());
    }

    #[test]
    fn ordinary_log_lines_are_unchanged() {
        let lines = [
            "[17:55:10] [Render thread/INFO]: Loading 64 mods:",
            "[17:55:12] [main/WARN]: Reference map 'iris.refmap.json' for mixins.iris.json could not be read.",
            "Caused by: org.spongepowered.asm.mixin.transformer.throwables.MixinTransformerError: An unexpected critical error was encountered",
            "\tat net.fabricmc.loader.impl.launch.knot.KnotClassDelegate.tryLoadClass(KnotClassDelegate.java:340)",
            "[Flint] Minecraft 26.3 prepared in 1.2 s; Java start-up cache: used",
            "Description: Initializing game",
            "Memory: 312 MB / 1024 MB up to 4096 MB",
        ];
        for line in lines {
            assert_eq!(windows().apply(line), format!("{line}\n"));
        }
    }

    #[test]
    fn the_shared_redact_still_hides_launch_tokens() {
        let out = redact(&format!("--username Steve --accessToken secret123 --session other456\nbearer {JWT} end"));
        assert!(!out.contains("secret123") && !out.contains("other456") && !out.contains(JWT), "{out}");
        assert!(out.contains("--username Steve"));
    }
}
