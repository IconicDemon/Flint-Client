import { useEffect, useRef, useState } from "react";
import { SkinViewer, type PlayerObject } from "skinview3d";
import { bodyFromSkin } from "../../lib/avatar";
import { applyTrueColor } from "../../lib/trueColor";
import { onWindowFocus } from "../../lib/windowFocus";

export type StageMotion = "full" | "balanced" | "static";

type Props = {
  skin: string | null;
  cape?: string | null;
  model: "classic" | "slim";
  username: string;
  motion: StageMotion;
  enabled3d: boolean;
  /** False while Home is hidden behind another page: rendering stops, the scene is kept. */
  active: boolean;
};

const WAVE = { raise: 0.36, hold: 1.25, lower: 0.42, cooldown: 2.5 };
const WAVE_TOTAL = WAVE.raise + WAVE.hold + WAVE.lower;
/*
 * skinview3d pivots the right arm 2px below the top of the shoulder. The wave raises it to ~132°
 * out to the side and slightly toward the camera, then swings it outward and back between ~120°
 * and ~144°, so it never reaches the head (the arm starts to clip at ~158°).
 */
const ARM = { raised: -2.3, swing: 0.2, forward: -0.32 };
const HEAD = { yaw: 0.5, pitchUp: 0.24, pitchDown: 0.3 };
/** The body turns this share of the head's yaw, so looking around involves the shoulders too. */
const BODY_FOLLOW = 0.22;
/** When the pointer rests, the character occasionally glances around on its own. */
const GLANCE = { after: 4500, hold: [1300, 2200], gap: [5000, 9000], yaw: 0.24, pitch: 0.07 };
/** Frame budgets: interactive while the pointer moves, the wave plays or the head is settling. */
const FPS: Record<Exclude<StageMotion, "static">, { active: number; ambient: number }> = {
  full: { active: 60, ambient: 15 },
  balanced: { active: 30, ambient: 10 },
};
const INTERACTIVE_MS = 1400;
/**
 * With no pointer or keyboard input for this long, the character eases back to rest and the loop
 * stops until the next input. Measured on an M2 (release build, Home idle): a WebGL canvas that
 * keeps animating costs about 10-14% CPU across Flint and WebKit almost regardless of frame rate,
 * and 0% once the loop stops.
 */
const DEEP_IDLE_MS = 60_000;

const smooth = (x: number) => x * x * (3 - 2 * x);
const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

/** Raise, hold and lower envelope for the right arm; 0 at rest, 1 fully raised. */
function waveEnvelope(t: number) {
  if (t <= 0 || t >= WAVE_TOTAL) return 0;
  if (t < WAVE.raise) return smooth(t / WAVE.raise);
  if (t < WAVE.raise + WAVE.hold) return 1;
  return 1 - smooth((t - WAVE.raise - WAVE.hold) / WAVE.lower);
}

type Rig = {
  t: number;
  yaw: number;
  pitch: number;
  target: { yaw: number; pitch: number };
  waveAt: number | null;
  /** performance.now() before which a new wave can't start. */
  readyAt: number;
};

const between = ([lo, hi]: number[]) => lo + Math.random() * (hi - lo);

