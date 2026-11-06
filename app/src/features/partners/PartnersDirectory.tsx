import { useEffect, useState } from "react";
import { list } from "../../lib/format";
import { useT, type MessageKey } from "../../lib/i18n";
import { directoryPartners, serverAddress, versionsText, type Partner } from "../../lib/partners";
import { refreshPartners, useNow, usePartners } from "../../lib/partnersFeed";
import { Button, EmptyState, Segmented } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import type { Navigate } from "../shell/Shell";
import { FavoriteButton, KindChip, LabelPill, LiveLine, PartnerLogo, PartnerPicture, PrimaryButton, usePartnerMenu, usePartnerSurface, whenText, type PartnerActions } from "./partnerUi";

type Filter = "all" | Partner["kind"] | "favorites";
const FILTERS: { value: Filter; label: MessageKey }[] = [
  { value: "all", label: "partners.filter.all" },
  { value: "minecraft_server", label: "partners.filter.servers" },
  { value: "discord", label: "partners.filter.communities" },
  { value: "hosting", label: "partners.filter.hosting" },
  { value: "favorites", label: "partners.filter.favorites" },
];

/**
 * Library → Partnerships: every listing in the directory that is running now, in the Flint Store's order, each with its
 * wide picture, label, kind, short description, address and the numbers the Store checked in the last ten minutes.
 * Opening the section asks the Store again (the launcher answers from its cache within the Store's max-age).
 */
export function PartnersDirectory({ navigate }: { navigate: Navigate }) {
  const t = useT();
  const { status, feed, stale, fetchedAt, favorites } = usePartners();
  const now = useNow();
  const { actions, ui } = usePartnerSurface(navigate);
  const [filter, setFilter] = useState<Filter>("all");
  useEffect(() => void refreshPartners("auto"), []);

  const all = directoryPartners(feed, now);
  const kinds = new Set(all.map((p) => p.kind));
  const options = FILTERS.filter((f) => f.value === "all" || (f.value === "favorites" ? all.some((p) => favorites.includes(p.id)) : kinds.has(f.value)));
  const active = options.some((o) => o.value === filter) ? filter : "all";
  const shown = all.filter((p) => (active === "all" ? true : active === "favorites" ? favorites.includes(p.id) : p.kind === active));

  if (!feed && status === "loading") {
    return (
      <div className="p-dir" aria-busy="true" aria-label={t("partners.loading")}>
        <div className="p-grid">
          {Array.from({ length: 4 }, (_, i) => (
            <div key={i} className="p-tile is-skeleton">
              <span className="p-tile-banner sk" />
              <div className="p-tile-body">
                <span className="sk p-tile-logo" />
                <span className="sk sk-line" style={{ width: "46%", height: 16 }} />
                <span className="sk sk-line" style={{ width: "92%" }} />
                <span className="sk sk-line" style={{ width: "70%" }} />
              </div>
            </div>
          ))}
        </div>
      </div>
    );
  }

  if (!feed) {
    return (
      <div className="p-dir">
        <EmptyState
          icon="wifiOff"
          title={t("partners.offline.title")}
          body={t(status === "offline" ? "partners.offline.body" : "partners.unavailable.body")}
          action={
            <Button icon="refresh" onClick={() => void refreshPartners("force")}>
              {t("common.tryAgain")}
            </Button>
          }
        />
      </div>
    );
  }

  if (all.length === 0) {
    return (
      <section className="partner-empty">
        <span className="partner-mark">
          <Icon name="handshake" size={26} />
        </span>
        <h2>{t("library.tabs.partners")}</h2>
        <p>{t("library.partners.empty")}</p>
      </section>
    );
  }

  return (
    <div className="p-dir">
      <div className="p-dir-bar">
        <p className="p-dir-intro">
          <Icon name="lock" size={14} />
          {t("partners.directory.safety")}
        </p>
        <div className="p-dir-tools">
          {stale ? (
            <span className="lib-stale">{t("partners.directory.stale", { when: whenText(fetchedAt ?? now) })}</span>
          ) : null}
          {options.length > 2 && <Segmented label={t("partners.filter.label")} value={active} onChange={setFilter} options={options.map((o) => ({ value: o.value, label: t(o.label) }))} />}
        </div>
      </div>
      {shown.length === 0 ? (
        <EmptyState icon="star" title={t("partners.empty.favorites")} body={t("partners.empty.favoritesBody")} compact />
      ) : (
        <div className="p-grid">
          {shown.map((p, i) => (
            <PartnerTile key={p.id} partner={p} actions={actions} now={now} index={i} />
          ))}
        </div>
      )}
      {ui}
    </div>
  );
}

function PartnerTile({ partner, actions, now, index }: { partner: Partner; actions: PartnerActions; now: number; index: number }) {
  const t = useT();
  const { onContextMenu, more, menu } = usePartnerMenu(partner, actions, now);
  const server = partner.server;
  const versions = server ? versionsText(server.versions, (l) => list(l)) : null;
  return (
    <article className="p-tile" onContextMenu={onContextMenu} style={{ animationDelay: `${Math.min(index, 8) * 40}ms` }}>
      <button type="button" className="p-tile-open" onClick={() => actions.run("info", partner)} aria-label={t("partners.action.details", { name: partner.name })}>
        <PartnerPicture partner={partner} image={partner.libraryImage ?? partner.featuredImage} className="p-tile-banner" />
      </button>
      <span className="p-tile-label">
        <LabelPill partner={partner} />
      </span>
      <div className="p-tile-body">
        <PartnerLogo partner={partner} size={56} className="p-tile-logo" />
        <div className="p-tile-head">
          <h3 title={partner.name}>{partner.name}</h3>
          <KindChip partner={partner} />
        </div>
        <p className="p-tile-summary">{partner.summary}</p>
        <div className="p-tile-facts">
          {server && (
            <span className="p-fact p-mono" title={serverAddress(server)}>
              <Icon name="globe" size={13} />
              {serverAddress(server)}
            </span>
          )}
          {versions && <span className="p-fact">{versions}</span>}
          <LiveLine partner={partner} now={now} />
        </div>
        <div className="p-tile-actions">
          <PrimaryButton partner={partner} actions={actions} />
          <span className="p-tile-spacer" />
          {partner.kind === "minecraft_server" && <FavoriteButton partner={partner} actions={actions} />}
          {more}
        </div>
      </div>
      {menu}
    </article>
  );
}
