import { Suspense, lazy, memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import {
  api,
  errorText,
  type ContentKind,
  type ContentUpdate,
  type FolderKind,
  type InstalledItem,
  type Job,
  type LogFile,
  type Profile,
  type ProfileContent,
  type ProfileFiles,
  type UpdateCheck,
  type UpdateResult,
} from "../../lib/api";
import { cachedCheck, currentUpdates, eligible, forgetCheck, runCheck, updateHint, withoutApplied } from "../../lib/contentUpdates";
import { bytes, relativeTime, shortDate } from "../../lib/format";
import { useT, type MessageKey } from "../../lib/i18n";
import { removableWhileRunning } from "../../lib/livePacks";
import { sound } from "../../lib/sound";
import { countEntries, crashDescription, filterLines, parseLog, type LevelFilter } from "../../lib/logLines";
import { UNKNOWN_LIMITS, effectiveMemory, gbLabel, limitsFrom, type MemoryLimits } from "../../lib/memory";
import { useSettings } from "../../lib/settings";
import { isBusy, jobActive, stateOf, useStoreSelect } from "../../lib/store";
import { loaderLabel, versionMeta } from "../../lib/versions";
import { Badge, Button, Checkbox, EmptyState, IconButton, Segmented, Spinner, Switch, Tabs } from "../../ui/controls";
import { FlintMark } from "../../ui/FlintMark";
import { Icon } from "../../ui/Icon";
import { MemorySlider } from "../../ui/MemorySlider";
import { Dropdown, Menu, Modal, Popover, Tooltip, useToast } from "../../ui/overlays";
import { InstallDialog, PERFORMANCE_PRESET, ProjectIcon, type InstallTarget } from "../library/InstallDialog";
import type { Navigate } from "../shell/Shell";
import { STATE_LABEL, type ProfileActions } from "./ProfilesPage";
import { UpdateDialog } from "./UpdateDialog";
import { usePinToggle } from "./usePinToggle";

const FlintAI = lazy(() => import("../library/FlintAI"));

export type WorkspaceTab = "content" | "files" | "worlds" | "logs";
type Filter = "all" | "mod" | "resourcepack" | "shader" | "datapack";
type Sort = "name" | "recent" | "size";

/** What a row that isn't a mod is, shown before its file name. */
const KIND_NAME: Record<Exclude<ContentKind, "mod">, MessageKey> = {
  resourcepack: "workspace.kind.resourcepack",
  shader: "workspace.kind.shader",
  datapack: "workspace.kind.datapack",
};
const SOURCE: Partial<Record<string, MessageKey>> = {
  user: "workspace.source.user",
  assistant: "workspace.source.assistant",
  dependency: "workspace.source.dependency",
  optional: "workspace.source.optional",
  manual: "workspace.source.manual",
  flint: "workspace.source.flint",
};
const FILTER_LABEL: Record<Filter, MessageKey> = {
  all: "workspace.filter.all",
  mod: "workspace.filter.mod",
  resourcepack: "workspace.filter.resourcepack",
  shader: "workspace.filter.shader",
  datapack: "workspace.filter.datapack",
};
/** The empty list's title, by filter (a search that finds nothing has its own). */
const EMPTY_TITLE: Record<Filter, MessageKey> = {
  all: "workspace.empty.all",
  mod: "workspace.empty.mod",
  resourcepack: "workspace.empty.resourcepack",
  shader: "workspace.empty.shader",
  datapack: "workspace.empty.datapack",
};
const key = (i: { kind: string; fileName: string; world?: string | null }) => `${i.kind}/${i.world ?? "-"}/${i.fileName}`;
/** A relative time inside a sentence ("checked just now"): only the first letter is lowered, so a German noun stays capitalised. */
const lowerFirst = (text: string, locale: string) => text.charAt(0).toLocaleLowerCase(locale) + text.slice(1);

export function Workspace({
  profile,
  tab,
  onTab,
  onBack,
  navigate,
  actions,
}: {
  profile: Profile;
  tab: WorkspaceTab;
  onTab: (t: WorkspaceTab) => void;
  onBack: () => void;
  navigate: Navigate;
  actions: ProfileActions;
}) {
  const store = useStoreSelect((st) => ({
    live: st.states[profile.id],
    selectedId: st.selectedId,
    selectProfile: st.selectProfile,
    activeAccount: st.activeAccount,
  }));
  const t = useT();
  const toast = useToast();
  const pin = usePinToggle();
  const [menu, setMenu] = useState(false);
  const anchor = useRef<HTMLButtonElement>(null);
  const s = store.live ?? stateOf({}, profile);
  const paused = s.state === "uninstalled" && !!s.message?.startsWith("Installation paused");
  // Checks and downloads can be paused; the install step after them is short and always finishes.
  const pausable = s.state === "checking" || s.state === "downloading";
  const selected = store.selectedId === profile.id;
  const meta = versionMeta(profile.gameVersion);

  const play = async () => {
    try {
      if (!selected) await store.selectProfile(profile.id);
      if (s.state === "running") return void (await api.stop(profile.id));
      if (pausable) return void api.cancelInstall(profile.id);
      if (s.state === "ready") {
        sound.play("launch");
        await api.launch(profile.id);
      } else if (!isBusy(s)) {
        await api.installProfile(profile.id, s.state === "error" && profile.installed);
      }
    } catch (e) {
      const message = errorText(e);
      if (message !== "Installation paused.") toast({ tone: "danger", title: t("workspace.toast.failed"), body: message });
    }
  };

  return (
    <div className="workspace">
      <header className="ws-hero">
        <img className="ws-hero-art" src={meta.wallpaper} alt="" />
        <div className="ws-hero-shade" />
        <div className="ws-hero-content">
          <button type="button" className="ws-back" onClick={onBack}>
            <Icon name="chevronLeft" size={16} /> {t("workspace.back")}
          </button>
          <div className="ws-hero-row">
            <div className="ws-hero-text">
              <h1 title={profile.name}>{profile.name}</h1>
              <p>
                Minecraft {profile.gameVersion} · {loaderLabel(profile.loader)}
                {profile.loaderVersion ? ` ${profile.loaderVersion}` : ""}
                {profile.modpack ? ` · ${profile.modpack.title} ${profile.modpack.versionNumber ?? ""}` : ""}
              </p>
              <span className="ws-hero-badges">
                {selected ? <Badge tone="accent">{t("workspace.hero.selected")}</Badge> : null}
                <span className={`state-pill state-${s.state}`}>
                  {isBusy(s) && <Spinner size={10} />}
                  {paused ? t("workspace.hero.paused") : STATE_LABEL[s.state]}
                  {s.state === "downloading" && s.progress != null ? ` ${t("workspace.hero.progress", { percent: Math.round(s.progress * 100) })}` : ""}
                </span>
                <ProfileMemory key={profile.id} profile={profile} navigate={navigate} />
              </span>
            </div>
            <div className="ws-hero-actions">
              {!selected && (
                <Button variant="secondary" onClick={() => void store.selectProfile(profile.id)}>
                  {t("workspace.hero.select")}
                </Button>
              )}
              <Button
                variant={s.state === "running" ? "secondary" : "primary"}
                size="lg"
                icon={s.state === "running" ? "stop" : s.state === "ready" ? "play" : pausable ? "pause" : isBusy(s) ? undefined : "download"}
                loading={s.state === "launching" || s.state === "installing"}
                disabled={!store.activeAccount && s.state === "ready"}
                onClick={() => void play()}
              >
                {t(playLabel(s.state, pausable, paused))}
              </Button>
              <IconButton ref={anchor} icon="more" label={t("workspace.menu.label")} variant="solid" aria-haspopup="menu" aria-expanded={menu} onClick={() => setMenu((m) => !m)} />
              <Popover anchor={anchor} open={menu} onClose={() => setMenu(false)} placement="bottom-end" label={t("workspace.menu.label")}>
                <Menu
                  onClose={() => setMenu(false)}
                  items={[
                    { label: t("workspace.menu.edit"), hint: t("workspace.menu.editHint"), icon: <Icon name="edit" size={15} />, onSelect: () => actions.rename(profile) },
                    {
                      label: t(profile.pinnedAt ? "workspace.menu.unpin" : "workspace.menu.pin"),
                      icon: <Icon name="star" size={15} />,
                      onSelect: () => pin(profile),
                    },
                    { label: t("workspace.menu.duplicate"), icon: <Icon name="copy" size={15} />, onSelect: () => actions.duplicate(profile) },
                    { label: t("workspace.menu.export"), hint: ".flintpack", icon: <Icon name="upload" size={15} />, onSelect: () => actions.export(profile) },
                    { label: t("workspace.menu.openFolder"), icon: <Icon name="folder" size={15} />, onSelect: () => void api.revealFolder("profile", profile.id) },
                    ...(profile.installed
                      ? [
                          {
                            label: t("workspace.menu.repair"),
                            icon: <Icon name="wrench" size={15} />,
                            onSelect: () => void api.installProfile(profile.id, true).catch((e) => toast({ tone: "danger", title: t("workspace.menu.repairFailed"), body: errorText(e) })),
                          },
                        ]
                      : []),
                    { type: "separator" as const },
                    { label: t("workspace.menu.delete"), icon: <Icon name="trash" size={15} />, danger: true, onSelect: () => actions.remove(profile) },
                  ]}
                />
              </Popover>
            </div>
          </div>
        </div>
      </header>
      {s.state === "error" && s.message && (
        <p className="ws-error" role="alert">
          <Icon name="alert" size={15} />
          {s.message}
          {tab !== "logs" && (
            <button type="button" className="launch-link" onClick={() => onTab("logs")}>
              {t("workspace.viewLogs")}
            </button>
          )}
        </p>
      )}

      <Tabs
        label={t("workspace.tabs.label")}
        value={tab}
        onChange={onTab}
        tabs={[
          { value: "content", label: t("workspace.tabs.content") },
          { value: "files", label: t("workspace.tabs.files") },
          { value: "worlds", label: t("workspace.tabs.worlds") },
          { value: "logs", label: t("workspace.tabs.logs") },
        ]}
      />
      <div className="ws-body">
        {tab === "content" && <ContentTabView profile={profile} navigate={navigate} />}
        {tab === "files" && <FilesTabView profile={profile} />}
        {tab === "worlds" && <WorldsTabView profile={profile} />}
        {tab === "logs" && <LogsTabView profile={profile} />}
      </div>
    </div>
  );
}

/** The big button's label: what pressing it does now, or what is under way. */
function playLabel(state: string, pausable: boolean, paused: boolean): MessageKey {
  if (state === "running") return "workspace.play.stop";
  if (state === "ready") return "workspace.play.play";
  if (state === "error") return "workspace.play.repair";
  if (state === "launching") return "workspace.play.starting";
  if (state === "installing") return "workspace.play.installing";
  if (pausable) return "workspace.play.pause";
  return paused ? "workspace.play.resume" : "workspace.play.install";
}

const gb = gbLabel;

// The tabs are memoized so the header's live progress (several updates a second while this profile
// downloads) doesn't re-render a long content list, file list or log underneath it.
const ContentTabView = memo(ContentTab);
const FilesTabView = memo(FilesTab);
const WorldsTabView = memo(WorldsTab);
const LogsTabView = memo(LogsTab);

/**
 * This profile's memory: the launcher-wide setting unless the profile has its own value. A chip in
 * the hero opens a small panel with a slider and a field to type a size in; slider moves are saved
 * once they rest, a typed size once it's set, and both apply from the next launch.
 */
function ProfileMemory({ profile, navigate }: { profile: Profile; navigate: Navigate }) {
  const { settings } = useSettings();
  const store = useStoreSelect((s) => ({ reloadProfiles: s.reloadProfiles }));
  const t = useT();
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [limits, setLimits] = useState<MemoryLimits | null>(null);
  const [draft, setDraft] = useState<number | null>(null);
  const anchor = useRef<HTMLButtonElement>(null);
  const pending = useRef<{ id: string; value: number | null; timer: number } | null>(null);
  const custom = profile.memoryMb ?? null;
  // What a launch really gets on this computer (data.rs launch_memory_mb), also for a value saved above it.
  const fallback = limits ? effectiveMemory(settings.memoryMb, limits) : settings.memoryMb;
  const value = draft ?? (custom != null && limits ? effectiveMemory(custom, limits) : custom) ?? fallback;
  const isCustom = draft != null || custom != null;

  useEffect(() => {
    if (limits) return;
    void api
      .systemInfo()
      .then((i) => setLimits(limitsFrom(i)))
      .catch(() => setLimits(UNKNOWN_LIMITS));
  }, [limits]);

  // Saves go through a ref so the debounce timer and the unmount flush always use the current store.
  const save = useRef<(id: string, memoryMb: number | null) => Promise<void>>(async () => {});
  useLayoutEffect(() => {
    save.current = async (id, memoryMb) => {
      try {
        await api.setProfileMemory(id, memoryMb);
        await store.reloadProfiles();
      } catch (e) {
        toast({ tone: "danger", title: t("workspace.memory.saveFailed"), body: errorText(e) });
      } finally {
        setDraft((d) => (d === memoryMb ? null : d));
      }
    };
  });

  const flush = useCallback(() => {
    const p = pending.current;
    if (!p) return;
    window.clearTimeout(p.timer);
    pending.current = null;
    void save.current(p.id, p.value);
  }, []);

  // A change still waiting for the slider to rest is saved, not dropped, when the page goes away.
  useEffect(() => flush, [flush]);

  const change = (mb: number | null) => {
    setDraft(mb);
    if (pending.current) window.clearTimeout(pending.current.timer);
    pending.current = { id: profile.id, value: mb, timer: window.setTimeout(flush, mb === null ? 0 : 350) };
  };

  return (
    <>
      <button ref={anchor} type="button" className={`ws-memory-chip ${isCustom ? "is-custom" : ""}`} aria-haspopup="dialog" aria-expanded={open} onClick={() => setOpen((o) => !o)} title={t("workspace.memory.label")}>
        <Icon name="gauge" size={13} />
        {t("workspace.memory.chip", { size: gb(value) })}
        <span className="faint">{t(isCustom ? "workspace.memory.custom" : "workspace.memory.default")}</span>
      </button>
      <Popover
        anchor={anchor}
        open={open}
        onClose={() => {
          flush();
          setOpen(false);
        }}
        placement="bottom-start"
        label={t("workspace.memory.label")}
        className="ws-memory"
      >
        <div className="ws-memory-head">
          <strong>{t("workspace.memory.title")}</strong>
          <span className="muted">{isCustom ? t("workspace.memory.customValue", { size: gb(value) }) : t("workspace.memory.defaultValue", { size: gb(fallback) })}</span>
        </div>
        {limits ? (
          <MemorySlider label={t("workspace.memory.label")} min={1024} max={limits.maxMb} step={512} value={effectiveMemory(value, limits)} onChange={(v) => change(v)} />
        ) : (
          <div className="ws-memory-wait">
            <Spinner size={14} />
          </div>
        )}
        {limits?.warnMb != null && value > limits.warnMb && <p className="settings-warn">{t("workspace.memory.warn")}</p>}
        <div className="ws-memory-foot">
          {isCustom ? (
            <Button size="sm" variant="ghost" icon="undo" onClick={() => change(null)}>
              {t("workspace.memory.useDefault", { size: gb(fallback) })}
            </Button>
          ) : (
            <button
              type="button"
              className="launch-link"
              onClick={() => {
                setOpen(false);
                navigate("settings", "java");
              }}
            >
              {t("workspace.memory.changeDefault")}
            </button>
          )}
        </div>
        <p className="faint ws-memory-note">{t(isCustom ? "workspace.memory.appliesNext" : "workspace.memory.sliderNote")}</p>
      </Popover>
    </>
  );
}

function ContentTab({ profile, navigate }: { profile: Profile; navigate: Navigate }) {
  // Re-renders for this profile's content changes and running state only, not every progress tick.
  const store = useStoreSelect((s) => ({ revision: s.contentRevision[profile.id] ?? 0, running: s.states[profile.id]?.state === "running" }));
  const { flintAssist } = useSettings().settings;
  const t = useT();
  const toast = useToast();
  const [data, setData] = useState<ProfileContent | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState<Filter>("all");
  const [sort, setSort] = useState<Sort>("name");
  const [query, setQuery] = useState("");
  const [picked, setPicked] = useState<Set<string>>(new Set());
  // The last update check, for the profile it was made for (the tab stays mounted when another profile opens).
  const [checked, setChecked] = useState<{ id: string; check: UpdateCheck } | null>(null);
  const [checking, setChecking] = useState(false);
  const [dialog, setDialog] = useState<{ only: string[] | null } | null>(null);
  // Rows updating on their own (their job, by row key) and rows whose update didn't go through.
  const [rowJobs, setRowJobs] = useState<Record<string, string>>({});
  const [rowErrors, setRowErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [removing, setRemoving] = useState<InstalledItem[] | null>(null);
  const [disabling, setDisabling] = useState<InstalledItem[] | null>(null);
  const [preset, setPreset] = useState<InstallTarget | null>(null);
  const [assisting, setAssisting] = useState(false);
  const revision = store.revision;
  const running = store.running || !!data?.running;

  const load = useCallback(async () => {
    try {
      setData(await api.content(profile.id));
      setError(null);
    } catch (e) {
      setError(errorText(e));
    }
  }, [profile.id]);

  useEffect(() => {
    void load();
  }, [load, revision]);

  useEffect(() => {
    setPicked(new Set());
  }, [revision, filter]);

  const all = useMemo(() => (data ? [...data.mods, ...data.resourcepacks, ...data.shaders, ...data.datapacks] : []), [data]);

  // Files Flint didn't install (a modpack's, or ones put in the folder by hand) are matched to Modrinth by their hash as soon
  // as the tab shows them, so they get their name and icon without waiting for "Check for updates". Once per set of such
  // files: what Modrinth doesn't know stays as it is, and the backend remembers every file it already looked up. An update
  // check identifies them as well, so this waits while one runs.
  const identifiedFor = useRef("");
  useEffect(() => {
    const unknown = all.filter((m) => m.source === "manual" && !m.identified && !m.folder);
    const signature = `${profile.id}:${unknown.map((m) => `${m.kind}/${m.fileName}/${m.size}`).sort().join("|")}`;
    if (!unknown.length || identifiedFor.current === signature || checking) return;
    identifiedFor.current = signature;
    void api
      .identifyContent(profile.id)
      // Still this profile and these files (the tab may have moved on meanwhile): show what was found.
      .then((result) => {
        if (identifiedFor.current === signature && result.identified) void load();
      })
      .catch(() => null);
  }, [all, profile.id, load, checking]);

  const check = checked?.id === profile.id ? checked.check : null;
  const checkUpdates = useCallback(
    async (manual: boolean) => {
      const id = profile.id;
      setChecking(true);
      try {
        const result = await runCheck(id, () => api.contentUpdates(id), { fresh: manual });
        setChecked({ id, check: result });
        // The check identifies hand-added files too: their names and icons show now.
        void load();
        if (manual) {
          const open = eligible(result.updates).length;
          const pinned = result.updates.filter((u) => u.pinned).length;
          const notes = [
            pinned ? t("workspace.check.pinnedNewer", { count: pinned }) : "",
            result.unmanaged.length ? t("workspace.check.unrecognised", { count: result.unmanaged.length }) : "",
          ].filter(Boolean);
          toast({ tone: open ? "neutral" : "success", title: open ? t("workspace.check.available", { count: open }) : t("workspace.check.upToDate"), body: notes.join(" ") || undefined });
        }
      } catch (e) {
        // Checks that run on their own stay quiet (offline, say); the list works without them.
        if (manual) toast({ tone: "danger", title: t("workspace.check.failed"), body: errorText(e) });
      } finally {
        setChecking(false);
      }
    },
    [profile.id, load, toast, t],
  );

  // Updates are looked for as soon as the tab shows a profile, in the background; a check under 30 minutes old is
  // reused. When the profile's content changed since (an install or an update finished), it's checked again.
  const lastLook = useRef<{ id: string; revision: number } | null>(null);
  useEffect(() => {
    const last = lastLook.current;
    lastLook.current = { id: profile.id, revision };
    if (last?.id !== profile.id) {
      setRowJobs({});
      setRowErrors({});
      setDialog(null);
    } else if (last.revision !== revision) {
      forgetCheck(profile.id);
    }
    const cached = cachedCheck(profile.id);
    if (cached) setChecked({ id: profile.id, check: cached });
    else void checkUpdates(false);
  }, [profile.id, revision, checkUpdates]);

  // Rows updating on their own: a finished job shows its outcome on the row.
  const rowJobStates = useStoreSelect((s) => {
    const out: Record<string, "active" | Job> = {};
    for (const [k, id] of Object.entries(rowJobs)) {
      const job = s.jobs.find((j) => j.id === id);
      out[k] = !job || jobActive(job) ? "active" : job;
    }
    return out;
  });
  useEffect(() => {
    const finished = Object.entries(rowJobStates).filter((e): e is [string, Job] => e[1] !== "active");
    if (!finished.length) return;
    setRowJobs((prev) => Object.fromEntries(Object.entries(prev).filter(([k]) => !finished.some(([f]) => f === k))));
    const errors: Record<string, string> = {};
    for (const [k, job] of finished) {
      const result = job.state === "done" ? (job.result as UpdateResult) : null;
      const failure = result?.failed.find((f) => key(f) === k);
      if (failure) errors[k] = failure.reason ?? t("workspace.row.updateFailedUnknown");
      else if (job.state !== "done") errors[k] = job.message ?? t("workspace.row.updateFailedUnknown");
      // Its update is gone at once, before the check that follows every finished job.
      if (result) setChecked((prev) => prev && { ...prev, check: { ...prev.check, updates: withoutApplied(prev.check.updates, result) } });
    }
    setRowErrors((prev) => ({ ...Object.fromEntries(Object.entries(prev).filter(([k]) => !finished.some(([f]) => f === k))), ...errors }));
  }, [rowJobStates, t]);

  const current = useMemo(() => currentUpdates(check?.updates ?? [], all), [check, all]);
  const eligibleUpdates = useMemo(() => eligible(current), [current]);
  const updateFor = useMemo(() => new Map(current.map((u) => [key(u), u])), [current]);
  const items = useMemo(() => {
    const q = query.trim().toLowerCase();
    const list = all.filter((i) => (filter === "all" || i.kind === filter) && (!q || i.title.toLowerCase().includes(q) || i.fileName.toLowerCase().includes(q)));
    return list.sort((a, b) =>
      sort === "size" ? b.size - a.size : sort === "recent" ? (b.installedAt ?? b.modified ?? 0) - (a.installedAt ?? a.modified ?? 0) : a.title.localeCompare(b.title),
    );
  }, [all, filter, query, sort]);
  const counts = useMemo(() => {
    const c: Record<Filter, number> = { all: all.length, mod: 0, resourcepack: 0, shader: 0, datapack: 0 };
    for (const i of all) c[i.kind]++;
    return c;
  }, [all]);
  const pickedItems = items.filter((i) => picked.has(key(i)));
  const pickedMods = pickedItems.filter((i) => i.kind === "mod" && !i.folder);
  const pickedUpdates = pickedItems.map((i) => updateFor.get(key(i))).filter((u): u is ContentUpdate => !!u);

  const guard = async (fn: () => Promise<unknown>, done?: string) => {
    setBusy(true);
    try {
      await fn();
      if (done) toast({ tone: "success", title: done });
      await load();
    } catch (e) {
      toast({ tone: "danger", title: t("workspace.toast.failed"), body: errorText(e) });
    } finally {
      setBusy(false);
    }
  };

  /** Enabled mods that require any of these items and aren't being changed themselves. */
  const dependentsOf = (list: InstalledItem[]) => {
    const ids = new Set(list.map((i) => i.projectId).filter((x): x is string => !!x));
    const names = new Set(list.map((i) => i.fileName));
    return all.filter((m) => m.kind === "mod" && m.enabled && !names.has(m.fileName) && m.requires.some((r) => ids.has(r)));
  };
  const setEnabled = (list: InstalledItem[], enabled: boolean) => {
    if (!enabled && dependentsOf(list).length) return setDisabling(list);
    void guard(() => api.setEnabled(profile.id, list.map((m) => m.fileName), enabled));
  };
  const toggle = (item: InstalledItem, enabled: boolean) => setEnabled([item], enabled);
  /** One row's update, as its own job: the row shows it working, then the new version or what went wrong. */
  const updateRow = async (u: ContentUpdate) => {
    const k = key(u);
    setRowErrors((prev) => Object.fromEntries(Object.entries(prev).filter(([e]) => e !== k)));
    try {
      const job = await api.updateContent(profile.id, [{ kind: u.kind, fileName: u.fileName, versionId: u.versionId, world: u.world ?? null }]);
      setRowJobs((prev) => ({ ...prev, [k]: job }));
    } catch (e) {
      setRowErrors((prev) => ({ ...prev, [k]: errorText(e) }));
    }
  };
  const setPinned = (item: InstalledItem, pinned: boolean) =>
    void guard(() => api.pinContent(profile.id, [{ kind: item.kind, fileName: item.fileName, world: item.world }], pinned), t(pinned ? "workspace.toast.versionPinned" : "workspace.toast.versionUnpinned", { name: item.title }));

  if (error) return <EmptyState icon="alert" title={t("workspace.content.readFailed")} body={error} action={<Button onClick={() => void load()}>{t("common.tryAgain")}</Button>} />;
  if (!data) return <div className="ws-loading"><Spinner /></div>;

  return (
    <section className="content-tab">
      <div className="ct-toolbar">
        <div className="ct-filters" role="tablist" aria-label={t("workspace.content.typeLabel")}>
          {(["all", "mod", "resourcepack", "shader", "datapack"] as Filter[]).map((f) => (
            <button key={f} type="button" role="tab" aria-selected={filter === f} className={`ct-filter ${filter === f ? "is-active" : ""}`} onClick={() => setFilter(f)}>
              {t(FILTER_LABEL[f])}
              <span className="tab-count">{counts[f]}</span>
            </button>
          ))}
        </div>
        <label className="lib-search ct-search">
          <Icon name="search" size={15} />
          <input type="search" placeholder={t("workspace.content.search")} value={query} onChange={(e) => setQuery(e.target.value)} aria-label={t("workspace.content.searchLabel")} />
        </label>
        <Dropdown
          label={t("workspace.sort.label")}
          value={sort}
          onChange={setSort}
          options={[
            { value: "name", label: t("workspace.sort.name") },
            { value: "recent", label: t("workspace.sort.recent") },
            { value: "size", label: t("workspace.sort.size") },
          ]}
        />
        <IconButton
          icon="refresh"
          className={`ct-check ${checking ? "is-checking" : ""}`}
          label={
            checking
              ? t("workspace.check.checking")
              : check
                ? t("workspace.check.runChecked", { when: lowerFirst(relativeTime(check.checkedAt), t.locale) })
                : t("workspace.check.run")
          }
          onClick={() => void checkUpdates(true)}
          disabled={checking}
        />
        {eligibleUpdates.length > 0 && (
          <Button className="ct-update-all" icon="arrowUp" onClick={() => setDialog({ only: null })}>
            {t("workspace.content.updateAll")} <span className="tab-count">{eligibleUpdates.length}</span>
          </Button>
        )}
        {/* The ways to add content stay together at the end of the bar, and move to a line of their own as one group. */}
        <div className="ct-toolbar-actions">
          {profile.loader === "fabric" && (
            <Button icon="gauge" onClick={() => setPreset(PERFORMANCE_PRESET)} disabled={running}>
              {t("workspace.content.performance")}
            </Button>
          )}
          {flintAssist && (
            <button type="button" className="lib-ai" aria-expanded={assisting} onClick={() => (sound.play(assisting ? "tap" : "open"), setAssisting((a) => !a))}>
              <FlintMark size={20} />
              {t("workspace.content.findWithAi")}
            </button>
          )}
          <Button variant="primary" icon="plus" onClick={() => navigate("library", `${profile.id}:${filter === "all" ? "mod" : filter}`)}>
            {t("workspace.content.add")}
          </Button>
        </div>
      </div>

      {flintAssist && assisting && (
        <Suspense fallback={null}>
          <FlintAI
            profile={profile}
            onClose={() => setAssisting(false)}
            onInspect={(hit, kind) => void api.openExternal(`https://modrinth.com/${kind}/${hit.slug}`)}
            onBrowse={() => navigate("library", `${profile.id}:${filter === "all" ? "mod" : filter}`)}
          />
        </Suspense>
      )}

      {running && (
        <p className="ct-note">
          <Icon name="lock" size={14} /> {t("workspace.content.runningLive")}
        </p>
      )}
      {profile.loader !== "fabric" && (filter === "mod" || filter === "shader") && (
        <p className="ct-note">
          <Icon name="info" size={14} /> {t("workspace.content.vanilla")}
        </p>
      )}
      {pickedItems.length > 0 && (
        <div className="ct-batch" role="toolbar" aria-label={t("workspace.batch.label")}>
          <strong>{t("workspace.batch.selected", { count: pickedItems.length })}</strong>
          {pickedMods.length > 0 && (
            <>
              <Button size="sm" onClick={() => setEnabled(pickedMods, true)} disabled={busy || running}>
                {t("workspace.batch.enable")}
              </Button>
              <Button size="sm" onClick={() => setEnabled(pickedMods, false)} disabled={busy || running}>
                {t("workspace.batch.disable")}
              </Button>
            </>
          )}
          {pickedUpdates.length > 0 && (
            <Button size="sm" icon="arrowUp" onClick={() => setDialog({ only: pickedUpdates.map(key) })} disabled={busy}>
              {t("workspace.batch.update", { count: pickedUpdates.length })}
            </Button>
          )}
          <Button size="sm" variant="danger" icon="trash" onClick={() => setRemoving(pickedItems)} disabled={busy || (running && !pickedItems.every((i) => removableWhileRunning(i.kind)))}>
            {t("common.remove")}
          </Button>
          <button type="button" className="ct-batch-clear" onClick={() => setPicked(new Set())}>
            {t("workspace.batch.clear")}
          </button>
        </div>
      )}

      {items.length === 0 ? (
        <EmptyState
          icon={query ? "search" : "puzzle"}
          title={t(query ? "workspace.empty.noMatches" : EMPTY_TITLE[filter])}
          body={query ? undefined : t("workspace.empty.body")}
          action={
            !query && (
              <Button variant="primary" icon="library" onClick={() => navigate("library", `${profile.id}:${filter === "all" ? "mod" : filter}`)}>
                {t("workspace.empty.browse")}
              </Button>
            )
          }
        />
      ) : (
        <div className="ct-table" role="table" aria-label={t("workspace.table.label")}>
          <div className="ct-row ct-head" role="row">
            <span role="columnheader">
              <Checkbox
                label={t("workspace.table.selectAll")}
                checked={pickedItems.length === items.length}
                onChange={(v) => setPicked(v ? new Set(items.map(key)) : new Set())}
              />
            </span>
            <span role="columnheader">{t("workspace.table.name")}</span>
            <span role="columnheader">{t("workspace.table.version")}</span>
            <span role="columnheader">{t("workspace.table.source")}</span>
            <span role="columnheader">{t("workspace.table.status")}</span>
            <span role="columnheader" className="sr-only">
              {t("workspace.table.actions")}
            </span>
          </div>
          {items.map((item) => (
            <ContentRow
              key={key(item)}
              item={item}
              picked={picked.has(key(item))}
              onPick={(v) =>
                setPicked((prev) => {
                  const next = new Set(prev);
                  if (v) next.add(key(item));
                  else next.delete(key(item));
                  return next;
                })
              }
              update={updateFor.get(key(item))}
              updateHint={updateFor.has(key(item)) ? updateHint(updateFor.get(key(item))!, profile.gameVersion, profile.loader) : ""}
              updating={!!rowJobs[key(item)]}
              updateError={rowErrors[key(item)]}
              locked={busy || running}
              // Packs can be removed while the game runs (it's told); mods need a restart.
              removeLocked={busy || (running && !removableWhileRunning(item.kind))}
              onToggle={(v) => void toggle(item, v)}
              onUpdate={(u) => void updateRow(u)}
              onChooseVersion={() => setDialog({ only: [key(item)] })}
              onPin={(v) => setPinned(item, v)}
              onRollback={() =>
                void guard(async () => {
                  await api.rollbackContent(profile.id, item.kind, item.projectId!, item.world);
                  forgetCheck(profile.id);
                  void checkUpdates(false);
                }, t("workspace.toast.rolledBack", { name: item.title }))
              }
              onRemove={() => setRemoving([item])}
              onReveal={() => void api.revealFolder(item.kind === "mod" ? "mods" : item.kind === "resourcepack" ? "resourcepacks" : item.kind === "shader" ? "shaderpacks" : "saves", profile.id)}
            />
          ))}
        </div>
      )}
      <p className="ct-foot faint">{t.rich("workspace.content.footer", { ending: <code>.disabled</code> })}</p>

      <Modal
        open={!!removing}
        onClose={() => setRemoving(null)}
        title={removing && removing.length === 1 ? t("workspace.remove.titleOne", { name: removing[0].title }) : t("workspace.remove.title", { count: removing?.length ?? 0 })}
        width={460}
        footer={
          <>
            <Button variant="ghost" onClick={() => setRemoving(null)}>
              {t("common.cancel")}
            </Button>
            <Button
              variant="danger"
              icon="trash"
              onClick={() => {
                const list = removing ?? [];
                setRemoving(null);
                void guard(
                  () => api.removeContent(profile.id, list.map((i) => ({ kind: i.kind, fileName: i.fileName, world: i.world }))),
                  list.length === 1 ? t("workspace.toast.removedOne", { name: list[0].title }) : t("workspace.toast.removed", { count: list.length }),
                );
              }}
            >
              {t("common.remove")}
            </Button>
          </>
        }
      >
        <p className="muted modal-copy">{t("workspace.remove.body")}</p>
        {removing && dependentsOf(removing).length > 0 && (
          <p className="install-warn">
            <Icon name="alert" size={15} />
            {dependentsOf(removing).length === 1
              ? t("workspace.remove.dependentOne", { name: dependentsOf(removing)[0].title })
              : t("workspace.remove.dependents", {
                  names: dependentsOf(removing)
                    .map((m) => m.title)
                    .join(", "),
                })}
          </p>
        )}
      </Modal>

      <InstallDialog target={preset} profile={profile} onClose={() => setPreset(null)} />

      <UpdateDialog
        open={!!dialog}
        profile={profile}
        updates={current}
        unmanaged={check?.unmanaged ?? []}
        only={dialog?.only ?? null}
        running={running}
        onClose={() => setDialog(null)}
        onChanged={() => {
          forgetCheck(profile.id);
          void load();
          void checkUpdates(false);
        }}
      />

      <Modal
        open={!!disabling}
        onClose={() => setDisabling(null)}
        title={t("workspace.disable.title")}
        width={480}
        footer={
          disabling && (
            <>
              <Button variant="ghost" onClick={() => setDisabling(null)}>
                {t("common.cancel")}
              </Button>
              <Button
                onClick={() => {
                  const list = disabling;
                  setDisabling(null);
                  void guard(() => api.setEnabled(profile.id, list.map((m) => m.fileName), false));
                }}
              >
                {t("workspace.disable.anyway")}
              </Button>
              <Button
                variant="primary"
                onClick={() => {
                  const list = [...disabling, ...dependentsOf(disabling)];
                  setDisabling(null);
                  void guard(() => api.setEnabled(profile.id, list.map((m) => m.fileName), false), t("workspace.toast.disabledMods", { count: list.length }));
                }}
              >
                {t("workspace.disable.together", { count: dependentsOf(disabling).length + disabling.length })}
              </Button>
            </>
          )
        }
      >
        {disabling && (
          <>
            <p className="muted modal-copy">
              {disabling.length === 1 ? t("workspace.disable.bodyOne", { name: disabling[0].title }) : t("workspace.disable.body", { count: disabling.length })}
            </p>
            <ul className="dep-list">
              {dependentsOf(disabling).map((m) => (
                <li key={m.fileName}>
                  <ProjectIcon src={m.iconUrl} fallback={m.localIcon} title={m.title} kind={m.kind} size={28} />
                  <span>
                    <strong>{m.title}</strong>
                    <span className="faint">{m.versionNumber || m.fileName}</span>
                  </span>
                </li>
              ))}
            </ul>
          </>
        )}
      </Modal>
    </section>
  );
}

function ContentRow({
  item,
  picked,
  onPick,
  update,
  updateHint,
  updating,
  updateError,
  locked,
  removeLocked,
  onToggle,
  onUpdate,
  onChooseVersion,
  onPin,
  onRollback,
  onRemove,
  onReveal,
}: {
  item: InstalledItem;
  picked: boolean;
  onPick: (v: boolean) => void;
  /** The update for this file; its own button shows only when it can go in and the item isn't pinned. */
  update?: ContentUpdate;
  updateHint: string;
  updating: boolean;
  updateError?: string;
  locked: boolean;
  /** Remove is unavailable (packs can go while the game runs, mods can't). */
  removeLocked: boolean;
  onToggle: (v: boolean) => void;
  onUpdate: (u: ContentUpdate) => void;
  onChooseVersion: () => void;
  onPin: (pinned: boolean) => void;
  onRollback: () => void;
  onRemove: () => void;
  onReveal: () => void;
}) {
  const t = useT();
  const [menu, setMenu] = useState(false);
  const anchor = useRef<HTMLButtonElement>(null);
  const isMod = item.kind === "mod" && !item.folder;
  const source = SOURCE[item.source];
  return (
    <div className={`ct-row ${item.enabled ? "" : "is-off"} ${item.missing ? "is-missing" : ""}`} role="row">
      <span role="cell">
        <Checkbox label={t("workspace.row.select", { name: item.title })} checked={picked} onChange={onPick} />
      </span>
      <span role="cell" className="ct-name">
        <ProjectIcon src={item.iconUrl} fallback={item.localIcon} title={item.title} kind={item.kind} size={36} />
        <span>
          <strong title={item.title}>{item.title}</strong>
          <span className="faint" title={item.fileName}>
            {item.kind !== "mod" ? `${t(KIND_NAME[item.kind])}${item.world ? ` · ${item.world}` : ""} · ` : ""}
            {item.fileName}
          </span>
        </span>
      </span>
      <span role="cell" className="ct-version">
        <span>{item.versionNumber || "—"}</span>
        {item.pinned && (
          <span className="ct-pinned" title={t("workspace.row.pinnedHint")}>
            <Icon name="pin" size={11} />
            {t("workspace.row.pinned")}
          </span>
        )}
        {updateError && (
          <span className="ct-update-error" role="status" title={updateError}>
            {t("workspace.row.updateFailed", { reason: updateError })}
          </span>
        )}
      </span>
      <span role="cell" className="faint ct-source">
        {source ? t(source) : item.source}
        {item.source === "manual" && item.identified && <span title={t("workspace.row.matchedHint")}> · {t("workspace.row.onModrinth")}</span>}
      </span>
      <span role="cell" className="ct-status">
        {item.missing ? (
          <Badge tone="danger">{t("workspace.row.fileMissing")}</Badge>
        ) : isMod ? (
          <Switch checked={item.enabled} onChange={onToggle} label={t(item.enabled ? "workspace.row.disable" : "workspace.row.enable", { name: item.title })} disabled={locked} />
        ) : item.kind === "resourcepack" || item.kind === "shader" ? (
          <span className="faint">{t("workspace.row.chooseInGame")}</span>
        ) : (
          <span className="faint">{t("workspace.row.activeInWorld")}</span>
        )}
      </span>
      <span role="cell" className="ct-actions">
        {updating ? (
          <span className="ct-update-btn is-busy" role="status" aria-label={t("workspace.row.updating", { name: item.title })}>
            <Spinner size={14} />
          </span>
        ) : (
          update &&
          !item.pinned &&
          !update.blocked && (
            <Tooltip label={updateHint} side="left">
              <button
                type="button"
                className="ct-update-btn"
                aria-label={t("workspace.row.updateNameTo", { name: item.title, version: update.versionNumber })}
                disabled={locked}
                onClick={() => {
                  sound.play("tap");
                  onUpdate(update);
                }}
              >
                <Icon name="arrowUp" size={15} />
              </button>
            </Tooltip>
          )
        )}
        <IconButton ref={anchor} icon="more" size="sm" label={t("workspace.row.actions", { name: item.title })} aria-haspopup="menu" aria-expanded={menu} onClick={() => setMenu((m) => !m)} />
        <Popover anchor={anchor} open={menu} onClose={() => setMenu(false)} placement="bottom-end" label={t("workspace.row.menuLabel")}>
          <Menu
            onClose={() => setMenu(false)}
            items={[
              ...(update
                ? [
                    {
                      label: t("workspace.row.updateTo", { version: update.versionNumber }),
                      hint: update.blocked ? t("workspace.row.blocked") : item.pinned ? t("workspace.row.pinned") : undefined,
                      icon: <Icon name="arrowUp" size={15} />,
                      disabled: locked || updating || !!update.blocked,
                      onSelect: () => onUpdate(update),
                    },
                  ]
                : []),
              ...(update && update.alternatives.length > 0 ? [{ label: t("workspace.row.chooseVersion"), icon: <Icon name="list" size={15} />, disabled: updating, onSelect: onChooseVersion }] : []),
              ...(item.folder
                ? []
                : [
                    {
                      label: t(item.pinned ? "workspace.row.unpin" : "workspace.row.pin"),
                      hint: item.pinned ? undefined : t("workspace.row.pinHint"),
                      icon: <Icon name="pin" size={15} />,
                      onSelect: () => onPin(!item.pinned),
                    },
                  ]),
              ...(item.canRollback
                ? [
                    {
                      label: item.previousVersion != null ? t("workspace.row.rollBackTo", { version: item.previousVersion }) : t("workspace.row.rollBackPrevious"),
                      icon: <Icon name="undo" size={15} />,
                      disabled: locked,
                      onSelect: onRollback,
                    },
                  ]
                : []),
              { label: t("common.showInFolder"), icon: <Icon name="folder" size={15} />, onSelect: onReveal },
              { type: "separator" as const },
              { label: t("common.remove"), icon: <Icon name="trash" size={15} />, danger: true, disabled: removeLocked, onSelect: onRemove },
            ]}
          />
        </Popover>
      </span>
    </div>
  );
}

const FOLDERS: { id: FolderKind; label: MessageKey; note: MessageKey }[] = [
  { id: "mods", label: "workspace.files.folder.mods", note: "workspace.files.note.mods" },
  { id: "resourcepacks", label: "workspace.files.folder.resourcepacks", note: "workspace.files.note.resourcepacks" },
  { id: "shaderpacks", label: "workspace.files.folder.shaderpacks", note: "workspace.files.note.shaderpacks" },
  { id: "saves", label: "workspace.files.folder.saves", note: "workspace.files.note.saves" },
  { id: "screenshots", label: "workspace.files.folder.screenshots", note: "workspace.files.note.screenshots" },
  { id: "config", label: "workspace.files.folder.config", note: "workspace.files.note.config" },
  { id: "logs", label: "workspace.files.folder.logs", note: "workspace.files.note.logs" },
];

function FilesTab({ profile }: { profile: Profile }) {
  const t = useT();
  const [files, setFiles] = useState<ProfileFiles | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    void api
      .profileFiles(profile.id)
      .then(setFiles)
      .catch((e) => setError(errorText(e)));
  }, [profile.id]);
  if (error) return <EmptyState icon="alert" title={t("workspace.files.readFailed")} body={error} />;
  if (!files) return <div className="ws-loading"><Spinner /></div>;
  return (
    <section className="files-tab">
      <div className="files-path">
        <span className="mono" title={files.path}>
          {files.path}
        </span>
        <span className="faint">{t("workspace.files.total", { size: bytes(files.total) })}</span>
        <Button size="sm" icon="folder" onClick={() => void api.revealFolder("profile", profile.id)}>
          {t("workspace.files.open")}
        </Button>
      </div>
      <div className="folder-grid">
        {FOLDERS.map((f) => {
          const info = files.folders.find((x) => x.id === f.id);
          return (
            <button key={f.id} type="button" className="folder-card" onClick={() => void api.revealFolder(f.id, profile.id)} disabled={!info?.exists}>
              <Icon name="folder" size={20} />
              <span>
                <strong>{t(f.label)}</strong>
                <span className="faint">{info?.exists ? t("workspace.files.items", { count: info.count, size: bytes(info.size) }) : t("workspace.files.notCreated")}</span>
                <span className="faint">{t(f.note)}</span>
              </span>
            </button>
          );
        })}
      </div>
    </section>
  );
}

