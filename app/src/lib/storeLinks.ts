/*
 * Links into the Flint Store website: the most specific page there is, with the Store's own trailing slash, so a link
 * opens (and previews, when shared) without a redirect hop.
 */

/** The Store's catalog for one kind (cape, wings, headwear, aura, pet, shield, gloves, emote); no kind: the Store's home. */
export function storeLink(storeUrl: string, kind?: string | null): string {
  const base = storeUrl.replace(/\/+$/, "");
  return kind && /^[a-z]{1,20}$/.test(kind) ? `${base}/cosmetics/?kind=${kind}` : `${base}/`;
}
