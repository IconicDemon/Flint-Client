import type { Profile } from "./api";

/** How many profiles the sidebar lists under Profiles before "View more". */
export const SIDEBAR_PROFILES = 5;

/**
 * The profiles the sidebar lists: the pinned ones first, in the order they were pinned, then the most played (time played
 * through Flint, then the most recently played, then the newest), at most {@link SIDEBAR_PROFILES}. `more` says whether
 * there are others to see on the Profiles page.
 */
export function sidebarProfiles(profiles: readonly Profile[]): { shown: Profile[]; more: boolean } {
  const pinned = profiles.filter((p) => p.pinnedAt).sort((a, b) => (a.pinnedAt ?? 0) - (b.pinnedAt ?? 0));
  const rest = profiles
    .filter((p) => !p.pinnedAt)
    .sort((a, b) => (b.playSeconds ?? 0) - (a.playSeconds ?? 0) || (b.lastPlayed ?? 0) - (a.lastPlayed ?? 0) || b.createdAt - a.createdAt);
  const shown = [...pinned, ...rest].slice(0, SIDEBAR_PROFILES);
  return { shown, more: profiles.length > shown.length };
}
