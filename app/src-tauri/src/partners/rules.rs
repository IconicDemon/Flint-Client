//! What the launcher accepts from a partner listing before it acts on it: a server host that is a public name or a public
//! IP (never a private, loopback, link-local or otherwise non-public address, before or after DNS), a port, the versions a
//! server takes, plain text for the name written into the server list, and how a game is told to join (Quick Play from
//! 1.20, `--server`/`--port` before). The interface checks the same rules (src/lib/partners.ts); these are the ones that
//! count, because the launcher is what writes servers.dat and starts the game.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

pub const DEFAULT_PORT: u16 = 25565;

/// Top-level names that only mean something on a local network (or nowhere).
const LOCAL_TLDS: &[&str] = &["localhost", "local", "localdomain", "internal", "intranet", "lan", "home", "corp", "private", "arpa", "onion", "invalid"];

/// Development builds only, for testing the Play flow against a server on this computer: `FLINT_PARTNERS_ALLOW_LOOPBACK=1`
/// lets a listing name `localhost` or a loopback address. Release builds never accept one.
pub fn allow_loopback() -> bool {
    cfg!(debug_assertions) && std::env::var("FLINT_PARTNERS_ALLOW_LOOPBACK").is_ok_and(|v| v == "1")
}

pub fn public_ipv4(ip: Ipv4Addr) -> bool {
    let [a, b, c, _] = ip.octets();
    !(a == 0
        || a == 10
        || a == 127
        || a >= 224
        || (a == 100 && (64..=127).contains(&b))
        || (a == 169 && b == 254)
        || (a == 172 && (16..=31).contains(&b))
        || (a == 192 && b == 168)
        || (a == 192 && b == 0 && (c == 0 || c == 2))
        || (a == 198 && (b == 18 || b == 19))
        || (a == 198 && b == 51 && c == 100)
        || (a == 203 && b == 0 && c == 113))
}

pub fn public_ipv6(ip: Ipv6Addr) -> bool {
    let g = ip.segments();
    if g[0] & 0xe000 != 0x2000 {
        // Not global unicast: ::, ::1, IPv4-mapped, fc00::/7, fe80::/10, ff00::/8...
        return false;
    }
    match (g[0], g[1]) {
        (0x2001, 0x0db8) | (0x2001, 0) => false, // documentation, Teredo
        (0x2002, _) => public_ipv4(Ipv4Addr::new((g[1] >> 8) as u8, g[1] as u8, (g[2] >> 8) as u8, g[2] as u8)), // 6to4
        _ => true,
    }
}

pub fn public_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => public_ipv4(v4),
        IpAddr::V6(v6) => public_ipv6(v6),
    }
}

fn loopback_ok(ip: IpAddr, loopback: bool) -> bool {
    public_ip(ip) || (loopback && ip.is_loopback())
}

#[derive(Debug, PartialEq)]
pub enum HostProblem {
    Invalid,
    Private,
}

/// A server host from a listing, lowercased, without a trailing dot or IPv6 brackets: a public DNS name (two or more
/// labels, an alphabetic top-level domain, nothing local-only) or a public IP. `loopback` (development only) also takes
/// `localhost` and loopback addresses.
pub fn check_host(raw: &str, loopback: bool) -> Result<String, HostProblem> {
    let mut host = raw.trim().to_ascii_lowercase();
    if host.starts_with('[') && host.ends_with(']') {
        host = host[1..host.len() - 1].to_owned();
    }
    if let Some(stripped) = host.strip_suffix('.') {
        host = stripped.to_owned();
    }
    if host.is_empty() || host.len() > 253 || !host.is_ascii() {
        return Err(HostProblem::Invalid);
    }
    if loopback && host == "localhost" {
        return Ok(host);
    }
    // Only the dotted-quad form is an IPv4 address here ("127.1" or "0x7f.0.0.1" are refused as names below).
    let dotted = host.split('.').count() == 4 && host.split('.').all(|p| !p.is_empty() && p.len() <= 3 && p.bytes().all(|b| b.is_ascii_digit()) && (p == "0" || !p.starts_with('0')));
    if dotted {
        let ip: Ipv4Addr = host.parse().map_err(|_| HostProblem::Invalid)?;
        return if loopback_ok(IpAddr::V4(ip), loopback) { Ok(host) } else { Err(HostProblem::Private) };
    }
    if host.contains(':') {
        let ip: Ipv6Addr = host.parse().map_err(|_| HostProblem::Invalid)?;
        return if loopback_ok(IpAddr::V6(ip), loopback) { Ok(host) } else { Err(HostProblem::Private) };
    }
    let labels: Vec<&str> = host.split('.').collect();
    let label_ok = |l: &&str| (1..=63).contains(&l.len()) && l.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-') && !l.starts_with('-') && !l.ends_with('-');
    if labels.len() < 2 || !labels.iter().all(label_ok) {
        return Err(HostProblem::Invalid);
    }
    let tld = labels[labels.len() - 1];
    let alphabetic = (2..=63).contains(&tld.len()) && tld.bytes().all(|b| b.is_ascii_lowercase());
    let punycode = tld.starts_with("xn--") && tld.len() > 4;
    if !alphabetic && !punycode {
        return Err(HostProblem::Invalid);
    }
    if LOCAL_TLDS.contains(&tld) || host.ends_with(".home.arpa") {
        return Err(HostProblem::Private);
    }
    Ok(host)
}

