import { lazy, Suspense, useEffect, useMemo, useRef, useState, type ComponentType } from "react";
import { api, errorText, releaseLabel, subscribe, type ImportResult, type Job, type Profile, type UpdateResult } from "../../lib/api";
import { isShownInPlace, resultSummary } from "../../lib/contentUpdates";
import { liveOutcome } from "../../lib/livePacks";
import { bytes } from "../../lib/format";
import { useT, type MessageKey } from "../../lib/i18n";
import { sidebarProfiles } from "../../lib/profileOrder";
import { useSettings } from "../../lib/settings";
import { sound } from "../../lib/sound";
import { jobActive, useStoreSelect } from "../../lib/store";
import { IconButton, Progress, Spinner } from "../../ui/controls";
import { Icon, type IconName } from "../../ui/Icon";
import { FlintMark } from "../../ui/Logo";
import { PerfArea } from "../../lib/perf";
import { Menu, Popover, Tooltip, useToast } from "../../ui/overlays";
import { Avatar } from "../onboarding/steps";
import { Home } from "../home/Home";
import { ProfileIcon } from "../profiles/ProfileIcon";
import { usePinToggle } from "../profiles/usePinToggle";
import { WindowChrome } from "./WindowChrome";
import { UpdateButton } from "./UpdateButton";
import { startUpdateChecks } from "../../lib/updater";
import { usePackOpenRequests } from "../../lib/flintpack";
import { outcome } from "../import/model";
import { importOnScreen } from "../import/useImportFlow";
import { FriendsBadge, FriendsProvider } from "../friends/FriendsProvider";

/**
 * A page loaded on demand. `preload` fetches it ahead of time; once loaded the page renders directly
 * instead of through lazy(), so a first visit never suspends (React holds a Suspense fallback on
 * screen for ~300 ms even when the code arrives a moment later).
 */
function pageChunk<P extends object>(load: () => Promise<{ default: ComponentType<P> }>) {
  let loaded: ComponentType<P> | null = null;
  const preload = () =>
    load().then((m) => {
      loaded = m.default;
      return m;
    });
  const Lazy = lazy(preload);
  const Page = (props: P) => {
    // Chosen once per visit: switching from Lazy to the loaded component later would remount the page.
    const [Component] = useState(() => loaded ?? Lazy);
    return <Component {...props} />;
  };
  return Object.assign(Page, { preload });
}

const LibraryPage = pageChunk(() => import("../library/LibraryPage"));
const SkinsPage = pageChunk(() => import("../pages/SkinsPage"));
const ProfilesPage = pageChunk(() => import("../profiles/ProfilesPage"));
const StorePage = pageChunk(() => import("../pages/StorePage"));
const SettingsPage = pageChunk(() => import("../pages/SettingsPage"));
const ImportPage = pageChunk(() => import("../pages/ImportPage"));
const FriendsPage = pageChunk(() => import("../friends/FriendsPage"));
/** Fetched one after another once start-up has settled, in the order people usually open them. */
const PRELOAD = [ProfilesPage, LibraryPage, SettingsPage, FriendsPage, SkinsPage, StorePage];

export type Page = "play" | "library" | "skins" | "profiles" | "friends" | "store" | "settings" | "import";
/** `param` carries page-specific targets: a settings section, a profile id, `profileId:tab`, or a friend to chat with. */
export type Navigate = (page: Page, param?: string) => void;

const NAV: { id: Page; label: MessageKey; icon: IconName }[] = [
  { id: "play", label: "shell.nav.play", icon: "play" },
  { id: "library", label: "shell.nav.library", icon: "library" },
  { id: "skins", label: "shell.nav.skins", icon: "shirt" },
  { id: "profiles", label: "shell.nav.profiles", icon: "layers" },
  { id: "friends", label: "shell.nav.friends", icon: "users" },
  { id: "store", label: "shell.nav.store", icon: "bag" },
  { id: "settings", label: "shell.nav.settings", icon: "gear" },
];

