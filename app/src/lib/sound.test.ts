// Run with `npm test`. Drives the sound engine with a fake AudioContext (no audio device needed).
import assert from "node:assert/strict";
import { test } from "node:test";
import { CUES, UiSound } from "./sound.ts";

type Scheduled = { freq: number; peak: number; at: number };

function fakeAudio(initial: "running" | "suspended" = "running") {
  const scheduled: Scheduled[] = [];
  const log: string[] = [];
  const param = () => {
    const p = {
      value: 0,
      setValueAtTime(v: number) {
        p.value = v;
        return p;
      },
      exponentialRampToValueAtTime(v: number) {
        p.peak = Math.max(p.peak, v);
        return p;
      },
      peak: 0,
    };
    return p;
  };
  const ctx = {
    state: initial as string,
    currentTime: 1,
    destination: {},
    resume() {
      log.push("resume");
      ctx.state = "running";
      return Promise.resolve();
    },
    suspend() {
      ctx.state = "suspended";
      return Promise.resolve();
    },
    createGain() {
      const gain = { gain: param(), connect: (n: unknown) => n };
      return gain;
    },
    createOscillator() {
      const frequency = param();
      const osc = {
        type: "sine",
        frequency,
        connect(g: { gain: { peak: number } }) {
          osc.gainNode = g;
          return g;
        },
        gainNode: null as null | { gain: { peak: number } },
        start(at: number) {
          log.push("start");
          scheduled.push({ freq: frequency.value, peak: osc.gainNode!.gain.peak, at });
        },
        stop() {},
      };
      return osc;
    },
  };
  return { ctx, scheduled, log };
}

function engine(initial: "running" | "suspended" = "running") {
  const audio = fakeAudio(initial);
  let clock = 0;
  const ui = new UiSound(() => audio.ctx as unknown as AudioContext, () => clock);
  return { ui, audio, advance: (ms: number) => (clock += ms) };
}

test("interaction cues are no longer silent", () => {
  for (const cue of ["tap", "toggle", "nav", "open"] as const) {
    assert.ok(CUES[cue].length > 0, `${cue} has notes`);
    const { ui, audio } = engine();
    assert.equal(ui.play(cue), true);
    assert.ok(audio.scheduled.length > 0 && audio.scheduled.every((n) => n.peak > 0), `${cue} schedules audible notes`);
  }
});

test("interaction cues stay quieter than outcome cues", () => {
  const loudest = (cue: keyof typeof CUES) => Math.max(...CUES[cue].map((n) => n.gain));
  for (const quiet of ["tap", "toggle", "nav", "open"] as const) {
    for (const outcome of ["success", "error", "launch"] as const) assert.ok(loudest(quiet) < loudest(outcome) / 2);
  }
});

test("the volume setting scales the master level and Off silences everything", () => {
  const { ui, audio } = engine();
  ui.configure(true, 0.25);
  ui.play("success");
  const master = (ui as unknown as { master: { gain: { value: number } } }).master;
  assert.equal(master.gain.value, 0.25);
  ui.configure(false, 1);
  const before = audio.scheduled.length;
  assert.equal(ui.play("success"), false);
  assert.equal(audio.scheduled.length, before);
  assert.equal(ui.play("success", true), true, "the Settings preview still plays");
});

test("rapid repeats of a click sound are throttled", () => {
  const { ui, advance } = engine();
  assert.equal(ui.play("tap"), true);
  assert.equal(ui.play("tap"), false);
  advance(80);
  assert.equal(ui.play("tap"), true);
  assert.equal(ui.play("success"), true, "outcome cues are never throttled");
  assert.equal(ui.play("success"), true);
});

test("a suspended context is resumed before any note is scheduled", async () => {
  const { ui, audio } = engine("suspended");
  assert.equal(ui.play("success"), true);
  assert.equal(audio.scheduled.length, 0, "nothing is scheduled against a stopped clock");
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(audio.log[0], "resume");
  assert.ok(audio.scheduled.length > 0);
});

test("nothing plays while the window is in the background", () => {
  const target = new EventTarget() as unknown as Window;
  const { ui, audio } = engine();
  ui.attach(target);
  target.dispatchEvent(new Event("blur"));
  assert.equal(ui.play("success"), false);
  target.dispatchEvent(new Event("focus"));
  assert.equal(ui.play("success"), true);
  assert.ok(audio.scheduled.length > 0);
});