/// `host` or `host:port` (the port only when it isn't Minecraft's default), as the server list and Quick Play write it.
pub fn address(host: &str, port: u16) -> String {
    let host = if host.contains(':') { format!("[{host}]") } else { host.to_owned() };
    if port == DEFAULT_PORT {
        host
    } else {
        format!("{host}:{port}")
    }
}

/// Whether every address a host resolves to is public. A host that doesn't resolve is let through: Minecraft also looks
/// up the SRV record (`_minecraft._tcp`), which this check can't, and an unreachable server only fails to connect.
pub async fn resolves_publicly(host: &str, port: u16, loopback: bool) -> Result<(), String> {
    if let Ok(ip) = host.parse::<IpAddr>() {
        return if loopback_ok(ip, loopback) { Ok(()) } else { Err(PRIVATE.into()) };
    }
    let found = tokio::time::timeout(std::time::Duration::from_secs(5), tokio::net::lookup_host((host, port))).await;
    match found {
        Ok(Ok(addresses)) => {
            for a in addresses {
                if !loopback_ok(a.ip(), loopback) {
                    return Err(PRIVATE.into());
                }
            }
            Ok(())
        }
        _ => Ok(()),
    }
}

pub const PRIVATE: &str = "This server's address leads to a private or local network, so Flint won't join it.";

/// Compares Minecraft release numbers by their parts (1.8.9 < 1.21.1 < 1.21.10 < 26.1 < 26.1.2).
pub fn compare_versions(a: &str, b: &str) -> std::cmp::Ordering {
    let parts = |v: &str| v.split('.').map(|p| p.parse::<u32>().unwrap_or(0)).collect::<Vec<_>>();
    let (x, y) = (parts(a), parts(b));
    for i in 0..x.len().max(y.len()) {
        let o = x.get(i).unwrap_or(&0).cmp(y.get(i).unwrap_or(&0));
        if o.is_ne() {
            return o;
        }
    }
    std::cmp::Ordering::Equal
}

