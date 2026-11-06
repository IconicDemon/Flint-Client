import { useEffect, useMemo, useState } from "react";
import { api, errorText, type ContentKind, type LibraryKind, type ModPlan, type PlanItem, type Profile, type World } from "../../lib/api";
import { bytes } from "../../lib/format";
import { t, useT, type MessageKey, type PluralKey } from "../../lib/i18n";
import { liveInstall } from "../../lib/livePacks";
import { useStoreSelect } from "../../lib/store";
import { loaderLabel } from "../../lib/versions";
import { Badge, Button, Checkbox, Spinner, TextField } from "../../ui/controls";
import { Icon, type IconName } from "../../ui/Icon";
import { Dropdown, Modal, useToast } from "../../ui/overlays";

export type InstallTarget = {
  kind: LibraryKind;
  projectId: string;
  title: string;
  iconUrl?: string | null;
  versionId?: string | null;
  versionNumber?: string | null;
  /** The symbol shown in place of a Modrinth icon, for Flint's own sets (which have none). */
  icon?: IconName;
  /** A curated set of projects; ones without a build for the profile are left out instead of blocking. `reason` shows on each row. */
  preset?: { project: string; reason: MessageKey }[];
  /** A set's install job title, by how many of its projects are installed ("Installing 6 performance mods"). */
  presetJob?: PluralKey;
};

/** Client-side Fabric mods that reduce stutter, memory use and idle power. Each is checked against the profile before install. */
export const PERFORMANCE_PRESET: InstallTarget = {
  kind: "mod",
  projectId: "preset:performance",
  // Read when shown, so the set's name follows the interface language.
  get title() {
    return t("library.preset.performance.title");
  },
  icon: "gauge",
  preset: [
    { project: "sodium", reason: "library.preset.performance.sodium" },
    { project: "lithium", reason: "library.preset.performance.lithium" },
    { project: "ferrite-core", reason: "library.preset.performance.ferriteCore" },
    { project: "immediatelyfast", reason: "library.preset.performance.immediatelyFast" },
    { project: "entityculling", reason: "library.preset.performance.entityCulling" },
    { project: "dynamic-fps", reason: "library.preset.performance.dynamicFps" },
  ],
  presetJob: "library.preset.performance.installing",
};

/** Each kind's name, in the interface language when read (`KIND_LABEL[kind]` while rendering). */
export const KIND_LABEL: Readonly<Record<LibraryKind, string>> = {
  get modpack() {
    return t("library.kind.modpack");
  },
  get mod() {
    return t("library.kind.mod");
  },
  get resourcepack() {
    return t("library.kind.resourcepack");
  },
  get shader() {
    return t("library.kind.shader");
  },
  get datapack() {
    return t("library.kind.datapack");
  },
};

/** The symbol a project without an icon of its own shows, by Modrinth project type. */
const KIND_ICON: Record<string, IconName> = { mod: "puzzle", resourcepack: "image", shader: "sun", datapack: "database", modpack: "box" };

/**
 * A project's Modrinth icon. Without one (or when it fails to load) it shows a symbol for what the
 * project is (`kind`), or `icon` for Flint's own sets, which get the accent tint.
 */
/** `fallback`: a second picture to try if `src` doesn't load (a mod's own logo when Modrinth's can't be reached). */
export function ProjectIcon({ src, fallback, title, size = 44, kind, icon }: { src?: string | null; fallback?: string | null; title: string; size?: number; kind?: string; icon?: IconName }) {
  const sources = [src, fallback].filter((s, i, all): s is string => !!s && all.indexOf(s) === i);
  const [failed, setFailed] = useState(0);
  const current = sources[failed];
  const showImage = !!current;
  return (
    <span className={`project-icon ${showImage ? "" : "is-fallback"} ${!showImage && icon ? "is-feature" : ""}`} style={{ width: size, height: size }} aria-hidden title={showImage ? undefined : title}>
      {showImage ? <img key={current} src={current} alt="" loading="lazy" onError={() => setFailed((n) => n + 1)} /> : <Icon name={icon ?? (kind ? KIND_ICON[kind] : undefined) ?? "cube"} size={Math.max(14, Math.round(size * 0.46))} />}
    </span>
  );
}

