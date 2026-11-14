import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { Account, SavedSkin } from "../../lib/api";
import type { ShownCape } from "../../lib/capeSource";
import { FLINT_DISCORD_INVITE, cardAction, cardState, discordUnlock, rewardItem, type RewardCard } from "../../lib/community";
import { CATEGORIES, SLOT_LABELS, STORE_SLOTS, categoryById, categoryOf, kindOf, slotOf, type CategoryId, type StoreSlot } from "../../lib/cosmeticTypes";
import { shortDate } from "../../lib/format";
import { useT } from "../../lib/i18n";
import { shownName } from "../../lib/storePage";
import type { StoreInventory, StoreItem } from "../../lib/storeSync";
import {
  NO_FILTER,

  WARDROBE_SORTS,
  categoryInfo,
  filterCount,

  isEquipped,
  loadOutfits,
  outfitItems,
  saveOutfits,
  shownCategories,
  wardrobeList,
  wheelOf,

  wornOutfit,
  type Outfit,
  type WardrobeFilter,
  type WardrobeSort,
} from "../../lib/wardrobe";
import { Button, Checkbox, EmptyState, IconButton, Segmented, Spinner } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { Dropdown, Popover } from "../../ui/overlays";
import type { CommunityProps } from "../store/CommunityRewards";
import { StorePreview } from "../store/StorePreview";
import { useItemCape } from "../store/storeCape";
import type { Wearing } from "../store/wearing";
import { BadgesView, useBadges } from "./Badges";
import { shown as shownBadges } from "../../lib/badges";

import { ItemArt } from "./ItemArt";
import { OutfitsView } from "./Outfits";
import { SkinChanger, SkinFront, useSkinLibrary } from "./SkinChanger";
import { useLoadoutModels } from "./useModels";
import { VirtualGrid, WardrobeCard } from "./WardrobeCard";
import { EmoteWheel } from "./EmoteWheel";

type Props = {
  account: Account;
  /** The account's skin (data URL) and arm model. */
  skin: { dataUrl: string | null; model: "classic" | "slim" };
  /** The inventory with a wear on its way applied. */
  inventory: StoreInventory;
  /** A wear the Store hasn't answered yet. */
  pending: Wearing;
  error: { slot: StoreSlot; text: string } | null;
  readOnly: boolean;
  motion: boolean;
  /** The cape the account shows everywhere (the launcher-wide rule), for the preview while no other cape is looked at. */
  wornCape: ShownCape<ImageBitmap>;
  onWear: (slot: StoreSlot, cosmeticId: string | null) => void;
  onSetEmotes: (wheel: (string | null)[]) => Promise<void>;
  /** Opens the Store; with a kind (e.g. "wings"), its catalog filtered to that kind. */
  onOpenStore: (kind?: string) => void;
  onSkinApplied: () => Promise<void>;
  community: CommunityProps | null;
};

type Selection = { kind: "item"; item: StoreItem } | { kind: "outfit"; outfit: Outfit } | { kind: "skin"; skin: SavedSkin } | null;

/**
 * The Flint wardrobe: categories on the left (with how many items each has and a dot where something is worn), the
 * category's items in the middle (search, order, filters), and "Your loadout" on the right: the player in 3D wearing what
 * is selected, with Equip/Unequip. Outfits (saved combinations), the skin changer and badges are categories of their own.
 * The Store decides what is owned and worn; the page's rules are lib/wardrobe.ts, the kinds lib/cosmeticTypes.ts.
 */
