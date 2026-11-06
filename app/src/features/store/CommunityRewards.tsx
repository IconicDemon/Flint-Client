import { useEffect, useState } from "react";
import { canRecheck, cardAction, cardState, discordNotice, recheckWait, rewardItem, type RewardCard, type RewardsView } from "../../lib/community";
import { useT } from "../../lib/i18n";
import type { StoreItem, StoreSlot } from "../../lib/storeSync";
import { Button, Callout, Spinner } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { ItemArt } from "./StoreInventory";
import { storeAnimation } from "./storeCape";
import type { RecheckState } from "./useCommunity";

export type CommunityProps = {
  cards: RewardCard[];
  rewards: RewardsView | null;
  recheck: RecheckState;
  onRecheck: () => void;
  /** Opens Settings → Connected Accounts. */
  onConnect: () => void;
  /** Opens a Discord invite in the system browser (checked in Rust). */
  onInvite: (url: string) => void;
};

/**
 * The Community Rewards group, after a slot's own items: the same tiles (picture, name, one status), each with what earns
 * it in the Store's words and the one thing to do next (Connect Discord, Join, Boost, or nothing while Discord is being
 * checked). A reward the account has is worn with a click, like any item; a locked one can still be looked at on the
 * stage. "Check again" sits in the group's header. Wearing goes through the page's usual wear (the Store decides).
 */
export function CommunityRewardsGroup({
  uuid,
  cards,
  rewards,
  recheck,
  readOnly,
  motion,
  busy,
  pendingId,
  onRecheck,
  onConnect,
  onInvite,
  onWear,
  onPreview,
}: CommunityProps & {
  uuid: string;
  readOnly: boolean;
  motion: boolean;
  /** A wear the Store hasn't answered yet (any slot). */
  busy: boolean;
  /** The reward that wear is for, in this slot. */
  pendingId: string | null;
  onWear: (slot: StoreSlot, cosmeticId: string | null) => void;
  onPreview: (item: StoreItem | null) => void;
}) {
  const t = useT();
  const notice = discordNotice(rewards);
  return (
    <section className="store-group store-rewards-group" aria-labelledby="store-rewards-title">
      <div className="store-group-head">
        <h3 className="store-group-title" id="store-rewards-title">
          {t("store.rewards.title")}
        </h3>
        {canRecheck(rewards) && !readOnly && <RecheckButton state={recheck} onRecheck={onRecheck} />}
      </div>
      {notice && (
        <Callout tone={notice.tone} icon={notice.tone === "warn" ? "wifiOff" : "info"} title={t(notice.title)}>
          {t(notice.body)}
        </Callout>
      )}
      {recheck.error && (
        <p className="store-inline-error" role="alert">
          <Icon name="alert" size={14} />
          {recheck.error}
        </p>
      )}
      {rewards?.state === "unavailable" && <p className="faint store-group-note">{t("store.rewards.progressUnavailable")}</p>}
      <div className="store-grid" onPointerLeave={() => onPreview(null)}>
        {cards.map((card) => (
          <RewardTile
            key={card.reward.cosmeticId}
            uuid={uuid}
            card={card}
            readOnly={readOnly}
            motion={motion}
            busy={busy}
            pending={pendingId === card.reward.cosmeticId}
            onConnect={onConnect}
            onInvite={onInvite}
            onWear={onWear}
            onPreview={onPreview}
          />
        ))}
      </div>
    </section>
  );
}

/** "Check again", then how long until it may run again (counting down, never retried by itself). */
function RecheckButton({ state, onRecheck }: { state: RecheckState; onRecheck: () => void }) {
  const t = useT();
  const [now, setNow] = useState(() => Date.now());
  const wait = recheckWait(state.nextAllowedAt, now);
  useEffect(() => {
    if (!state.nextAllowedAt || state.nextAllowedAt <= Date.now()) return;
    setNow(Date.now());
    const timer = window.setInterval(() => {
      const at = Date.now();
      setNow(at);
      if (at >= (state.nextAllowedAt ?? 0)) window.clearInterval(timer);
    }, 1000);
    return () => window.clearInterval(timer);
  }, [state.nextAllowedAt]);
  return (
    <Button size="sm" variant="ghost" icon="refresh" loading={state.busy} disabled={wait > 0} onClick={onRecheck} aria-live="polite" className="store-recheck">
      {wait > 0 ? t("store.rewards.recheckIn", { seconds: wait }) : t("store.rewards.recheck")}
    </Button>
  );
}

/**
 * One reward as a tile. Unlocked: a button like an owned item's tile (a click wears it). Otherwise: its picture (a button
 * that shows it on the stage) and its one action or status, since a tile can't hold a second button inside a button.
 */
