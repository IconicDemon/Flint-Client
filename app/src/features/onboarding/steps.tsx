import { useEffect, useRef, useState, type ReactNode } from "react";
import { api, errorText, subscribe, type Account, type Catalog, type LibraryHit } from "../../lib/api";
import { formatDuration, quotaLabel } from "../../lib/aiQuota";
import { EXAMPLES, QuotaExhausted, suggestMods } from "../../lib/assist";
import { groupPlan, type DisplayGroup } from "../../lib/assistPlan";
import { bodyFromSkin, headFromSkin } from "../../lib/avatar";
import { bytes, compact, dashedUuid, shortDate } from "../../lib/format";
import { EFFECTS, useSettings } from "../../lib/settings";
import { sound } from "../../lib/sound";
import { useStore } from "../../lib/store";
import { VERSIONS, defaultProfileName, platformBlock, versionMeta } from "../../lib/versions";
import { FontPicker, ThemePicker } from "../../ui/AppearancePickers";
import { Badge, Button, Checkbox, RadioCards, Segmented, Slider, Spinner, Switch, TextArea, TextField } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { FlintMark } from "../../ui/Logo";
import { Modal } from "../../ui/overlays";
import { PERFORMANCE_SET, compatibility, joinResolved, mergePlan, pickRequests, planSelection, rebasePlan, withoutPick, type PlanEntry } from "./plan";

export type { PlanEntry };

export type Answers = {
  accountUuid: string | null;
  version: string;
  profileName: string;
  nameTouched: boolean;
  assistPrompt: string;
  assistMode: "ai" | "fallback" | "skipped" | null;
  plan: PlanEntry[];
  /** The Minecraft version the plan was checked for; a different version re-checks it. */
  planVersion?: string | null;
  launchAtLogin: boolean;
  community: "pending" | "opened" | "skipped";
};

export const DEFAULT_ANSWERS: Answers = {
  accountUuid: null,
  version: "26.3",
  profileName: defaultProfileName("26.3"),
  nameTouched: false,
  assistPrompt: "",
  assistMode: null,
  plan: [],
  launchAtLogin: false,
  community: "pending",
};

export type StepProps = {
  answers: Answers;
  /** A patch, or a function of the latest answers (for results that arrive after a wait). */
  set: (patch: Partial<Answers> | ((a: Answers) => Partial<Answers>)) => void;
  setCanContinue: (ok: boolean) => void;
};

function StepHead({ eyebrow, title, body }: { eyebrow?: string; title: string; body?: ReactNode }) {
  return (
    <header className="ob-head">
      {eyebrow && <p className="eyebrow">{eyebrow}</p>}
      <h1>{title}</h1>
      {body && <p className="ob-sub">{body}</p>}
    </header>
  );
}

export function useHead(account: Account | null) {
  const { skins } = useStore();
  const [head, setHead] = useState<string | null>(null);
  const skin = account ? skins[account.uuid]?.dataUrl : null;
  useEffect(() => {
    setHead(null);
    if (skin) void headFromSkin(skin, 96).then(setHead);
  }, [skin]);
  return head;
}

export function Avatar({ account, size = 40 }: { account: Account | null; size?: number }) {
  const head = useHead(account);
  return (
    <span className="avatar" style={{ width: size, height: size }}>
      {head ? <img src={head} alt="" /> : <span>{account?.username.slice(0, 1).toUpperCase() ?? "?"}</span>}
    </span>
  );
}

// ---------------- Welcome ----------------

const WELCOME_STEPS = ["Sign in with Microsoft", "Choose a Minecraft version", "Pick your mods"];

export function WelcomeStep({ setCanContinue, onStart }: StepProps & { onStart: () => void }) {
  useEffect(() => setCanContinue(true), [setCanContinue]);
  return (
    <div className="ob-welcome">
      <div className="ob-welcome-emblem" aria-hidden>
        <span className="ob-welcome-grid" />
        <span className="ob-welcome-tile">
          <FlintMark size={68} />
        </span>
        <span className="ob-welcome-floor" />
      </div>
      <h1 className="ob-welcome-title">Welcome to Flint.</h1>
      <p className="ob-welcome-lede">Your Minecraft experience, made simpler.</p>
      <p className="ob-welcome-body">
        Flint prepares Minecraft: Java Edition for you: the game files, the right Java and your mods, each downloaded from official sources and checked before it runs.
      </p>
      <ol className="ob-welcome-steps">
        {WELCOME_STEPS.map((label, i) => (
          <li key={label}>
            <span>{i + 1}</span>
            {label}
          </li>
        ))}
      </ol>
      <Button variant="primary" size="lg" iconRight="arrowRight" onClick={onStart} className="ob-start" data-autofocus>
        Get started
      </Button>
      <p className="ob-welcome-legal">Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.</p>
    </div>
  );
}

// ---------------- Account ----------------

