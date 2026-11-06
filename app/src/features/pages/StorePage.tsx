import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import meritStar from "../../assets/brand/merit-star-23.png";
import meritStar2x from "../../assets/brand/merit-star-46.png";
import meritStar3x from "../../assets/brand/merit-star-69.png";
import wallpaper from "../../assets/wallpapers/26_3.jpg";
import { api, errorText, type Account } from "../../lib/api";
import { NO_CAPE, type ShownCape } from "../../lib/capeSource";
import { joinRewards } from "../../lib/community";
import { useDisplayedCape } from "../../lib/displayedCape";
import { useT } from "../../lib/i18n";
import { useSettings } from "../../lib/settings";
import { useStoreSelect } from "../../lib/store";
import { meritShown, storePhase, withEquipped, wornIn, type MeritShown } from "../../lib/storePage";
import {
  FOCUS_CHECK_GAP_MS,
  formatStoreMerit,
  meritText,
  needsRefresh,
  nextPollDelay,
  POLL_DEFAULT_MS,
  storeHost,
  updatedText,
  viewFor,
  type StoreChanges,
  type StoreInfo,
  type StoreItem,
  type StoreSlot,
  type StoreView,
} from "../../lib/storeSync";
import { onWindowFocus } from "../../lib/windowFocus";
import { Button, Callout, EmptyState, Spinner } from "../../ui/controls";
import { Icon, type IconName } from "../../ui/Icon";
import { FlintMark } from "../../ui/Logo";
import { Menu, Popover, Tooltip, useToast, type MenuItem } from "../../ui/overlays";
import type { Navigate } from "../shell/Shell";
import { StoreActivityPanel, type Activity, type ActivityTab } from "../store/StoreActivity";
import { StoreApproveDialog } from "../store/StoreApprove";
import { StoreInventoryCard, type Wearing } from "../store/StoreInventory";
import { StorePreview } from "../store/StorePreview";
import { useItemCape } from "../store/storeCape";
import { useCommunity } from "../store/useCommunity";

/**
 * The Flint Store for the active Minecraft account, laid out as a wardrobe: one header (the page's name, the Merit
 * balance, orders, opening the Store website and a menu for the rest), then the character in 3D beside the account's
 * items, one slot at a time. Orders and Merit history open in a side panel; approving a Store sign-in in a browser is in
 * the menu. Everything shown is the Store's answer (src-tauri/src/store); while the Store can't be reached the last answer
 * is shown as out of date and nothing can be changed. The page's rules are in lib/storePage.ts; its parts in
 * features/store.
 */
export default function StorePage({ navigate }: { navigate: Navigate }) {
  const t = useT();
  const account = useStoreSelect((s) => s.activeAccount);
  const [info, setInfo] = useState<StoreInfo | null>(null);
  useEffect(() => {
    void api.storeInfo().then(setInfo).catch(() => {});
  }, []);
  // A development or isolated test run: its sign-ins, codes and items are that Store's, not the live one's.
  const test = !!info && !info.production;

  return (
    <div className="page-body store-page">
      {!account ? (
        <>
          <StoreHeader test={test} />
          <StoreHero icon="user" title={t("store.noAccount.title")} body={t("store.noAccount.body")}>
            <Button variant="primary" onClick={() => navigate("settings", "accounts")}>
              {t("store.noAccount.manageAccounts")}
            </Button>
          </StoreHero>
        </>
      ) : account.qa ? (
        <>
          <StoreHeader test={test} />
          <StoreHero icon="lock" title={t("store.testIdentity.title")} body={t("store.testIdentity.body")} />
        </>
      ) : (
        // A new account is a new page: nothing of the previous account's Store page carries over, not even for a frame.
        <StoreAccount key={account.uuid} account={account} info={info} test={test} navigate={navigate} />
      )}
    </div>
  );
}

/** Re-renders every `ms` so "Updated 4 min ago" stays true. */
function useNow(ms: number): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), ms);
    return () => window.clearInterval(timer);
  }, [ms]);
  return now;
}

