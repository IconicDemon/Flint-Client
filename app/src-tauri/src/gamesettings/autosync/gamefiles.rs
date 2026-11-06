//! The game's own lists Auto Sync can share (each off until turned on in Settings → Syncing). Each format was
//! checked in the game's code (26.3's unobfuscated jar; the 1.21.1 and 1.8.9 client jars by their strings and
//! bytecode):
//!
//! - Multiplayer servers, `servers.dat`: uncompressed NBT, a `servers` list of compounds. Every version writes
//!   `name`, `ip`, `icon` (base64 PNG) and `acceptTextures`; 1.21.x and 26.x add `hidden` (servers joined by
//!   address, remembered for their resource pack choice) and 1.21.10+ `acceptedCodeOfConduct`. 1.8.9 reads the
//!   same file and ignores the tags it doesn't know, so the list is shared with every version. Servers are
//!   synced one by one (by address) plus their order, so two profiles adding different servers both keep
//!   theirs; hidden servers never go to 1.8.9, which would show them.
//! - Saved creative hotbars, `hotbar.nbt` (1.12+, none in 1.8.9): uncompressed NBT with a `DataVersion`.
//!   Minecraft upgrades an older file as it loads it (`DataFixTypes.HOTBAR`) but can't read a newer version's
//!   items, and 1.20.5 moved items to components; so the file goes only to a profile of the same or a newer
//!   version, both from 1.20.5 on.
//! - Command history, `command_history.txt` (1.19.3+, none in 1.8.9): the last 50 commands, one per line in
//!   UTF-8 (`CommandHistory`), written with the computer's line endings. Server login commands carry passwords
//!   (`/login <password>`, `/register ...`): they are left out of what is stored and copied, and each profile keeps
//!   its own.

use super::nbt::{self, Compound, Root, Tag};

pub const SERVERS_FILE: &str = "servers.dat";
pub const HOTBAR_FILE: &str = "hotbar.nbt";
pub const COMMANDS_FILE: &str = "command_history.txt";
/// Larger files aren't synced (hundreds of servers with icons stay far below).
pub const MAX_FILE: u64 = 4 * 1024 * 1024;
/// 1.8.9 refuses NBT that adds up to over 2 MB as it reads it (`NBTSizeTracker`, which counts strings twice);
/// a list written there stays under half of that.
pub const MAX_LEGACY_SERVERS: usize = 1024 * 1024;
/// Minecraft 1.20.5's data version: items are stored as components from there on.
pub const COMPONENT_ITEMS: u32 = 3837;
/// The data version Minecraft assumes for a hotbar file that has none (`HotbarManager`: 1343, 1.12.2).
const UNVERSIONED_HOTBAR: i32 = 1343;

/// Tags the profiles share for a server, as every version reads them; anything else (26.x's
/// `acceptedCodeOfConduct`, a mod's own) stays with each profile.
const SHARED: &[&str] = &["name", "ip", "icon", "acceptTextures", "hidden"];

fn sha(bytes: &[u8]) -> String {
    super::super::backup::sha256(bytes)
}

#[derive(Clone, Debug, PartialEq)]
pub struct Server {
    /// Its address, hashed (with "-2", "-3"... for the same address listed again).
    pub id: String,
    pub tag: Compound,
}

#[derive(Clone, Debug, PartialEq)]
pub struct ServerList {
    pub root: Root,
    pub servers: Vec<Server>,
}

/// Reads servers.dat whole; a damaged file, or one whose `servers` isn't a list of servers, is refused.
pub fn read_servers(bytes: &[u8]) -> Result<ServerList, String> {
    let root = nbt::read(bytes)?;
    let items: &[Tag] = match root.body.get("servers") {
        None => &[],
        Some(Tag::List(_, items)) => items,
        Some(_) => return Err("servers isn't a list".into()),
    };
    let mut servers = Vec::new();
    let mut seen: Vec<String> = Vec::new();
    for item in items {
        let Tag::Compound(tag) = item else { return Err("a server that isn't a compound".into()) };
        let address = String::from_utf8_lossy(tag.text("ip").unwrap_or_default()).trim().to_lowercase();
        let base = sha(address.as_bytes())[..16].to_owned();
        let mut id = base.clone();
        let mut n = 1;
        while seen.contains(&id) {
            n += 1;
            id = format!("{base}-{n}");
        }
        seen.push(id.clone());
        servers.push(Server { id, tag: tag.clone() });
    }
    Ok(ServerList { root, servers })
}

