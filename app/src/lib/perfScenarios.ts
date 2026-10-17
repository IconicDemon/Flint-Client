// Scripted measurement scenarios for perf builds (see lib/perf.ts). They drive the real interface the
// way a person would (clicks, typing, scrolling, pointer movement, keyboard), the same way on every run,
// and record for each step: time to the first painted frame, time until the page's content is ready,
// frame intervals while it settles, React commits per area and backend calls.
import { api } from "./api";
import { sound } from "./sound";
import { nextPaint, round, startFrames, stopFrames, takeAreas, takeCalls, takeMarks, type FrameStats } from "./perf";

type Step = {
  name: string;
  invalid?: boolean;
  started?: number;
  paintMs: number;
  readyMs: number | null;
  frames: FrameStats;
  areas: ReturnType<typeof takeAreas>;
  calls: ReturnType<typeof takeCalls>;
  at: number;
};

const results: { run: string; marks: ReturnType<typeof takeMarks>; steps: Step[]; keystrokes: Record<string, number[]>; audio: Record<string, number | string>; errors: string[]; focusLost: number; refreshMs: number } = {
  run: "",
  audio: {},
  marks: takeMarks(),
  steps: [],
  keystrokes: {},
  errors: [],
  focusLost: 0,
  refreshMs: 0,
};
// A step measured while the window was in the background isn't comparable; this counts it.
window.addEventListener("blur", () => (results.focusLost += 1));

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
const $ = <T extends Element = HTMLElement>(sel: string) => document.querySelector<T>(sel);

async function waitFor(test: () => boolean, timeout = 10000): Promise<number | null> {
  const start = performance.now();
  while (performance.now() - start < timeout) {
    if (test()) return performance.now();
    await new Promise((r) => requestAnimationFrame(r));
  }
  return null;
}

const visible = () => document.visibilityState === "visible" && document.hasFocus();

async function step(name: string, action: () => void | Promise<void>, opts: { ready?: () => boolean; observe?: number } = {}) {
  // A hidden or background window throttles frames: wait until it's in front again.
  while (!visible()) await sleep(250);
  const lostBefore = results.focusLost;
  takeAreas();
  takeCalls();
  startFrames();
  const t0 = performance.now();
  const startedAt = Date.now();
  try {
    await action();
  } catch (e) {
    results.errors.push(`${name}: ${String(e)}`);
  }
  const paint = (await nextPaint()) - t0;
  const ready = opts.ready ? await waitFor(opts.ready) : null;
  await sleep(opts.observe ?? 1000);
  results.steps.push({
    name,
    paintMs: round(paint),
    readyMs: ready === null ? null : round(ready - t0),
    frames: stopFrames(),
    areas: takeAreas(),
    calls: takeCalls(),
    started: startedAt,
    at: Date.now(),
    invalid: results.focusLost !== lostBefore || undefined,
  });
}

function click(sel: string) {
  const el = $(sel);
  if (!el) throw new Error(`missing ${sel}`);
  el.click();
}

const rail = (label: string) => `.rail-item[aria-label="${label}"]`;

function setInput(el: HTMLInputElement, value: string) {
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!;
  setter.call(el, value);
  el.dispatchEvent(new Event("input", { bubbles: true }));
}

/** Types into an input one character at a time; returns per-keystroke time to the next painted frame. */
async function type(el: HTMLInputElement, text: string, gap = 110): Promise<number[]> {
  const out: number[] = [];
  el.focus();
  for (let i = 1; i <= text.length; i++) {
    const t0 = performance.now();
    setInput(el, text.slice(0, i));
    out.push(round((await nextPaint()) - t0));
    await sleep(gap);
  }
  return out;
}

/** Moves the pointer in a slow circle over the window, one event per frame. */
async function sweep(ms: number) {
  const start = performance.now();
  while (performance.now() - start < ms) {
    const a = (performance.now() - start) / 700;
    const x = innerWidth / 2 + Math.cos(a) * innerWidth * 0.3;
    const y = innerHeight / 2 + Math.sin(a) * innerHeight * 0.3;
    window.dispatchEvent(new PointerEvent("pointermove", { clientX: x, clientY: y, bubbles: true, pointerType: "mouse" }));
    await new Promise((r) => requestAnimationFrame(r));
  }
}

function scroller(from: Element | null): HTMLElement | null {
  let el = from?.parentElement ?? null;
  while (el) {
    const style = getComputedStyle(el);
    if (/(auto|scroll)/.test(style.overflowY) && el.scrollHeight > el.clientHeight + 4) return el;
    el = el.parentElement;
  }
  return null;
}

