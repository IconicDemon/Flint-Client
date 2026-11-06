import { useCallback, useEffect, useRef, useState } from "react";
import { api, errorText, type Profile, type ProfileFiles, type RunState } from "../../lib/api";
import { neighbourAfterRemoval } from "../../lib/carousel";
import { takePendingPack } from "../../lib/flintpack";
import { bytes, relativeTime, shortDate } from "../../lib/format";
import { t, useT, type MessageKey, type Translate } from "../../lib/i18n";
import { useSettings } from "../../lib/settings";
import { sound } from "../../lib/sound";
import { useStoreSelect } from "../../lib/store";
import { defaultProfileName, platformBlock, versionMeta, VERSIONS } from "../../lib/versions";
import { Button, EmptyState, IconButton, Segmented, Spinner, TextField } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { Menu, Modal, Popover, useToast } from "../../ui/overlays";
import { PageHeader } from "../pages/PageHeader";
import type { Navigate } from "../shell/Shell";
import { EditProfile } from "./EditProfile";
import { ProfileCarousel } from "./ProfileCarousel";
import { ProfileGrid } from "./ProfileGrid";
import { ExportProfile, ImportProfile } from "./ProfilePack";
import { usePinToggle } from "./usePinToggle";
import { Workspace, type WorkspaceTab } from "./Workspace";

const STATE_KEY: Record<RunState, MessageKey> = {
  uninstalled: "profiles.state.uninstalled",
  checking: "profiles.state.checking",
  downloading: "profiles.state.downloading",
  installing: "profiles.state.installing",
  ready: "profiles.state.ready",
  launching: "profiles.state.launching",
  running: "profiles.state.running",
  error: "profiles.state.error",
};

/**
 * Each profile state's name, for its status pill. Every read gives the name in the current language, so
 * `STATE_LABEL[state]` works in a render like a plain table (the component re-renders through `useT`).
 */
export const STATE_LABEL = Object.defineProperties(
  {},
  Object.fromEntries(Object.entries(STATE_KEY).map(([state, key]) => [state, { get: () => t(key), enumerable: true }])),
) as Readonly<Record<RunState, string>>;

