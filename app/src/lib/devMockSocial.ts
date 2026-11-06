// Browser-only development stand-in for Friends (src-tauri/src/social.rs), used by devMock.ts. Never bundled into
// production builds. `?friends` gives six friends (one pinned, one in game, one with unread messages, one private message),
// two requests received and one sent; `?friends=empty|unavailable|connecting|offline|build` shows the other states, and
// `&friendsToast` sends a request and a message a moment after start-up. Without `friends`, the server isn't live yet.
import type { ChatMessage, Friend, FriendRequest, Me, SearchPlayer, SocialSnapshot } from "./social";

type Emit = (event: string, payload: unknown) => void;
type Player = { uuid: string; name: string; colors: [hair: string, skin: string, eyes: string] };

const P = (uuid: string, name: string, hair: string, skin: string, eyes: string): Player => ({ uuid, name, colors: [hair, skin, eyes] });
const KESTREL = P("1a2b3c4d5e6f40718293a4b5c6d7e8f1", "Kestrel", "#3b2a20", "#d9a27a", "#4a7bd0");
const ASHWYN = P("2b3c4d5e6f7041829304b5c6d7e8f9a2", "Ashwyn", "#c9c3b8", "#e3b48e", "#3c8a5a");
const PIXELMOSS = P("3c4d5e6f708142939405c6d7e8f9a0b3", "Pixelmoss", "#4f7a3a", "#c98f66", "#2d2d2d");
const COPPERLEAF = P("4d5e6f70819243a4a516d7e8f9a0b1c4", "Copperleaf", "#b5652d", "#f0c9a5", "#5a3b22");
const RUNE = P("5e6f708192a344b5b627e8f9a0b1c2d5", "Rune_Wright", "#1d1d24", "#8d5a3c", "#d8d8e0");
const TIDE = P("6f708192a3b445c6c738f9a0b1c2d3e6", "Tidecaller", "#2c5a7a", "#e6bc98", "#2e6fb0");
const EMBERLYNN = P("7a8192a3b4c546d7d849a0b1c2d3e4f7", "Emberlynn", "#a8452c", "#efc3a0", "#6b4a2e");
const QUARTZITE = P("8b92a3b4c5d647e8e95ab1c2d3e4f5a8", "Quartzite", "#e8e2d6", "#c08b68", "#8a5cc0");
const NOMAD = P("9ca3b4c5d6e748f9fa6bc2d3e4f5a6b9", "Nether_Nomad", "#5a1f1f", "#b77a55", "#e0a040");
const MOSSY = P("adb4c5d6e7f849a0ab7cd3e4f5a6b7ca", "Mossy_Bricks", "#59654a", "#d3a07c", "#3d3d3d");
const LANTERN = P("bec5d6e7f8a94ab1bc8de4f5a6b7c8db", "Lanternfish", "#2a3b4c", "#e2b593", "#d9a441");
const PLAYERS = [KESTREL, ASHWYN, PIXELMOSS, COPPERLEAF, RUNE, TIDE, EMBERLYNN, QUARTZITE, NOMAD, MOSSY, LANTERN];

const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;

/** A small face on a 64×64 skin: hair, skin, two eyes and a mouth, enough for a head in the list. */
const skins = new Map<string, string>();
export function demoSkin(uuid: string): string | null {
  const player = PLAYERS.find((p) => p.uuid === uuid);
  if (!player) return null;
  if (!skins.has(uuid)) {
    const [hair, skin, eyes] = player.colors;
    const c = document.createElement("canvas");
    c.width = c.height = 64;
    const g = c.getContext("2d")!;
    const px = (x: number, y: number, w: number, h: number, color: string) => {
      g.fillStyle = color;
      g.fillRect(x, y, w, h);
    };
    px(8, 8, 8, 8, skin);
    px(8, 8, 8, 2, hair);
    px(8, 10, 1, 2, hair);
    px(15, 10, 1, 2, hair);
    px(9, 12, 2, 1, "#f4f1ea");
    px(13, 12, 2, 1, "#f4f1ea");
    px(10, 12, 1, 1, eyes);
    px(13, 12, 1, 1, eyes);
    px(11, 14, 2, 1, "rgba(60,30,20,0.55)");
    px(9, 13, 1, 1, "rgba(0,0,0,0.06)");
    skins.set(uuid, c.toDataURL("image/png"));
  }
  return skins.get(uuid)!;
}

