/**
 * The Store page's rules, apart from React so they can be tested: which state the page is in, what the inventory shows
 * (slot, search, sort, pages), what wearing something looks like before the Store answers, which Merit balance is the
 * newest, and the order history as rows. The cape a player shows is lib/capeSource.ts's rule. Everything here is
 * display: the Store decides what an account owns, wears and has spent (lib/storeSync.ts, src-tauri/src/store).
 */
import { searchable, type MessageKey } from "./i18n.ts";
import { STORE_SLOTS, type StoreInventory, type StoreItem, type StoreOrder, type StoreSlot, type StoreView } from "./storeSync.ts";

// ---------- the page's state ----------

/**
 * - `noAccount`, `testIdentity`: nothing to ask the Store about.
 * - `loading`: the first answer for this account hasn't come (nothing of another account is ever shown meanwhile).
 * - `error`: Flint couldn't ask at all.
 * - `locked`: the system refused to read the saved Store sign-in.
 * - `signedOut`: no Store session for this account.
 * - `unreachable`: signed in, but the Store can't be reached and Flint has no copy of its last answer.
 * - `offline`: the Store's last answer, read only.
 * - `ready`: the Store's current answer.
 */
export type StorePhase = "noAccount" | "testIdentity" | "loading" | "error" | "locked" | "signedOut" | "unreachable" | "offline" | "ready";

export function storePhase({ account, view, loadError }: { account: { qa?: boolean } | null; view: StoreView | null; loadError: string | null }): StorePhase {
  if (!account) return "noAccount";
  if (account.qa) return "testIdentity";
  if (!view) return loadError ? "error" : "loading";
  if (!view.signedIn) return view.locked ? "locked" : "signedOut";
  if (view.stale) return view.inventory ? "offline" : "unreachable";
  return "ready";
}

// ---------- the inventory ----------

export type ItemSort = "newest" | "oldest" | "name";
export const ITEM_SORTS: { value: ItemSort; label: MessageKey }[] = [
  { value: "newest", label: "store.inventory.sort.newest" },
  { value: "oldest", label: "store.inventory.sort.oldest" },
  { value: "name", label: "store.inventory.sort.name" },
];

/** How many items each slot has, every slot listed. */
export function slotCounts(inventory: StoreInventory | null | undefined): Record<StoreSlot, number> {
  const counts = { cape: 0, back: 0, head: 0, aura: 0 } as Record<StoreSlot, number>;
  for (const item of inventory?.owned ?? []) if (item.kind in counts) counts[item.kind]++;
  return counts;
}

/** The slot to show: the one chosen if it is a slot, else the first with something in it (capes when nothing is owned). */
export function pickSlot(inventory: StoreInventory | null | undefined, chosen: StoreSlot | null): StoreSlot {
  if (chosen && STORE_SLOTS.includes(chosen)) return chosen;
  const counts = slotCounts(inventory);
  return STORE_SLOTS.find((slot) => counts[slot] > 0) ?? "cape";
}

const grantedTime = (item: StoreItem) => {
  const time = item.grantedAt ? Date.parse(item.grantedAt) : NaN;
  return Number.isNaN(time) ? 0 : time;
};

const byName = (a: StoreItem, b: StoreItem) => a.name.localeCompare(b.name, undefined, { sensitivity: "base", numeric: true }) || a.cosmeticId.localeCompare(b.cosmeticId);

/** A slot's items matching `query` (name, case and accents ignored), in `sort` order. Ties fall back to the name. */
export function inventoryList(items: readonly StoreItem[], { slot, query, sort }: { slot: StoreSlot; query: string; sort: ItemSort }): StoreItem[] {
  const needle = searchable(query.trim());
  const list = items.filter((item) => item.kind === slot && (!needle || searchable(item.name).includes(needle)));
  if (sort === "name") return list.sort(byName);
  const direction = sort === "newest" ? -1 : 1;
  return list.sort((a, b) => (grantedTime(a) - grantedTime(b)) * direction || byName(a, b));
}

export type Paged<T> = { items: T[]; page: number; pages: number; from: number; to: number; total: number };

/** Page `page` (from 0, kept within range) of `list`, `size` to a page. `from`/`to` count from 1 for "21–40 of 300". */
export function paginate<T>(list: readonly T[], page: number, size: number): Paged<T> {
  const total = list.length;
  const pages = Math.max(1, Math.ceil(total / Math.max(1, size)));
  const current = Math.min(Math.max(0, Math.floor(page) || 0), pages - 1);
  const start = current * size;
  const items = list.slice(start, start + size);
  return { items, page: current, pages, from: total ? start + 1 : 0, to: start + items.length, total };
}

