import { number } from "../../lib/format";
import { useT } from "../../lib/i18n";
import { pageButtons, type Paged } from "../../lib/storePage";
import { IconButton } from "../../ui/controls";

/** "21–40 of 300" and the pages around this one; nothing at all for a list that fits on one page. */
export function Pager({ paged, onPage, label }: { paged: Paged<unknown>; onPage: (page: number) => void; label: string }) {
  const t = useT();
  if (paged.pages <= 1) return null;
  return (
    <nav className="store-pager" aria-label={label}>
      <span className="store-pager-count" aria-live="polite">
        {t("store.pager.showing", { from: number(paged.from), to: number(paged.to), total: number(paged.total) })}
      </span>
      <span className="store-pager-pages">
        <IconButton icon="chevronLeft" size="sm" label={t("store.pager.previous")} disabled={paged.page === 0} onClick={() => onPage(paged.page - 1)} />
        {pageButtons(paged.page, paged.pages).map((page, i) =>
          page === null ? (
            <span key={`gap${i}`} className="store-pager-gap" aria-hidden>
              …
            </span>
          ) : (
            <button
              key={page}
              type="button"
              className={`store-pager-page ${page === paged.page ? "is-current" : ""}`}
              aria-current={page === paged.page ? "page" : undefined}
              aria-label={t("store.pager.page", { page: page + 1 })}
              onClick={() => page !== paged.page && onPage(page)}
            >
              {number(page + 1)}
            </button>
          ),
        )}
        <IconButton icon="chevronRight" size="sm" label={t("store.pager.next")} disabled={paged.page + 1 >= paged.pages} onClick={() => onPage(paged.page + 1)} />
      </span>
    </nav>
  );
}