export default function ProfilesPage({ navigate, target, onOpenChange }: { navigate: Navigate; target?: string; onOpenChange?: (id: string | null) => void }) {
  const t = useT();
  const store = useStoreSelect((s) => ({ profiles: s.profiles, selectedId: s.selectedId, info: s.info, selectProfile: s.selectProfile }));
  const [targetId, targetTab] = (target ?? "").split(":");
  const [openId, setOpenId] = useState<string | null>(targetId && targetId !== "new" ? targetId : null);
  const [tab, setTab] = useState<WorkspaceTab>((targetTab as WorkspaceTab) || "content");
  const [creating, setCreating] = useState(targetId === "new");
  const [renaming, setRenaming] = useState<Profile | null>(null);
  const [duplicating, setDuplicating] = useState<Profile | null>(null);
  const [deleting, setDeleting] = useState<Profile | null>(null);
  const [exporting, setExporting] = useState<Profile | null>(null);
  // A .flintpack Flint was opened with (double-click) opens its import preview straight away.
  const [importPath, setImportPath] = useState<string | null>(() => takePendingPack());
  const { settings, update } = useSettings();
  const view = settings.profilesView;
  // Which card the carousel centres. Only kept in memory: it starts on the profile selected for Play
  // and falls back to it whenever the focused profile disappears.
  const [focusId, setFocusId] = useState<string | null>(targetId && targetId !== "new" ? targetId : null);
  const toast = useToast();
  const open = store.profiles.find((p) => p.id === openId) ?? null;
  // The sidebar marks the profile open here (it changes without navigating).
  const openNow = open?.id ?? null;
  useEffect(() => onOpenChange?.(openNow), [openNow, onOpenChange]);
  const ids = store.profiles.map((p) => p.id);
  const focusAt = focusId ? ids.indexOf(focusId) : -1;
  const focusIndex = Math.max(0, focusAt >= 0 ? focusAt : ids.indexOf(store.selectedId ?? ""));
  const focused = store.profiles[focusIndex] ?? null;

  const actions = { rename: setRenaming, duplicate: setDuplicating, remove: setDeleting, export: setExporting };

  const pickImport = async () => {
    try {
      const path = await api.packPickOpen();
      if (path) setImportPath(path);
    } catch (e) {
      toast({ tone: "danger", title: t("profiles.page.openFileFailed"), body: errorText(e) });
    }
  };
  const closeImport = useCallback(() => setImportPath(null), []);
  const closeExport = useCallback(() => setExporting(null), []);
  const imported = useCallback((id: string) => setFocusId(id), []);

  const openProfile = (id: string, at: WorkspaceTab) => {
    setFocusId(id);
    setTab(at);
    setOpenId(id);
  };

  const select = (p: Profile) => {
    if (p.id === store.selectedId) return;
    // The store shows the new selection at once and puts the old one back if saving fails.
    store.selectProfile(p.id).catch((e) => toast({ tone: "danger", title: t("profiles.page.selectFailed"), body: errorText(e) }));
  };

  /**
   * A card click or Enter/Space. It selects the profile for Play (never launches). On the card that is
   * already focused and selected, it opens the profile's content, like the old artwork click did (the
   * carousel drops the second click of a double-click, so selecting a card never also opens it).
   * Profiles this computer can't run are only focused, so the row under the cards explains why.
   */
  const activate = (p: Profile, wasFocused: boolean) => {
    if (platformBlock(p.gameVersion, store.info)) return;
    if (p.id !== store.selectedId) select(p);
    else if (wasFocused) openProfile(p.id, "content");
  };

  return (
    <div className={`page-body profiles ${open ? "" : `is-gallery is-${view}`}`}>
      {open ? (
        <Workspace profile={open} tab={tab} onTab={setTab} onBack={() => setOpenId(null)} navigate={navigate} actions={actions} />
      ) : (
        <>
          <PageHeader
            title={t("profiles.page.title")}
            subtitle={t("profiles.page.subtitle")}
            actions={
              <>
                {store.profiles.length > 0 && (
                  <Segmented
                    label={t("profiles.page.viewLabel")}
                    value={view}
                    onChange={(v) => update({ profilesView: v })}
                    options={[
                      { value: "deck", label: t("profiles.page.viewDeck") },
                      { value: "grid", label: t("profiles.page.viewGrid") },
                    ]}
                  />
                )}
                <NewProfileButton onNew={() => setCreating(true)} onImport={() => void pickImport()} />
              </>
            }
          />
          {store.profiles.length === 0 ? (
            <EmptyState
              icon="layers"
              title={t("profiles.empty.title")}
              body={t("profiles.empty.body")}
              action={
                <div className="empty-actions">
                  <Button variant="primary" icon="plus" onClick={() => setCreating(true)}>
                    {t("profiles.add.new")}
                  </Button>
                  <Button icon="download" onClick={() => void pickImport()}>
                    {t("profiles.add.import")}
                  </Button>
                </div>
              }
            />
          ) : view === "grid" ? (
            <div className="pg-area">
              <ProfileGrid profiles={store.profiles} selectedId={store.selectedId} navigate={navigate} actions={actions} onSelect={select} onOpen={(p) => openProfile(p.id, "content")} />
            </div>
          ) : (
            <div className="pc-area">
              <ProfileCarousel profiles={store.profiles} focusIndex={focusIndex} selectedId={store.selectedId} onFocus={setFocusId} onActivate={activate} />
              {focused && <ProfileBar profile={focused} onSelect={() => select(focused)} onOpen={() => openProfile(focused.id, "content")} navigate={navigate} actions={actions} />}
            </div>
          )}
        </>
      )}
      <CreateProfile
        open={creating}
        onClose={() => setCreating(false)}
        onCreated={(id) => {
          setFocusId(id);
          setOpenId(id);
        }}
      />
      <ExportProfile profile={exporting} onClose={closeExport} />
      <ImportProfile path={importPath} onClose={closeImport} onImported={imported} onPickAnother={() => void pickImport()} />
      <EditProfile profile={renaming} onClose={() => setRenaming(null)} />
      <DuplicateProfile profile={duplicating} onClose={() => setDuplicating(null)} />
      <DeleteProfile
        profile={deleting}
        onClose={() => setDeleting(null)}
        onDeleted={(id) => {
          if (openId === id) setOpenId(null);
          // `ids` is from the render before the reload, so it still has the removed profile and its
          // neighbours. If it doesn't, focus falls back to the profile selected for Play.
          setFocusId((f) => (f === id ? neighbourAfterRemoval(ids, id) : f));
        }}
      />
    </div>
  );
}

export type ProfileActions = {
  rename: (p: Profile) => void;
  duplicate: (p: Profile) => void;
  remove: (p: Profile) => void;
  export: (p: Profile) => void;
};

