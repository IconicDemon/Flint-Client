import { useEffect, useState } from "react";
import coOwner from "../../assets/badges/tag_co_owner.png";
import contentCreator from "../../assets/badges/tag_content_creator.png";
import flint from "../../assets/badges/tag_default.png";
import developer from "../../assets/badges/tag_developer.png";
import owner from "../../assets/badges/tag_owner.png";
import special from "../../assets/badges/tag_special.png";
import staff from "../../assets/badges/tag_staff.png";
import trialCreator from "../../assets/badges/tag_trial_creator.png";
import vip from "../../assets/badges/tag_vip.png";
import { api, subscribe, type Account } from "../../lib/api";
import { equipBadge, shown, UNKNOWN, type BadgeTag, type Badges } from "../../lib/badges";
import { useT, type MessageKey } from "../../lib/i18n";
import { Button } from "../../ui/controls";
import { CardGrid } from "./WardrobeCard";

/** Each mark's picture (the game's badge font, the owner's icon set) and name. */
const MARKS: Record<BadgeTag, { art: string; name: MessageKey }> = {
  default: { art: flint, name: "wardrobe.badges.flint" },
  owner: { art: owner, name: "wardrobe.badges.owner" },
  co_owner: { art: coOwner, name: "wardrobe.badges.coOwner" },
  developer: { art: developer, name: "wardrobe.badges.developer" },
  staff: { art: staff, name: "wardrobe.badges.staff" },
  content_creator: { art: contentCreator, name: "wardrobe.badges.contentCreator" },
  trial_creator: { art: trialCreator, name: "wardrobe.badges.trialCreator" },
  vip: { art: vip, name: "wardrobe.badges.vip" },
  special: { art: special, name: "wardrobe.badges.special" },
};

/** The account's badges as Flint last heard them, per account: the sidebar count and the page share them. */
const known = new Map<string, Badges>();
const listeners = new Set<() => void>();
const remember = (uuid: string, b: Badges) => {
  known.set(uuid, b);
  for (const l of listeners) l();
};

/** The account's badges (asked of the Store when shown; a Store without them, or none reachable: the Flint mark only). */
export function useBadges(uuid: string): [Badges, (b: Badges) => void] {
  const [, redraw] = useState(0);
  useEffect(() => {
    const l = () => redraw((n) => n + 1);
    listeners.add(l);
    // A badge chosen in a game (game link `badgeSet`) shows here too.
    const off = subscribe<Badges>("badges-changed", (b) => remember(uuid, b));
    let live = true;
    api.badgeList(uuid).then(
      (b) => live && remember(uuid, b),
      () => live && !known.has(uuid) && remember(uuid, UNKNOWN),
    );
    return () => {
      live = false;
      listeners.delete(l);
      off();
    };
  }, [uuid]);
  return [known.get(uuid) ?? UNKNOWN, (b) => remember(uuid, b)];
}

/**
 * Badges: the mark before a Flint player's name on nametags, only the ones the player has: everyone has the Flint mark;
 * creators, partners and the team are given theirs by Flint (the Store says which). Pick one and Equip it: the Store keeps
 * the choice and other Flint players see it within a minute. Badges aren't Store items.
 */
export function BadgesView({ account }: { account: Account }) {
  const t = useT();
  const [data, setData] = useBadges(account.uuid);
  const { badges, badge } = shown(data);
  const [picked, setPicked] = useState<BadgeTag | null>(null);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const selected = picked && badges.includes(picked) ? picked : badge;
  const worn = MARKS[badge];

  const equip = async () => {
    if (busy || selected === badge) return;
    setBusy(true);
    setProblem(null);
    setProblem(await equipBadge(data, selected, (tag) => api.badgeSet(account.uuid, tag), setData));
    setBusy(false);
  };

  return (
    <div className="wd-badges">
      <div className="wd-badge-own">
        <span className="wd-badge-art is-large">
          <img src={worn.art} alt="" draggable={false} />
        </span>
        <span className="wd-badge-text">
          <span className="wd-card-state is-on">
            <i aria-hidden />
            {t("wardrobe.card.equipped")}
          </span>
          <strong>{t(worn.name)}</strong>
          <span className="faint">{badges.length > 1 ? t("wardrobe.badges.othersSee") : t("wardrobe.badges.flintWho")}</span>
        </span>
        <span className="wd-nametag" aria-label={badge === "default" ? t("wardrobe.badges.nametag", { name: account.username }) : t("wardrobe.badges.nametagOf", { name: account.username, badge: t(worn.name) })}>
          <img src={worn.art} alt="" />
          {account.username}
        </span>
      </div>
      {badges.length > 1 && (
        <>
          <div className="wd-badge-bar">
            <span className="wd-badge-bar-text">
              <strong>{t("wardrobe.badges.yours")}</strong>
              <span className="faint">{t("wardrobe.badges.pick")}</span>
            </span>
            <Button size="sm" disabled={busy || selected === badge} onClick={() => void equip()}>
              {t("wardrobe.equip")}
            </Button>
          </div>
          {problem && (
            <p className="wd-badge-problem" role="alert">
              {problem}
            </p>
          )}
          <CardGrid label={t("wardrobe.badges.yours")} className="is-badges">
            {badges.map((tag) => {
              const mark = MARKS[tag];
              const on = tag === badge;
              return (
                <button
                  key={tag}
                  type="button"
                  className={`wd-card wd-badge ${tag === selected ? "is-selected" : ""} ${on ? "is-equipped" : ""}`}
                  aria-pressed={tag === selected}
                  aria-busy={(busy && on) || undefined}
                  onClick={() => setPicked(tag)}
                >
                  <span className="wd-card-art">
                    <span className="wd-badge-art">
                      <img src={mark.art} alt="" draggable={false} />
                    </span>
                    {on && <span className="wd-card-dot" aria-hidden />}
                  </span>
                  <span className="wd-card-body">
                    <span className="wd-card-name">{t(mark.name)}</span>
                    {on ? (
                      <span className="wd-card-state is-on">
                        <i aria-hidden />
                        {t("wardrobe.card.equipped")}
                      </span>
                    ) : (
                      <span className="wd-card-state">{t("wardrobe.card.owned")}</span>
                    )}
                  </span>
                </button>
              );
            })}
          </CardGrid>
        </>
      )}
    </div>
  );
}
