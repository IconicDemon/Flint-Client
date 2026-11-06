import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import type { ImportListing, ImportSourceProfile } from "../../lib/api";
import { list, shortDate } from "../../lib/format";
import { useT } from "../../lib/i18n";
import { sound } from "../../lib/sound";
import { Badge, Button, Checkbox, Segmented, TextField } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { Dropdown, Tooltip } from "../../ui/overlays";
import {
  SORTS,
  countLine,
  headerState,
  lastPlayedLabel,
  loaderCounts,
  loaderLabel,
  nameList,
  selectRange,
  selectable,
  toggleAll,
  toggleKey,
  visibleProfiles,
  type LoaderFilter,
  type SortKey,
  type TableView,
} from "./model";

/** Every row is this tall, so a long list can draw only the rows in view. */
const ROW_H = 48;
const HEAD_H = 40;
/** Up to this many rows are all drawn; past it, only those in view (plus a margin). */
const WINDOW_FROM = 120;
const OVERSCAN = 10;

const SETTINGS_LOADERS = ["forge", "neoforge", "quilt"] as const;

/**
 * A launcher's profiles: search, a loader filter with counts, sorting, and a tick per row. Space ticks the focused
 * row, Shift-click (or Shift+Space) ticks the range from the last row ticked, the arrow keys move between rows.
 */