/** "New profile", with a menu beside it for the other way to add one: importing a .flintpack. */
function NewProfileButton({ onNew, onImport }: { onNew: () => void; onImport: () => void }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const anchor = useRef<HTMLButtonElement>(null);
  return (
    <div className="split-btn">
      <Button variant="primary" icon="plus" onClick={onNew}>
        {t("profiles.add.new")}
      </Button>
      <button
        ref={anchor}
        type="button"
        className="btn btn-primary btn-md split-btn-more"
        aria-label={t("profiles.add.more")}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => {
          sound.play("tap");
          setOpen((o) => !o);
        }}
      >
        <Icon name="chevronDown" size={16} />
      </button>
      <Popover anchor={anchor} open={open} onClose={() => setOpen(false)} placement="bottom-end" label={t("profiles.add.menuLabel")}>
        <Menu
          onClose={() => setOpen(false)}
          items={[
            { label: t("profiles.add.new"), hint: t("profiles.add.newHint"), icon: <Icon name="plus" size={15} />, onSelect: onNew },
            { label: t("profiles.add.import"), hint: ".flintpack", icon: <Icon name="download" size={15} />, onSelect: onImport },
          ]}
        />
      </Popover>
    </div>
  );
}

/** Timestamps are Unix seconds. Dates before 2020 can only come from a missing or corrupt record, so they aren't shown. */
const sane = (seconds: number | null | undefined) => (seconds && seconds > 1_577_836_800 ? seconds : null);

function historyLine(p: Profile, t: Translate) {
  const played = sane(p.lastPlayed);
  const created = sane(p.createdAt);
  if (played) return t("profiles.history.played", { when: relativeTime(played) });
  return created ? t("profiles.history.created", { date: shortDate(created) }) : t("profiles.history.never");
}

export function ProfileMenu({ profile: p, navigate, actions, anchor, open, onClose }: { profile: Profile; navigate: Navigate; actions: ProfileActions; anchor: React.RefObject<HTMLButtonElement | null>; open: boolean; onClose: () => void }) {
  const t = useT();
  const pin = usePinToggle();
  return (
    <Popover anchor={anchor} open={open} onClose={onClose} placement="bottom-end" label={t("profiles.menu.label")}>
      <Menu
        onClose={onClose}
        items={[
          { label: t("profiles.menu.addContent"), icon: <Icon name="library" size={15} />, onSelect: () => navigate("library", p.id) },
          { label: t("profiles.menu.edit"), hint: t("profiles.menu.editHint"), icon: <Icon name="edit" size={15} />, onSelect: () => actions.rename(p) },
          { label: t(p.pinnedAt ? "profiles.menu.unpin" : "profiles.menu.pin"), icon: <Icon name="star" size={15} />, onSelect: () => pin(p) },
          { label: t("profiles.menu.duplicate"), icon: <Icon name="copy" size={15} />, onSelect: () => actions.duplicate(p) },
          { label: t("profiles.menu.export"), hint: ".flintpack", icon: <Icon name="upload" size={15} />, onSelect: () => actions.export(p) },
          { label: t("profiles.menu.openFolder"), icon: <Icon name="folder" size={15} />, onSelect: () => void api.revealFolder("profile", p.id) },
          { type: "separator" },
          { label: t("profiles.menu.delete"), icon: <Icon name="trash" size={15} />, danger: true, onSelect: () => actions.remove(p) },
        ]}
      />
    </Popover>
  );
}

/**
 * One quiet row under the carousel for the centred profile. The card itself already shows its name,
 * version and state, so this only offers the next step and a single line of context.
 */
function ProfileBar({ profile: p, onSelect, onOpen, navigate, actions }: { profile: Profile; onSelect: () => void; onOpen: () => void; navigate: Navigate; actions: ProfileActions }) {
  const t = useT();
  const store = useStoreSelect((s) => ({ info: s.info, selectedId: s.selectedId, selected: s.selected }));
  const [menu, setMenu] = useState(false);
  const anchor = useRef<HTMLButtonElement>(null);
  const blocked = platformBlock(p.gameVersion, store.info);
  const selected = p.id === store.selectedId;
  const note = blocked ?? (!selected && store.selected ? t("profiles.bar.otherSelected", { name: store.selected.name }) : historyLine(p, t));

  return (
    <section className="pc-bar" aria-label={t("profiles.bar.label", { name: p.name })}>
      <div className="pc-bar-actions">
        {selected ? (
          <Button variant="primary" icon="play" className="pc-bar-play" onClick={() => navigate("play")} disabled={!!blocked}>
            {t("profiles.action.goToPlay")}
          </Button>
        ) : (
          <Button variant="primary" icon="check" className="pc-bar-play" onClick={onSelect} disabled={!!blocked}>
            {t("profiles.action.selectForPlay")}
          </Button>
        )}
        <Button icon="layers" onClick={onOpen}>
          {t("profiles.bar.open")}
        </Button>
        <IconButton ref={anchor} icon="more" label={t("profiles.bar.more")} aria-haspopup="menu" aria-expanded={menu} onClick={() => setMenu((m) => !m)} />
        <ProfileMenu profile={p} navigate={navigate} actions={actions} anchor={anchor} open={menu} onClose={() => setMenu(false)} />
      </div>
      <p className={`pc-bar-note ${blocked ? "is-danger" : ""}`} title={note}>
        {note}
      </p>
    </section>
  );
}

