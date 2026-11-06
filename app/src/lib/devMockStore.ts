// Browser-only development stand-in for the Flint Store (src-tauri/src/store), used by devMock.ts. Never bundled into
// production builds. Nothing here is a real product. `?store=` takes comma-separated flags:
// - in: every account starts signed in (otherwise "Sign in to the Store" signs in).
// - empty: owns nothing. many: owns 300 items (capes, back, head, aura; long names, gifts, test items).
// - anim: also owns animated capes, and wears one. (By default it owns devMockCapes.ts's test capes: static, HD, animated.)
// - orders: 500 orders and 60 Merit movements (the real Store sends at most 50 orders; the page pages them itself).
// - offline: the Store can't be reached; the last copy shows, read only. unreachable: the same with no copy.
// - error: the Store page can't be loaded at all. locked: the system refuses to read the saved sign-in.
// - slow: every Store call takes 2.5 s. wearfail: wearing is refused. ordersfail: orders can't be loaded.
// - staging: connected to a test Store. badcode: a browser code isn't waiting at the Store.
// `&cape=static|frost|hd|animated|animatedhd` starts the active account signed in wearing that test cape; `&mccape`
// (devMock.ts) gives it a Minecraft cape.
import { CAPE_IDS, CAPE_ITEMS, CAPE_PARAM, capeAnimation, capeAsset, capeStrip } from "./devMockCapes";

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
const DAY = 86_400_000;

export type Item = {
  cosmeticId: string;
  slug: string;
  kind: "cape" | "back" | "head" | "aura";
  name: string;
  test: boolean;
  gift: boolean;
  giftFrom?: string | null;
  giftMessage?: string | null;
  grantedAt: string | null;
  asset: { id: string; version: number; width: number; height: number; animation?: unknown } | null;
  /** Mock only: how the texture is drawn (`testCape`: by devMockCapes.ts). */
  art: { hue: number; animated?: { frames: number; scale: number; durations: number[] }; testCape?: boolean };
  /** A newer Store's fields (devMockCommunity.ts). */
  source?: string;
  obtainedVia?: string;
  permanence?: "permanent" | "conditional";
  state?: "owned" | "eligible" | "unavailable";
};

/**
 * What devMockCommunity.ts adds to the Store mock: items an account has from community rewards right now (and gifts), and
 * the fields a newer Store sends on every item. Without it the mock is today's Store.
 */
export type StoreExtension = {
  extra(uuid: string): Item[];
  decorate(uuid: string, item: Item): Partial<Item>;
};

const id = (n: number) => `00000000-0000-4000-8000-${n.toString(16).padStart(12, "0")}`;

const ADJECTIVES = ["Ember", "Frost", "Aurora", "Midnight", "Copper", "Verdant", "Obsidian", "Solar", "Lunar", "Tidal", "Crimson", "Amber", "Glacier", "Thunder", "Velvet", "Ashen", "Golden", "Prism"];
const NOUNS: Record<Item["kind"], string[]> = {
  cape: ["Cape", "Mantle", "Banner Cape", "Cloak", "Drape"],
  back: ["Wings", "Pack", "Quiver", "Sheath"],
  head: ["Crown", "Halo", "Circlet", "Horns"],
  aura: ["Aura", "Sparkle", "Embers", "Mist"],
};
const LONG = "The Extraordinarily Long Name of the Midnight Aurora Borealis Cape of the Northern Reaches";

export function animatedArt(frames: number, scale: number) {
  return { frames, scale, durations: Array.from({ length: frames }, (_, f) => [90, 60, 60, 90, 140, 60][f % 6]) };
}

export function manifest(art: NonNullable<Item["art"]["animated"]>) {
  const height = 17 * art.scale;
  const per = Math.max(1, Math.floor(2048 / height));
  return {
    width: 22 * art.scale,
    height,
    scale: art.scale,
    frameCount: art.frames,
    durationsMs: art.durations,
    durationMs: art.durations.reduce((a, b) => a + b, 0),
    framesPerStrip: per,
    strips: Array.from({ length: Math.ceil(art.frames / per) }, (_, i) => ({ frames: Math.min(per, art.frames - i * per) })),
  };
}

