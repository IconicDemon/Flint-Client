// Role badges (src-tauri/src/store/badges.rs): the marks an account owns ("default" first, then the Store's rank order)
// and the one other players see. A Store that doesn't send them yet answers `known: false`: the Flint mark only.

export type BadgeTag = "default" | "owner" | "co_owner" | "developer" | "staff" | "content_creator" | "trial_creator" | "vip" | "special";

export type Badges = { known: boolean; badges: string[]; badge: string };

export const TAGS: readonly BadgeTag[] = ["default", "owner", "co_owner", "developer", "staff", "content_creator", "trial_creator", "vip", "special"];

export const UNKNOWN: Badges = { known: false, badges: ["default"], badge: "default" };

export const isTag = (tag: string): tag is BadgeTag => (TAGS as readonly string[]).includes(tag);

/** The owned marks Flint can show, "default" first and once, and a worn one that is among them (else "default"). */
export function shown(b: Badges | null | undefined): { badges: BadgeTag[]; badge: BadgeTag } {
  const badges: BadgeTag[] = ["default"];
  for (const tag of b?.badges ?? []) if (isTag(tag) && !badges.includes(tag)) badges.push(tag);
  const badge = b && isTag(b.badge) && badges.includes(b.badge) ? b.badge : "default";
  return { badges, badge };
}

/**
 * One Equip: shown at once (optimistic), then the Store's answer; a refusal puts the previous badge back. `onChange` gets
 * every state to show; the promise ends with the error text, or null when the Store took it.
 */
export async function equipBadge(current: Badges, tag: BadgeTag, put: (tag: BadgeTag) => Promise<Badges>, onChange: (b: Badges) => void): Promise<string | null> {
  onChange({ ...current, badge: tag });
  try {
    onChange(await put(tag));
    return null;
  } catch (error) {
    onChange(current);
    return typeof error === "string" ? error : error instanceof Error ? error.message : String(error);
  }
}
