import { memo, useCallback, useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { useT } from "../../lib/i18n";
import { shownName } from "../../lib/storePage";
import type { StoreItem } from "../../lib/storeSync";
import { isAnimated, itemSource, type ItemSource } from "../../lib/wardrobe";
import { Spinner } from "../../ui/controls";
import { Icon, type IconName } from "../../ui/Icon";
import { ItemArt } from "./ItemArt";

const SOURCE_ICON: Record<NonNullable<ItemSource>, { icon: IconName; label: "wardrobe.source.discord" | "wardrobe.source.gift" | "wardrobe.source.creator" | "wardrobe.source.event" }> = {
  discord: { icon: "discord", label: "wardrobe.source.discord" },
  gift: { icon: "gift", label: "wardrobe.source.gift" },
  creator: { icon: "star", label: "wardrobe.source.creator" },
  event: { icon: "sparkle", label: "wardrobe.source.event" },
};

/**
 * One item in the wardrobe's grid: its picture, its name, and one state line (Equipped, Updating…, Locked). A small
 * corner shows where it came from (a Discord reward, a gift) and whether it moves. Selecting it shows it on the preview;
 * equipping is the loadout's button (or a double click).
 */
export const WardrobeCard = memo(function WardrobeCard({
  uuid,
  item,
  selected,
  equipped,
  pending,
  motion,
  onSelect,
  onToggle,
}: {
  uuid: string;
  item: StoreItem;
  selected: boolean;
  equipped: boolean;
  pending: boolean;
  motion: boolean;
  onSelect: (item: StoreItem) => void;
  /** Equip (or take off) at once: a double click. */
  onToggle?: (item: StoreItem) => void;
}) {
  const t = useT();
  const locked = !!item.listed;
  const source = itemSource(item);
  const animated = isAnimated(item);
  const sourceLabel = source === "gift" && item.giftFrom ? t("store.tile.giftFrom", { name: item.giftFrom }) : source ? t(SOURCE_ICON[source].label) : null;
  const state = pending ? (
    <span className="wd-card-state is-busy">
      <Spinner size={11} />
      {t("store.inventory.updating")}
    </span>
  ) : locked ? (
    <span className="wd-card-state is-locked">
      <Icon name="lock" size={11} />
      {t("store.rewards.status.locked")}
    </span>
  ) : equipped ? (
    <span className="wd-card-state is-on">
      <Icon name="check" size={12} />
      {t("wardrobe.card.equipped")}
    </span>
  ) : (
    <span className="wd-card-state">{t("wardrobe.card.owned")}</span>
  );
  const labels = [shownName(item), equipped ? t("wardrobe.card.equipped") : locked ? t("store.rewards.status.locked") : null, sourceLabel]
    .filter(Boolean)
    .join(", ");
  return (
    <button
      type="button"
      className={`wd-card ${selected ? "is-selected" : ""} ${equipped ? "is-equipped" : ""} ${locked ? "is-locked" : ""}`}
      aria-pressed={selected}
      aria-busy={pending || undefined}
      aria-label={labels}
      title={item.name}
      data-id={item.cosmeticId}
      onClick={() => onSelect(item)}
      onDoubleClick={() => !locked && onToggle?.(item)}
    >
      <span className="wd-card-art">
        <ItemArt uuid={uuid} item={item} motion={motion} />
        <span className="wd-card-flags">
          {animated && (
            <span className="wd-flag" title={t("store.inventory.animated")}>
              <Icon name="play" size={9} />
            </span>
          )}
          {source && (
            <span className={`wd-flag is-${source}`} title={sourceLabel ?? undefined}>
              <Icon name={SOURCE_ICON[source].icon} size={11} />
            </span>
          )}
        </span>
        {equipped && !pending && <span className="wd-card-dot" aria-hidden />}
        {locked && (
          <span className="wd-card-lock" aria-hidden>
            <Icon name="lock" size={13} />
          </span>
        )}
        {item.test && <span className="wd-flag is-test">{t("store.tile.test")}</span>}
      </span>
      <span className="wd-card-body">
        <span className="wd-card-name">{shownName(item)}</span>
        {state}
      </span>
    </button>
  );
});

/** A grid of cards with arrow-key movement (the columns follow the width). */
export function CardGrid({ children, label, className = "" }: { children: ReactNode; label: string; className?: string }) {
  const ref = useRef<HTMLDivElement>(null);
  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const el = ref.current;
    if (!el) return;
    const columns = Math.max(1, getComputedStyle(el).gridTemplateColumns.split(" ").filter(Boolean).length);
    const step = { ArrowRight: 1, ArrowLeft: -1, ArrowDown: columns, ArrowUp: -columns, Home: -Infinity, End: Infinity }[e.key];
    if (step === undefined) return;
    const all = Array.from(el.querySelectorAll<HTMLElement>(":scope > button, :scope > [data-card]"));
    const at = all.indexOf(document.activeElement as HTMLElement);
    if (at < 0) return;
    e.preventDefault();
    all[Math.min(all.length - 1, Math.max(0, at + step))]?.focus();
  };
  return (
    <div ref={ref} className={`wd-grid ${className}`} role="group" aria-label={label} onKeyDown={onKeyDown}>
      {children}
    </div>
  );
}