pub fn hidden(tag: &Compound) -> bool {
    matches!(tag.get("hidden"), Some(Tag::Byte(b)) if *b != 0)
}

/// A server's comparison form: the shared tags as Minecraft reads them (no `hidden` is the same as false), so
/// 1.8.9 saving the list without the tags it doesn't know isn't a change.
pub fn server_fp(tag: &Compound) -> String {
    let mut shared = Compound::default();
    for name in SHARED {
        if *name == "hidden" && !hidden(tag) {
            continue;
        }
        if let Some(value) = tag.get(name) {
            shared.set(name, value.clone());
        }
    }
    sha(&nbt::write(&Root { name: Vec::new(), body: shared }))
}

/// A server as the store keeps it: a small NBT file of its own.
pub fn server_bytes(tag: &Compound) -> Vec<u8> {
    nbt::write(&Root { name: Vec::new(), body: tag.clone() })
}

pub fn server_from(bytes: &[u8]) -> Option<Compound> {
    nbt::read(bytes).ok().map(|root| root.body)
}

/// The stored server as it goes into a profile: its shared tags as stored, plus the profile's own tags the
/// stored copy doesn't have (an icon or a resource pack choice that was removed stays removed; `hidden`
/// and anything only some versions write stay as the profile has them).
pub fn merged(stored: &Compound, local: Option<&Compound>) -> Compound {
    let mut out = stored.clone();
    for (name, tag) in local.map(|l| l.0.as_slice()).unwrap_or_default() {
        let removable = SHARED.iter().any(|s| s.as_bytes() == name.as_slice()) && name.as_slice() != b"hidden";
        if !removable && out.0.iter().all(|(n, _)| n != name) {
            out.0.push((name.clone(), tag.clone()));
        }
    }
    out
}

/// servers.dat holding these servers, with the file's other tags kept.
pub fn write_servers(base: Option<&Root>, servers: &[Compound]) -> Vec<u8> {
    let mut root = base.cloned().unwrap_or_default();
    root.body.set("servers", Tag::List(10, servers.iter().cloned().map(Tag::Compound).collect()));
    nbt::write(&root)
}

/// The data version a hotbar file was saved with, when it is a whole hotbar file.
pub fn hotbar_version(bytes: &[u8]) -> Option<i32> {
    let root = nbt::read(bytes).ok()?;
    Some(match root.body.get("DataVersion") {
        Some(Tag::Int(v)) => *v,
        _ => UNVERSIONED_HOTBAR,
    })
}

/// Whether Minecraft at data version `target` reads hotbars saved at `saved` (see the module notes).
pub fn hotbar_fits(saved: i32, target: Option<u32>) -> Result<(), &'static str> {
    let Some(target) = target else { return Err("this profile's Minecraft version isn't known yet") };
    let saved = u32::try_from(saved).unwrap_or(0);
    if saved < COMPONENT_ITEMS || target < COMPONENT_ITEMS {
        return Err("hotbars from before Minecraft 1.20.5 store items another way");
    }
    if saved > target {
        return Err("saved by a newer Minecraft version, whose items this one can't read");
    }
    Ok(())
}

/// The command history's comparison form: its lines, whatever line endings the computer writes.
pub fn commands_fp(bytes: &[u8]) -> String {
    let text = String::from_utf8_lossy(bytes).replace("\r\n", "\n");
    sha(text.as_bytes())
}

/// Server login commands (AuthMe, LibreLogin, nLogin and the like) carry passwords, so they never leave the profile
/// they were typed in: not into the store, not into another profile. Short aliases match exactly; any command whose name
/// holds one of `PRIVATE_PARTS` counts too, with a plugin's prefix as well (`/authme:login`). A command kept private by
/// mistake only stays in its own profile, so the net is wide.
const PRIVATE_COMMANDS: &[&str] = &["l", "log", "reg", "cp", "premium", "unreg", "mail", "email", "logar", "registrar"];
const PRIVATE_PARTS: &[&str] = &["login", "logon", "regist", "pass", "pw", "2fa", "totp", "auth"];

