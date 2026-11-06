//! Adding one server to a profile's Multiplayer list (`servers.dat`), for a partner server the player chose to play on
//! (partners/play.rs). The file is read and written with Auto Sync's NBT code (`autosync::gamefiles`, `nbt`), so every
//! existing server and every tag Flint doesn't know stays byte for byte; the write is one transaction with a backup
//! (`apply::commit`). A server already in the visible list is never added again, and no existing entry is ever changed.
//! The caller holds the profile and has made sure its game isn't running.

use super::apply::{self, FileWrite, Hooks, TxnError};
use super::autosync::gamefiles::{self, MAX_FILE, SERVERS_FILE};
use super::autosync::nbt::{Compound, Tag};
use std::path::Path;

/// What adding a server did.
#[derive(Clone, Debug, PartialEq)]
pub enum Listed {
    /// Added at the end of the list.
    Added,
    /// The list already has it (visible): nothing was written. `ip` is the entry's own spelling, which Minecraft's Quick
    /// Play looks the server up by.
    AlreadyListed { ip: String },
}

/// Java's "modified UTF-8", as NBT strings are stored: NUL as two bytes and characters outside the Basic Multilingual
/// Plane as two encoded surrogates. Plain UTF-8 otherwise.
pub fn java_utf8(text: &str) -> Vec<u8> {
    let mut out = Vec::with_capacity(text.len());
    for unit in text.encode_utf16() {
        match unit {
            0x0001..=0x007f => out.push(unit as u8),
            0x0000 | 0x0080..=0x07ff => out.extend_from_slice(&[0xc0 | (unit >> 6) as u8, 0x80 | (unit & 0x3f) as u8]),
            _ => out.extend_from_slice(&[0xe0 | (unit >> 12) as u8, 0x80 | ((unit >> 6) & 0x3f) as u8, 0x80 | (unit & 0x3f) as u8]),
        }
    }
    out
}

/// A server address as the list compares it: case, surrounding spaces, a trailing dot on the host and Minecraft's
/// default port don't matter (`Play.Example.net.:25565` is `play.example.net`).
pub fn same_address(a: &str, b: &str) -> bool {
    fn norm(text: &str) -> String {
        let t = text.trim().to_ascii_lowercase();
        let (host, port) = match t.strip_prefix('[') {
            Some(rest) => match rest.split_once(']') {
                Some((h, tail)) => (format!("[{h}]"), tail.strip_prefix(':').map(str::to_owned)),
                None => (t.clone(), None),
            },
            None => match t.rsplit_once(':') {
                Some((h, p)) if !h.contains(':') => (h.to_owned(), Some(p.to_owned())),
                _ => (t.clone(), None),
            },
        };
        let host = host.trim_end_matches('.').to_owned();
        match port.as_deref() {
            None | Some("25565") => host,
            Some(p) => format!("{host}:{p}"),
        }
    }
    norm(a) == norm(b)
}

/// The profile's server list as it is now: its bytes and servers, or None when it has none. Err when it can't be read.
fn read_list(dir: &Path) -> Result<Option<(Vec<u8>, gamefiles::ServerList)>, String> {
    let path = dir.join(SERVERS_FILE);
    let bytes = match std::fs::metadata(&path) {
        Err(_) => return Ok(None),
        Ok(meta) if meta.len() > MAX_FILE => return Err("This profile's server list is too large for Flint to change.".into()),
        Ok(_) => std::fs::read(&path).map_err(|e| format!("Flint couldn't read this profile's server list: {e}"))?,
    };
    let list = gamefiles::read_servers(&bytes).map_err(|_| "This profile's server list is damaged, so Flint left it as it is.".to_owned())?;
    Ok(Some((bytes, list)))
}

/// The visible entry for `address` in the profile's `servers.dat`, by its own spelling (a missing list lists nothing).
/// Err when the file can't be read.
pub fn listed(dir: &Path, address: &str) -> Result<Option<String>, String> {
    Ok(read_list(dir)?.and_then(|(_, list)| listed_in(&list, address)))
}