function pose(player: PlayerObject, rig: Rig, dt: number) {
  rig.t += dt;
  const k = 1 - Math.exp(-dt * 7);
  rig.yaw += (rig.target.yaw - rig.yaw) * k;
  rig.pitch += (rig.target.pitch - rig.pitch) * k;
  const { head, body, leftArm, rightArm, leftLeg, rightLeg } = player.skin;
  const bodyYaw = rig.yaw * BODY_FOLLOW;
  player.rotation.y = bodyYaw;
  body.rotation.set(0, 0, 0);
  leftLeg.rotation.set(0, 0, 0);
  rightLeg.rotation.set(0, 0, 0);

  const breathe = Math.sin(rig.t * 1.5) * 0.028;
  leftArm.rotation.set(0, 0, 0.06 + breathe);

  const waveT = rig.waveAt === null ? -1 : rig.t - rig.waveAt;
  const env = waveEnvelope(waveT);
  if (rig.waveAt !== null && waveT >= WAVE_TOTAL) {
    rig.waveAt = null;
    rig.readyAt = performance.now() + WAVE.cooldown * 1000;
  }
  // The swing fades in and out with the hold so the raise and lower stay one clean arc.
  const holdT = clamp((waveT - WAVE.raise) / WAVE.hold, 0, 1);
  const swing = Math.sin(holdT * Math.PI * 5) * ARM.swing * Math.sin(holdT * Math.PI);
  const idleZ = -0.06 - breathe;
  rightArm.rotation.set(ARM.forward * env, 0, idleZ + (ARM.raised - idleZ) * env + swing);
  // A slight, friendly head tilt toward the waving hand.
  head.rotation.set(rig.pitch, rig.yaw - bodyYaw, 0.05 * env);
  player.cape.rotation.x = 0.12 + Math.sin(rig.t * 1.5) * 0.015;
}

