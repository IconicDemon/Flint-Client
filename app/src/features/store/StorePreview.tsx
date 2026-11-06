import { memo, useEffect, useRef, useState, type ReactNode } from "react";
import { IdleAnimation, SkinViewer, type PlayerObject } from "skinview3d";
import type { ShownCape } from "../../lib/capeSource";
import { createCapeDriver, type CapeDriver } from "../../lib/capeTexture";
import { useT } from "../../lib/i18n";
import { approach, createRenderLoop, motionWanted, pacedDelay, smoothstep, type Next } from "../../lib/renderLoop";
import { applyTrueColor } from "../../lib/trueColor";
import { onWindowFocus } from "../../lib/windowFocus";
import { IconButton } from "../../ui/controls";
import { Icon } from "../../ui/Icon";

/** The character turned to show its back three-quarters, where a cape is; "front" faces the camera. */
const VIEWS = { back: Math.PI - 0.55, front: 0.42 } as const;
const TURN_MS = 420;
const AMBIENT_FPS = 30;
const SETTLE_S = 0.6;
const REST = { arm: Math.PI * 0.02, cape: Math.PI * 0.06 };

/** Blends the joints the idle pose moves toward rest: `s` = 1 keeps the pose, 0 is rest (as on the Skins page). */
function blendToRest(player: PlayerObject, s: number) {
  if (s >= 1) return;
  const { head, leftArm, rightArm, leftLeg, rightLeg } = player.skin;
  head.rotation.x *= s;
  head.rotation.y *= s;
  leftArm.rotation.x *= s;
  rightArm.rotation.x *= s;
  leftLeg.rotation.x *= s;
  rightLeg.rotation.x *= s;
  leftArm.rotation.z = REST.arm + (leftArm.rotation.z - REST.arm) * s;
  rightArm.rotation.z = -REST.arm + (rightArm.rotation.z + REST.arm) * s;
  player.cape.rotation.x = REST.cape + (player.cape.rotation.x - REST.cape) * s;
}

function decode(url: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.crossOrigin = "anonymous";
    img.onload = () => resolve(img);
    img.onerror = reject;
    img.src = url;
  });
}

/** How React reaches the viewer (inert until it exists). */
type Stage = { wake(): void; turnTo(yaw: number): void; driver: CapeDriver | null };
const INERT: Stage = { wake() {}, turnTo() {}, driver: null };

type Props = {
  skin: string | null;
  model: "classic" | "slim";
  /** What `useDisplayedCape` returns, or an item the player points at (storeCape.ts `useItemCape`). */
  cape: ShownCape<ImageBitmap>;
  /** Ambient motion and automatic playback (Settings → Animations; off under reduced motion). */
  motion: boolean;
  label: string;
  /** Over the character while it shows something other than what the account wears (an item pointed at); null otherwise. */
  tag: ReactNode;
};

/**
 * The player in 3D wearing `cape`, drawn by the launcher's cape driver (lib/capeTexture.ts): a static texture as is, an
 * animated cape's poster and then its frames, each for its own duration on the wall clock as in game, paused while the
 * preview is hidden, the window minimized or the player paused it. The scene renders only when something changes; the idle
 * pose rests when the window is behind another or nobody has used it for a minute. Its few controls (turn, front and
 * back, pause) show while the pointer or keyboard focus is on it.
 */