/**
 * A long grid drawn a few rows at a time: only the rows in (and just around) the scrolling column are in the page, so
 * 500 capes scroll as smoothly as 5. Columns follow the width like the plain grid (`--wd-card` wide at least); arrow keys
 * move between cards, scrolling the next one in when it isn't drawn yet.
 */
export function VirtualGrid<T>({
  items,
  keyOf,
  render,
  label,
  selectedKey,
}: {
  items: readonly T[];
  keyOf: (item: T) => string;
  render: (item: T) => ReactNode;
  label: string;
  selectedKey: string | null;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [box, setBox] = useState({ columns: 1, row: 240, gap: 16, top: 0, view: 800 });
  const focusAfter = useRef<number | null>(null);

  const measure = useCallback(() => {
    const el = ref.current;
    const scroller = el?.closest<HTMLElement>(".wd-main-body");
    if (!el || !scroller) return;
    const style = getComputedStyle(el);
    const min = parseFloat(style.getPropertyValue("--wd-card")) || 184;
    const gap = parseFloat(getComputedStyle(el.firstElementChild ?? el).columnGap) || 16;
    const width = el.clientWidth;
    const columns = Math.max(1, Math.floor((width + gap) / (min + gap)));
    const card = el.querySelector<HTMLElement>(".wd-card");
    const row = (card?.offsetHeight ?? (width - gap * (columns - 1)) / columns + 64) + gap;
    const top = Math.max(0, scroller.scrollTop - (el.offsetTop - scroller.offsetTop));
    setBox((b) => (b.columns === columns && Math.abs(b.row - row) < 0.5 && b.gap === gap && Math.abs(b.top - top) < 1 && b.view === scroller.clientHeight ? b : { columns, row, gap, top, view: scroller.clientHeight }));
  }, []);

  useLayoutEffect(() => {
    const el = ref.current;
    const scroller = el?.closest<HTMLElement>(".wd-main-body");
    if (!el || !scroller) return;
    measure();
    let frame = 0;
    const onScroll = () => {
      if (!frame) frame = requestAnimationFrame(() => ((frame = 0), measure()));
    };
    scroller.addEventListener("scroll", onScroll, { passive: true });
    const observer = new ResizeObserver(() => measure());
    observer.observe(el);
    observer.observe(scroller);
    return () => {
      scroller.removeEventListener("scroll", onScroll);
      observer.disconnect();
      cancelAnimationFrame(frame);
    };
  }, [measure]);

  // A new card size (the first card drawn) corrects the row height.
  useLayoutEffect(measure, [items.length, measure]);

  const rows = Math.ceil(items.length / box.columns);
  const first = Math.max(0, Math.floor(box.top / box.row) - 2);
  const last = Math.min(rows, Math.ceil((box.top + box.view) / box.row) + 2);
  const shown = items.slice(first * box.columns, last * box.columns);

  useEffect(() => {
    const at = focusAfter.current;
    if (at === null) return;
    const el = ref.current?.querySelector<HTMLElement>(`[data-index="${at}"] > .wd-card, [data-index="${at}"] > button`);
    if (el) {
      focusAfter.current = null;
      el.focus();
    }
  });

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const step = { ArrowRight: 1, ArrowLeft: -1, ArrowDown: box.columns, ArrowUp: -box.columns, Home: -Infinity, End: Infinity }[e.key];
    if (step === undefined) return;
    const cell = (document.activeElement as HTMLElement | null)?.closest<HTMLElement>("[data-index]");
    if (!cell) return;
    e.preventDefault();
    const target = Math.min(items.length - 1, Math.max(0, Number(cell.dataset.index) + step));
    const scroller = ref.current?.closest<HTMLElement>(".wd-main-body");
    const rowTop = Math.floor(target / box.columns) * box.row + (ref.current?.offsetTop ?? 0) - (scroller?.offsetTop ?? 0);
    if (scroller && (rowTop < scroller.scrollTop || rowTop + box.row > scroller.scrollTop + scroller.clientHeight)) {
      scroller.scrollTo({ top: Math.max(0, rowTop - box.row), behavior: "auto" });
    }
    focusAfter.current = target;
    measure();
  };

  return (
    <div ref={ref} className="wd-vgrid" style={{ height: Math.max(0, rows * box.row - box.gap) }} role="group" aria-label={label} onKeyDown={onKeyDown}>
      <div className="wd-grid" style={{ gridTemplateColumns: `repeat(${box.columns}, minmax(0, 1fr))`, transform: `translateY(${first * box.row}px)` }}>
        {shown.map((item, i) => (
          <div key={keyOf(item)} data-index={first * box.columns + i} className="wd-cell" aria-current={keyOf(item) === selectedKey || undefined}>
            {render(item)}
          </div>
        ))}
      </div>
    </div>
  );
}
