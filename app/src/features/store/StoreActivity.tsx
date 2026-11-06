import { useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { shortDate } from "../../lib/format";
import { useT } from "../../lib/i18n";
import { ledgerReason, orderRows, orderStatus, paginate, type OrderRow } from "../../lib/storePage";
import { formatStoreMerit, meritText, moneyText, reasonText, type StoreOrder, type StoreWallet } from "../../lib/storeSync";
import { Badge, Button, EmptyState, IconButton, Tabs } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { Pager } from "./Pager";

const PAGE = 12;

export type Activity = { wallet: StoreWallet; orders: StoreOrder[] };
export type ActivityTab = "orders" | "merit";

type Props = {
  /** The tab shown; null: the panel is closed. */
  tab: ActivityTab | null;
  onTab: (tab: ActivityTab) => void;
  onClose: () => void;
  data: Activity | null;
  loading: boolean;
  error: string | null;
  /** The Store can't be reached: orders and Merit movements aren't kept, so they show once it answers. */
  offline: boolean;
  onRetry: () => void;
};

/**
 * The account's Store orders and Merit movements, as the Store lists them (never cached), a page at a time: a panel from
 * the side of the window, over the page, opened from the header (Orders, or the Merit balance). Escape, the close button or
 * a click beside it closes it, and focus goes back where it was.
 */
export function StoreActivityPanel({ tab, onTab, onClose, data, loading, error, offline, onRetry }: Props) {
  const t = useT();
  const panel = useRef<HTMLElement>(null);
  const [page, setPage] = useState({ orders: 0, merit: 0 });
  const open = tab !== null;
  const close = useRef(onClose);
  close.current = onClose;

  // Once per opening: focus moves into the panel, and back to where it was when the panel closes.
  useEffect(() => {
    if (!open) return;
    const back = document.activeElement as HTMLElement | null;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && !document.querySelector(".popover:not(.is-closing), .modal-backdrop:not(.is-closing)") && close.current();
    window.addEventListener("keydown", onKey);
    panel.current?.focus();
    return () => {
      window.removeEventListener("keydown", onKey);
      back?.focus?.({ preventScroll: true });
    };
  }, [open]);
  const rows = useMemo(() => orderRows(data?.orders ?? []), [data]);
  const ledger = data?.wallet.ledger ?? [];

  const body = () => {
    if (!data && offline) return <EmptyState compact icon="wifiOff" title={t("store.activity.offlineTitle")} body={t("store.activity.offline")} />;
    if (!data && error)
      return (
        <EmptyState
          compact
          icon="alert"
          title={t("store.activity.failed")}
          body={error}
          action={
            <Button size="sm" icon="refresh" onClick={onRetry}>
              {t("common.tryAgain")}
            </Button>
          }
        />
      );
    if (!data) return <ActivitySkeleton />;
    if (tab === "orders") {
      if (!rows.length) return <EmptyState compact icon="bag" title={t("store.orders.empty")} body={t("store.orders.emptyBody")} />;
      const paged = paginate(rows, page.orders, PAGE);
      return (
        <>
          <div className="store-table" role="table" aria-label={t("store.activity.orders")}>
            <div className="store-row store-row-head store-order" role="row">
              <span role="columnheader">{t("store.orders.col.date")}</span>
              <span role="columnheader">{t("store.orders.col.items")}</span>
              <span role="columnheader" className="is-end">
                {t("store.orders.col.amount")}
              </span>
              <span role="columnheader" className="is-end">
                {t("store.orders.col.status")}
              </span>
            </div>
            {paged.items.map((row) => (
              <OrderLine key={row.key} row={row} />
            ))}
          </div>
          <Pager paged={paged} onPage={(p) => setPage({ ...page, orders: p })} label={t("store.pager.orders")} />
        </>
      );
    }
    if (!ledger.length) return <EmptyState compact icon="star" title={t("store.merit.empty")} body={t("store.activity.intro")} />;
    const paged = paginate(ledger, page.merit, PAGE);
    return (
      <>
        <div className="store-table" role="table" aria-label={t("store.activity.merit")}>
          <div className="store-row store-row-head store-ledger" role="row">
            <span role="columnheader">{t("store.orders.col.date")}</span>
            <span role="columnheader">{t("store.merit.col.what")}</span>
            <span role="columnheader" className="is-end">
              {t("store.merit.col.change")}
            </span>
            <span role="columnheader" className="is-end">
              {t("store.merit.col.balance")}
            </span>
          </div>
          {paged.items.map((entry, i) => {
            const delta = entry.delta ?? 0;
            const reason = ledgerReason(entry.reason);
            return (
              <div key={`${paged.page}:${i}`} className="store-row store-ledger" role="row">
                <span role="cell" className="store-cell-date">
                  {shortDate(entry.createdAt)}
                </span>
                <span role="cell" className="store-cell-main">
                  {reason ? t(reason) : reasonText(entry.reason) || t("store.activity.noReason")}
                </span>
                <span role="cell" className={`is-end store-delta ${delta < 0 ? "is-spent" : delta > 0 ? "is-earned" : ""}`}>
                  {delta > 0 ? "+" : delta < 0 ? "−" : ""}
                  {formatStoreMerit(Math.abs(delta))}
                </span>
                <span role="cell" className="is-end faint store-cell-num">
                  {formatStoreMerit(entry.balanceAfter)}
                </span>
              </div>
            );
          })}
        </div>
        <Pager paged={paged} onPage={(p) => setPage({ ...page, merit: p })} label={t("store.pager.merit")} />
      </>
    );
  };

  if (!open) return null;
  // On the body, over everything: the page's own layers (the sticky stage) never show through.
  return createPortal(
    <div className="drawer-backdrop" onPointerDown={(e) => e.target === e.currentTarget && onClose()}>
      <aside ref={panel} className="drawer store-drawer" role="dialog" aria-modal="true" aria-labelledby="store-activity-title" tabIndex={-1}>
        <header className="drawer-head">
          <div className="drawer-title">
            <h2 id="store-activity-title">{t("store.activity.title")}</h2>
            {loading && data && <span className="faint">{t("store.activity.refreshing")}</span>}
          </div>
          <IconButton icon="x" label={t("common.close")} onClick={onClose} />
        </header>
        <Tabs
          label={t("store.activity.title")}
          value={tab}
          onChange={onTab}
          tabs={[
            { value: "orders", label: t("store.activity.orders"), count: data ? rows.length : undefined },
            { value: "merit", label: t("store.activity.merit"), count: data ? ledger.length : undefined },
          ]}
        />
        <div className="drawer-body scroll store-activity">
          {error && data && (
            <p className="store-inline-error" role="alert">
              <Icon name="alert" size={14} />
              {error}
            </p>
          )}
          {body()}
        </div>
      </aside>
    </div>,
    document.body,
  );
}

function OrderLine({ row }: { row: OrderRow }) {
  const t = useT();
  const statuses = row.statuses.map(orderStatus);
  const money = row.money ? moneyText(row.money.minor, row.money.currency) : "";
  const [first, ...rest] = row.items;
  return (
    <div className="store-row store-order" role="row">
      <span role="cell" className="store-cell-date">
        {shortDate(row.createdAt)}
      </span>
      <span role="cell" className="store-cell-main">
        <span className="store-order-item" title={row.items.join(", ")}>
          {first ?? t("store.orders.unnamed")}
          {rest.length > 0 && <span className="faint"> {t("store.orders.more", { count: rest.length })}</span>}
        </span>
        {(row.gift || row.testMode || row.reference) && (
          <span className="store-order-sub">
            {row.gift && (
              <span className="store-order-gift">
                <Icon name="gift" size={12} />
                {row.recipientName ? t("store.orders.giftFor", { name: row.recipientName }) : t("store.tile.gift")}
              </span>
            )}
            {row.testMode && <Badge tone="warning">{t("store.tile.test")}</Badge>}
            {row.reference && <span className="faint">{row.reference}</span>}
          </span>
        )}
      </span>
      <span role="cell" className="is-end store-order-amount">
        {money && <span className="store-cell-num">{money}</span>}
        {row.meritSpent !== null && <span className="store-cell-num">{meritText(row.meritSpent)}</span>}
        {row.meritAdded !== null && <span className="store-cell-num store-delta is-earned">+{meritText(row.meritAdded)}</span>}
        {!money && row.meritSpent === null && row.meritAdded === null && <span className="faint">—</span>}
      </span>
      <span role="cell" className="is-end store-order-status">
        {statuses.map((s, i) => (
          <Badge key={i} tone={s.tone}>
            {s.label ? t(s.label) : reasonText(s.text) || "—"}
          </Badge>
        ))}
      </span>
    </div>
  );
}

function ActivitySkeleton() {
  return (
    <div className="store-table is-skeleton" aria-hidden>
      {[62, 48, 70, 54].map((w, i) => (
        <div key={i} className="store-row store-order">
          <span className="sk sk-line" style={{ width: 64 }} />
          <span className="sk sk-line" style={{ width: `${w}%` }} />
          <span className="sk sk-line is-end" style={{ width: 48 }} />
          <span className="sk sk-line is-end" style={{ width: 56 }} />
        </div>
      ))}
    </div>
  );
}