const AUTH_STAGES: { key: string; label: string }[] = [
  { key: "browser", label: "Sign in on Microsoft's page" },
  { key: "exchange", label: "Confirm the Microsoft sign-in" },
  { key: "xbox", label: "Connect to Xbox Live" },
  { key: "minecraft", label: "Sign in to Minecraft services" },
  { key: "ownership", label: "Check Java Edition ownership" },
  { key: "profile", label: "Load your Minecraft profile" },
];

function AccountReveal({ account, fresh }: { account: Account; fresh: boolean }) {
  const { skins } = useStore();
  const skin = skins[account.uuid];
  const [body, setBody] = useState<string | null>(null);
  useEffect(() => {
    setBody(null);
    if (skin?.dataUrl) void bodyFromSkin(skin.dataUrl, skin.model === "slim").then(setBody);
  }, [skin?.dataUrl, skin?.model]);
  return (
    <div className={`ob-reveal ${fresh ? "is-fresh" : ""}`}>
      <div className="ob-reveal-figure">{body ? <img src={body} alt="" /> : <Avatar account={account} size={72} />}</div>
      <div className="ob-reveal-text">
        <span className="ob-reveal-kicker">Signed in as</span>
        <strong>{account.username}</strong>
        <span className="mono faint">{dashedUuid(account.uuid)}</span>
        {import.meta.env.DEV && account.qa ? (
          <Badge tone="warning">
            <Icon name="alert" size={12} /> Development test identity
          </Badge>
        ) : (
          <Badge tone="success">
            <Icon name="check" size={12} /> Owns Minecraft: Java Edition
          </Badge>
        )}
      </div>
    </div>
  );
}

