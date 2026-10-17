import { lazy, Suspense, useEffect, useRef, useState, type ComponentType } from "react";
import { api, errorText, releaseLabel, type Job } from "../../lib/api";
import { bytes } from "../../lib/format";
import { sound } from "../../lib/sound";
import { jobActive, useStoreSelect } from "../../lib/store";
import { IconButton, Progress, Spinner } from "../../ui/controls";
import { Icon, type IconName } from "../../ui/Icon";
import { FlintMark } from "../../ui/Logo";
import { PerfArea } from "../../lib/perf";
import { Menu, Popover, Tooltip, useToast } from "../../ui/overlays";
import { Avatar } from "../onboarding/steps";
import { Home } from "../home/Home";
import { WindowChrome } from "./WindowChrome";
import { UpdateButton } from "./UpdateButton";
import { startUpdateChecks } from "../../lib/updater";

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
/** Fetched one after another once start-up has settled, in the order people usually open them. */
const PRELOAD = [ProfilesPage, LibraryPage, SettingsPage, SkinsPage, StorePage];

export type Page = "play" | "library" | "skins" | "profiles" | "store" | "settings";
/** `param` carries page-specific targets: a settings section, a profile id, or `profileId:tab`. */
export type Navigate = (page: Page, param?: string) => void;

const NAV: { id: Page; label: string; icon: IconName }[] = [
  { id: "play", label: "Play", icon: "play" },
  { id: "library", label: "Library", icon: "library" },
  { id: "skins", label: "Skins", icon: "shirt" },
  { id: "profiles", label: "Profiles", icon: "layers" },
  { id: "store", label: "Store", icon: "bag" },
  { id: "settings", label: "Settings", icon: "gear" },
];

export function Shell() {
  const info = useStoreSelect((s) => s.info);
  const [qaPage, qaParam] = (info.qaStartPage ?? "").split(/:(.*)/s);
  const [page, setPage] = useState<Page>(NAV.some((n) => n.id === qaPage) ? (qaPage as Page) : "play");
  const [param, setParam] = useState<string | undefined>(qaParam || undefined);
  const [visit, setVisit] = useState(0);

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
      if (index >= 0 && index < NAV.length) {
        e.preventDefault();
        navigate(NAV[index].id);
      }
      if (e.key === ",") {
        e.preventDefault();
        navigate("settings");
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  // Quiet update checks: a few seconds after start-up, then every few hours (never blocks the launcher).
  useEffect(() => startUpdateChecks(), []);

  useEffect(() => {
    let cancelled = false;
    const t = window.setTimeout(async () => {
      for (const chunk of PRELOAD) {
        if (cancelled) return;
        // A failed fetch is retried by lazy() when the page is opened.
        await chunk.preload().catch(() => {});
      }
    }, 1500);
    return () => {
      cancelled = true;
      window.clearTimeout(t);
    };
  }, []);

  return (
    <div className={`shell page-${page} platform-${info.platform}`}>
      <nav className="rail" aria-label="Main">
        <span className="rail-logo" title="Flint Client">
          <FlintMark size={30} />
        </span>
        <ul>
          {NAV.map((item, i) => (
            <li key={item.id}>
              <Tooltip label={`${item.label}  ·  ${info.platform === "macos" ? "⌘" : "Ctrl+"}${i + 1}`} side="right">
                <button
                  type="button"
                  className={`rail-item ${page === item.id ? "is-active" : ""}`}
                  aria-current={page === item.id ? "page" : undefined}
                  aria-label={item.label}
                  onClick={() => navigate(item.id)}
                >
                  <Icon name={item.icon} size={20} />
                </button>
              </Tooltip>
            </li>
          ))}
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
                {page === "profiles" && <ProfilesPage navigate={navigate} target={param} />}
                {page === "store" && <StorePage />}
                {page === "settings" && <SettingsPage initialSection={param} />}
              </PerfArea>
            </Suspense>
          </div>
        )}
      </main>
    </div>
  );
}

function jobProgress(job: Job): number | null {
  if (job.state !== "running") return job.state === "done" ? 1 : null;
  if (job.bytesTotal) return Math.min(1, job.bytesDone / job.bytesTotal);
  if (job.itemsTotal) return Math.min(1, job.itemsDone / job.itemsTotal);
  return null;
}