/** A slot offers search, order and list view only past this many items; fewer are seen at a glance. */
export const FINDER_FROM = 8;
export const needsFinder = (count: number) => count > FINDER_FROM;

/**
 * One page of a slot's tiles: "None" (`null`) first on the first page when `withNone`, then the items, `size` tiles to a
 * page so a grid page ends on a full row. `paged` counts the items only, so "21–40 of 300" never counts "None".
 */
export function tilePage<T>(items: readonly T[], page: number, size: number, withNone: boolean): { tiles: (T | null)[]; paged: Paged<T> } {
  const entries: (T | null)[] = withNone ? [null, ...items] : [...items];
  const at = paginate(entries, page, size);
  const shown = at.items.filter((entry): entry is T => entry !== null);
  const before = Math.max(0, at.page * Math.max(1, size) - (withNone && at.page > 0 ? 1 : 0));
  return {
    tiles: at.items,
    paged: { items: shown, page: at.page, pages: at.pages, from: shown.length ? before + 1 : 0, to: before + shown.length, total: items.length },
  };
}

/** The page buttons to offer: always the first and last, the current one and its neighbours; `null` for a gap. */
export function pageButtons(page: number, pages: number): (number | null)[] {
  if (pages <= 7) return Array.from({ length: pages }, (_, i) => i);
  const wanted = new Set([0, pages - 1, page - 1, page, page + 1].filter((p) => p >= 0 && p < pages));
  if (page <= 2) [1, 2, 3].forEach((p) => wanted.add(p));
  if (page >= pages - 3) [pages - 4, pages - 3, pages - 2].forEach((p) => wanted.add(p));
  const sorted = [...wanted].sort((a, b) => a - b);
  const out: (number | null)[] = [];
  sorted.forEach((p, i) => {
    if (i && p - sorted[i - 1] > 1) out.push(null);
    out.push(p);
  });
  return out;
}

/** The inventory as it will be once the Store accepts wearing `cosmeticId` in `slot` (null: nothing). Never changes the Store's copy. */
export function withEquipped(inventory: StoreInventory, slot: StoreSlot, cosmeticId: string | null): StoreInventory {
  const equipped = { ...inventory.equipped };
  if (cosmeticId) equipped[slot] = cosmeticId;
  else delete equipped[slot];
  return { ...inventory, equipped };
}

/**
 * An item's name on its tile. The Store names its test items "TEST · Ember Cape"; the tile marks them "Test" on the
 * picture instead and shows "Ember Cape", so two test items never look alike once a narrow tile cuts the name short.
 * Every other name is the Store's, as is.
 */
export function shownName(item: Pick<StoreItem, "name" | "test">): string {
  if (!item.test) return item.name;
  return item.name.replace(/^test\s*[·:|\-–—]\s*/i, "").trim() || item.name;
}

/** What a slot has on: the owned item, or null (nothing, or an item the inventory no longer lists). */
export function wornIn(inventory: StoreInventory | null | undefined, slot: StoreSlot): StoreItem | null {
  const id = inventory?.equipped[slot];
  return (id && inventory?.owned.find((item) => item.cosmeticId === id && item.kind === slot)) || null;
}

// ---------- Merit ----------

export type MeritShown = { balance: number | null; at: number | null; stale: boolean };

/**
 * The newest Merit balance Flint has for the account: the inventory's (`view.fetchedAt`) or the wallet's (`wallet.at`,
 * when it was answered), whichever the Store sent last. Never a number from another account: both come from this
 * account's page. Null when neither is known (never shown as zero).
 */
export function meritShown(view: StoreView | null, wallet: { balance: number | null; at: number } | null): MeritShown {
  const fromInventory = typeof view?.inventory?.balance === "number" ? { balance: view.inventory.balance, at: view.fetchedAt ?? null } : null;
  const fromWallet = wallet && typeof wallet.balance === "number" ? { balance: wallet.balance, at: wallet.at } : null;
  const newest = fromWallet && (!fromInventory || (fromInventory.at ?? 0) <= fromWallet.at) ? fromWallet : fromInventory;
  return { balance: newest?.balance ?? null, at: newest?.at ?? null, stale: !!view?.stale };
}

// ---------- orders and Merit activity ----------

export type Tone = "neutral" | "accent" | "success" | "danger" | "warning";

