import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { api, errorText, subscribe, type InstallItem, type PlanItem, type ProfileState } from "../../lib/api";
import { bytes } from "../../lib/format";
import { EFFECTS, FONTS, THEMES, useSettings } from "../../lib/settings";
import { sound } from "../../lib/sound";
import { jobActive, seqOf, useStore } from "../../lib/store";
import { loaderLabel, versionMeta } from "../../lib/versions";
import { Badge, Button, Progress, Spinner } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { FlintMark } from "../../ui/Logo";
import { WindowChrome } from "../shell/WindowChrome";
import {
  AccountStep,
  AppearanceStep,
  Avatar,
  CommunityStep,
  DEFAULT_ANSWERS,
  ModsStep,
  PerformanceSetNote,
  StartupStep,
  VersionStep,
  WelcomeStep,
  type Answers,
  type StepProps,
} from "./steps";
import { installBatches, installedGroups, isPick, joinResolved, pickRequests, planSelection, reviewGroups, type InstalledGroups, type PlanEntry } from "./plan";

const STEPS = [
  { key: "welcome", label: "Welcome" },
  { key: "account", label: "Account" },
  { key: "version", label: "Version" },
  { key: "mods", label: "Mods", skippable: true },
  { key: "startup", label: "Startup" },
  { key: "community", label: "Community", skippable: true },
  { key: "appearance", label: "Appearance" },
  { key: "review", label: "Review" },
  { key: "setup", label: "Setup" },
] as const;

type StepKey = (typeof STEPS)[number]["key"];
const IDX = Object.fromEntries(STEPS.map((s, i) => [s.key, i])) as Record<StepKey, number>;
const REVIEW = IDX.review;
const SETUP = IDX.setup;
const DONE = STEPS.length;
const FLOW_VERSION = 2;

type Persisted = {
  completed?: boolean;
  step?: number;
  answers?: Partial<Answers>;
  profileId?: string | null;
  modsInstalled?: boolean;
  completedAt?: number;
  flow?: number;
};