export function ProfileTable({ listing, selected, onSelect, loading = false }: { listing: ImportListing; selected: string[]; onSelect: (keys: string[]) => void; loading?: boolean }) {
  const t = useT();
  const profiles = listing.profiles;
  const [view, setView] = useState<TableView>({ query: "", loader: "all", sort: "played", hideImported: false });
  const counts = useMemo(() => loaderCounts(profiles), [profiles, t]);
  // A rescan can drop the filtered loader; the table then shows all of them again.
  const loader = counts.some((c) => c.value === view.loader) ? view.loader : "all";
  // The search also matches the loader's name as shown, so it follows the language too.
  const rows = useMemo(() => visibleProfiles(profiles, { ...view, loader }), [profiles, view, loader, t]);
  const picked = useMemo(() => new Set(selected), [selected]);
  const head = headerState(rows, selected);
  const importedCount = profiles.filter((p) => p.imported).length;
  const anchor = useRef<string | null>(null);
  // Shift as last seen by the table, for ticks made with the keyboard (Space) as well as the mouse.
  const shift = useRef(false);
  const trackShift = (e: { shiftKey: boolean }) => {
    shift.current = e.shiftKey;
  };

  const toggle = (p: ImportSourceProfile, withShift: boolean) => {
    if (!selectable(p)) return;
    const on = !picked.has(p.key);
    sound.play("toggle");
    onSelect(withShift && anchor.current ? selectRange(selected, rows, anchor.current, p.key, on) : toggleKey(selected, p.key, on));
    anchor.current = p.key;
  };

  // Only the rows in view are drawn once the list is long; the rest are space.
  const body = useRef<HTMLDivElement>(null);
  const windowed = rows.length > WINDOW_FROM;
  const [range, setRange] = useState({ first: 0, last: WINDOW_FROM });
  const measure = useCallback(() => {
    const el = body.current;
    if (!el) return;
    const top = Math.max(0, el.scrollTop - HEAD_H);
    const first = Math.max(0, Math.floor(top / ROW_H) - OVERSCAN);
    const last = Math.min(rows.length, Math.ceil((top + el.clientHeight) / ROW_H) + OVERSCAN);
    setRange((prev) => (prev.first === first && prev.last === last ? prev : { first, last }));
  }, [rows.length]);
  useLayoutEffect(() => {
    measure();
    const el = body.current;
    if (!el || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(measure);
    observer.observe(el);
    return () => observer.disconnect();
  }, [measure]);
  // A new search, filter or sort starts at the top of the list.
  useEffect(() => {
    if (body.current) body.current.scrollTop = 0;
  }, [view.query, loader, view.sort, view.hideImported]);
  const first = windowed ? range.first : 0;
  const last = windowed ? range.last : rows.length;

  /** Up and Down move between rows' ticks, drawing the next one first when the list is long. */
  const moveFocus = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key !== "ArrowDown" && e.key !== "ArrowUp") return;
    const from = Number((e.target as HTMLElement).closest<HTMLElement>("[data-row]")?.dataset.row);
    if (!Number.isFinite(from)) return;
    e.preventDefault();
    const to = Math.max(0, Math.min(rows.length - 1, from + (e.key === "ArrowDown" ? 1 : -1)));
    const el = body.current;
    if (!el) return;
    const top = HEAD_H + to * ROW_H;
    if (top < el.scrollTop + HEAD_H) el.scrollTop = top - HEAD_H;
    else if (top + ROW_H > el.scrollTop + el.clientHeight) el.scrollTop = top + ROW_H - el.clientHeight;
    requestAnimationFrame(() => el.querySelector<HTMLInputElement>(`[data-row="${to}"] input`)?.focus());
  };

  const settingsLoaders = SETTINGS_LOADERS.filter((l) => profiles.some((p) => p.loader === l && p.support === "settings")).map(loaderLabel);
  const unsupported = profiles.filter((p) => p.support === "unsupported").length;
  const shared = profiles.some((p) => p.sharedFolder);
  const copies = profiles.filter((p) => p.imported && picked.has(p.key));

  return (
    <div className="imp-table-wrap">
      <div className="imp-toolbar">
        <TextField
          icon="search"
          className="imp-search"
          placeholder={t("import.table.searchPlaceholder", { count: profiles.length })}
          aria-label={t("import.table.searchLabel")}
          value={view.query}
          onChange={(e) => setView((v) => ({ ...v, query: e.target.value }))}
          onKeyDown={(e) => {
            if (e.key === "Escape" && view.query) {
              e.stopPropagation();
              setView((v) => ({ ...v, query: "" }));
            }
          }}
        />
        {counts.length > 2 && (
          <Segmented<LoaderFilter>
            label={t("import.table.filterLoader")}
            value={loader}
            onChange={(l) => setView((v) => ({ ...v, loader: l }))}
            options={counts.map((c) => ({ value: c.value, label: c.label, count: c.count }))}
          />
        )}
        <Dropdown<SortKey>
          className="imp-sort"
          label={t("import.table.sortLabel")}
          value={view.sort}
          options={SORTS.map((s) => ({ value: s.value, label: t(s.label) }))}
          onChange={(sort) => setView((v) => ({ ...v, sort }))}
        />
      </div>

      <div
        className={`imp-table ${loading ? "is-loading" : ""}`}
        role="table"
        aria-label={t("import.table.label", { launcher: listing.launcher.name })}
        aria-rowcount={rows.length + 1}
        aria-busy={loading || undefined}
      >
        <div
          ref={body}
          className="imp-table-body scroll"
          onScroll={windowed ? measure : undefined}
          onKeyDown={(e) => {
            trackShift(e);
            moveFocus(e);
          }}
          onKeyUp={trackShift}
          onPointerDownCapture={trackShift}
        >
          <div className="imp-row is-head" role="row" aria-rowindex={1}>
            <span role="columnheader" className="imp-cell-check">
              <Checkbox
                checked={head.checked}
                indeterminate={head.indeterminate}
                disabled={head.disabled}
                label={head.checked ? t("import.table.clearShown") : t("import.table.selectShown")}
                onChange={() => {
                  anchor.current = null;
                  onSelect(toggleAll(selected, rows));
                }}
              />
            </span>
            <span role="columnheader">{t("import.table.col.name")}</span>
            <span role="columnheader">{t("import.table.col.version")}</span>
            <span role="columnheader">{t("import.table.col.loader")}</span>
            <span role="columnheader">{t("import.table.col.mods")}</span>
            <span role="columnheader">{t("import.table.col.lastPlayed")}</span>
          </div>
          {loading && rows.length === 0 ? (
            <SkeletonRows />
          ) : rows.length === 0 ? (
            <div className="imp-table-empty">
              <span className="muted">{view.query ? t("import.table.noMatch", { query: view.query.trim() }) : t("import.table.empty")}</span>
              {(view.query || loader !== "all" || view.hideImported) && (
                <Button size="sm" variant="ghost" onClick={() => setView((v) => ({ ...v, query: "", loader: "all", hideImported: false }))}>
                  {t("import.table.showAll")}
                </Button>
              )}
            </div>
          ) : (
            <>
              {first > 0 && <div style={{ height: first * ROW_H }} aria-hidden />}
              {rows.slice(first, last).map((p, i) => (
                <Row key={p.key} profile={p} index={first + i} on={picked.has(p.key)} onToggle={(withShift) => toggle(p, withShift || shift.current)} />
              ))}
              {last < rows.length && <div style={{ height: (rows.length - last) * ROW_H }} aria-hidden />}
            </>
          )}
        </div>
        <div className="imp-table-foot">
          <span>
            {countLine(profiles.length, rows.length, view.hideImported ? 0 : importedCount)}
            {importedCount > 0 && (
              <button type="button" className="imp-foot-toggle" onClick={() => setView((v) => ({ ...v, hideImported: !v.hideImported }))}>
                {view.hideImported
                  ? t("import.table.showImported", { count: importedCount })
                  : importedCount === 1
                    ? t("import.table.hideImportedOne")
                    : t("import.table.hideImportedAll")}
              </button>
            )}
          </span>
          {rows.length > 2 && <span className="faint">{t("import.table.rangeHint")}</span>}
        </div>
      </div>

      {(settingsLoaders.length > 0 || shared || unsupported > 0 || copies.length > 0) && (
        <ul className="imp-notes">
          {copies.length > 0 && (
            <li className="is-accent">
              <Icon name="copy" size={14} />
              <span>
                {copies.length === 1
                  ? t("import.table.note.copyOne", { name: copies[0].name })
                  : t("import.table.note.copyMany", { names: nameList(copies.map((p) => p.name)) })}
              </span>
            </li>
          )}
          {settingsLoaders.length > 0 && (
            <li>
              <Icon name="info" size={14} />
              <span>
                <strong>{t("import.table.note.settingsOnlyLead")}</strong> {t("import.table.note.settingsOnly", { loaders: list(settingsLoaders, "or") })}
              </span>
            </li>
          )}
          {shared && (
            <li>
              <Icon name="folder" size={14} />
              <span>{t("import.table.note.sharedFolder", { launcher: listing.launcher.name })}</span>
            </li>
          )}
          {unsupported > 0 && (
            <li>
              <Icon name="minimize" size={14} />
              <span>{t("import.table.note.unsupported", { count: unsupported })}</span>
            </li>
          )}
        </ul>
      )}
    </div>
  );
}

