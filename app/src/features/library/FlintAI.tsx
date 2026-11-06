import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { formatDuration, quotaLabel, type Quota } from "../../lib/aiQuota";
import { api, errorText, type LibraryHit, type PlanItem, type Profile } from "../../lib/api";
import { QuotaExhausted, suggestMods, type AssistItem, type AssistResult, type AssistStage } from "../../lib/assist";
import { goalLabel, markMissing, selectPlan } from "../../lib/assistPlan";
import { bytes, list } from "../../lib/format";
import { formatLocale, useT, type MessageKey, type Translate } from "../../lib/i18n";
import { liveInstall } from "../../lib/livePacks";
import { sound } from "../../lib/sound";
import { useStoreSelect } from "../../lib/store";
import { loaderLabel } from "../../lib/versions";
import { onWindowFocus } from "../../lib/windowFocus";
import { Badge, Button, Checkbox, IconButton } from "../../ui/controls";
import { FlintMark } from "../../ui/FlintMark";
import { Icon } from "../../ui/Icon";
import { useToast } from "../../ui/overlays";
import { ProjectIcon } from "./InstallDialog";

/**
 * What players can type without knowing a mod's name. English on purpose: a click puts the example
 * in the request, and Flint's smart search (the answer whenever Flint AI isn't) only reads English.
 */
const EXAMPLES = [
  "The best FPS mods and some useful PvP mods",
  "Lower fire, a smaller shield and no pumpkin blur",
  "A mod that lets me zoom with a key",
  "My laptop is weak and Minecraft needs to run smoother",
  "Something that makes inventory management easier",
];

type Availability = "checking" | "ready" | "offline" | "off";

const STAGES: { id: AssistStage; label: MessageKey }[] = [
  { id: "connecting", label: "ai.stage.connecting" },
  { id: "thinking", label: "ai.stage.thinking" },
  { id: "matching", label: "ai.stage.matching" },
];

const KIND_NAME: Record<string, MessageKey> = {
  mod: "library.kind.mod",
  resourcepack: "library.kind.resourcepack",
  shader: "ai.kind.shaderPack",
  datapack: "library.kind.datapack",
};

type InspectKind = "mod" | "resourcepack" | "shader";

/** The label on a result card: Iris is a mod, but players know it as the shader loader. */
const kindLabel = (t: Translate, item: PlanItem) => (item.role === "shaderLoader" ? t("ai.kind.shaderLoader") : KIND_NAME[item.kind] ? t(KIND_NAME[item.kind]) : item.kind);

/**
 * Flint AI: describe what you want in your own words; Flint AI works out the goals, and Flint finds
 * real Modrinth projects, checks them against the profile's version and loader, resolves
 * dependencies and installs only what the player ticks. The same panel (and the same server-side
 * search allowance) serves the Library and each profile's Content tab.
 */
