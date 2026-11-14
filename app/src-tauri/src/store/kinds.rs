//! The Store's cosmetic kinds and the slot each one is worn in: the one place in Rust that names them. The Store owns the
//! names (its cosmetics v2 contract, `FlintQA/release-104/cosmetics-contract-website.md`); a rename there is a one-line
//! change here. The interface has the same table in `src/lib/cosmeticTypes.ts`.
//!
//! A kind Flint doesn't know yet (a newer Store) is kept, never refused: the launcher shows it under "More", and it isn't
//! worn from here until Flint knows its slot.

/// The slots the Store knows (`PUT /api/v1/me/equipped/`): one item worn in each.
pub const SLOTS: [&str; 7] = ["cape", "back", "head", "aura", "pet", "shield", "hands"];

/// Every kind the Store sends (the contract's "Update 2": 8 kinds, no bodywear), and its slot. An emote isn't worn in a
/// slot: it goes on the emote wheel.
pub const KINDS: [(&str, Option<&str>); 8] = [
    ("cape", Some("cape")),
    ("wings", Some("back")),
    ("headwear", Some("head")),
    ("aura", Some("aura")),
    ("pet", Some("pet")),
    ("shield", Some("shield")),
    ("gloves", Some("hands")),
    ("emote", None),
];

/// The emote kind, and how many places its wheel has (`inventory.emotes`, `PUT /api/v1/me/emotes/`).
pub const EMOTE: &str = "emote";
pub const EMOTE_SLOTS: usize = 8;

/// Names an older Store used for today's kinds (the v2 migration renames them).
const ALIASES: [(&str, &str); 2] = [("back", "wings"), ("head", "headwear")];

/// The kind as Flint names it: an older name read as today's, anything else as it came when it is a plain token
/// (`a-z`, `0-9`, `_`, at most 32), else none (the item is left out).
pub fn normalize(kind: &str) -> Option<String> {
    let kind = kind.trim();
    if let Some((_, now)) = ALIASES.iter().find(|(old, _)| *old == kind) {
        return Some((*now).to_owned());
    }
    let token = !kind.is_empty() && kind.len() <= 32 && kind.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_');
    token.then(|| kind.to_owned())
}

/// The slot a kind is worn in; none for an emote or a kind Flint doesn't know.
pub fn slot_of(kind: &str) -> Option<&'static str> {
    KINDS.iter().find(|(k, _)| *k == kind).and_then(|(_, slot)| *slot)
}

/// Whether Flint knows the kind (it may still have no slot: an emote).
pub fn is_known(kind: &str) -> bool {
    KINDS.iter().any(|(k, _)| *k == kind)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_kind_has_one_slot_and_older_names_read_as_todays() {
        for slot in SLOTS {
            assert_eq!(KINDS.iter().filter(|(_, s)| *s == Some(slot)).count(), 1, "{slot}");
        }
        assert_eq!(slot_of("wings"), Some("back"));
        assert_eq!(slot_of("gloves"), Some("hands"));
        assert_eq!(slot_of("emote"), None);
        assert!(!is_known("bodywear") && slot_of("bodywear").is_none(), "no bodywear this release");
        assert_eq!(normalize("back").as_deref(), Some("wings"));
        assert_eq!(normalize("head").as_deref(), Some("headwear"));
        assert_eq!(normalize("jetpack_v2").as_deref(), Some("jetpack_v2"));
        assert!(normalize("Boots").is_none() && normalize("").is_none() && normalize(&"x".repeat(33)).is_none() && normalize("a b").is_none());
        assert!(is_known("emote") && !is_known("jetpack_v2"));
    }
}