export function AccountStep({ answers, set, setCanContinue }: StepProps) {
  const store = useStore();
  const account = store.accounts.find((a) => a.uuid === answers.accountUuid) ?? null;
  const [phase, setPhase] = useState<"idle" | "working" | "error" | "cancelled">("idle");
  const [stage, setStage] = useState("browser");
  const [error, setError] = useState("");
  const [fresh, setFresh] = useState(false);
  const stageIndex = Math.max(0, AUTH_STAGES.findIndex((s) => s.key === stage));

  useEffect(() => setCanContinue(!!account && phase !== "working"), [account, phase, setCanContinue]);
  useEffect(() => subscribe<{ stage: string }>("auth-status", (e) => setStage(e.stage)), []);

  const connect = async () => {
    setPhase("working");
    setStage("browser");
    setError("");
    try {
      const result = await api.signIn();
      await store.reloadAccounts();
      set({ accountUuid: result.uuid });
      setFresh(true);
      setPhase("idle");
      sound.play("success");
    } catch (e) {
      const message = errorText(e);
      if (/cancel/i.test(message)) {
        setPhase("cancelled");
        return;
      }
      setError(message);
      setPhase("error");
      sound.play("error");
    }
  };

  const useTestIdentity = async () => {
    const result = await api.qaAccount();
    await store.reloadAccounts();
    set({ accountUuid: result.uuid });
  };

  return (
    <div className="ob-step ob-narrow ob-account-step">
      <StepHead
        eyebrow="Account"
        title={account && phase !== "working" ? `Good to see you, ${account.username}.` : "Connect your Minecraft account."}
        body={
          account && phase !== "working"
            ? "Your account is ready. Continue when you are."
            : "Use the Microsoft account that owns Minecraft: Java Edition. Microsoft handles the sign-in on its own page, so Flint never sees your password."
        }
      />
      <section className="ob-signin card">
        {phase === "working" ? (
          <div className="ob-signin-progress" role="status" aria-live="polite">
            <ol>
              {AUTH_STAGES.map((s, i) => (
                <li key={s.key} className={i < stageIndex ? "is-done" : i === stageIndex ? "is-current" : ""}>
                  <span className="ob-signin-dot">{i < stageIndex ? <Icon name="check" size={11} /> : i === stageIndex ? <Spinner size={12} /> : null}</span>
                  {s.label}
                </li>
              ))}
            </ol>
            <div className="ob-signin-foot">
              <p className="muted">{stage === "browser" ? "A Microsoft page opened in your browser. Come back here when you've finished." : "Almost there. This only takes a moment."}</p>
              {stage === "browser" && (
                <Button variant="secondary" size="sm" onClick={() => void api.cancelSignIn()}>
                  Cancel
                </Button>
              )}
            </div>
          </div>
        ) : account ? (
          <AccountReveal account={account} fresh={fresh} />
        ) : (
          <div className="ob-signin-start">
            <div className="ob-signin-brand">
              <span className="ob-signin-ms" aria-hidden>
                <Icon name="microsoft" size={22} />
              </span>
              <div>
                <strong>Microsoft handles your sign-in</strong>
                <span className="muted">You'll sign in on Microsoft's secure page in your browser, then come back here.</span>
              </div>
            </div>
            <button type="button" className="ob-ms-btn" onClick={() => void connect()}>
              <Icon name="microsoft" size={18} />
              Sign in with Microsoft
            </button>
            {phase === "cancelled" && <p className="ob-signin-note muted">Sign-in was cancelled. You can try again whenever you're ready.</p>}
            {phase === "error" && (
              <div className="ob-error" role="alert">
                <Icon name="alert" size={16} />
                <span>{error}</span>
                <Button size="sm" variant="secondary" icon="refresh" onClick={() => void connect()}>
                  Try again
                </Button>
              </div>
            )}
          </div>
        )}
        <ul className="ob-access">
          <li>
            <Icon name="check" size={13} /> Flint receives your Minecraft username, player ID and skin.
          </li>
          <li>
            <Icon name="lock" size={13} /> Sign-in tokens stay in your system keychain, never in Flint's files.
          </li>
          <li>
            <Icon name="x" size={13} /> Flint never sees your password or email, and can't make purchases.
          </li>
        </ul>
      </section>
      {account && phase !== "working" && (
        <button className="ob-link" type="button" onClick={() => void connect()}>
          Use a different account
        </button>
      )}
      {import.meta.env.DEV && store.info.qa && !account && phase !== "working" && (
        <button className="ob-link ob-qa" type="button" onClick={() => void useTestIdentity()}>
          Development build: continue with a local test identity
        </button>
      )}
    </div>
  );
}

// ---------------- Version ----------------

export function VersionStep({ answers, set, setCanContinue }: StepProps) {
  const [catalog, setCatalog] = useState<Catalog | null>(null);
  const [catalogError, setCatalogError] = useState(false);
  useEffect(() => {
    api.catalog().then(setCatalog, () => setCatalogError(true));
  }, []);
  const store = useStore();
  const nameOk = answers.profileName.trim().length > 0;
  const selectedBlocked = platformBlock(answers.version, store.info);
  useEffect(() => setCanContinue(nameOk && !selectedBlocked), [nameOk, selectedBlocked, setCanContinue]);

  const pick = (id: string) => {
    sound.play("toggle");
    set({ version: id, ...(answers.nameTouched ? {} : { profileName: defaultProfileName(id) }) });
  };

  return (
    <div className="ob-step ob-wide">
      <StepHead title="Which Minecraft version would you like to play?" body="Each version gets its own profile, folder and mods. You can add more later." />
      <div className="ob-versions" role="radiogroup" aria-label="Minecraft version">
        {VERSIONS.map((v) => {
          const live = catalog?.versions.find((c) => c.id === v.id);
          const fabric = live ? live.fabric : v.fabric;
          const selected = answers.version === v.id;
          const blocked = platformBlock(v.id, store.info);
          return (
            <button
              key={v.id}
              type="button"
              role="radio"
              aria-checked={selected}
              className={`ob-version ${selected ? "is-selected" : ""}`}
              onClick={() => pick(v.id)}
              disabled={!!blocked}
              title={blocked ?? undefined}
            >
              <img src={v.thumb} alt="" loading="lazy" />
              <span className="ob-version-shade" />
              <span className="ob-version-body">
                <span className="ob-version-id">{v.id}</span>
                <span className="ob-version-drop">{v.drop ?? (live?.releaseTime ? `Released ${shortDate(live.releaseTime)}` : "Minecraft release")}</span>
                <span className="ob-version-tags">
                  {blocked ? (
                    <Badge tone="warning">Not on Apple silicon yet</Badge>
                  ) : (
                    <>
                      {fabric ? <Badge tone="accent">Fabric</Badge> : <Badge>Vanilla only</Badge>}
                      <Badge>Java {v.java}</Badge>
                    </>
                  )}
                </span>
              </span>
              {selected && (
                <span className="ob-version-check">
                  <Icon name="check" size={14} />
                </span>
              )}
            </button>
          );
        })}
      </div>
      <p className="ob-verify faint" role="status">
        {catalog ? (
          catalog.offline ? (
            <>
              <Icon name="wifiOff" size={14} /> Offline: showing the last verified list. Flint checks again during setup.
            </>
          ) : (
            <>
              <Icon name="check" size={14} /> Verified just now with Mojang and Fabric{catalog.fabricLoader ? ` (Fabric Loader ${catalog.fabricLoader})` : ""}.
            </>
          )
        ) : catalogError ? (
          <>
            <Icon name="wifiOff" size={14} /> Couldn't reach Mojang or Fabric. Availability will be checked during setup.
          </>
        ) : (
          <>
            <Spinner size={12} /> Checking availability with Mojang and Fabric…
          </>
        )}
      </p>
      {versionMeta(answers.version).note && <p className="ob-note">{versionMeta(answers.version).note}</p>}
      <div className="ob-name">
        <TextField
          label="Profile name"
          value={answers.profileName}
          maxLength={40}
          onChange={(e) => set({ profileName: e.target.value, nameTouched: true })}
          hint="This names the setup in Flint. It's separate from your Minecraft username."
          error={nameOk ? undefined : "Give your profile a name."}
        />
      </div>
    </div>
  );
}

// ---------------- Mods ----------------

/** One plan row. `included` says whether it installs as things stand; `parentOn` whether an extra's mod is included. */
function ModRow({
  item,
  gameVersion,
  included,
  forced,
  parentOn,
  includedTitles,
  onToggle,
  onRemove,
  onReplace,
}: {
  item: PlanEntry;
  gameVersion: string;
  included: boolean;
  forced: boolean;
  parentOn: boolean;
  includedTitles: ReadonlySet<string>;
  onToggle: (v: boolean) => void;
  onRemove: () => void;
  onReplace: () => void;
}) {
  const blocked = item.status === "conflict" || item.status === "unavailable";
  const dependency = item.source === "dependency";
  const optional = item.source === "optional";
  const needers = item.requiredByAll?.length ? item.requiredByAll : item.requiredBy ? [item.requiredBy] : ["another mod"];
  const neededNow = needers.filter((t) => includedTitles.has(t));
  const neededLater = needers.filter((t) => !includedTitles.has(t));
  const note = blocked
    ? item.message
    : dependency
      ? included
        ? `Needed by ${neededNow.join(", ")}${neededLater.length ? `, and by ${neededLater.join(", ")} if included` : ""}`
        : `Needed by ${needers.join(", ")}. Installs only if ${needers.length > 1 ? "one of them" : "that"} is included.`
      : optional
        ? !parentOn
          ? `Optional add-on for ${item.requiredBy ?? "a mod"}, which isn't included.`
          : included
            ? `Optional add-on for ${item.requiredBy ?? "your mods"}. Included because you ticked it.`
            : `Optional add-on for ${item.requiredBy ?? "your mods"}. Off unless you tick it.`
        : forced
          ? `Needed by another included mod${item.reason ? ` · ${item.reason}` : ""}`
          : (item.reason ?? item.description);
  const compat = item.status === "ok" ? compatibility(item, gameVersion) : null;
  // Dependencies follow the mods that need them; a pick another included mod needs can't be cleared.
  const checked = !blocked && (dependency || forced ? included : optional ? item.selected && parentOn : item.selected);
  const locked = blocked || dependency || forced || (optional && !parentOn);
  return (
    <li className={`ob-mod ${blocked ? "is-blocked" : ""} ${dependency ? "is-dep" : ""} ${!included && !blocked ? "is-off" : ""}`}>
      <Checkbox checked={checked} disabled={locked} onChange={onToggle} label={`Include ${item.title}`} />
      <span className="ob-mod-icon">{item.iconUrl ? <img src={item.iconUrl} alt="" loading="lazy" /> : <Icon name="cube" size={16} />}</span>
      <div className="ob-mod-text">
        <strong>{item.title}</strong>
        <span className="muted" title={note ?? undefined}>
          {note}
        </span>
      </div>
      <span className="ob-mod-meta">
        {item.source === "assistant" && <Badge tone="accent">AI suggestion</Badge>}
        {item.source === "user" && <Badge>{item.reason ? "Search match" : "You added"}</Badge>}
        {dependency && <Badge>Required</Badge>}
        {optional && <Badge>Optional</Badge>}
        {item.status === "conflict" && <Badge tone="danger">Conflict</Badge>}
        {item.status === "unavailable" && <Badge tone="warning">Not available</Badge>}
        {item.versionType && item.versionType !== "release" && item.status === "ok" && <Badge tone="warning">{item.versionType}</Badge>}
        {compat && (
          <span className="ob-mod-compat faint" title={compat.title}>
            <Icon name="check" size={11} />
            {compat.label}
          </span>
        )}
      </span>
      {!dependency && !optional ? (
        <span className="ob-mod-actions">
          <button type="button" className="icon-btn icon-btn-sm icon-btn-ghost" aria-label={`Replace ${item.title}`} title="Replace" onClick={onReplace}>
            <Icon name="refresh" size={14} />
          </button>
          <button type="button" className="icon-btn icon-btn-sm icon-btn-ghost" aria-label={`Remove ${item.title}`} title="Remove" onClick={onRemove}>
            <Icon name="x" size={14} />
          </button>
        </span>
      ) : (
        <span className="ob-mod-actions" />
      )}
    </li>
  );
}

/** Flint's automatic performance set, as the Mods step and Review show it (names only: what fits is decided at install). */
export function PerformanceSetNote({ compact }: { compact?: boolean }) {
  return (
    <div className={`ob-perf ${compact ? "is-compact" : ""}`}>
      {!compact && (
        <h4>
          Flint performance set<span className="faint">Added automatically where compatible · can be turned off in Settings</span>
        </h4>
      )}
      <p className="ob-perf-names">
        {PERFORMANCE_SET.map((name) => (
          <span key={name} className="ob-perf-chip">
            {name}
          </span>
        ))}
      </p>
      {!compact && <p className="faint ob-perf-foot">Each one is added only if Modrinth has a build for your version, plus anything it needs. They're labelled in the profile, and the finished screen lists what was installed.</p>}
    </div>
  );
}

export function ModPicker({ open, onClose, gameVersion, title, onPick }: { open: boolean; onClose: () => void; gameVersion: string; title: string; onPick: (hit: LibraryHit) => void }) {
  const [query, setQuery] = useState("");
  const [hits, setHits] = useState<LibraryHit[] | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    if (!open) return;
    const t = window.setTimeout(() => {
      setError("");
      api.librarySearch({ kind: "mod", query, index: "relevance", offset: 0, limit: 20, gameVersion, loader: "fabric", categories: [] }).then(
        (r) => setHits(r.hits),
        (e) => setError(errorText(e)),
      );
    }, 220);
    return () => window.clearTimeout(t);
  }, [open, query, gameVersion]);
  return (
    <Modal open={open} onClose={onClose} title={title} width={560}>
      <TextField icon="search" placeholder={`Search Fabric mods for ${gameVersion}`} value={query} onChange={(e) => setQuery(e.target.value)} data-autofocus />
      <ul className="picker scroll">
        {error && <li className="picker-empty">{error}</li>}
        {!error && hits === null && (
          <li className="picker-empty">
            <Spinner size={14} /> Searching Modrinth…
          </li>
        )}
        {hits?.length === 0 && <li className="picker-empty">No Fabric mods for {gameVersion} match that search.</li>}
        {hits?.map((hit) => (
          <li key={hit.projectId}>
            <button
              type="button"
              className="picker-item"
              onClick={() => {
                onPick(hit);
                onClose();
              }}
            >
              <span className="ob-mod-icon">{hit.iconUrl ? <img src={hit.iconUrl} alt="" /> : <Icon name="cube" size={16} />}</span>
              <span className="picker-text">
                <strong>{hit.title}</strong>
                <span className="muted">
                  {hit.author} · {hit.description}
                </span>
              </span>
              <span className="faint">{compact(hit.downloads)}</span>
            </button>
          </li>
        ))}
      </ul>
    </Modal>
  );
}

