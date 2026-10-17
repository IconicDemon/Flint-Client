import { useEffect, useMemo, useState } from "react";
import { api, errorText, type ContentKind, type LibraryKind, type ModPlan, type PlanItem, type Profile, type World } from "../../lib/api";
import { bytes } from "../../lib/format";
import { useStoreSelect } from "../../lib/store";
import { loaderLabel } from "../../lib/versions";
import { Badge, Button, Checkbox, Spinner, TextField } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { Dropdown, Modal, useToast } from "../../ui/overlays";

export type InstallTarget = {
  kind: LibraryKind;
  projectId: string;
  title: string;
  iconUrl?: string | null;
  versionId?: string | null;
  versionNumber?: string | null;
  /** A curated set of projects; ones without a build for the profile are left out instead of blocking. */
  preset?: { project: string; reason: string }[];
};

/** Client-side Fabric mods that reduce stutter, memory use and idle power. Each is checked against the profile before install. */
export const PERFORMANCE_PRESET: InstallTarget = {
  kind: "mod",
  projectId: "preset:performance",
  title: "Performance mods",
  preset: [
    { project: "sodium", reason: "Faster chunk rendering" },
    { project: "lithium", reason: "Lighter game logic" },
    { project: "ferrite-core", reason: "Lower memory use" },
    { project: "immediatelyfast", reason: "Faster HUD and text rendering" },
    { project: "entityculling", reason: "Skips drawing hidden entities" },
    { project: "dynamic-fps", reason: "Throttles the game while it's in the background" },
  ],
};

export const KIND_LABEL: Record<LibraryKind, string> = {
  modpack: "Modpack",
  mod: "Mod",
  resourcepack: "Resource pack",
  shader: "Shader",
  datapack: "Data pack",
};

export function ProjectIcon({ src, title, size = 44 }: { src?: string | null; title: string; size?: number }) {
  const [broken, setBroken] = useState(false);
  return (
    <span className="project-icon" style={{ width: size, height: size }} aria-hidden>
      {src && !broken ? <img src={src} alt="" loading="lazy" onError={() => setBroken(true)} /> : <span>{title.slice(0, 1).toUpperCase()}</span>}
    </span>
  );
}

/** Where installed content goes and what the player has to do afterwards. */
export function placementNote(kind: LibraryKind): string {
  switch (kind) {
    case "mod":
      return "Goes into this profile's mods folder and loads the next time you play.";
    case "resourcepack":
      return "Goes into resourcepacks. Turn it on in Minecraft under Options → Resource Packs.";
    case "shader":
      return "Goes into shaderpacks and needs Iris, which Flint adds if it's missing. Pick it in Options → Video Settings → Shader Packs.";
    case "datapack":
      return "Goes into the datapacks folder of the world you choose. It applies the next time that world loads.";
    case "modpack":
      return "Creates a brand-new profile. Your existing profiles aren't touched.";
  }
}

function rowNote(item: PlanItem): string | null {
  if (item.message) return item.message;
  if (item.status === "installed") return "Already installed";
  if (item.source === "dependency" && item.requiredByAll?.length) return `Required by ${item.requiredByAll.join(", ")}`;
  if (item.source === "dependency" && item.requiredBy) return `Required by ${item.requiredBy}`;
  if (item.source === "optional" && item.requiredBy) return `Optional for ${item.requiredBy}`;
  return item.reason ?? null;
}

/** One checked plan row. `locked` shows the checkbox state without letting it change (e.g. a required dependency). */
/** `summary`: also show the project's own Modrinth summary (used where Flint suggested the pick). */
export function PlanRow({ item, checked, onToggle, locked, summary }: { item: PlanItem; checked: boolean; onToggle?: (v: boolean) => void; locked?: boolean; summary?: boolean }) {
  const blocked = item.status === "unavailable" || item.status === "conflict";
  return (
    <li className={`plan-row status-${item.status}`}>
      <ProjectIcon src={item.iconUrl} title={item.title} size={36} />
      <span className="plan-text">
        <strong>{item.title}</strong>
        {summary && item.description && <span className="plan-summary">{item.description}</span>}
        <span className="faint">
          {[item.versionNumber, item.size ? bytes(item.size) : null, rowNote(item)].filter(Boolean).join(" · ")}
        </span>
      </span>
      {item.kind !== "mod" && <Badge>{KIND_LABEL[item.kind]}</Badge>}
      {item.status === "installed" ? (
        <Badge tone="success">Installed</Badge>
      ) : item.status === "not-needed" ? (
        <Badge>Not needed</Badge>
      ) : blocked ? (
        <Badge tone="danger">{item.status === "conflict" ? "Conflict" : "Unavailable"}</Badge>
      ) : onToggle || locked ? (
        <Checkbox checked={checked} disabled={locked} onChange={onToggle ?? (() => {})} label={`Include ${item.title}`} />
      ) : (
        <Icon name="check" size={16} className="plan-check" />
      )}
    </li>
  );
}

