import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { quotaLabel, type Quota } from "../../lib/aiQuota";
import { api, errorText, type LibraryHit, type PlanItem, type Profile } from "../../lib/api";
import { QuotaExhausted, suggestMods, type AssistItem, type AssistResult, type AssistStage } from "../../lib/assist";
import { markMissing, selectPlan } from "../../lib/assistPlan";
import { bytes } from "../../lib/format";
import { sound } from "../../lib/sound";
import { useStoreSelect } from "../../lib/store";
import { loaderLabel } from "../../lib/versions";
import { onWindowFocus } from "../../lib/windowFocus";
import { Badge, Button, Checkbox, IconButton } from "../../ui/controls";
import { FlintMark } from "../../ui/FlintMark";
import { Icon } from "../../ui/Icon";
import { useToast } from "../../ui/overlays";
import { ProjectIcon } from "./InstallDialog";

/** What players can type without knowing a mod's name. */
const EXAMPLES = [
  "The best FPS mods and some useful PvP mods",
  "Lower fire, a smaller shield and no pumpkin blur",
  "A mod that lets me zoom with a key",
  "My laptop is weak and Minecraft needs to run smoother",
  "Something that makes inventory management easier",
];

type Availability = "checking" | "ready" | "offline" | "off";

const STAGES: { id: AssistStage; label: string }[] = [
  { id: "connecting", label: "Connecting to Flint AI" },
  { id: "thinking", label: "Understanding your request" },
  { id: "matching", label: "Searching Modrinth and checking compatibility" },
];

const KIND_NAME: Record<string, string> = {
  mod: "Mod",
  resourcepack: "Resource pack",
  shader: "Shader pack",
  datapack: "Data pack",
};

type InspectKind = "mod" | "resourcepack" | "shader";