/// Adds `name` at `address` to the end of the profile's server list, unless a visible entry already has that address.
/// A server only in the list's hidden part (one Minecraft remembers from Direct Connect, for its resource pack choice) is
/// added visibly. Everything already in the file stays exactly as it was.
pub fn add_server(dir: &Path, name: &str, address: &str) -> Result<Listed, String> {
    let current = read_list(dir)?;
    if let Some(ip) = current.as_ref().and_then(|(_, list)| listed_in(list, address)) {
        return Ok(Listed::AlreadyListed { ip });
    }
    let mut tags: Vec<Compound> = current.as_ref().map(|(_, l)| l.servers.iter().map(|s| s.tag.clone()).collect()).unwrap_or_default();
    // What every version writes for a server the player added: its name and address (1.8.9 reads the same two).
    tags.push(Compound(vec![(b"name".to_vec(), Tag::Str(java_utf8(name))), (b"ip".to_vec(), Tag::Str(java_utf8(address)))]));
    let bytes = gamefiles::write_servers(current.as_ref().map(|(_, l)| &l.root), &tags);
    let expect = current.as_ref().map(|(b, _)| super::backup::sha256(b));
    match apply::commit(dir, &[FileWrite::bytes(SERVERS_FILE, bytes, expect)], "Partner server added", &Hooks::default()) {
        Ok(_) => Ok(Listed::Added),
        Err(TxnError::Conflict(_)) => Err("This profile's server list changed while Flint was adding the server. Try again.".into()),
        Err(TxnError::Refused(why) | TxnError::Failed(why)) => Err(why),
    }
}

fn listed_in(list: &gamefiles::ServerList, address: &str) -> Option<String> {
    list.servers.iter().filter(|s| !gamefiles::hidden(&s.tag)).find_map(|s| {
        let ip = String::from_utf8_lossy(s.tag.text("ip")?).into_owned();
        same_address(&ip, address).then_some(ip)
    })
}

#[cfg(test)]
mod tests {
    use super::super::autosync::nbt::{self, Root};
    use super::*;
    use crate::core::TestDir;

    fn server(name: &str, ip: &str, extra: &[(&str, Tag)]) -> Compound {
        let mut tag = Compound(vec![(b"name".to_vec(), Tag::Str(name.as_bytes().to_vec())), (b"ip".to_vec(), Tag::Str(ip.as_bytes().to_vec()))]);
        for (k, v) in extra {
            tag.0.push((k.as_bytes().to_vec(), v.clone()));
        }
        tag
    }

    fn file(servers: Vec<Compound>) -> Vec<u8> {
        let mut root = Root::default();
        root.body.set("servers", Tag::List(10, servers.into_iter().map(Tag::Compound).collect()));
        // A tag of the file's own that Flint doesn't know.
        root.body.set("modData", Tag::IntArray(vec![7, 8]));
        nbt::write(&root)
    }

    fn servers_in(dir: &Path) -> Vec<Compound> {
        gamefiles::read_servers(&std::fs::read(dir.join(SERVERS_FILE)).unwrap()).unwrap().servers.into_iter().map(|s| s.tag).collect()
    }

    #[test]
    fn a_server_is_added_after_the_existing_ones_which_stay_exactly_as_they_were() {
        let dir = TestDir::new();
        let icon = Tag::Str(b"iVBORw0KGgo=".to_vec());
        let before = vec![
            server("Home", "home.example.net", &[("icon", icon.clone()), ("acceptTextures", Tag::Byte(1))]),
            server("Hidden", "old.example.net", &[("hidden", Tag::Byte(1))]),
            server("Friend", "friend.example.org:25570", &[("acceptedCodeOfConduct", Tag::Int(-1))]),
        ];
        let original = file(before.clone());
        std::fs::write(dir.0.join(SERVERS_FILE), &original).unwrap();

        assert_eq!(add_server(&dir.0, "Emberfall Network", "play.emberfall.example").unwrap(), Listed::Added);
        let after = servers_in(&dir.0);
        assert_eq!(after.len(), 4);
        assert_eq!(&after[..3], &before[..], "every existing entry, tag for tag");
        assert_eq!(after[3], server("Emberfall Network", "play.emberfall.example", &[]));
        let root = nbt::read(&std::fs::read(dir.0.join(SERVERS_FILE)).unwrap()).unwrap();
        assert_eq!(root.body.get("modData"), Some(&Tag::IntArray(vec![7, 8])), "the file's other tags stay");
        // The old file is in the profile's backups.
        let backups = super::super::backup::list(&dir.0);
        assert_eq!(backups.len(), 1);
        assert!(backups[0].reason.contains("Partner server added"));

        // Asked again: nothing is written.
        let written = std::fs::read(dir.0.join(SERVERS_FILE)).unwrap();
        assert_eq!(add_server(&dir.0, "Emberfall Network", "Play.Emberfall.Example.:25565").unwrap(), Listed::AlreadyListed { ip: "play.emberfall.example".into() });
        assert_eq!(std::fs::read(dir.0.join(SERVERS_FILE)).unwrap(), written);
        assert_eq!(super::super::backup::list(&dir.0).len(), 1);
    }