export function Wardrobe(props: Props) {
  const { account, skin, inventory, pending, error, readOnly, motion, wornCape, onWear, onSetEmotes, onOpenStore, community } = props;
  const t = useT();
  const uuid = account.uuid;
  const badgeCount = shownBadges(useBadges(uuid)[0]).badges.length;
  const [category, setCategory] = useState<CategoryId>(() => {
    const info = categoryInfo(inventory);
    return CATEGORIES.find((c) => c.kind && info[c.id].count > 0)?.id ?? "capes";
  });
  // Each category remembers what was selected in it.
  const [selections, setSelections] = useState<Partial<Record<CategoryId, Selection>>>({});
  const selection = selections[category] ?? null;
  const setSelection = useCallback((next: Selection) => setSelections((all) => ({ ...all, [category]: next })), [category]);
  const [query, setQuery] = useState("");
  const [sort, setSort] = useState<WardrobeSort>("equipped");
  const [filter, setFilter] = useState<WardrobeFilter>(NO_FILTER);
  const [outfits, setOutfitsState] = useState<Outfit[]>(() => loadOutfits(uuid));
  const [variant, setVariant] = useState<"classic" | "slim">(skin.model);
  const library = useSkinLibrary(uuid);
  const setOutfits = useCallback(
    (next: Outfit[]) => {
      setOutfitsState(next);
      saveOutfits(uuid, next);
    },
    [uuid],
  );

  // Community rewards the account doesn't have yet: locked cards in their category.
  const cards = community?.cards ?? [];
  const listed = useMemo(() => cards.filter((c) => !c.item).map((c) => rewardItem(c.reward)), [cards]);
  const all = useMemo(() => [...inventory.owned, ...listed.filter((l) => !inventory.owned.some((o) => o.cosmeticId === l.cosmeticId))], [inventory.owned, listed]);
  const info = useMemo(() => categoryInfo(inventory, listed), [inventory, listed]);
  const sidebar = shownCategories(info);
  const meta = categoryById(category);
  const store = !!meta.kind || category === "more";
  const list = useMemo(
    () => (store ? wardrobeList(all, { category, query, sort, filter, equipped: inventory.equipped }) : []),
    [all, store, category, query, sort, filter, inventory.equipped],
  );
  const inCategory = store && all.some((i) => categoryOf(i) === category);

  const pick = (next: CategoryId) => {
    setCategory(next);
    setQuery("");
    setFilter(NO_FILTER);
  };

  // What the preview wears: what is worn, with the selection (an item in its slot, or a whole outfit) put on.
  const owned = useMemo(() => new Map(inventory.owned.map((o) => [o.cosmeticId, o])), [inventory.owned]);
  const shown = useMemo(() => {
    const slots = Object.fromEntries(STORE_SLOTS.map((slot) => [slot, owned.get(inventory.equipped[slot] ?? "") ?? null])) as Record<StoreSlot, StoreItem | null>;
    if (selection?.kind === "item") {
      const slot = slotOf(selection.item);
      if (slot) slots[slot] = selection.item;
    } else if (selection?.kind === "outfit") {
      for (const slot of STORE_SLOTS) if (slot in selection.outfit.slots) slots[slot] = owned.get(selection.outfit.slots[slot] ?? "") ?? null;
    }
    return slots;
  }, [owned, inventory.equipped, selection]);
  const wornCapeItem = owned.get(inventory.equipped.cape ?? "") ?? null;
  const looking = shown.cape && shown.cape.cosmeticId !== wornCapeItem?.cosmeticId ? shown.cape : null;
  const lookCape = useItemCape(uuid, looking, motion);
  const cape: ShownCape<ImageBitmap> = looking ? (lookCape ?? wornCape) : shown.cape === null && wornCapeItem ? { source: "none" } : wornCape;
  const modelItems = useMemo(() => STORE_SLOTS.filter((s) => s !== "cape").map((s) => shown[s]), [shown]);
  const models = useLoadoutModels(uuid, modelItems);
  const previewSkin = selection?.kind === "skin" ? (library.pngs[selection.skin.id] ?? null) : skin.dataUrl;
  const previewModel = selection?.kind === "skin" ? variant : skin.model;
  const outfit = wornOutfit(outfits, inventory);

  useEffect(() => {
    if (selection?.kind === "skin") setVariant(selection.skin.variant);
  }, [selection]);

  // A selected item that went away (refunded, a reward that stopped counting) is no longer selected.
  useEffect(() => {
    if (selection?.kind === "item" && !selection.item.listed && !owned.has(selection.item.cosmeticId)) setSelection(null);
  }, [owned, selection]);

  const selectedItem = selection?.kind === "item" ? selection.item : null;
  const toggle = (item: StoreItem) => {
    const slot = slotOf(item);
    if (!slot || readOnly || item.listed) return;
    onWear(slot, isEquipped(inventory, item) ? null : item.cosmeticId);
  };

  const center = (() => {
    if (category === "outfits")
      return (
        <OutfitsView
          uuid={uuid}
          inventory={inventory}
          outfits={outfits}
          selected={selection?.kind === "outfit" ? selection.outfit.id : null}
          worn={outfit?.id ?? null}
          motion={motion}
          readOnly={readOnly}
          onSelect={(o) => setSelection(o ? { kind: "outfit", outfit: o } : null)}
          onChange={setOutfits}
          onWear={onWear}
        />
      );
    if (category === "skins")
      return (
        <SkinChanger
          uuid={uuid}
          library={library}
          selected={selection?.kind === "skin" ? selection.skin.id : null}
          onSelect={(s) => setSelection(s ? { kind: "skin", skin: s } : null)}
        />
      );
    if (category === "badges") return <BadgesView account={account} />;
    if (meta.soon) return <p className="wd-soon">{t("wardrobe.comingSoon")}</p>;
    return (
      <>

        {category === "emotes" && inCategory && (
          <EmoteWheel uuid={uuid} inventory={inventory} selected={selectedItem} readOnly={readOnly} motion={motion} onSet={onSetEmotes} onSelect={(item) => setSelection(item ? { kind: "item", item } : null)} />
        )}
        {inCategory && (
        <ItemsToolbar
          query={query}
          onQuery={setQuery}
          sort={sort}
          onSort={setSort}
          filter={filter}
          onFilter={setFilter}
          hasLocked={all.some((i) => i.listed && categoryOf(i) === category)}
        />
        )}
        {error && meta.kind && error.slot === slotOf({ kind: meta.kind }) && (
          <p className="store-inline-error" role="alert">
            <Icon name="alert" size={14} />
            {error.text}
          </p>
        )}
        {list.length ? (
          <VirtualGrid
            label={t(meta.label)}
            items={list}
            keyOf={(item) => item.cosmeticId}
            selectedKey={selectedItem?.cosmeticId ?? null}
            render={(item) => (
              <WardrobeCard
                uuid={uuid}
                item={item}
                selected={selectedItem?.cosmeticId === item.cosmeticId}
                equipped={isEquipped(inventory, item)}
                pending={!!pending && pending.cosmeticId === item.cosmeticId}
                motion={motion}
                onSelect={(it) => setSelection(selectedItem?.cosmeticId === it.cosmeticId ? null : { kind: "item", item: it })}
                onToggle={toggle}
              />
            )}
          />
        ) : query.trim() || filterCount(filter) ? (
          <EmptyState
            compact
            icon="search"
            title={query.trim() ? t("store.inventory.noMatch", { query: query.trim() }) : t("wardrobe.empty.filtered")}
            body={t("store.inventory.noMatchBody")}
            action={
              <Button
                size="sm"
                onClick={() => {
                  setQuery("");
                  setFilter(NO_FILTER);
                }}
              >
                {t("wardrobe.filter.clear")}
              </Button>
            }
          />
        ) : (
          <EmptyState
            icon={meta.icon}
            title={t(meta.empty ?? "wardrobe.empty.more")}
            body={t("wardrobe.empty.body")}
            action={
              <Button variant="secondary" iconRight="external" onClick={() => onOpenStore(meta.kind ?? undefined)}>
                {t("wardrobe.shop")}
              </Button>
            }
          />
        )}
      </>
    );
  })();

  return (
    <div className="wardrobe">
      <nav className="wd-side" aria-label={t("wardrobe.categories")}>
        <ul>
          {sidebar.map((id, i) => {
            const c = categoryById(id);
            const count = c.soon ? null : id === "outfits" ? outfits.length : id === "skins" ? library.skins.length : id === "badges" ? badgeCount : info[id].count;
            const on = c.soon ? false : id === "outfits" ? !!outfit : id === "badges" ? true : info[id].equipped;
            const separated = i > 0 && c.group !== categoryById(sidebar[i - 1]).group;
            return (
              <li key={id} className={separated ? "wd-cat-sep" : undefined}>
                <button type="button" className={`wd-cat ${category === id ? "is-active" : ""}`} title={t(c.label)} aria-current={category === id ? "page" : undefined} onClick={() => pick(id)}>
                  <Icon name={c.icon} size={16} />
                  <span className="wd-cat-name">{t(c.label)}</span>
                  {on && <i className="wd-dot" aria-label={t("wardrobe.card.equipped")} />}
                  {count !== null && <span className="wd-cat-count">{count}</span>}
                </button>
              </li>
            );
          })}
        </ul>
      </nav>

      <section className="wd-main" aria-labelledby="wd-main-title">
        <header className="wd-main-head">
          <h2 id="wd-main-title">{t(meta.label)}</h2>
          {store && inCategory && <span className="wd-main-count">{list.length}</span>}
        </header>
        <div className="wd-main-body" key={category}>
          {center}
        </div>
      </section>

      <aside className="wd-loadout" aria-labelledby="wd-loadout-title">
        <div className="wd-loadout-head">
          <h2 id="wd-loadout-title">{t("wardrobe.loadout.title")}</h2>
          <span className="wd-outfit-name" title={outfit?.name}>
            {outfit ? outfit.name : t("wardrobe.loadout.custom")}
          </span>
        </div>
        <div className="wd-stage">
          <StorePreview
            skin={previewSkin}
            model={previewModel}
            cape={cape}
            motion={motion}
            models={models}
            zoomable
            initialZoom={0.56}
            facing={category === "capes" || category === "wings" || category === "outfits" ? "back" : "front"}
            label={shown.cape ? t("store.preview.labelCape", { name: account.username, cape: shownName(shown.cape) }) : t("store.preview.label", { name: account.username })}
            tag={
              selection && selection.kind !== "skin" && !(selection.kind === "item" && isEquipped(inventory, selection.item)) ? (
                <>
                  <span>{t("store.preview.previewing")}</span>
                  <strong>{selection.kind === "item" ? shownName(selection.item) : selection.outfit.name}</strong>
                </>
              ) : null
            }
          />
        </div>
        <LoadoutPanel
          uuid={uuid}
          selection={selection}
          inventory={inventory}
          pending={pending}
          readOnly={readOnly}
          motion={motion}
          onSetEmotes={onSetEmotes}
          variant={variant}
          onVariant={setVariant}
          cards={cards}
          community={community}
          onWear={onWear}

          onSkinApplied={props.onSkinApplied}
          library={library}
          shown={shown}
        />
      </aside>
    </div>
  );
}