/** Scrolls an element smoothly to the bottom and back over `ms`, one step per frame. */
async function scrollThrough(el: HTMLElement, ms: number) {
  const start = performance.now();
  const max = el.scrollHeight - el.clientHeight;
  while (performance.now() - start < ms) {
    const t = (performance.now() - start) / ms;
    el.scrollTop = max * (t < 0.5 ? t * 2 : 2 - t * 2);
    el.dispatchEvent(new Event("scroll"));
    await new Promise((r) => requestAnimationFrame(r));
  }
}

async function save(name: string) {
  try {
    await api.perfWrite(name, JSON.stringify(results, null, 1));
  } catch (e) {
    console.error("perf write failed", e);
  }
}

const libraryReady = () => !!$(".result:not(.is-skeleton)") && !$(".result.is-skeleton");

export async function runSuite(run: string) {
  results.run = run;
  await waitFor(() => !!$(".rail"), 20000);
  // Measure only a visible, focused window (background windows throttle animation frames).
  const start = performance.now();
  while (!(document.visibilityState === "visible" && document.hasFocus()) && performance.now() - start < 60000) await sleep(200);
  await sleep(3000);
  results.marks = takeMarks();
  // The display's refresh interval, from 60 idle frames.
  startFrames();
  await sleep(1100);
  results.refreshMs = stopFrames().p50;
  results.focusLost = 0;
  const profileId = (await api.profiles()).profiles[0]?.id;

  // Home: idle, pointer movement, and a download's event traffic.
  await step("home-idle", () => undefined, { observe: 3000 });
  await step("home-pointer", () => sweep(4000), { observe: 300 });
  // The session's first sound: the audio context is created, then resumed, then the first note plays.
  // Measured separately (nothing has played yet at this point) to find where its one-off cost lies.
  const audio = sound as unknown as { context(): AudioContext | null };
  let ctx: AudioContext | null = null;
  await step("audio-create", () => {
    const t0 = performance.now();
    ctx = audio.context();
    results.audio.createMs = round(performance.now() - t0);
    results.audio.state = ctx?.state ?? "none";
  }, { observe: 800 });
  await step("audio-resume", async () => {
    const t0 = performance.now();
    await ctx?.resume().catch(() => {});
    results.audio.resumeMs = round(performance.now() - t0);
  }, { observe: 800 });
  await step("audio-first-play", () => void sound.play("tap", true), { observe: 800 });
  if (profileId) {
    await step("home-download-load", () => void api.perfEmitLoad(profileId, 5), { observe: 5200 });
    await step("home-download-load-pointer", async () => {
      void api.perfEmitLoad(profileId, 4);
      await sweep(4000);
    }, { observe: 300 });
  }
  await save(run);

  // Navigation between every page, three rounds.
  const pages: [string, () => boolean][] = [
    ["Library", libraryReady],
    ["Profiles", () => !!$(".pc-stage")],
    ["Settings", () => !!$(".settings-nav")],
    ["Skins", () => !!$(".page:not(.page-home) canvas")],
    ["Store", () => !!$(".page:not(.page-home) h1")],
    ["Play", () => !!$(".page-home:not(.is-parked)")],
  ];
  for (let round = 1; round <= 3; round++) {
    for (const [label, ready] of pages) {
      await step(`nav-${label.toLowerCase()}-${round}`, () => click(rail(label)), { ready, observe: 1200 });
    }
  }
  await save(run);

  // Settings: every section, a slider drag, a switch.
  await step("nav-settings", () => click(rail("Settings")), { ready: () => !!$(".settings-nav"), observe: 800 });
  for (const btn of [...document.querySelectorAll<HTMLButtonElement>(".settings-nav")]) {
    const label = btn.textContent?.trim().toLowerCase() ?? "?";
    await step(`settings-section-${label}`, () => btn.click(), { observe: 600 });
  }
  const appearance = [...document.querySelectorAll<HTMLButtonElement>(".settings-nav")].find((b) => b.textContent?.includes("Appearance"));
  appearance?.click();
  await sleep(500);
  const dim = $<HTMLInputElement>('input[type="range"][aria-label="Wallpaper dimming"]') ?? document.querySelectorAll<HTMLInputElement>('input[type="range"]')[0];
  if (dim) {
    const original = Number(dim.value);
    await step("settings-slider-drag", async () => {
      const start = performance.now();
      while (performance.now() - start < 2500) {
        const t = (performance.now() - start) / 2500;
        setInput(dim, String(Math.round((0.1 + 0.6 * (t < 0.5 ? t * 2 : 2 - t * 2)) * 100) / 100));
        await new Promise((r) => requestAnimationFrame(r));
      }
      setInput(dim, String(original));
    }, { observe: 500 });
  }
  const general = [...document.querySelectorAll<HTMLButtonElement>(".settings-nav")].find((b) => b.textContent?.includes("General"));
  general?.click();
  await sleep(500);
  for (const state of ["off", "on"]) {
    await step(`settings-switch-${state}`, () => click('[role="switch"][aria-label="Flint Assist"]'), { observe: 600 });
  }
  await save(run);

  // Library: typing a search, switching kinds, scrolling results.
  await step("nav-library", () => click(rail("Library")), { ready: libraryReady, observe: 800 });
  const search = $<HTMLInputElement>(".lib-search input");
  if (search) {
    let keys: number[] = [];
    await step("library-type-sodium", async () => {
      keys = await type(search, "sodium");
    }, { ready: libraryReady, observe: 1500 });
    results.keystrokes["library-sodium"] = keys;
    await step("library-clear", () => setInput(search, ""), { ready: libraryReady, observe: 1500 });
  }
  for (const tab of [...document.querySelectorAll<HTMLButtonElement>('.lib-tabs [role="tab"], [role="tablist"] [role="tab"]')].slice(0, 5)) {
    const label = tab.textContent?.trim().toLowerCase().replace(/\s+/g, "-") ?? "?";
    await step(`library-tab-${label}`, () => tab.click(), { ready: libraryReady, observe: 900 });
  }
  const modsTab = [...document.querySelectorAll<HTMLButtonElement>('[role="tab"]')].find((t) => t.textContent?.trim() === "Mods");
  modsTab?.click();
  await waitFor(libraryReady);
  await sleep(800);
  const list = scroller($(".result"));
  if (list) await step("library-scroll", () => scrollThrough(list, 3000), { observe: 300 });
  await save(run);

  // Profiles: carousel, a profile's tabs, its content list.
  await step("nav-profiles", () => click(rail("Profiles")), { ready: () => !!$(".pc-stage"), observe: 800 });
  const stage = $(".pc-stage");
  if (stage) {
    await step("profiles-carousel", async () => {
      for (let i = 0; i < 6; i++) {
        stage.dispatchEvent(new KeyboardEvent("keydown", { key: i < 3 ? "ArrowRight" : "ArrowLeft", bubbles: true }));
        await sleep(450);
      }
    }, { observe: 300 });
  }
  const open = [...document.querySelectorAll<HTMLButtonElement>("button")].find((b) => b.textContent?.trim() === "Open profile");
  if (open) {
    await step("profile-open", () => open.click(), { ready: () => !!$('[aria-label="Profile sections"]'), observe: 1000 });
    for (const tab of ["Files", "Worlds", "Logs", "Content"]) {
      const btn = [...document.querySelectorAll<HTMLButtonElement>('[aria-label="Profile sections"] [role="tab"]')].find((b) => b.textContent?.trim() === tab);
      await step(`profile-tab-${tab.toLowerCase()}`, () => btn?.click(), { observe: 900 });
    }
    const filter = $<HTMLInputElement>('input[aria-label="Search installed content"]');
    if (filter) {
      let keys: number[] = [];
      await step("content-type-filter", async () => {
        keys = await type(filter, "fabric");
      }, { observe: 500 });
      results.keystrokes["content-filter"] = keys;
      setInput(filter, "");
      await sleep(500);
    }
    const rows = scroller($(".ct-row, .ct-list > *"));
    if (rows) await step("content-scroll", () => scrollThrough(rows, 3000), { observe: 300 });
    if (profileId) await step("content-download-load", () => void api.perfEmitLoad(profileId, 4), { observe: 4200 });
  }
  await save(run);

  // Skins: the second 3D view.
  await step("nav-skins", () => click(rail("Skins")), { ready: () => !!$(".page:not(.page-home) canvas"), observe: 800 });
  await step("skins-pointer", () => sweep(4000), { observe: 300 });
  await step("nav-play", () => click(rail("Play")), { ready: () => !!$(".page-home:not(.is-parked)"), observe: 1500 });
  await step("home-idle-end", () => undefined, { observe: 3000 });
  results.errors.push("done");
  await save(run);
}