/** Where installed content goes and what the player has to do afterwards. */
export function placementNote(kind: LibraryKind): string {
  switch (kind) {
    case "mod":
      return t("library.placement.mod");
    case "resourcepack":
      return t("library.placement.resourcepack");
    case "shader":
      return t("library.placement.shader");
    case "datapack":
      return t("library.placement.datapack");
    case "modpack":
      return t("library.placement.modpack");
  }
}

function rowNote(item: PlanItem): string | null {
  if (item.message) return item.message;
  if (item.status === "installed") return t("library.plan.alreadyInstalled");
  if (item.source === "dependency" && item.requiredByAll?.length) return t("library.plan.requiredBy", { titles: item.requiredByAll.join(", ") });
  if (item.source === "dependency" && item.requiredBy) return t("library.plan.requiredBy", { titles: item.requiredBy });
  if (item.source === "optional" && item.requiredBy) return t("library.plan.optionalFor", { title: item.requiredBy });
  return item.reason ?? null;
}

/** One checked plan row. `locked` shows the checkbox state without letting it change (e.g. a required dependency). */
/** `summary`: also show the project's own Modrinth summary (used where Flint suggested the pick). */
export function PlanRow({ item, checked, onToggle, locked, summary }: { item: PlanItem; checked: boolean; onToggle?: (v: boolean) => void; locked?: boolean; summary?: boolean }) {
  const t = useT();
  const blocked = item.status === "unavailable" || item.status === "conflict";
  return (
    <li className={`plan-row status-${item.status}`}>
      <ProjectIcon src={item.iconUrl} title={item.title} kind={item.kind} size={36} />
      <span className="plan-text">
        <strong>{item.title}</strong>
        {summary && item.description && <span className="plan-summary">{item.description}</span>}
        <span className="faint">
          {[item.versionNumber, item.size ? bytes(item.size) : null, rowNote(item)].filter(Boolean).join(" · ")}
        </span>
      </span>
      {item.kind !== "mod" && <Badge>{KIND_LABEL[item.kind]}</Badge>}
      {item.status === "installed" ? (
        <Badge tone="success">{t("library.card.installed")}</Badge>
      ) : item.status === "not-needed" ? (
        <Badge>{t("library.plan.notNeeded")}</Badge>
      ) : blocked ? (
        <Badge tone="danger">{t(item.status === "conflict" ? "library.plan.conflict" : "library.plan.unavailable")}</Badge>
      ) : onToggle || locked ? (
        <Checkbox checked={checked} disabled={locked} onChange={onToggle ?? (() => {})} label={t("library.plan.include", { title: item.title })} />
      ) : (
        <Icon name="check" size={16} className="plan-check" />
      )}
    </li>
  );
}