function item(n: number, kind: Item["kind"], name: string, over: Partial<Item> = {}): Item {
  const hue = (n * 47) % 360;
  const art = over.art ?? { hue };
  return {
    cosmeticId: id(n),
    slug: name.toLowerCase().replace(/[^a-z0-9]+/g, "-"),
    kind,
    name,
    test: false,
    gift: false,
    grantedAt: new Date(Date.now() - n * 0.37 * DAY).toISOString(),
    asset: { id: id(0xa000 + n), version: 1, width: 64, height: 32, animation: art.animated ? manifest(art.animated) : null },
    ...over,
    art,
  };
}

const testCapes = (): Item[] =>
  CAPE_ITEMS.map((c, i) => {
    const animation = capeAnimation(c.cosmeticId);
    const scale = animation ? Math.min(16, animation.scale) : c.scale;
    return {
      cosmeticId: c.cosmeticId,
      slug: c.slug,
      kind: "cape",
      name: c.name,
      test: true,
      gift: !!c.gift,
      giftFrom: c.gift ?? null,
      grantedAt: new Date(Date.now() - (i + 1) * 0.5 * DAY).toISOString(),
      asset: { id: `a${i + 1}`, version: 1, width: 64 * scale, height: 32 * scale, animation },
      art: { hue: 0, testCape: true },
    };
  });

function inventoryFor(flags: Set<string>): Item[] {
  if (flags.has("empty")) return [];
  const items: Item[] = [];
  if (!flags.has("many")) items.push(...testCapes());
  if (flags.has("anim")) {
    items.push(item(1, "cape", "Aurora Borealis", { art: { hue: 160, animated: animatedArt(16, 8) } }));
    items.push(item(2, "cape", "Ember Storm", { art: { hue: 18, animated: animatedArt(12, 16) }, gift: true, giftFrom: "Kestrel", giftMessage: "Happy launch day!" }));
    items.push(item(3, "cape", "Tidal Shimmer", { art: { hue: 205, animated: animatedArt(10, 4) } }));
  }
  if (flags.has("many")) {
    const kinds: Item["kind"][] = [...Array(240).fill("cape"), ...Array(30).fill("back"), ...Array(20).fill("head"), ...Array(10).fill("aura")];
    kinds.forEach((kind, i) => {
      const n = 10 + i;
      const name = i === 4 ? LONG : `${ADJECTIVES[i % ADJECTIVES.length]} ${NOUNS[kind][Math.floor(i / ADJECTIVES.length) % NOUNS[kind].length]}${i >= 90 ? ` ${Math.floor(i / 90) + 1}` : ""}`;
      items.push(
        item(n, kind, i % 23 === 0 ? `TEST · ${name}` : name, {
          gift: i % 11 === 3,
          giftFrom: i % 22 === 3 ? "Ashwyn" : null,
          test: i % 23 === 0,
          art: i % 37 === 5 && kind === "cape" ? { hue: (n * 47) % 360, animated: animatedArt(8, 8) } : { hue: (n * 47) % 360 },
        }),
      );
    });
  }
  return items;
}

// ---------- textures ----------

/** One cape atlas (22 × 17 units) at `scale` px per unit, at (x, y): frame `f` of `frames`. */
function drawAtlas(g: CanvasRenderingContext2D, x: number, y: number, scale: number, hue: number, f: number, frames: number) {
  const u = (n: number) => n * scale;
  const t = frames ? f / frames : 0;
  g.fillStyle = `hsl(${hue} 45% 22%)`;
  g.fillRect(x, y, u(22), u(17));
  // Front (1,1)-(11,17) and back (12,1)-(22,17): bands that drift down as the frames go by.
  for (const face of [1, 12]) {
    for (let row = 0; row < 16; row++) {
      const wave = Math.sin((row / 16) * Math.PI * 2 - t * Math.PI * 2);
      g.fillStyle = `hsl(${(hue + wave * 18 + 360) % 360} ${58 + wave * 12}% ${34 + wave * 14 + row}%)`;
      g.fillRect(x + u(face), y + u(1 + row), u(10), u(1));
    }
    // A glint that sweeps across.
    const glint = Math.floor(((t * 26) % 26) - 8);
    g.fillStyle = "rgba(255,255,255,0.35)";
    for (let k = 0; k < 3; k++) if (glint + k >= 0 && glint + k < 16) g.fillRect(x + u(face), y + u(1 + glint + k), u(10), u(0.5));
    g.fillStyle = `hsl(${hue} 70% 70%)`;
    g.fillRect(x + u(face + 4), y + u(6), u(2), u(2));
  }
}