export function InstallDialog({ target, profile, onClose }: { target: InstallTarget | null; profile: Profile | null; onClose: () => void }) {
  const toast = useToast();
  const running = useStoreSelect((s) => (profile ? s.states[profile.id]?.state === "running" : false));
  const [plan, setPlan] = useState<ModPlan | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [optional, setOptional] = useState<Record<string, boolean>>({});
  const [worlds, setWorlds] = useState<World[] | null>(null);
  const [world, setWorld] = useState<string>("");
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const isPack = target?.kind === "modpack";
  const needsFabric = !!target && (target.kind === "mod" || target.kind === "shader") && profile?.loader !== "fabric";

  useEffect(() => {
    setPlan(null);
    setError(null);
    setOptional({});
    setWorlds(null);
    setWorld("");
    setName(target?.title ?? "");
    if (!target || isPack || !profile || needsFabric) return;
    let live = true;
    const requests = target.preset
      ? target.preset.map((p) => ({ kind: "mod" as const, project: p.project, versionId: null, source: "user" as const }))
      : [{ kind: target.kind as ContentKind, project: target.projectId, versionId: target.versionId ?? null, source: "user" as const }];
    void api
      .planContent(profile.id, requests)
      .then((p) => {
        if (!live) return;
        if (target.preset) {
          const why = new Map(target.preset.map((x) => [x.project, x.reason]));
          p = { ...p, items: p.items.map((i) => (i.source === "user" && why.has(i.slug) ? { ...i, reason: why.get(i.slug) ?? i.reason } : i)) };
        }
        setPlan(p);
      })
      .catch((e) => live && setError(errorText(e)));
    if (target.kind === "datapack") {
      void api
        .worlds(profile.id)
        .then((w) => {
          if (!live) return;
          setWorlds(w);
          setWorld(w[0]?.id ?? "");
        })
        .catch(() => live && setWorlds([]));
    }
    return () => {
      live = false;
    };
  }, [target, profile, isPack, needsFabric]);

  const groups = useMemo(() => {
    const items = plan?.items ?? [];
    return {
      requested: items.filter((i) => i.source === "user"),
      required: items.filter((i) => i.source === "dependency"),
      optional: items.filter((i) => i.source === "optional"),
    };
  }, [plan]);

  const chosen = (plan?.items ?? []).filter((i) => i.status === "ok" && (i.source !== "optional" || optional[i.projectId]));
  const presetBlockers = (plan?.items ?? []).filter((i) => i.source === "dependency" && (i.status === "unavailable" || i.status === "conflict")).map((i) => `${i.title}: ${i.message ?? "unavailable"}`);
  const blockers = target?.preset ? presetBlockers : (plan?.blockers ?? []);
  const blocked = blockers.length > 0;
  const leftOut = target?.preset ? (plan?.items ?? []).filter((i) => i.source === "user" && (i.status === "unavailable" || i.status === "conflict")).length : 0;
  const downloadSize = chosen.reduce((sum, i) => sum + (i.size ?? 0), 0);

  const confirm = async () => {
    if (!target) return;
    setBusy(true);
    try {
      if (isPack) {
        await api.installModpack(target.projectId, target.versionId ?? null, name.trim() || null, `Installing ${target.title}`);
        toast({ tone: "neutral", title: `Downloading ${target.title}`, body: "It becomes a new profile when every file is verified." });
      } else if (profile) {
        await api.installContent(
          profile.id,
          target.preset ? `Installing ${chosen.length} ${target.title.toLowerCase()}` : chosen.length === 1 ? `Installing ${chosen[0].title}` : `Installing ${target.title} + ${chosen.length - 1} more`,
          chosen.map((i) => ({ versionId: i.versionId!, kind: i.kind, source: i.source, reason: i.reason })),
          target.kind === "datapack" ? world : null,
        );
      }
      onClose();
    } catch (e) {
      toast({ tone: "danger", title: "Couldn't start the install", body: errorText(e) });
    } finally {
      setBusy(false);
    }
  };

  const nothing = !!plan && chosen.length === 0;
  const canInstall = isPack ? true : !!plan && !blocked && !nothing && !running && (target?.kind !== "datapack" || !!world);

  return (
    <Modal
      open={!!target}
      onClose={onClose}
      title={isPack ? `Install ${target?.title ?? "modpack"}` : `Add ${target?.title ?? ""}`}
      width={600}
      footer={
        <>
          <span className="modal-foot-note faint">{!isPack && plan && chosen.length > 0 ? `${chosen.length} file${chosen.length === 1 ? "" : "s"} · ${bytes(downloadSize)}` : ""}</span>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" icon="download" onClick={() => void confirm()} loading={busy} disabled={!canInstall}>
            {isPack ? "Create profile" : nothing ? "Nothing to install" : `Install${chosen.length > 1 ? ` ${chosen.length}` : ""}`}
          </Button>
        </>
      }
    >
      {target && (
        <div className="install-dialog">
          <div className="install-target">
            <ProjectIcon src={target.iconUrl} title={target.title} size={48} />
            <span>
              <strong>{target.title}</strong>
              <span className="faint">
                {KIND_LABEL[target.kind]}
                {target.versionNumber ? ` · ${target.versionNumber}` : ""}
                {!isPack && profile ? ` → ${profile.name} (Minecraft ${profile.gameVersion} · ${loaderLabel(profile.loader)})` : ""}
              </span>
            </span>
          </div>
          <p className="install-note">
            <Icon name="info" size={15} />
            {placementNote(target.kind)}
          </p>

          {isPack ? (
            <TextField label="Profile name" value={name} maxLength={40} onChange={(e) => setName(e.target.value)} hint="Files are checked against Modrinth's hashes before the profile is created." />
          ) : !profile ? (
            <p className="field-error">Choose a profile at the top of the Library first.</p>
          ) : needsFabric ? (
            <p className="install-warn">
              <Icon name="alert" size={15} />
              {profile.name} runs vanilla Minecraft. {target.kind === "shader" ? "Shaders need Iris, which needs Fabric." : "Mods need Fabric."} Create a Fabric profile to add this.
            </p>
          ) : error ? (
            <p className="install-warn">
              <Icon name="alert" size={15} />
              {error}
            </p>
          ) : !plan ? (
            <div className="install-loading">
              <Spinner /> Checking versions and dependencies…
            </div>
          ) : (
            <>
              {running && (
                <p className="install-warn">
                  <Icon name="alert" size={15} />
                  Close Minecraft before changing this profile.
                </p>
              )}
              {target.preset && (
                <p className="install-note">
                  <Icon name="gauge" size={15} />
                  These mods are widely used to cut stutter, memory use and background power draw. How much they help depends on your computer and world; they don't guarantee a particular frame rate.
                  {leftOut > 0 && ` ${leftOut} without a build for Minecraft ${profile.gameVersion} will be left out.`}
                </p>
              )}
              {blocked && (
                <div className="install-warn is-block">
                  <Icon name="alert" size={15} />
                  <div>
                    <strong>This can't be installed yet</strong>
                    <ul>
                      {blockers.map((b) => (
                        <li key={b}>{b}</li>
                      ))}
                    </ul>
                  </div>
                </div>
              )}
              {target.kind === "datapack" &&
                (worlds === null ? (
                  <Spinner />
                ) : worlds.length === 0 ? (
                  <p className="install-warn">
                    <Icon name="globe" size={15} />
                    This profile has no worlds yet. Play once and create a world, then add the data pack to it.
                  </p>
                ) : (
                  <div className="modal-row">
                    <span className="modal-label">World</span>
                    <Dropdown label="World" value={world} onChange={setWorld} options={worlds.map((w) => ({ value: w.id, label: w.name, hint: w.datapacks ? `${w.datapacks} packs` : undefined }))} />
                  </div>
                ))}
              <PlanGroup title="You're adding" items={groups.requested} />
              <PlanGroup title="Required to work" hint="Installed automatically" items={groups.required} />
              <PlanGroup
                title="Optional extras"
                hint="Off unless you choose them"
                items={groups.optional}
                optional={optional}
                onToggle={(id, v) => setOptional((o) => ({ ...o, [id]: v }))}
              />
            </>
          )}
        </div>
      )}
    </Modal>
  );
}

function PlanGroup({
  title,
  hint,
  items,
  optional,
  onToggle,
}: {
  title: string;
  hint?: string;
  items: PlanItem[];
  optional?: Record<string, boolean>;
  onToggle?: (projectId: string, value: boolean) => void;
}) {
  if (items.length === 0) return null;
  return (
    <section className="plan-group">
      <header>
        <span className="eyebrow">{title}</span>
        {hint && <span className="faint">{hint}</span>}
      </header>
      <ul>
        {items.map((item) => (
          <PlanRow key={`${item.kind}-${item.projectId}`} item={item} checked={!!optional?.[item.projectId]} onToggle={onToggle ? (v) => onToggle(item.projectId, v) : undefined} />
        ))}
      </ul>
    </section>
  );
}