/** The label on a result card: Iris is a mod, but players know it as the shader loader. */
const kindLabel = (item: PlanItem) => (item.role === "shaderLoader" ? "Shader loader" : (KIND_NAME[item.kind] ?? item.kind));

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
    const id = window.setInterval(() => setTick((t) => t + 1), 30_000);
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

  const items = useMemo<AssistItem[]>(() => markMissing(result?.plan.items ?? []), [result]);
  const { on: included, titles: includedTitles, forced } = useMemo(() => selectPlan(items, picked), [items, picked]);
  const chosen = items.filter((i) => included.has(i.projectId) && i.versionId && i.status === "ok");
  const size = chosen.reduce((sum, i) => sum + (i.size ?? 0), 0);
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
        setError(`Flint couldn't reach Modrinth (${errorText(e)}). Check your connection and try again.`);
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
        chosen.length === 1 ? `Installing ${chosen[0].title}` : `Installing ${chosen.length} items from Flint AI`,
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
        title: `Downloading ${chosen.length} item${chosen.length === 1 ? "" : "s"}`,
        body: `Each file is verified before it's added to ${profile.name}.`,
      });
      onClose();
    } catch (e) {
      toast({
        tone: "danger",
        title: "Couldn't start the install",
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
          <p className="faint">Describe what you want in your own words. Flint finds real mods, resource packs and shader packs that work with {profile.name}.</p>
        </div>
        {quota && useAi && <span className={`fa-quota ${quota.remaining === 0 ? "is-empty" : ""}`}>{quotaLabel(quota, msLeft)}</span>}
        <IconButton icon="x" label="Close Flint AI" onClick={onClose} />
      </header>

      {exhausted && quota ? (
        <div className="fa-exhausted" role="status">
          <FlintMark size={40} />
          <h3>You've used your {quota.limit} Flint AI searches</h3>
          <p className="muted">
            {msLeft ? (
              <>
                They reset in <strong>{quotaLabel({ ...quota, remaining: 0 }, msLeft).replace("Your searches reset in ", "")}</strong>, at{" "}
                {new Date(Date.now() + msLeft).toLocaleTimeString([], {
                  hour: "numeric",
                  minute: "2-digit",
                })}
                .
              </>
            ) : (
              "They reset soon."
            )}{" "}
            Browsing and installing from the Library stays free.
          </p>
          {onBrowse && (
            <div className="fa-row">
              <Button variant="secondary" icon="search" onClick={() => onBrowse(prompt.trim())}>
                Browse the Library
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
            placeholder={exhausted ? "Flint's smart search still works: describe what you want" : "e.g. lower fire and a smaller shield, plus a zoom key"}
            aria-label="Describe what you want"
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
              Stop
            </Button>
          ) : (
            <Button variant="primary" icon="sparkle" disabled={prompt.trim().length < 2} onClick={() => void run()}>
              {useAi ? "Ask Flint AI" : "Search"}
            </Button>
          )}
        </div>
        {!result && !busy && (
          <div className="fa-examples" aria-label="Examples">
            {EXAMPLES.map((ex) => (
              <button key={ex} type="button" className="fa-example" onClick={() => (setPrompt(ex), inputRef.current?.focus())}>
                {ex}
              </button>
            ))}
          </div>
        )}
        <p className="fa-meta faint">
          <span>For {target}</span>
          {exhausted && <span>Searching with Flint's smart search (no AI, unlimited).</span>}
          {availability === "offline" && <span className="fa-warn">Flint AI is unreachable right now, so Flint's own smart search answers instead.</span>}
          {availability === "off" && <span>Flint AI isn't available in this build; Flint's smart search answers instead.</span>}
        </p>
      </div>

      {busy && (
        <ol className="fa-stages" role="status" aria-live="polite">
          {STAGES.filter((s) => useAi || s.id === "matching").map((s) => (
            <li key={s.id} className={done.includes(s.id) ? "is-done" : stage === s.id ? "is-current" : ""}>
              <span className="fa-dot" aria-hidden>
                {done.includes(s.id) && <Icon name="check" size={11} />}
              </span>
              {s.label}
            </li>
          ))}
        </ol>
      )}

      {error && (
        <p className="install-warn" role="alert">
          <Icon name="alert" size={15} />
          {error}
          <Button size="sm" variant="ghost" onClick={() => void run()}>
            Try again
          </Button>
        </p>
      )}

      {result && !busy && (
        <div className="fa-results">
          <p className="fa-summary">
            {result.mode === "ai" ? <Badge tone="accent">Flint AI</Badge> : <Badge>Flint smart search</Badge>}
            <span className="faint">
              {[
                result.mode === "fallback" && result.reason && useAi ? `${result.reason}, so Flint's smart search answered (no AI).` : "",
                result.lookup ? "" : result.goals.length ? `Understood: ${result.goals.join(", ")}.` : "",
                result.note ?? "",
                result.needsFabric.length
                  ? `${result.needsFabric.join(", ")} need mods, and ${profile.name} runs vanilla Minecraft: create a Fabric profile for those.`
                  : "",
                unmatched ? `${unmatched} ${unmatched === 1 ? "idea had" : "ideas had"} no compatible match for Minecraft ${profile.gameVersion}.` : "",
              ]
                .filter(Boolean)
                .join(" ")}
            </span>
          </p>

          {result.lookup && (
            <p className={`fa-lookup ${result.lookup.identified ? "" : "is-unsure"}`} role="status">
              <Icon name={result.lookup.identified ? "search" : "alert"} size={14} />
              {result.lookup.identified
                ? `Flint looked for one project that ${describeLookup(result.lookup)}. These are possible matches, not certain ones: check each description.`
                : `Flint couldn't identify the project that ${describeLookup(result.lookup)} reliably.${groups.length ? " These are the closest it found." : ""}`}
            </p>
          )}

          {groups.length === 0 ? (
            <div className="fa-empty">
              <h3>No compatible match turned up</h3>
              <p className="muted">
                Flint only shows projects it could verify for Minecraft {profile.gameVersion} with {loaderLabel(profile.loader)}. Try describing it differently,
                or look through the Library.
              </p>
              {alternatives && alternatives.length > 0 && (
                <>
                  <p className="eyebrow">Closest on Modrinth for your words · not checked against your request</p>
                  <ul className="fa-alts">
                    {alternatives.map((hit) => (
                      <li key={hit.projectId}>
                        <button type="button" onClick={() => onInspect?.(hit, profile.loader === "fabric" ? result.searchKind : "resourcepack")} disabled={!onInspect}>
                          <ProjectIcon src={hit.iconUrl} title={hit.title} size={28} />
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
                  Search the Library
                </Button>
              )}
            </div>
          ) : (
            groups.map((g) => (
              <section key={g.id} className={`fa-group is-${g.kind}`}>
                <header>
                  <span className="eyebrow">{g.title}</span>
                  {g.kind === "dependency" && <span className="faint">Installed with the picks that need them</span>}
                  {g.kind === "optional" && <span className="faint">Off unless you choose them</span>}
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
              <span className="faint">{chosen.length ? `${chosen.length} file${chosen.length === 1 ? "" : "s"} · ${bytes(size)}` : "Nothing selected"}</span>
              {running && <span className="fa-warn">Close Minecraft before changing this profile.</span>}
              <Button variant="primary" icon="download" loading={installing} disabled={!chosen.length || running} onClick={() => void install()}>
                Install to {profile.name}
              </Button>
            </footer>
          )}
        </div>
      )}

      <p className="fa-privacy faint">
        <Icon name="lock" size={12} />
        {useAi
          ? "Your request, Minecraft version and installed project names go to Flint AI on Cloudflare. Your Minecraft username only proves you own the game; your sign-in is never sent. Every result comes from Modrinth and is checked for this profile."
          : "Flint's smart search runs on this computer and only searches Modrinth. Every result is checked for this profile."}
      </p>
    </section>
  );
}

/** "lowers fire, low shield and no fog": the features a lookup searched for, as one phrase. */
function describeLookup(lookup: { features: string[]; nameHint: string | null }): string {
  const f = lookup.features;
  const list = f.length > 1 ? `${f.slice(0, -1).join(", ")} and ${f[f.length - 1]}` : (f[0] ?? "you described");
  const hint = lookup.nameHint ? (lookup.nameHint.length === 1 ? `, starting with “${lookup.nameHint.toUpperCase()}”` : `, named something like “${lookup.nameHint}”`) : "";
  return `matches ${list}${hint}`;
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
  item: PlanItem;
  index: number;
  compact: boolean;
  profile: Profile;
  checked: boolean;
  locked: boolean;
  onToggle?: (v: boolean) => void;
  onInspect?: (hit: LibraryHit, kind: InspectKind) => void;
}) {
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
      <ProjectIcon src={item.iconUrl} title={item.title} size={compact ? 28 : 40} />
      <span className="fa-card-text">
        <span className="fa-card-title">
          {inspect ? (
            <button type="button" className="fa-link" onClick={inspect}>
              {item.title}
            </button>
          ) : (
            <strong>{item.title}</strong>
          )}
          {item.author && <span className="faint">by {item.author}</span>}
          <span className="fa-kind">{kindLabel(item)}</span>
        </span>
        {compact && item.requiredBy && <span className="fa-needed faint">Needed by {item.requiredByAll?.length ? item.requiredByAll.join(", ") : item.requiredBy}</span>}
        {!compact && item.description && <span className="fa-desc">{item.description}</span>}
        {!compact && item.reason && /^(Possible|Closest) match/.test(item.reason) && <span className="fa-match">{item.reason.replace(/^(Possible|Closest) match — /, "")}</span>}
        <span className={`fa-compat ${blocked ? "is-bad" : ""}`}>
          {blocked ? (
            <>
              <Icon name="alert" size={12} />
              {item.message ?? `No version for Minecraft ${profile.gameVersion}`}
            </>
          ) : item.status === "installed" ? (
            <>
              <Icon name="check" size={12} />
              Already installed
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
        <Checkbox checked={checked} disabled={locked} onChange={onToggle ?? (() => {})} label={`Include ${item.title}`} />
      ) : null}
    </li>
  );
}