function Row({ profile: p, index, on, onToggle }: { profile: ImportSourceProfile; index: number; on: boolean; onToggle: (shift: boolean) => void }) {
  const t = useT();
  const blocked = !selectable(p);
  const label = blocked
    ? p.supportNote
      ? t("import.table.row.blockedWhy", { name: p.name, reason: p.supportNote })
      : t("import.table.row.blocked", { name: p.name })
    : t(p.imported ? "import.table.row.importAgain" : "import.table.row.import", { name: p.name });
  return (
    <div
      role="row"
      aria-rowindex={index + 2}
      aria-disabled={blocked || undefined}
      data-row={index}
      className={`imp-row ${on ? "is-on" : ""} ${blocked ? "is-blocked" : ""} ${p.imported ? "is-in" : ""}`}
      title={blocked ? (p.supportNote ?? undefined) : undefined}
      onClick={(e) => onToggle(e.shiftKey)}
    >
      <span role="cell" className="imp-cell-check">
        <label className={`checkbox ${blocked ? "is-disabled" : ""}`} onClick={(e) => e.stopPropagation()}>
          <input type="checkbox" checked={on} disabled={blocked} aria-label={label} onChange={() => onToggle(false)} />
          <span className="checkbox-box" aria-hidden>
            <Icon name="check" size={13} />
          </span>
        </label>
      </span>
      <span role="cell" className="imp-cell-name">
        <span className="imp-picon" aria-hidden>
          {p.icon ? <img src={p.icon} alt="" loading="lazy" /> : <Icon name="cube" size={14} />}
        </span>
        <span className="imp-name" title={p.name}>
          {p.name}
        </span>
        {p.imported && (
          <Tooltip label={t("import.table.row.inFlintTooltip", { name: p.imported.name })}>
            <Badge tone="success">
              <span className="imp-dot" aria-hidden />
              {t("import.table.row.inFlint")}
            </Badge>
          </Tooltip>
        )}
        {p.support === "settings" && (
          <Tooltip label={p.supportNote ?? t("import.table.row.settingsOnlyTooltip")}>
            <span className="badge imp-badge-outline">{t("import.table.row.settingsOnly")}</span>
          </Tooltip>
        )}
        {blocked && (
          <Tooltip label={p.supportNote ?? t("import.table.row.cantImportTooltip")}>
            <span className="badge imp-badge-outline is-muted">{t("import.table.row.cantImport")}</span>
          </Tooltip>
        )}
      </span>
      <span role="cell" className="imp-cell-dim">
        {p.gameVersion ?? t("import.table.row.unknownVersion")}
      </span>
      {/* The loader's name and version, "Fabric 0.19.5". */}
      <span role="cell" className="imp-cell-dim" title={p.loaderVersion ? `${loaderLabel(p.loader)} ${p.loaderVersion}` : undefined}>
        {loaderLabel(p.loader)}
      </span>
      <span role="cell" className={`imp-cell-dim ${p.support === "settings" ? "is-left" : ""}`} title={p.support === "settings" && p.mods ? t("import.table.row.modsLeft") : undefined}>
        {p.mods || "—"}
      </span>
      <span role="cell" className="imp-cell-faint" title={p.lastPlayed ? shortDate(p.lastPlayed) : undefined}>
        {lastPlayedLabel(p.lastPlayed)}
      </span>
    </div>
  );
}

function SkeletonRows() {
  return (
    <>
      {[0, 1, 2, 3, 4].map((i) => (
        <div key={i} className="imp-row is-ghost" aria-hidden>
          <span className="imp-cell-check">
            <span className="imp-ghost-box" />
          </span>
          <span className="imp-ghost-line" style={{ width: `${46 - i * 5}%` }} />
          <span className="imp-ghost-line is-short" />
          <span className="imp-ghost-line is-short" />
          <span className="imp-ghost-line is-tiny" />
          <span className="imp-ghost-line is-short" />
        </div>
      ))}
    </>
  );
}
