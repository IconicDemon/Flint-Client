import { useT } from "../../lib/i18n";
import { featuredPartner, serverAddress, type Partner } from "../../lib/partners";
import { useNow, usePartners } from "../../lib/partnersFeed";
import { Icon } from "../../ui/Icon";
import type { Navigate } from "../shell/Shell";
import { KIND, LabelPill, LiveLine, PartnerLogo, PartnerPicture, PrimaryButton, usePartnerMenu, usePartnerSurface, type PartnerActions } from "./partnerUi";

/**
 * Home's one featured partner, between News and Friends: the Flint Store's `featured_id` while its listing runs. One card,
 * never a grid, a carousel or a rotation. With no featured partner (or before the listings arrive) nothing is rendered,
 * so the column closes up with no gap.
 */
export function FeaturedPartner({ navigate }: { navigate: Navigate }) {
  const { feed } = usePartners();
  const now = useNow();
  const partner = featuredPartner(feed, now);
  const { actions, ui } = usePartnerSurface(navigate);
  return (
    <>
      {partner && <FeaturedCard key={partner.id} partner={partner} now={now} actions={actions} />}
      {ui}
    </>
  );
}

function FeaturedCard({ partner, now, actions }: { partner: Partner; now: number; actions: PartnerActions }) {
  const t = useT();
  const { onContextMenu, more, menu } = usePartnerMenu(partner, actions, now);
  return (
    <section className="side-featured" aria-labelledby="featured-title" onContextMenu={onContextMenu}>
      <header>
        <h2 id="featured-title">{t("partners.featured.title")}</h2>
        <LabelPill partner={partner} />
      </header>
      <button type="button" className="p-feature-open" onClick={() => actions.run("info", partner)} aria-label={t("partners.action.details", { name: partner.name })}>
        <PartnerPicture partner={partner} image={partner.featuredImage} className="p-feature-art" eager />
        <span className="p-feature-id">
          <PartnerLogo partner={partner} size={38} />
          <span className="p-feature-name">
            <strong>{partner.name}</strong>
            <span>
              {partner.server ? (
                <span className="p-mono">{serverAddress(partner.server)}</span>
              ) : (
                <>
                  <Icon name={KIND[partner.kind].icon} size={12} /> {t(KIND[partner.kind].label)}
                </>
              )}
            </span>
          </span>
        </span>
      </button>
      <p className="p-feature-summary">{partner.summary}</p>
      <div className="p-feature-row">
        <LiveLine partner={partner} now={now} className="p-feature-live" compact />
        <span className="p-tile-spacer" />
        <PrimaryButton partner={partner} actions={actions} />
        {more}
      </div>
      {menu}
    </section>
  );
}