export function createSocialMock(params: URLSearchParams, me: () => { uuid: string; name: string } | null, emit: Emit) {
  const mode = params.get("friends");
  const demo = mode === "";
  const now = Date.now();
  let nextId = 5000;
  let focus: string | null = null;
  let my: Me | null = null;

  const friend = (p: Player, extra: Partial<Friend>): Friend => ({ uuid: p.uuid, name: p.name, status: "online", inGame: false, lastSeen: null, pinned: false, since: now - 40 * DAY, unread: 0, ...extra });
  let friends: Friend[] = demo
    ? [
        friend(KESTREL, { pinned: true }),
        friend(ASHWYN, { inGame: true }),
        friend(PIXELMOSS, { status: "away", unread: 3 }),
        friend(COPPERLEAF, { status: "busy" }),
        friend(RUNE, { status: "offline", lastSeen: now - 2 * HOUR }),
        friend(TIDE, { status: "offline", lastSeen: now - 3 * DAY }),
      ]
    : [];
  let incoming: FriendRequest[] = demo
    ? [
        { id: 71, uuid: EMBERLYNN.uuid, name: EMBERLYNN.name, at: now - 20 * MIN },
        { id: 72, uuid: QUARTZITE.uuid, name: QUARTZITE.name, at: now - DAY },
      ]
    : [];
  let outgoing: FriendRequest[] = demo ? [{ id: 73, uuid: NOMAD.uuid, name: NOMAD.name, at: now - 2 * DAY }] : [];

  const conversations: Record<string, ChatMessage[]> = {};
  const say = (with_: Player, mine: boolean, ago: number, body: string, extra: Partial<ChatMessage> = {}) => {
    const self = me()?.uuid ?? "";
    (conversations[with_.uuid] ??= []).push({ id: nextId++, from: mine ? self : with_.uuid, to: mine ? with_.uuid : self, body, sensitive: false, at: now - ago, read: true, ...extra });
  };
  if (demo) {
    const yesterday = (h: number, m: number) => {
      const d = new Date(now - DAY);
      d.setHours(h, m, 0, 0);
      return now - d.getTime();
    };
    say(ASHWYN, false, 3 * DAY, "thanks for the help with the farm, it's working");
    say(RUNE, false, yesterday(20, 12), "can you hop on my alt for the event? here's the login");
    say(RUNE, false, yesterday(20, 12) - 20_000, "rune_alt:Sunset42", { sensitive: true });
    say(RUNE, true, yesterday(20, 15), "ok, just this once. change it after, and never send that in chat again");
    say(KESTREL, false, yesterday(21, 40), "gg, that bedwars run was clean");
    say(KESTREL, true, yesterday(21, 41), "the bridge clutch at the end though");
    say(KESTREL, true, yesterday(21, 41) - 30_000, "same time tomorrow?");
    say(KESTREL, false, 54 * MIN, "yep, I'll be on around 7");
    say(KESTREL, false, 53 * MIN, "bringing the new texture pack, it's so much calmer than the old one");
    say(KESTREL, true, 46 * MIN, "perfect. I'll set up the private server so we can test it first");
    say(PIXELMOSS, false, 12 * MIN, "hey!", { read: false });
    say(PIXELMOSS, false, 11 * MIN, "are you around later?", { read: false });
    say(PIXELMOSS, false, 10 * MIN, "found a seed with a mushroom island right at spawn", { read: false });
  }

  const configured = mode !== "build";
  const phase = (): SocialSnapshot["phase"] => {
    if (!me()) return "signed_out";
    if (mode === null || mode === "unavailable") return "unavailable";
    if (mode === "connecting") return "connecting";
    if (mode === "offline") return "error";
    return "ready";
  };

  const snapshot = (): SocialSnapshot => {
    const self = me();
    if (self && (!my || my.uuid !== self.uuid)) my = { uuid: self.uuid, name: self.name, status: "online", allowRequests: true };
    const ready = phase() === "ready";
    return {
      configured,
      phase: configured ? phase() : "unavailable",
      account: self?.uuid ?? null,
      me: ready ? my : null,
      friends: ready ? friends : [],
      incoming: ready ? incoming : [],
      outgoing: ready ? outgoing : [],
      conversations: ready ? Object.fromEntries(Object.entries(conversations).map(([k, v]) => [k, { messages: v.slice(-200), loaded: true }])) : {},
      offline: mode === "offline",
      error: mode === "offline" ? "offline" : null,
      syncedAt: ready ? Date.now() : null,
      focus,
      stale: false,
    };
  };
  const publish = () => emit("social:update", snapshot());
  const player = (name: string) => PLAYERS.find((p) => p.name.toLowerCase() === name.toLowerCase()) ?? null;
  const replies = ["sounds good", "on my way", "haha yes", "give me 5 min", "nice, send me the coords"];

  if (params.has("friendsToast") && demo) {
    window.setTimeout(() => {
      incoming = [{ id: 80, uuid: LANTERN.uuid, name: LANTERN.name, at: Date.now() }, ...incoming];
      publish();
      emit("social:event", { kind: "request", id: 80, uuid: LANTERN.uuid, name: LANTERN.name });
      const message: ChatMessage = { id: nextId++, from: COPPERLEAF.uuid, to: me()?.uuid ?? "", body: "you up for the build battle tonight?", sensitive: false, at: Date.now(), read: false };
      (conversations[COPPERLEAF.uuid] ??= []).push(message);
      friends = friends.map((f) => (f.uuid === COPPERLEAF.uuid ? { ...f, unread: f.unread + 1 } : f));
      publish();
      emit("social:event", { kind: "message", name: COPPERLEAF.name, message });
    }, 1200);
  }

  return async function invoke(cmd: string, args: Record<string, any>): Promise<unknown> {
    const self = me();
    switch (cmd) {
      case "social_snapshot":
        return snapshot();
      case "social_focus":
        focus = args.chat ?? null;
        return;
      case "social_refresh":
        return;
      case "social_settings":
        if (my) my = { ...my, ...(args.status ? { status: args.status } : {}), ...(typeof args.allowRequests === "boolean" ? { allowRequests: args.allowRequests } : {}) };
        publish();
        return my;
      case "social_search": {
        const q = String(args.query).toLowerCase();
        const relation = (p: Player): SearchPlayer["relation"] =>
          friends.some((f) => f.uuid === p.uuid) ? "friend" : incoming.some((r) => r.uuid === p.uuid) ? "incoming" : outgoing.some((r) => r.uuid === p.uuid) ? "outgoing" : "none";
        const found: SearchPlayer[] = PLAYERS.filter((p) => p.name.toLowerCase().startsWith(q)).map((p) => ({ uuid: p.uuid, name: p.name, relation: relation(p), accepts: p !== TIDE }));
        if (self && self.name.toLowerCase().startsWith(q)) found.push({ uuid: self.uuid, name: self.name, relation: "self", accepts: true });
        return found.sort((a, b) => Number(a.name.toLowerCase() !== q) - Number(b.name.toLowerCase() !== q) || a.name.length - b.name.length || a.name.localeCompare(b.name));
      }
      case "social_request": {
        const p = player(String(args.name));
        if (!p) throw "not_on_flint";
        if (friends.some((f) => f.uuid === p.uuid)) throw "already_friends";
        if (outgoing.some((r) => r.uuid === p.uuid)) throw "already_sent";
        if (incoming.some((r) => r.uuid === p.uuid)) {
          incoming = incoming.filter((r) => r.uuid !== p.uuid);
          friends = [...friends, friend(p, { since: Date.now() })];
          publish();
          return "accepted";
        }
        outgoing = [{ id: nextId++, uuid: p.uuid, name: p.name, at: Date.now() }, ...outgoing];
        publish();
        return "sent";
      }
      case "social_respond": {
        const request = incoming.find((r) => r.id === args.requestId);
        if (!request) throw "not_found";
        incoming = incoming.filter((r) => r !== request);
        if (args.accept) friends = [...friends, friend(PLAYERS.find((p) => p.uuid === request.uuid)!, { since: Date.now() })];
        publish();
        return;
      }
      case "social_cancel":
        outgoing = outgoing.filter((r) => r.id !== args.requestId);
        publish();
        return;
      case "social_remove":
        friends = friends.filter((f) => f.uuid !== args.uuid);
        delete conversations[args.uuid];
        publish();
        return;
      case "social_pin":
        friends = friends.map((f) => (f.uuid === args.uuid ? { ...f, pinned: !!args.pinned } : f));
        publish();
        return;
      case "social_send": {
        await new Promise((r) => setTimeout(r, 260));
        const to = String(args.to);
        const body = String(args.body).trim();
        if (!body) throw "empty";
        if (Array.from(body).length > 1000) throw "too_long";
        const message: ChatMessage = { id: nextId++, from: self?.uuid ?? "", to, body, sensitive: !!args.sensitive || /\S+@\S+\.\S+/.test(body), at: Date.now(), read: false };
        (conversations[to] ??= []).push(message);
        publish();
        const them = friends.find((f) => f.uuid === to);
        if (them && them.status !== "offline") {
          window.setTimeout(() => {
            const reply: ChatMessage = { id: nextId++, from: to, to: self?.uuid ?? "", body: replies[message.id % replies.length], sensitive: false, at: Date.now(), read: false };
            conversations[to].push(reply);
            friends = friends.map((f) => (f.uuid === to ? { ...f, unread: f.unread + 1 } : f));
            publish();
            emit("social:event", { kind: "message", name: them.name, message: reply });
          }, 2400);
        }
        return message;
      }
      case "social_history": {
        const list = conversations[args.with] ?? [];
        const before = typeof args.before === "number" ? args.before : Infinity;
        const older = list.filter((m) => m.id < before);
        return older.slice(-(args.limit ?? 50));
      }
      case "social_read":
        for (const m of conversations[args.with] ?? []) if (m.from === args.with && m.id <= args.upTo) m.read = true;
        friends = friends.map((f) => (f.uuid === args.with ? { ...f, unread: 0 } : f));
        publish();
        return;
    }
    throw new Error(`devMock: unknown command ${cmd}`);
  };
}