/** The page's name, marked when Flint is connected to a test Store, and the actions on the right. */
function StoreHeader({ test, children }: { test: boolean; children?: ReactNode }) {
  const t = useT();
  return (
    <header className="store-head">
      <div className="store-head-text">
        <div className="store-head-title">
          <h1>{t("store.title")}</h1>
          {test && (
            <Tooltip label={t("store.chip.testStoreTip")} side="bottom">
              <span className="store-test-chip" tabIndex={0}>
                {t("store.chip.testStore")}
              </span>
            </Tooltip>
          )}
        </div>
        <p className="muted">{t("store.subtitle")}</p>
      </div>
      {children && <div className="store-head-actions">{children}</div>}
    </header>
  );
}

const PERKS: { icon: IconName; label: "store.signIn.perkWear" | "store.signIn.perkMerit" | "store.signIn.perkApprove" }[] = [
  { icon: "shirt", label: "store.signIn.perkWear" },
  { icon: "star", label: "store.signIn.perkMerit" },
  { icon: "link", label: "store.signIn.perkApprove" },
];

/** The page while there's nothing of the account's to show: what is going on, and the one thing to do about it. */
function StoreHero({
  title,
  body,
  children,
  icon,
  note,
  perks = false,
}: {
  title: string;
  body?: string;
  children?: ReactNode;
  icon?: IconName;
  note?: ReactNode;
  perks?: boolean;
}) {
  const t = useT();
  return (
    <section className={`store-hero ${icon === "alert" || icon === "wifiOff" || icon === "lock" ? "is-problem" : ""}`} aria-labelledby="store-hero-title">
      <div className="store-hero-content">
        <span className="store-hero-mark" aria-hidden>
          {icon ? <Icon name={icon} size={22} /> : <FlintMark size={26} />}
        </span>
        <h2 id="store-hero-title">{title}</h2>
        {body && <p className="store-hero-body">{body}</p>}
        {perks && (
          <ul className="store-hero-perks">
            {PERKS.map((perk) => (
              <li key={perk.label}>
                <Icon name={perk.icon} size={15} />
                {t(perk.label)}
              </li>
            ))}
          </ul>
        )}
        {children && <div className="store-hero-actions">{children}</div>}
        {note}
      </div>
      <div className="store-hero-art" aria-hidden>
        <img src={wallpaper} alt="" />
      </div>
    </section>
  );
}

type ActivityState = { data: Activity | null; at: number | null; loading: boolean; error: string | null };
const NO_ACTIVITY: ActivityState = { data: null, at: null, loading: false, error: null };

