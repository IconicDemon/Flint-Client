import { useEffect, useRef, useState, type ReactNode } from "react";
import { api, errorText, subscribe, type Account, type Catalog, type LibraryHit } from "../../lib/api";
import { formatDuration, quotaLabel } from "../../lib/aiQuota";
import { EXAMPLES, QuotaExhausted, suggestMods } from "../../lib/assist";
import { goalLabel, groupPlan, type DisplayGroup } from "../../lib/assistPlan";
import { bodyFromSkin, headFromSkin } from "../../lib/avatar";
import { bytes, compact, dashedUuid, shortDate } from "../../lib/format";
import { useT, type MessageKey } from "../../lib/i18n";
import { EFFECTS, useSettings } from "../../lib/settings";
import { sound } from "../../lib/sound";
import { useStore } from "../../lib/store";
import { VERSIONS, defaultProfileName, platformBlock, versionMeta } from "../../lib/versions";
import { EFFECT_TEXT, FontPicker, ThemePicker } from "../../ui/AppearancePickers";
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
  /** After sign-in: the guided setup, or straight to Home with Flint's defaults. */
  path: "setup" | "skip";
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
  path: "setup",
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

const WELCOME_STEPS: MessageKey[] = ["onboarding.welcome.steps.signIn", "onboarding.welcome.steps.version", "onboarding.welcome.steps.mods"];

export function WelcomeStep({ setCanContinue, onStart }: StepProps & { onStart: () => void }) {
  const t = useT();
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
      <h1 className="ob-welcome-title">{t("onboarding.welcome.title")}</h1>
      <p className="ob-welcome-lede">{t("onboarding.welcome.lede")}</p>
      <p className="ob-welcome-body">{t("onboarding.welcome.body")}</p>
      <ol className="ob-welcome-steps">
        {WELCOME_STEPS.map((label, i) => (
          <li key={label}>
            <span>{i + 1}</span>
            {t(label)}
          </li>
        ))}
      </ol>
      <Button variant="primary" size="lg" iconRight="arrowRight" onClick={onStart} className="ob-start" data-autofocus>
        {t("onboarding.welcome.start")}
      </Button>
      <p className="ob-welcome-legal">{t("onboarding.welcome.legal")}</p>
    </div>
  );
}

// ---------------- Account ----------------

const AUTH_STAGES: { key: string; label: MessageKey }[] = [
  { key: "browser", label: "onboarding.account.stages.browser" },
  { key: "exchange", label: "onboarding.account.stages.exchange" },
  { key: "xbox", label: "onboarding.account.stages.xbox" },
  { key: "minecraft", label: "onboarding.account.stages.minecraft" },
  { key: "ownership", label: "onboarding.account.stages.ownership" },
  { key: "profile", label: "onboarding.account.stages.profile" },
];

function AccountReveal({ account, fresh }: { account: Account; fresh: boolean }) {
  const { skins } = useStore();
  const t = useT();
  const skin = skins[account.uuid];
  const [body, setBody] = useState<string | null>(null);
  useEffect(() => {
    setBody(null);
    if (skin?.dataUrl) void bodyFromSkin(skin.dataUrl, skin.model === "slim").then(setBody);
  }, [skin?.dataUrl, skin?.model]);
  return (
    <div className={`ob-reveal ${fresh ? "is-fresh" : ""}`}>
      {/* The figure's frame stays empty until the skin is drawn, then the character fades in. */}
      <div className="ob-reveal-figure">{body && <img src={body} alt="" />}</div>
      <div className="ob-reveal-text">
        <span className="ob-reveal-kicker">{t("onboarding.account.signedInAs")}</span>
        <strong>{account.username}</strong>
        <span className="mono faint">{dashedUuid(account.uuid)}</span>
        {import.meta.env.DEV && account.qa ? (
          // Development builds only; stays English.
          <Badge tone="warning">
            <Icon name="alert" size={12} /> Development test identity
          </Badge>
        ) : (
          <Badge tone="success">
            <Icon name="check" size={12} /> {t("onboarding.account.ownsJava")}
          </Badge>
        )}
      </div>
    </div>
  );
}