pub fn private_command(line: &str) -> bool {
    let line = line.trim_start();
    let word = line.strip_prefix('/').unwrap_or(line).split_whitespace().next().unwrap_or_default().to_ascii_lowercase();
    let name = word.rsplit(':').next().unwrap_or_default();
    PRIVATE_COMMANDS.contains(&name) || PRIVATE_PARTS.iter().any(|part| name.contains(part))
}

/// The command history as profiles share it: every line but login commands, each with its own line ending.
pub fn shared_commands(bytes: &[u8]) -> Vec<u8> {
    let text = String::from_utf8_lossy(bytes);
    text.split_inclusive('\n').filter(|line| !private_command(line)).collect::<String>().into_bytes()
}

/// The shared history as it goes into a profile: the stored lines, then that profile's own login commands (its
/// latest), which never leave it.
pub fn commands_for(stored: &[u8], current: Option<&[u8]>) -> Vec<u8> {
    let mut out = stored.to_vec();
    let own = String::from_utf8_lossy(current.unwrap_or_default()).into_owned();
    for line in own.split_inclusive('\n').filter(|line| private_command(line)) {
        if !out.is_empty() && !out.ends_with(b"\n") {
            out.extend_from_slice(if stored.windows(2).any(|w| w == b"\r\n") { b"\r\n" } else { b"\n" });
        }
        out.extend_from_slice(line.as_bytes());
    }
    out
}

#[cfg(test)]
pub mod testdata {
    use super::*;

    pub fn server(name: &str, ip: &str, extra: &[(&str, Tag)]) -> Compound {
        let mut tag = Compound(vec![(b"name".to_vec(), Tag::Str(name.as_bytes().to_vec())), (b"ip".to_vec(), Tag::Str(ip.as_bytes().to_vec()))]);
        for (k, v) in extra {
            tag.set(k, v.clone());
        }
        tag
    }

    /// servers.dat as Minecraft writes it: 26.x adds `hidden` to each server (and 1.21.10+ the code of conduct
    /// it accepted), 1.8.9 writes only name, ip, icon and acceptTextures.
    pub fn servers_dat(servers: &[Compound]) -> Vec<u8> {
        write_servers(None, servers)
    }

    /// hotbar.nbt as Minecraft saves it: the nine rows and the data version.
    pub fn hotbar(data_version: i32, item: &str) -> Vec<u8> {
        let stack = Compound(vec![(b"id".to_vec(), Tag::Str(item.as_bytes().to_vec())), (b"count".to_vec(), Tag::Int(1))]);
        let mut body = Compound::default();
        for row in 0..9 {
            body.set(&row.to_string(), Tag::List(10, vec![Tag::Compound(stack.clone())]));
        }
        body.set("DataVersion", Tag::Int(data_version));
        nbt::write(&Root { name: Vec::new(), body })
    }
}

#[cfg(test)]
mod tests {
    use super::testdata::*;
    use super::*;

    #[test]
    fn servers_are_known_by_address_and_compared_by_what_every_version_reads() {
        let modern = server("Hub", "Hub.Example.invalid ", &[("hidden", Tag::Byte(0)), ("acceptedCodeOfConduct", Tag::Int(3))]);
        let legacy = server("Hub", "hub.example.invalid", &[]);
        let list = read_servers(&servers_dat(&[modern.clone(), legacy.clone(), server("Other", "other.invalid", &[])])).unwrap();
        assert_eq!(list.servers.len(), 3);
        assert_eq!(list.servers[1].id, format!("{}-2", list.servers[0].id), "the same address listed twice");
        assert_ne!(list.servers[2].id, list.servers[0].id);
        assert_eq!(server_fp(&modern), server_fp(&server("Hub", "Hub.Example.invalid ", &[])), "1.8.9 dropping tags it doesn't know isn't a change");
        assert_ne!(server_fp(&modern), server_fp(&server("Hub 2", "Hub.Example.invalid ", &[])));
        assert_ne!(server_fp(&legacy), server_fp(&server("Hub", "hub.example.invalid", &[("hidden", Tag::Byte(1))])));
        assert_eq!(server_from(&server_bytes(&modern)), Some(modern.clone()));
        assert!(read_servers(b"not nbt").is_err());
        assert!(read_servers(&nbt::write(&Root { name: Vec::new(), body: Compound(vec![(b"servers".to_vec(), Tag::Int(1))]) })).is_err());
        assert!(read_servers(&nbt::write(&Root::default())).unwrap().servers.is_empty(), "no list: no servers");
    }

