/*
 * Friends as the interface sees it: the backend's snapshot and events (src-tauri/src/social.rs), and the pure helpers
 * the Friends page and its notifications share. Times are milliseconds since the epoch; UUIDs are 32 lowercase hex.
 */
import type { MessageKey } from "./i18n";

export type FriendStatus = "online" | "busy" | "away" | "offline";
export type MyStatus = "online" | "busy" | "away" | "invisible";

export type Me = { uuid: string; name: string; status: MyStatus; allowRequests: boolean };
export type Friend = { uuid: string; name: string; status: FriendStatus; inGame: boolean; lastSeen: number | null; pinned: boolean; since: number | null; unread: number };
export type FriendRequest = { id: number | string; uuid: string; name: string; at: number | null };
export type ChatMessage = { id: number; from: string; to: string; body: string; sensitive: boolean; at: number | null; read: boolean };
export type Conversation = { messages: ChatMessage[]; loaded: boolean };
export type SocialPhase = "signed_out" | "connecting" | "unavailable" | "ready" | "error";

export type SocialSnapshot = {
  /** This build knows where Friends lives. */
  configured: boolean;
  phase: SocialPhase;
  account: string | null;
  me: Me | null;
  friends: Friend[];
  incoming: FriendRequest[];
  outgoing: FriendRequest[];
  conversations: Record<string, Conversation>;
  /** The last sync failed for want of a network: what's shown is the last state. */
  offline: boolean;
  error: string | null;
  syncedAt: number | null;
  focus: string | null;
  /** The list shown is the last run's, kept on this computer, until the first sync: no status in it is live. */
  stale: boolean;
};

export type SocialEvent =
  | { kind: "request"; id: number | string; uuid: string; name: string }
  | { kind: "accepted"; uuid: string; name: string }
  | { kind: "message"; name: string; message: ChatMessage }
  | { kind: "online"; uuid: string; name: string; status: FriendStatus };

export type SearchPlayer = { uuid: string; name: string; relation: "none" | "friend" | "incoming" | "outgoing" | "self"; accepts: boolean };

/** A message on its way: shown at once, and replaced by the real one when the server has it. */
/** A message on its way. `clientId` goes with every try, so a retry after a timeout is never stored twice. */
export type PendingMessage = { key: string; clientId: string; to: string; body: string; sensitive: boolean; at: number; failed: boolean };

export const EMPTY_SNAPSHOT: SocialSnapshot = {
  configured: true,
  phase: "connecting",
  account: null,
  me: null,
  friends: [],
  incoming: [],
  outgoing: [],
  conversations: {},
  offline: false,
  error: null,
  syncedAt: null,
  focus: null,
  stale: false,
};

export const MAX_MESSAGE = 1000;
/** The counter shows from here. */
export const COUNTER_FROM = 900;

/** A Minecraft name as Mojang allows it. */
export const isPlayerName = (name: string) => /^[A-Za-z0-9_]{1,16}$/.test(name);

/** Pending requests and unread messages: the number on the sidebar's Friends button. */
export function badgeCount(snapshot: SocialSnapshot | null): number {
  if (!snapshot || snapshot.phase === "signed_out") return 0;
  return snapshot.incoming.length + snapshot.friends.reduce((sum, f) => sum + f.unread, 0);
}

const STATUS_ORDER: Record<FriendStatus, number> = { online: 0, busy: 1, away: 2, offline: 3 };
const byName = (a: Friend, b: Friend) => a.name.localeCompare(b.name, undefined, { sensitivity: "base" });

/** Pinned (online ones first), then online, busy and away, then offline (most recently seen first). */
export function friendSections(friends: Friend[]): { pinned: Friend[]; online: Friend[]; offline: Friend[] } {
  const pinned = friends.filter((f) => f.pinned).sort((a, b) => STATUS_ORDER[a.status] - STATUS_ORDER[b.status] || byName(a, b));
  const rest = friends.filter((f) => !f.pinned);
  const online = rest.filter((f) => f.status !== "offline").sort((a, b) => Number(b.inGame) - Number(a.inGame) || STATUS_ORDER[a.status] - STATUS_ORDER[b.status] || byName(a, b));
  const offline = rest.filter((f) => f.status === "offline").sort((a, b) => (b.lastSeen ?? 0) - (a.lastSeen ?? 0) || byName(a, b));
  return { pinned, online, offline };
}