export function AccountStep({ answers, set, setCanContinue }: StepProps) {
  const store = useStore();
  const t = useT();
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
        eyebrow={t("onboarding.account.eyebrow")}
        title={account && phase !== "working" ? t("onboarding.account.titleReady", { name: account.username }) : t("onboarding.account.title")}
        body={account && phase !== "working" ? t("onboarding.account.bodyReady") : t("onboarding.account.body")}
      />
      <section className="ob-signin card">
        {phase === "working" ? (
          <div className="ob-signin-progress" role="status" aria-live="polite">
            <ol>
              {AUTH_STAGES.map((s, i) => (
                <li key={s.key} className={i < stageIndex ? "is-done" : i === stageIndex ? "is-current" : ""}>
                  <span className="ob-signin-dot">{i < stageIndex ? <Icon name="check" size={11} /> : i === stageIndex ? <Spinner size={12} /> : null}</span>
                  {t(s.label)}
                </li>
              ))}
            </ol>
            <div className="ob-signin-foot">
              <p className="muted">{stage === "browser" ? t("onboarding.account.browserOpened") : t("onboarding.account.almostThere")}</p>
              {stage === "browser" && (
                <Button variant="secondary" size="sm" onClick={() => void api.cancelSignIn()}>
                  {t("common.cancel")}
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
                <strong>{t("onboarding.account.msTitle")}</strong>
                <span className="muted">{t("onboarding.account.msBody")}</span>
              </div>
            </div>
            <button type="button" className="ob-ms-btn" onClick={() => void connect()}>
              <Icon name="microsoft" size={18} />
              {t("onboarding.account.signIn")}
            </button>
            {phase === "cancelled" && <p className="ob-signin-note muted">{t("onboarding.account.cancelled")}</p>}
            {phase === "error" && (
              <div className="ob-error" role="alert">
                <Icon name="alert" size={16} />
                <span>{error}</span>
                <Button size="sm" variant="secondary" icon="refresh" onClick={() => void connect()}>
                  {t("common.tryAgain")}
                </Button>
              </div>
            )}
          </div>
        )}
        <ul className="ob-access">
          <li>
            <Icon name="check" size={13} /> {t("onboarding.account.access.receives")}
          </li>
          <li>
            <Icon name="lock" size={13} /> {t("onboarding.account.access.tokens")}
          </li>
          <li>
            <Icon name="x" size={13} /> {t("onboarding.account.access.never")}
          </li>
        </ul>
      </section>
      {account && phase !== "working" && (
        <button className="ob-link" type="button" onClick={() => void connect()}>
          {t("onboarding.account.switch")}
        </button>
      )}
      {/* Development builds only; stays English. */}
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
  const t = useT();
  const nameOk = answers.profileName.trim().length > 0;
  const selectedBlocked = platformBlock(answers.version, store.info);
  useEffect(() => setCanContinue(nameOk && !selectedBlocked), [nameOk, selectedBlocked, setCanContinue]);

  const pick = (id: string) => {
    sound.play("toggle");
    set({ version: id, ...(answers.nameTouched ? {} : { profileName: defaultProfileName(id) }) });
  };

  return (
    <div className="ob-step ob-wide">
      <StepHead title={t("onboarding.version.title")} body={t("onboarding.version.body")} />
      <div className="ob-versions" role="radiogroup" aria-label={t("onboarding.version.listLabel")}>
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
                <span className="ob-version-drop">
                  {v.drop ?? (live?.releaseTime ? t("onboarding.version.released", { date: shortDate(live.releaseTime) }) : t("onboarding.version.release"))}
                </span>
                <span className="ob-version-tags">
                  {blocked ? (
                    <Badge tone="warning">{t("onboarding.version.appleSilicon")}</Badge>
                  ) : (
                    <>
                      {fabric ? <Badge tone="accent">Fabric</Badge> : <Badge>{t("onboarding.version.vanillaOnly")}</Badge>}
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
              <Icon name="wifiOff" size={14} /> {t("onboarding.version.offline")}
            </>
          ) : (
            <>
              <Icon name="check" size={14} />{" "}
              {catalog.fabricLoader ? t("onboarding.version.verifiedLoader", { version: catalog.fabricLoader }) : t("onboarding.version.verified")}
            </>
          )
        ) : catalogError ? (
          <>
            <Icon name="wifiOff" size={14} /> {t("onboarding.version.unreachable")}
          </>
        ) : (
          <>
            <Spinner size={12} /> {t("onboarding.version.checking")}
          </>
        )}
      </p>
      {versionMeta(answers.version).note && <p className="ob-note">{versionMeta(answers.version).note}</p>}
      <div className="ob-name">
        <TextField
          label={t("onboarding.version.nameLabel")}
          value={answers.profileName}
          maxLength={40}
          onChange={(e) => set({ profileName: e.target.value, nameTouched: true })}
          hint={t("onboarding.version.nameHint")}
          error={nameOk ? undefined : t("onboarding.version.nameMissing")}
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
  const t = useT();
  const blocked = item.status === "conflict" || item.status === "unavailable";
  const dependency = item.source === "dependency";
  const optional = item.source === "optional";
  // The mods that need a dependency, when the resolver named them.
  const needers = item.requiredByAll?.length ? item.requiredByAll : item.requiredBy ? [item.requiredBy] : [];
  const neededNow = needers.filter((title) => includedTitles.has(title)).join(", ");
  const neededLater = needers.filter((title) => !includedTitles.has(title)).join(", ");
  const dependencyNote = () => {
    if (included) {
      if (!neededNow) return t("onboarding.mods.note.neededByIncluded");
      return neededLater ? t("onboarding.mods.note.neededLater", { mods: neededNow, later: neededLater }) : t("onboarding.mods.note.needed", { mods: neededNow });
    }
    if (!needers.length) return t("onboarding.mods.note.neededByAnother");
    return t(needers.length > 1 ? "onboarding.mods.note.neededIfAny" : "onboarding.mods.note.neededIfOne", { mods: needers.join(", ") });
  };
  const parent = item.requiredBy;
  const optionalNote = () => {
    if (!parentOn) return parent ? t("onboarding.mods.note.addonParentOff", { mod: parent }) : t("onboarding.mods.note.addonParentOffUnknown");
    if (included) return parent ? t("onboarding.mods.note.addonOn", { mod: parent }) : t("onboarding.mods.note.addonOnUnknown");
    return parent ? t("onboarding.mods.note.addonOff", { mod: parent }) : t("onboarding.mods.note.addonOffUnknown");
  };
  // Backend and Modrinth text (the resolver's message, the pick's reason, the description) stays as it is.
  const note = blocked
    ? item.message
    : dependency
      ? dependencyNote()
      : optional
        ? optionalNote()
        : forced
          ? [t("onboarding.mods.note.neededByIncluded"), item.reason].filter(Boolean).join(" · ")
          : (item.reason ?? item.description);
  const compat = item.status === "ok" ? compatibility(item, gameVersion) : null;
  // Dependencies follow the mods that need them; a pick another included mod needs can't be cleared.
  const checked = !blocked && (dependency || forced ? included : optional ? item.selected && parentOn : item.selected);
  const locked = blocked || dependency || forced || (optional && !parentOn);
  return (
    <li className={`ob-mod ${blocked ? "is-blocked" : ""} ${dependency ? "is-dep" : ""} ${!included && !blocked ? "is-off" : ""}`}>
      <Checkbox checked={checked} disabled={locked} onChange={onToggle} label={t("onboarding.mods.row.include", { mod: item.title })} />
      <span className="ob-mod-icon">{item.iconUrl ? <img src={item.iconUrl} alt="" loading="lazy" /> : <Icon name="cube" size={16} />}</span>
      <div className="ob-mod-text">
        <strong>{item.title}</strong>
        <span className="muted" title={note ?? undefined}>
          {note}
        </span>
      </div>
      <span className="ob-mod-meta">
        {item.source === "assistant" && <Badge tone="accent">{t("onboarding.mods.row.aiSuggestion")}</Badge>}
        {item.source === "user" && <Badge>{item.reason ? t("onboarding.mods.row.searchMatch") : t("onboarding.mods.row.youAdded")}</Badge>}
        {dependency && <Badge>{t("onboarding.mods.row.required")}</Badge>}
        {optional && <Badge>{t("onboarding.mods.row.optional")}</Badge>}
        {item.status === "conflict" && <Badge tone="danger">{t("onboarding.mods.row.conflict")}</Badge>}
        {item.status === "unavailable" && <Badge tone="warning">{t("onboarding.mods.row.unavailable")}</Badge>}
        {/* Modrinth's channel (beta, alpha), as Modrinth writes it. */}
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
          <button
            type="button"
            className="icon-btn icon-btn-sm icon-btn-ghost"
            aria-label={t("onboarding.mods.row.replaceLabel", { mod: item.title })}
            title={t("onboarding.mods.row.replace")}
            onClick={onReplace}
          >
            <Icon name="refresh" size={14} />
          </button>
          <button
            type="button"
            className="icon-btn icon-btn-sm icon-btn-ghost"
            aria-label={t("onboarding.mods.row.removeLabel", { mod: item.title })}
            title={t("common.remove")}
            onClick={onRemove}
          >
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
  const t = useT();
  return (
    <div className={`ob-perf ${compact ? "is-compact" : ""}`}>
      {!compact && (
        <h4>
          {t("onboarding.perf.title")}
          <span className="faint">{t("onboarding.perf.hint")}</span>
        </h4>
      )}
      <p className="ob-perf-names">
        {PERFORMANCE_SET.map((name) => (
          <span key={name} className="ob-perf-chip">
            {name}
          </span>
        ))}
      </p>
      {!compact && <p className="faint ob-perf-foot">{t("onboarding.perf.foot")}</p>}
    </div>
  );
}

export function ModPicker({ open, onClose, gameVersion, title, onPick }: { open: boolean; onClose: () => void; gameVersion: string; title: string; onPick: (hit: LibraryHit) => void }) {
  const [query, setQuery] = useState("");
  const [hits, setHits] = useState<LibraryHit[] | null>(null);
  const [error, setError] = useState("");
  const t = useT();
  useEffect(() => {
    if (!open) return;
    const timer = window.setTimeout(() => {
      setError("");
      api.librarySearch({ kind: "mod", query, index: "relevance", offset: 0, limit: 20, gameVersion, loader: "fabric", categories: [] }).then(
        (r) => setHits(r.hits),
        (e) => setError(errorText(e)),
      );
    }, 220);
    return () => window.clearTimeout(timer);
  }, [open, query, gameVersion]);
  return (
    <Modal open={open} onClose={onClose} title={title} width={560}>
      <TextField icon="search" placeholder={t("onboarding.picker.placeholder", { version: gameVersion })} value={query} onChange={(e) => setQuery(e.target.value)} data-autofocus />
      <ul className="picker scroll">
        {error && <li className="picker-empty">{error}</li>}
        {!error && hits === null && (
          <li className="picker-empty">
            <Spinner size={14} /> {t("onboarding.picker.searching")}
          </li>
        )}
        {hits?.length === 0 && <li className="picker-empty">{t("onboarding.picker.empty", { version: gameVersion })}</li>}
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

const GROUP_NOTE: Record<DisplayGroup<PlanEntry>["kind"], MessageKey | null> = {
  goal: null,
  picks: "onboarding.mods.groupNote.picks",
  dependency: "onboarding.mods.groupNote.dependency",
  optional: "onboarding.mods.groupNote.optional",
};

export function ModsStep({ answers, set, setCanContinue }: StepProps) {
  const store = useStore();
  const t = useT();
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
        setStatus({ tone: "info", text: t("onboarding.mods.status.rechecked", { version }) });
      })
      .catch((e) => live && setStatus({ tone: "error", text: t("onboarding.mods.status.recheckFailed", { version, error: errorText(e) }) }))
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
      // The goals Flint AI understood are the AI's own words.
      const goals = result.goals.length ? t("onboarding.mods.status.understood", { goals: result.goals.join(", ") }) : "";
      // Whole sentences, joined with spaces.
      let parts: (string | null | undefined)[];
      if (result.mode === "fallback") {
        const why = aiOn ? t("onboarding.mods.status.fallbackReason", { reason: result.reason ?? "" }) : t("onboarding.mods.status.fallback");
        parts = [why, goals, missed ? t("onboarding.mods.status.noMatch", { count: missed }) : "", t("onboarding.mods.status.reviewList")];
      } else {
        parts = [goals, result.note, missed ? t("onboarding.mods.status.notFound", { count: missed, version: answers.version }) : "", result.quota ? `${quotaLabel(result.quota)}.` : ""];
      }
      setStatus({ tone: result.mode === "fallback" && aiOn ? "warn" : "info", text: parts.filter(Boolean).join(" ") });
      sound.play("success");
    } catch (e) {
      if (e instanceof QuotaExhausted) {
        // The same allowance as the Library: when it's used up, Flint's free smart search answers instead.
        // The reason reads as a clause: "{reason}, so Flint smart search answered instead".
        const reason = e.quota.resetInMs
          ? t("onboarding.mods.status.quotaUsedReset", { count: e.quota.limit, time: formatDuration(e.quota.resetInMs) })
          : t("onboarding.mods.status.quotaUsed", { count: e.quota.limit });
        if (requestRef.current === requestId) requestRef.current = null;
        setBusy("idle");
        return void run(reason);
      }
      if (errorText(e) !== "AI_CANCELLED") {
        setStatus({ tone: "error", text: t("onboarding.mods.status.searchFailed", { error: errorText(e) }) });
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
    setStatus({ tone: "info", text: t("onboarding.mods.status.stopped") });
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
        <StepHead title={t("onboarding.mods.title")} />
        <div className="card ob-vanilla">
          <Icon name="info" size={18} />
          <p>{t("onboarding.mods.vanilla", { version: answers.version })}</p>
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
      <StepHead title={t("onboarding.mods.title")} body={aiOn ? t("onboarding.mods.bodyAi") : t("onboarding.mods.body")} />
      <div className="ob-assist">
        <TextArea
          aria-label={t("onboarding.mods.promptLabel")}
          placeholder={t("onboarding.mods.promptPlaceholder")}
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
              {aiOn ? t("onboarding.mods.suggest") : t("onboarding.mods.find")}
            </Button>
          ) : (
            <Button variant="secondary" onClick={cancel} icon="x">
              {t("onboarding.mods.stop")}
            </Button>
          )}
        </div>
      </div>
      {aiOn && (
        <p className="ob-assist-privacy">
          <Icon name="lock" size={12} />
          {t("onboarding.mods.privacy")}
        </p>
      )}

      {busy !== "idle" && (
        <div className="ob-assist-status" role="status">
          <Spinner size={14} />
          {busy === "connecting" ? t("onboarding.mods.busy.connecting") : busy === "thinking" ? t("onboarding.mods.busy.thinking") : t("onboarding.mods.busy.matching")}
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
            <h3>{t("onboarding.mods.plan.title")}</h3>
            {answers.assistMode === "ai" ? (
              <Badge tone="accent">Flint AI</Badge>
            ) : answers.assistMode === "fallback" ? (
              <span className="ob-assist-mode">{t("onboarding.mods.plan.smartSearch")}</span>
            ) : null}
            <span className="faint">
              {t("onboarding.mods.plan.toInstall", { count: installing.length })} · {bytes(totalSize)} ·{" "}
              {stale ? t("onboarding.mods.plan.checking", { version: answers.version }) : t("onboarding.mods.plan.checked", { version: answers.version })}
            </span>
            <Button size="sm" variant="ghost" icon="plus" onClick={() => setPicker({})}>
              {t("onboarding.mods.plan.addMod")}
            </Button>
          </header>
          {blocked.length > 0 && (
            <p className="ob-plan-warn" role="status">
              <Icon name="alert" size={14} />
              {blocked.length > 1
                ? t("onboarding.mods.plan.blockedMany", { count: blocked.length })
                : blocked[0].message
                  ? t("onboarding.mods.plan.blockedOne", { mod: blocked[0].title, reason: blocked[0].message })
                  : t("onboarding.mods.plan.blockedOneNoReason", { mod: blocked[0].title })}
            </p>
          )}
          {groupPlan(answers.plan.filter((i) => i.status !== "not-needed")).map((g) => {
            const rows = g.items;
            const groupNote = GROUP_NOTE[g.kind];
            return (
              <div key={g.id} className={`ob-plan-group is-${g.kind}`}>
                <h4>
                  {goalLabel(g.title)}
                  {groupNote && <span className="faint">{t(groupNote)}</span>}
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
          {t("onboarding.mods.searchYourself")}
        </button>
      )}
      {settings.performanceMods && <PerformanceSetNote />}
      <ModPicker
        open={!!picker}
        onClose={() => setPicker(null)}
        gameVersion={answers.version}
        title={picker?.replace ? t("onboarding.picker.replaceTitle") : t("onboarding.picker.addTitle")}
        onPick={(hit) => void addFromSearch(hit, picker?.replace)}
      />
    </div>
  );
}

// ---------------- Startup ----------------

export function StartupStep({ answers, set, setCanContinue }: StepProps) {
  const t = useT();
  useEffect(() => setCanContinue(true), [setCanContinue]);
  return (
    <div className="ob-step ob-narrow">
      <StepHead title={t("onboarding.startup.title")} body={t("onboarding.startup.body")} />
      <RadioCards
        name="startup"
        className="ob-yesno"
        value={answers.launchAtLogin ? "yes" : "no"}
        onChange={(v) => set({ launchAtLogin: v === "yes" })}
        options={[
          { value: "no", title: t("onboarding.startup.no"), description: t("onboarding.startup.noBody") },
          { value: "yes", title: t("onboarding.startup.yes"), description: t("onboarding.startup.yesBody") },
        ]}
      />
    </div>
  );
}

// ---------------- Path ----------------

/**
 * After sign-in (and the Import step): the guided setup, or Home straight away with Flint's defaults, or with
 * the profiles just imported (`imported`: how many).
 */
export function PathStep({ answers, set, setCanContinue, error, imported = 0 }: StepProps & { error: string | null; imported?: number }) {
  const t = useT();
  useEffect(() => setCanContinue(true), [setCanContinue]);
  return (
    <div className="ob-step ob-narrow">
      <StepHead title={t("onboarding.path.title")} body={t("onboarding.path.body")} />
      <RadioCards
        name="path"
        className="ob-yesno"
        value={answers.path}
        onChange={(path) => set({ path })}
        options={[
          {
            value: "setup",
            title: t("onboarding.path.setup"),
            description: imported ? t("onboarding.path.setupBodyImported") : t("onboarding.path.setupBody"),
          },
          {
            value: "skip",
            title: t("onboarding.path.skip"),
            description: imported
              ? imported === 1
                ? t("onboarding.path.skipBodyImportedOne")
                : t("onboarding.path.skipBodyImportedMany")
              : t("onboarding.path.skipBody", { version: DEFAULT_ANSWERS.version }),
          },
        ]}
      />
      {error && (
        <div className="ob-error" role="alert">
          <Icon name="alert" size={16} />
          <span>{error}</span>
        </div>
      )}
    </div>
  );
}

// ---------------- Appearance ----------------

export function AppearanceStep({ setCanContinue }: StepProps) {
  const { settings, update } = useSettings();
  const t = useT();
  useEffect(() => setCanContinue(true), [setCanContinue]);
  const effects = EFFECT_TEXT[settings.effects] ?? EFFECT_TEXT.full;
  return (
    <div className="ob-step ob-mid">
      <StepHead title={t("onboarding.appearance.title")} body={t("onboarding.appearance.body")} />
      <div className="ob-appearance">
        <section>
          <h3 className="ob-label">{t("onboarding.appearance.theme")}</h3>
          <ThemePicker value={settings.theme} onChange={(theme) => update({ theme })} />
        </section>
        <section>
          <h3 className="ob-label">{t("onboarding.appearance.typography")}</h3>
          <FontPicker value={settings.font} onChange={(font) => update({ font })} />
        </section>
        <section className="ob-rows">
          <div className="ob-row">
            <div>
              <strong>{t("onboarding.appearance.sounds")}</strong>
              <span className="muted">{t("onboarding.appearance.soundsBody")}</span>
            </div>
            <Switch checked={settings.sounds} onChange={(sounds) => update({ sounds })} label={t("onboarding.appearance.sounds")} />
          </div>
          <div className="ob-row">
            <div>
              <strong>{t("onboarding.appearance.volume")}</strong>
            </div>
            <div className="ob-row-inline">
              <Slider
                value={Math.round(settings.volume * 100)}
                min={0}
                max={100}
                step={5}
                label={t("onboarding.appearance.volumeLabel")}
                disabled={!settings.sounds}
                onChange={(v) => update({ volume: v / 100 })}
                format={(v) => t("onboarding.appearance.volumeValue", { value: v })}
              />
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
                {t("onboarding.appearance.preview")}
              </Button>
            </div>
          </div>
          <div className="ob-row">
            <div>
              <strong>{t("onboarding.appearance.animations")}</strong>
              <span className="muted">{t("onboarding.appearance.animationsBody")}</span>
            </div>
            <Switch checked={settings.animations} onChange={(animations) => update({ animations })} label={t("onboarding.appearance.animations")} />
          </div>
          <div className="ob-row">
            <div>
              <strong>{t("onboarding.appearance.parallax")}</strong>
              <span className="muted">{t("onboarding.appearance.parallaxBody")}</span>
            </div>
            <Switch checked={settings.parallax} onChange={(parallax) => update({ parallax })} label={t("onboarding.appearance.parallax")} />
          </div>
          <div className="ob-row">
            <div>
              <strong>{t("onboarding.appearance.effects")}</strong>
              <span className="muted">{t(effects.note)}</span>
            </div>
            <Segmented
              label={t("onboarding.appearance.effects")}
              value={settings.effects}
              onChange={(level) => update({ effects: level })}
              options={EFFECTS.map((e) => ({ value: e.id, label: t(EFFECT_TEXT[e.id].name) }))}
            />
          </div>
        </section>
      </div>
    </div>
  );
}

export function Segment({ value, onChange }: { value: "low" | "medium" | "high"; onChange: (v: "low" | "medium" | "high") => void }) {
  const t = useT();
  return (
    <Segmented
      label={t("onboarding.effort.label")}
      value={value}
      onChange={onChange}
      options={[
        { value: "low", label: t("onboarding.effort.low") },
        { value: "medium", label: t("onboarding.effort.medium") },
        { value: "high", label: t("onboarding.effort.high") },
      ]}
    />
  );
}