    #[test]
    fn a_stored_server_keeps_what_only_this_profile_writes() {
        let icon = ("icon", Tag::Str(b"iVBORw0KGgo=".to_vec()));
        let mine = server("Hub", "hub.invalid", &[icon.clone(), ("acceptTextures", Tag::Byte(1)), ("hidden", Tag::Byte(0)), ("acceptedCodeOfConduct", Tag::Int(7))]);
        // Renamed in 1.8.9, where its icon was also removed and its pack choice went back to asking.
        let stored = server("Hub (EU)", "hub.invalid", &[]);
        let out = merged(&stored, Some(&mine));
        assert_eq!(out.text("name"), Some(b"Hub (EU)".as_slice()));
        assert_eq!((out.get("icon"), out.get("acceptTextures")), (None, None));
        assert_eq!((out.get("hidden"), out.get("acceptedCodeOfConduct")), (Some(&Tag::Byte(0)), Some(&Tag::Int(7))));
        assert_eq!(merged(&stored, None), stored);
        // The file keeps tags Flint doesn't know.
        let mut root = Root::default();
        root.body.set("someMod", Tag::Int(5));
        let back = read_servers(&write_servers(Some(&root), &[out])).unwrap();
        assert_eq!(back.root.body.get("someMod"), Some(&Tag::Int(5)));
    }

    #[test]
    fn hotbars_go_only_where_minecraft_can_read_them() {
        assert_eq!(hotbar_version(&hotbar(4671, "minecraft:stone")), Some(4671));
        assert_eq!(hotbar_version(b"\x1f\x8b"), None);
        assert_eq!(hotbar_fits(4671, Some(4671)), Ok(()));
        assert_eq!(hotbar_fits(3955, Some(5023)), Ok(()), "1.21.1's file into 26.3: Minecraft upgrades it");
        assert!(hotbar_fits(5023, Some(3955)).is_err(), "26.3's file into 1.21.1");
        assert!(hotbar_fits(3465, Some(5023)).is_err(), "1.20.1's items predate components");
        assert!(hotbar_fits(4671, None).is_err(), "1.8.9 and versions not installed");
        assert_eq!(commands_fp(b"/gamemode creative\r\n/time set day\r\n"), commands_fp(b"/gamemode creative\n/time set day\n"));
    }

    #[test]
    fn login_commands_never_leave_their_profile() {
        let typed = b"/gamemode creative\r\n/login hunter2\r\n/L hunter2\r\n/REGISTER a a\r\n/reg a a\r\n/changepassword a b\r\n/changepw a b\r\n/cp a b\r\n/premium\r\n/unregister a\r\n/email add x@y.z x@y.z\r\n/tp 0 64 0\r\n/lobby\r\n/clear\r\n";
        let shared = shared_commands(typed);
        assert_eq!(shared, b"/gamemode creative\r\n/tp 0 64 0\r\n/lobby\r\n/clear\r\n", "only login commands are left out (/lobby and /clear aren't)");
        assert!(!String::from_utf8_lossy(&shared).contains("hunter2"));
        // Other plugins' aliases and prefixed forms (AuthMe, LibreLogin, nLogin): all kept private.
        for line in ["/log hunter2", "/changepass a b", "/passwd a", "/unreg a", "/mail add x", "/authme:login a", "/librelogin:register a a", "/2fa 123456", "/totp 1", "/logar a", "/registrar a a", "/AUTH a"] {
            assert!(private_command(line), "{line}");
        }
        for line in ["/gamemode creative", "/lobby", "/clear", "/time set day", "/give @s stone", "/locate structure village"] {
            assert!(!private_command(line), "{line}");
        }
        // Another profile keeps its own login commands, after the shared lines.
        let theirs = b"/give @s stone\n/login mine\n";
        assert_eq!(commands_for(b"/time set day\n", Some(theirs)), b"/time set day\n/login mine\n");
        assert_eq!(commands_for(b"/time set day\r\n", None), b"/time set day\r\n");
        assert_eq!(commands_for(b"/time set day", Some(b"/login mine\n")), b"/time set day\n/login mine\n");
    }
}