const GROUP_NOTE: Record<DisplayGroup<PlanEntry>["kind"], string> = {
  goal: "",
  picks: "Added by you",
  dependency: "Installed with the picks that need them",
  optional: "Off unless you tick them",
};

export function ModsStep({ answers, set, setCanContinue }: StepProps) {
  const store = useStore();
  // AI suggestions need the gateway and Flint Assist (Settings → General); otherwise Flint's smart search answers.
  const { flintAssist } = useSettings().settings;
  const aiOn = store.info.gatewayConfigured && flintAssist;
  const fabric = versionMeta(answers.version).fabric;
  const [busy, setBusy] = useState<"idle" | "connecting" | "thinking" | "matching">("idle");
  const [status, setStatus] = useState<{ tone: "info" | "warn" | "error"; text: string } | null>(null);
  const [picker, setPicker] = useState<{ replace?: string } | null>(null);
  const requestRef = useRef<string | null>(null);
  const { settings } = useSettings();

  useEffect(() => setCanContinue(busy === "idle"), [busy, setCanContinue]);

  // The plan was checked for another version (the player went back and changed it): check the
  // same picks again for this one, keeping their ticks, before anything is shown as compatible.
  const planVersion = answers.planVersion ?? answers.version;
  const stale = fabric && answers.plan.length > 0 && planVersion !== answers.version;
  useEffect(() => {
    if (!stale) return;
    let live = true;
    const version = answers.version;
    const picks = answers.plan.filter((i) => i.source === "user" || i.source === "assistant");
    setBusy("matching");
    setStatus(null);
    Promise.all(pickRequests(picks).map((batch) => api.resolveMods({ gameVersion: version }, batch)))
      .then((plans) => {
        if (!live) return;
        set((a) => ({ plan: rebasePlan(a.plan, joinResolved(plans.map((p) => p.items))), planVersion: version }));
        setStatus({ tone: "info", text: `Checked your mods again for Minecraft ${version}.` });
      })
      .catch((e) => live && setStatus({ tone: "error", text: `Couldn't check your mods for ${version}: ${errorText(e)}. Flint checks them again during setup.` }))
      .finally(() => live && setBusy("idle"));
    return () => {
      live = false;
    };
    // Runs once per version change; the plan it re-checks is read when it starts.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stale, answers.version]);

  const run = async (smartOnly?: string) => {
    const prompt = answers.assistPrompt.trim();
    if (prompt.length < 2) return;
    const requestId = `r${Date.now().toString(36)}`;
    requestRef.current = requestId;
    setStatus(null);
    setBusy(aiOn ? "connecting" : "matching");
    try {
      const result = await suggestMods({ gameVersion: answers.version }, prompt, {
        aiEnabled: aiOn && !smartOnly,
        fallbackReason: smartOnly,
        requestId,
        onStage: (stage) => requestRef.current === requestId && setBusy(stage),
      });
      if (requestRef.current !== requestId) return;
      set((a) => ({ plan: mergePlan(a.plan, result.plan.items), planVersion: answers.version, assistMode: result.mode }));
      const missed = result.plan.unmatched?.length ?? 0;
      const goals = result.goals.length ? ` Understood: ${result.goals.join(", ")}.` : "";
      if (result.mode === "fallback") {
        const why = aiOn ? `${result.reason}, so Flint smart search answered instead (no AI).` : "Flint smart search answered (no AI).";
        setStatus({ tone: aiOn ? "warn" : "info", text: `${why}${goals}${missed ? ` ${missed} item(s) had no match for this version.` : ""} Review the list before continuing.` });
      } else {
        const parts = [goals.trim(), result.note, missed ? `${missed} suggestion(s) weren't found on Modrinth for ${answers.version} and were left out.` : "", result.quota ? `${quotaLabel(result.quota)}.` : ""];
        setStatus({ tone: "info", text: parts.filter(Boolean).join(" ") });
      }
      sound.play("success");
    } catch (e) {
      if (e instanceof QuotaExhausted) {
        // The same allowance as the Library: when it's used up, Flint's free smart search answers instead.
        const reset = e.quota.resetInMs ? ` (they reset in ${formatDuration(e.quota.resetInMs)})` : "";
        if (requestRef.current === requestId) requestRef.current = null;
        setBusy("idle");
        return void run(`You've used your ${e.quota.limit} Flint AI searches${reset}`);
      }
      if (errorText(e) !== "AI_CANCELLED") {
        setStatus({ tone: "error", text: `Couldn't reach Modrinth: ${errorText(e)}. You can try again or skip this step.` });
        sound.play("error");
      }
    } finally {
      if (requestRef.current === requestId) {
        requestRef.current = null;
        setBusy("idle");
      }
    }
  };

  const cancel = () => {
    if (requestRef.current) void api.aiCancel(requestRef.current);
    requestRef.current = null;
    setBusy("idle");
    setStatus({ tone: "info", text: "Stopped. Nothing was added." });
  };

  const addFromSearch = async (hit: LibraryHit, replace?: string) => {
    setBusy("matching");
    try {
      const plan = await api.resolveMods({ gameVersion: answers.version }, [{ project: hit.slug, source: "user" }]);
      set((a) => ({ plan: mergePlan(a.plan, plan.items, replace), planVersion: answers.version }));
    } catch (e) {
      setStatus({ tone: "error", text: errorText(e) });
    } finally {
      setBusy("idle");
    }
  };

  const remove = (item: PlanEntry) => {
    set((a) => ({ plan: withoutPick(a.plan, item.projectId) }));
  };

  if (!fabric) {
    return (
      <div className="ob-step ob-narrow">
        <StepHead title="What would you like to add to your Minecraft setup?" />
        <div className="card ob-vanilla">
          <Icon name="info" size={18} />
          <p>
            Minecraft {answers.version} runs without a mod loader in Flint, because official Fabric doesn't support it. Continue to set it up as vanilla, or go back and pick a newer version for mods.
          </p>
        </div>
      </div>
    );
  }

  const { on, forced } = planSelection(answers.plan);
  const installing = answers.plan.filter((i) => on.has(i.projectId));
  const blocked = answers.plan.filter((i) => i.status === "conflict" || i.status === "unavailable");
  const totalSize = installing.reduce((sum, i) => sum + (i.size ?? 0), 0);
  const includedTitles = new Set(installing.map((i) => i.title));
  const toggle = (projectId: string, v: boolean) => set((a) => ({ plan: a.plan.map((i) => (i.projectId === projectId ? { ...i, selected: v } : i)) }));
  return (
    <div className="ob-step ob-mid">
      <StepHead
        title="What would you like to add to your Minecraft setup?"
        body={
          aiOn
            ? "Describe it in your own words. Flint AI works out what you're after; Flint then finds real Modrinth mods and checks each one for Fabric and your version. Nothing downloads until you approve."
            : "Describe it in a few words. Flint searches Modrinth for matching Fabric mods for your version. Nothing downloads until you approve."
        }
      />
      <div className="ob-assist">
        <TextArea
          aria-label="Describe what you want to add"
          placeholder="e.g. smoother FPS, a minimap, and a zoom key"
          value={answers.assistPrompt}
          maxLength={600}
          disabled={busy !== "idle"}
          onChange={(e) => set({ assistPrompt: e.target.value })}
          onKeyDown={(e) => {
            if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) void run();
          }}
        />
        <div className="ob-assist-bar">
          <div className="ob-chips">
            {EXAMPLES.map((ex) => (
              <button
                key={ex.label}
                type="button"
                className="chip"
                disabled={busy !== "idle"}
                onClick={() => {
                  sound.play("tap");
                  const cur = answers.assistPrompt.trim();
                  set({ assistPrompt: cur ? `${cur}, ${ex.text}` : ex.text });
                }}
              >
                <Icon name="plus" size={13} />
                {ex.label}
              </button>
            ))}
          </div>
          {busy === "idle" ? (
            <Button variant="primary" icon={aiOn ? "sparkle" : "search"} disabled={answers.assistPrompt.trim().length < 2} onClick={() => void run()}>
              {aiOn ? "Suggest mods" : "Find mods"}
            </Button>
          ) : (
            <Button variant="secondary" onClick={cancel} icon="x">
              Stop
            </Button>
          )}
        </div>
      </div>
      {aiOn && (
        <p className="ob-assist-privacy">
          <Icon name="lock" size={12} />
          Your request and Minecraft version are sent to Flint AI (Cloudflare). Your Minecraft username is used only to confirm you own the game. Your sign-in is never sent.
        </p>
      )}

      {busy !== "idle" && (
        <div className="ob-assist-status" role="status">
          <Spinner size={14} />
          {busy === "connecting" ? "Connecting to Flint AI…" : busy === "thinking" ? "Flint AI is reading your request…" : "Searching Modrinth for compatible versions…"}
        </div>
      )}
      {status && (
        <p className={`ob-assist-note is-${status.tone}`} role={status.tone === "error" ? "alert" : "status"}>
          <Icon name={status.tone === "error" ? "alert" : "info"} size={14} />
          {status.text}
        </p>
      )}

      {answers.plan.length > 0 && (
        <section className="ob-plan">
          <header>
            <h3>Proposed mods</h3>
            {answers.assistMode === "ai" ? (
              <Badge tone="accent">Flint AI</Badge>
            ) : answers.assistMode === "fallback" ? (
              <span className="ob-assist-mode">Using Flint smart search</span>
            ) : null}
            <span className="faint">
              {installing.length} to install · {bytes(totalSize)} · {stale ? `checking for ${answers.version}…` : `checked for Fabric ${answers.version}`}
            </span>
            <Button size="sm" variant="ghost" icon="plus" onClick={() => setPicker({})}>
              Add a mod
            </Button>
          </header>
          {blocked.length > 0 && (
            <p className="ob-plan-warn" role="status">
              <Icon name="alert" size={14} />
              {blocked.length === 1 ? `${blocked[0].title} can't be installed: ${blocked[0].message ?? "no compatible release"}.` : `${blocked.length} mods can't be installed and will be left out. Details are shown on each row.`}
            </p>
          )}
          {groupPlan(answers.plan.filter((i) => i.status !== "not-needed")).map((g) => {
            const rows = g.items;
            return (
              <div key={g.id} className={`ob-plan-group is-${g.kind}`}>
                <h4>
                  {g.title}
                  {GROUP_NOTE[g.kind] && <span className="faint">{GROUP_NOTE[g.kind]}</span>}
                </h4>
                <ul>
                  {rows.map((item) => (
                    <ModRow
                      key={item.projectId}
                      item={item}
                      gameVersion={answers.version}
                      included={on.has(item.projectId)}
                      forced={forced.has(item.projectId) && item.source !== "dependency"}
                      parentOn={item.source !== "optional" || (item.requiredBy ? includedTitles.has(item.requiredBy) : includedTitles.size > 0)}
                      includedTitles={includedTitles}
                      onToggle={(v) => toggle(item.projectId, v)}
                      onRemove={() => remove(item)}
                      onReplace={() => setPicker({ replace: item.projectId })}
                    />
                  ))}
                </ul>
              </div>
            );
          })}
        </section>
      )}
      {answers.plan.length === 0 && busy === "idle" && (
        <button type="button" className="ob-link" onClick={() => setPicker({})}>
          Or search Modrinth yourself
        </button>
      )}
      {settings.performanceMods && <PerformanceSetNote />}
      <ModPicker
        open={!!picker}
        onClose={() => setPicker(null)}
        gameVersion={answers.version}
        title={picker?.replace ? "Replace with another mod" : "Add a mod"}
        onPick={(hit) => void addFromSearch(hit, picker?.replace)}
      />
    </div>
  );
}

// ---------------- Startup ----------------

export function StartupStep({ answers, set, setCanContinue }: StepProps) {
  useEffect(() => setCanContinue(true), [setCanContinue]);
  return (
    <div className="ob-step ob-narrow">
      <StepHead title="Would you like Flint to open when your computer starts?" body="Only for your user account. You can change this any time in Settings." />
      <RadioCards
        name="startup"
        className="ob-yesno"
        value={answers.launchAtLogin ? "yes" : "no"}
        onChange={(v) => set({ launchAtLogin: v === "yes" })}
        options={[
          { value: "no", title: "No, I'll open it myself", description: "Recommended. Flint stays out of the way until you need it." },
          { value: "yes", title: "Yes, open Flint at login", description: "Flint starts when you sign in to your computer." },
        ]}
      />
    </div>
  );
}

// ---------------- Community ----------------

export function CommunityStep({ answers, set, setCanContinue }: StepProps) {
  const { info } = useStore();
  useEffect(() => setCanContinue(true), [setCanContinue]);
  return (
    <div className="ob-step ob-narrow">
      <StepHead title="Want to stay connected with Flint?" body="Get release news, share setups and report bugs in the community." />
      <div className="ob-community card">
        <span className="ob-community-logo">
          <Icon name="discord" size={26} />
        </span>
        <div>
          <strong>Flint Community on Discord</strong>
          <p className="muted">
            {info.discordUrl ? "Opens in your browser. Joining is completely optional." : "The invite link hasn't been published yet. It will appear on Home once it's available."}
          </p>
        </div>
        {info.discordUrl ? (
          <Button
            variant="secondary"
            iconRight="external"
            onClick={() => {
              void api.openExternal(info.discordUrl!);
              set({ community: "opened" });
            }}
          >
            {answers.community === "opened" ? "Opened in browser" : "Join"}
          </Button>
        ) : (
          <Badge>Awaiting URL</Badge>
        )}
      </div>
    </div>
  );
}

// ---------------- Appearance ----------------

export function AppearanceStep({ setCanContinue }: StepProps) {
  const { settings, update } = useSettings();
  useEffect(() => setCanContinue(true), [setCanContinue]);
  return (
    <div className="ob-step ob-mid">
      <StepHead title="Make Flint feel like yours." body="Changes preview instantly. Everything here also lives in Settings → Appearance." />
      <div className="ob-appearance">
        <section>
          <h3 className="ob-label">Theme</h3>
          <ThemePicker value={settings.theme} onChange={(theme) => update({ theme })} />
        </section>
        <section>
          <h3 className="ob-label">Typography</h3>
          <FontPicker value={settings.font} onChange={(font) => update({ font })} />
        </section>
        <section className="ob-rows">
          <div className="ob-row">
            <div>
              <strong>Interface sounds</strong>
              <span className="muted">Quiet cues when downloads finish, the game launches or something fails.</span>
            </div>
            <Switch checked={settings.sounds} onChange={(sounds) => update({ sounds })} label="Interface sounds" />
          </div>
          <div className="ob-row">
            <div>
              <strong>Volume</strong>
            </div>
            <div className="ob-row-inline">
              <Slider value={Math.round(settings.volume * 100)} min={0} max={100} step={5} label="Sound volume" disabled={!settings.sounds} onChange={(v) => update({ volume: v / 100 })} format={(v) => `${v}%`} />
              <Button
                size="sm"
                variant="secondary"
                icon="volume"
                disabled={!settings.sounds || settings.volume <= 0}
                silent
                // The cue plays through the same master level as every other sound, at the volume shown.
                onClick={() => {
                  sound.configure(settings.sounds, settings.volume);
                  sound.play("success");
                }}
              >
                Preview
              </Button>
            </div>
          </div>
          <div className="ob-row">
            <div>
              <strong>Animations</strong>
              <span className="muted">Transitions and the character's idle motion.</span>
            </div>
            <Switch checked={settings.animations} onChange={(animations) => update({ animations })} label="Animations" />
          </div>
          <div className="ob-row">
            <div>
              <strong>Wallpaper parallax</strong>
              <span className="muted">The Home background shifts a few pixels with your cursor.</span>
            </div>
            <Switch checked={settings.parallax} onChange={(parallax) => update({ parallax })} label="Wallpaper parallax" />
          </div>
          <div className="ob-row">
            <div>
              <strong>Visual effects</strong>
              <span className="muted">{EFFECTS.find((e) => e.id === settings.effects)?.note}</span>
            </div>
            <Segmented label="Visual effects" value={settings.effects} onChange={(effects) => update({ effects })} options={EFFECTS.map((e) => ({ value: e.id, label: e.name }))} />
          </div>
        </section>
      </div>
    </div>
  );
}

export function Segment({ value, onChange }: { value: "low" | "medium" | "high"; onChange: (v: "low" | "medium" | "high") => void }) {
  return (
    <Segmented
      label="Assistant reasoning effort"
      value={value}
      onChange={onChange}
      options={[
        { value: "low", label: "Fast" },
        { value: "medium", label: "Balanced" },
        { value: "high", label: "Thorough" },
      ]}
    />
  );
}
