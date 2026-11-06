import { useEffect, useRef } from "react";
import { createPortal } from "react-dom";
import { list, number } from "../../lib/format";
import { useT, type MessageKey } from "../../lib/i18n";
import { canJoin, freshDiscordStats, freshStats, serverAddress, versionsText, type LinkKind, type Partner } from "../../lib/partners";
import { useNow } from "../../lib/partnersFeed";
import { Button, IconButton } from "../../ui/controls";
import { Icon, type IconName } from "../../ui/Icon";
import { FavoriteButton, KindChip, LabelPill, PartnerLogo, PartnerPicture, PrimaryButton, whenText, type PartnerActions } from "./partnerUi";

const LINKS: { key: LinkKind; label: MessageKey; icon: IconName }[] = [
  { key: "website", label: "partners.menu.website", icon: "globe" },
  { key: "discord", label: "partners.menu.discord", icon: "discord" },
  { key: "store", label: "partners.menu.store", icon: "bag" },
  { key: "x", label: "partners.menu.x", icon: "external" },
  { key: "youtube", label: "partners.menu.youtube", icon: "external" },
];

/**
 * A listing's details: its picture, what it is, its full description (plain text, line breaks kept), the server and the
 * versions it takes, the statistics the Flint Store checked in the last ten minutes (or a note that there are none),
 * and its verified links, each opened in the system browser.
 */
