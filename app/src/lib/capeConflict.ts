/*
 * What wearing a Flint Store cape on the Store page says while a mod that shows its own capes is on in one of the
 * player's profiles (src-tauri/src/capemods.rs): which mods, in which profiles, that they can hide the Flint cape there
 * (they put their own in its place when their service has one for the player), and that turning them off brings it back.
 * The cape is worn all the same (it's the account's). Shown once per visit to the Store page for the same mods in the same
 * profiles.
 */
import { list } from "./format.ts";
import type { Key, Vars } from "./i18n.ts";

/** A profile where a cape mod is on (capemods.rs `CapeConflict`): selected profile first, then the latest played. */
export type CapeConflict = { profileId: string; profileName: string; gameVersion: string; mods: string[] };

/** A message to translate. `strong`: its placeholders shown in bold. */
export type Message = { key: Key; vars: Vars; strong?: string[] };

export type CapeNotice = {
  title: Message;
  /** One profile: the whole message. Several: what the list under it is. */
  body: Message;
  /** Several profiles: each with its cape mods ("Capes and Essential"). */
  rows: { profileId: string; profile: string; mods: string }[];
  /** "And 2 more profiles", for those past the list. */
  more: Message | null;
  /** Several profiles: what to do. */
  outro: Message | null;
};

/** Profiles listed by name; the rest are counted. */
export const LISTED = 4;

/** The notice for these conflicts (at least one). */
export function capeNotice(conflicts: CapeConflict[]): CapeNotice {
  const mods = [...new Set(conflicts.flatMap((c) => c.mods))];
  const title: Message = { key: "store.capeConflict.title", vars: { count: mods.length } };
  if (conflicts.length === 1) {
    const [only] = conflicts;
    return {
      title,
      body: { key: "store.capeConflict.body", vars: { count: only.mods.length, mods: list(only.mods), profile: only.profileName }, strong: ["mods", "profile"] },
      rows: [],
      more: null,
      outro: null,
    };
  }
  const rows = conflicts.slice(0, LISTED).map((c) => ({ profileId: c.profileId, profile: c.profileName, mods: list(c.mods) }));
  const hidden = conflicts.length - rows.length;
  return {
    title,
    body: { key: "store.capeConflict.several", vars: {} },
    rows,
    more: hidden > 0 ? { key: "store.capeConflict.more", vars: { count: hidden } } : null,
    outro: { key: "store.capeConflict.turnOff", vars: { count: mods.length } },
  };
}

/** Which notice this is: the same mods in the same profiles give the same key. */
export const capeNoticeKey = (conflicts: CapeConflict[]) => conflicts.map((c) => `${c.profileId}:${c.mods.join(",")}`).join("|");