/** Search, order and filters over a category's grid. */
function ItemsToolbar({
  query,
  onQuery,
  sort,
  onSort,
  filter,
  onFilter,
  hasLocked,
}: {
  query: string;
  onQuery: (q: string) => void;
  sort: WardrobeSort;
  onSort: (s: WardrobeSort) => void;
  filter: WardrobeFilter;
  onFilter: (f: WardrobeFilter) => void;
  hasLocked: boolean;
}) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const anchor = useRef<HTMLButtonElement>(null);
  const count = filterCount(filter);
  return (
    <div className="wd-toolbar">
      <label className="store-search wd-search">
        <Icon name="search" size={15} />
        <input type="search" value={query} placeholder={t("store.inventory.search")} aria-label={t("store.inventory.search")} spellCheck={false} onChange={(e) => onQuery(e.target.value)} />
        {query && <IconButton icon="x" size="sm" label={t("store.inventory.clearSearch")} onClick={() => onQuery("")} />}
      </label>
      <Dropdown
        label={t("store.inventory.sort.label")}
        value={sort}
        onChange={onSort}
        options={WARDROBE_SORTS.map((s) => ({ value: s.value, label: t(s.label) }))}
        className="wd-sort"
      />
      <button
        ref={anchor}
        type="button"
        className={`btn btn-secondary btn-md wd-filter ${count ? "is-on" : ""}`}
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
      >
        <Icon name="filter" size={15} />
        <span>{t("wardrobe.filter.label")}</span>
        {count > 0 && <span className="wd-filter-count">{count}</span>}
      </button>
      <Popover anchor={anchor} open={open} onClose={() => setOpen(false)} placement="bottom-end" label={t("wardrobe.filter.label")} className="wd-filter-pop">
        <div className="wd-filter-body">
          {hasLocked && (
            <div className="wd-filter-group" role="group" aria-label={t("wardrobe.filter.show")}>
              <span>{t("wardrobe.filter.show")}</span>
              <Segmented
                label={t("wardrobe.filter.show")}
                value={filter.show}
                onChange={(show) => onFilter({ ...filter, show })}
                options={[
                  { value: "all", label: t("wardrobe.filter.all") },
                  { value: "owned", label: t("wardrobe.filter.owned") },
                  { value: "locked", label: t("store.rewards.status.locked") },
                ]}
              />
            </div>
          )}
          <Checkbox checked={filter.animated} onChange={(animated) => onFilter({ ...filter, animated })} label={t("wardrobe.filter.animated")} />
          {count > 0 && (
            <Button size="sm" variant="ghost" onClick={() => onFilter(NO_FILTER)}>
              {t("wardrobe.filter.clear")}
            </Button>
          )}
        </div>
      </Popover>
    </div>
  );
}