function CreateProfile({ open, onClose, onCreated }: { open: boolean; onClose: () => void; onCreated: (id: string) => void }) {
  const t = useT();
  const store = useStoreSelect((s) => ({ info: s.info, reloadProfiles: s.reloadProfiles }));
  const toast = useToast();
  const [version, setVersion] = useState(VERSIONS[0].id);
  const [loader, setLoader] = useState<"fabric" | "vanilla">("fabric");
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const meta = versionMeta(version);
  const effectiveLoader = meta.fabric ? loader : "vanilla";

  useEffect(() => {
    if (open) {
      setVersion(VERSIONS[0].id);
      setLoader("fabric");
      setName("");
    }
  }, [open]);

  const create = async () => {
    setBusy(true);
    try {
      const profile = await api.createProfile(name.trim() || defaultProfileName(version), version, effectiveLoader, true);
      await store.reloadProfiles();
      toast({ tone: "success", title: t("profiles.create.done", { name: profile.name }), body: t("profiles.create.doneBody") });
      onClose();
      onCreated(profile.id);
    } catch (e) {
      toast({ tone: "danger", title: t("profiles.create.failed"), body: errorText(e) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t("profiles.create.title")}
      width={680}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {t("common.cancel")}
          </Button>
          <Button variant="primary" onClick={() => void create()} loading={busy}>
            {t("profiles.create.submit")}
          </Button>
        </>
      }
    >
      <div className="version-pick" role="radiogroup" aria-label={t("profiles.create.versionLabel")}>
        {VERSIONS.map((v) => {
          const blocked = platformBlock(v.id, store.info);
          return (
            <button
              key={v.id}
              type="button"
              role="radio"
              aria-checked={v.id === version}
              className={`version-chip ${v.id === version ? "is-selected" : ""}`}
              onClick={() => setVersion(v.id)}
              disabled={!!blocked}
              title={blocked ?? undefined}
            >
              <img src={v.thumb} alt="" />
              <span>
                <strong>{v.id}</strong>
                <span className="faint">{blocked ? t("profiles.create.appleSilicon") : v.drop ?? t(v.fabric ? "profiles.create.fabricOrVanilla" : "profiles.create.vanillaOnly")}</span>
              </span>
              {v.id === version && <Icon name="check" size={15} className="version-chip-check" />}
            </button>
          );
        })}
      </div>
      <div className="modal-row">
        <span className="modal-label">{t("profiles.create.loader")}</span>
        {meta.fabric ? (
          <Segmented
            label={t("profiles.create.loader")}
            value={loader}
            onChange={setLoader}
            options={[
              { value: "fabric", label: "Fabric" },
              { value: "vanilla", label: t("profiles.create.vanilla") },
            ]}
          />
        ) : (
          <span className="muted">{meta.note ?? t("profiles.create.noFabric")}</span>
        )}
      </div>
      <TextField
        label={t("profiles.field.name")}
        placeholder={defaultProfileName(version)}
        value={name}
        maxLength={40}
        onChange={(e) => setName(e.target.value)}
        onKeyDown={(e) => e.key === "Enter" && void create()}
        hint={t("profiles.create.javaHint", { version: meta.java })}
      />
    </Modal>
  );
}

function DuplicateProfile({ profile, onClose }: { profile: Profile | null; onClose: () => void }) {
  const t = useT();
  const store = useStoreSelect((s) => ({ reloadProfiles: s.reloadProfiles }));
  const toast = useToast();
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  useEffect(() => setName(profile ? t("profiles.duplicate.defaultName", { name: profile.name }).slice(0, 40) : ""), [profile, t]);
  const run = async () => {
    if (!profile || !name.trim()) return;
    setBusy(true);
    try {
      const copy = await api.duplicateProfile(profile.id, name.trim());
      await store.reloadProfiles();
      toast({ tone: "success", title: t("profiles.duplicate.done", { name: copy.name }), body: t("profiles.duplicate.doneBody") });
      onClose();
    } catch (e) {
      toast({ tone: "danger", title: t("profiles.duplicate.failed"), body: errorText(e) });
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal
      open={!!profile}
      onClose={onClose}
      title={t("profiles.duplicate.title")}
      width={460}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {t("common.cancel")}
          </Button>
          <Button variant="primary" icon="copy" onClick={() => void run()} loading={busy} disabled={!name.trim()}>
            {t("profiles.duplicate.submit")}
          </Button>
        </>
      }
    >
      <p className="muted modal-copy">{t("profiles.duplicate.body")}</p>
      <TextField label={t("profiles.duplicate.name")} value={name} maxLength={40} data-autofocus onChange={(e) => setName(e.target.value)} onKeyDown={(e) => e.key === "Enter" && void run()} />
    </Modal>
  );
}

/** The folders the delete dialog lists, in its order, by their folder name on disk. */
const FOLDER_KEY = {
  saves: "profiles.delete.folder.saves",
  screenshots: "profiles.delete.folder.screenshots",
  mods: "profiles.delete.folder.mods",
  resourcepacks: "profiles.delete.folder.resourcepacks",
  shaderpacks: "profiles.delete.folder.shaderpacks",
} as const satisfies Record<string, MessageKey>;

function DeleteProfile({ profile, onClose, onDeleted }: { profile: Profile | null; onClose: () => void; onDeleted: (id: string) => void }) {
  const t = useT();
  const store = useStoreSelect((s) => ({ reloadProfiles: s.reloadProfiles }));
  const toast = useToast();
  const [mode, setMode] = useState<"record" | "files">("record");
  const [busy, setBusy] = useState(false);
  const [files, setFiles] = useState<ProfileFiles | null>(null);
  useEffect(() => {
    setMode("record");
    setFiles(null);
    if (profile) void api.profileFiles(profile.id).then(setFiles).catch(() => {});
  }, [profile]);
  const folder = (id: string) => files?.folders.find((f) => f.id === id && f.exists);
  const contents = (Object.keys(FOLDER_KEY) as (keyof typeof FOLDER_KEY)[])
    .map((id) => ({ id, info: folder(id) }))
    .filter((x) => x.info && x.info.count > 0);
  const run = async () => {
    if (!profile) return;
    setBusy(true);
    try {
      const result = await api.deleteProfile(profile.id, mode === "files");
      await store.reloadProfiles();
      onDeleted(profile.id);
      toast({
        tone: "success",
        title: t("profiles.delete.done", { name: profile.name }),
        body: t(result.keptFolder ? "profiles.delete.keptBody" : "profiles.delete.deletedBody"),
      });
      onClose();
    } catch (e) {
      toast({ tone: "danger", title: t("profiles.delete.failed"), body: errorText(e) });
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal
      open={!!profile}
      onClose={onClose}
      title={profile ? t("profiles.delete.title", { name: profile.name }) : t("profiles.delete.titleNoName")}
      width={520}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {t("common.cancel")}
          </Button>
          <Button variant="danger" icon="trash" onClick={() => void run()} loading={busy} disabled={!profile}>
            {t(mode === "files" ? "profiles.delete.submitFiles" : "profiles.delete.submitRecord")}
          </Button>
        </>
      }
    >
      <div className="delete-options" role="radiogroup" aria-label={t("profiles.delete.choiceLabel")}>
        <button type="button" role="radio" aria-checked={mode === "record"} className={`delete-option ${mode === "record" ? "is-selected" : ""}`} onClick={() => setMode("record")}>
          <strong>{t("profiles.delete.recordTitle")}</strong>
          <span className="muted">{t("profiles.delete.recordBody")}</span>
        </button>
        <button type="button" role="radio" aria-checked={mode === "files"} className={`delete-option is-danger ${mode === "files" ? "is-selected" : ""}`} onClick={() => setMode("files")}>
          <strong>{t("profiles.delete.filesTitle")}</strong>
          <span className="muted">{t("profiles.delete.filesBody")}</span>
        </button>
      </div>
      {mode === "files" && profile && (
        <>
          <div className="delete-contents">
            <span className="eyebrow">{t("profiles.delete.contains")}</span>
            {files === null ? (
              <Spinner size={14} />
            ) : contents.length === 0 ? (
              <span className="muted">{t("profiles.delete.nothing", { size: bytes(files.total) })}</span>
            ) : (
              <ul>
                {contents.map(({ id, info }) => (
                  <li key={id} className={id === "saves" || id === "screenshots" ? "is-precious" : ""}>
                    <strong>{t(FOLDER_KEY[id])}</strong>
                    <span className="faint">
                      {info!.count} · {bytes(info!.size)}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </>
      )}
    </Modal>
  );
}