function JobsTray({ navigate }: { navigate: Navigate }) {
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
      if (!previous || previous === job.state) continue;
      if (job.state === "done") {
        const result = job.result as { installed?: string[]; mods?: number } | undefined;
        toast({
          tone: "success",
          title: job.kind === "modpack" ? "Modpack ready" : job.kind === "update" ? "Updated" : "Installed",
          body: job.kind === "modpack" ? `${job.title.replace(/^Installing /, "")} is now a profile.` : result?.installed?.join(", ") ?? job.title,
        });
      } else if (job.state === "failed") {
        toast({ tone: "danger", title: `${job.title} failed`, body: job.message ?? undefined });
      }
    }
  }, [visible, toast]);

  if (visible.length === 0) return null;
  const lead = active[0];
  return (
    <>
      <button ref={anchor} type="button" className={`jobs-chip ${active.length ? "is-active" : ""}`} aria-haspopup="dialog" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        {active.length ? <Spinner size={14} /> : <Icon name="download" size={15} />}
        <span>{active.length ? (active.length === 1 ? lead.stage || "Working" : `${active.length} tasks`) : "Downloads"}</span>
      </button>
      <Popover anchor={anchor} open={open} onClose={() => setOpen(false)} placement="bottom-end" label="Downloads" className="jobs-pop">
        <header className="jobs-head">
          <strong>Downloads</strong>
          <span className="faint">Verified with Modrinth hashes</span>
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
                      <IconButton icon="x" size="sm" label="Cancel" onClick={() => void api.cancelJob(job.id).catch((e) => toast({ tone: "danger", title: "Couldn't cancel", body: errorText(e) }))} />
                    ) : (
                      <IconButton icon="x" size="sm" label="Dismiss" onClick={() => store.dismissJob(job.id)} />
                    )}
                  </div>
                  <span className={`job-stage ${job.state === "failed" ? "is-danger" : ""}`}>
                    {job.state === "failed" ? job.message : job.state === "cancelled" ? job.message ?? "Cancelled" : job.stage}
                    {job.state === "running" && job.bytesTotal ? ` · ${bytes(job.bytesDone)} of ${bytes(job.bytesTotal)}` : ""}
                  </span>
                  {jobActive(job) && <Progress value={progress} label={job.title} />}
                  {job.state === "done" && job.profileId && (
                    <button
                      type="button"
                      className="job-link"
                      onClick={() => {
                        setOpen(false);
                        navigate("profiles", `${job.profileId}:content`);
                      }}
                    >
                      Open profile
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
  const store = useStoreSelect((s) => ({ accounts: s.accounts, activeAccount: s.activeAccount, reloadAccounts: s.reloadAccounts, switchAccount: s.switchAccount }));
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [adding, setAdding] = useState(false);
  const anchor = useRef<HTMLButtonElement>(null);
  const account = store.activeAccount;

  const add = async () => {
    setAdding(true);
    try {
      const added = await api.signIn();
      await store.reloadAccounts();
      toast({ tone: "success", title: `Signed in as ${added.username}` });
    } catch (e) {
      toast({ tone: "danger", title: "Sign-in didn't finish", body: errorText(e) });
    } finally {
      setAdding(false);
    }
  };

  return (
    <>
      <button ref={anchor} type="button" className="account-chip" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        {adding ? <Spinner size={16} /> : <Avatar account={account} size={26} />}
        <span className="account-name">{account?.username ?? "Not signed in"}</span>
        <Icon name="chevronDown" size={14} />
      </button>
      <Popover anchor={anchor} open={open} onClose={() => setOpen(false)} placement="bottom-end" label="Accounts" className="account-pop">
        <Menu
          onClose={() => setOpen(false)}
          items={[
            { type: "heading", label: "Minecraft accounts" },
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
            { label: "Add account", icon: <Icon name="plus" size={15} />, onSelect: () => void add() },
            { label: "Manage accounts", icon: <Icon name="users" size={15} />, onSelect: onManage },
          ]}
        />
      </Popover>
    </>
  );
}