export function PartnerDrawer({ partner, section, actions, onClose }: { partner: Partner; section: "info" | "stats"; actions: PartnerActions; onClose: () => void }) {
  const t = useT();
  const now = useNow();
  const panel = useRef<HTMLElement>(null);
  const statsRef = useRef<HTMLElement>(null);
  const stats = freshStats(partner, now);
  const discord = freshDiscordStats(partner, now);
  const server = partner.server;
  const links = LINKS.filter((l) => partner.links[l.key]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && !document.querySelector(".popover:not(.is-closing), .modal-backdrop:not(.is-closing)") && onClose();
    window.addEventListener("keydown", onKey);
    panel.current?.focus();
    if (section === "stats") statsRef.current?.scrollIntoView({ block: "start" });
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose, section]);

  // On the body: Home's side column (where the featured card opens it) is its own stacking context.
  return createPortal(
    <div className="drawer-backdrop" onPointerDown={(e) => e.target === e.currentTarget && onClose()}>
      <aside ref={panel} className="drawer p-drawer" role="dialog" aria-modal="true" aria-label={partner.name} tabIndex={-1}>
        <div className="drawer-body scroll p-drawer-scroll">
          <div className="p-drawer-hero">
            <PartnerPicture partner={partner} image={partner.featuredImage} className="p-drawer-art" eager />
            <span className="p-drawer-hero-top">
              <LabelPill partner={partner} />
              <IconButton icon="x" label={t("common.close")} onClick={onClose} className="p-drawer-close" />
            </span>
          </div>
          <header className="p-drawer-head">
            <PartnerLogo partner={partner} size={68} className="p-drawer-logo" />
            <div className="p-drawer-title">
              <KindChip partner={partner} />
              <h2>{partner.name}</h2>
              {server && <span className="p-mono faint">{serverAddress(server)}</span>}
            </div>
          </header>
          <div className="p-drawer-cta">
            <PrimaryButton partner={partner} actions={actions} size="md" />
            {partner.kind === "minecraft_server" && <FavoriteButton partner={partner} actions={actions} />}
            {server && (
              <Button variant="ghost" size="sm" icon="copy" onClick={() => actions.run("copyAddress", partner)}>
                {t("partners.drawer.copyAddress")}
              </Button>
            )}
            {partner.kind === "discord" && (
              <Button variant="ghost" size="sm" icon="copy" onClick={() => actions.run("copyInvite", partner)}>
                {t("partners.menu.copyInvite")}
              </Button>
            )}
          </div>
          {partner.kind === "minecraft_server" && !canJoin(partner) && (
            <p className="drawer-unavailable p-drawer-edition">
              <Icon name="alert" size={15} />
              {t("partners.drawer.otherEdition")}
            </p>
          )}

          <section className="p-drawer-section">
            <h3>{t("partners.drawer.about")}</h3>
            <p className="p-desc">{partner.description}</p>
          </section>

          {server && (
            <section className="p-drawer-section">
              <h3>{t("partners.drawer.server")}</h3>
              <dl className="details p-details">
                <dt>{t("partners.drawer.address")}</dt>
                <dd className="p-mono">{server.host}</dd>
                <dt>{t("partners.drawer.port")}</dt>
                <dd className="p-mono">{server.port}</dd>
                <dt>{t("partners.drawer.edition")}</dt>
                <dd>{server.edition === "java" ? t("partners.drawer.java") : server.edition.charAt(0).toUpperCase() + server.edition.slice(1).replace(/_/g, " ")}</dd>
                <dt>{t("partners.drawer.versions")}</dt>
                <dd>{versionsText(server.versions, (l) => list(l)) ?? t("partners.drawer.anyVersion")}</dd>
                {server.recommendedVersion && (
                  <>
                    <dt>{t("partners.drawer.recommended")}</dt>
                    <dd>Minecraft {server.recommendedVersion}</dd>
                  </>
                )}
              </dl>
            </section>
          )}

          {partner.kind !== "hosting" && (
            <section ref={statsRef} className="p-drawer-section">
              <h3>{t("partners.drawer.statistics")}</h3>
              {stats ? (
                <div className="p-stat-grid">
                  <div className={`p-stat ${stats.online ? "is-online" : "is-offline"}`}>
                    <span className="p-stat-label">{t("partners.drawer.status")}</span>
                    <strong>
                      <span className="p-live-dot" aria-hidden /> {stats.online ? t("partners.stats.online") : t("partners.stats.offline")}
                    </strong>
                  </div>
                  {stats.online && stats.players != null && (
                    <div className="p-stat">
                      <span className="p-stat-label">{t("partners.drawer.players")}</span>
                      <strong>{number(stats.players)}</strong>
                    </div>
                  )}
                  {stats.online && stats.max != null && (
                    <div className="p-stat">
                      <span className="p-stat-label">{t("partners.drawer.slots")}</span>
                      <strong>{number(stats.max)}</strong>
                    </div>
                  )}
                </div>
              ) : discord ? (
                <div className="p-stat-grid">
                  {discord.members != null && (
                    <div className="p-stat">
                      <span className="p-stat-label">{t("partners.drawer.members")}</span>
                      <strong>{number(discord.members)}</strong>
                    </div>
                  )}
                  {discord.online != null && (
                    <div className="p-stat is-online">
                      <span className="p-stat-label">{t("partners.drawer.onlineNow")}</span>
                      <strong>
                        <span className="p-live-dot" aria-hidden /> {number(discord.online)}
                      </strong>
                    </div>
                  )}
                </div>
              ) : (
                <p className="faint p-nostats">{t("partners.drawer.noStats")}</p>
              )}
              {(stats || discord) && <p className="faint p-checked">{t("partners.stats.checked", { when: whenText((stats ?? discord)!.checkedAt) })}</p>}
            </section>
          )}

          {links.length > 0 && (
            <section className="p-drawer-section">
              <h3>{t("partners.drawer.links")}</h3>
              <div className="p-links">
                {links.map((l) => (
                  <Button key={l.key} size="sm" icon={l.icon} iconRight="external" onClick={() => actions.run(l.key, partner)}>
                    {t(l.label)}
                  </Button>
                ))}
              </div>
            </section>
          )}

          <p className="p-disclosure">{t(partner.label === "Sponsored" ? "partners.drawer.sponsoredNote" : "partners.drawer.partnerNote")}</p>
        </div>
      </aside>
    </div>,
    document.body,
  );
}
