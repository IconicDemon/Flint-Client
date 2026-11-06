/**
 * The cape a launcher preview shows for an account: `useDisplayedCape(uuid)`. One rule everywhere (lib/capeSource.ts):
 * the Flint Store cape the account wears, otherwise its Minecraft cape, otherwise none. Home's character, the Skins page
 * and the Store page's previews all use it, with `createCapeDriver` (lib/capeTexture.ts) on their skinview3d viewer:
 *
 * ```tsx
 * const cape = useDisplayedCape(account.uuid);
 * // in the viewer's setup: driver = createCapeDriver(viewer, requestRender); on change: driver.show(cape)
 * // and driver.setPlaying(onScreen && motionAllowed)
 * ```
 *
 * One shared copy per account (by canonical UUID) for the whole app: the Store's answer, the cape's checked picture and its
 * decoded animation. It follows every Store answer the launcher gets (`storeFeed`: equipping or taking off on the Store page
 * shows at once), asks the Store again when the window comes to the front (an equip in the game or on the website), and
 * forgets an account that signs out of the Store or leaves Flint.
 */
import { useCallback, useEffect, useLayoutEffect, useRef, useSyncExternalStore } from "react";
import { api } from "./api";
import { isPlayable } from "./capeAnimation";
import { chooseCape, createCapeSource, NO_CAPE, sameCape, settleCape, type DisplayedCape, type FlintCapeState, type Settled } from "./capeSource";
import { loadStrips, releaseStrips } from "./capeTexture";
import { useStoreSelect } from "./store";
import { canonicalUuid, storeFeed } from "./storeSync";
import { onWindowFocus } from "./windowFocus";

export type { DisplayedCape } from "./capeSource";
export { createCapeDriver, type CapeDriver } from "./capeTexture";

/** The app's one cape source (exported for the dev tour and tests of the previews). */
export const capeSource = createCapeSource<ImageBitmap>({
  inventory: (uuid) => api.storeInventory(uuid, false),
  changes: (uuid, since) => api.storeChanges(uuid, since),
  poster: (uuid, cosmeticId) => api.storeAsset(uuid, cosmeticId),
  strips: (uuid, cosmeticId, manifest) => loadStrips(manifest, (index) => api.storeCapeStrip(uuid, cosmeticId, index)),
  release: releaseStrips,
  isPlayable,
  now: () => Date.now(),
  later: (callback, ms) => window.setTimeout(callback, ms),
  cancel: (handle) => window.clearTimeout(handle as number),
});

let wired = false;
/** Once, on first use: Store answers from anywhere in the app, and a check when the window comes to the front. */
function wire() {
  if (wired) return;
  wired = true;
  storeFeed.listen((event) => (event.kind === "view" ? capeSource.noteView(event.view) : capeSource.signedOut(event.uuid)));
  onWindowFocus((focused) => focused && capeSource.checkShown());
  window.addEventListener("focus", () => capeSource.checkShown());
}

const NO_FLINT: FlintCapeState<ImageBitmap> = { state: "none" };
const noop = () => () => {};

/**
 * What a preview shows for account `uuid` (any spelling; null: no account, no cape). `minecraftCape` overrides the account's
 * Minecraft cape as Flint last loaded it (a data URL, null for none; undefined to use the account's own), for a page that
 * has a fresher copy. The value is the same object until what it shows changes.
 */
export function useDisplayedCape(uuid: string | null | undefined, options: { minecraftCape?: string | null } = {}): DisplayedCape {
  const id = uuid ? canonicalUuid(uuid) : null;
  const { qa, minecraft, accounts } = useStoreSelect((s) => {
    const account = id ? s.accounts.find((a) => canonicalUuid(a.uuid) === id) : undefined;
    const skin = account ? s.skins[account.uuid] : undefined;
    return {
      qa: !!account?.qa,
      // Until its skin has loaded, the Minecraft cape isn't known (undefined) rather than "none".
      minecraft: skin?.dataUrl ? (skin.capeUrl ?? null) : undefined,
      accounts: s.accounts.map((a) => a.uuid).join(","),
    };
  });

  useEffect(wire, []);
  // An account removed from Flint is forgotten here too.
  useEffect(() => capeSource.keepOnly(accounts ? accounts.split(",") : []), [accounts]);

  const subscribe = useCallback((listener: () => void) => (id ? capeSource.subscribe(id, listener, { store: !qa }) : noop()), [id, qa]);
  const flint = useSyncExternalStore(subscribe, () => (id ? capeSource.state(id) : NO_FLINT));

  const mc = options.minecraftCape !== undefined ? options.minecraftCape : minecraft;
  const choice = id ? chooseCape(flint, mc) : NO_CAPE;
  const settled = useRef<Settled<ImageBitmap> | null>(null);
  const next = settleCape(settled.current, id ?? "", choice);
  // The same object while it shows the same thing, so previews' effects don't run for nothing.
  const value = useRef<DisplayedCape>(next);
  if (!sameCape(value.current, next) || value.current.pending !== next.pending) value.current = next;
  const shown = value.current;
  useLayoutEffect(() => {
    if (!shown.pending && id) settled.current = { uuid: id, cape: shown };
  }, [shown, id]);
  return shown;
}