export function InstallDialog({ target, profile, onClose }: { target: InstallTarget | null; profile: Profile | null; onClose: () => void }) {
  const t = useT();
  const toast = useToast();
  const running = useStoreSelect((s) => (profile ? s.states[profile.id]?.state === "running" : false));
  const [plan, setPlan] = useState<ModPlan | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [optional, setOptional] = useState<Record<string, boolean>>({});
  const [worlds, setWorlds] = useState<World[] | null>(null);
  const [world, setWorld] = useState<string>("");
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  // While the profile's game runs: turn the packs on in it as they arrive (on by default).
  const [enableLive, setEnableLive] = useState(true);
  const isPack = target?.kind === "modpack";
  const needsFabric = !!target && (target.kind === "mod" || target.kind === "shader") && profile?.loader !== "fabric";
  // A pack for the running game's profile goes into the game at once (lib/livePacks.ts); mods need a restart.
  const livePackTarget = running && !!target && !target.preset && (target.kind === "resourcepack" || target.kind === "shader" || target.kind === "datapack");

  useEffect(() => {
    setPlan(null);
    setError(null);
    setOptional({});
    setWorlds(null);
    setWorld("");
    setEnableLive(true);
    setName(target?.title ?? "");
    if (!target || isPack || !profile || needsFabric) return;
    let live = true;
    const requests = target.preset
      ? target.preset.map((p) => ({ kind: "mod" as const, project: p.project, versionId: null, source: "user" as const }))
      : [{ kind: target.kind as ContentKind, project: target.projectId, versionId: target.versionId ?? null, source: "user" as const }];
    void api
      .planContent(profile.id, requests)
      .then((p) => live && setPlan(p))
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

  // A set's own picks carry its reason for each ("Faster chunk rendering"), in the interface language.
  const items = useMemo(() => {
    const all = plan?.items ?? [];
    const why = new Map(target?.preset?.map((x) => [x.project, x.reason]));
    return all.map((i) => {
      const reason = i.source === "user" ? why.get(i.slug) : undefined;
      return reason ? { ...i, reason: t(reason) } : i;
    });
  }, [plan, target, t]);

  const groups = useMemo(
    () => ({
      requested: items.filter((i) => i.source === "user"),
      required: items.filter((i) => i.source === "dependency"),
      optional: items.filter((i) => i.source === "optional"),
    }),
    [items],
  );

  const chosen = items.filter((i) => i.status === "ok" && (i.source !== "optional" || optional[i.projectId]));
  const presetBlockers = items
    .filter((i) => i.source === "dependency" && (i.status === "unavailable" || i.status === "conflict"))
    .map((i) => t("library.install.blocker", { title: i.title, reason: i.message ?? t("library.install.blockerUnavailable") }));
  const blockers = target?.preset ? presetBlockers : (plan?.blockers ?? []);
  const blocked = blockers.length > 0;
  const leftOut = target?.preset ? items.filter((i) => i.source === "user" && (i.status === "unavailable" || i.status === "conflict")).length : 0;
  const downloadSize = chosen.reduce((sum, i) => sum + (i.size ?? 0), 0);
  const inGame = liveInstall(running, plan ? chosen : target && !target.preset ? [{ kind: target.kind === "modpack" ? "mod" : target.kind, projectId: target.projectId }] : []);

  const confirm = async () => {
    if (!target) return;
    setBusy(true);
    try {
      if (isPack) {
        // English on purpose: the jobs panel reads the pack's name back out of this title (Shell.tsx).
        await api.installModpack(target.projectId, target.versionId ?? null, name.trim() || null, `Installing ${target.title}`);
        toast({ tone: "neutral", title: t("library.install.downloadingPack", { title: target.title }), body: t("library.install.downloadingPackBody") });
      } else if (profile) {
        await api.installContent(
          profile.id,
          target.preset && target.presetJob
            ? t(target.presetJob, { count: chosen.length })
            : chosen.length === 1
              ? t("library.install.jobSingle", { title: chosen[0].title })
              : t("library.install.jobMore", { title: target.title, count: chosen.length - 1 }),
          chosen.map((i) => ({ versionId: i.versionId!, kind: i.kind, source: i.source, reason: i.reason })),
          target.kind === "datapack" ? world : null,
          enableLive,
        );
      }
      onClose();
    } catch (e) {
      toast({ tone: "danger", title: t("library.install.failed"), body: errorText(e) });
    } finally {
      setBusy(false);
    }
  };

  const nothing = !!plan && chosen.length === 0;
  const canInstall = isPack ? true : !!plan && !blocked && !nothing && inGame.state !== "blocked" && (target?.kind !== "datapack" || !!world);
  // The game is running and this goes into it now: the note says so instead of where to turn it on in Minecraft. A game
  // without Flint's runtime (vanilla, or the in-game menu switched off) lists it but turns nothing on by itself.
  const showLive = livePackTarget && inGame.state !== "blocked";
  const gameApplies = plan?.gameApplies !== false;

  return (
    <Modal
      open={!!target}
      onClose={onClose}
      title={t(isPack ? "library.install.titlePack" : "library.install.titleAdd", { title: target?.title ?? "" })}
      width={600}
      footer={
        <>
          <span className="modal-foot-note faint">{!isPack && plan && chosen.length > 0 ? t("library.install.summary", { count: chosen.length, size: bytes(downloadSize) }) : ""}</span>
          <Button variant="ghost" onClick={onClose}>
            {t("common.cancel")}
          </Button>
          <Button variant="primary" icon="download" onClick={() => void confirm()} loading={busy} disabled={!canInstall}>
            {isPack
              ? t("library.install.createProfile")
              : nothing
                ? t("library.install.nothing")
                : chosen.length > 1
                  ? t("library.install.confirmCount", { count: chosen.length })
                  : t("library.card.install")}
          </Button>
        </>
      }
    >
      {target && (
        <div className="install-dialog">
          <div className="install-target">
            <ProjectIcon src={target.iconUrl} title={target.title} kind={target.kind} icon={target.icon} size={48} />
            <span>
              <strong>{target.title}</strong>
              <span className="faint">
                {KIND_LABEL[target.kind]}
                {target.versionNumber ? ` · ${target.versionNumber}` : ""}
                {!isPack && profile ? ` → ${profile.name} (Minecraft ${profile.gameVersion} · ${loaderLabel(profile.loader)})` : ""}
              </span>
            </span>
          </div>
          {showLive ? (
            <div className="install-live">
              <p>
                <span className="install-live-dot" aria-hidden />
                {target.kind === "datapack"
                  ? t("library.install.liveDatapack")
                  : gameApplies
                    ? t("library.install.liveOne")
                    : t(target.kind === "shader" ? "library.install.liveManualShader" : "library.install.liveManualResourcepack")}
              </p>
              {target.kind !== "datapack" && gameApplies && (
                <Checkbox checked={enableLive} onChange={setEnableLive}>
                  {t("library.install.liveEnable")}
                </Checkbox>
              )}
            </div>
          ) : (
            <p className="install-note">
              <Icon name="info" size={15} />
              {placementNote(target.kind)}
            </p>
          )}

          {isPack ? (
            <TextField label={t("library.install.profileName")} value={name} maxLength={40} onChange={(e) => setName(e.target.value)} hint={t("library.install.profileNameHint")} />
          ) : !profile ? (
            <p className="field-error">{t("library.install.chooseProfile")}</p>
          ) : needsFabric ? (
            <p className="install-warn">
              <Icon name="alert" size={15} />
              {t(target.kind === "shader" ? "library.install.vanillaShaders" : "library.install.vanillaMods", { profile: profile.name })}
            </p>
          ) : error ? (
            <p className="install-warn">
              <Icon name="alert" size={15} />
              {error}
            </p>
          ) : !plan ? (
            <div className="install-loading">
              <Spinner /> {t("library.install.checking")}
            </div>
          ) : (
            <>
              {inGame.state === "blocked" && (
                <p className="install-warn">
                  <Icon name="alert" size={15} />
                  {t(inGame.reason === "iris" ? "library.install.runningIris" : "library.install.runningMods")}
                </p>
              )}
              {target.preset && (
                <p className="install-note">
                  <Icon name="gauge" size={15} />
                  {t("library.install.presetNote")}
                  {leftOut > 0 && ` ${t("library.install.leftOut", { count: leftOut, version: profile.gameVersion })}`}
                </p>
              )}
              {blocked && (
                <div className="install-warn is-block">
                  <Icon name="alert" size={15} />
                  <div>
                    <strong>{t("library.install.blocked")}</strong>
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
                    {t("library.install.noWorlds")}
                  </p>
                ) : (
                  <div className="modal-row">
                    <span className="modal-label">{t("library.install.world")}</span>
                    <Dropdown
                      label={t("library.install.world")}
                      value={world}
                      onChange={setWorld}
                      options={worlds.map((w) => ({ value: w.id, label: w.name, hint: w.datapacks ? t("library.install.worldPacks", { count: w.datapacks }) : undefined }))}
                    />
                  </div>
                ))}
              <PlanGroup title={t("library.install.adding")} items={groups.requested} />
              <PlanGroup title={t("library.install.required")} hint={t("library.install.requiredHint")} items={groups.required} />
              <PlanGroup
                title={t("library.install.optional")}
                hint={t("library.install.optionalHint")}
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
