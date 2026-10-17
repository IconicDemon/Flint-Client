import { useEffect, useRef, useState } from "react";
import { api, errorText, type Profile, type ProfileFiles } from "../../lib/api";
import { neighbourAfterRemoval } from "../../lib/carousel";
import { bytes, relativeTime, shortDate } from "../../lib/format";
import { useStoreSelect } from "../../lib/store";
import { defaultProfileName, platformBlock, versionMeta, VERSIONS } from "../../lib/versions";
import { Button, EmptyState, IconButton, Segmented, Spinner, TextField } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { Menu, Modal, Popover, useToast } from "../../ui/overlays";
import { PageHeader } from "../pages/PageHeader";
import type { Navigate } from "../shell/Shell";
import { ProfileCarousel } from "./ProfileCarousel";
import { Workspace, type WorkspaceTab } from "./Workspace";

export const STATE_LABEL: Record<string, string> = {
  uninstalled: "Not installed",
  checking: "Checking",
  downloading: "Downloading",
  installing: "Installing",
  ready: "Ready",
  launching: "Launching",
  running: "Running",
  error: "Needs attention",
};

export default function ProfilesPage({ navigate, target }: { navigate: Navigate; target?: string }) {
  const store = useStoreSelect((s) => ({ profiles: s.profiles, selectedId: s.selectedId, info: s.info, selectProfile: s.selectProfile }));
  const [targetId, targetTab] = (target ?? "").split(":");
  const [openId, setOpenId] = useState<string | null>(targetId && targetId !== "new" ? targetId : null);
  const [tab, setTab] = useState<WorkspaceTab>((targetTab as WorkspaceTab) || "content");
  const [creating, setCreating] = useState(targetId === "new");
  const [renaming, setRenaming] = useState<Profile | null>(null);
  const [duplicating, setDuplicating] = useState<Profile | null>(null);
  const [deleting, setDeleting] = useState<Profile | null>(null);
  // Which card the carousel centres. Only kept in memory: it starts on the profile selected for Play
  // and falls back to it whenever the focused profile disappears.
  const [focusId, setFocusId] = useState<string | null>(targetId && targetId !== "new" ? targetId : null);
  const toast = useToast();
  const open = store.profiles.find((p) => p.id === openId) ?? null;
  const ids = store.profiles.map((p) => p.id);
  const focusAt = focusId ? ids.indexOf(focusId) : -1;
  const focusIndex = Math.max(0, focusAt >= 0 ? focusAt : ids.indexOf(store.selectedId ?? ""));
  const focused = store.profiles[focusIndex] ?? null;

  const actions = { rename: setRenaming, duplicate: setDuplicating, remove: setDeleting };

  const openProfile = (id: string, t: WorkspaceTab) => {
    setFocusId(id);
    setTab(t);
    setOpenId(id);
  };

  const select = (p: Profile) => {
    if (p.id === store.selectedId) return;
    // The store shows the new selection at once and puts the old one back if saving fails.
    store.selectProfile(p.id).catch((e) => toast({ tone: "danger", title: "Couldn't select the profile", body: errorText(e) }));
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
    <div className={`page-body profiles ${open ? "" : "is-gallery"}`}>
      {open ? (
        <Workspace profile={open} tab={tab} onTab={setTab} onBack={() => setOpenId(null)} navigate={navigate} actions={actions} />
      ) : (
        <>
          <PageHeader
            title="Profiles"
            subtitle="Each profile keeps its own Minecraft version, mods, worlds and settings."
            actions={
              <Button variant="primary" icon="plus" onClick={() => setCreating(true)}>
                New profile
              </Button>
            }
          />
          {store.profiles.length === 0 ? (
            <EmptyState
              icon="layers"
              title="No profiles yet"
              body="Create one to choose a Minecraft version."
              action={
                <Button variant="primary" icon="plus" onClick={() => setCreating(true)}>
                  New profile
                </Button>
              }
            />
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
      <RenameProfile profile={renaming} onClose={() => setRenaming(null)} />
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
};

/** Timestamps are Unix seconds. Dates before 2020 can only come from a missing or corrupt record, so they aren't shown. */
const sane = (seconds: number | null | undefined) => (seconds && seconds > 1_577_836_800 ? seconds : null);

function historyLine(p: Profile) {
  const played = sane(p.lastPlayed);
  const created = sane(p.createdAt);
  if (played) return `Played ${relativeTime(played)}`;
  return created ? `Created ${shortDate(created)}` : "Never played";
}

function ProfileMenu({ profile: p, navigate, actions, anchor, open, onClose }: { profile: Profile; navigate: Navigate; actions: ProfileActions; anchor: React.RefObject<HTMLButtonElement | null>; open: boolean; onClose: () => void }) {
  return (
    <Popover anchor={anchor} open={open} onClose={onClose} placement="bottom-end" label="Profile actions">
      <Menu
        onClose={onClose}
        items={[
          { label: "Add content", icon: <Icon name="library" size={15} />, onSelect: () => navigate("library", p.id) },
          { label: "Rename", icon: <Icon name="edit" size={15} />, onSelect: () => actions.rename(p) },
          { label: "Duplicate", icon: <Icon name="copy" size={15} />, onSelect: () => actions.duplicate(p) },
          { label: "Open folder", icon: <Icon name="folder" size={15} />, onSelect: () => void api.revealFolder("profile", p.id) },
          { type: "separator" },
          { label: "Delete…", icon: <Icon name="trash" size={15} />, danger: true, onSelect: () => actions.remove(p) },
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
  const store = useStoreSelect((s) => ({ info: s.info, selectedId: s.selectedId, selected: s.selected }));
  const [menu, setMenu] = useState(false);
  const anchor = useRef<HTMLButtonElement>(null);
  const blocked = platformBlock(p.gameVersion, store.info);
  const selected = p.id === store.selectedId;
  const note = blocked ?? (!selected && store.selected ? `${store.selected.name} stays selected for Play until you choose this one.` : historyLine(p));

  return (
    <section className="pc-bar" aria-label={`${p.name} actions`}>
      <div className="pc-bar-actions">
        {selected ? (
          <Button variant="primary" icon="play" className="pc-bar-play" onClick={() => navigate("play")} disabled={!!blocked}>
            Go to Play
          </Button>
        ) : (
          <Button variant="primary" icon="check" className="pc-bar-play" onClick={onSelect} disabled={!!blocked}>
            Select for Play
          </Button>
        )}
        <Button icon="layers" onClick={onOpen}>
          Open profile
        </Button>
        <IconButton ref={anchor} icon="more" label="More actions" aria-haspopup="menu" aria-expanded={menu} onClick={() => setMenu((m) => !m)} />
        <ProfileMenu profile={p} navigate={navigate} actions={actions} anchor={anchor} open={menu} onClose={() => setMenu(false)} />
      </div>
      <p className={`pc-bar-note ${blocked ? "is-danger" : ""}`} title={note}>
        {note}
      </p>
    </section>
  );
}

function CreateProfile({ open, onClose, onCreated }: { open: boolean; onClose: () => void; onCreated: (id: string) => void }) {
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
      toast({ tone: "success", title: `Created ${profile.name}`, body: "Install it from Play, or add content first." });
      onClose();
      onCreated(profile.id);
    } catch (e) {
      toast({ tone: "danger", title: "Couldn't create the profile", body: errorText(e) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="New profile"
      width={680}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" onClick={() => void create()} loading={busy}>
            Create profile
          </Button>
        </>
      }
    >
      <div className="version-pick" role="radiogroup" aria-label="Minecraft version">
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
                <span className="faint">{blocked ? "Not on Apple silicon yet" : v.drop ?? (v.fabric ? "Fabric or Vanilla" : "Vanilla only")}</span>
              </span>
              {v.id === version && <Icon name="check" size={15} className="version-chip-check" />}
            </button>
          );
        })}
      </div>
      <div className="modal-row">
        <span className="modal-label">Loader</span>
        {meta.fabric ? (
          <Segmented
            label="Loader"
            value={loader}
            onChange={setLoader}
            options={[
              { value: "fabric", label: "Fabric" },
              { value: "vanilla", label: "Vanilla" },
            ]}
          />
        ) : (
          <span className="muted">{meta.note ?? "Fabric isn't available for this version."}</span>
        )}
      </div>
      <TextField label="Profile name" placeholder={defaultProfileName(version)} value={name} maxLength={40} onChange={(e) => setName(e.target.value)} onKeyDown={(e) => e.key === "Enter" && void create()} hint={`Java ${meta.java} is downloaded automatically.`} />
    </Modal>
  );
}

function RenameProfile({ profile, onClose }: { profile: Profile | null; onClose: () => void }) {
  const store = useStoreSelect((s) => ({ reloadProfiles: s.reloadProfiles }));
  const toast = useToast();
  const [name, setName] = useState("");
  useEffect(() => setName(profile?.name ?? ""), [profile]);
  const save = async () => {
    if (!profile || !name.trim()) return;
    try {
      await api.renameProfile(profile.id, name.trim());
      await store.reloadProfiles();
      onClose();
    } catch (e) {
      toast({ tone: "danger", title: "Couldn't rename", body: errorText(e) });
    }
  };
  return (
    <Modal
      open={!!profile}
      onClose={onClose}
      title="Rename profile"
      width={420}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" onClick={() => void save()} disabled={!name.trim()}>
            Save
          </Button>
        </>
      }
    >
      <TextField label="Profile name" value={name} maxLength={40} data-autofocus onChange={(e) => setName(e.target.value)} onKeyDown={(e) => e.key === "Enter" && void save()} />
    </Modal>
  );
}

function DuplicateProfile({ profile, onClose }: { profile: Profile | null; onClose: () => void }) {
  const store = useStoreSelect((s) => ({ reloadProfiles: s.reloadProfiles }));
  const toast = useToast();
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  useEffect(() => setName(profile ? `${profile.name} copy`.slice(0, 40) : ""), [profile]);
  const run = async () => {
    if (!profile || !name.trim()) return;
    setBusy(true);
    try {
      const copy = await api.duplicateProfile(profile.id, name.trim());
      await store.reloadProfiles();
      toast({ tone: "success", title: `Created ${copy.name}`, body: "Worlds, mods and settings were copied. The original wasn't changed." });
      onClose();
    } catch (e) {
      toast({ tone: "danger", title: "Couldn't duplicate", body: errorText(e) });
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal
      open={!!profile}
      onClose={onClose}
      title="Duplicate profile"
      width={460}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" icon="copy" onClick={() => void run()} loading={busy} disabled={!name.trim()}>
            Duplicate
          </Button>
        </>
      }
    >
      <p className="muted modal-copy">Copies worlds, mods, packs and settings into a new profile. Logs and crash reports aren't copied.</p>
      <TextField label="New profile name" value={name} maxLength={40} data-autofocus onChange={(e) => setName(e.target.value)} onKeyDown={(e) => e.key === "Enter" && void run()} />
    </Modal>
  );
}

function DeleteProfile({ profile, onClose, onDeleted }: { profile: Profile | null; onClose: () => void; onDeleted: (id: string) => void }) {
  const store = useStoreSelect((s) => ({ reloadProfiles: s.reloadProfiles }));
  const toast = useToast();
  const [mode, setMode] = useState<"record" | "files">("record");
  const [confirm, setConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  const [files, setFiles] = useState<ProfileFiles | null>(null);
  useEffect(() => {
    setMode("record");
    setConfirm("");
    setFiles(null);
    if (profile) void api.profileFiles(profile.id).then(setFiles).catch(() => {});
  }, [profile]);
  const folder = (id: string) => files?.folders.find((f) => f.id === id && f.exists);
  const contents = (["saves", "screenshots", "mods", "resourcepacks", "shaderpacks"] as const)
    .map((id) => ({ id, info: folder(id) }))
    .filter((x) => x.info && x.info.count > 0);
  const FOLDER_NAME: Record<string, string> = { saves: "Worlds", screenshots: "Screenshots", mods: "Mods", resourcepacks: "Resource packs", shaderpacks: "Shader packs" };
  const ready = mode === "record" || confirm.trim() === profile?.name;
  const run = async () => {
    if (!profile || !ready) return;
    setBusy(true);
    try {
      const result = await api.deleteProfile(profile.id, mode === "files");
      await store.reloadProfiles();
      onDeleted(profile.id);
      toast({
        tone: "success",
        title: `Removed ${profile.name}`,
        body: result.keptFolder ? "Its folder, worlds and mods were kept on disk." : "Its folder was deleted.",
      });
      onClose();
    } catch (e) {
      toast({ tone: "danger", title: "Couldn't remove the profile", body: errorText(e) });
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal
      open={!!profile}
      onClose={onClose}
      title={`Delete ${profile?.name ?? "profile"}?`}
      width={520}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="danger" icon="trash" onClick={() => void run()} loading={busy} disabled={!ready}>
            {mode === "files" ? "Delete profile and files" : "Remove from Flint"}
          </Button>
        </>
      }
    >
      <div className="delete-options" role="radiogroup" aria-label="What to delete">
        <button type="button" role="radio" aria-checked={mode === "record"} className={`delete-option ${mode === "record" ? "is-selected" : ""}`} onClick={() => setMode("record")}>
          <strong>Remove from Flint only</strong>
          <span className="muted">The profile disappears from Flint. Its folder with worlds, mods and screenshots stays on disk.</span>
        </button>
        <button type="button" role="radio" aria-checked={mode === "files"} className={`delete-option is-danger ${mode === "files" ? "is-selected" : ""}`} onClick={() => setMode("files")}>
          <strong>Also delete its folder</strong>
          <span className="muted">Permanently deletes this profile's worlds, mods, resource packs, settings and screenshots. This can't be undone.</span>
        </button>
      </div>
      {mode === "files" && profile && (
        <>
          <div className="delete-contents">
            <span className="eyebrow">This folder contains</span>
            {files === null ? (
              <Spinner size={14} />
            ) : contents.length === 0 ? (
              <span className="muted">No worlds, screenshots or content. {bytes(files.total)} in total.</span>
            ) : (
              <ul>
                {contents.map(({ id, info }) => (
                  <li key={id} className={id === "saves" || id === "screenshots" ? "is-precious" : ""}>
                    <strong>{FOLDER_NAME[id]}</strong>
                    <span className="faint">
                      {info!.count} · {bytes(info!.size)}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </div>
          <TextField label={`Type “${profile.name}” to confirm`} value={confirm} onChange={(e) => setConfirm(e.target.value)} autoComplete="off" spellCheck={false} />
        </>
      )}
    </Modal>
  );
}