    #[test]
    fn a_server_already_listed_under_another_name_is_left_alone() {
        let dir = TestDir::new();
        let original = file(vec![server("My PvP", "Friend.Example.org:25570", &[])]);
        std::fs::write(dir.0.join(SERVERS_FILE), &original).unwrap();
        assert_eq!(add_server(&dir.0, "Friend", "friend.example.org:25570").unwrap(), Listed::AlreadyListed { ip: "Friend.Example.org:25570".into() });
        assert_eq!(std::fs::read(dir.0.join(SERVERS_FILE)).unwrap(), original, "not a byte changed");
        assert_eq!(listed(&dir.0, "friend.example.org:25570").unwrap().as_deref(), Some("Friend.Example.org:25570"));
        assert_eq!(listed(&dir.0, "friend.example.org").unwrap(), None, "another port is another server");
    }

    #[test]
    fn a_server_only_remembered_as_hidden_is_added_visibly() {
        let dir = TestDir::new();
        let hidden = server("Minecraft Server", "play.emberfall.example", &[("hidden", Tag::Byte(1))]);
        std::fs::write(dir.0.join(SERVERS_FILE), file(vec![hidden.clone()])).unwrap();
        assert_eq!(add_server(&dir.0, "Emberfall Network", "play.emberfall.example").unwrap(), Listed::Added);
        let after = servers_in(&dir.0);
        assert_eq!(after[0], hidden);
        assert_eq!(after[1], server("Emberfall Network", "play.emberfall.example", &[]));
    }

    #[test]
    fn a_profile_without_a_list_gets_one_and_a_damaged_list_is_never_replaced() {
        let dir = TestDir::new();
        assert_eq!(listed(&dir.0, "a.example").unwrap(), None);
        assert_eq!(add_server(&dir.0, "Skyhollow SMP", "mc.skyhollow.example:25570").unwrap(), Listed::Added);
        assert_eq!(servers_in(&dir.0), vec![server("Skyhollow SMP", "mc.skyhollow.example:25570", &[])]);

        let damaged = b"\x0a\x00\x00\x09\x00\x07servers".to_vec();
        std::fs::write(dir.0.join(SERVERS_FILE), &damaged).unwrap();
        assert!(add_server(&dir.0, "X", "x.example").unwrap_err().contains("damaged"));
        assert!(listed(&dir.0, "x.example").is_err());
        assert_eq!(std::fs::read(dir.0.join(SERVERS_FILE)).unwrap(), damaged);
    }

    #[test]
    fn names_are_written_as_java_reads_them() {
        assert_eq!(java_utf8("Ember"), b"Ember");
        assert_eq!(java_utf8("é"), "é".as_bytes());
        assert_eq!(java_utf8("\0"), [0xc0, 0x80]);
        // U+1F525 (fire) as two surrogates of three bytes each, not four-byte UTF-8.
        assert_eq!(java_utf8("🔥"), [0xed, 0xa0, 0xbd, 0xed, 0xb4, 0xa5]);
        let dir = TestDir::new();
        add_server(&dir.0, "Ember 🔥", "play.emberfall.example").unwrap();
        assert_eq!(servers_in(&dir.0)[0].text("name"), Some(&b"Ember \xed\xa0\xbd\xed\xb4\xa5"[..]));
    }

    #[test]
    fn addresses_compare_without_case_dots_or_the_default_port() {
        assert!(same_address("Play.Example.net.", "play.example.net:25565"));
        assert!(same_address(" play.example.net ", "play.example.net"));
        assert!(!same_address("play.example.net:25566", "play.example.net"));
        assert!(same_address("[2606:4700::1]:25565", "[2606:4700::1]"));
        assert!(!same_address("a.example.net", "b.example.net"));
    }
}
