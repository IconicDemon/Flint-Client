import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { api, errorText, subscribe, type InstallItem, type PlanItem, type ProfileState } from "../../lib/api";
import { bytes } from "../../lib/format";
import { useT, type MessageKey } from "../../lib/i18n";
import { THEMES, useSettings } from "../../lib/settings";
import { sound } from "../../lib/sound";
import { jobActive, seqOf, useStore } from "../../lib/store";
import { VERSIONS, defaultProfileName, loaderLabel, platformBlock, versionMeta } from "../../lib/versions";
import { EFFECT_TEXT, FONT_TEXT } from "../../ui/AppearancePickers";
import { Badge, Button, Progress, Spinner } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { FlintMark } from "../../ui/Logo";
import { WindowChrome } from "../shell/WindowChrome";
import {
  AccountStep,
  AppearanceStep,
  Avatar,
  DEFAULT_ANSWERS,
  ModsStep,
  PathStep,
  PerformanceSetNote,
  StartupStep,
  VersionStep,
  WelcomeStep,
  type Answers,
  type StepProps,
} from "./steps";
import { installBatches, installedGroups, isPick, joinResolved, pickRequests, planSelection, reviewGroups, type InstalledGroups, type PlanEntry } from "./plan";
import { useImportFlow } from "../import/useImportFlow";
import { ImportFooter, ImportStep, importPrimary } from "./ImportStep";

const STEPS = [
  { key: "welcome", label: "onboarding.steps.welcome" },
  { key: "account", label: "onboarding.steps.account" },
  { key: "import", label: "onboarding.steps.import" },
  { key: "path", label: "onboarding.steps.path" },
  { key: "version", label: "onboarding.steps.version" },
  { key: "mods", label: "onboarding.steps.mods", skippable: true },
  { key: "startup", label: "onboarding.steps.startup" },
  { key: "appearance", label: "onboarding.steps.appearance" },
  { key: "review", label: "onboarding.steps.review" },
  { key: "setup", label: "onboarding.steps.setup" },
] as const satisfies readonly { key: string; label: MessageKey; skippable?: boolean }[];

type StepKey = (typeof STEPS)[number]["key"];
const IDX = Object.fromEntries(STEPS.map((s, i) => [s.key, i])) as Record<StepKey, number>;
const REVIEW = IDX.review;
const SETUP = IDX.setup;
const DONE = STEPS.length;
// 3: the start choice after sign-in, and no Community step (step numbers moved).
// 4: the Import step after Account (step numbers moved again).
const FLOW_VERSION = 4;

type Persisted = {
  completed?: boolean;
  step?: number;
  answers?: Partial<Answers>;
  profileId?: string | null;
  modsInstalled?: boolean;
  /** Profiles the Import step brought in, newest played first; "Go to Home" starts with the first. */
  imported?: string[];
  completedAt?: number;
  flow?: number;
};