function WorldsTab({ profile }: { profile: Profile }) {
  const t = useT();
  const [worlds, setWorlds] = useState<ProfileContent["worlds"] | null>(null);
  useEffect(() => {
    void api
      .worlds(profile.id)
      .then(setWorlds)
      .catch(() => setWorlds([]));
  }, [profile.id]);
  if (!worlds) return <div className="ws-loading"><Spinner /></div>;
  if (worlds.length === 0) return <EmptyState icon="globe" title={t("workspace.worlds.none")} body={t("workspace.worlds.noneBody")} />;
  return (
    <section className="worlds-tab">
      <div className="world-grid">
        {worlds.map((w) => (
          <article key={w.id} className="world-card">
            {w.icon ? <img src={w.icon} alt="" /> : <span className="world-icon"><Icon name="globe" size={22} /></span>}
            <span>
              <strong title={w.name}>{w.name}</strong>
              <span className="faint">{w.lastPlayed ? t("workspace.worlds.played", { when: relativeTime(w.lastPlayed) }) : ""}</span>
              <span className="faint">{w.datapacks ? t("workspace.worlds.datapacks", { count: w.datapacks }) : t("workspace.worlds.noDatapacks")}</span>
            </span>
          </article>
        ))}
      </div>
      <Button icon="folder" onClick={() => void api.revealFolder("saves", profile.id)}>
        {t("workspace.worlds.openFolder")}
      </Button>
    </section>
  );
}