/** The dot's colour class for a status. */
export const statusTone = (status: FriendStatus | MyStatus) => (status === "invisible" ? "offline" : status);

/** The label of a friend's status ("In game" while they play). The offline label gets "· 2 h ago" from the caller. */
export function statusKey(friend: Pick<Friend, "status" | "inGame">): MessageKey {
  if (friend.status === "offline") return "friends.status.offline";
  if (friend.inGame) return "friends.status.inGame";
  return friend.status === "busy" ? "friends.status.busy" : friend.status === "away" ? "friends.status.away" : "friends.status.online";
}

export const MY_STATUS_KEYS: Record<MyStatus, MessageKey> = {
  online: "friends.status.online",
  busy: "friends.status.busy",
  away: "friends.status.away",
  invisible: "friends.status.invisible",
};

/** What the backend's error codes say. Anything else is the generic "didn't work". */
export const ERROR_KEYS: Record<string, MessageKey> = {
  offline: "friends.errors.offline",
  not_on_flint: "friends.errors.notOnFlint",
  not_accepting: "friends.errors.notAccepting",
  already_friends: "friends.errors.alreadyFriends",
  already_sent: "friends.errors.alreadySent",
  self: "friends.errors.self",
  too_many: "friends.errors.tooMany",
  rate_limited: "friends.errors.rateLimited",
  not_found: "friends.errors.notFound",
  not_friends: "friends.errors.notFriends",
  empty: "friends.errors.empty",
  too_long: "friends.errors.tooLong",
  mojang: "friends.errors.mojang",
  account: "friends.errors.account",
  unverified: "friends.errors.mojang",
  invalid: "friends.errors.generic",
};

export const errorKey = (code: unknown): MessageKey => (typeof code === "string" && ERROR_KEYS[code]) || "friends.errors.generic";

/** Where "Send a gift" goes: the Store's cosmetics, set up as a gift for this friend. */
export function giftUrl(storeUrl: string, uuid: string, name: string): string {
  const base = storeUrl.replace(/\/+$/, "");
  return `${base}/cosmetics/?for=gift&to=${encodeURIComponent(uuid.replace(/-/g, ""))}&toName=${encodeURIComponent(name)}`;
}

/** The conversation as shown: the backend's messages, older pages, sends already confirmed; by id, no repeats. */
export function mergeMessages(...lists: ChatMessage[][]): ChatMessage[] {
  const byId = new Map<number, ChatMessage>();
  for (const list of lists) for (const m of list) byId.set(m.id, m);
  return Array.from(byId.values()).sort((a, b) => a.id - b.id);
}

/** Messages close together from one person form a group; their time shows once, under the last of them. */
const GROUP_GAP_MS = 5 * 60_000;

export type ChatItem =
  | { type: "day"; key: string; at: number }
  | { type: "message"; key: string; message: ChatMessage | PendingMessage; mine: boolean; first: boolean; last: boolean };

const dayOf = (ms: number) => {
  const d = new Date(ms);
  return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
};

const isPending = (m: ChatMessage | PendingMessage): m is PendingMessage => "key" in m;
const senderOf = (m: ChatMessage | PendingMessage, me: string) => (isPending(m) ? me : m.from);
const timeOf = (m: ChatMessage | PendingMessage, fallback: number) => m.at ?? fallback;

/** The chat as rows: a separator for each day, and each message with its place in its group. */
export function chatItems(messages: (ChatMessage | PendingMessage)[], me: string): ChatItem[] {
  const items: ChatItem[] = [];
  let lastDay = "";
  let previous: ChatMessage | PendingMessage | null = null;
  let previousAt = 0;
  for (const message of messages) {
    const at = timeOf(message, previousAt);
    const day = dayOf(at);
    const newDay = day !== lastDay;
    if (newDay) {
      items.push({ type: "day", key: `day-${day}`, at });
      lastDay = day;
    }
    const sender = senderOf(message, me);
    const continues = !!previous && !newDay && senderOf(previous, me) === sender && at - previousAt < GROUP_GAP_MS;
    if (continues) {
      const before = items[items.length - 1];
      if (before.type === "message") before.last = false;
    }
    items.push({ type: "message", key: isPending(message) ? message.key : `m${message.id}`, message, mine: sender === me, first: !continues, last: true });
    previous = message;
    previousAt = at;
  }
  return items;
}

export { isPending };