const textures = new Map<string, string>();
export function texture(art: Item["art"], kind: Item["kind"]): string {
  const key = `${kind}:${art.hue}:${art.animated?.scale ?? 1}`;
  let url = textures.get(key);
  if (!url) {
    const c = document.createElement("canvas");
    const g = c.getContext("2d")!;
    if (kind === "cape") {
      const scale = Math.min(art.animated?.scale ?? 1, 16);
      c.width = 64 * scale;
      c.height = 32 * scale;
      drawAtlas(g, 0, 0, scale, art.hue, 0, art.animated?.frames ?? 0);
    } else {
      // A small emblem for items the page shows whole.
      c.width = c.height = 32;
      g.fillStyle = `hsl(${art.hue} 50% 30%)`;
      g.fillRect(8, 8, 16, 16);
      g.fillStyle = `hsl(${art.hue} 65% 55%)`;
      g.fillRect(10, 10, 12, 12);
      g.fillStyle = `hsl(${art.hue} 80% 78%)`;
      g.fillRect(14, 4, 4, 6);
      g.fillRect(4, 14, 6, 4);
      g.fillRect(22, 14, 6, 4);
    }
    url = c.toDataURL("image/png");
    textures.set(key, url);
  }
  return url;
}

const strips = new Map<string, string>();
function stripUrl(art: Item["art"], index: number): string {
  const anim = art.animated!;
  const m = manifest(anim);
  const key = `${art.hue}:${anim.scale}:${anim.frames}:${index}`;
  let url = strips.get(key);
  if (!url) {
    const frames = m.strips[index].frames;
    const c = document.createElement("canvas");
    c.width = m.width;
    c.height = frames * m.height;
    const g = c.getContext("2d")!;
    for (let k = 0; k < frames; k++) drawAtlas(g, 0, k * m.height, anim.scale, art.hue, index * m.framesPerStrip + k, anim.frames);
    url = c.toDataURL("image/png");
    strips.set(key, url);
  }
  return url;
}

// ---------- orders and Merit ----------

const STATUSES = ["paid", "paid", "paid", "paid", "paid", "refunded", "pending", "canceled", "refund_pending", "charged_back", "disputed", "declined"];

function ordersFor(flags: Set<string>) {
  const now = Date.now();
  if (!flags.has("orders"))
    return [
      { id: "o1", kind: "cosmetic", status: "paid", paymentMethod: "tebex", testMode: true, item: "TEST · Ember Cape", amountMinor: 499, currency: "USD", createdAt: new Date(now - 2 * DAY).toISOString(), gift: false, reference: "FL-7Q2M-K4" },
      { id: "o2", kind: "cosmetic", status: "paid", paymentMethod: "flint_coins", testMode: true, item: "TEST · Frost Cape", coins: 750, createdAt: new Date(now - 5 * DAY).toISOString(), gift: true, recipientName: "Alex" },
    ];
  const out: Record<string, unknown>[] = [];
  for (let i = 0, n = 0; out.length < 500; i++) {
    const created = new Date(now - i * 0.61 * DAY).toISOString();
    const lines = i % 9 === 4 ? 3 : i % 5 === 2 ? 2 : 1;
    const reference = lines > 1 || i % 3 === 0 ? `FL-${(1000 + i).toString(36).toUpperCase()}-${(i * 7) % 97}` : null;
    const merit = i % 7 === 3;
    const pack = i % 13 === 6;
    const gift = i % 10 === 1;
    for (let l = 0; l < lines && out.length < 500; l++, n++) {
      out.push({
        id: `order-${n}`,
        kind: pack ? "coins" : "cosmetic",
        status: STATUSES[(i + l) % STATUSES.length],
        paymentMethod: merit ? "flint_coins" : "tebex",
        testMode: i % 17 === 0,
        item: pack ? "1,000 Merits" : `${ADJECTIVES[(i + l) % ADJECTIVES.length]} ${NOUNS.cape[(i + l) % NOUNS.cape.length]}`,
        coins: pack ? 1000 : merit ? 250 + ((i * 50) % 1000) : 500,
        amountMinor: merit ? null : pack ? 999 : 199 + ((i * 100) % 800),
        currency: merit ? null : i % 29 === 0 ? "EUR" : "USD",
        createdAt: created,
        gift,
        recipientName: gift ? ["Kestrel", "Ashwyn", "Pixelmoss"][i % 3] : null,
        reference,
      });
    }
  }
  return out;
}