/** Under the preview: what is selected, and the one thing to do with it (Equip, Unequip, Apply, Wear outfit). */
function LoadoutPanel({
  uuid,
  selection,
  inventory,
  pending,
  readOnly,
  motion,
  variant,
  onVariant,
  cards,
  community,
  onWear,
  onSkinApplied,
  onSetEmotes,
  library,
  shown,
}: {
  uuid: string;
  selection: Selection;
  inventory: StoreInventory;
  pending: Wearing;
  readOnly: boolean;
  motion: boolean;
  variant: "classic" | "slim";
  onVariant: (v: "classic" | "slim") => void;
  cards: RewardCard[];
  community: CommunityProps | null;
  onWear: (slot: StoreSlot, cosmeticId: string | null) => void;
  onSkinApplied: () => Promise<void>;
  onSetEmotes: (wheel: (string | null)[]) => Promise<void>;
  library: ReturnType<typeof useSkinLibrary>;
  shown: Record<StoreSlot, StoreItem | null>;
}) {
  const t = useT();
  const [busy, setBusy] = useState(false);

  if (selection?.kind === "skin")
    return (
      <div className="wd-panel">
        <div className="wd-panel-title">
          <span className="wd-panel-art">
            <SkinFront src={library.pngs[selection.skin.id] ?? null} slim={variant === "slim"} />
          </span>
          <span>
            <strong>{selection.skin.name}</strong>
            <span className="faint">{selection.skin.source === "username" ? t("wardrobe.skins.fromUser", { name: selection.skin.origin }) : t("wardrobe.skins.fromUrl")}</span>
          </span>
        </div>
        <Segmented
          label={t("skins.change.armStyle")}
          value={variant}
          onChange={onVariant}
          options={[
            { value: "classic", label: t("skins.change.classic") },
            { value: "slim", label: t("skins.change.slim") },
          ]}
        />
        <Button
          variant="primary"
          icon="upload"
          loading={library.applying === selection.skin.id}
          onClick={() => void library.apply(selection.skin, variant).then(async (ok) => { if (ok) await onSkinApplied(); })}
        >
          {t("skins.change.apply")}
        </Button>
        <p className="faint wd-note">{t("wardrobe.skins.applyNote")}</p>
      </div>
    );

  if (selection?.kind === "outfit") {
    const items = outfitItems(selection.outfit, inventory);
    return (
      <div className="wd-panel">
        <div className="wd-panel-title">
          <span>
            <strong>{selection.outfit.name}</strong>
            <span className="faint">{t("wardrobe.outfits.items", { count: items.length })}</span>
          </span>
        </div>
        <p className="faint wd-note">{t("wardrobe.outfits.wearHint")}</p>
      </div>
    );
  }

  if (selection?.kind === "item") {
    const item = selection.item;
    const slot = slotOf(item);
    const kind = kindOf(item.kind);
    const equipped = isEquipped(inventory, item);
    const wheel = wheelOf(inventory);
    const onWheel = kind === "emote" && wheel.includes(item.cosmeticId);
    const card = cards.find((c) => c.reward.cosmeticId === item.cosmeticId) ?? null;
    const isPending = !!pending && pending.slot === slot;

    return (
      <div className="wd-panel">
        <div className="wd-panel-title">
          <span className="wd-panel-art">
            <ItemArt uuid={uuid} item={item} motion={motion} small />
          </span>
          <span>
            <strong title={item.name}>{shownName(item)}</strong>
            <span className="faint">{[slot ? t(SLOT_LABELS[slot]) : kind === "emote" ? t("wardrobe.cat.emotes") : t("wardrobe.cat.more"), itemMeta(item, t)].filter(Boolean).join(" · ")}</span>
          </span>
        </div>
        {item.giftMessage && <p className="wd-gift-note">{t("store.tile.giftMessage", { message: item.giftMessage })}</p>}
        {item.listed && card ? (
          <RewardPanel card={card} community={community} />
        ) : kind === "emote" ? (
          <Button
            variant={onWheel ? "secondary" : "primary"}
            icon={onWheel ? "x" : "plus"}
            disabled={readOnly || busy || (!onWheel && inventory.emotes == null)}
            loading={busy}
            onClick={() => {
              setBusy(true);
              const free = wheel.indexOf(null);
              const next = onWheel ? wheel.map((id) => (id === item.cosmeticId ? null : id)) : wheel.map((id, i) => (i === (free >= 0 ? free : 0) ? item.cosmeticId : id));
              void onSetEmotes(next).finally(() => setBusy(false));
            }}
          >
            {onWheel ? t("wardrobe.emotes.remove") : t("wardrobe.emotes.add")}
          </Button>
        ) : slot ? (
          <Button
            variant={equipped ? "secondary" : "primary"}
            icon={equipped ? "x" : "check"}
            disabled={readOnly}
            loading={isPending}
            onClick={() => onWear(slot, equipped ? null : item.cosmeticId)}
          >
            {equipped ? t("wardrobe.unequip") : t("wardrobe.equip")}
          </Button>
        ) : (
          <p className="faint wd-note">{t("wardrobe.more.note")}</p>
        )}
        {item.asset?.model?.hidesCape && <p className="faint wd-note">{t("wardrobe.hidesCape")}</p>}
        {kind === "emote" && <p className="faint wd-note">{t("wardrobe.emotes.inGame")}</p>}

      </div>
    );
  }

  // Nothing selected: what is worn, slot by slot.
  const worn = STORE_SLOTS.map((slot) => [slot, shown[slot]] as const).filter(([, item]) => item);
  return (
    <div className="wd-panel">
      {worn.length ? (
        <ul className="wd-worn" aria-label={t("wardrobe.loadout.wearing")}>
          {worn.map(([slot, item]) => (
            <li key={slot}>
              <span className="wd-worn-art">
                <ItemArt uuid={uuid} item={item!} motion={false} small />
              </span>
              <span className="wd-worn-text">
                <span className="faint">{t(SLOT_LABELS[slot])}</span>
                <strong title={item!.name}>{shownName(item!)}</strong>
              </span>
              <IconButton icon="x" size="sm" label={t("wardrobe.unequipNamed", { name: shownName(item!) })} disabled={readOnly} onClick={() => onWear(slot, null)} />
            </li>
          ))}
        </ul>
      ) : (
        <p className="faint wd-note">{t("wardrobe.loadout.nothing")}</p>
      )}
    </div>
  );
}

