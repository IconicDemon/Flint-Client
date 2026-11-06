import { memo, useEffect, useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { isConditional, obtainedLabel, ownItems, type RewardCard } from "../../lib/community";
import { shortDate } from "../../lib/format";
import { useT } from "../../lib/i18n";
import { inventoryList, ITEM_SORTS, needsFinder, pickSlot, shownName, slotCounts, tilePage, wornIn, type ItemSort } from "../../lib/storePage";
import { SLOT_LABELS, STORE_SLOTS, type StoreInventory, type StoreItem, type StoreSlot } from "../../lib/storeSync";
import { Button, EmptyState, IconButton, Segmented, Spinner, Tabs } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { Dropdown } from "../../ui/overlays";
import { onThumbnailTick, thumbnailFrame, thumbnailFrames, type AnimationSource } from "./capeFrames";
import { CommunityRewardsGroup, type CommunityProps } from "./CommunityRewards";
import { Pager } from "./Pager";
import { storeAnimation, textureKey, useStoreTexture } from "./storeCape";

/** Rows of tiles on a page; the columns follow the width, so a page always ends on a full row. */
const GRID_ROWS = 4;
const LIST_PAGE = 12;

export type Wearing = { slot: StoreSlot; cosmeticId: string | null } | null;

type Props = {
  uuid: string;
  inventory: StoreInventory;
  /** A wear the Store hasn't answered yet (already applied to `inventory`). */
  pending: Wearing;
  /** The last wear the Store refused, in plain words. */
  error: { slot: StoreSlot; text: string } | null;
  readOnly: boolean;
  /** The account has a Minecraft cape (shown when no Flint cape is worn). */
  hasMinecraftCape: boolean;
  motion: boolean;
  onWear: (slot: StoreSlot, cosmeticId: string | null) => void;
  /** The item pointed at or focused, for the stage; null when none. */
  onPreview: (item: StoreItem | null) => void;
  onOpenStore: () => void;
  /** Community Rewards (lib/community.ts), when the Store lists any for this account; null shows nothing of them. */
  community?: CommunityProps | null;
};

const NO_CARDS: RewardCard[] = [];

/**
 * The account's Store items, one slot at a time: equal tiles, "None" first, the worn one marked "Wearing", a click to wear.
 * A slot with many items also gets search, order and a list view, and pages. When the Store lists Community Rewards for
 * the slot, they follow as a second group ("My Capes", then "Community Rewards") of the same tiles.
 */
export function StoreInventoryCard({ uuid, inventory: full, pending, error, readOnly, hasMinecraftCape, motion, onWear, onPreview, onOpenStore, community }: Props) {
  const t = useT();
  const cards = community?.cards ?? NO_CARDS;
  // The account's own items (bought, given, earned for good). Conditional rewards are in their group, with their requirement.
  const own = useMemo(() => ownItems(full, cards), [full, cards]);
  const inventory = useMemo(() => (own.length === full.owned.length ? full : { ...full, owned: own }), [full, own]);
  const [chosen, setChosen] = useState<StoreSlot | null>(null);
  const [query, setQuery] = useState("");
  const [sort, setSort] = useState<ItemSort>("newest");
  const [view, setView] = useState<"grid" | "list">("grid");
  const [first, setFirst] = useState(0);
  const [columns, setColumns] = useState(4);
  const slot = pickSlot(inventory, chosen);
  const counts = slotCounts(inventory);
  // Search, order and the list view are for a slot with many items; a smaller one shows them all, newest first.
  const finder = needsFinder(counts[slot]);
  const search = finder ? query : "";
  const order: ItemSort = finder ? sort : "newest";
  const shape = finder ? view : "grid";
  const list = useMemo(() => inventoryList(inventory.owned, { slot, query: search, sort: order }), [inventory.owned, slot, search, order]);
  const size = shape === "grid" ? columns * GRID_ROWS : LIST_PAGE;
  const { tiles, paged } = tilePage(list, Math.floor(first / size), size, !search.trim());
  // What the slot wears comes from the whole inventory: a reward cape worn there counts too ("None" isn't marked then).
  const worn = wornIn(full, slot);
  const slotCards = cards.filter((card) => card.reward.kind === slot);
  const top = useRef<HTMLElement>(null);

  // A new slot, search or order starts at the top; a new page size keeps the first tile shown on screen.
  useEffect(() => setFirst(0), [slot, search, order]);

  const goTo = (page: number) => {
    setFirst(page * size);
    const section = top.current;
    if (section && section.getBoundingClientRect().top < 0) section.scrollIntoView({ block: "start", behavior: "smooth" });
  };

  if (!inventory.owned.length && !cards.length) {
    return (
      <section className="store-items is-empty" ref={top} aria-labelledby="store-items-title">
        <h2 id="store-items-title" className="sr-only">
          {t("store.items.title")}
        </h2>
        <EmptyState
          icon="bag"
          title={t("store.items.emptyTitle")}
          body={t("store.items.emptyBody")}
          action={
            <Button variant="primary" iconRight="external" onClick={onOpenStore}>
              {t("store.summary.openStore")}
            </Button>
          }
        />
      </section>
    );
  }

  const busy = !!pending;
  return (
    <section className="store-items" ref={top} aria-labelledby="store-items-title">
      <h2 id="store-items-title" className="sr-only">
        {t("store.items.title")}
      </h2>
      <div className="store-items-bar">
        <Tabs
          label={t("store.inventory.slots")}
          value={slot}
          onChange={(next) => {
            setChosen(next);
            onPreview(null);
          }}
          tabs={STORE_SLOTS.map((s) => ({ value: s, label: t(SLOT_LABELS[s]), count: counts[s] }))}
        />
        {finder && (
          <div className="store-finder">
            <label className="store-search">
              <Icon name="search" size={15} />
              <input type="search" value={query} placeholder={t("store.inventory.search")} aria-label={t("store.inventory.search")} spellCheck={false} onChange={(e) => setQuery(e.target.value)} />
              {query && <IconButton icon="x" size="sm" label={t("store.inventory.clearSearch")} onClick={() => setQuery("")} />}
            </label>
            <Dropdown label={t("store.inventory.sort.label")} value={sort} onChange={setSort} options={ITEM_SORTS.map((s) => ({ value: s.value, label: t(s.label) }))} className="store-sort" />
            <Segmented
              label={t("store.inventory.view.label")}
              value={view}
              onChange={(next) => {
                setView(next);
                setFirst(paged.from ? paged.from - 1 : 0);
              }}
              options={[
                { value: "grid", label: t("store.inventory.view.grid") },
                { value: "list", label: t("store.inventory.view.list") },
              ]}
            />
          </div>
        )}
      </div>

      {error?.slot === slot && (
        <p className="store-inline-error" role="alert">
          <Icon name="alert" size={14} />
          {error.text}
        </p>
      )}

      <ItemGroup title={slotCards.length ? (slot === "cape" ? t("store.rewards.myCapes") : t("store.rewards.myItems")) : undefined}>
        {counts[slot] === 0 && !slotCards.length ? (
          <EmptyState compact icon="bag" title={t("store.inventory.slotEmptyTitle")} body={t("store.inventory.slotEmptyBody")} />
        ) : list.length === 0 && search.trim() ? (
          <EmptyState
            compact
            icon="search"
            title={t("store.inventory.noMatch", { query: search.trim() })}
            body={t("store.inventory.noMatchBody")}
            action={
              <Button size="sm" onClick={() => setQuery("")}>
                {t("store.inventory.clearSearch")}
              </Button>
            }
          />
        ) : (
          <ItemGrid
            uuid={uuid}
            view={shape}
            slot={slot}
            tiles={tiles}
            worn={worn}
            pending={pending}
            readOnly={readOnly}
            hasMinecraftCape={hasMinecraftCape}
            motion={motion}
            onColumns={setColumns}
            onWear={(item) => !busy && !readOnly && full.equipped[item.kind] !== item.cosmeticId && onWear(item.kind, item.cosmeticId)}
            onNone={() => worn && !busy && !readOnly && onWear(slot, null)}
            onPreview={onPreview}
          />
        )}
        {counts[slot] === 0 && slotCards.length > 0 && slot === "cape" && <p className="faint store-group-note">{t("store.rewards.noOwnCapes")}</p>}

        {paged.pages > 1 && <Pager paged={paged} onPage={goTo} label={t("store.pager.items")} />}
      </ItemGroup>
      {community && slotCards.length > 0 && (
        <CommunityRewardsGroup
          {...community}
          uuid={uuid}
          cards={slotCards}
          readOnly={readOnly}
          motion={motion}
          busy={busy}
          pendingId={pending?.slot === slot ? pending.cosmeticId : null}
          onWear={(s, id) => !busy && !readOnly && onWear(s, id)}
          onPreview={onPreview}
        />
      )}
    </section>
  );
}

/**
 * One group of a slot's tiles with its own pages: today the items the account owns. Another group (Community Rewards
 * capes, say) sits below it with a `title`, and then this one gets one too.
 */
function ItemGroup({ title, children }: { title?: string; children: ReactNode }) {
  return (
    <div className="store-group">
      {title && (
        <div className="store-group-head">
          <h3 className="store-group-title">{title}</h3>
        </div>
      )}
      {children}
    </div>
  );
}

/** Tiles or rows ("None" is `null`), with arrow keys moving between them. Reports how many columns fit, for the page size. */
const ItemGrid = memo(function ItemGrid({
  uuid,
  view,
  slot,
  tiles,
  worn,
  pending,
  readOnly,
  hasMinecraftCape,
  motion,
  onColumns,
  onWear,
  onNone,
  onPreview,
}: {
  uuid: string;
  view: "grid" | "list";
  slot: StoreSlot;
  tiles: (StoreItem | null)[];
  /** What the slot wears (an owned item), or null. */
  worn: StoreItem | null;
  pending: Wearing;
  readOnly: boolean;
  hasMinecraftCape: boolean;
  motion: boolean;
  onColumns: (n: number) => void;
  onWear: (item: StoreItem) => void;
  onNone: () => void;
  onPreview: (item: StoreItem | null) => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const columns = useRef(1);

  useLayoutEffect(() => {
    const el = ref.current;
    if (!el || view !== "grid" || typeof ResizeObserver === "undefined") return;
    const measure = () => {
      const n = Math.max(1, getComputedStyle(el).gridTemplateColumns.split(" ").filter(Boolean).length);
      columns.current = n;
      onColumns(n);
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(el);
    return () => observer.disconnect();
  }, [view, onColumns]);

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const step = { ArrowRight: 1, ArrowLeft: -1, ArrowDown: view === "grid" ? columns.current : 1, ArrowUp: view === "grid" ? -columns.current : -1, Home: -Infinity, End: Infinity }[e.key];
    if (step === undefined) return;
    const all = Array.from(ref.current?.querySelectorAll<HTMLButtonElement>(":scope > .store-tile") ?? []);
    const at = all.indexOf(document.activeElement as HTMLButtonElement);
    if (at < 0) return;
    e.preventDefault();
    all[Math.min(all.length - 1, Math.max(0, at + step))]?.focus();
  };

  return (
    <div ref={ref} className={view === "grid" ? "store-grid" : "store-list"} onKeyDown={onKeyDown} onPointerLeave={() => onPreview(null)}>
      {tiles.map((item) =>
        item ? (
          <ItemTile
            key={item.cosmeticId}
            uuid={uuid}
            item={item}
            view={view}
            worn={worn?.cosmeticId === item.cosmeticId}
            pending={pending?.cosmeticId === item.cosmeticId}
            readOnly={readOnly}
            motion={motion}
            onWear={onWear}
            onPreview={onPreview}
          />
        ) : (
          <NoneTile
            key="none"
            slot={slot}
            view={view}
            on={!worn}
            pending={pending?.slot === slot && !pending.cosmeticId}
            readOnly={readOnly}
            minecraftCape={slot === "cape" && hasMinecraftCape}
            onNone={onNone}
            onPreview={onPreview}
          />
        ),
      )}
    </div>
  );
});

/** "None": the way to wear nothing in the slot, marked while nothing is worn there (a Minecraft cape shows then). */
function NoneTile({
  slot,
  view,
  on,
  pending,
  readOnly,
  minecraftCape,
  onNone,
  onPreview,
}: {
  slot: StoreSlot;
  view: "grid" | "list";
  on: boolean;
  pending: boolean;
  readOnly: boolean;
  minecraftCape: boolean;
  onNone: () => void;
  onPreview: (item: StoreItem | null) => void;
}) {
  const t = useT();
  const state = pending ? (
    <span className="store-tile-state is-busy">
      <Spinner size={12} />
      {t("store.inventory.updating")}
    </span>
  ) : on ? (
    <span className="store-tile-state is-worn">
      <Icon name="check" size={12} />
      {minecraftCape ? t("store.preview.minecraftCape") : t("store.slotbar.nothing")}
    </span>
  ) : (
    <span className="store-tile-state" />
  );
  return (
    <button
      type="button"
      className={`store-tile store-tile-${view} is-none ${on ? "is-worn" : ""}`}
      aria-pressed={on}
      aria-busy={pending || undefined}
      aria-disabled={readOnly || undefined}
      aria-label={t("store.slotbar.noneLabel", { slot: t(SLOT_LABELS[slot]) })}
      onClick={onNone}
      onPointerEnter={() => onPreview(null)}
      onFocus={() => onPreview(null)}
    >
      <span className="store-tile-art">
        <span className="store-none-art" aria-hidden>
          <Icon name="x" size={18} />
        </span>
      </span>
      <span className="store-tile-body">
        <span className="store-tile-name">{t("store.items.none")}</span>
        {state}
      </span>
    </button>
  );
}

const ItemTile = memo(function ItemTile({
  uuid,
  item,
  view,
  worn,
  pending,
  readOnly,
  motion,
  onWear,
  onPreview,
}: {
  uuid: string;
  item: StoreItem;
  view: "grid" | "list";
  worn: boolean;
  pending: boolean;
  readOnly: boolean;
  motion: boolean;
  onWear: (item: StoreItem) => void;
  onPreview: (item: StoreItem | null) => void;
}) {
  const t = useT();
  const gift = item.gift ? (item.giftFrom ? t("store.tile.giftFrom", { name: item.giftFrom }) : t("store.tile.gift")) : null;
  const added = shortDate(item.grantedAt);
  // Where it came from, when a newer Store says and it wasn't simply bought: "Gift from Flint", "Giveaway"… (a quiet line).
  const via = obtainedLabel(item);
  const obtained = via && item.obtainedVia !== "purchase" ? t(via) : null;
  const bought = item.obtainedVia === "purchase";
  const meta = gift ?? obtained ?? (added ? (bought ? t("store.obtained.purchasedOn", { date: added }) : t("store.inventory.added", { date: added })) : t(SLOT_LABELS[item.kind]));
  const animated = !!storeAnimation(uuid, item);
  // A test item is marked "Test" on its picture; its name drops the Store's "TEST · ", so two such names still differ.
  const test = item.test;
  const title = [
    item.name,
    meta,
    isConditional(item) ? t("store.rewards.note.conditional") : null,
    item.giftMessage ? t("store.tile.giftMessage", { message: item.giftMessage }) : null,
  ]
    .filter(Boolean)
    .join(" · ");
  const state = pending ? (
    <span className="store-tile-state is-busy">
      <Spinner size={12} />
      {t("store.inventory.updating")}
    </span>
  ) : worn ? (
    <span className="store-tile-state is-worn">
      <Icon name="check" size={12} />
      {t("store.inventory.wearing")}
    </span>
  ) : (
    <span className={`store-tile-state ${readOnly ? "" : "is-wear"}`}>{!readOnly && t("store.inventory.wear")}</span>
  );

  return (
    <button
      type="button"
      className={`store-tile store-tile-${view} ${worn ? "is-worn" : ""} ${pending ? "is-pending" : ""}`}
      aria-pressed={worn}
      aria-busy={pending || undefined}
      aria-disabled={readOnly || undefined}
      title={title}
      onClick={() => onWear(item)}
      onPointerEnter={() => onPreview(item)}
      // Keyboard focus previews; a click's focus doesn't (the pointer already did, and leaving should end it).
      onFocus={(e) => e.currentTarget.matches(":focus-visible") && onPreview(item)}
      onBlur={() => onPreview(null)}
    >
      <span className="store-tile-art">
        <ItemArt uuid={uuid} item={item} motion={motion} />
        {(animated || item.gift) && (
          <span className="store-tile-flags">
            {animated && (
              <span className="store-flag">
                <i aria-hidden />
                {t("store.inventory.animated")}
              </span>
            )}
            {item.gift && (
              <span className="store-flag is-gift" aria-label={t("store.tile.gift")}>
                <Icon name="gift" size={11} />
              </span>
            )}
          </span>
        )}
        {test && <span className="store-flag is-test">{t("store.tile.test")}</span>}
      </span>
      <span className="store-tile-body">
        <span className="store-tile-name">{shownName(item)}</span>
        {view === "grid" && obtained && <span className="store-tile-meta">{obtained}</span>}
        {view === "list" && (
          <span className="store-tile-meta">
            {test && <span className="store-tile-test">{t("store.tile.test")}</span>}
            {meta}
          </span>
        )}
        {state}
      </span>
    </button>
  );
});

/**
 * An item's picture. A cape shows its front, pixel for pixel; an animated cape plays there while it is on screen (its
 * poster until the frames are ready). Other items show their texture whole.
 */
export function ItemArt({ uuid, item, motion, small = false }: { uuid: string; item: StoreItem; motion: boolean; small?: boolean }) {
  const { url, error } = useStoreTexture(uuid, item);
  const key = textureKey(uuid, item);
  // Keyed on the texture, not the item object (every answer from the Store brings new objects for the same items).
  const source = useMemo(() => (motion && !small ? storeAnimation(uuid, item) : null), [key, motion, small]); // eslint-disable-line react-hooks/exhaustive-deps
  if (!url) return <span className={`store-art ${small ? "is-small" : ""} is-missing`}>{error ? <Icon name="alert" size={16} /> : null}</span>;
  if (item.kind !== "cape") return <img className={`store-art store-art-whole ${small ? "is-small" : ""}`} src={url} alt="" />;
  return (
    <span className={`store-art store-art-cape ${small ? "is-small" : ""}`} style={{ backgroundImage: `url(${url})` }}>
      {source && <AnimatedFront source={source} />}
    </span>
  );
}

/** An animated cape's front, drawn when its frame changes while on screen. Hidden until its frames are ready. */
function AnimatedFront({ source }: { source: AnimationSource }) {
  const ref = useRef<HTMLCanvasElement>(null);
  const [visible, setVisible] = useState(false);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    const el = ref.current;
    if (!el || typeof IntersectionObserver === "undefined") return;
    const observer = new IntersectionObserver(([entry]) => setVisible(entry.isIntersecting), { rootMargin: "120px" });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const el = ref.current;
    if (!visible || !el) return;
    let stop = () => {};
    let cancelled = false;
    thumbnailFrames(source).then(
      (frames) => {
        if (cancelled) return;
        el.width = frames.width;
        el.height = frames.height;
        const ctx = el.getContext("2d")!;
        let shown = -1;
        const draw = (now: number) => {
          const index = thumbnailFrame(frames, now);
          if (index === shown) return;
          frames.draw(ctx, index);
          shown = index;
        };
        // This moment's frame at once (the clock may be paused: window behind, nobody using it), then each change.
        draw(Date.now());
        stop = onThumbnailTick(draw);
        setReady(true);
      },
      () => {},
    );
    return () => {
      cancelled = true;
      stop();
    };
  }, [visible, source]);

  return <canvas ref={ref} className={`store-art-frames ${ready ? "is-ready" : ""}`} aria-hidden />;
}