export function Onboarding({ initial, onDone }: { initial: Persisted; onDone: () => void }) {
  const store = useStore();
  const [step, setStep] = useState(() => (initial.completed || initial.flow !== FLOW_VERSION ? 0 : Math.min(initial.step ?? 0, REVIEW)));
  const [answers, setAnswers] = useState<Answers>(() => ({ ...DEFAULT_ANSWERS, ...initial.answers }));
  const [canContinue, setCanContinue] = useState(false);
  const [returnToReview, setReturnToReview] = useState(false);
  const [direction, setDirection] = useState<1 | -1>(1);
  const meta = useRef({ profileId: initial.profileId ?? null, modsInstalled: initial.modsInstalled ?? false });

  const persist = useCallback(
    (extra: Partial<Persisted> = {}) =>
      api
        .onboardingSet({ completed: false, flow: FLOW_VERSION, step, answers, profileId: meta.current.profileId, modsInstalled: meta.current.modsInstalled, ...extra })
        .catch(() => {}),
    [step, answers],
  );

  useEffect(() => {
    if (step >= SETUP) return;
    const t = window.setTimeout(() => void persist(), 300);
    return () => window.clearTimeout(t);
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
  const next = () => {
    if (returnToReview && step < REVIEW) {
      setReturnToReview(false);
      go(REVIEW);
    } else go(step + 1);
  };
  const skip = () => {
    if (STEPS[step].key === "mods") set({ plan: [], assistMode: "skipped" });
    if (STEPS[step].key === "community") set({ community: "skipped" });
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
          <div className="ob-stepper" aria-label={`Step ${step} of ${counted}: ${STEPS[step].label}`}>
            <span className="ob-stepper-text" data-tauri-drag-region>
              <strong>{STEPS[step].label}</strong>
              <span className="faint">
                {step} / {counted}
              </span>
            </span>
            <ol className="ob-dots">
              {STEPS.slice(1).map((s, i) => (
                <li key={s.key} className={i + 1 < step ? "is-done" : i + 1 === step ? "is-current" : ""} title={s.label} />
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
        {current === "version" && <VersionStep {...props} />}
        {current === "mods" && <ModsStep {...props} />}
        {current === "startup" && <StartupStep {...props} />}
        {current === "community" && <CommunityStep {...props} />}
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

      {step < SETUP && current !== "welcome" && (
        <footer className="ob-foot">
          <div>
            {step > 0 && (
              <Button variant="ghost" icon="chevronLeft" onClick={() => go(step - 1)}>
                Back
              </Button>
            )}
          </div>
          <div className="ob-foot-right">
            {"skippable" in STEPS[step] && (
              <Button variant="ghost" onClick={skip}>
                Skip
              </Button>
            )}
            {current === "review" ? (
              <Button variant="primary" size="lg" onClick={next} disabled={!canContinue} silent>
                Set Up Flint
              </Button>
            ) : (
              <Button variant="primary" size="lg" iconRight={returnToReview ? undefined : "arrowRight"} onClick={next} disabled={!canContinue}>
                {returnToReview ? "Back to review" : "Continue"}
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
  const account = store.accounts.find((a) => a.uuid === answers.accountUuid) ?? null;
  const v = versionMeta(answers.version);
  const groups = reviewGroups(answers.plan);
  const planVersion = answers.planVersion ?? answers.version;
  const ready = !!account && answers.profileName.trim().length > 0;
  useEffect(() => setCanContinue(ready), [ready, setCanContinue]);

  const Group = ({ title, step, children }: { title: string; step: number; children: React.ReactNode }) => (
    <section className="ob-review-group">
      <div className="ob-review-head">
        <h3>{title}</h3>
        <button type="button" className="ob-edit" onClick={() => edit(step)}>
          Edit
        </button>
      </div>
      <div className="ob-review-body">{children}</div>
    </section>
  );

  return (
    <div className="ob-step ob-mid">
      <header className="ob-head">
        <h1>Here's your setup.</h1>
        <p className="ob-sub">Check everything before Flint downloads and installs anything.</p>
      </header>
      <div className="ob-review">
        <Group title="Account" step={IDX.account}>
          {account ? (
            <span className="ob-review-account">
              <Avatar account={account} size={28} /> {account.username}
            </span>
          ) : (
            <span className="ob-review-warn">
              <Icon name="alert" size={14} /> No account connected
            </span>
          )}
        </Group>
        <Group title="Profile" step={IDX.version}>
          <strong className="ivory">{answers.profileName}</strong>
          <span className="muted">
            Minecraft {answers.version} · {loaderLabel(v.fabric ? "fabric" : "vanilla")}
          </span>
        </Group>
        <Group title="Mods" step={IDX.mods}>
          {!v.fabric ? (
            <span className="muted">Vanilla, no mods.</span>
          ) : (
            <div className="ob-review-mods">
              {answers.plan.length > 0 && planVersion !== answers.version && (
                <p className="ob-review-note">
                  <Icon name="info" size={13} /> Checked for Minecraft {planVersion}. Flint checks them again for {answers.version} during setup.
                </p>
              )}
              <ReviewList label="Your picks" items={groups.picks} empty="None. You can add mods later from the Library." />
              {groups.dependencies.length > 0 && <ReviewList label="Required dependencies" hint="Installed with the picks that need them" items={groups.dependencies} />}
              {(groups.optional.length > 0 || groups.optionalOff.length > 0) && (
                <ReviewList
                  label="Optional extras"
                  hint={groups.optionalOff.length ? `Off unless chosen · ${groups.optionalOff.length} left off` : "Off unless chosen"}
                  items={groups.optional}
                  empty="None chosen."
                />
              )}
              {groups.blocked.length > 0 && <ReviewList label="Left out" hint="Not available for this version" items={groups.blocked} muted />}
              {settings.performanceMods && (
                <div className="ob-review-list">
                  <span className="ob-review-list-label">
                    Flint performance set<span className="faint">Added automatically where compatible · can be turned off in Settings</span>
                  </span>
                  <PerformanceSetNote compact />
                </div>
              )}
            </div>
          )}
        </Group>
        <Group title="Startup" step={IDX.startup}>
          <span className="muted">{answers.launchAtLogin ? "Open Flint when you log in" : "Don't open at login"}</span>
        </Group>
        <Group title="Community" step={IDX.community}>
          <span className="muted">{answers.community === "opened" ? "Opened the Discord invite" : "Not now"}</span>
        </Group>
        <Group title="Appearance" step={IDX.appearance}>
          <span className="muted">Flint {THEMES.find((t) => t.id === settings.theme)?.name ?? "Ember"}</span>
        </Group>
        <Group title="Typography" step={IDX.appearance}>
          <span className="muted">
            {FONTS.find((f) => f.id === settings.font)?.name ?? "Flint Default"} · {FONTS.find((f) => f.id === settings.font)?.note}
          </span>
        </Group>
        <Group title="Accessibility & sound" step={IDX.appearance}>
          <span className="muted">
            {[
              settings.animations ? "Animations on" : "Animations off",
              settings.parallax ? "Parallax on" : "Parallax off",
              `${EFFECTS.find((e) => e.id === settings.effects)?.name ?? "Full"} effects`,
              settings.sounds ? `Sounds ${Math.round(settings.volume * 100)}%` : "Sounds off",
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
const STAGES: { key: StageKey; label: string }[] = [
  { key: "prefs", label: "Save preferences" },
  { key: "profile", label: "Prepare profile" },
  { key: "validate", label: "Validate version and loader" },
  { key: "check", label: "Check files" },
  { key: "resolve", label: "Resolve mods and dependencies" },
  { key: "download", label: "Download and install" },
  { key: "finalize", label: "Finalize" },
];

const PAUSED = "Download paused. Files already downloaded are kept, so resuming picks up where it stopped.";
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
      if (catalog && !catalog.offline && entry && !entry.available) throw new Error(`Mojang no longer lists Minecraft ${answers.version}.`);
      if (catalog && !catalog.offline && entry && loader === "fabric" && !entry.fabric) throw new Error(`Fabric doesn't support Minecraft ${answers.version}.`);
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
        if (blocked.length) note(`Left out: ${blocked.map((b) => `${b.title} (${b.message})`).join(", ")}`);
        ({ required, optional } = installBatches(resolved, wantOptional));
      }
      mark("resolve");

      begin("download");
      await api.installProfile(profile.id);
      if (required.length) {
        const result = await api.installMods(profile.id, required.map(toInstall));
        if (result.failed.length) throw new Error(`${result.failed.length} required mod(s) couldn't be installed: ${result.failed.map((f) => f.message).join("; ")}`);
      }
      if (optional.length) {
        const titles = optional.map((o) => o.title).join(", ");
        try {
          const result = await api.installMods(profile.id, optional.map(toInstall));
          if (result.failed.length) note(`Skipped optional extras (${titles}): ${result.failed.map((f) => f.message).join("; ")}`);
        } catch (e) {
          // A pause is a pause, not a skipped extra: resuming installs it.
          if (isCancel(errorText(e))) throw e;
          note(`Skipped optional extras (${titles}): ${errorText(e)}`);
        }
      }
      if (required.length || optional.length) meta.current.modsInstalled = true;
      // Success only once every download job for the profile has finished and the backend reports
      // every game file present.
      await jobsSettled(profile.id);
      const verified = await api.checkProfile(profile.id);
      if (!verified.ready) throw new Error(`${verified.missingFiles} game file(s) are still missing after download. Retry to fetch them.`);
      mark("download");

      begin("finalize");
      if (answers.launchAtLogin) {
        const enabled = await api.launchAtLoginSet(true);
        if (!enabled) note("Couldn't turn on open-at-login. You can retry in Settings.");
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
      setFailed({ stage, message: wasPaused ? PAUSED : message });
      if (!wasPaused) sound.play("error");
    } finally {
      setPausing(false);
      running.current = false;
    }
  }, [answers, meta, persist, settings, update, store, onComplete]);

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
    if (pausing) return "Pausing…";
    if (modJob) return `${modJob.title}: ${Math.min(modJob.itemsDone + 1, modJob.itemsTotal)} of ${modJob.itemsTotal}${modJob.file ? ` · ${modJob.file}` : ""}`;
    if (live?.state === "checking") return "Checking game files";
    if (live?.state === "downloading" && live.bytesTotal) return `${bytes(live.bytesDone ?? 0)} of ${bytes(live.bytesTotal)}`;
    if (live?.state === "installing") return live.label ?? "Installing game files";
    return live?.label ?? "Preparing";
  }, [active, live, modJob, pausing]);
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
        <h1>{complete ? "Flint is ready." : paused ? "Setup paused." : failed ? "Setup needs attention." : "Setting up Flint…"}</h1>
        <p className="ob-sub">
          {failed ? "Nothing was lost. Flint picks up where it stopped." : "Every file is checked against its published hash. Your existing Minecraft installs aren't touched."}
        </p>
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
              <span className="ob-stage-label">{s.label}</span>
              {isActive && s.key === "download" && <span className="faint ob-stage-detail">{downloadDetail}</span>}
              {isActive && s.key === "download" && <Progress value={downloadValue} label="Download progress" />}
            </li>
          );
        })}
      </ol>
      {(notes.length > 0 || (missing && !complete)) && (
        <ul className="ob-setup-notes faint">
          {missing && !complete ? <li>{bytes(missing)} of game files to download</li> : null}
          {notes.map((n) => (
            <li key={n}>{n}</li>
          ))}
        </ul>
      )}
      {failed && (
        <div className="ob-setup-fail">
          <div className={paused ? "ob-paused" : "ob-error"} role={paused ? "status" : "alert"}>
            <Icon name={paused ? "pause" : "alert"} size={16} />
            <span>{failed.message}</span>
          </div>
          <div className="ob-setup-actions">
            <Button variant="ghost" onClick={onBack}>
              Back to review
            </Button>
            {meta.current.profileId && (
              <Button variant="ghost" onClick={() => void finishLater()}>
                Finish later from Home
              </Button>
            )}
            <Button variant="primary" icon={paused ? "play" : "refresh"} onClick={() => void runRef.current()}>
              {paused ? "Resume" : "Retry"}
            </Button>
          </div>
        </div>
      )}
      {!failed && !complete && active === "download" && meta.current.profileId && (
        <button type="button" className="ob-link" onClick={pause} disabled={pausing}>
          {pausing ? "Pausing…" : "Pause download"}
        </button>
      )}
    </div>
  );
}

const INSTALLED_SECTIONS: { key: Exclude<keyof InstalledGroups, "total">; label: string; hint?: string }[] = [
  { key: "picks", label: "Your picks" },
  { key: "performance", label: "Flint performance set", hint: "Added automatically · can be turned off in Settings" },
  { key: "dependencies", label: "Required dependencies", hint: "Needed by the mods above" },
  { key: "optional", label: "Optional extras", hint: "You chose these" },
];

function Done({ answers, installed, onDone }: { answers: Answers; installed: InstalledGroups | null; onDone: () => void }) {
  const store = useStore();
  const account = store.accounts.find((a) => a.uuid === answers.accountUuid);
  // With a list of what was installed there is something to read, so Home waits for a click.
  const auto = !installed || installed.total === 0;
  useEffect(() => {
    if (!auto) return;
    const t = window.setTimeout(onDone, 2200);
    return () => window.clearTimeout(t);
  }, [auto, onDone]);
  return (
    <div className={`ob-step ob-done ${auto ? "ob-narrow" : "ob-mid"}`}>
      <FlintMark size={44} />
      <h1>You're all set{account ? `, ${account.username}` : ""}.</h1>
      <p className="ob-sub">
        {answers.profileName} is ready on Minecraft {answers.version}.{auto ? " Taking you home…" : ""}
      </p>
      {installed && installed.total > 0 && (
        <section className="ob-installed" aria-label="Installed in this profile">
          <h3>
            Installed in {answers.profileName}
            <span className="faint">{installed.total} in the profile, read back after setup</span>
          </h3>
          {INSTALLED_SECTIONS.filter((s) => installed[s.key].length).map((s) => (
            <div key={s.key} className="ob-review-list">
              <span className="ob-review-list-label">
                {s.label}
                <span className="ob-review-count">{installed[s.key].length}</span>
                {s.hint && <span className="faint">{s.hint}</span>}
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
        {auto ? "Go to Home now" : "Go to Home"}
      </Button>
    </div>
  );
}