function StoreAccount({ account, info, test, navigate }: { account: Account; info: StoreInfo | null; test: boolean; navigate: Navigate }) {
  const t = useT();
  const toast = useToast();
  const { motionOk } = useSettings();
  const uuid = account.uuid;
  const skin = useStoreSelect((s) => s.skins[uuid] ?? null);
  const [view, setView] = useState<StoreView | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [pending, setPending] = useState<Wearing>(null);
  const [wearError, setWearError] = useState<{ slot: StoreSlot; text: string } | null>(null);
  const [pointed, setPointed] = useState<StoreItem | null>(null);
  const [activity, setActivity] = useState<ActivityState>(NO_ACTIVITY);
  const [activityRun, setActivityRun] = useState(0);
  const [panel, setPanel] = useState<ActivityTab | null>(null);
  const [approving, setApproving] = useState(false);
  const now = useNow(30_000);
  const storeUrl = view?.storeUrl ?? info?.storeUrl ?? "https://store.flintclient.com";
  const host = storeHost(storeUrl);
  const phase = storePhase({ account, view, loadError });

  /** Shows an answer only if it is for this account. */
  const show = useCallback(
    (next: StoreView) => {
      const mine = viewFor(uuid, next);
      if (mine) {
        setView(mine);
        setLoadError(null);
      }
    },
    [uuid],
  );

  const reload = useCallback(
    async (interactive = false) => {
      try {
        show(await api.storeInventory(uuid, interactive));
      } catch (e) {
        setLoadError(errorText(e));
      }
    },
    [uuid, show],
  );

  useEffect(() => {
    void reload(false);
  }, [reload]);

  useStoreChanges(uuid, view, reload);

  // Orders and Merit movements: with the page, again when the inventory's version changes (a purchase, a refund) and on
  // Refresh. Only while the Store answers; they're never cached.
  const version = view?.inventory?.version ?? null;
  const live = phase === "ready";
  useEffect(() => {
    if (!live) return;
    let cancelled = false;
    setActivity((a) => ({ ...a, loading: true }));
    Promise.all([api.storeWallet(uuid), api.storeOrders(uuid)]).then(
      ([wallet, orders]) => !cancelled && setActivity({ data: { wallet, orders }, at: Date.now(), loading: false, error: null }),
      (e) => !cancelled && setActivity((a) => ({ ...a, loading: false, error: errorText(e) })),
    );
    return () => {
      cancelled = true;
    };
  }, [uuid, live, version, activityRun]);

  const run = async (key: string, work: () => Promise<void>) => {
    setBusy(key);
    try {
      await work();
    } finally {
      setBusy(null);
    }
  };

  const signIn = () =>
    run("sign-in", async () => {
      try {
        show(await api.storeSignIn(uuid));
      } catch (e) {
        toast({ tone: "danger", title: t("store.signIn.failed"), body: errorText(e) });
      }
    });

  const signOut = () =>
    run("sign-out", async () => {
      try {
        await api.storeSignOut(uuid);
        setActivity(NO_ACTIVITY);
        setPanel(null);
        await reload(false);
        toast({ tone: "neutral", title: t("store.signOut.done", { name: account.username }), body: t("store.signOut.doneBody") });
      } catch (e) {
        toast({ tone: "danger", title: t("store.signOut.failed"), body: errorText(e) });
      }
    });

  const refresh = () =>
    run("refresh", async () => {
      await reload(true);
      setActivityRun((n) => n + 1);
    });

  /** Shown at once; the Store's answer then replaces it, or the previous state comes back with the reason. */
  const wear = async (slot: StoreSlot, cosmeticId: string | null) => {
    if (pending) return;
    setPending({ slot, cosmeticId });
    setWearError(null);
    try {
      show(await api.storeEquip(uuid, slot, cosmeticId));
    } catch (e) {
      const text = errorText(e);
      setWearError({ slot, text });
      toast({ tone: "danger", title: cosmeticId ? t("store.wear.failed") : t("store.wear.takeOffFailed"), body: text });
      await reload(false);
    } finally {
      setPending(null);
    }
  };

  const openStore = () => void api.openExternal(storeUrl).catch((e) => toast({ tone: "danger", title: t("store.openFailed"), body: errorText(e) }));

  const inventory = view?.inventory ? (pending ? withEquipped(view.inventory, pending.slot, pending.cosmeticId) : view.inventory) : null;
  // Community Rewards: the Store's public list joined with this account's standing (nothing at all from a Store without it).
  const community = useCommunity(uuid, live, version, reload);
  const cards = useMemo(() => joinRewards(community.catalog, community.rewards, inventory), [community.catalog, community.rewards, inventory]);
  const openInvite = (url: string) => void api.storeOpenDiscordInvite(url).catch((e) => toast({ tone: "danger", title: t("store.rewards.inviteFailed"), body: errorText(e) }));
  // The cape this account shows: the launcher-wide rule (lib/displayedCape.ts), as Home and the Skins page show it.
  const displayed = useDisplayedCape(uuid);
  // This page's inventory (with a wear on its way) is ahead of it for a moment after each wear: until they agree, the
  // stage shows the page's choice, so a cape never goes back to the previous one while the Store's answer travels.
  const wornItem = wornIn(inventory, "cape");
  const wornFlint = wornItem?.asset ? wornItem : null;
  const wornCape = useItemCape(uuid, wornFlint, motionOk);
  const minecraftCape = skin?.capeUrl ?? null;
  const worn = useMemo<ShownCape<ImageBitmap>>(() => {
    const agrees = wornFlint ? displayed.source === "flint" && displayed.cosmeticId === wornFlint.cosmeticId : displayed.source !== "flint";
    if (agrees) return displayed;
    if (wornFlint) return wornCape ?? displayed;
    return minecraftCape ? { source: "minecraft", url: minecraftCape } : NO_CAPE;
  }, [displayed, wornFlint, wornCape, minecraftCape]);
  // Pointing at what the account wears is no preview.
  const pointedCape = useItemCape(uuid, pointed && pointed.cosmeticId !== wornFlint?.cosmeticId ? pointed : null, motionOk);
  const merit = meritShown(view, activity.data ? { balance: activity.data.wallet.balance, at: activity.at ?? 0 } : null);

  if (phase === "loading") return <StoreSkeleton test={test} />;
  if (phase === "error")
    return (
      <>
        <StoreHeader test={test} />
        <StoreHero icon="alert" title={t("store.load.failed")} body={loadError ?? undefined}>
          <Button variant="primary" icon="refresh" onClick={() => void reload(true)}>
            {t("common.tryAgain")}
          </Button>
        </StoreHero>
      </>
    );
  if (phase === "locked" || phase === "signedOut") {
    const locked = phase === "locked";
    return (
      <>
        <StoreHeader test={test} />
        <StoreHero
          icon={locked ? "lock" : undefined}
          perks={!locked}
          title={locked ? t("store.signIn.lockedTitle") : t("store.signIn.title", { name: account.username })}
          body={locked ? (view?.problem ?? undefined) : undefined}
          note={
            !locked && (
              <>
                {view?.problem && (
                  <p className="store-hero-note" role="status">
                    <Icon name="info" size={14} /> {view.problem}
                  </p>
                )}
                <p className="store-hero-trust">
                  <Icon name="lock" size={13} />
                  {t("store.signIn.body")}
                </p>
              </>
            )
          }
        >
          {locked ? (
            <Button variant="primary" onClick={() => void reload(true)}>
              {t("common.tryAgain")}
            </Button>
          ) : (
            <Button variant="primary" size="lg" icon="bag" loading={busy === "sign-in"} onClick={() => void signIn()}>
              {t("store.signIn.button")}
            </Button>
          )}
          <Button variant="ghost" size={locked ? "md" : "lg"} iconRight="external" onClick={openStore}>
            {t("store.summary.openStore")}
          </Button>
        </StoreHero>
      </>
    );
  }

  const offline = phase !== "ready";
  const menu: MenuItem[] = [
    { label: t("store.approve.title"), icon: <Icon name="link" size={16} />, disabled: offline, onSelect: () => setApproving(true) },
    { label: t("store.summary.refresh"), icon: <Icon name="refresh" size={16} />, onSelect: () => void refresh() },
    { type: "separator" },
    { label: t("store.summary.signOut"), icon: <Icon name="logout" size={16} />, onSelect: () => void signOut() },
  ];
  const header = (
    <StoreHeader test={test}>
      {merit.balance !== null && <MeritPill merit={merit} onOpen={() => setPanel("merit")} />}
      <Button variant="secondary" icon="history" onClick={() => setPanel("orders")}>
        {t("store.activity.orders")}
      </Button>
      <Button variant="primary" iconRight="external" onClick={openStore}>
        {t("store.summary.openStore")}
      </Button>
      <MoreMenu items={menu} busy={busy === "refresh" || busy === "sign-out"} />
    </StoreHeader>
  );
  const overlays = (
    <>
      <StoreActivityPanel
        tab={panel}
        onTab={setPanel}
        onClose={() => setPanel(null)}
        data={activity.data}
        loading={activity.loading}
        error={activity.error}
        offline={offline}
        onRetry={() => setActivityRun((n) => n + 1)}
      />
      <StoreApproveDialog open={approving} onClose={() => setApproving(false)} uuid={uuid} name={account.username} host={host} disabled={offline} />
    </>
  );

  if (phase === "unreachable")
    return (
      <>
        {header}
        <StoreHero icon="wifiOff" title={t("store.unreachable.title")} body={t("store.unreachable.body")}>
          <Button variant="primary" icon="refresh" loading={busy === "refresh"} onClick={() => void refresh()}>
            {t("common.tryAgain")}
          </Button>
        </StoreHero>
        {overlays}
      </>
    );

  const stageCape = pointedCape ?? worn;
  // A listed reward pointed at (locked: not in the inventory) names itself on the stage too.
  const stageItem =
    stageCape.source === "flint" ? (inventory?.owned.find((o) => o.cosmeticId === stageCape.cosmeticId) ?? (pointed?.cosmeticId === stageCape.cosmeticId ? pointed : null)) : null;
  return (
    <>
      {header}

      {offline && (
        <Callout
          tone="warn"
          icon="wifiOff"
          title={t("store.offline.title")}
          live
          className="store-offline"
          action={
            <Button size="sm" icon="refresh" loading={busy === "refresh"} onClick={() => void refresh()}>
              {t("common.tryAgain")}
            </Button>
          }
        >
          {t("store.offline.readOnly", { updated: updatedText(view?.fetchedAt, now) })}
        </Callout>
      )}

      <div className="store-main">
        <section className="store-stage" aria-label={t("store.preview.title")}>
          <StorePreview
            skin={skin?.dataUrl ?? null}
            model={skin?.model ?? account.skinModel ?? "classic"}
            cape={stageCape}
            motion={motionOk}
            label={stageItem ? t("store.preview.labelCape", { name: account.username, cape: stageItem.name }) : t("store.preview.label", { name: account.username })}
            tag={
              pointedCape && stageItem ? (
                <>
                  <span>{t("store.preview.previewing")}</span>
                  <strong>{stageItem.name}</strong>
                </>
              ) : null
            }
          />
        </section>

        {inventory ? (
          <StoreInventoryCard
            uuid={uuid}
            inventory={inventory}
            pending={pending}
            error={wearError}
            readOnly={offline}
            hasMinecraftCape={!!skin?.capeUrl}
            motion={motionOk}
            onWear={(slot, id) => void wear(slot, id)}
            onPreview={setPointed}
            onOpenStore={openStore}
            community={
              cards.length
                ? {
                    cards,
                    rewards: community.rewards,
                    recheck: community.recheck,
                    onRecheck: () => void community.checkAgain(),
                    onConnect: () => navigate("settings", "connections"),
                    onInvite: openInvite,
                  }
                : null
            }
          />
        ) : (
          <section className="store-items is-empty">
            <EmptyState icon="bag" title={t("store.items.title")} body={t("store.items.notHeard")} />
          </section>
        )}
      </div>
      {overlays}
    </>
  );
}

