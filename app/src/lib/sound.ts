export type Cue = "tap" | "toggle" | "nav" | "open" | "success" | "error" | "launch";

type Note = { freq: number; to?: number; at: number; dur: number; gain: number; type?: OscillatorType };

/*
 * Synthesised, so there are no audio files to load. Interaction cues (tap, toggle, nav, open) are
 * very short and sit well below the outcome cues (success, error, launch), so clicking around is
 * felt rather than heard. Gains are at full volume; the Volume setting scales all of them.
 */
export const CUES: Record<Cue, Note[]> = {
  tap: [{ freq: 1250, to: 900, at: 0, dur: 0.035, gain: 0.05, type: "triangle" }],
  toggle: [
    { freq: 880, at: 0, dur: 0.04, gain: 0.05, type: "triangle" },
    { freq: 1320, at: 0.035, dur: 0.05, gain: 0.04, type: "triangle" },
  ],
  nav: [{ freq: 660, to: 760, at: 0, dur: 0.06, gain: 0.045, type: "sine" }],
  open: [{ freq: 520, to: 700, at: 0, dur: 0.07, gain: 0.04, type: "sine" }],
  success: [
    { freq: 587, at: 0, dur: 0.12, gain: 0.13, type: "triangle" },
    { freq: 880, at: 0.09, dur: 0.2, gain: 0.12, type: "triangle" },
  ],
  error: [
    { freq: 300, to: 220, at: 0, dur: 0.14, gain: 0.14, type: "triangle" },
    { freq: 220, to: 180, at: 0.11, dur: 0.16, gain: 0.11, type: "triangle" },
  ],
  launch: [
    { freq: 392, at: 0, dur: 0.4, gain: 0.12, type: "sine" },
    { freq: 587, at: 0.06, dur: 0.42, gain: 0.1, type: "sine" },
    { freq: 784, at: 0.12, dur: 0.46, gain: 0.08, type: "sine" },
  ],
};

/** The same interaction cue can't repeat faster than this, so rapid clicks never chatter. */
const MIN_REPEAT_MS = 60;
const QUIET_CUES = new Set<Cue>(["tap", "toggle", "nav", "open"]);

type AudioLike = Pick<AudioContext, "state" | "currentTime" | "destination" | "resume" | "suspend" | "createOscillator" | "createGain">;

export class UiSound {
  private ctx: AudioLike | null = null;
  private master: GainNode | null = null;
  private enabled = true;
  private volume = 0.5;
  private focused = true;
  private lastPlayed = new Map<Cue, number>();

  private readonly makeContext: () => AudioLike;
  private readonly now: () => number;

  constructor(makeContext: () => AudioLike = () => new AudioContext(), now: () => number = () => performance.now()) {
    this.makeContext = makeContext;
    this.now = now;
  }

  /** Wires window events. Separate from the constructor so tests can drive the class directly. */
  attach(target: Window) {
    target.addEventListener("blur", () => {
      this.focused = false;
      // Release the audio device while Flint is in the background.
      void this.ctx?.suspend();
    });
    target.addEventListener("focus", () => {
      this.focused = true;
    });
    // WebKit only lets audio start inside a user gesture: unlock it on the first real input, so
    // sounds triggered later by background events (a finished download) are heard too.
    const unlock = () => this.unlock();
    target.addEventListener("pointerdown", unlock, { capture: true, passive: true });
    target.addEventListener("keydown", unlock, { capture: true });
  }

  configure(enabled: boolean, volume: number) {
    this.enabled = enabled;
    this.volume = Math.min(1, Math.max(0, volume));
    if (this.master) this.master.gain.value = this.volume;
  }

  private context(): AudioLike | null {
    if (!this.ctx) {
      try {
        this.ctx = this.makeContext();
        this.master = this.ctx.createGain();
        this.master.gain.value = this.volume;
        this.master.connect(this.ctx.destination);
      } catch {
        return null;
      }
    }
    return this.ctx;
  }

  unlock() {
    if (!this.enabled) return;
    const ctx = this.context();
    if (ctx?.state === "suspended") void ctx.resume().catch(() => {});
  }

  /** Plays a cue. `force` plays even when sounds are off (the Settings preview). */
  play(cue: Cue, force = false): boolean {
    if ((!this.enabled && !force) || !this.focused || this.volume <= 0 || CUES[cue].length === 0) return false;
    const t = this.now();
    if (QUIET_CUES.has(cue) && t - (this.lastPlayed.get(cue) ?? -Infinity) < MIN_REPEAT_MS) return false;
    this.lastPlayed.set(cue, t);
    const ctx = this.context();
    if (!ctx) return false;
    const schedule = () => this.schedule(ctx, CUES[cue]);
    // Notes are scheduled against the context clock, which stands still while suspended.
    if (ctx.state === "suspended") void ctx.resume().then(schedule, () => {});
    else schedule();
    return true;
  }

  private schedule(ctx: AudioLike, notes: Note[]) {
    try {
      const start = ctx.currentTime + 0.005;
      for (const note of notes) {
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.type = note.type ?? "sine";
        osc.frequency.setValueAtTime(note.freq, start + note.at);
        if (note.to) osc.frequency.exponentialRampToValueAtTime(note.to, start + note.at + note.dur);
        gain.gain.setValueAtTime(0.0001, start + note.at);
        gain.gain.exponentialRampToValueAtTime(note.gain, start + note.at + 0.006);
        gain.gain.exponentialRampToValueAtTime(0.0001, start + note.at + note.dur);
        osc.connect(gain).connect(this.master!);
        osc.start(start + note.at);
        osc.stop(start + note.at + note.dur + 0.02);
      }
    } catch {
      // audio unavailable; stay silent
    }
  }
}

export const sound = new UiSound();
if (typeof window !== "undefined") sound.attach(window);