/// A Minecraft version number as listings write them: `1.8.9`, `26.3`.
pub fn is_version(v: &str) -> bool {
    let parts: Vec<&str> = v.split('.').collect();
    (2..=4).contains(&parts.len()) && parts.iter().all(|p| (1..=3).contains(&p.len()) && p.bytes().all(|b| b.is_ascii_digit()))
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Versions {
    pub min: Option<String>,
    pub max: Option<String>,
    pub list: Option<Vec<String>>,
}

/// Whether a profile on `version` can join a server taking `versions` (none named: any).
pub fn version_fits(version: &str, versions: Option<&Versions>) -> bool {
    let Some(v) = versions else { return true };
    if let Some(list) = &v.list {
        return list.iter().any(|l| l == version);
    }
    v.min.as_deref().is_none_or(|min| compare_versions(version, min).is_ge()) && v.max.as_deref().is_none_or(|max| compare_versions(version, max).is_le())
}

/// What tells a game to join a server as it starts: Quick Play (`--quickPlayMultiplayer`, Minecraft 1.20 and newer),
/// which finds the server in its list by the same address; older versions (1.8.9) take `--server` and `--port`.
pub fn join_args(game_version: &str, address: &str, host: &str, port: u16) -> Vec<String> {
    if compare_versions(game_version, "1.20").is_ge() {
        vec!["--quickPlayMultiplayer".into(), address.into()]
    } else {
        vec!["--server".into(), host.into(), "--port".into(), port.to_string()]
    }
}

/// A listing's name as it goes into the server list: control, invisible and reordering characters and Minecraft's
/// formatting sign removed, spaces collapsed, at most 60 characters. None when nothing is left.
pub fn server_name(raw: &str) -> Option<String> {
    let hidden = |c: char| {
        c.is_control()
            || matches!(c, '\u{00ad}' | '\u{061c}' | '\u{115f}' | '\u{1160}' | '\u{180e}' | '\u{200b}'..='\u{200f}' | '\u{202a}'..='\u{202e}' | '\u{2060}'..='\u{206f}' | '\u{3164}' | '\u{feff}' | '\u{ffa0}' | '\u{fff9}'..='\u{fffb}' | '§')
    };
    let text: String = raw.chars().map(|c| if c == '\n' || c == '\t' { ' ' } else { c }).filter(|c| !hidden(*c)).collect();
    let text = text.split_whitespace().collect::<Vec<_>>().join(" ");
    (!text.is_empty() && text.chars().count() <= 60).then_some(text)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hosts_are_public_names_or_public_addresses() {
        for host in ["play.example.com", "Play.Example.COM.", "mc.xn--80ak6aa92e.xn--p1ai", "8.8.8.8", "1.1.1.1", "2606:4700:4700::1111", "[2606:4700:4700::1111]", "100.63.255.255", "172.32.0.1", "2002:0808:0808::1"] {
            assert!(check_host(host, false).is_ok(), "{host}");
        }
        let private = [
            "minecraft.localhost", "server.local", "nas.home.arpa", "printer.lan", "10.0.0.5", "127.0.0.1", "127.255.0.1", "0.0.0.0", "169.254.10.1", "172.16.0.1",
            "172.31.255.255", "192.168.1.20", "100.64.0.1", "192.0.2.10", "198.18.0.1", "224.0.0.1", "255.255.255.255", "::1", "::", "fe80::1", "fd12:3456::1",
            "::ffff:192.168.0.1", "::ffff:8.8.8.8", "2001:db8::1", "2002:c0a8:0101::1",
        ];
        for host in private {
            assert_eq!(check_host(host, false), Err(HostProblem::Private), "{host}");
        }
        let x = "x".repeat(254);
        for host in ["localhost", "127.1", "0x7f.0.0.1", "2130706433", "010.0.0.1", "play_server.example.com", "-bad.example.com", "a b.example.com", "", x.as_str(), "ünï.example", "fe80::1%eth0"] {
            assert_eq!(check_host(host, false), Err(HostProblem::Invalid), "{host}");
        }
        // Development only: this computer.
        assert_eq!(check_host("localhost", true).as_deref(), Ok("localhost"));
        assert_eq!(check_host("127.0.0.1", true).as_deref(), Ok("127.0.0.1"));
        assert_eq!(check_host("192.168.1.20", true), Err(HostProblem::Private), "never the local network");
    }

    #[tokio::test]
    async fn a_name_that_resolves_to_a_private_address_is_refused() {
        assert_eq!(resolves_publicly("localhost", 25565, false).await.unwrap_err(), PRIVATE);
        assert!(resolves_publicly("localhost", 25565, true).await.is_ok());
        assert!(resolves_publicly("192.168.0.4", 25565, false).await.is_err());
        assert!(resolves_publicly("8.8.8.8", 25565, false).await.is_ok());
        // A name that doesn't resolve: Minecraft may still find it by SRV record, and fails on its own if not.
        assert!(resolves_publicly("nothing-here.invalid", 25565, false).await.is_ok());
    }

    #[test]
    fn addresses_versions_and_join_arguments() {
        assert_eq!(address("play.example.net", 25565), "play.example.net");
        assert_eq!(address("play.example.net", 25570), "play.example.net:25570");
        assert_eq!(address("2606:4700::1", 25570), "[2606:4700::1]:25570");
        let mut list = vec!["26.1.2", "1.21.10", "1.8.9", "26.3", "1.21.1", "26.1", "1.21.11"];
        list.sort_by(|a, b| compare_versions(a, b));
        assert_eq!(list, ["1.8.9", "1.21.1", "1.21.10", "1.21.11", "26.1", "26.1.2", "26.3"]);
        let range = Versions { min: Some("1.21.1".into()), max: Some("26.2".into()), list: None };
        assert!(version_fits("1.21.11", Some(&range)) && version_fits("26.2", Some(&range)));
        assert!(!version_fits("1.8.9", Some(&range)) && !version_fits("26.3", Some(&range)));
        assert!(version_fits("1.8.9", Some(&Versions { list: Some(vec!["1.8.9".into()]), ..Default::default() })));
        assert!(version_fits("1.8.9", None));
        assert!(is_version("1.8.9") && is_version("26.3") && !is_version("26") && !is_version("1.8.9-pre1") && !is_version("1..9"));

        assert_eq!(join_args("26.3", "play.example.net:25570", "play.example.net", 25570), ["--quickPlayMultiplayer", "play.example.net:25570"]);
        assert_eq!(join_args("1.21.1", "play.example.net", "play.example.net", 25565), ["--quickPlayMultiplayer", "play.example.net"]);
        assert_eq!(join_args("1.8.9", "play.example.net:25570", "play.example.net", 25570), ["--server", "play.example.net", "--port", "25570"]);
    }

    #[test]
    fn names_written_to_the_server_list_are_plain() {
        assert_eq!(server_name("  §cEmber\u{202e}fall \u{200b}Network\n").as_deref(), Some("cEmberfall Network"));
        assert_eq!(server_name("\u{200b}§"), None);
        assert_eq!(server_name(&"a".repeat(61)), None);
    }
}