const LOG_ROW = 22;

function LogsTab({ profile }: { profile: Profile }) {
  const [logs, setLogs] = useState<LogFile[] | null>(null);
  const [open, setOpen] = useState<LogFile | null>(null);
  const [text, setText] = useState<string | null>(null);
  const [filter, setFilter] = useState("");
  const [level, setLevel] = useState<LevelFilter>("all");
  const [revision, setRevision] = useState(0);
  const [view, setView] = useState({ top: 0, height: 480 });
  const scroller = useRef<HTMLDivElement>(null);
  const t = useT();
  const toast = useToast();

  useEffect(() => {
    void api
      .profileLogs(profile.id)
      .then((l) => {
        setLogs(l);
        setOpen((current) => {
          if (current && l.some((x) => x.name === current.name && x.folder === current.folder)) return current;
          const launchLog = l.find((x) => x.name === "flint-launch.log");
          const crash = l.find((x) => x.kind === "crash" && (!launchLog || (x.modified ?? 0) >= (launchLog.modified ?? 0)));
          return crash ?? launchLog ?? l[0] ?? null;
        });
      })
      .catch(() => setLogs([]));
  }, [profile.id, revision]);

  useEffect(() => {
    setText(null);
    if (!open) return;
    void api
      .readLog(profile.id, open.folder, open.name)
      .then(setText)
      .catch((e) => setText(errorText(e)));
  }, [open, profile.id, revision]);

  const parsed = useMemo(() => (text === null ? null : parseLog(text)), [text]);
  const shown = useMemo(() => (parsed ? filterLines(parsed, level, filter) : null), [parsed, level, filter]);
  const counts = useMemo(() => (parsed ? countEntries(parsed) : { warn: 0, error: 0 }), [parsed]);
  const description = open?.kind === "crash" && text ? crashDescription(text) : null;
  // Logs with timestamps show the time beside each line; others (crash reports) show line numbers instead.
  const timed = useMemo(() => !!parsed?.some((l) => l.time), [parsed]);

  // Newest lines are what matter after a failure: start at the bottom whenever the view changes.
  useEffect(() => {
    const el = scroller.current;
    if (el && shown) el.scrollTop = el.scrollHeight;
  }, [shown]);

  useEffect(() => {
    const el = scroller.current;
    if (!el) return;
    const measure = () => setView((v) => ({ ...v, height: el.clientHeight }));
    const observer = new ResizeObserver(measure);
    observer.observe(el);
    return () => observer.disconnect();
  }, [logs]);

  const onScroll = () => {
    const el = scroller.current;
    if (el) setView({ top: el.scrollTop, height: el.clientHeight });
  };

  const jumpToFirstError = () => {
    const index = shown?.findIndex((l) => l.level === "error") ?? -1;
    if (index >= 0 && scroller.current) scroller.current.scrollTop = Math.max(0, index * LOG_ROW - LOG_ROW * 2);
  };

  if (!logs) return <div className="ws-loading"><Spinner /></div>;
  if (logs.length === 0) return <EmptyState icon="terminal" title={t("workspace.logs.none")} body={t("workspace.logs.noneBody")} />;

  // Only the rows in view (plus a margin) are rendered, so large logs stay smooth.
  const total = shown?.length ?? 0;
  const first = Math.max(0, Math.floor(view.top / LOG_ROW) - 30);
  const last = Math.min(total, Math.ceil((view.top + view.height) / LOG_ROW) + 30);
  const visible = shown ? shown.slice(first, last) : [];

  return (
    <section className="logs-tab">
      <aside className="log-list" aria-label={t("workspace.logs.files")}>
        <header className="log-list-head">
          <span className="eyebrow">{t("workspace.logs.files")}</span>
          <span className="faint">{logs.length}</span>
        </header>
        <div className="log-list-items scroll">
        {logs.map((l) => (
          <button key={`${l.folder}/${l.name}`} type="button" className={`log-item ${l.kind === "crash" ? "is-crash" : ""} ${open?.name === l.name && open.folder === l.folder ? "is-active" : ""}`} onClick={() => setOpen(l)}>
            <Icon name={l.kind === "crash" ? "alert" : "file"} size={15} />
            <span>
              <strong>{l.name}</strong>
              <span className="faint">
                {l.kind === "crash" ? `${t("workspace.logs.crashReport")} · ` : ""}
                {l.modified ? shortDate(l.modified) : ""} · {bytes(l.size)}
              </span>
            </span>
          </button>
        ))}
        </div>
      </aside>
      <div className="log-view">
        <header className="log-head">
          <span className="log-title">
            <strong>{open?.name}</strong>
            <span className="faint">
              {t(open?.kind === "crash" ? "workspace.logs.crashReport" : open?.name === "flint-launch.log" ? "workspace.logs.launchLog" : "workspace.logs.minecraftLog")} · {t("workspace.logs.tokensHidden")}
            </span>
          </span>
          <span className="log-counts">
            {open?.kind === "crash" ? (
              <span className="log-count is-error">
                <Icon name="alert" size={13} /> {t("workspace.logs.crashReport")}
              </span>
            ) : counts.error > 0 && (
              <button type="button" className="log-count is-error" onClick={jumpToFirstError}>
                <Icon name="alert" size={13} /> {t("workspace.logs.errors", { count: counts.error })}
              </button>
            )}
            {counts.warn > 0 && <span className="log-count is-warn">{t("workspace.logs.warnings", { count: counts.warn })}</span>}
          </span>
        </header>
        {description && (
          <p className="log-crash" role="note">
            <Icon name="alert" size={15} />
            <span>
              <strong>{t("workspace.logs.crashed")}</strong> {description}
            </span>
          </p>
        )}
        <div className="log-tools">
          <label className="lib-search">
            <Icon name="search" size={15} />
            <input type="search" placeholder={t("workspace.logs.filter")} value={filter} onChange={(e) => setFilter(e.target.value)} aria-label={t("workspace.logs.filterLabel")} />
          </label>
          <Segmented
            label={t("workspace.logs.show")}
            value={level}
            onChange={setLevel}
            options={[
              { value: "all", label: t("workspace.logs.level.all") },
              { value: "warn", label: t("workspace.logs.level.warn") },
              { value: "error", label: t("workspace.logs.level.error") },
            ]}
          />
          <span className="log-tools-gap" />
          <IconButton icon="refresh" label={t("workspace.logs.reload")} size="sm" onClick={() => setRevision((r) => r + 1)} />
          <IconButton
            icon="copy"
            label={t(filter || level !== "all" ? "workspace.logs.copyShown" : "workspace.logs.copyAll")}
            size="sm"
            disabled={!shown?.length}
            onClick={() => {
              const lines = (shown ?? []).map((l) => (l.time ? `[${l.time}] ${l.text}` : l.text)).join("\n");
              void navigator.clipboard
                .writeText(lines)
                .then(() => toast({ tone: "success", title: t("workspace.logs.copied"), body: t("workspace.logs.copiedLines", { count: shown?.length ?? 0 }) }));
            }}
          />
          <IconButton icon="folder" label={t("workspace.logs.openFolder")} size="sm" onClick={() => void api.revealFolder(open?.folder === "crash-reports" ? "profile" : "logs", profile.id)} />
        </div>
        <div ref={scroller} className="log-scroll scroll" onScroll={onScroll} role="log" aria-label={open?.name}>
          {shown === null ? (
            <p className="log-empty">{t("common.loading")}</p>
          ) : total === 0 ? (
            <p className="log-empty">{t(filter || level !== "all" ? "workspace.logs.noMatches" : "workspace.logs.emptyFile")}</p>
          ) : (
            <div className={`log-rows ${timed ? "is-timed" : "is-numbered"}`} style={{ height: total * LOG_ROW }}>
              <div style={{ transform: `translateY(${first * LOG_ROW}px)` }}>
                {visible.map((l) => (
                  <div key={l.n} className={`log-line lv-${l.level} ${l.stack ? "is-stack" : ""}`}>
                    <span className="log-time">{timed ? l.time ?? "" : l.n}</span>
                    <span className="log-msg">{l.text || " "}</span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>
    </section>
  );
}
