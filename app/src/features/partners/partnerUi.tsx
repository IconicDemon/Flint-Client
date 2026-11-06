import { useCallback, useRef, useState, type CSSProperties, type MouseEvent, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { api, errorText } from "../../lib/api";
import { copyText } from "../../lib/clipboard";
import { compact, number, relativeTime } from "../../lib/format";
import { currentLocale, useT, type MessageKey } from "../../lib/i18n";
import { canJoin, freshDiscordStats, freshStats, hueOf, inviteOf, menuEntries, serverAddress, websiteOf, type MenuEntry, type Partner, type PartnerImage } from "../../lib/partners";
import { imageSrc, toggleFavorite, usePartners } from "../../lib/partnersFeed";
import { useStoreSelect } from "../../lib/store";
import { Button, IconButton } from "../../ui/controls";
import { Icon, type IconName } from "../../ui/Icon";
import { Menu, Popover, useToast, type MenuItem } from "../../ui/overlays";
import type { Navigate } from "../shell/Shell";
import { PartnerDrawer } from "./PartnerDrawer";
import { PlayServerDialog } from "./PlayServerDialog";

export const KIND: Record<Partner["kind"], { icon: IconName; label: MessageKey }> = {
  minecraft_server: { icon: "cube", label: "partners.kind.server" },
  discord: { icon: "discord", label: "partners.kind.discord" },
  hosting: { icon: "drive", label: "partners.kind.hosting" },
};

/**
 * A listing's picture, as the launcher serves it after checking its checksum. It fades in once decoded; without one
 * (or if it can't load) the listing's own colour stands in, never a broken image.
 */
export function PartnerPicture({ partner, image, className = "", eager = false }: { partner: Partner; image: PartnerImage | null; className?: string; eager?: boolean }) {
  const { mediaBase } = usePartners();
  const [state, setState] = useState<"loading" | "loaded" | "failed">("loading");
  const src = image && state !== "failed" ? imageSrc(mediaBase, image.sha256) : "";
  return (
    <span className={`p-art ${className} ${state === "loaded" ? "is-loaded" : ""}`} style={{ "--p-hue": hueOf(partner.id) } as CSSProperties}>
      {src && <img src={src} alt={image?.alt ?? ""} loading={eager ? "eager" : "lazy"} decoding="async" draggable={false} onLoad={() => setState("loaded")} onError={() => setState("failed")} />}
    </span>
  );
}

/** The listing's square logo, or its initial on its own colour. */
export function PartnerLogo({ partner, size = 48, className = "" }: { partner: Partner; size?: number; className?: string }) {
  const { mediaBase } = usePartners();
  const [failed, setFailed] = useState(false);
  const src = partner.logo && !failed ? imageSrc(mediaBase, partner.logo.sha256) : "";
  const initial = [...partner.name.replace(/[^\p{L}\p{N}]/gu, "")][0]?.toUpperCase() ?? "?";
  return (
    <span className={`p-logo ${className} ${src ? "" : "is-monogram"}`} style={{ width: size, height: size, "--p-hue": hueOf(partner.id) } as CSSProperties} aria-hidden>
      {src ? <img src={src} alt="" decoding="async" draggable={false} onError={() => setFailed(true)} /> : <span style={{ fontSize: Math.round(size * 0.44) }}>{initial}</span>}
    </span>
  );
}

/** Partner or Sponsored: always shown, never hidden behind hover. */
export function LabelPill({ partner }: { partner: Partner }) {
  const t = useT();
  const sponsored = partner.label === "Sponsored";
  return <span className={`p-label ${sponsored ? "is-sponsored" : "is-partner"}`}>{t(sponsored ? "partners.label.sponsored" : "partners.label.partner")}</span>;
}

export function KindChip({ partner }: { partner: Partner }) {
  const t = useT();
  return (
    <span className="p-kind">
      <Icon name={KIND[partner.kind].icon} size={13} />
      {t(KIND[partner.kind].label)}
    </span>
  );
}

/**
 * The numbers the Flint Store checked in the last ten minutes, or nothing. A server's online state and players, a
 * Discord community's members and how many are online; a count the Store didn't send is left out, never made up.
 */
export function LiveLine({ partner, now, className = "", compact: short = false }: { partner: Partner; now: number; className?: string; compact?: boolean }) {
  const t = useT();
  const stats = freshStats(partner, now);
  const discord = freshDiscordStats(partner, now);
  if (stats) {
    const players = stats.online && stats.players != null ? (stats.max != null && !short ? t("partners.stats.playersOf", { players: number(stats.players), max: number(stats.max) }) : t("partners.stats.players", { players: number(stats.players) })) : null;
    return (
      <span className={`p-live ${stats.online ? "is-online" : "is-offline"} ${className}`} title={t("partners.stats.checked", { when: whenText(stats.checkedAt) })}>
        <span className="p-live-dot" aria-hidden />
        <span>{stats.online ? t("partners.stats.online") : t("partners.stats.offline")}</span>
        {players && <span className="p-live-count">{players}</span>}
      </span>
    );
  }
  if (discord) {
    return (
      <span className={`p-live is-online ${className}`} title={t("partners.stats.checked", { when: whenText(discord.checkedAt) })}>
        <span className="p-live-dot" aria-hidden />
        {discord.members != null && <span>{t("partners.stats.members", { members: compact(discord.members) })}</span>}
        {discord.online != null && <span className="p-live-count">{t("partners.stats.membersOnline", { online: compact(discord.online) })}</span>}
      </span>
    );
  }
  return null;
}

/** How long ago (ms since the epoch), to go inside a sentence: English writes "just now" there, not "Just now". */
export function whenText(ms: number): string {
  const text = relativeTime(ms / 1000);
  return currentLocale() === "en" ? text.charAt(0).toLowerCase() + text.slice(1) : text;
}

/** What the listing's main button says: the partner's own short label, else Flint's for its kind. */
export function primaryLabel(t: ReturnType<typeof useT>, partner: Partner): string {
  if (partner.primary.label) return partner.primary.label;
  if (partner.kind === "minecraft_server") return t("partners.action.play");
  return t(partner.kind === "discord" ? "partners.action.join" : "partners.action.visit");
}

export type PartnerActions = {
  /** The main action: Play (asks first), the Discord invite, the website. */
  primary: (partner: Partner) => void;
  run: (entry: MenuEntry, partner: Partner) => void;
  isFavorite: (id: string) => boolean;
  toggleFavorite: (partner: Partner) => void;
};

/**
 * Everything a surface (Home's featured card, Library → Partnerships) does with listings: the details drawer, the
 * Play confirmation, links opened in the system browser and copies to the clipboard. Render `ui` once on the surface.
 */
export function usePartnerSurface(navigate: Navigate): { actions: PartnerActions; ui: ReactNode } {
  const t = useT();
  const toast = useToast();
  const { favorites } = usePartners();
  const [drawer, setDrawer] = useState<{ partner: Partner; section: "info" | "stats" } | null>(null);
  const [play, setPlay] = useState<{ partner: Partner; choose: boolean } | null>(null);

  const open = useCallback((url: string | null | undefined) => url && void api.openExternal(url).catch((e) => toast({ tone: "danger", title: t("partners.openFailed"), body: errorText(e) })), [t, toast]);
  const copy = useCallback(
    (text: string, title: MessageKey) =>
      void copyText(text)
        .then(() => toast({ tone: "success", title: t(title), body: text }))
        .catch(() => toast({ tone: "danger", title: t("partners.copyFailed") })),
    [t, toast],
  );
  const toggle = useCallback(
    (partner: Partner) => void toggleFavorite(partner.id, !favorites.includes(partner.id)).catch((e) => toast({ tone: "danger", title: t("partners.favoriteFailed"), body: errorText(e) })),
    [favorites, t, toast],
  );

  const actions: PartnerActions = {
    primary: (partner) => {
      if (partner.kind === "minecraft_server") return canJoin(partner) ? setPlay({ partner, choose: false }) : setDrawer({ partner, section: "info" });
      open(partner.kind === "discord" ? inviteOf(partner) : websiteOf(partner));
    },
    run: (entry, partner) => {
      switch (entry) {
        case "info":
          return setDrawer({ partner, section: "info" });
        case "stats":
          return setDrawer({ partner, section: "stats" });
        case "play":
          return setPlay({ partner, choose: false });
        case "playWith":
          return setPlay({ partner, choose: true });
        case "favorite":
          return toggle(partner);
        case "copyAddress":
          return partner.server && copy(serverAddress(partner.server), "partners.copied.address");
        case "copyInvite":
          return copy(inviteOf(partner) ?? "", "partners.copied.invite");
        case "joinDiscord":
          return open(inviteOf(partner));
        case "visitWebsite":
          return open(websiteOf(partner));
        default:
          return open(partner.links[entry]);
      }
    },
    isFavorite: (id) => favorites.includes(id),
    toggleFavorite: toggle,
  };

  const ui = (
    <>
      {drawer && <PartnerDrawer partner={drawer.partner} section={drawer.section} actions={actions} onClose={() => setDrawer(null)} />}
      <PlayServerDialog target={play} navigate={navigate} onClose={() => setPlay(null)} />
    </>
  );
  return { actions, ui };
}

const MENU: Record<MenuEntry, { label: MessageKey; icon: IconName }> = {
  info: { label: "partners.menu.info", icon: "info" },
  stats: { label: "partners.menu.stats", icon: "gauge" },
  play: { label: "partners.menu.play", icon: "play" },
  playWith: { label: "partners.menu.playWith", icon: "layers" },
  favorite: { label: "partners.menu.favorite", icon: "star" },
  copyAddress: { label: "partners.menu.copyAddress", icon: "copy" },
  website: { label: "partners.menu.website", icon: "globe" },
  discord: { label: "partners.menu.discord", icon: "discord" },
  store: { label: "partners.menu.store", icon: "bag" },
  x: { label: "partners.menu.x", icon: "external" },
  youtube: { label: "partners.menu.youtube", icon: "external" },
  joinDiscord: { label: "partners.menu.joinDiscord", icon: "discord" },
  copyInvite: { label: "partners.menu.copyInvite", icon: "copy" },
  visitWebsite: { label: "partners.menu.visitWebsite", icon: "external" },
};

/** Groups: what it is, playing it, keeping it, its links. */
const GROUP: Partial<Record<MenuEntry, number>> = { info: 0, stats: 0, play: 1, playWith: 1, favorite: 2, copyAddress: 2, joinDiscord: 2, copyInvite: 2, visitWebsite: 2 };

/**
 * The listing's context menu (its ⋯ button, or a right-click anywhere on it): only the entries that work for it now.
 * Returns the props for the card and the button, and the menu itself.
 */
export function usePartnerMenu(partner: Partner, actions: PartnerActions, now: number) {
  const t = useT();
  const profiles = useStoreSelect((s) => s.profiles.length);
  const button = useRef<HTMLButtonElement>(null);
  // The menu opens at a point: under the ⋯ button, or where the listing was right-clicked.
  const point = useRef<HTMLSpanElement>(null);
  const [at, setAt] = useState<{ x: number; y: number; fromButton: boolean } | null>(null);
  const closedAt = useRef(0);
  const entries = menuEntries(partner, now, profiles);
  const items: MenuItem[] = [];
  let group = -1;
  for (const entry of entries) {
    const g = GROUP[entry] ?? 3;
    if (group !== -1 && g !== group) items.push({ type: "separator" });
    group = g;
    const favorite = entry === "favorite" && actions.isFavorite(partner.id);
    items.push({
      label: favorite ? t("partners.menu.unfavorite") : t(MENU[entry].label),
      icon: <Icon name={MENU[entry].icon} size={15} />,
      onSelect: () => actions.run(entry, partner),
    });
  }
  const close = useCallback(() => {
    setAt(null);
    closedAt.current = Date.now();
  }, []);
  const onContextMenu = (e: MouseEvent) => {
    if (!entries.length) return;
    e.preventDefault();
    setAt({ x: e.clientX, y: e.clientY, fromButton: false });
  };
  const label = t("partners.action.more", { name: partner.name });
  const menu = (
    <>
      {at && createPortal(<span ref={point} className="p-menu-point" style={{ left: at.x, top: at.y }} aria-hidden />, document.body)}
      <Popover anchor={point} open={!!at} onClose={close} placement={at?.fromButton ? "bottom-end" : "bottom-start"} label={label} offset={at?.fromButton ? 6 : 2}>
        <Menu items={items} onClose={close} />
      </Popover>
    </>
  );
  const more =
    entries.length > 0 ? (
      <IconButton
        ref={button}
        icon="more"
        size="sm"
        label={label}
        className="p-more"
        aria-haspopup="menu"
        aria-expanded={!!at?.fromButton}
        onClick={() => {
          // The press that closed the menu (outside it) isn't also the one that opens it again.
          if (Date.now() - closedAt.current < 250) return;
          const r = button.current?.getBoundingClientRect();
          if (r) setAt({ x: r.right, y: r.bottom, fromButton: true });
        }}
      />
    ) : null;
  return { onContextMenu, more, menu };
}

/** The main action button of a listing. */
export function PrimaryButton({ partner, actions, size = "sm", className = "" }: { partner: Partner; actions: PartnerActions; size?: "sm" | "md"; className?: string }) {
  const t = useT();
  const server = partner.kind === "minecraft_server";
  if (server && !canJoin(partner)) return null;
  return (
    <Button size={size} variant={server ? "primary" : "secondary"} icon={server ? "play" : undefined} iconRight={server ? undefined : "external"} className={`p-primary ${className}`} onClick={() => actions.primary(partner)}>
      {primaryLabel(t, partner)}
    </Button>
  );
}

export function FavoriteButton({ partner, actions }: { partner: Partner; actions: PartnerActions }) {
  const t = useT();
  const on = actions.isFavorite(partner.id);
  return (
    <button type="button" className={`p-fav ${on ? "is-on" : ""}`} aria-pressed={on} aria-label={t(on ? "partners.menu.unfavorite" : "partners.menu.favorite")} title={t(on ? "partners.menu.unfavorite" : "partners.menu.favorite")} onClick={() => actions.toggleFavorite(partner)}>
      <Icon name="star" size={15} />
    </button>
  );
}
