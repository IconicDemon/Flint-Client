import { Suspense, lazy, memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import {
  api,
  errorText,
  type ContentKind,
  type ContentUpdate,
  type FolderKind,
  type InstalledItem,
  type LogFile,
  type Profile,
  type ProfileContent,
  type ProfileFiles,
} from "../../lib/api";
import { bytes, relativeTime, shortDate } from "../../lib/format";
import { sound } from "../../lib/sound";
import { countEntries, crashDescription, filterLines, parseLog, type LevelFilter } from "../../lib/logLines";
import { useSettings } from "../../lib/settings";
import { isBusy, stateOf, useStoreSelect } from "../../lib/store";
import { loaderLabel, versionMeta } from "../../lib/versions";
import { Badge, Button, Checkbox, EmptyState, IconButton, Segmented, Slider, Spinner, Switch, Tabs } from "../../ui/controls";
import { FlintMark } from "../../ui/FlintMark";
import { Icon } from "../../ui/Icon";
import { Dropdown, Menu, Modal, Popover, useToast } from "../../ui/overlays";
import { InstallDialog, PERFORMANCE_PRESET, ProjectIcon, type InstallTarget } from "../library/InstallDialog";
import type { Navigate } from "../shell/Shell";
import { STATE_LABEL, type ProfileActions } from "./ProfilesPage";

const FlintAI = lazy(() => import("../library/FlintAI"));

export type WorkspaceTab = "content" | "files" | "worlds" | "logs";
type Filter = "all" | "mod" | "resourcepack" | "shader" | "datapack";
type Sort = "name" | "recent" | "size";

const KIND_NAME: Record<ContentKind, string> = { mod: "Mod", resourcepack: "Resource pack", shader: "Shader", datapack: "Data pack" };
const SOURCE: Record<string, string> = { user: "Added by you", assistant: "Suggested", dependency: "Dependency", optional: "Optional", manual: "Added manually", flint: "Flint performance" };
const key = (i: { kind: string; fileName: string; world?: string | null }) => `${i.kind}/${i.world ?? "-"}/${i.fileName}`;

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
  const toast = useToast();
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
      if (message !== "Installation paused.") toast({ tone: "danger", title: "That didn't work", body: message });
    }
  };

  return (
    <div className="workspace">
      <header className="ws-hero">
        <img className="ws-hero-art" src={meta.wallpaper} alt="" />
        <div className="ws-hero-shade" />
        <div className="ws-hero-content">
          <button type="button" className="ws-back" onClick={onBack}>
            <Icon name="chevronLeft" size={16} /> Profiles
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
                {selected ? <Badge tone="accent">Selected for Play</Badge> : null}
                <span className={`state-pill state-${s.state}`}>
                  {isBusy(s) && <Spinner size={10} />}
                  {paused ? "Paused" : STATE_LABEL[s.state]}
                  {s.state === "downloading" && s.progress != null ? ` ${Math.round(s.progress * 100)}%` : ""}
                </span>
                <ProfileMemory key={profile.id} profile={profile} navigate={navigate} />
              </span>
            </div>
            <div className="ws-hero-actions">
              {!selected && (
                <Button variant="secondary" onClick={() => void store.selectProfile(profile.id)}>
                  Select
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
                {s.state === "running" ? "Stop" : s.state === "ready" ? "Play" : s.state === "error" ? "Repair" : s.state === "launching" ? "Starting" : s.state === "installing" ? "Installing" : pausable ? "Pause" : paused ? "Resume" : "Install"}
              </Button>
              <IconButton ref={anchor} icon="more" label="Profile actions" variant="solid" aria-haspopup="menu" aria-expanded={menu} onClick={() => setMenu((m) => !m)} />
              <Popover anchor={anchor} open={menu} onClose={() => setMenu(false)} placement="bottom-end" label="Profile actions">
                <Menu
                  onClose={() => setMenu(false)}
                  items={[
                    { label: "Rename", icon: <Icon name="edit" size={15} />, onSelect: () => actions.rename(profile) },
                    { label: "Duplicate", icon: <Icon name="copy" size={15} />, onSelect: () => actions.duplicate(profile) },
                    { label: "Open folder", icon: <Icon name="folder" size={15} />, onSelect: () => void api.revealFolder("profile", profile.id) },
                    ...(profile.installed
                      ? [{ label: "Repair game files", icon: <Icon name="wrench" size={15} />, onSelect: () => void api.installProfile(profile.id, true).catch((e) => toast({ tone: "danger", title: "Repair failed", body: errorText(e) })) }]
                      : []),
                    { type: "separator" as const },
                    { label: "Delete…", icon: <Icon name="trash" size={15} />, danger: true, onSelect: () => actions.remove(profile) },
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
              View logs
            </button>
          )}
        </p>
      )}

      <Tabs
        label="Profile sections"
        value={tab}
        onChange={onTab}
        tabs={[
          { value: "content", label: "Content" },
          { value: "files", label: "Files" },
          { value: "worlds", label: "Worlds" },
          { value: "logs", label: "Logs" },
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

const gb = (mb: number) => `${(mb / 1024).toFixed(1)} GB`;

/** The ceiling the launcher enforces (data.rs max_memory_mb), worked out the same way as Settings → Memory. */
// The tabs are memoized so the header's live progress (several updates a second while this profile
// downloads) doesn't re-render a long content list, file list or log underneath it.
const ContentTabView = memo(ContentTab);
const FilesTabView = memo(FilesTab);
const WorldsTabView = memo(WorldsTab);
const LogsTabView = memo(LogsTab);

function memoryLimits(total: number | null) {
  if (!total) return { max: 16384, warn: null as number | null };
  const mb = Math.floor(total / (1024 * 1024));
  return { max: Math.max(2048, Math.min(32768, Math.floor((mb * 0.75) / 512) * 512)), warn: Math.floor((mb * 0.6) / 512) * 512 };
}

/**
 * This profile's memory: the launcher-wide setting unless the profile has its own value. A chip in
 * the hero opens a small panel; slider moves are saved once they rest, and apply from the next launch.
 */
function ProfileMemory({ profile, navigate }: { profile: Profile; navigate: Navigate }) {
  const { settings } = useSettings();
  const store = useStoreSelect((s) => ({ reloadProfiles: s.reloadProfiles }));
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [limits, setLimits] = useState<ReturnType<typeof memoryLimits> | null>(null);
  const [draft, setDraft] = useState<number | null>(null);
  const anchor = useRef<HTMLButtonElement>(null);
  const pending = useRef<{ id: string; value: number | null; timer: number } | null>(null);
  const custom = profile.memoryMb ?? null;
  const fallback = limits ? Math.min(settings.memoryMb, limits.max) : settings.memoryMb;
  const value = draft ?? custom ?? fallback;
  const isCustom = draft != null || custom != null;

  useEffect(() => {
    if (!open || limits) return;
    void api
      .systemInfo()
      .then((i) => setLimits(memoryLimits(i.totalMemory)))
      .catch(() => setLimits(memoryLimits(null)));
  }, [open, limits]);

  // Saves go through a ref so the debounce timer and the unmount flush always use the current store.
  const save = useRef<(id: string, memoryMb: number | null) => Promise<void>>(async () => {});
  useLayoutEffect(() => {
    save.current = async (id, memoryMb) => {
      try {
        await api.setProfileMemory(id, memoryMb);
        await store.reloadProfiles();
      } catch (e) {
        toast({ tone: "danger", title: "Couldn't change memory", body: errorText(e) });
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
      <button ref={anchor} type="button" className={`ws-memory-chip ${isCustom ? "is-custom" : ""}`} aria-haspopup="dialog" aria-expanded={open} onClick={() => setOpen((o) => !o)} title="Memory for this profile">
        <Icon name="gauge" size={13} />
        Memory {gb(value)}
        <span className="faint">{isCustom ? "Custom" : "Default"}</span>
      </button>
      <Popover
        anchor={anchor}
        open={open}
        onClose={() => {
          flush();
          setOpen(false);
        }}
        placement="bottom-start"
        label="Memory for this profile"
        className="ws-memory"
      >
        <div className="ws-memory-head">
          <strong>Memory</strong>
          <span className="muted">{isCustom ? `Custom · ${gb(value)}` : `Uses the default · ${gb(fallback)}`}</span>
        </div>
        {limits ? (
          <Slider label="Memory for this profile" min={1024} max={limits.max} step={512} value={Math.min(value, limits.max)} onChange={(v) => change(v)} format={gb} />
        ) : (
          <div className="ws-memory-wait">
            <Spinner size={14} />
          </div>
        )}
        {limits?.warn && value > limits.warn && <p className="settings-warn">That leaves little memory for your system. Minecraft may stutter or your computer may slow down.</p>}
        <div className="ws-memory-foot">
          {isCustom ? (
            <Button size="sm" variant="ghost" icon="undo" onClick={() => change(null)}>
              Use default ({gb(fallback)})
            </Button>
          ) : (
            <button
              type="button"
              className="launch-link"
              onClick={() => {
                setOpen(false);
                navigate("settings", "minecraft");
              }}
            >
              Change the default in Settings → Memory
            </button>
          )}
        </div>
        <p className="faint ws-memory-note">{isCustom ? "Applies the next time this profile starts." : "Moving the slider gives this profile its own value, from the next time it starts."}</p>
      </Popover>
    </>
  );
}

function ContentTab({ profile, navigate }: { profile: Profile; navigate: Navigate }) {
  // Re-renders for this profile's content changes and running state only, not every progress tick.
  const store = useStoreSelect((s) => ({ revision: s.contentRevision[profile.id] ?? 0, running: s.states[profile.id]?.state === "running" }));
  const { flintAssist } = useSettings().settings;
  const toast = useToast();
  const [data, setData] = useState<ProfileContent | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState<Filter>("all");
  const [sort, setSort] = useState<Sort>("name");
  const [query, setQuery] = useState("");
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [updates, setUpdates] = useState<ContentUpdate[] | null>(null);
  const [checking, setChecking] = useState(false);
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
  const updateFor = useMemo(() => new Map((updates ?? []).map((u) => [key(u), u])), [updates]);
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
      toast({ tone: "danger", title: "That didn't work", body: errorText(e) });
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
  const checkUpdates = async () => {
    setChecking(true);
    try {
      await api.identifyContent(profile.id).catch(() => null);
      const result = await api.contentUpdates(profile.id);
      setUpdates(result.updates);
      await load();
      toast({ tone: result.updates.length ? "neutral" : "success", title: result.updates.length ? `${result.updates.length} update${result.updates.length === 1 ? "" : "s"} available` : "Everything is up to date" });
    } catch (e) {
      toast({ tone: "danger", title: "Couldn't check for updates", body: errorText(e) });
    } finally {
      setChecking(false);
    }
  };
  const applyUpdates = (list: ContentUpdate[]) =>
    guard(async () => {
      await api.updateContent(
        profile.id,
        list.map((u) => ({ kind: u.kind, fileName: u.fileName, versionId: u.versionId, world: u.world })),
      );
      setUpdates((prev) => prev?.filter((u) => !list.includes(u)) ?? null);
    });

  if (error) return <EmptyState icon="alert" title="Couldn't read this profile" body={error} action={<Button onClick={() => void load()}>Try again</Button>} />;
  if (!data) return <div className="ws-loading"><Spinner /></div>;

  return (
    <section className="content-tab">
      <div className="ct-toolbar">
        <div className="ct-filters" role="tablist" aria-label="Content type">
          {(["all", "mod", "resourcepack", "shader", "datapack"] as Filter[]).map((f) => (
            <button key={f} type="button" role="tab" aria-selected={filter === f} className={`ct-filter ${filter === f ? "is-active" : ""}`} onClick={() => setFilter(f)}>
              {f === "all" ? "All" : f === "mod" ? "Mods" : f === "resourcepack" ? "Resource packs" : f === "shader" ? "Shaders" : "Data packs"}
              <span className="tab-count">{counts[f]}</span>
            </button>
          ))}
        </div>
        <label className="lib-search ct-search">
          <Icon name="search" size={15} />
          <input type="search" placeholder="Search installed" value={query} onChange={(e) => setQuery(e.target.value)} aria-label="Search installed content" />
        </label>
        <Dropdown
          label="Sort"
          value={sort}
          onChange={setSort}
          options={[
            { value: "name", label: "Name" },
            { value: "recent", label: "Recently added" },
            { value: "size", label: "Size" },
          ]}
        />
        <Button icon="refresh" onClick={() => void checkUpdates()} loading={checking}>
          Check updates
        </Button>
        {profile.loader === "fabric" && (
          <Button icon="gauge" onClick={() => setPreset(PERFORMANCE_PRESET)} disabled={running}>
            Performance mods
          </Button>
        )}
        {flintAssist && (
          <button type="button" className="lib-ai" aria-expanded={assisting} onClick={() => (sound.play(assisting ? "tap" : "open"), setAssisting((a) => !a))}>
            <FlintMark size={20} />
            Find with Flint AI
          </button>
        )}
        <Button variant="primary" icon="plus" onClick={() => navigate("library", `${profile.id}:${filter === "all" ? "mod" : filter}`)}>
          Add content
        </Button>
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
        <p className="ct-note is-warn">
          <Icon name="lock" size={14} /> Minecraft is running. Close it to change this profile's content.
        </p>
      )}
      {profile.loader !== "fabric" && (filter === "mod" || filter === "shader") && (
        <p className="ct-note">
          <Icon name="info" size={14} /> This is a vanilla profile, so Minecraft won't load mods or shaders here.
        </p>
      )}
      {updates && updates.length > 0 && (
        <div className="ct-updates">
          <span>
            <Icon name="arrowUp" size={15} /> {updates.length} update{updates.length === 1 ? "" : "s"} available. The current file is kept so you can roll back.
          </span>
          <Button size="sm" variant="primary" onClick={() => void applyUpdates(updates)} disabled={busy || running}>
            Update all
          </Button>
        </div>
      )}

      {pickedItems.length > 0 && (
        <div className="ct-batch" role="toolbar" aria-label="Selected items">
          <strong>{pickedItems.length} selected</strong>
          {pickedMods.length > 0 && (
            <>
              <Button size="sm" onClick={() => setEnabled(pickedMods, true)} disabled={busy || running}>
                Enable
              </Button>
              <Button size="sm" onClick={() => setEnabled(pickedMods, false)} disabled={busy || running}>
                Disable
              </Button>
            </>
          )}
          {pickedUpdates.length > 0 && (
            <Button size="sm" icon="arrowUp" onClick={() => void applyUpdates(pickedUpdates)} disabled={busy || running}>
              Update {pickedUpdates.length}
            </Button>
          )}
          <Button size="sm" variant="danger" icon="trash" onClick={() => setRemoving(pickedItems)} disabled={busy || running}>
            Remove
          </Button>
          <button type="button" className="ct-batch-clear" onClick={() => setPicked(new Set())}>
            Clear
          </button>
        </div>
      )}

      {items.length === 0 ? (
        <EmptyState
          icon={query ? "search" : "puzzle"}
          title={query ? "No matches" : filter === "all" ? "Nothing installed yet" : `No ${filter === "mod" ? "mods" : filter === "resourcepack" ? "resource packs" : filter === "shader" ? "shaders" : "data packs"} yet`}
          body={query ? undefined : "Browse the Library to add content to this profile."}
          action={
            !query && (
              <Button variant="primary" icon="library" onClick={() => navigate("library", `${profile.id}:${filter === "all" ? "mod" : filter}`)}>
                Browse Library
              </Button>
            )
          }
        />
      ) : (
        <div className="ct-table" role="table" aria-label="Installed content">
          <div className="ct-row ct-head" role="row">
            <span role="columnheader">
              <Checkbox
                label="Select all"
                checked={pickedItems.length === items.length}
                onChange={(v) => setPicked(v ? new Set(items.map(key)) : new Set())}
              />
            </span>
            <span role="columnheader">Name</span>
            <span role="columnheader">Version</span>
            <span role="columnheader">Source</span>
            <span role="columnheader">Status</span>
            <span role="columnheader" className="sr-only">
              Actions
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
              locked={busy || running}
              onToggle={(v) => void toggle(item, v)}
              onUpdate={(u) => void applyUpdates([u])}
              onRollback={() => void guard(() => api.rollbackContent(profile.id, item.kind, item.projectId!, item.world), `Rolled back ${item.title}`)}
              onRemove={() => setRemoving([item])}
              onReveal={() => void api.revealFolder(item.kind === "mod" ? "mods" : item.kind === "resourcepack" ? "resourcepacks" : item.kind === "shader" ? "shaderpacks" : "saves", profile.id)}
            />
          ))}
        </div>
      )}
      <p className="ct-foot faint">
        Changes apply the next time Minecraft starts. Switched-off mods keep their file with a <code>.disabled</code> ending, so switching them back on is instant.
      </p>

      <Modal
        open={!!removing}
        onClose={() => setRemoving(null)}
        title={removing && removing.length === 1 ? `Remove ${removing[0].title}?` : `Remove ${removing?.length ?? 0} items?`}
        width={460}
        footer={
          <>
            <Button variant="ghost" onClick={() => setRemoving(null)}>
              Cancel
            </Button>
            <Button
              variant="danger"
              icon="trash"
              onClick={() => {
                const list = removing ?? [];
                setRemoving(null);
                void guard(() => api.removeContent(profile.id, list.map((i) => ({ kind: i.kind, fileName: i.fileName, world: i.world }))), list.length === 1 ? `Removed ${list[0].title}` : `Removed ${list.length} items`);
              }}
            >
              Remove
            </Button>
          </>
        }
      >
        <p className="muted modal-copy">The files are deleted from this profile only. Other profiles and your worlds aren't affected.</p>
        {removing && dependentsOf(removing).length > 0 && (
          <p className="install-warn">
            <Icon name="alert" size={15} />
            {dependentsOf(removing)
              .map((m) => m.title)
              .join(", ")}{" "}
            {dependentsOf(removing).length === 1 ? "needs" : "need"} this. Minecraft may fail to start until you remove {dependentsOf(removing).length === 1 ? "it" : "them"} too or reinstall this.
          </p>
        )}
      </Modal>

      <InstallDialog target={preset} profile={profile} onClose={() => setPreset(null)} />

      <Modal
        open={!!disabling}
        onClose={() => setDisabling(null)}
        title="Other mods need this"
        width={480}
        footer={
          disabling && (
            <>
              <Button variant="ghost" onClick={() => setDisabling(null)}>
                Cancel
              </Button>
              <Button
                onClick={() => {
                  const list = disabling;
                  setDisabling(null);
                  void guard(() => api.setEnabled(profile.id, list.map((m) => m.fileName), false));
                }}
              >
                Disable anyway
              </Button>
              <Button
                variant="primary"
                onClick={() => {
                  const list = [...disabling, ...dependentsOf(disabling)];
                  setDisabling(null);
                  void guard(() => api.setEnabled(profile.id, list.map((m) => m.fileName), false), `Disabled ${list.length} mods`);
                }}
              >
                Disable {dependentsOf(disabling).length + disabling.length} together
              </Button>
            </>
          )
        }
      >
        {disabling && (
          <>
            <p className="muted modal-copy">
              {disabling.length === 1 ? disabling[0].title : `${disabling.length} mods`} {disabling.length === 1 ? "is" : "are"} required by these enabled mods. If you disable only{" "}
              {disabling.length === 1 ? "it" : "them"}, Minecraft will refuse to start until you turn {disabling.length === 1 ? "it" : "them"} back on.
            </p>
            <ul className="dep-list">
              {dependentsOf(disabling).map((m) => (
                <li key={m.fileName}>
                  <ProjectIcon src={m.iconUrl} title={m.title} size={28} />
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
  locked,
  onToggle,
  onUpdate,
  onRollback,
  onRemove,
  onReveal,
}: {
  item: InstalledItem;
  picked: boolean;
  onPick: (v: boolean) => void;
  update?: ContentUpdate;
  locked: boolean;
  onToggle: (v: boolean) => void;
  onUpdate: (u: ContentUpdate) => void;
  onRollback: () => void;
  onRemove: () => void;
  onReveal: () => void;
}) {
  const [menu, setMenu] = useState(false);
  const anchor = useRef<HTMLButtonElement>(null);
  const isMod = item.kind === "mod" && !item.folder;
  return (
    <div className={`ct-row ${item.enabled ? "" : "is-off"} ${item.missing ? "is-missing" : ""}`} role="row">
      <span role="cell">
        <Checkbox label={`Select ${item.title}`} checked={picked} onChange={onPick} />
      </span>
      <span role="cell" className="ct-name">
        <ProjectIcon src={item.iconUrl} title={item.title} size={36} />
        <span>
          <strong title={item.title}>{item.title}</strong>
          <span className="faint" title={item.fileName}>
            {item.kind !== "mod" ? `${KIND_NAME[item.kind]}${item.world ? ` · ${item.world}` : ""} · ` : ""}
            {item.fileName}
          </span>
        </span>
      </span>
      <span role="cell" className="ct-version">
        <span>{item.versionNumber || "—"}</span>
        {update && (
          <button type="button" className="ct-update" onClick={() => onUpdate(update)} disabled={locked} title={update.changelog || undefined}>
            <Icon name="arrowUp" size={12} />
            {update.versionNumber}
          </button>
        )}
      </span>
      <span role="cell" className="faint ct-source">
        {SOURCE[item.source] ?? item.source}
        {item.source === "manual" && item.identified && <span title="Matched to Modrinth by file hash"> · on Modrinth</span>}
      </span>
      <span role="cell" className="ct-status">
        {item.missing ? (
          <Badge tone="danger">File missing</Badge>
        ) : isMod ? (
          <Switch checked={item.enabled} onChange={onToggle} label={`${item.enabled ? "Disable" : "Enable"} ${item.title}`} disabled={locked} />
        ) : item.kind === "resourcepack" || item.kind === "shader" ? (
          <span className="faint">Choose in game</span>
        ) : (
          <span className="faint">Active in world</span>
        )}
      </span>
      <span role="cell" className="ct-actions">
        <IconButton ref={anchor} icon="more" size="sm" label={`Actions for ${item.title}`} aria-haspopup="menu" aria-expanded={menu} onClick={() => setMenu((m) => !m)} />
        <Popover anchor={anchor} open={menu} onClose={() => setMenu(false)} placement="bottom-end" label="Item actions">
          <Menu
            onClose={() => setMenu(false)}
            items={[
              ...(update ? [{ label: `Update to ${update.versionNumber}`, icon: <Icon name="arrowUp" size={15} />, disabled: locked, onSelect: () => onUpdate(update) }] : []),
              ...(item.canRollback ? [{ label: `Roll back to ${item.previousVersion ?? "previous"}`, icon: <Icon name="undo" size={15} />, disabled: locked, onSelect: onRollback }] : []),
              { label: "Show in folder", icon: <Icon name="folder" size={15} />, onSelect: onReveal },
              { type: "separator" as const },
              { label: "Remove", icon: <Icon name="trash" size={15} />, danger: true, disabled: locked, onSelect: onRemove },
            ]}
          />
        </Popover>
      </span>
    </div>
  );
}

const FOLDERS: { id: FolderKind; label: string; note: string }[] = [
  { id: "mods", label: "Mods", note: "Fabric mods (.jar)" },
  { id: "resourcepacks", label: "Resource packs", note: "Textures and sounds" },
  { id: "shaderpacks", label: "Shader packs", note: "Used by Iris" },
  { id: "saves", label: "Worlds", note: "Single-player saves" },
  { id: "screenshots", label: "Screenshots", note: "F2 in game" },
  { id: "config", label: "Config", note: "Mod settings" },
  { id: "logs", label: "Logs", note: "Game output" },
];

function FilesTab({ profile }: { profile: Profile }) {
  const [files, setFiles] = useState<ProfileFiles | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    void api
      .profileFiles(profile.id)
      .then(setFiles)
      .catch((e) => setError(errorText(e)));
  }, [profile.id]);
  if (error) return <EmptyState icon="alert" title="Couldn't read the folder" body={error} />;
  if (!files) return <div className="ws-loading"><Spinner /></div>;
  return (
    <section className="files-tab">
      <div className="files-path">
        <span className="mono" title={files.path}>
          {files.path}
        </span>
        <span className="faint">{bytes(files.total)} total</span>
        <Button size="sm" icon="folder" onClick={() => void api.revealFolder("profile", profile.id)}>
          Open profile folder
        </Button>
      </div>
      <div className="folder-grid">
        {FOLDERS.map((f) => {
          const info = files.folders.find((x) => x.id === f.id);
          return (
            <button key={f.id} type="button" className="folder-card" onClick={() => void api.revealFolder(f.id, profile.id)} disabled={!info?.exists}>
              <Icon name="folder" size={20} />
              <span>
                <strong>{f.label}</strong>
                <span className="faint">{info?.exists ? `${info.count} item${info.count === 1 ? "" : "s"} · ${bytes(info.size)}` : "Not created yet"}</span>
                <span className="faint">{f.note}</span>
              </span>
            </button>
          );
        })}
      </div>
    </section>
  );
}

function WorldsTab({ profile }: { profile: Profile }) {
  const [worlds, setWorlds] = useState<ProfileContent["worlds"] | null>(null);
  useEffect(() => {
    void api
      .worlds(profile.id)
      .then(setWorlds)
      .catch(() => setWorlds([]));
  }, [profile.id]);
  if (!worlds) return <div className="ws-loading"><Spinner /></div>;
  if (worlds.length === 0) return <EmptyState icon="globe" title="No worlds yet" body="Worlds you create while playing this profile show up here." />;
  return (
    <section className="worlds-tab">
      <div className="world-grid">
        {worlds.map((w) => (
          <article key={w.id} className="world-card">
            {w.icon ? <img src={w.icon} alt="" /> : <span className="world-icon"><Icon name="globe" size={22} /></span>}
            <span>
              <strong title={w.name}>{w.name}</strong>
              <span className="faint">{w.lastPlayed ? `Played ${relativeTime(w.lastPlayed)}` : ""}</span>
              <span className="faint">{w.datapacks ? `${w.datapacks} data pack${w.datapacks === 1 ? "" : "s"}` : "No data packs"}</span>
            </span>
          </article>
        ))}
      </div>
      <Button icon="folder" onClick={() => void api.revealFolder("saves", profile.id)}>
        Open saves folder
      </Button>
    </section>
  );
}

const LOG_ROW = 20;

function LogsTab({ profile }: { profile: Profile }) {
  const [logs, setLogs] = useState<LogFile[] | null>(null);
  const [open, setOpen] = useState<LogFile | null>(null);
  const [text, setText] = useState<string | null>(null);
  const [filter, setFilter] = useState("");
  const [level, setLevel] = useState<LevelFilter>("all");
  const [revision, setRevision] = useState(0);
  const [view, setView] = useState({ top: 0, height: 480 });
  const scroller = useRef<HTMLDivElement>(null);
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
  if (logs.length === 0) return <EmptyState icon="terminal" title="No logs yet" body="Logs appear after this profile has been launched." />;

  // Only the rows in view (plus a margin) are rendered, so large logs stay smooth.
  const total = shown?.length ?? 0;
  const first = Math.max(0, Math.floor(view.top / LOG_ROW) - 30);
  const last = Math.min(total, Math.ceil((view.top + view.height) / LOG_ROW) + 30);
  const visible = shown ? shown.slice(first, last) : [];

  return (
    <section className="logs-tab">
      <aside className="log-list" aria-label="Log files">
        {logs.map((l) => (
          <button key={`${l.folder}/${l.name}`} type="button" className={`log-item ${l.kind === "crash" ? "is-crash" : ""} ${open?.name === l.name && open.folder === l.folder ? "is-active" : ""}`} onClick={() => setOpen(l)}>
            <Icon name={l.kind === "crash" ? "alert" : "file"} size={15} />
            <span>
              <strong>{l.name}</strong>
              <span className="faint">
                {l.kind === "crash" ? "Crash report · " : ""}
                {l.modified ? shortDate(l.modified) : ""} · {bytes(l.size)}
              </span>
            </span>
          </button>
        ))}
      </aside>
      <div className="log-view">
        <header className="log-head">
          <span className="log-title">
            <strong>{open?.name}</strong>
            <span className="faint">
              {open?.kind === "crash" ? "Crash report" : open?.name === "flint-launch.log" ? "Game output from Flint's last launch" : "Minecraft log"} · Access tokens are hidden
            </span>
          </span>
          <span className="log-counts">
            {open?.kind === "crash" ? (
              <span className="log-count is-error">
                <Icon name="alert" size={13} /> Crash report
              </span>
            ) : counts.error > 0 && (
              <button type="button" className="log-count is-error" onClick={jumpToFirstError}>
                <Icon name="alert" size={13} /> {counts.error} error{counts.error === 1 ? "" : "s"}
              </button>
            )}
            {counts.warn > 0 && <span className="log-count is-warn">{counts.warn} warning{counts.warn === 1 ? "" : "s"}</span>}
          </span>
        </header>
        {description && (
          <p className="log-crash" role="note">
            <Icon name="alert" size={15} />
            <span>
              <strong>Minecraft crashed:</strong> {description}
            </span>
          </p>
        )}
        <div className="log-tools">
          <label className="lib-search">
            <Icon name="search" size={15} />
            <input type="search" placeholder="Filter lines" value={filter} onChange={(e) => setFilter(e.target.value)} aria-label="Filter log lines" />
          </label>
          <Segmented
            label="Show"
            value={level}
            onChange={setLevel}
            options={[
              { value: "all", label: "All" },
              { value: "warn", label: "Warnings" },
              { value: "error", label: "Errors" },
            ]}
          />
          <span className="log-tools-gap" />
          <IconButton icon="refresh" label="Reload logs" size="sm" onClick={() => setRevision((r) => r + 1)} />
          <IconButton
            icon="copy"
            label={filter || level !== "all" ? "Copy shown lines" : "Copy log"}
            size="sm"
            disabled={!shown?.length}
            onClick={() => {
              const lines = (shown ?? []).map((l) => (l.time ? `[${l.time}] ${l.text}` : l.text)).join("\n");
              void navigator.clipboard.writeText(lines).then(() => toast({ tone: "success", title: "Copied", body: `${shown?.length ?? 0} lines` }));
            }}
          />
          <IconButton icon="folder" label="Open logs folder" size="sm" onClick={() => void api.revealFolder(open?.folder === "crash-reports" ? "profile" : "logs", profile.id)} />
        </div>
        <div ref={scroller} className="log-scroll scroll" onScroll={onScroll} role="log" aria-label={open?.name}>
          {shown === null ? (
            <p className="log-empty">Loading…</p>
          ) : total === 0 ? (
            <p className="log-empty">{filter || level !== "all" ? "No matching lines." : "This log is empty."}</p>
          ) : (
            <div className="log-rows" style={{ height: total * LOG_ROW }}>
              <div style={{ transform: `translateY(${first * LOG_ROW}px)` }}>
                {visible.map((l) => (
                  <div key={l.n} className={`log-line lv-${l.level} ${l.stack ? "is-stack" : ""}`}>
                    <span className="log-time">{l.time ?? ""}</span>
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