export const StorePreview = memo(function StorePreview({ skin, model, cape, motion, label, tag }: Props) {
  const t = useT();
  const host = useRef<HTMLDivElement>(null);
  const viewerRef = useRef<SkinViewer | null>(null);
  const stage = useRef<Stage>(INERT);
  const [failed, setFailed] = useState(false);
  const [view, setView] = useState<"back" | "front">("back");
  /** Extra turns from the arrow buttons, added to the view's angle. */
  const [turn, setTurn] = useState(0);
  /** Playing unless the player paused it; reduced motion starts paused. */
  const [paused, setPaused] = useState<boolean | null>(null);
  const playing = paused === null ? motion : !paused;

  useEffect(() => {
    const el = host.current;
    if (!el) return;
    const canvas = document.createElement("canvas");
    el.appendChild(canvas);
    let viewer: SkinViewer;
    try {
      viewer = new SkinViewer({
        canvas,
        width: el.clientWidth || 360,
        height: el.clientHeight || 520,
        enableControls: true,
        renderPaused: true,
        pixelRatio: Math.min(window.devicePixelRatio || 1, 1.75),
      });
    } catch {
      canvas.remove();
      setFailed(true);
      return;
    }
    viewer.fov = 30;
    viewer.zoom = 0.7;
    applyTrueColor(viewer);
    viewer.controls.enablePan = false;
    // The page scrolls under the wheel; the character turns by dragging or with the buttons.
    viewer.controls.enableZoom = false;
    viewer.playerWrapper.rotation.y = VIEWS.back;
    viewer.animation = null;
    viewerRef.current = viewer;

    const player = viewer.playerObject;
    let focused = document.hasFocus();
    let visible = !document.hidden;
    let inputAt = performance.now();
    let amp = 0;
    let dragging = false;
    let inStep = false;
    let tween: { from: number; to: number; start: number } | null = null;
    const wanted = (now: number) => motionWanted({ enabled: true, visible, focused, now, inputAt });

    const step = (now: number, dt: number): Next => {
      if (tween) {
        const p = Math.min(1, Math.max(0, (now - tween.start) / TURN_MS));
        viewer.playerWrapper.rotation.y = tween.from + (tween.to - tween.from) * (1 - Math.pow(1 - p, 3));
        if (p >= 1) tween = null;
      }
      const want = wanted(now);
      const idle = viewer.animation;
      if (idle) {
        amp = approach(amp, want ? 1 : 0, dt, SETTLE_S);
        if (amp > 0) idle.update(player, dt);
        blendToRest(player, smoothstep(amp));
      }
      try {
        inStep = true;
        viewer.controls.update();
      } finally {
        inStep = false;
      }
      viewer.render();
      if (tween || dragging) return 0;
      return idle && (want || amp > 0) ? pacedDelay(now, performance.now(), AMBIENT_FPS) : null;
    };
    const loop = createRenderLoop(step);
    // The cape's frames ask for a render when they change; the loop draws it.
    const driver = createCapeDriver(viewer, () => loop.request());

    const onInput = () => {
      inputAt = performance.now();
      if (!loop.running && wanted(inputAt)) loop.request();
    };
    const onActivate = () => {
      focused = true;
      onInput();
    };
    const setFocused = (next: boolean) => {
      focused = next;
      if (next) onInput();
      else dragging = false;
    };
    const onBlur = () => setFocused(false);
    const onFocus = () => setFocused(true);
    const onVisibility = () => {
      visible = !document.hidden;
      if (!visible) return loop.halt();
      inputAt = performance.now();
      loop.request();
    };
    const onChange = () => {
      if (!inStep) loop.request();
    };
    const onDragStart = () => {
      dragging = true;
      onInput();
      loop.request();
    };
    const onDragEnd = () => (dragging = false);
    const onRestored = () => loop.request();
    const resize = new ResizeObserver(([entry]) => {
      viewer.setSize(Math.max(1, Math.round(entry.contentRect.width)), Math.max(1, Math.round(entry.contentRect.height)));
      viewer.render();
    });
    resize.observe(el);
    viewer.controls.addEventListener("change", onChange);
    viewer.controls.addEventListener("start", onDragStart);
    viewer.controls.addEventListener("end", onDragEnd);
    canvas.addEventListener("webglcontextrestored", onRestored);
    window.addEventListener("pointermove", onInput, { passive: true });
    window.addEventListener("pointerdown", onActivate, { passive: true });
    window.addEventListener("wheel", onInput, { passive: true });
    window.addEventListener("keydown", onActivate);
    window.addEventListener("blur", onBlur);
    window.addEventListener("focus", onFocus);
    document.addEventListener("visibilitychange", onVisibility);
    const offFocus = onWindowFocus(setFocused);

    stage.current = {
      wake: () => {
        inputAt = performance.now();
        loop.request();
      },
      turnTo: (to) => {
        const from = viewer.playerWrapper.rotation.y;
        tween = Math.abs(to - from) < 1e-4 ? null : { from, to, start: performance.now() };
        if (!tween) viewer.playerWrapper.rotation.y = to;
        loop.request();
      },
      driver,
    };
    loop.request();

    return () => {
      stage.current = INERT;
      driver.dispose();
      loop.dispose();
      offFocus();
      viewer.controls.removeEventListener("change", onChange);
      viewer.controls.removeEventListener("start", onDragStart);
      viewer.controls.removeEventListener("end", onDragEnd);
      canvas.removeEventListener("webglcontextrestored", onRestored);
      window.removeEventListener("pointermove", onInput);
      window.removeEventListener("pointerdown", onActivate);
      window.removeEventListener("wheel", onInput);
      window.removeEventListener("keydown", onActivate);
      window.removeEventListener("blur", onBlur);
      window.removeEventListener("focus", onFocus);
      document.removeEventListener("visibilitychange", onVisibility);
      resize.disconnect();
      // Give the WebGL context back at once: WebKit allows only a few, and Home's character keeps one.
      viewer.renderer.forceContextLoss();
      viewer.dispose();
      canvas.remove();
      viewerRef.current = null;
    };
  }, []);

  useEffect(() => {
    const viewer = viewerRef.current;
    if (!viewer) return;
    if (!skin) {
      viewer.loadSkin(null);
      stage.current.wake();
      return;
    }
    let stale = false;
    decode(skin).then(
      (img) => {
        if (stale || viewer.disposed) return;
        viewer.loadSkin(img, { model: model === "slim" ? "slim" : "default" });
        stage.current.wake();
      },
      () => {},
    );
    return () => {
      stale = true;
    };
  }, [skin, model, failed]);

  // The driver leaves the texture alone while the cape is the same one.
  useEffect(() => stage.current.driver?.show(cape), [cape, failed]);
  useEffect(() => stage.current.driver?.setPlaying(playing), [playing, failed]);

  useEffect(() => {
    stage.current.turnTo(VIEWS[view] + turn);
  }, [view, turn]);

  useEffect(() => {
    const viewer = viewerRef.current;
    if (!viewer) return;
    viewer.animation = motion ? new IdleAnimation() : null;
    stage.current.wake();
  }, [motion, failed]);

  const animated = cape.source === "flint" && !!cape.animation;
  if (failed)
    return (
      <div className="store-stage-fallback">
        <Icon name="shirt" size={22} />
        <strong>{t("store.preview.unavailableTitle")}</strong>
        <span className="faint">{t("store.preview.unavailableBody")}</span>
      </div>
    );
  return (
    <>
      {tag && (
        <div className="store-stage-tag" aria-hidden>
          {tag}
        </div>
      )}
      <div className="store-stage-floor" aria-hidden />
      <div ref={host} className="store-stage-canvas" role="img" aria-label={label} />
      <div className="store-stage-tools" role="toolbar" aria-label={t("store.preview.controls")}>
        <IconButton icon="chevronLeft" label={t("store.preview.turnLeft")} onClick={() => setTurn(turn - Math.PI / 4)} />
        <IconButton
          icon="rotate"
          label={view === "back" ? t("store.preview.showFront") : t("store.preview.showBack")}
          onClick={() => {
            setTurn(0);
            setView(view === "back" ? "front" : "back");
          }}
        />
        <IconButton icon="chevronRight" label={t("store.preview.turnRight")} onClick={() => setTurn(turn + Math.PI / 4)} />
        {animated && (
          <>
            <span className="store-stage-sep" />
            <IconButton icon={playing ? "pause" : "play"} label={playing ? t("store.preview.pause") : t("store.preview.play")} aria-pressed={playing} onClick={() => setPaused(playing)} />
          </>
        )}
      </div>
    </>
  );
});