function RewardTile({
  uuid,
  card,
  readOnly,
  motion,
  busy,
  pending,
  onConnect,
  onInvite,
  onWear,
  onPreview,
}: {
  uuid: string;
  card: RewardCard;
  readOnly: boolean;
  motion: boolean;
  busy: boolean;
  pending: boolean;
  onConnect: () => void;
  onInvite: (url: string) => void;
  onWear: (slot: StoreSlot, cosmeticId: string | null) => void;
  onPreview: (item: StoreItem | null) => void;
}) {
  const t = useT();
  const { reward } = card;
  const state = cardState(card);
  const action = cardAction(card);
  // What the stage shows: the account's own item when it has it, else the catalog's art (a locked cape, to look at).
  const shown = card.item ?? rewardItem(reward);
  const locked = card.status === "locked";
  const wearable = state.wearable && !!card.item;
  const requirement = reward.requirement.label || t("store.rewards.requirementFallback");
  const animated = !!storeAnimation(uuid, shown);
  // The requirement in the Store's longer words, and how long it lasts, when pointed at.
  const title = [reward.name, reward.requirement.description ?? requirement, state.note ? t(state.note) : null].filter(Boolean).join(" · ");

  const status = pending ? (
    <span className="store-tile-state is-busy">
      <Spinner size={12} />
      {t("store.inventory.updating")}
    </span>
  ) : card.worn ? (
    <span className="store-tile-state is-worn">
      <Icon name="check" size={12} />
      {t("store.inventory.wearing")}
    </span>
  ) : wearable ? (
    <span className={`store-tile-state is-unlocked ${readOnly ? "" : "can-wear"}`}>
      <span className="store-tile-rest">
        <Icon name="check" size={12} />
        {t(state.label)}
      </span>
      {!readOnly && <span className="store-tile-hover">{t("store.inventory.wear")}</span>}
    </span>
  ) : action ? (
    <Button
      size="sm"
      icon="discord"
      className={`store-tile-action ${action.kind === "connect" ? "btn-discord" : "btn-discord-quiet"}`}
      disabled={readOnly}
      onClick={() => (action.kind === "connect" ? onConnect() : onInvite(action.url))}
    >
      {t(action.label)}
    </Button>
  ) : (
    <span className={`store-tile-state is-text is-${state.tone}`}>
      {state.busy ? <Spinner size={12} /> : <Icon name={locked ? "lock" : card.status === "unavailable" ? "alert" : "check"} size={12} />}
      {/* A first-launch reward says when it unlocks; any other says its state. */}
      <span className="store-tile-words">{locked && state.note ? t(state.note) : t(state.label)}</span>
    </span>
  );

  const art = (
    <>
      <ItemArt uuid={uuid} item={shown} motion={motion} />
      {(animated || locked) && (
        <span className="store-tile-flags">
          {animated && (
            <span className="store-flag">
              <i aria-hidden />
              {t("store.inventory.animated")}
            </span>
          )}
          {locked && (
            <span className="store-flag is-icon" aria-hidden>
              <Icon name="lock" size={11} />
            </span>
          )}
        </span>
      )}
    </>
  );
  const body = (
    <span className="store-tile-body">
      <span className="store-tile-name">{reward.name}</span>
      <span className="store-tile-meta">{requirement}</span>
      <span className="store-tile-foot">{status}</span>
    </span>
  );
  const classes = `store-tile store-tile-grid is-reward ${locked ? "is-locked" : ""} ${card.worn ? "is-worn" : ""} ${pending ? "is-pending" : ""}`;

  if (wearable)
    return (
      <button
        type="button"
        className={classes}
        aria-pressed={card.worn}
        aria-busy={pending || undefined}
        aria-disabled={readOnly || undefined}
        title={title}
        onClick={() => !card.worn && !busy && !readOnly && onWear(reward.kind, reward.cosmeticId)}
        onPointerEnter={() => onPreview(shown)}
        onFocus={(e) => e.currentTarget.matches(":focus-visible") && onPreview(shown)}
        onBlur={() => onPreview(null)}
      >
        <span className="store-tile-art">{art}</span>
        {body}
      </button>
    );
  return (
    <div
      className={classes}
      title={title}
      onPointerEnter={() => onPreview(shown)}
      onFocus={(e) => e.target.matches(":focus-visible") && onPreview(shown)}
      onBlur={() => onPreview(null)}
    >
      <button type="button" className="store-tile-art" aria-label={t("store.rewards.preview", { name: reward.name })} onClick={() => onPreview(shown)}>
        {art}
      </button>
      {body}
    </div>
  );
}