/** The Merit balance: the star and the amount, "Merits" in the language's order. Opens the Merit history. */
function MeritPill({ merit, onOpen }: { merit: MeritShown; onOpen: () => void }) {
  const t = useT();
  const text = meritText(merit.balance);
  const value = formatStoreMerit(merit.balance);
  const at = text ? text.indexOf(value) : -1;
  return (
    <Tooltip label={t("store.merit.tooltip")} side="bottom">
      <button
        type="button"
        className={`store-merit ${merit.stale ? "is-stale" : ""}`}
        aria-label={text ? t("store.merit.balanceLabel", { amount: text }) : t("store.merit.balanceUnknown")}
        onClick={onOpen}
      >
        <img className="merit-star" src={meritStar} srcSet={`${meritStar} 1x, ${meritStar2x} 2x, ${meritStar3x} 3x`} alt="" />
        {text && at >= 0 ? (
          <span className="store-merit-text">
            {at > 0 && <span className="store-merit-unit">{text.slice(0, at)}</span>}
            <span className="store-merit-value">{value}</span>
            <span className="store-merit-unit">{text.slice(at + value.length)}</span>
          </span>
        ) : (
          <span className="store-merit-unit">—</span>
        )}
      </button>
    </Tooltip>
  );
}

/** "⋯": the Store's less frequent actions. Shows a spinner while one of them is under way. */
function MoreMenu({ items, busy }: { items: MenuItem[]; busy: boolean }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const anchor = useRef<HTMLButtonElement>(null);
  return (
    <>
      <button
        ref={anchor}
        type="button"
        className="icon-btn icon-btn-md icon-btn-solid store-more"
        aria-label={t("store.more")}
        title={t("store.more")}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-busy={busy || undefined}
        onClick={() => setOpen((o) => !o)}
      >
        {busy ? <Spinner size={16} /> : <Icon name="more" size={18} />}
      </button>
      <Popover anchor={anchor} open={open} onClose={() => setOpen(false)} placement="bottom-end" label={t("store.more")}>
        <Menu items={items} onClose={() => setOpen(false)} />
      </Popover>
    </>
  );
}