export default function SkinStage({ skin, cape, model, username, motion, enabled3d, active }: Props) {
  const hostRef = useRef<HTMLDivElement>(null);
  const viewerRef = useRef<SkinViewer | null>(null);
  const rig = useRef<Rig>({ t: 0, yaw: 0, pitch: 0, target: { yaw: 0, pitch: 0 }, waveAt: null, readyAt: 0 });
  const kick = useRef<() => void>(() => {});
  const hovering = useRef(false);
  const [greeting, setGreeting] = useState(false);
  const [failed, setFailed] = useState(false);
  const [flat, setFlat] = useState<string | null>(null);
  const use3d = enabled3d && !failed && !!skin;

  useEffect(() => {
    setFlat(null);
    if (skin && !use3d) void bodyFromSkin(skin, model === "slim").then(setFlat);
  }, [skin, model, use3d]);

  useEffect(() => {
    const host = hostRef.current;
    if (!use3d || !host) return;
    const canvas = document.createElement("canvas");
    canvas.className = "skin-canvas";
    host.appendChild(canvas);
    let viewer: SkinViewer;
    try {
      viewer = new SkinViewer({
        canvas,
        width: host.clientWidth || 360,
        height: host.clientHeight || 560,
        preserveDrawingBuffer: false,
        enableControls: false,
        renderPaused: true,
        pixelRatio: Math.min(window.devicePixelRatio || 1, 2),
      });
    } catch {
      canvas.remove();
      setFailed(true);
      return;
    }
    viewer.fov = 26;
    viewer.zoom = 0.78;
    applyTrueColor(viewer);
    viewer.playerWrapper.rotation.y = -0.1;
    viewer.animation = null;
    viewerRef.current = viewer;
    pose(viewer.playerObject, rig.current, 0);
    viewer.render();

    const resize = new ResizeObserver(([entry]) => {
      const { width, height } = entry.contentRect;
      if (width < 2 || height < 2) return;
      viewer.width = Math.round(width);
      viewer.height = Math.round(height);
      viewer.render();
    });
    resize.observe(host);

    return () => {
      resize.disconnect();
      viewer.dispose();
      canvas.remove();
      viewerRef.current = null;
    };
  }, [use3d]);

  useEffect(() => {
    const viewer = viewerRef.current;
    if (!viewer) return;
    viewer.pixelRatio = Math.min(window.devicePixelRatio || 1, motion === "full" ? 2 : 1.5);
    viewer.render();
  }, [motion, use3d]);

  useEffect(() => {
    const viewer = viewerRef.current;
    if (!viewer || !skin) return;
    void viewer.loadSkin(skin, { model: model === "slim" ? "slim" : "default" }).then(() => {
      if (viewerRef.current === viewer) viewer.render();
    });
  }, [skin, model, use3d]);

  useEffect(() => {
    const viewer = viewerRef.current;
    if (!viewer) return;
    const done = () => viewerRef.current === viewer && viewer.render();
    if (cape) void viewer.loadCape(cape).then(done);
    else {
      viewer.resetCape();
      done();
    }
  }, [cape, use3d]);

  // Render loop. Pointer events only record coordinates; aiming happens once per rendered frame.
  useEffect(() => {
    const viewer = viewerRef.current;
    const host = hostRef.current;
    if (!viewer || !host) return;
    const state = rig.current;
    const rest = () => {
      state.target = { yaw: 0, pitch: 0 };
      state.yaw = state.pitch = 0;
      state.waveAt = null;
      pose(viewer.playerObject, state, 0);
      viewer.render();
    };
    if (motion === "static" || !active) {
      rest();
      return;
    }
    const budget = FPS[motion];
    const pointer = { x: 0, y: 0, at: -Infinity, inside: false };
    let rect = host.getBoundingClientRect();
    const measure = () => (rect = host.getBoundingClientRect());
    const measurer = new ResizeObserver(measure);
    measurer.observe(host);

    let frame = 0;
    /** Between ambient frames the loop sleeps on a timer instead of waking every display frame. */
    let timer = 0;
    let last = 0;
    let running = false;
    let inputAt = performance.now();
    // When unfocused, keep going only until the wave ends and the head is back at rest.
    let settling = false;

    const glance = { until: 0, next: performance.now() + between(GLANCE.gap), yaw: 0, pitch: 0 };
    const aim = (now: number) => {
      if (settling) {
        state.target = { yaw: 0, pitch: 0 };
        return;
      }
      if (!pointer.inside || now - pointer.at > GLANCE.after) {
        if (now > glance.next) {
          glance.yaw = (Math.random() * 2 - 1) * GLANCE.yaw;
          glance.pitch = (Math.random() * 2 - 1) * GLANCE.pitch;
          glance.until = now + between(GLANCE.hold);
          glance.next = glance.until + between(GLANCE.gap);
        }
        state.target = now < glance.until ? { yaw: glance.yaw, pitch: glance.pitch } : { yaw: 0, pitch: 0 };
        return;
      }
      const dx = (pointer.x - (rect.left + rect.width / 2)) / (window.innerWidth * 0.42);
      const dy = (pointer.y - (rect.top + rect.height * 0.18)) / (window.innerHeight * 0.55);
      state.target = {
        yaw: clamp(dx, -1, 1) * HEAD.yaw,
        pitch: clamp(dy, -1, 1) * (dy < 0 ? HEAD.pitchUp : HEAD.pitchDown),
      };
    };
    const interactiveAt = (now: number) => {
      const settled = Math.abs(state.target.yaw - state.yaw) + Math.abs(state.target.pitch - state.pitch) < 0.003;
      return state.waveAt !== null || !settled || now - pointer.at < INTERACTIVE_MS;
    };
    const loop = (now: number) => {
      frame = 0;
      const interval = 1000 / (interactiveAt(now) ? budget.active : budget.ambient);
      if (!last || now - last >= interval - 1) {
        const dt = last ? Math.min(0.1, (now - last) / 1000) : 1 / 60;
        last = now;
        aim(now);
        pose(viewer.playerObject, state, dt);
        viewer.render();
        if (!settling && state.waveAt === null && now - inputAt > DEEP_IDLE_MS) settling = true;
        if (settling && state.waveAt === null && Math.abs(state.yaw) + Math.abs(state.pitch) < 0.004) return halt();
      }
      // Interactive motion follows the display; ambient breathing wakes only when its frame is due.
      // A glance target change (decided in aim) turns the next frame interactive by itself.
      if (interactiveAt(now)) frame = requestAnimationFrame(loop);
      else timer = window.setTimeout(() => (frame = requestAnimationFrame(loop)), Math.max(0, last + interval - performance.now() - 4));
    };
    const halt = () => {
      running = false;
      cancelAnimationFrame(frame);
      clearTimeout(timer);
      frame = timer = 0;
    };
    /** Input arrived: resume after a deep idle, or follow at once while sleeping between frames. */
    const wake = () => {
      inputAt = performance.now();
      if (!running) return start();
      // Input during a deep-idle settle cancels it (a window in the background keeps settling).
      settling = !document.hasFocus();
      if (frame) return;
      clearTimeout(timer);
      timer = 0;
      frame = requestAnimationFrame(loop);
    };
    const start = () => {
      if (document.hidden) return;
      inputAt = performance.now();
      settling = !document.hasFocus();
      if (running) return;
      running = true;
      last = 0;
      frame = requestAnimationFrame(loop);
    };
    const onMove = (e: PointerEvent) => {
      pointer.x = e.clientX;
      pointer.y = e.clientY;
      pointer.at = performance.now();
      pointer.inside = true;
      wake();
    };
    const onLeave = () => (pointer.inside = false);
    const onBlur = () => (settling = true);
    const onVisibility = () => {
      if (!document.hidden) return start();
      halt();
      rest();
    };
    kick.current = start;
    window.addEventListener("pointermove", onMove, { passive: true });
    window.addEventListener("keydown", wake);
    window.addEventListener("resize", measure);
    document.documentElement.addEventListener("pointerleave", onLeave);
    window.addEventListener("blur", onBlur);
    window.addEventListener("focus", start);
    document.addEventListener("visibilitychange", onVisibility);
    const offFocus = onWindowFocus((focused) => {
      if (!focused) return onBlur();
      start();
      settling = false;
    });
    start();
    return () => {
      offFocus();
      halt();
      measurer.disconnect();
      kick.current = () => {};
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("keydown", wake);
      window.removeEventListener("resize", measure);
      document.documentElement.removeEventListener("pointerleave", onLeave);
      window.removeEventListener("blur", onBlur);
      window.removeEventListener("focus", start);
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [motion, active, use3d]);

  // The greeting belongs to a visible Home: hide it when the page is left mid-wave.
  useEffect(() => {
    if (!active) setGreeting(false);
  }, [active]);

  useEffect(() => {
    if (!greeting) return;
    const t = window.setTimeout(() => setGreeting(false), WAVE_TOTAL * 1000 + 150);
    return () => window.clearTimeout(t);
  }, [greeting]);

  // One greeting per hover, never restarted mid-wave, and a short cooldown before the next one.
  const greet = () => {
    if (hovering.current) return;
    hovering.current = true;
    const state = rig.current;
    if (greeting || state.waveAt !== null || performance.now() < state.readyAt) return;
    setGreeting(true);
    if (motion !== "static" && use3d) {
      state.waveAt = state.t;
      kick.current();
    } else {
      state.readyAt = performance.now() + (WAVE_TOTAL + WAVE.cooldown) * 1000;
    }
  };

  return (
    <div className={`skin-stage ${use3d ? "is-3d" : "is-static"}`}>
      <div className="skin-hit">
        <span
          className="skin-target"
          tabIndex={0}
          role="img"
          aria-label={`${username}'s Minecraft character`}
          onPointerEnter={greet}
          onPointerLeave={() => (hovering.current = false)}
          onFocus={greet}
          onBlur={() => (hovering.current = false)}
        />
        <span className="nameplate">{username}</span>
        <span className={`skin-hi ${greeting ? "is-on" : ""}`} aria-hidden>
          Hi!
        </span>
        {use3d ? (
          <div ref={hostRef} className="skin-host" />
        ) : (
          <div className="skin-fallback">{flat ? <img src={flat} alt="" /> : <span>{username.slice(0, 1)}</span>}</div>
        )}
      </div>
      <span className="skin-shadow" aria-hidden />
    </div>
  );
}