/** When and how an owned item came: "Gift from Kestrel", "Bought Nov 3", "Added Nov 3"; nothing for a listed reward. */
function itemMeta(item: StoreItem, t: ReturnType<typeof useT>): string | null {
  if (item.listed) return null;
  if (item.gift && item.giftFrom) return t("store.tile.giftFrom", { name: item.giftFrom });
  const date = shortDate(item.grantedAt);
  if (!date) return null;
  return item.obtainedVia === "purchase" ? t("store.obtained.purchasedOn", { date }) : t("store.inventory.added", { date });
}

/** A Community Reward the account doesn't have: what earns it, and the one thing to do next. */
function RewardPanel({ card, community }: { card: RewardCard; community: CommunityProps | null }) {
  const t = useT();
  const state = cardState(card);
  const action = cardAction(card);
  const discord = discordUnlock(card.reward);
  // A Flint Discord reward always offers the server (the Store's invite, else Flint's), and Connect Discord too when the
  // Store can't see the account's Discord yet.
  const invite = action?.kind === "invite" ? action : discord && card.status === "locked" ? { url: FLINT_DISCORD_INVITE, label: discord.button } : null;
  return (
    <div className="wd-reward">
      <span className={`wd-reward-state is-${state.tone}`}>
        {state.busy ? <Spinner size={11} /> : <Icon name="lock" size={12} />}
        {t(state.label)}
      </span>
      {discord ? <p className="wd-reward-req">{t(discord.how)}</p> : card.reward.requirement.label && <p className="wd-reward-req">{card.reward.requirement.label}</p>}
      {state.note && <p className="faint wd-note">{t(state.note)}</p>}
      {community && invite && (
        <Button className="btn-discord" icon="discord" iconRight="external" onClick={() => community.onInvite(invite.url)}>
          {t(discord ? discord.button : invite.label)}
        </Button>
      )}
      {community && action?.kind === "connect" && (
        <Button className="btn-discord-quiet" icon="discord" onClick={() => community.onConnect()}>
          {t(action.label)}
        </Button>
      )}
    </div>
  );
}
