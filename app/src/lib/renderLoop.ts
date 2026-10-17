/**
 * On-demand rendering for WebGL views. WebKit pays a large fixed cost for every frame a live WebGL
 * canvas presents, almost regardless of frame rate, so the only real saving is not rendering when
 * nothing changes. A view owns one loop: `request()` asks for a frame, and each frame's `step`
 * decides whether another one is needed — the next display frame, a frame after a pause, or none.
 */

/** What a frame wants next: `0` the next display frame, `n > 0` a frame after `n` ms, `null` none. */
export type Next = number | null;

/** Timing sources, injectable so tests can drive the loop by hand. */
export type FrameClock = {
  now(): number;
  frame(cb: (now: number) => void): number;
  cancelFrame(id: number): void;
  later(cb: () => void, ms: number): number;
  cancelLater(id: number): void;
};

export const browserClock: FrameClock = {
  now: () => performance.now(),
  frame: (cb) => requestAnimationFrame(cb),
  cancelFrame: (id) => cancelAnimationFrame(id),
  later: (cb, ms) => window.setTimeout(cb, ms),
  cancelLater: (id) => window.clearTimeout(id),
};

/** Longest step handed to animations, so a stalled frame never makes them jump. */
export const MAX_DT = 0.1;

/**
 * With no pointer or keyboard input for this long, ambient motion eases back to rest and rendering
 * stops until the next input (the same budget Home's character uses).
 */
export const DEEP_IDLE_MS = 60_000;

export type RenderLoop = {
  /** Render on the next display frame (cancels a pending pause). */
  request(): void;
  /** Stops after the current frame, if any, and forgets the frame timing. */
  halt(): void;
  dispose(): void;
  /** A frame or a timed pause is pending. */
  readonly running: boolean;
};

export function createRenderLoop(step: (now: number, dt: number) => Next, clock: FrameClock = browserClock): RenderLoop {
  let frame = 0;
  let timer = 0;
  /** Time of the previous frame in this run; 0 after a stop, so a restart never sees a huge dt. */
  let last = 0;
  let disposed = false;

  const run = (now: number) => {
    frame = 0;
    const dt = last ? Math.min(MAX_DT, Math.max(0, (now - last) / 1000)) : 1 / 60;
    last = now;
    const next = step(now, dt);
    // `step` may have asked for a frame itself (a control reacting to the render); that one wins.
    if (frame || timer || disposed) return;
    if (next === null) last = 0;
    else if (next <= 0) frame = clock.frame(run);
    else
      timer = clock.later(() => {
        timer = 0;
        if (!disposed) frame = clock.frame(run);
      }, next);
  };

  const halt = () => {
    if (frame) clock.cancelFrame(frame);
    if (timer) clock.cancelLater(timer);
    frame = timer = 0;
    last = 0;
  };

  return {
    request() {
      if (disposed || frame) return;
      if (timer) {
        clock.cancelLater(timer);
        timer = 0;
      }
      frame = clock.frame(run);
    },
    halt,
    dispose() {
      disposed = true;
      halt();
    },
    get running() {
      return !!(frame || timer);
    },
  };
}

/** Moves `value` linearly toward `target`, covering the full 0..1 range in `seconds`. */
export function approach(value: number, target: number, dt: number, seconds: number): number {
  if (seconds <= 0) return target;
  const stepSize = dt / seconds;
  return value < target ? Math.min(target, value + stepSize) : Math.max(target, value - stepSize);
}

export const smoothstep = (x: number) => {
  const t = Math.min(1, Math.max(0, x));
  return t * t * (3 - 2 * t);
};

/** Delay until the next frame of a paced loop, waking a few ms early to catch the display frame. */
export function pacedDelay(last: number, now: number, fps: number, early = 4): number {
  return Math.max(0, last + 1000 / fps - now - early);
}

/** Whether ambient motion should play: only for a visible, focused window with recent input. */
export function motionWanted(s: { enabled: boolean; visible: boolean; focused: boolean; now: number; inputAt: number }): boolean {
  return s.enabled && s.visible && s.focused && s.now - s.inputAt < DEEP_IDLE_MS;
}