export default function FlintAI({
  profile,
  onClose,
  onInspect,
  onBrowse,
}: {
  profile: Profile;
  onClose: () => void;
  /** Opens a result's full Modrinth details. */
  onInspect?: (hit: LibraryHit, kind: InspectKind) => void;
  /** Continues in the normal Library search (free, no AI). */
  onBrowse?: (query: string) => void;
}) {
  const t = useT();
  const { configured, running } = useStoreSelect((s) => ({ configured: s.info.gatewayConfigured, running: s.states[profile.id]?.state === "running" }));
  const toast = useToast();
  const [availability, setAvailability] = useState<Availability>(configured ? "checking" : "off");
  const [quota, setQuota] = useState<Quota | null>(null);
  /** performance.now() when `quota.resetInMs` was measured, so the countdown never depends on the system clock. */
  const quotaAt = useRef(0);
  const [, setTick] = useState(0);
  const [prompt, setPrompt] = useState("");
  const [stage, setStage] = useState<AssistStage | null>(null);
  const [done, setDone] = useState<AssistStage[]>([]);
  const [result, setResult] = useState<AssistResult | null>(null);
  const [exhausted, setExhausted] = useState(false);
  const [picked, setPicked] = useState<Record<string, boolean>>({});
  const [error, setError] = useState<string | null>(null);
  const [installing, setInstalling] = useState(false);
  const [active, setActive] = useState(true);
  const [alternatives, setAlternatives] = useState<LibraryHit[] | null>(null);
  const requestRef = useRef<string | null>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const rootRef = useRef<HTMLElement>(null);
  const busy = stage !== null;

  useEffect(() => onWindowFocus(setActive), []);
  useEffect(() => inputRef.current?.focus(), []);
  useEffect(
    () => () => {
      if (requestRef.current) void api.aiCancel(requestRef.current);
    },
    [],
  );

  const applyQuota = useCallback((q: Quota | null | undefined) => {
    if (!q) return;
    quotaAt.current = performance.now();
    setQuota(q);
    setExhausted(q.remaining <= 0);
  }, []);

  // Availability is a health check only (no sign-in). The allowance is read once the panel is open,
  // which is the player's own action; it needs the Minecraft sign-in the gateway verifies.
  useEffect(() => {
    if (!configured) return;
    let live = true;
    void api
      .aiStatus()
      .then(async (s) => {
        if (!live) return;
        if (!s.reachable) return setAvailability("offline");
        setAvailability("ready");
        try {
          const q = await api.aiQuota();
          if (live) applyQuota(q);
        } catch {
          // The count shows after the first search instead.
        }
      })
      .catch(() => live && setAvailability("offline"));
    return () => {
      live = false;
    };
  }, [configured, applyQuota]);

  const msLeft = quota?.resetInMs != null ? Math.max(0, quota.resetInMs - (performance.now() - quotaAt.current)) : null;
  // A countdown is shown only when the allowance is used up: tick once a minute while visible.
  useEffect(() => {
    if (!exhausted || !active) return;
    const id = window.setInterval(() => setTick((n) => n + 1), 30_000);
    return () => window.clearInterval(id);
  }, [exhausted, active]);
  useEffect(() => {
    if (exhausted && msLeft === 0) {
      void api
        .aiQuota()
        .then(applyQuota)
        .catch(() => {});
    }
  }, [exhausted, msLeft, applyQuota]);

  // `t` is listed so the "Needs …" notes markMissing writes follow a language change.
  const items = useMemo<AssistItem[]>(() => markMissing(result?.plan.items ?? []), [result, t]);
  const { on: included, titles: includedTitles, forced } = useMemo(() => selectPlan(items, picked), [items, picked]);
  const chosen = items.filter((i) => included.has(i.projectId) && i.versionId && i.status === "ok");
  const size = chosen.reduce((sum, i) => sum + (i.size ?? 0), 0);
  // While the game runs, packs go into it at once (lib/livePacks.ts); mods need a restart.
  const live = liveInstall(running, chosen);
  // Out of searches: the panel keeps working with Flint's free smart search until the reset.
  const useAi = availability === "ready" && !exhausted;

  const run = async (opts: { smart?: boolean } = {}) => {
    const text = prompt.trim();
    if (text.length < 2 || busy) return;
    const ai = useAi && !opts.smart;
    const requestId = `fa-${crypto.randomUUID()}`;
    requestRef.current = requestId;
    setError(null);
    setResult(null);
    setAlternatives(null);
    setDone([]);
    setStage(ai ? "connecting" : "matching");
    sound.play("tap");
    try {
      const r = await suggestMods({ profileId: profile.id }, text, {
        aiEnabled: ai,
        requestId,
        loader: profile.loader,
        gameVersion: profile.gameVersion,
        onStage: (next) => {
          if (requestRef.current !== requestId) return;
          setStage((cur) => {
            if (cur && cur !== next) setDone((d) => (d.includes(cur) ? d : [...d, cur]));
            return next;
          });
        },
      });
      if (requestRef.current !== requestId) return;
      applyQuota(r.quota);
      setResult(r);
      setPicked(Object.fromEntries(r.plan.items.filter((i) => i.source !== "dependency").map((i) => [i.projectId, i.source !== "optional"])));
      sound.play(r.groups.length ? "success" : "tap");
      if (!r.groups.length) void findAlternatives(text, r.searchKind);
    } catch (e) {
      if (requestRef.current !== requestId) return;
      if (e instanceof QuotaExhausted) {
        applyQuota(e.quota);
        sound.play("error");
      } else if (errorText(e) !== "AI_CANCELLED") {
        setError(t("ai.error.unreachable", { error: errorText(e) }));
        sound.play("error");
      }
    } finally {
      if (requestRef.current === requestId) {
        requestRef.current = null;
        setStage(null);
      }
    }
  };

  /** Plain Modrinth matches for the words, shown honestly as alternatives when nothing fit. */
  const findAlternatives = async (text: string, kind: InspectKind) => {
    try {
      const r = await api.librarySearch({
        kind: profile.loader === "fabric" ? kind : "resourcepack",
        query: text.slice(0, 80),
        index: "relevance",
        offset: 0,
        limit: 3,
        gameVersion: profile.gameVersion,
        loader: profile.loader,
        categories: [],
      });
      setAlternatives(r.hits);
    } catch {
      setAlternatives([]);
    }
  };

  const stop = () => {
    if (requestRef.current) void api.aiCancel(requestRef.current);
    requestRef.current = null;
    setStage(null);
  };

  const install = async () => {
    if (!chosen.length || installing) return;
    setInstalling(true);
    try {
      await api.installContent(
        profile.id,
        chosen.length === 1 ? t("library.install.jobSingle", { title: chosen[0].title }) : t("ai.install.job", { count: chosen.length }),
        chosen.map((i) => ({
          versionId: i.versionId!,
          kind: i.kind,
          source: i.source,
          reason: i.reason,
        })),
        null,
      );
      sound.play("success");
      toast({
        tone: "neutral",
        title: t("ai.install.downloading", { count: chosen.length }),
        body: t("ai.install.downloadingBody", { profile: profile.name }),
      });
      onClose();
    } catch (e) {
      toast({
        tone: "danger",
        title: t("library.install.failed"),
        body: errorText(e),
      });
    } finally {
      setInstalling(false);
    }
  };

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Escape" && !e.defaultPrevented) {
      e.preventDefault();
      if (busy) stop();
      else onClose();
    }
  };

  const target = `${profile.name} · Minecraft ${profile.gameVersion} · ${loaderLabel(profile.loader)}`;
  const groups = result?.groups.filter((g) => g.items.some((i) => i.status !== "not-needed")) ?? [];
  const unmatched = result?.plan.unmatched?.length ?? 0;
  let index = 0;

  return (
    <section ref={rootRef} className={`flint-ai ${active ? "" : "is-inactive"}`} aria-label="Flint AI" onKeyDown={onKeyDown}>
      <header className="fa-head">
        <FlintMark working={busy} size={30} />
        <div className="fa-title">
          <h2>Flint AI</h2>
          <p className="faint">{t("ai.panel.intro", { profile: profile.name })}</p>
        </div>
        {quota && useAi && <span className={`fa-quota ${quota.remaining === 0 ? "is-empty" : ""}`}>{quotaLabel(quota, msLeft)}</span>}
        <IconButton icon="x" label={t("ai.panel.close")} onClick={onClose} />
      </header>

      {exhausted && quota ? (
        <div className="fa-exhausted" role="status">
          <FlintMark size={40} />
          <h3>{t("ai.exhausted.title", { count: quota.limit })}</h3>
          <p className="muted">
            {msLeft
              ? t.rich("ai.exhausted.resetsAt", {
                  duration: <strong>{formatDuration(msLeft)}</strong>,
                  time: new Date(Date.now() + msLeft).toLocaleTimeString(formatLocale(), {
                    hour: "numeric",
                    minute: "2-digit",
                  }),
                })
              : t("ai.exhausted.resetsSoon")}{" "}
            {t("ai.exhausted.libraryFree")}
          </p>
          {onBrowse && (
            <div className="fa-row">
              <Button variant="secondary" icon="search" onClick={() => onBrowse(prompt.trim())}>
                {t("ai.exhausted.browse")}
              </Button>
            </div>
          )}
        </div>
      ) : null}
      <div className="fa-ask">
        <div className="fa-input">
          <textarea
            ref={inputRef}
            rows={2}
            maxLength={600}
            value={prompt}
            disabled={busy}
            placeholder={t(exhausted ? "ai.ask.placeholderSmart" : "ai.ask.placeholder")}
            aria-label={t("ai.ask.label")}
            onChange={(e) => setPrompt(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
                e.preventDefault();
                void run();
              }
            }}
          />
          {busy ? (
            <Button variant="secondary" icon="x" onClick={stop}>
              {t("ai.ask.stop")}
            </Button>
          ) : (
            <Button variant="primary" icon="sparkle" disabled={prompt.trim().length < 2} onClick={() => void run()}>
              {t(useAi ? "ai.ask.submit" : "common.search")}
            </Button>
          )}
        </div>
        {!result && !busy && (
          <div className="fa-examples" aria-label={t("ai.ask.examples")}>
            {EXAMPLES.map((ex) => (
              <button key={ex} type="button" className="fa-example" onClick={() => (setPrompt(ex), inputRef.current?.focus())}>
                {ex}
              </button>
            ))}
          </div>
        )}
        <p className="fa-meta faint">
          <span>{t("ai.ask.forTarget", { target })}</span>
          {exhausted && <span>{t("ai.ask.smartOnly")}</span>}
          {availability === "offline" && <span className="fa-warn">{t("ai.ask.offline")}</span>}
          {availability === "off" && <span>{t("ai.ask.off")}</span>}
        </p>
      </div>

      {busy && (
        <ol className="fa-stages" role="status" aria-live="polite">
          {STAGES.filter((s) => useAi || s.id === "matching").map((s) => (
            <li key={s.id} className={done.includes(s.id) ? "is-done" : stage === s.id ? "is-current" : ""}>
              <span className="fa-dot" aria-hidden>
                {done.includes(s.id) && <Icon name="check" size={11} />}
              </span>
              {t(s.label)}
            </li>
          ))}
        </ol>
      )}

      {error && (
        <p className="install-warn" role="alert">
          <Icon name="alert" size={15} />
          {error}
          <Button size="sm" variant="ghost" onClick={() => void run()}>
            {t("common.tryAgain")}
          </Button>
        </p>
      )}

      {result && !busy && (
        <div className="fa-results">
          <p className="fa-summary">
            {result.mode === "ai" ? <Badge tone="accent">Flint AI</Badge> : <Badge>{t("ai.results.smartBadge")}</Badge>}
            <span className="faint">
              {[
                result.mode === "fallback" && result.reason && useAi ? t("ai.results.fallback", { reason: result.reason }) : "",
                result.lookup ? "" : result.goals.length ? t("ai.results.understood", { goals: result.goals.join(", ") }) : "",
                result.note ?? "",
                result.needsFabric.length ? t("ai.results.needsFabric", { goals: result.needsFabric.join(", "), profile: profile.name }) : "",
                unmatched ? t("ai.results.unmatched", { count: unmatched, version: profile.gameVersion }) : "",
              ]
                .filter(Boolean)
                .join(" ")}
            </span>
          </p>

          {result.lookup && (
            <p className={`fa-lookup ${result.lookup.identified ? "" : "is-unsure"}`} role="status">
              <Icon name={result.lookup.identified ? "search" : "alert"} size={14} />
              {lookupText(t, result.lookup)}
              {result.lookup.identified ? ` ${t("ai.lookup.possibleNote")}` : groups.length ? ` ${t("ai.lookup.closestNote")}` : ""}
            </p>
          )}

          {groups.length === 0 ? (
            <div className="fa-empty">
              <h3>{t("ai.empty.title")}</h3>
              <p className="muted">{t("ai.empty.body", { version: profile.gameVersion, loader: loaderLabel(profile.loader) })}</p>
              {alternatives && alternatives.length > 0 && (
                <>
                  <p className="eyebrow">{t("ai.empty.alternatives")}</p>
                  <ul className="fa-alts">
                    {alternatives.map((hit) => (
                      <li key={hit.projectId}>
                        <button type="button" onClick={() => onInspect?.(hit, profile.loader === "fabric" ? result.searchKind : "resourcepack")} disabled={!onInspect}>
                          <ProjectIcon src={hit.iconUrl} title={hit.title} kind={hit.projectType} size={28} />
                          <span>
                            <strong>{hit.title}</strong>
                            <span className="faint">{hit.description}</span>
                          </span>
                        </button>
                      </li>
                    ))}
                  </ul>
                </>
              )}
              {onBrowse && (
                <Button variant="secondary" icon="search" onClick={() => onBrowse(prompt.trim())}>
                  {t("ai.empty.search")}
                </Button>
              )}
            </div>
          ) : (
            groups.map((g) => (
              <section key={g.id} className={`fa-group is-${g.kind}`}>
                <header>
                  <span className="eyebrow">{goalLabel(g.title)}</span>
                  {g.kind === "dependency" && <span className="faint">{t("ai.group.dependencyHint")}</span>}
                  {g.kind === "optional" && <span className="faint">{t("ai.group.optionalHint")}</span>}
                </header>
                {g.why && <p className="fa-why">{g.why}</p>}
                <ul>
                  {g.items
                    .filter((i) => i.status !== "not-needed")
                    .map((item) => {
                      const locked =
                        g.kind === "dependency" ||
                        forced.has(item.projectId) ||
                        (g.kind === "optional" && !!item.requiredBy && !includedTitles.has(item.requiredBy));
                      return (
                        <ResultCard
                          key={`${item.kind}-${item.projectId}`}
                          item={item}
                          index={index++}
                          compact={g.kind === "dependency"}
                          profile={profile}
                          checked={included.has(item.projectId)}
                          locked={locked}
                          onToggle={
                            locked
                              ? undefined
                              : (v) =>
                                  setPicked((p) => ({
                                    ...p,
                                    [item.projectId]: v,
                                  }))
                          }
                          onInspect={onInspect}
                        />
                      );
                    })}
                </ul>
              </section>
            ))
          )}

          {groups.length > 0 && (
            <footer className="fa-foot">
              <span className="faint">{chosen.length ? t("ai.foot.summary", { count: chosen.length, size: bytes(size) }) : t("ai.foot.nothing")}</span>
              {live.state === "blocked" && <span className="fa-warn">{t(live.reason === "iris" ? "library.install.runningIris" : "library.install.runningMods")}</span>}
              {live.state === "live" && chosen.length > 0 && <span className="fa-live">{t("ai.foot.live")}</span>}
              <Button variant="primary" icon="download" loading={installing} disabled={!chosen.length || live.state === "blocked"} onClick={() => void install()}>
                {t("ai.foot.install", { profile: profile.name })}
              </Button>
            </footer>
          )}
        </div>
      )}

      <p className="fa-privacy faint">
        <Icon name="lock" size={12} />
        {t(useAi ? "ai.privacy.ai" : "ai.privacy.smart")}
      </p>
    </section>
  );
}