export function Onboarding({ initial, onDone }: { initial: Persisted; onDone: () => void }) {
  const store = useStore();
  const { settings } = useSettings();
  const t = useT();
  const [step, setStep] = useState(() => (initial.completed || initial.flow !== FLOW_VERSION ? 0 : Math.min(initial.step ?? 0, REVIEW)));
  const [answers, setAnswers] = useState<Answers>(() => ({ ...DEFAULT_ANSWERS, ...initial.answers }));
  const [canContinue, setCanContinue] = useState(false);
  const [returnToReview, setReturnToReview] = useState(false);
  const [direction, setDirection] = useState<1 | -1>(1);
  const meta = useRef({ profileId: initial.profileId ?? null, modsInstalled: initial.modsInstalled ?? false });
  const importFlow = useImportFlow();
  const imported = useRef<string[]>(initial.flow === FLOW_VERSION ? (initial.imported ?? []) : []);

  const persist = useCallback(
    (extra: Partial<Persisted> = {}) =>
      api
        .onboardingSet({ completed: false, flow: FLOW_VERSION, step, answers, profileId: meta.current.profileId, modsInstalled: meta.current.modsInstalled, imported: imported.current, ...extra })
        .catch(() => {}),
    [step, answers],
  );

  // What the Import step brought in, kept for "Go to Home" (a restart keeps it too).
  const persistNow = useRef(persist);
  persistNow.current = persist;
  const finishedImport = importFlow.finished;
  useEffect(() => {
    const ids = finishedImport?.result.profiles.flatMap((p) => (p.state === "done" && p.profileId ? [p.profileId] : [])) ?? [];
    if (!ids.length) return;
    imported.current = [...new Set([...imported.current, ...ids])];
    void persistNow.current();
  }, [finishedImport]);

  useEffect(() => {
    if (step >= SETUP) return;
    const timer = window.setTimeout(() => void persist(), 300);
    return () => window.clearTimeout(timer);
  }, [persist, step]);

  useEffect(() => {
    if (!answers.accountUuid && store.activeAccount && initial.answers?.accountUuid) {
      setAnswers((a) => ({ ...a, accountUuid: store.activeAccount!.uuid }));
    }
  }, [store.activeAccount, answers.accountUuid, initial.answers]);

  const set = useCallback<StepProps["set"]>((patch) => setAnswers((a) => ({ ...a, ...(typeof patch === "function" ? patch(a) : patch) })), []);
  const [installed, setInstalled] = useState<InstalledGroups | null>(null);

  // Each step keeps its scroll position for when the player comes back to it (Back, or the Review
  // after an edit); moving forward to a step starts at its top.
  const mainRef = useRef<HTMLElement>(null);
  const scrolls = useRef<Record<number, number>>({});
  const restoreScroll = useRef(false);
  const go = (next: number) => {
    sound.play("nav");
    scrolls.current[step] = mainRef.current?.scrollTop ?? 0;
    restoreScroll.current = next < step || (next === REVIEW && returnToReview);
    setDirection(next > step ? 1 : -1);
    setCanContinue(false);
    setStep(next);
  };
  // Marks the bars when content runs under them, so a long list visibly continues past the edge.
  const rootRef = useRef<HTMLDivElement>(null);
  const edges = useCallback(() => {
    const main = mainRef.current;
    const root = rootRef.current;
    if (!main || !root) return;
    root.toggleAttribute("data-scrolled", main.scrollTop > 2);
    root.toggleAttribute("data-more", main.scrollHeight - main.clientHeight - main.scrollTop > 2);
  }, []);
  useLayoutEffect(() => {
    const main = mainRef.current;
    if (!main) return;
    main.scrollTop = restoreScroll.current ? (scrolls.current[step] ?? 0) : 0;
    edges();
    // Content grows after a search or when a list loads; the edge marks follow it.
    const observer = new ResizeObserver(edges);
    observer.observe(main);
    for (const child of Array.from(main.children)) observer.observe(child);
    return () => observer.disconnect();
  }, [step, edges]);
  // "Skip, go to Home": what Setup would do with its defaults, without the steps. The profile is made
  // and selected and its game files start downloading in the background; Home shows that progress.
  // After an import, the profile played last of those imported is the one, and nothing new is made.
  const [leaving, setLeaving] = useState(false);
  const [leaveError, setLeaveError] = useState<string | null>(null);
  const skipToHome = async () => {
    if (leaving) return;
    setLeaving(true);
    setLeaveError(null);
    try {
      await api.settingsSet({ ...settings, launchAtLogin: false });
      const { profiles } = await api.profiles();
      const fromImport = imported.current.map((id) => profiles.find((p) => p.id === id)).find((p) => !!p);
      let profile = profiles.find((p) => p.id === meta.current.profileId) ?? fromImport ?? profiles[0];
      if (!profile) {
        const version = platformBlock(DEFAULT_ANSWERS.version, store.info) ? (VERSIONS.find((v) => !platformBlock(v.id, store.info))?.id ?? DEFAULT_ANSWERS.version) : DEFAULT_ANSWERS.version;
        profile = await api.createProfile(defaultProfileName(version), version, versionMeta(version).fabric ? "fabric" : "vanilla", true);
      }
      meta.current.profileId = profile.id;
      await api.selectProfile(profile.id);
      if (answers.accountUuid) await api.setActiveAccount(answers.accountUuid);
      await api.onboardingSet({ completed: true, flow: FLOW_VERSION, completedAt: Math.floor(Date.now() / 1000), answers, profileId: profile.id, modsInstalled: false, imported: imported.current, skipped: true });
      void api.installProfile(profile.id).catch(() => {});
      await store.reloadProfiles();
      await store.reloadAccounts();
      await store.selectProfile(profile.id).catch(() => {});
      sound.play("success");
      onDone();
    } catch (e) {
      setLeaveError(errorText(e));
      setLeaving(false);
      sound.play("error");
    }
  };

  // The Import step moves through its own screens first; its footer and Enter use the screen's main button.
  const importAction = STEPS[step]?.key === "import" ? importPrimary(importFlow, () => go(step + 1)) : null;
  const importReady = !!importAction && !importAction.disabled && !importAction.loading;
  useEffect(() => {
    if (STEPS[step]?.key === "import") setCanContinue(importReady);
  }, [step, importReady]);

  const next = () => {
    if (STEPS[step].key === "import") {
      if (importReady) importAction!.run();
      return;
    }
    if (STEPS[step].key === "path" && answers.path === "skip") {
      void skipToHome();
      return;
    }
    if (returnToReview && step < REVIEW) {
      setReturnToReview(false);
      go(REVIEW);
    } else go(step + 1);
  };
  const skip = () => {
    if (STEPS[step].key === "mods") set({ plan: [], assistMode: "skipped" });
    next();
  };

  const nextRef = useRef(next);
  nextRef.current = next;
  const footerActive = step < SETUP && STEPS[step]?.key !== "welcome";
  useEffect(() => {
    if (!footerActive || !canContinue) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Enter" || e.isComposing || e.metaKey || e.ctrlKey || e.altKey || e.shiftKey) return;
      const el = e.target as HTMLElement;
      if (el.closest("button, a, textarea, select, [role=dialog], [contenteditable=true]")) return;
      if (el instanceof HTMLInputElement && !["text", "search"].includes(el.type)) return;
      e.preventDefault();
      nextRef.current();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [footerActive, canContinue]);

  const props: StepProps = { answers, set, setCanContinue };
  const current: StepKey | "done" = step < STEPS.length ? STEPS[step].key : "done";
  const progress = Math.min(1, Math.max(0, step - 1 + (current === "done" ? 1 : 0)) / (STEPS.length - 2));
  const counted = STEPS.length - 1;

  return (
    <div className="ob" data-step={current} ref={rootRef}>
      <WindowChrome />
      <div className="ob-glimpse" aria-hidden style={{ backgroundImage: `url(${versionMeta(answers.version).wallpaper})` }} />
      <header className="ob-top" data-tauri-drag-region>
        {step > 0 && step < DONE && (
          <div className="ob-stepper" aria-label={t("onboarding.stepper.label", { step, total: counted, name: t(STEPS[step].label) })}>
            <span className="ob-stepper-text" data-tauri-drag-region>
              <strong>{t(STEPS[step].label)}</strong>
              <span className="faint">
                {step} / {counted}
              </span>
            </span>
            <ol className="ob-dots">
              {STEPS.slice(1).map((s, i) => (
                <li key={s.key} className={i + 1 < step ? "is-done" : i + 1 === step ? "is-current" : ""} title={t(s.label)} />
              ))}
            </ol>
          </div>
        )}
      </header>
      <div className="ob-progress" aria-hidden>
        <span style={{ transform: `scaleX(${progress})` }} />
      </div>

      <main className={`ob-main scroll dir-${direction}`} key={step} ref={mainRef} onScroll={edges}>
        {current === "welcome" && <WelcomeStep {...props} onStart={next} />}
        {current === "account" && <AccountStep {...props} />}
        {current === "import" && <ImportStep flow={importFlow} />}
        {current === "version" && <VersionStep {...props} />}
        {current === "mods" && <ModsStep {...props} />}
        {current === "startup" && <StartupStep {...props} />}
        {current === "path" && <PathStep {...props} error={leaveError} imported={imported.current.length} />}
        {current === "appearance" && <AppearanceStep {...props} />}
        {current === "review" && (
          <Review
            answers={answers}
            setCanContinue={setCanContinue}
            edit={(i) => {
              setReturnToReview(true);
              go(i);
            }}
          />
        )}
        {current === "setup" && (
          <Setup
            answers={answers}
            meta={meta}
            persist={persist}
            onComplete={(groups) => {
              setInstalled(groups);
              go(DONE);
            }}
            onBack={() => go(REVIEW)}
          />
        )}
        {current === "done" && <Done answers={answers} installed={installed} onDone={onDone} />}
      </main>

      {current === "import" && (
        <footer className={`ob-foot ${importFlow.screen === "profiles" ? "is-import-bar" : ""}`}>
          <ImportFooter flow={importFlow} onBack={() => go(step - 1)} onSkip={() => go(step + 1)} onDone={() => go(step + 1)} />
        </footer>
      )}
      {step < SETUP && current !== "welcome" && current !== "import" && (
        <footer className="ob-foot">
          <div>
            {step > 0 && (
              <Button variant="ghost" icon="chevronLeft" onClick={() => go(step - 1)}>
                {t("common.back")}
              </Button>
            )}
          </div>
          <div className="ob-foot-right">
            {"skippable" in STEPS[step] && (
              <Button variant="ghost" onClick={skip}>
                {t("onboarding.footer.skip")}
              </Button>
            )}
            {current === "review" ? (
              <Button variant="primary" size="lg" onClick={next} disabled={!canContinue} silent>
                {t("onboarding.footer.setUp")}
              </Button>
            ) : current === "path" && answers.path === "skip" ? (
              <Button variant="primary" size="lg" iconRight="arrowRight" onClick={next} disabled={!canContinue} loading={leaving}>
                {t("onboarding.actions.goHome")}
              </Button>
            ) : (
              <Button variant="primary" size="lg" iconRight={returnToReview ? undefined : "arrowRight"} onClick={next} disabled={!canContinue}>
                {returnToReview ? t("onboarding.actions.backToReview") : t("common.continue")}
              </Button>
            )}
          </div>
        </footer>
      )}
    </div>
  );
}

function Review({ answers, edit, setCanContinue }: { answers: Answers; edit: (step: number) => void; setCanContinue: (v: boolean) => void }) {
  const store = useStore();
  const { settings } = useSettings();
  const t = useT();
  const account = store.accounts.find((a) => a.uuid === answers.accountUuid) ?? null;
  const v = versionMeta(answers.version);
  const groups = reviewGroups(answers.plan);
  const planVersion = answers.planVersion ?? answers.version;
  const ready = !!account && answers.profileName.trim().length > 0;
  useEffect(() => setCanContinue(ready), [ready, setCanContinue]);
  const font = FONT_TEXT[settings.font] ?? FONT_TEXT.default;
  const effects = EFFECT_TEXT[settings.effects] ?? EFFECT_TEXT.full;

  const Group = ({ title, step, children }: { title: string; step: number; children: React.ReactNode }) => (
    <section className="ob-review-group">
      <div className="ob-review-head">
        <h3>{title}</h3>
        <button type="button" className="ob-edit" onClick={() => edit(step)}>
          {t("onboarding.review.edit")}
        </button>
      </div>
      <div className="ob-review-body">{children}</div>
    </section>
  );

  return (
    <div className="ob-step ob-mid">
      <header className="ob-head">
        <h1>{t("onboarding.review.title")}</h1>
        <p className="ob-sub">{t("onboarding.review.body")}</p>
      </header>
      <div className="ob-review">
        <Group title={t("onboarding.review.sections.account")} step={IDX.account}>
          {account ? (
            <span className="ob-review-account">
              <Avatar account={account} size={28} /> {account.username}
            </span>
          ) : (
            <span className="ob-review-warn">
              <Icon name="alert" size={14} /> {t("onboarding.review.noAccount")}
            </span>
          )}
        </Group>
        <Group title={t("onboarding.review.sections.profile")} step={IDX.version}>
          <strong className="ivory">{answers.profileName}</strong>
          <span className="muted">{t("onboarding.review.profileVersion", { version: answers.version, loader: loaderLabel(v.fabric ? "fabric" : "vanilla") })}</span>
        </Group>
        <Group title={t("onboarding.review.sections.mods")} step={IDX.mods}>
          {!v.fabric ? (
            <span className="muted">{t("onboarding.review.vanilla")}</span>
          ) : (
            <div className="ob-review-mods">
              {answers.plan.length > 0 && planVersion !== answers.version && (
                <p className="ob-review-note">
                  <Icon name="info" size={13} /> {t("onboarding.review.recheckNote", { checked: planVersion, version: answers.version })}
                </p>
              )}
              <ReviewList label={t("onboarding.groups.picks")} items={groups.picks} empty={t("onboarding.review.picksEmpty")} />
              {groups.dependencies.length > 0 && (
                <ReviewList label={t("onboarding.groups.dependencies")} hint={t("onboarding.review.dependenciesHint")} items={groups.dependencies} />
              )}
              {(groups.optional.length > 0 || groups.optionalOff.length > 0) && (
                <ReviewList
                  label={t("onboarding.groups.optional")}
                  hint={groups.optionalOff.length ? t("onboarding.review.optionalHintLeftOff", { count: groups.optionalOff.length }) : t("onboarding.review.optionalHint")}
                  items={groups.optional}
                  empty={t("onboarding.review.optionalEmpty")}
                />
              )}
              {groups.blocked.length > 0 && <ReviewList label={t("onboarding.review.blocked")} hint={t("onboarding.review.blockedHint")} items={groups.blocked} muted />}
              {settings.performanceMods && (
                <div className="ob-review-list">
                  <span className="ob-review-list-label">
                    {t("onboarding.perf.title")}
                    <span className="faint">{t("onboarding.perf.hint")}</span>
                  </span>
                  <PerformanceSetNote compact />
                </div>
              )}
            </div>
          )}
        </Group>
        <Group title={t("onboarding.review.sections.startup")} step={IDX.startup}>
          <span className="muted">{answers.launchAtLogin ? t("onboarding.review.launchAtLogin") : t("onboarding.review.noLaunchAtLogin")}</span>
        </Group>
        <Group title={t("onboarding.review.sections.appearance")} step={IDX.appearance}>
          {/* "Flint Ember": the brand and the theme's name, both names. */}
          <span className="muted">Flint {THEMES.find((theme) => theme.id === settings.theme)?.name ?? "Ember"}</span>
        </Group>
        <Group title={t("onboarding.review.sections.typography")} step={IDX.appearance}>
          <span className="muted">
            {t(font.name)} · {t(font.note)}
          </span>
        </Group>
        <Group title={t("onboarding.review.sections.accessibility")} step={IDX.appearance}>
          <span className="muted">
            {[
              settings.animations ? t("onboarding.review.animationsOn") : t("onboarding.review.animationsOff"),
              settings.parallax ? t("onboarding.review.parallaxOn") : t("onboarding.review.parallaxOff"),
              t("onboarding.review.effects", { name: t(effects.name) }),
              settings.sounds ? t("onboarding.review.soundsOn", { volume: Math.round(settings.volume * 100) }) : t("onboarding.review.soundsOff"),
            ].join(" · ")}
          </span>
        </Group>
      </div>
    </div>
  );
}

function ReviewList({ label, hint, items, empty, muted }: { label: string; hint?: string; items: PlanEntry[]; empty?: string; muted?: boolean }) {
  return (
    <div className="ob-review-list">
      <span className="ob-review-list-label">
        {label}
        {items.length > 0 && <span className="ob-review-count">{items.length}</span>}
        {hint && <span className="faint">{hint}</span>}
      </span>
      {items.length ? (
        <span className="ob-review-badges">
          {items.map((m) => (
            <Badge key={m.projectId} tone={muted ? "neutral" : m.source === "assistant" ? "accent" : "neutral"}>
              {m.title}
            </Badge>
          ))}
        </span>
      ) : (
        <span className="muted">{empty}</span>
      )}
    </div>
  );
}

type StageKey = "prefs" | "profile" | "validate" | "check" | "resolve" | "download" | "finalize";
const STAGES: { key: StageKey; label: MessageKey }[] = [
  { key: "prefs", label: "onboarding.setup.stages.prefs" },
  { key: "profile", label: "onboarding.setup.stages.profile" },
  { key: "validate", label: "onboarding.setup.stages.validate" },
  { key: "check", label: "onboarding.setup.stages.check" },
  { key: "resolve", label: "onboarding.setup.stages.resolve" },
  { key: "download", label: "onboarding.setup.stages.download" },
  { key: "finalize", label: "onboarding.setup.stages.finalize" },
];

// "Installation paused." is the backend's own text for a cancelled download.
const isCancel = (message: string) => message === "Installation paused." || /cancel/i.test(message);
const toInstall = (i: PlanItem): InstallItem => ({ versionId: i.versionId!, kind: i.kind, source: i.source, reason: i.reason });

/** Waits until no download job for the profile is queued or running (the backend's own job list). */
async function jobsSettled(profileId: string) {
  for (let i = 0; i < 1200; i++) {
    const jobs = await api.jobs().catch(() => []);
    if (!jobs.some((j) => j.profileId === profileId && jobActive(j))) return;
    await new Promise((r) => window.setTimeout(r, 500));
  }
}

function Setup({
  answers,
  meta,
  persist,
  onComplete,
  onBack,
}: {
  answers: Answers;
  meta: React.MutableRefObject<{ profileId: string | null; modsInstalled: boolean }>;
  persist: (extra?: Partial<Persisted>) => Promise<void>;
  onComplete: (installed: InstalledGroups | null) => void;
  onBack: () => void;
}) {
  const store = useStore();
  const { settings, update } = useSettings();
  const t = useT();
  // Stages already finished stay ticked through a pause or retry; running them again is quick.
  const finished = useRef(new Set<StageKey>());
  const [done, setDone] = useState<Set<StageKey>>(new Set());
  const [active, setActive] = useState<StageKey | null>(null);
  const [failed, setFailed] = useState<{ stage: StageKey; message: string } | null>(null);
  const [live, setLive] = useState<ProfileState | null>(null);
  const [paused, setPaused] = useState(false);
  const [pausing, setPausing] = useState(false);
  const [complete, setComplete] = useState(false);
  const [notes, setNotes] = useState<string[]>([]);
  // What the latest file check found missing; replaced on each run, not added to the notes.
  const [missing, setMissing] = useState<number | null>(null);
  const note = (text: string) => setNotes((n) => (n.includes(text) ? n : [...n, text]));
  const running = useRef(false);

  const run = useCallback(async () => {
    if (running.current) return;
    running.current = true;
    setFailed(null);
    setPaused(false);
    setPausing(false);
    const mark = (k: StageKey) => {
      finished.current.add(k);
      setDone(new Set(finished.current));
    };
    let stage: StageKey = "prefs";
    const begin = (k: StageKey) => {
      stage = k;
      setActive(k);
    };
    const v = versionMeta(answers.version);
    const loader = v.fabric ? "fabric" : "vanilla";
    try {
      begin("prefs");
      update({ launchAtLogin: answers.launchAtLogin });
      await api.settingsSet({ ...settings, launchAtLogin: answers.launchAtLogin });
      await persist({ step: SETUP });
      mark("prefs");

      begin("profile");
      const existing = meta.current.profileId ? (await api.profiles()).profiles.find((p) => p.id === meta.current.profileId) : undefined;
      const profile = existing ?? (await api.createProfile(answers.profileName.trim(), answers.version, loader, true));
      if (existing && existing.name !== answers.profileName.trim()) await api.renameProfile(existing.id, answers.profileName.trim());
      meta.current.profileId = profile.id;
      await api.selectProfile(profile.id);
      if (answers.accountUuid) await api.setActiveAccount(answers.accountUuid);
      await persist({ step: SETUP });
      mark("profile");

      begin("validate");
      const catalog = await api.catalog().catch(() => null);
      const entry = catalog?.versions.find((c) => c.id === answers.version);
      if (catalog && !catalog.offline && entry && !entry.available) throw new Error(t("onboarding.setup.errors.versionGone", { version: answers.version }));
      if (catalog && !catalog.offline && entry && loader === "fabric" && !entry.fabric) throw new Error(t("onboarding.setup.errors.noFabric", { version: answers.version }));
      mark("validate");

      begin("check");
      const check = await api.checkProfile(profile.id);
      setMissing(!check.ready && check.missingBytes ? check.missingBytes : null);
      mark("check");

      begin("resolve");
      // Exactly what the Review listed: included picks, and only the optional extras the player ticked.
      const { on } = planSelection(answers.plan);
      const picks = answers.plan.filter((i) => isPick(i) && on.has(i.projectId));
      const wantOptional = new Set(answers.plan.filter((i) => i.source === "optional" && on.has(i.projectId)).map((i) => i.projectId));
      let required: PlanItem[] = [];
      let optional: PlanItem[] = [];
      if (loader === "fabric" && picks.length && !meta.current.modsInstalled) {
        const plans = await Promise.all(pickRequests(picks).map((batch) => api.resolveMods({ profileId: profile.id }, batch)));
        const resolved = joinResolved(plans.map((p) => p.items));
        const blocked = resolved.filter((i) => i.status === "conflict" || i.status === "unavailable");
        if (blocked.length) note(t("onboarding.setup.notes.leftOut", { mods: blocked.map((b) => `${b.title} (${b.message})`).join(", ") }));
        ({ required, optional } = installBatches(resolved, wantOptional));
      }
      mark("resolve");

      begin("download");
      await api.installProfile(profile.id);
      if (required.length) {
        const result = await api.installMods(profile.id, required.map(toInstall));
        if (result.failed.length)
          throw new Error(t("onboarding.setup.errors.requiredFailed", { count: result.failed.length, errors: result.failed.map((f) => f.message).join("; ") }));
      }
      if (optional.length) {
        const titles = optional.map((o) => o.title).join(", ");
        try {
          const result = await api.installMods(profile.id, optional.map(toInstall));
          if (result.failed.length) note(t("onboarding.setup.notes.optionalSkipped", { mods: titles, error: result.failed.map((f) => f.message).join("; ") }));
        } catch (e) {
          // A pause is a pause, not a skipped extra: resuming installs it.
          if (isCancel(errorText(e))) throw e;
          note(t("onboarding.setup.notes.optionalSkipped", { mods: titles, error: errorText(e) }));
        }
      }
      if (required.length || optional.length) meta.current.modsInstalled = true;
      // Success only once every download job for the profile has finished and the backend reports
      // every game file present.
      await jobsSettled(profile.id);
      const verified = await api.checkProfile(profile.id);
      if (!verified.ready) throw new Error(t("onboarding.setup.errors.filesMissing", { count: verified.missingFiles }));
      mark("download");

      begin("finalize");
      if (answers.launchAtLogin) {
        const enabled = await api.launchAtLoginSet(true);
        if (!enabled) note(t("onboarding.setup.notes.launchAtLoginFailed"));
      } else if (await api.launchAtLoginGet().catch(() => false)) {
        await api.launchAtLoginSet(false).catch(() => {});
      }
      // What the profile really has now, including Flint's performance set and its libraries.
      const content = loader === "fabric" ? await api.content(profile.id).catch(() => null) : null;
      const installed = content ? installedGroups([...content.mods, ...content.resourcepacks]) : null;
      await api.onboardingSet({ completed: true, flow: FLOW_VERSION, completedAt: Math.floor(Date.now() / 1000), answers, profileId: profile.id, modsInstalled: meta.current.modsInstalled });
      await store.reloadProfiles();
      await store.reloadAccounts();
      await store.selectProfile(profile.id).catch(() => {});
      mark("finalize");
      setActive(null);
      setComplete(true);
      sound.play("success");
      window.setTimeout(() => onComplete(installed), 650);
    } catch (e) {
      const message = errorText(e);
      const wasPaused = isCancel(message);
      setPaused(wasPaused);
      // A pause shows Flint's own explanation (in the render), not the backend's message.
      setFailed({ stage, message });
      if (!wasPaused) sound.play("error");
    } finally {
      setPausing(false);
      running.current = false;
    }
  }, [answers, meta, persist, settings, update, store, onComplete, t]);

  const runRef = useRef(run);
  runRef.current = run;
  useEffect(() => {
    void runRef.current();
  }, []);

  useEffect(() => {
    // Events can arrive out of order with a fetched state; the backend's sequence number decides.
    const offState = subscribe<ProfileState>("profile-state", (s) => {
      if (s.profileId === meta.current.profileId) setLive((prev) => (prev && seqOf(prev) > seqOf(s) ? prev : s));
    });
    return offState;
  }, [meta]);

  const modJob = store.jobs.find((j) => j.profileId === meta.current.profileId && j.kind === "install" && jobActive(j)) ?? null;
  const downloadDetail = useMemo(() => {
    if (active !== "download") return null;
    if (pausing) return t("onboarding.setup.download.pausing");
    if (modJob) {
      const progress = { job: modJob.title, done: Math.min(modJob.itemsDone + 1, modJob.itemsTotal), total: modJob.itemsTotal };
      return modJob.file ? t("onboarding.setup.download.modJobFile", { ...progress, file: modJob.file }) : t("onboarding.setup.download.modJob", progress);
    }
    if (live?.state === "checking") return t("onboarding.setup.download.checking");
    if (live?.state === "downloading" && live.bytesTotal) return t("onboarding.setup.download.bytes", { done: bytes(live.bytesDone ?? 0), total: bytes(live.bytesTotal) });
    if (live?.state === "installing") return live.label ?? t("onboarding.setup.download.installing");
    return live?.label ?? t("onboarding.setup.download.preparing");
  }, [active, live, modJob, pausing, t]);
  const downloadValue = modJob
    ? modJob.bytesTotal
      ? Math.min(1, (modJob.itemsDone + modJob.bytesDone / modJob.bytesTotal) / Math.max(1, modJob.itemsTotal))
      : modJob.itemsDone / Math.max(1, modJob.itemsTotal)
    : live?.state === "downloading"
      ? (live.progress ?? null)
      : null;
  const pause = () => {
    setPausing(true);
    if (modJob) void api.cancelJob(modJob.id);
    else if (meta.current.profileId) void api.cancelInstall(meta.current.profileId);
  };

  const finishLater = async () => {
    await api.onboardingSet({ completed: true, completedAt: Math.floor(Date.now() / 1000), answers, profileId: meta.current.profileId, modsInstalled: meta.current.modsInstalled, deferred: true });
    await store.reloadProfiles();
    await store.reloadAccounts();
    if (meta.current.profileId) await store.selectProfile(meta.current.profileId).catch(() => {});
    onComplete(null);
  };

  return (
    <div className="ob-step ob-narrow ob-setup">
      <header className="ob-head">
        <h1>
          {complete
            ? t("onboarding.setup.title.ready")
            : paused
              ? t("onboarding.setup.title.paused")
              : failed
                ? t("onboarding.setup.title.failed")
                : t("onboarding.setup.title.running")}
        </h1>
        <p className="ob-sub">{failed ? t("onboarding.setup.bodyFailed") : t("onboarding.setup.body")}</p>
      </header>
      <ol className="ob-stages" aria-live="polite">
        {STAGES.map((s) => {
          const isDone = done.has(s.key);
          const isFailed = failed?.stage === s.key;
          const isActive = active === s.key && !failed && !isDone;
          return (
            <li key={s.key} className={isDone ? "is-done" : isActive ? "is-active" : isFailed ? (paused ? "is-paused" : "is-failed") : ""}>
              <span className="ob-stage-icon">
                {isDone ? <Icon name="check" size={13} /> : isFailed ? <Icon name={paused ? "pause" : "x"} size={13} /> : isActive ? <Spinner size={13} /> : null}
              </span>
              <span className="ob-stage-label">{t(s.label)}</span>
              {isActive && s.key === "download" && <span className="faint ob-stage-detail">{downloadDetail}</span>}
              {isActive && s.key === "download" && <Progress value={downloadValue} label={t("onboarding.setup.progressLabel")} />}
            </li>
          );
        })}
      </ol>
      {(notes.length > 0 || (missing && !complete)) && (
        <ul className="ob-setup-notes faint">
          {missing && !complete ? <li>{t("onboarding.setup.notes.missing", { size: bytes(missing) })}</li> : null}
          {notes.map((n) => (
            <li key={n}>{n}</li>
          ))}
        </ul>
      )}
      {failed && (
        <div className="ob-setup-fail">
          <div className={paused ? "ob-paused" : "ob-error"} role={paused ? "status" : "alert"}>
            <Icon name={paused ? "pause" : "alert"} size={16} />
            <span>{paused ? t("onboarding.setup.pausedMessage") : failed.message}</span>
          </div>
          <div className="ob-setup-actions">
            <Button variant="ghost" onClick={onBack}>
              {t("onboarding.actions.backToReview")}
            </Button>
            {meta.current.profileId && (
              <Button variant="ghost" onClick={() => void finishLater()}>
                {t("onboarding.setup.finishLater")}
              </Button>
            )}
            <Button variant="primary" icon={paused ? "play" : "refresh"} onClick={() => void runRef.current()}>
              {paused ? t("onboarding.setup.resume") : t("common.retry")}
            </Button>
          </div>
        </div>
      )}
      {!failed && !complete && active === "download" && meta.current.profileId && (
        <button type="button" className="ob-link" onClick={pause} disabled={pausing}>
          {pausing ? t("onboarding.setup.download.pausing") : t("onboarding.setup.pause")}
        </button>
      )}
    </div>
  );
}

const INSTALLED_SECTIONS: { key: Exclude<keyof InstalledGroups, "total">; label: MessageKey; hint?: MessageKey }[] = [
  { key: "picks", label: "onboarding.groups.picks" },
  { key: "performance", label: "onboarding.perf.title", hint: "onboarding.done.hints.performance" },
  { key: "dependencies", label: "onboarding.groups.dependencies", hint: "onboarding.done.hints.dependencies" },
  { key: "optional", label: "onboarding.groups.optional", hint: "onboarding.done.hints.optional" },
];

function Done({ answers, installed, onDone }: { answers: Answers; installed: InstalledGroups | null; onDone: () => void }) {
  const store = useStore();
  const t = useT();
  const account = store.accounts.find((a) => a.uuid === answers.accountUuid);
  // With a list of what was installed there is something to read, so Home waits for a click.
  const auto = !installed || installed.total === 0;
  useEffect(() => {
    if (!auto) return;
    const timer = window.setTimeout(onDone, 2200);
    return () => window.clearTimeout(timer);
  }, [auto, onDone]);
  const ready = { profile: answers.profileName, version: answers.version };
  return (
    <div className={`ob-step ob-done ${auto ? "ob-narrow" : "ob-mid"}`}>
      <FlintMark size={44} />
      <h1>{account ? t("onboarding.done.titleNamed", { name: account.username }) : t("onboarding.done.title")}</h1>
      <p className="ob-sub">{auto ? t("onboarding.done.readyAuto", ready) : t("onboarding.done.ready", ready)}</p>
      {installed && installed.total > 0 && (
        <section className="ob-installed" aria-label={t("onboarding.done.installedLabel")}>
          <h3>
            {t("onboarding.done.installedTitle", { profile: answers.profileName })}
            <span className="faint">{t("onboarding.done.installedCount", { count: installed.total })}</span>
          </h3>
          {INSTALLED_SECTIONS.filter((s) => installed[s.key].length).map((s) => (
            <div key={s.key} className="ob-review-list">
              <span className="ob-review-list-label">
                {t(s.label)}
                <span className="ob-review-count">{installed[s.key].length}</span>
                {s.hint && <span className="faint">{t(s.hint)}</span>}
              </span>
              <span className="ob-review-badges">
                {installed[s.key].map((m) => (
                  <Badge key={m.fileName} tone={s.key === "picks" ? "accent" : "neutral"}>
                    <span title={m.versionNumber ? `${m.title} ${m.versionNumber}` : m.title}>{m.title}</span>
                  </Badge>
                ))}
              </span>
            </div>
          ))}
        </section>
      )}
      <Button variant={auto ? "secondary" : "primary"} size={auto ? undefined : "lg"} iconRight={auto ? undefined : "arrowRight"} onClick={onDone} data-autofocus>
        {auto ? t("onboarding.done.goHomeNow") : t("onboarding.actions.goHome")}
      </Button>
    </div>
  );
}
