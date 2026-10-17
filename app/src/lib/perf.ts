// Performance instrumentation for measurement builds only (`vite build --mode perf`). In every other
// build PERF is a compile-time false, so all of this is removed by the bundler and costs nothing.
//
// What it records, all inside the real webview:
// - frame intervals (requestAnimationFrame timestamps) while a measurement is running,
// - React commits per area (<PerfArea>, built on React's Profiler),
// - start-up marks (script start, boot data ready, first commit, first painted frame),
// - per-step results from the scripted scenarios in perfScenarios.ts.
// Results are written by the backend's `perf_write` command (a no-op outside perf builds).
import { Profiler, createElement, type ProfilerOnRenderCallback, type ReactNode } from "react";

export const PERF = import.meta.env.MODE === "perf";

type AreaStats = { commits: number; ms: number; maxMs: number };
const areas = new Map<string, AreaStats>();

const onRender: ProfilerOnRenderCallback = (id, _phase, actualDuration) => {
  const s = areas.get(id) ?? { commits: 0, ms: 0, maxMs: 0 };
  s.commits += 1;
  s.ms += actualDuration;
  s.maxMs = Math.max(s.maxMs, actualDuration);
  areas.set(id, s);
};

/** Counts commits and render time for a part of the tree (only in perf builds). */
export function PerfArea({ id, children }: { id: string; children: ReactNode }) {
  return PERF ? createElement(Profiler, { id, onRender }, children) : children;
}

export function takeAreas(): Record<string, AreaStats> {
  const out = Object.fromEntries([...areas].map(([k, v]) => [k, { ...v, ms: round(v.ms), maxMs: round(v.maxMs) }]));
  areas.clear();
  return out;
}

type CallStats = { count: number; ms: number; maxMs: number };
const calls = new Map<string, CallStats>();

export function recordCall(command: string, ms: number) {
  if (command === "perf_write") return;
  const s = calls.get(command) ?? { count: 0, ms: 0, maxMs: 0 };
  s.count += 1;
  s.ms += ms;
  s.maxMs = Math.max(s.maxMs, ms);
  calls.set(command, s);
}

export function takeCalls(): Record<string, CallStats> {
  const out = Object.fromEntries([...calls].map(([k, v]) => [k, { count: v.count, ms: round(v.ms), maxMs: round(v.maxMs) }]));
  calls.clear();
  return out;
}

const marks: Record<string, number> = {};
/** Records a start-up mark (ms since the webview started loading). */
export function perfMark(name: string) {
  if (PERF && marks[name] === undefined) marks[name] = round(performance.now());
}
export function takeMarks() {
  return { ...marks, wallClockAtLoad: Math.round(Date.now() - performance.now()) };
}

// ---------- frame sampling ----------

let frames: number[] = [];
let generation = 0;

// Each measurement has its own loop; a loop from an earlier measurement stops at its next frame.
function loop(gen: number) {
  const tick = (t: number) => {
    if (gen !== generation) return;
    frames.push(t);
    requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
}

export function startFrames() {
  frames = [];
  generation += 1;
  loop(generation);
}

export type FrameStats = { frames: number; p50: number; p95: number; p99: number; max: number; over25: number; over50: number; seconds: number };

export function stopFrames(): FrameStats {
  generation += 1;
  const gaps: number[] = [];
  for (let i = 1; i < frames.length; i++) gaps.push(frames[i] - frames[i - 1]);
  gaps.sort((a, b) => a - b);
  const pick = (q: number) => (gaps.length ? round(gaps[Math.min(gaps.length - 1, Math.floor(q * gaps.length))]) : 0);
  return {
    frames: gaps.length,
    p50: pick(0.5),
    p95: pick(0.95),
    p99: pick(0.99),
    max: round(gaps[gaps.length - 1] ?? 0),
    over25: gaps.filter((g) => g > 25).length,
    over50: gaps.filter((g) => g > 50).length,
    seconds: frames.length > 1 ? round((frames[frames.length - 1] - frames[0]) / 1000) : 0,
  };
}

/** Resolves on the first frame painted after the current work: two animation frames later. */
export function nextPaint(): Promise<number> {
  return new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame((t) => resolve(t))));
}

export function round(n: number) {
  return Math.round(n * 10) / 10;
}