/** The signed-in page's shape while the first answer comes, so nothing moves when it does. */
function StoreSkeleton({ test }: { test: boolean }) {
  return (
    <div className="store-skeleton" aria-busy="true">
      <StoreHeader test={test}>
        <span className="sk" style={{ width: 118, height: 38, borderRadius: 999 }} />
        <span className="sk" style={{ width: 104, height: 38, borderRadius: 8 }} />
        <span className="sk" style={{ width: 168, height: 38, borderRadius: 8 }} />
        <span className="sk" style={{ width: 38, height: 38, borderRadius: 8 }} />
      </StoreHeader>
      <div className="store-main" aria-hidden>
        <div className="store-stage is-skeleton">
          <span className="store-stage-ghost">
            <span className="sk" />
            <span className="sk" />
          </span>
          <span className="store-stage-floor" />
        </div>
        <div className="store-items">
          <div className="store-items-bar">
            <span className="sk" style={{ width: 340, maxWidth: "100%", height: 40, borderRadius: 12 }} />
          </div>
          <div className="store-grid">
            {Array.from({ length: 8 }, (_, i) => (
              <span key={i} className="store-tile is-skeleton">
                <span className="store-tile-art">
                  <span className="sk" />
                </span>
                <span className="store-tile-body">
                  <span className="sk sk-line" style={{ width: "68%" }} />
                  <span className="sk sk-line" style={{ width: "36%" }} />
                </span>
              </span>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

/** Keeps the page current: the Store's change feed at its own pace while the window is in front, and at once on focus. */
function useStoreChanges(uuid: string, view: StoreView | null, reload: (interactive?: boolean) => Promise<void>) {
  const live = useRef({ version: null as number | null, stale: false });
  live.current = { version: view?.inventory?.version ?? null, stale: !!view?.stale };
  const signedIn = !!view?.signedIn;

  useEffect(() => {
    if (!signedIn) return;
    let disposed = false;
    let timer: number | undefined;
    let delay = POLL_DEFAULT_MS;
    let lastCheck = 0;
    let focused = typeof document === "undefined" || document.hasFocus();

    const stopTimer = () => {
      if (timer !== undefined) window.clearTimeout(timer);
      timer = undefined;
    };
    const schedule = () => {
      if (!disposed && focused && timer === undefined) timer = window.setTimeout(() => void check(), delay);
    };
    const check = async () => {
      stopTimer();
      lastCheck = Date.now();
      const result: StoreChanges = await api.storeChanges(uuid, live.current.version).catch(() => ({ state: "offline" }) as const);
      if (disposed) return;
      if (needsRefresh(result, live.current.stale)) await reload(false);
      const next = nextPollDelay(delay, result);
      if (next === null) return; // signed out: the page now says so
      delay = next;
      schedule();
    };
    const onFocus = (now: boolean) => {
      focused = now;
      if (!now) stopTimer();
      else if (Date.now() - lastCheck > FOCUS_CHECK_GAP_MS) void check();
      else schedule();
    };
    const offNative = onWindowFocus(onFocus);
    const onWebFocus = () => onFocus(true);
    const onWebBlur = () => onFocus(false);
    window.addEventListener("focus", onWebFocus);
    window.addEventListener("blur", onWebBlur);
    schedule();
    return () => {
      disposed = true;
      stopTimer();
      offNative();
      window.removeEventListener("focus", onWebFocus);
      window.removeEventListener("blur", onWebBlur);
    };
  }, [uuid, signedIn, reload]);
}