const ORDER_STATUS: Record<string, { label: MessageKey; tone: Tone }> = {
  paid: { label: "store.orders.status.paid", tone: "success" },
  pending: { label: "store.orders.status.pending", tone: "warning" },
  declined: { label: "store.orders.status.declined", tone: "neutral" },
  canceled: { label: "store.orders.status.canceled", tone: "neutral" },
  refund_pending: { label: "store.orders.status.refundPending", tone: "warning" },
  refunded: { label: "store.orders.status.refunded", tone: "neutral" },
  disputed: { label: "store.orders.status.disputed", tone: "warning" },
  charged_back: { label: "store.orders.status.chargedBack", tone: "danger" },
};

/** An order status as words and a tone; an unknown one is shown as the Store wrote it. */
export function orderStatus(status: string | null | undefined): { label: MessageKey | null; text: string; tone: Tone } {
  const known = status ? ORDER_STATUS[status] : undefined;
  return known ? { label: known.label, text: "", tone: known.tone } : { label: null, text: status ?? "", tone: "neutral" };
}

const LEDGER_REASON: Record<string, MessageKey> = {
  purchase: "store.merit.reason.purchase",
  spend: "store.merit.reason.spend",
  refund_reversal: "store.merit.reason.refundReversal",
  chargeback_reversal: "store.merit.reason.chargebackReversal",
  adjustment: "store.merit.reason.adjustment",
  test_grant: "store.merit.reason.testGrant",
  refund_credit: "store.merit.reason.refundCredit",
  daily_reward: "store.merit.reason.dailyReward",
};

/** A Merit movement's reason as a message key, or null for one Flint doesn't know (shown as the Store wrote it). */
export const ledgerReason = (reason: string | null | undefined): MessageKey | null => (reason ? (LEDGER_REASON[reason] ?? null) : null);

/**
 * One row of the order history: an order, or every line of one checkout (a cart) together.
 * - `money`: what was paid with a card, summed (null when lines were in different currencies, or none was).
 * - `meritSpent`: Merit paid. `meritAdded`: Merit bought (a Merit pack).
 */
export type OrderRow = {
  key: string;
  reference: string | null;
  createdAt: string | null;
  items: string[];
  money: { minor: number; currency: string } | null;
  meritSpent: number | null;
  meritAdded: number | null;
  statuses: string[];
  testMode: boolean;
  gift: boolean;
  recipientName: string | null;
};

const MERIT_PAYMENT = "flint_coins";

export function orderRows(orders: readonly StoreOrder[]): OrderRow[] {
  const rows: OrderRow[] = [];
  const byReference = new Map<string, OrderRow>();
  /** A row's card payments so far: their currency (null once two differ) and sum. */
  const paid = new Map<OrderRow, { currency: string | null; minor: number }>();
  for (const order of orders) {
    let row = order.reference ? byReference.get(order.reference) : undefined;
    if (!row) {
      row = {
        key: order.reference ? `ref:${order.reference}` : `order:${order.id}`,
        reference: order.reference ?? null,
        createdAt: order.createdAt ?? null,
        items: [],
        money: null,
        meritSpent: null,
        meritAdded: null,
        statuses: [],
        testMode: false,
        gift: false,
        recipientName: null,
      };
      rows.push(row);
      if (order.reference) byReference.set(order.reference, row);
    }
    if (order.item) row.items.push(order.item);
    if (order.status && !row.statuses.includes(order.status)) row.statuses.push(order.status);
    const coins = typeof order.coins === "number" && Number.isFinite(order.coins) ? order.coins : null;
    if (order.paymentMethod === MERIT_PAYMENT) {
      if (coins !== null) row.meritSpent = (row.meritSpent ?? 0) + coins;
    } else {
      if (typeof order.amountMinor === "number" && Number.isFinite(order.amountMinor) && order.currency) {
        const sum = paid.get(row);
        const next = sum ? { currency: sum.currency === order.currency ? sum.currency : null, minor: sum.minor + order.amountMinor } : { currency: order.currency, minor: order.amountMinor };
        paid.set(row, next);
        row.money = next.currency ? { minor: next.minor, currency: next.currency } : null;
      }
      if (order.kind === "coins" && coins !== null) row.meritAdded = (row.meritAdded ?? 0) + coins;
    }
    row.testMode ||= order.testMode;
    if (order.gift) {
      row.gift = true;
      row.recipientName ??= order.recipientName ?? null;
    }
    row.createdAt ??= order.createdAt ?? null;
  }
  return rows;
}