/** "Flint looked for one project that matches lower fire and no fog, starting with “L”.": what a lookup searched for. */
function lookupText(t: Translate, lookup: { identified: boolean; features: string[]; nameHint: string | null }): string {
  const features = list(lookup.features);
  const hint = lookup.nameHint;
  if (!hint) return t(lookup.identified ? "ai.lookup.found" : "ai.lookup.unsure", { features });
  if (hint.length === 1) return t(lookup.identified ? "ai.lookup.foundLetter" : "ai.lookup.unsureLetter", { features, letter: hint.toUpperCase() });
  return t(lookup.identified ? "ai.lookup.foundName" : "ai.lookup.unsureName", { features, name: hint });
}

function ResultCard({
  item,
  index,
  compact,
  profile,
  checked,
  locked,
  onToggle,
  onInspect,
}: {
  item: AssistItem;
  index: number;
  compact: boolean;
  profile: Profile;
  checked: boolean;
  locked: boolean;
  onToggle?: (v: boolean) => void;
  onInspect?: (hit: LibraryHit, kind: InspectKind) => void;
}) {
  const t = useT();
  const blocked = item.status === "unavailable" || item.status === "conflict";
  const inspect =
    onInspect && (item.kind === "mod" || item.kind === "resourcepack" || item.kind === "shader")
      ? () =>
          onInspect(
            {
              projectId: item.projectId,
              slug: item.slug,
              title: item.title,
              description: item.description ?? "",
              iconUrl: item.iconUrl,
              author: item.author ?? "",
              downloads: 0,
              follows: 0,
              projectType: item.kind,
            },
            item.kind as InspectKind,
          )
      : undefined;
  return (
    <li className={`fa-card ${compact ? "is-compact" : ""} status-${item.status}`} style={{ "--i": Math.min(index, 10) } as React.CSSProperties}>
      <ProjectIcon src={item.iconUrl} title={item.title} kind={item.kind} size={compact ? 28 : 40} />
      <span className="fa-card-text">
        <span className="fa-card-title">
          {inspect ? (
            <button type="button" className="fa-link" onClick={inspect}>
              {item.title}
            </button>
          ) : (
            <strong>{item.title}</strong>
          )}
          {item.author && <span className="faint">{t("library.card.by", { author: item.author })}</span>}
          <span className="fa-kind">{kindLabel(t, item)}</span>
        </span>
        {compact && item.requiredBy && (
          <span className="fa-needed faint">{t("ai.card.neededBy", { titles: item.requiredByAll?.length ? item.requiredByAll.join(", ") : item.requiredBy })}</span>
        )}
        {!compact && item.description && <span className="fa-desc">{item.description}</span>}
        {!compact && item.match && <span className="fa-match">{item.match}</span>}
        <span className={`fa-compat ${blocked ? "is-bad" : ""}`}>
          {blocked ? (
            <>
              <Icon name="alert" size={12} />
              {item.message ?? t("ai.card.noVersion", { version: profile.gameVersion })}
            </>
          ) : item.status === "installed" ? (
            <>
              <Icon name="check" size={12} />
              {t("library.plan.alreadyInstalled")}
            </>
          ) : (
            <>
              <Icon name="check" size={12} />
              {item.versionNumber ? `${item.versionNumber} · ` : ""}Minecraft {profile.gameVersion}
              {item.kind === "mod" ? ` · ${loaderLabel(profile.loader)}` : item.kind === "shader" ? " · Iris" : ""}
              {item.size ? ` · ${bytes(item.size)}` : ""}
            </>
          )}
        </span>
      </span>
      {item.status === "ok" && (onToggle || locked) ? (
        <Checkbox checked={checked} disabled={locked} onChange={onToggle ?? (() => {})} label={t("library.plan.include", { title: item.title })} />
      ) : null}
    </li>
  );
}
