import { useLayoutEffect, useMemo, useRef, useState, type ReactNode } from "react";
import type { ImportSourceProfile } from "../../lib/api";
import { useT } from "../../lib/i18n";
import { sound } from "../../lib/sound";
import { Button, Checkbox } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { Popover, Tooltip } from "../../ui/overlays";
import { chips, importLabel, toggleKey } from "./model";

/**
 * What's picked, as removable chips (the first few, then "+N more", which lists the rest), Clear, the worlds
 * choice and the import button. `start` and `end` hold what the host adds (onboarding's Back and Skip).
 */
export function SelectionBar({
  profiles,
  selected,
  onSelect,
  worlds,
  onWorlds,
  onImport,
  start,
  end,
  className = "",
}: {
  profiles: ImportSourceProfile[];
  selected: string[];
  onSelect: (keys: string[]) => void;
  worlds: boolean;
  onWorlds: (on: boolean) => void;
  onImport: () => void;
  start?: ReactNode;
  end?: ReactNode;
  className?: string;
}) {
  const t = useT();
  const byKey = useMemo(() => new Map(profiles.map((p) => [p.key, p])), [profiles]);
  const items = selected.flatMap((k) => byKey.get(k) ?? []);
  // As many chips as the bar has room for (the "+N more" chip counts as one), up to five; a chip is up to ~160 px wide.
  const picksRef = useRef<HTMLDivElement>(null);
  const [slots, setSlots] = useState(3);
  useLayoutEffect(() => {
    const el = picksRef.current;
    if (!el || typeof ResizeObserver === "undefined") return;
    const measure = () => setSlots(Math.max(2, Math.min(5, Math.floor((el.clientWidth - 150) / 160))));
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(el);
    return () => observer.disconnect();
  }, []);
  const { shown, more } = chips(items, slots);
  const worldCount = items.reduce((n, p) => n + p.worlds, 0);
  const moreRef = useRef<HTMLButtonElement>(null);
  const [listOpen, setListOpen] = useState(false);
  const remove = (key: string) => {
    sound.play("tap");
    onSelect(toggleKey(selected, key, false));
  };

  return (
    <div className={`imp-bar ${className}`}>
      {start}
      <div ref={picksRef} className="imp-bar-picks">
        <strong className="imp-bar-count" aria-live="polite">
          {items.length ? t("import.selection.count", { count: items.length }) : t("import.selection.none")}
        </strong>
        {shown.map((p) => (
          <span key={p.key} className="imp-chip">
            <span className="imp-chip-name">{p.name}</span>
            <button type="button" aria-label={t("import.selection.remove", { name: p.name })} onClick={() => remove(p.key)}>
              <Icon name="x" size={12} />
            </button>
          </span>
        ))}
        {more > 0 && (
          <button ref={moreRef} type="button" className="imp-chip is-more" aria-haspopup="dialog" aria-expanded={listOpen} onClick={() => setListOpen((o) => !o)}>
            {t("import.selection.more", { count: more })}
          </button>
        )}
        {items.length > 0 && (
          <button
            type="button"
            className="imp-clear"
            onClick={() => {
              sound.play("tap");
              onSelect([]);
            }}
          >
            {t("import.selection.clear")}
          </button>
        )}
      </div>
      <div className="imp-bar-end">
        <Tooltip label={t("import.copyWorlds.tooltip")}>
          <Checkbox className="imp-worlds" checked={worlds} onChange={onWorlds}>
            {t("import.copyWorlds.label")}
            {worldCount > 0 && <span className="faint">{t("import.selection.worlds", { count: worldCount })}</span>}
          </Checkbox>
        </Tooltip>
        {end}
        <Button variant="primary" size="lg" iconRight="arrowRight" onClick={onImport} disabled={!items.length}>
          {importLabel(items.length)}
        </Button>
      </div>
      <Popover anchor={moreRef} open={listOpen && more > 0} onClose={() => setListOpen(false)} placement="top-start" label={t("import.selection.listLabel")} className="imp-picks-pop">
        <ul className="imp-picks-list scroll">
          {items.map((p) => (
            <li key={p.key}>
              <span>{p.name}</span>
              <button type="button" className="icon-btn icon-btn-sm icon-btn-ghost" aria-label={t("import.selection.remove", { name: p.name })} onClick={() => remove(p.key)}>
                <Icon name="x" size={13} />
              </button>
            </li>
          ))}
        </ul>
      </Popover>
    </div>
  );
}