/** Import Profile: in the rail above Settings only when Settings → General shows it. No shortcut, so the others keep theirs. */
const IMPORT_ITEM: { id: Page; label: MessageKey; icon: IconName } = { id: "import", label: "shell.nav.import", icon: "import" };

/** A launcher import's job result (a `.flintpack` import is an "import" job too, with a single profile). */
const isImportResult = (result: unknown): result is ImportResult => Array.isArray((result as ImportResult | null)?.profiles);

export function Shell() {
  const t = useT();
  const info = useStoreSelect((s) => s.info);
  const { importInSidebar: showImport, friendsInSidebar: showFriends } = useSettings().settings;
  // Friends in the sidebar is the player's choice (Settings → General, off by default); its notifications still open it.
  const nav = showFriends ? NAV : NAV.filter((n) => n.id !== "friends");
  const [qaPage, qaParam] = (info.qaStartPage ?? "").split(/:(.*)/s);
  const [page, setPage] = useState<Page>(NAV.some((n) => n.id === qaPage) || (qaPage === "import" && showImport) ? (qaPage as Page) : "play");
  const [param, setParam] = useState<string | undefined>(qaParam || undefined);
  const [visit, setVisit] = useState(0);
  // The profile open on the Profiles page (it changes there without navigating), for the sidebar to mark.
  const [openProfile, setOpenProfile] = useState<string | null>(null);

  const navigate: Navigate = (next, nextParam) => {
    if (next !== page) sound.play("nav");
    setParam(nextParam);
    setPage(next);
    setVisit((v) => v + 1);
  };

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!(e.metaKey || e.ctrlKey) || e.altKey) return;
      const index = Number(e.key) - 1;
      if (index >= 0 && index < nav.length) {
        e.preventDefault();
        navigate(nav[index].id);
      }
      if (e.key === ",") {
        e.preventDefault();
        navigate("settings");
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  // Import Profile turned off in Settings while its page is open: back to Home. Imported profiles are unaffected.
  useEffect(() => {
    if (page === "import" && !showImport) navigate("play");
  });

  // Quiet update checks: a few seconds after start-up, then every few hours (never blocks the launcher).
  useEffect(() => startUpdateChecks(), []);

  // A double-clicked .flintpack opens its import preview on Profiles.
  usePackOpenRequests(() => navigate("profiles"));

  useEffect(() => {
    let cancelled = false;
    const timer = window.setTimeout(async () => {
      for (const chunk of PRELOAD) {
        if (cancelled) return;
        // A failed fetch is retried by lazy() when the page is opened.
        await chunk.preload().catch(() => {});
      }
    }, 1500);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, []);

  return (
    <FriendsProvider navigate={navigate}>
      <div className={`shell page-${page} platform-${info.platform}`}>
        <nav className="rail" aria-label={t("shell.nav.label")}>
          <span className="rail-logo" title="Flint Client">
            <FlintMark size={30} />
          </span>
          <ul>
            {(showImport ? [...nav.slice(0, -1), IMPORT_ITEM, nav[nav.length - 1]] : nav).map((item) => {
              const i = nav.indexOf(item);
              const label = t(item.label);
              const shortcut = `${info.platform === "macos" ? "⌘" : "Ctrl+"}${i + 1}`;
              const button = (
                <Tooltip label={i < 0 ? label : t("shell.nav.withShortcut", { label, shortcut })} side="right">
                  <button
                    type="button"
                    className={`rail-item ${page === item.id ? "is-active" : ""}`}
                    aria-current={page === item.id ? "page" : undefined}
                    aria-label={label}
                    onClick={() => navigate(item.id)}
                  >
                    <Icon name={item.icon} size={20} />
                    {item.id === "friends" && <FriendsBadge />}
                  </button>
                </Tooltip>
              );
              return item.id === "profiles" ? (
                <li key={item.id} className="rail-profiles">
                  <SidebarProfiles button={button} active={page === "profiles"} navigate={navigate} openId={page === "profiles" ? (openProfile ?? undefined) : undefined} />
                </li>
              ) : (
                <li key={item.id} className={item.id === "import" ? "rail-import" : undefined}>
                  {button}
                </li>
              );
            })}
          </ul>
          <span className="rail-version" title={`Flint Client ${releaseLabel(info)}`}>
            {info.version}
          </span>
        </nav>

        <PerfArea id="topbar">
          <header className="topbar" data-tauri-drag-region>
            <div className="topbar-fill" data-tauri-drag-region />
            <JobsTray navigate={navigate} />
            <UpdateButton />
            <AccountMenu onManage={() => navigate("settings", "accounts")} />
            <WindowChrome />
          </header>
        </PerfArea>

        <main className="stage">
          {/* Home stays mounted so its wallpaper and 3D character survive tab switches; it pauses while hidden. */}
          <div className={`page page-home ${page === "play" ? "" : "is-parked"}`} aria-hidden={page !== "play"} inert={page !== "play"}>
            <PerfArea id="home">
              <Home navigate={navigate} active={page === "play"} />
            </PerfArea>
          </div>
          {page !== "play" && (
            <div className="page" key={`${page}-${visit}`}>
              <Suspense
                fallback={
                  <div className="page-loading">
                    <Spinner />
                  </div>
                }
              >
                <PerfArea id={page}>
                  {page === "library" && <LibraryPage navigate={navigate} target={param} />}
                  {page === "skins" && <SkinsPage navigate={navigate} />}
                  {page === "profiles" && <ProfilesPage navigate={navigate} target={param} onOpenChange={setOpenProfile} />}
                  {page === "store" && <StorePage navigate={navigate} />}
                  {page === "settings" && <SettingsPage initialSection={param} />}
                  {page === "import" && <ImportPage navigate={navigate} />}
                  {page === "friends" && <FriendsPage navigate={navigate} target={param} />}
                </PerfArea>
              </Suspense>
            </div>
          )}
        </main>
      </div>
    </FriendsProvider>
  );
}

/**
 * Profiles in the sidebar, as one tile: the Profiles button, with the arrow in its lower edge that opens the player's
 * profiles inside the tile, under the arrow (pinned ones first, then the most played, five at most, then View more), each
 * as its icon. Open or closed is kept across restarts; Store and Settings move down while it is open.
 */
function SidebarProfiles({ button, active, navigate, openId }: { button: React.ReactNode; active: boolean; navigate: Navigate; openId?: string }) {
  const t = useT();
  const { settings, update } = useSettings();
  const profiles = useStoreSelect((s) => s.profiles);
  const open = settings.sidebarProfilesOpen;
  const { shown, more } = useMemo(() => sidebarProfiles(profiles), [profiles]);
  const has = profiles.length > 0;
  const toggleLabel = open ? t("shell.sidebar.hideProfiles") : t("shell.sidebar.showProfiles");
  // Right-click a profile: open it, or pin / unpin it (pinning also lives in its ••• menu).
  const pin = usePinToggle();
  const menuAnchor = useRef<HTMLButtonElement | null>(null);
  const [menuFor, setMenuFor] = useState<Profile | null>(null);
  return (
    <>
      <div className={`rail-combo ${active ? "is-active" : ""} ${has ? "has-toggle" : ""} ${has && open ? "is-open" : ""}`}>
        <div className="rail-combo-head">
          {button}
          {has && (
            <Tooltip label={toggleLabel} side="right">
              <button
                type="button"
                className="rail-combo-toggle"
                aria-expanded={open}
                aria-controls="rail-profile-list"
                aria-label={toggleLabel}
                onClick={() => {
                  sound.play("tap");
                  update({ sidebarProfilesOpen: !open });
                }}
              >
                <Icon name="chevronDown" size={13} />
              </button>
            </Tooltip>
          )}
        </div>
        {/* The profiles open inside the tile, under its arrow: the tile grows down around them. */}
        {has && (
          <div id="rail-profile-list" className={`rail-profile-list ${open ? "is-open" : ""}`} aria-hidden={!open} inert={!open}>
            <div className="rail-profile-inner">
              {shown.map((p, i) => (
                <Tooltip key={p.id} label={t(p.pinnedAt ? "shell.sidebar.profilePinned" : "shell.sidebar.profile", { name: p.name, version: p.gameVersion })} side="right">
                  <button
                    type="button"
                    className={`rail-profile ${p.id === openId ? "is-open-profile" : ""}`}
                    style={{ "--i": i } as React.CSSProperties}
                    aria-label={t("shell.sidebar.openProfile", { name: p.name })}
                    aria-haspopup="menu"
                    onClick={() => navigate("profiles", p.id)}
                    onContextMenu={(e) => {
                      e.preventDefault();
                      menuAnchor.current = e.currentTarget;
                      setMenuFor(p);
                    }}
                  >
                    <ProfileIcon profile={p} size={34} />
                    {p.pinnedAt ? <span className="rail-pin" aria-hidden /> : null}
                  </button>
                </Tooltip>
              ))}
              {more && (
                <Tooltip label={t("shell.sidebar.viewAll")} side="right">
                  <button
                    type="button"
                    className="rail-more"
                    style={{ "--i": shown.length } as React.CSSProperties}
                    aria-label={t("shell.sidebar.viewAll")}
                    onClick={() => navigate("profiles")}
                  >
                    <Icon name="more" size={16} />
                  </button>
                </Tooltip>
              )}
            </div>
          </div>
        )}
      </div>
      <Popover anchor={menuAnchor} open={!!menuFor} onClose={() => setMenuFor(null)} placement="right" label={menuFor?.name ?? t("shell.sidebar.menu.label")}>
        {menuFor && (
          <Menu
            onClose={() => setMenuFor(null)}
            items={[
              { type: "heading", label: menuFor.name },
              { label: t("shell.sidebar.menu.open"), icon: <Icon name="layers" size={15} />, onSelect: () => navigate("profiles", menuFor.id) },
              {
                label: menuFor.pinnedAt ? t("shell.sidebar.menu.unpin") : t("shell.sidebar.menu.pin"),
                icon: <Icon name="star" size={15} />,
                onSelect: () => pin(menuFor),
              },
            ]}
          />
        )}
      </Popover>
    </>
  );
}

function jobProgress(job: Job): number | null {
  if (job.state !== "running") return job.state === "done" ? 1 : null;
  if (job.bytesTotal) return Math.min(1, job.bytesDone / job.bytesTotal);
  if (job.itemsTotal) return Math.min(1, job.itemsDone / job.itemsTotal);
  return null;
}

function JobsTray({ navigate }: { navigate: Navigate }) {
  const t = useT();
  const store = useStoreSelect((s) => ({ jobs: s.jobs, dismissJob: s.dismissJob }));
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const anchor = useRef<HTMLButtonElement>(null);
  const seen = useRef(new Map<string, string>());
  const visible = store.jobs.filter((j) => j.kind !== "install" || j.title !== "Mods for your new profile");
  const active = visible.filter(jobActive);

  useEffect(() => {
    for (const job of visible) {
      const previous = seen.current.get(job.id);
      seen.current.set(job.id, job.state);
      if (!previous || previous === job.state || isShownInPlace(job.id)) continue;
      // The Import Profile page showing this import says it itself.
      if (job.kind === "import" && importOnScreen(job.id)) continue;
      if (job.state === "done" && job.kind === "import" && isImportResult(job.result)) {
        const told = outcome(job.result, false);
        toast({
          tone: told.imported ? "success" : "danger",
          title: told.title,
          body: told.body,
          action: told.imported ? { label: t("shell.jobs.showInProfiles"), onClick: () => navigate("profiles") } : undefined,
        });
      } else if (job.state === "done") {
        const result = job.result as { installed?: string[]; mods?: number; path?: string; name?: string } | undefined;
        if (job.kind === "export" && result?.path) {
          const path = result.path;
          toast({
            tone: "success",
            title: t("shell.jobs.exported"),
            body: path.split(/[\\/]/).pop(),
            action: { label: t("common.showInFolder"), onClick: () => void api.packReveal(path).catch((e) => toast({ tone: "danger", title: t("shell.jobs.revealFailed"), body: errorText(e) })) },
          });
        } else if (job.kind === "install" && liveOutcome(result)) {
          // Packs that went into the running game (content/livepacks.rs): the game shows its own notice as they arrive.
          const names = result?.installed?.join(", ") ?? job.title;
          const told = liveOutcome(result);
          toast({
            tone: "success",
            title: t("shell.jobs.liveTitle"),
            body: told === "on" ? t("shell.jobs.liveOn", { names }) : told === "datapack" ? t("shell.jobs.liveDatapack", { names }) : t("shell.jobs.liveListed", { names }),
          });
        } else if (job.kind === "update" && result && "updated" in result) {
          // Some items may not have gone through; the summary says so instead of a plain "Updated".
          toast(resultSummary(result as UpdateResult));
        } else if (job.kind === "import") {
          const body = result?.name ? t("shell.jobs.importedBody", { name: result.name }) : t("shell.jobs.importedBodyUnnamed");
          toast({ tone: "success", title: t("shell.jobs.imported"), body });
        } else {
          toast({
            tone: "success",
            title: job.kind === "modpack" ? t("shell.jobs.modpackReady") : job.kind === "update" ? t("shell.jobs.updated") : t("shell.jobs.installed"),
            // The backend titles a modpack job "Installing <name>" (in English).
            body: job.kind === "modpack" ? t("shell.jobs.modpackReadyBody", { name: job.title.replace(/^Installing /, "") }) : result?.installed?.join(", ") ?? job.title,
          });
        }
      } else if (job.state === "failed") {
        toast({ tone: "danger", title: t("shell.jobs.failed", { title: job.title }), body: job.message ?? undefined });
      }
    }
  }, [visible, toast, t]);

  if (visible.length === 0) return null;
  const lead = active[0];
  return (
    <>
      <button ref={anchor} type="button" className={`jobs-chip ${active.length ? "is-active" : ""}`} aria-haspopup="dialog" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        {active.length ? <Spinner size={14} /> : <Icon name="download" size={15} />}
        <span>{active.length ? (active.length === 1 ? lead.stage || t("shell.jobs.working") : t("shell.jobs.tasks", { count: active.length })) : t("shell.jobs.title")}</span>
      </button>
      <Popover anchor={anchor} open={open} onClose={() => setOpen(false)} placement="bottom-end" label={t("shell.jobs.title")} className="jobs-pop">
        <header className="jobs-head">
          <strong>{t("shell.jobs.title")}</strong>
          <span className="faint">{t("shell.jobs.verified")}</span>
        </header>
        <ul className="jobs-list">
          {visible
            .slice()
            .reverse()
            .map((job) => {
              const progress = jobProgress(job);
              return (
                <li key={job.id} className={`job job-${job.state}`}>
                  <div className="job-top">
                    <strong title={job.title}>{job.title}</strong>
                    {jobActive(job) ? (
                      <IconButton icon="x" size="sm" label={t("common.cancel")} onClick={() => void api.cancelJob(job.id).catch((e) => toast({ tone: "danger", title: t("shell.jobs.cancelFailed"), body: errorText(e) }))} />
                    ) : (
                      <IconButton icon="x" size="sm" label={t("common.dismiss")} onClick={() => store.dismissJob(job.id)} />
                    )}
                  </div>
                  <span className={`job-stage ${job.state === "failed" ? "is-danger" : ""}`}>
                    {job.state === "failed"
                      ? job.message
                      : job.state === "cancelled"
                        ? job.message ?? t("shell.jobs.cancelled")
                        : job.state === "running" && job.bytesTotal
                          ? t("shell.jobs.stageWithBytes", { stage: job.stage, done: bytes(job.bytesDone), total: bytes(job.bytesTotal) })
                          : job.stage}
                  </span>
                  {jobActive(job) && <Progress value={progress} label={job.title} />}
                  {job.state === "done" && job.kind === "export" && typeof (job.result as { path?: unknown } | null)?.path === "string" && (
                    <button type="button" className="job-link" onClick={() => void api.packReveal((job.result as { path: string }).path).catch((e) => toast({ tone: "danger", title: t("shell.jobs.revealFailed"), body: errorText(e) }))}>
                      {t("common.showInFolder")}
                    </button>
                  )}
                  {!jobActive(job) && isImportResult(job.result) && job.result.profiles.some((p) => p.state === "done") && (
                    <button
                      type="button"
                      className="job-link"
                      onClick={() => {
                        setOpen(false);
                        navigate("profiles");
                      }}
                    >
                      {t("shell.jobs.showInProfiles")}
                    </button>
                  )}
                  {job.state === "done" && job.kind !== "export" && job.profileId && (
                    <button
                      type="button"
                      className="job-link"
                      onClick={() => {
                        setOpen(false);
                        navigate("profiles", `${job.profileId}:content`);
                      }}
                    >
                      {t("shell.jobs.openProfile")}
                    </button>
                  )}
                </li>
              );
            })}
        </ul>
      </Popover>
    </>
  );
}

function AccountMenu({ onManage }: { onManage: () => void }) {
  const t = useT();
  const store = useStoreSelect((s) => ({ accounts: s.accounts, activeAccount: s.activeAccount, reloadAccounts: s.reloadAccounts, switchAccount: s.switchAccount }));
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [adding, setAdding] = useState(false);
  const anchor = useRef<HTMLButtonElement>(null);
  const account = store.activeAccount;

  // An account added from a game's main menu (Flint ran the sign-in for it), or one a game's main menu signed out.
  useEffect(
    () =>
      subscribe<{ added: string | null; removed?: string | null }>("accounts-changed", (e) => {
        if (e.added) toast({ tone: "success", title: t("shell.account.signedInAs", { name: e.added }), body: t("shell.account.addedInGame") });
        if (e.removed) toast({ tone: "neutral", title: t("shell.account.signedOut", { name: e.removed }), body: t("shell.account.removedInGame") });
      }),
    [toast, t],
  );

  const add = async () => {
    setAdding(true);
    try {
      const added = await api.signIn();
      await store.reloadAccounts();
      toast({ tone: "success", title: t("shell.account.signedInAs", { name: added.username }) });
    } catch (e) {
      toast({ tone: "danger", title: t("shell.account.signInFailed"), body: errorText(e) });
    } finally {
      setAdding(false);
    }
  };

  return (
    <>
      <button ref={anchor} type="button" className="account-chip" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        {adding ? <Spinner size={16} /> : <Avatar account={account} size={26} />}
        <span className="account-name">{account?.username ?? t("shell.account.notSignedIn")}</span>
        <Icon name="chevronDown" size={14} />
      </button>
      <Popover anchor={anchor} open={open} onClose={() => setOpen(false)} placement="bottom-end" label={t("shell.account.menuLabel")} className="account-pop">
        <Menu
          onClose={() => setOpen(false)}
          items={[
            { type: "heading", label: t("shell.account.heading") },
            ...store.accounts.map((a) => ({
              label: (
                <span className="account-row">
                  <Avatar account={a} size={22} />
                  {a.username}
                  {a.qa && <span className="faint"> · QA</span>}
                </span>
              ),
              active: a.uuid === account?.uuid,
              onSelect: () => void store.switchAccount(a.uuid),
            })),
            { type: "separator" },
            { label: t("shell.account.add"), icon: <Icon name="plus" size={15} />, onSelect: () => void add() },
            { label: t("shell.account.manage"), icon: <Icon name="users" size={15} />, onSelect: onManage },
          ]}
        />
      </Popover>
    </>
  );
}