const REASONS = ["daily_reward", "spend", "purchase", "daily_reward", "test_grant", "refund_credit", "adjustment", "refund_reversal"];
function walletFor(flags: Set<string>, balance: number) {
  const count = flags.has("orders") ? 60 : 3;
  let after = balance;
  const ledger = Array.from({ length: count }, (_, i) => {
    const reason = REASONS[i % REASONS.length];
    const delta = reason === "spend" || reason === "refund_reversal" ? -250 - ((i * 50) % 500) : reason === "daily_reward" ? [5, 10, 15, 20, 25, 40][i % 6] : 500;
    const entry = { delta, balanceAfter: after, reason, createdAt: new Date(Date.now() - i * 0.8 * DAY).toISOString() };
    after -= delta;
    return entry;
  });
  return { balance, ledger };
}

// ---------- the Store ----------

/** Strip `index` as `store_cape_strip` answers it: PNG bytes. */
export const strip = async (art: Item["art"], index: number): Promise<ArrayBuffer> => (await fetch(stripUrl(art, index))).arrayBuffer();

export function createStoreMock(params: URLSearchParams, accountName: (uuid: string) => string | null, active: () => string | null, extension?: StoreExtension) {
  const flags = new Set((params.get("store") ?? "").split(",").map((f) => f.trim()).filter(Boolean));
  const storeUrl = flags.has("staging") ? "https://flint-store-website-preview.example.workers.dev" : "https://store.flintclient.com";
  const signedIn = new Set<string>();
  const startSignedIn = flags.has("in") || flags.has("offline") || flags.has("unreachable") || flags.has("many") || flags.has("anim") || flags.has("orders") || flags.has("empty") || flags.has("ordersfail") || flags.has("wearfail");
  const owned = inventoryFor(flags);
  const firstWorn: Record<string, string> = {};
  if (flags.has("anim")) firstWorn.cape = CAPE_IDS.flame;
  else if (flags.has("many")) {
    firstWorn.cape = id(13);
    firstWorn.head = id(10 + 240 + 30 + 2);
  }
  const equipped: Record<string, Record<string, string>> = {};
  const worn = (uuid: string) => (equipped[uuid] ??= { ...firstWorn });
  const versions: Record<string, number> = {};
  const version = (uuid: string) => versions[uuid] ?? 3;
  const startCape = CAPE_PARAM[params.get("cape") ?? ""];
  const starter = params.get("cape") ? active() : null;
  if (starter) {
    signedIn.add(starter);
    if (startCape) worn(starter).cape = startCape;
  }
  const balance = flags.has("empty") ? 0 : 1250;
  const itemsOf = (uuid: string): Item[] => [...owned.map((o) => ({ ...o, ...extension?.decorate(uuid, o) })), ...(extension?.extra(uuid) ?? [])];
  const offline = flags.has("offline") || flags.has("unreachable");

  const view = (uuid: string) => {
    const signed = signedIn.has(uuid) || (startSignedIn && !signedIn.has(`out:${uuid}`));
    const locked = flags.has("locked") && !signed;
    return {
      uuid,
      storeUrl,
      signedIn: signed,
      locked,
      name: accountName(uuid),
      stale: signed && offline,
      fetchedAt: signed && !flags.has("unreachable") ? Date.now() - (offline ? 6 * 60_000 : 0) : null,
      problem: locked
        ? "Flint couldn't read your saved Flint Store sign-in. Try again, and allow Flint if your system asks."
        : signed && offline
          ? "Flint can't reach the Flint Store right now. Check your connection and try again."
          : null,
      inventory:
        signed && !flags.has("unreachable")
          ? { version: version(uuid), name: accountName(uuid) ?? "Steve", balance, owned: itemsOf(uuid).map(({ art: _art, ...rest }) => rest), equipped: { ...worn(uuid) } }
          : null,
    };
  };

  async function storeCall(cmd: string, args: Record<string, any>): Promise<unknown> {
    if (flags.has("slow")) await sleep(2500);
    switch (cmd) {
      case "store_info":
        return { storeUrl, contractVersion: 1, production: !flags.has("staging") };
      case "store_inventory":
        if (flags.has("error")) throw "Flint couldn't open its Store cache: the folder is read only.";
        return view(args.uuid);
      case "store_sign_in":
        await sleep(600);
        signedIn.add(args.uuid);
        signedIn.delete(`out:${args.uuid}`);
        return view(args.uuid);
      case "store_sign_out":
        signedIn.delete(args.uuid);
        signedIn.add(`out:${args.uuid}`);
        return;
      case "store_equip":
        await sleep(450);
        if (flags.has("wearfail")) throw "This account doesn't own that item (any more). The Flint Store decides what you own.";
        if (args.cosmeticId) worn(args.uuid)[args.slot] = args.cosmeticId;
        else delete worn(args.uuid)[args.slot];
        versions[args.uuid] = version(args.uuid) + 1;
        return view(args.uuid);
      case "store_changes":
        if (offline) return { state: "offline" };
        return view(args.uuid).signedIn ? { state: "ok", changed: args.since !== version(args.uuid), version: version(args.uuid), pollAfterSeconds: 25 } : { state: "signedOut" };
      case "store_asset": {
        const found = itemsOf(args.uuid).find((o) => o.cosmeticId === args.cosmeticId);
        if (!found) throw "Flint has no Store inventory for this account yet.";
        return found.art.testCape ? capeAsset(found.cosmeticId) : texture(found.art, found.kind);
      }
      case "store_cape_strip": {
        const found = itemsOf(args.uuid).find((o) => o.cosmeticId === args.cosmeticId);
        if (found?.art.testCape) return capeStrip(found.cosmeticId, args.index);
        if (!found?.art.animated) throw "This item isn't animated.";
        await sleep(120);
        return strip(found.art, args.index);
      }
      case "store_wallet":
        if (offline) throw "Flint can't reach the Flint Store right now. Check your connection and try again.";
        return walletFor(flags, balance);
      case "store_orders":
        if (offline) throw "Flint can't reach the Flint Store right now. Check your connection and try again.";
        if (flags.has("ordersfail")) throw "The Flint Store isn't available right now. Try again in a few minutes.";
        return ordersFor(flags);
      case "store_approve_link":
        if (flags.has("badcode"))
          throw "store.flintclient.com has no sign-in waiting for that code. A code works once, for 10 minutes, and only on the Flint Store that showed it: this Flint approves codes from store.flintclient.com, so a code from a staging or test Store can't be approved here. Check the code, or start the sign-in again on store.flintclient.com.";
        return;
    }
    throw new Error(`devMock: unknown command ${cmd}`);
  }
  return Object.assign(storeCall, {
    /** The account's inventory changed at the Store (the change feed reports it). */
    bump(uuid: string) {
      versions[uuid] = version(uuid) + 1;
    },
    /** The Store took `cosmeticId` off (it stopped counting). */
    takeOff(uuid: string, cosmeticId: string) {
      const wearing = worn(uuid);
      for (const slot of Object.keys(wearing)) if (wearing[slot] === cosmeticId) delete wearing[slot];
    },
    signedIn: (uuid: string) => view(uuid).signedIn,
  });
}
