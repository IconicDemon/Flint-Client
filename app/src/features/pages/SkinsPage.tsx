import { memo, useCallback, useEffect, useRef, useState } from "react";
import { IdleAnimation, SkinViewer, WalkingAnimation, type PlayerObject } from "skinview3d";
import { api, errorText, type SkinProfile } from "../../lib/api";
import { approach, createRenderLoop, motionWanted, pacedDelay, smoothstep, type Next } from "../../lib/renderLoop";
import { useSettings } from "../../lib/settings";
import { sound } from "../../lib/sound";
import { useStore } from "../../lib/store";
import { applyTrueColor } from "../../lib/trueColor";
import { onWindowFocus } from "../../lib/windowFocus";
import { Badge, Button, EmptyState, IconButton, Segmented, Spinner } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { Dropdown, Modal, useToast } from "../../ui/overlays";
import { Avatar } from "../onboarding/steps";
import type { Navigate } from "../shell/Shell";
import { PageHeader } from "./PageHeader";

type Variant = "classic" | "slim";
type Pending = { dataUrl: string; base64: string; name: string; legacy: boolean; detected: Variant | null };
type Pose = "idle" | "walk" | "still";

const ZOOM = { min: 0.45, max: 1.3, step: 0.12, initial: 0.62 };
const YAW = 0.42;
const TURN_MS = 420;
/**
 * The preview renders only when something changes. Ambient motion (the Idle/Walk pose) plays at
 * this rate while the user is around, and eases back to rest over SETTLE_S once the window loses
 * focus or after DEEP_IDLE_MS without input; then nothing renders until the next input.
 */
const AMBIENT_FPS = 30;
const SETTLE_S = 0.6;
/** The pose ambient motion rests in: IdleAnimation's centre (arms slightly out, cape at its default tilt). */
const REST = { arm: Math.PI * 0.02, cape: Math.PI * 0.06 };

/** Blends the joints Idle and Walk animate toward REST: `s` = 1 keeps the animated pose, 0 is rest. */
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

/** Decodes each skin/cape image once per page visit, so hovering capes back and forth doesn't re-decode them. */
function decodeImage(cache: Map<string, Promise<HTMLImageElement>>, url: string): Promise<HTMLImageElement> {
  let image = cache.get(url);
  if (!image) {
    image = new Promise<HTMLImageElement>((resolve, reject) => {
      const img = new Image();
      // Same as skinview3d's own loader, so remote textures can still be uploaded to WebGL.
      img.crossOrigin = "anonymous";
      img.onload = () => resolve(img);
      img.onerror = reject;
      img.src = url;
    });
    cache.set(url, image);
    image.catch(() => cache.delete(url));
    if (cache.size > 32) cache.delete(cache.keys().next().value!);
  }
  return image;
}

/** Slim skins leave the outer 2px column of each arm's back face transparent. */
function detectVariant(img: HTMLImageElement): Variant | null {
  if (img.height !== 64) return null;
  const c = document.createElement("canvas");
  c.width = 64;
  c.height = 64;
  const g = c.getContext("2d", { willReadFrequently: true });
  if (!g) return null;
  g.drawImage(img, 0, 0);
  const strip = g.getImageData(54, 20, 2, 12).data;
  for (let i = 3; i < strip.length; i += 4) if (strip[i] !== 0) return "classic";
  return "slim";
}

function readPng(file: File): Promise<Pending> {
  return new Promise((resolve, reject) => {
    if (file.type && file.type !== "image/png") return reject(new Error(`“${file.name}” isn't a PNG. Minecraft skins are PNG images.`));
    if (file.size > 64 * 1024) return reject(new Error(`“${file.name}” is ${Math.round(file.size / 1024)} KB. Skin files must be smaller than 64 KB.`));
    const reader = new FileReader();
    reader.onerror = () => reject(new Error("That file couldn't be read."));
    reader.onload = () => {
      const dataUrl = String(reader.result);
      const img = new Image();
      img.onload = () => {
        const ok = img.width === 64 && (img.height === 64 || img.height === 32);
        if (!ok) return reject(new Error(`Minecraft skins are 64×64 or 64×32 pixels. This image is ${img.width}×${img.height}.`));
        resolve({ dataUrl, base64: dataUrl.split(",")[1] ?? "", name: file.name, legacy: img.height === 32, detected: detectVariant(img) });
      };
      img.onerror = () => reject(new Error("That isn't a valid PNG image."));
      img.src = dataUrl;
    };
    reader.readAsDataURL(file);
  });
}

export default function SkinsPage({ navigate }: { navigate: Navigate }) {
  const store = useStore();
  const toast = useToast();
  const { motionOk } = useSettings();
  const account = store.activeAccount;
  const current = account ? store.skins[account.uuid] : undefined;
  const [profile, setProfile] = useState<SkinProfile | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  /** The capes list needs the saved sign-in, which this run hasn't read yet. */
  const [locked, setLocked] = useState(false);
  const [pending, setPending] = useState<Pending | null>(null);
  const [variant, setVariant] = useState<Variant>("classic");
  const [fileError, setFileError] = useState<string | null>(null);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  /** undefined = not previewing; null = previewing "no cape". */
  const [capePreview, setCapePreview] = useState<string | null | undefined>(undefined);
  const fileInput = useRef<HTMLInputElement>(null);
  const previewOnly = !!account?.qa;
  const accountVariant: Variant = profile?.variant ?? current?.model ?? "classic";
  // Keyed on the account's identity, not the object: every reloadAccounts() (including the one after
  // each skin or cape change below) hands out new account objects, which used to clear the profile
  // and fetch it from Minecraft again.
  const accountUuid = account?.uuid ?? null;
  const accountQa = !!account?.qa;
  const profileRequest = useRef(0);

  /** Opening the page never asks macOS for the saved sign-in; "Show my capes" (interactive) may. */
  const loadProfile = useCallback(
    async (interactive: boolean) => {
      const request = ++profileRequest.current;
      if (!accountUuid || accountQa) return setProfile(null);
      setLoadError(null);
      setLocked(false);
      if (interactive) setProfile(null);
      try {
        const result = await api.skinProfile(accountUuid, interactive);
        // A newer request (another account, or Retry) owns the panel now.
        if (request !== profileRequest.current) return;
        if ("locked" in result) setLocked(true);
        else setProfile(result);
      } catch (e) {
        if (request === profileRequest.current) setLoadError(errorText(e));
      }
    },
    [accountUuid, accountQa],
  );

  useEffect(() => {
    setPending(null);
    setProfile(null);
    setFileError(null);
    void loadProfile(false);
  }, [loadProfile]);

  useEffect(() => {
    if (!pending) setVariant(accountVariant);
  }, [accountVariant, pending]);

  const choose = async (file?: File | null) => {
    if (!file) return;
    setFileError(null);
    try {
      const next = await readPng(file);
      setPending(next);
      setVariant(next.detected ?? accountVariant);
    } catch (e) {
      setFileError(errorText(e));
    }
  };

  const drop = {
    onDragOver: (e: React.DragEvent) => {
      e.preventDefault();
      setDragging(true);
    },
    onDragLeave: (e: React.DragEvent) => {
      if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setDragging(false);
    },
    onDrop: (e: React.DragEvent) => {
      e.preventDefault();
      setDragging(false);
      void choose(e.dataTransfer.files[0]);
    },
  };

  const after = async (result: SkinProfile, title: string) => {
    if (!account) return;
    profileRequest.current++; // this answer is newer than any load still in flight
    setProfile(result);
    await store.refreshSkin(account.uuid).catch(() => null);
    await store.reloadAccounts().catch(() => null);
    sound.play("success");
    toast({ tone: "success", title });
  };

  const upload = async () => {
    if (!account || !pending) return;
    setBusy("upload");
    setUploadError(null);
    try {
      const result = await api.skinUpload(account.uuid, pending.base64, variant);
      setPending(null);
      setConfirming(false);
      await after(result, "Skin updated");
    } catch (e) {
      setUploadError(errorText(e));
    } finally {
      setBusy(null);
    }
  };

  const reset = async () => {
    if (!account) return;
    setBusy("reset");
    try {
      const result = await api.skinReset(account.uuid);
      setResetting(false);
      await after(result, "Skin reset to the default");
    } catch (e) {
      toast({ tone: "danger", title: "Couldn't reset the skin", body: errorText(e) });
    } finally {
      setBusy(null);
    }
  };

  const setCape = async (id: string | null) => {
    if (!account) return;
    setBusy(`cape:${id ?? "none"}`);
    try {
      await after(await api.capeSet(account.uuid, id), id ? "Cape equipped" : "Cape hidden");
    } catch (e) {
      toast({ tone: "danger", title: "Couldn't change the cape", body: errorText(e) });
    } finally {
      setBusy(null);
    }
  };

  if (!account) {
    return (
      <div className="page-body">
        <PageHeader title="Skins" subtitle="Preview and change how you look in game." />
        <EmptyState
          icon="shirt"
          title="Sign in to manage your skin"
          body="Your skin and capes belong to your Minecraft account. Sign in with Microsoft to see and change them."
          action={
            <Button variant="primary" onClick={() => navigate("settings", "accounts")}>
              Manage accounts
            </Button>
          }
        />
      </div>
    );
  }

  const shownSkin = pending?.dataUrl ?? current?.dataUrl ?? null;
  const activeCape = profile?.capes.find((c) => c.active) ?? null;
  const shownCape = capePreview !== undefined ? capePreview : (activeCape?.dataUrl ?? current?.capeUrl ?? null);
  const shownVariant = pending ? variant : accountVariant;
  const previewCape = (url: string | null) => ({
    onPointerEnter: () => setCapePreview(url),
    onPointerLeave: () => setCapePreview(undefined),
    onFocus: () => setCapePreview(url),
    onBlur: () => setCapePreview(undefined),
  });

  return (
    <div className="page-body skins-page">
      <PageHeader
        title="Skins"
        subtitle="Drag to turn your character. Changes apply to your Minecraft account everywhere."
        actions={
          store.accounts.length > 1 && (
            <Dropdown
              label="Account"
              value={account.uuid}
              onChange={(uuid) => void store.switchAccount(uuid)}
              options={store.accounts.map((a) => ({ value: a.uuid, label: a.username, hint: a.qa ? "Test account" : undefined }))}
            />
          )
        }
      />

      <div className="skins-layout">
        <section className={`skins-stage ${dragging ? "is-drop" : ""}`} {...drop}>
          {pending && <span className="skins-preview-tag">Preview · not applied yet</span>}
          <SkinViewport skin={shownSkin} cape={shownCape} model={shownVariant} animate={motionOk} showBack={capePreview !== undefined && !!capePreview} label={`${account.username}'s skin`} />
          {!shownSkin && !dragging && (
            <div className="skins-drop is-hint">
              <Icon name="shirt" size={26} />
              {current === undefined ? "Loading skin…" : "No skin loaded for this account"}
              <span className="faint">Choose or drop a 64×64 or 64×32 PNG to preview it here.</span>
            </div>
          )}
          {dragging && (
            <div className="skins-drop">
              <Icon name="upload" size={26} />
              Drop to preview — nothing is uploaded yet
            </div>
          )}
        </section>

        <aside className="skins-panel">
          <div className="skins-card skins-who">
            <Avatar account={account} size={40} />
            <span>
              <strong>{account.username}</strong>
              <span className="faint">{previewOnly ? "Local test account" : "Minecraft: Java Edition"}</span>
            </span>
            {previewOnly ? <Badge tone="warning">Preview only</Badge> : <Badge>{accountVariant === "slim" ? "Slim arms" : "Classic arms"}</Badge>}
            {loadError && (
              <p className="skins-error" role="alert">
                <Icon name="alert" size={14} /> {loadError}
                <button type="button" onClick={() => void loadProfile(true)}>
                  Retry
                </button>
              </p>
            )}
          </div>

          <div className="skins-card">
            <h2 className="skins-h">{pending ? "Preview" : "Change skin"}</h2>
            {pending ? (
              <>
                <div className="skins-file">
                  <img src={pending.dataUrl} alt="" />
                  <span>
                    <strong title={pending.name}>{pending.name}</strong>
                    <span className="faint">{pending.legacy ? "64×32 legacy skin" : "64×64 skin"}</span>
                  </span>
                </div>
                <span className="skins-label">Arm style</span>
                <Segmented
                  label="Arm style"
                  value={variant}
                  onChange={setVariant}
                  options={[
                    { value: "classic", label: "Classic" },
                    { value: "slim", label: "Slim" },
                  ]}
                />
                <p className="faint skins-note">
                  {pending.detected
                    ? `This image looks like a ${pending.detected} skin. Change it if the arms look wrong in the preview.`
                    : "Older 64×32 skins only work with classic arms."}
                </p>
                <div className="skins-actions">
                  <Button variant="ghost" onClick={() => setPending(null)}>
                    Cancel
                  </Button>
                  <Button
                    variant="primary"
                    icon="upload"
                    disabled={previewOnly}
                    onClick={() => {
                      setUploadError(null);
                      setConfirming(true);
                    }}
                  >
                    Apply skin
                  </Button>
                </div>
                {previewOnly && <p className="faint skins-note">Test accounts can preview skins, but can't upload them.</p>}
              </>
            ) : (
              <>
                <div className={`skins-upload ${dragging ? "is-over" : ""}`} {...drop}>
                  <span className="skins-upload-icon" aria-hidden>
                    <Icon name="shirt" size={22} />
                  </span>
                  <span className="skins-upload-text">
                    <strong>Upload a new skin</strong>
                    <span className="faint">PNG · 64×64, or classic 64×32 · up to 64 KB</span>
                  </span>
                  <Button variant="primary" icon="upload" className="skins-upload-btn" onClick={() => fileInput.current?.click()}>
                    Choose file
                  </Button>
                  <span className="skins-upload-hint">You can also drop a file on the preview. Nothing is uploaded until you confirm.</span>
                </div>
                {fileError && (
                  <p className="skins-error" role="alert">
                    <Icon name="alert" size={14} /> {fileError}
                  </p>
                )}
                {!previewOnly && (
                  <button type="button" className="skins-link" onClick={() => setResetting(true)}>
                    <Icon name="undo" size={14} /> Reset to a default skin
                  </button>
                )}
              </>
            )}
            <input
              ref={fileInput}
              type="file"
              accept="image/png"
              hidden
              onChange={(e) => {
                void choose(e.target.files?.[0]);
                e.target.value = "";
              }}
            />
          </div>

          {!previewOnly && (
            <div className="skins-card">
              <h2 className="skins-h">
                Capes
                {profile && profile.capes.length > 0 && <span className="faint"> · {profile.capes.length} owned</span>}
              </h2>
              {locked && !profile ? (
                <>
                  <p className="faint skins-note">Your capes load from your Minecraft account. macOS may ask once to let Flint use your saved sign-in.</p>
                  <Button size="sm" onClick={() => void loadProfile(true)}>
                    Show my capes
                  </Button>
                </>
              ) : !profile && !loadError ? (
                <Spinner />
              ) : profile && profile.capes.length === 0 ? (
                <p className="faint skins-note">This account doesn't own any capes. Capes come from Minecraft events, promotions and purchases.</p>
              ) : profile ? (
                <>
                  <div className="cape-grid" role="radiogroup" aria-label="Cape">
                    <button
                      type="button"
                      role="radio"
                      aria-checked={!activeCape}
                      className={`cape-tile ${!activeCape ? "is-active" : ""}`}
                      disabled={!!busy}
                      onClick={() => activeCape && void setCape(null)}
                      {...previewCape(null)}
                    >
                      <span className="cape-none">
                        <Icon name="x" size={16} />
                      </span>
                      <span>None</span>
                    </button>
                    {profile.capes.map((c) => (
                      <button
                        key={c.id}
                        type="button"
                        role="radio"
                        aria-checked={c.active}
                        className={`cape-tile ${c.active ? "is-active" : ""}`}
                        disabled={!!busy}
                        title={c.alias}
                        onClick={() => !c.active && void setCape(c.id)}
                        {...previewCape(c.dataUrl)}
                      >
                        {busy === `cape:${c.id}` ? (
                          <span className="cape-none">
                            <Spinner size={14} />
                          </span>
                        ) : (
                          <span className="cape-art" style={c.dataUrl ? { backgroundImage: `url(${c.dataUrl})` } : undefined} />
                        )}
                        <span>{c.alias}</span>
                      </button>
                    ))}
                  </div>
                  <p className="faint skins-note">Hover a cape to see it on your character. Click to wear it.</p>
                </>
              ) : null}
            </div>
          )}
        </aside>
      </div>

      <Modal
        open={confirming}
        onClose={() => busy !== "upload" && setConfirming(false)}
        title="Apply this skin?"
        width={440}
        footer={
          <>
            <Button variant="ghost" onClick={() => setConfirming(false)} disabled={busy === "upload"}>
              Cancel
            </Button>
            <Button variant="primary" loading={busy === "upload"} onClick={() => void upload()}>
              {uploadError ? "Try again" : `Apply to ${account.username}`}
            </Button>
          </>
        }
      >
        <p className="muted modal-copy">
          This replaces the skin on your Minecraft account with <strong>{pending?.name}</strong> ({variant === "slim" ? "slim" : "classic"} arms). It shows everywhere you play, not just in Flint.
        </p>
        {uploadError && (
          <p className="install-warn" role="alert">
            <Icon name="alert" size={15} />
            {uploadError}
          </p>
        )}
      </Modal>

      <Modal
        open={resetting}
        onClose={() => busy !== "reset" && setResetting(false)}
        title="Reset to a default skin?"
        width={440}
        footer={
          <>
            <Button variant="ghost" onClick={() => setResetting(false)} disabled={busy === "reset"}>
              Cancel
            </Button>
            <Button variant="danger" loading={busy === "reset"} onClick={() => void reset()}>
              Reset skin
            </Button>
          </>
        }
      >
        <p className="muted modal-copy">Your account goes back to one of Minecraft's default characters. Keep a copy of your current skin file if you want to use it again.</p>
      </Modal>
    </div>
  );
}

/** How the React side drives the viewer's render loop (inert until the viewer exists). */
type Stage = {
  /** Something visible changed (camera, size): render it. */
  request(): void;
  /** New content or a pose change: render it and let ambient motion play again. */
  wake(): void;
  /** Turns the character to `yaw`, eased when `smooth`. */
  turnTo(yaw: number, smooth: boolean): void;
};
const INERT_STAGE: Stage = { request() {}, wake() {}, turnTo() {} };

const SkinViewport = memo(function SkinViewport({ skin, cape, model, animate, showBack, label }: { skin: string | null; cape: string | null; model: Variant; animate: boolean; showBack: boolean; label: string }) {
  const host = useRef<HTMLDivElement>(null);
  const viewerRef = useRef<SkinViewer | null>(null);
  const stage = useRef<Stage>(INERT_STAGE);
  const images = useRef(new Map<string, Promise<HTMLImageElement>>());
  /** The skin texture currently in the viewer, so an arm-style change doesn't reload it. */
  const loadedSkin = useRef<{ viewer: SkinViewer; url: string } | null>(null);
  const yaw = useRef(YAW);
  const [failed, setFailed] = useState(false);
  const [pose, setPose] = useState<Pose>("idle");

  useEffect(() => {
    const el = host.current;
    if (!el) return;
    const canvas = document.createElement("canvas");
    el.appendChild(canvas);
    let viewer: SkinViewer;
    try {
      viewer = new SkinViewer({
        canvas,
        width: el.clientWidth || 480,
        height: el.clientHeight || 600,
        enableControls: true,
        // Rendering is driven by the loop below, never by skinview3d's own every-frame loop.
        renderPaused: true,
        pixelRatio: Math.min(window.devicePixelRatio || 1, 1.75),
      });
    } catch {
      canvas.remove();
      setFailed(true);
      return;
    }
    viewer.fov = 30;
    viewer.zoom = ZOOM.initial;
    applyTrueColor(viewer);
    viewer.controls.enablePan = false;
    viewer.controls.minDistance = 20;
    viewer.controls.maxDistance = 120;
    viewer.playerWrapper.rotation.y = YAW;
    viewerRef.current = viewer;

    const player = viewer.playerObject;
    let focused = document.hasFocus();
    let visible = !document.hidden;
    let inputAt = performance.now();
    /** Strength of the ambient pose: 1 playing, 0 at rest. Eased so motion never starts or stops abruptly. */
    let amp = 0;
    let dragging = false;
    let inStep = false;
    let tween: { from: number; to: number; start: number } | null = null;
    const wanted = (now: number) => motionWanted({ enabled: !!viewer.animation, visible, focused, now, inputAt });

    const step = (now: number, dt: number): Next => {
      if (tween) {
        const t = Math.min(1, Math.max(0, (now - tween.start) / TURN_MS));
        viewer.playerWrapper.rotation.y = tween.from + (tween.to - tween.from) * (1 - Math.pow(1 - t, 3));
        if (t >= 1) tween = null;
      }
      const animation = viewer.animation;
      const want = wanted(now);
      if (animation) {
        amp = approach(amp, want ? 1 : 0, dt, SETTLE_S);
        if (amp > 0) animation.update(player, dt);
        blendToRest(player, smoothstep(amp));
      }
      try {
        // update() reports camera moves made outside the controls (zoom buttons, reset view) as
        // `change`; this frame draws them, so that event mustn't queue another one.
        inStep = true;
        viewer.controls.update();
      } finally {
        inStep = false;
      }
      viewer.render();
      // Turning and dragging follow the display; ambient motion sleeps between its frames.
      if (tween || dragging) return 0;
      if (animation && (want || amp > 0)) return pacedDelay(now, performance.now(), AMBIENT_FPS);
      return null;
    };
    const loop = createRenderLoop(step);

    /** Any input resets the deep-idle clock and restarts ambient motion if it had come to rest. */
    const onInput = () => {
      inputAt = performance.now();
      if (!loop.running && wanted(inputAt)) loop.request();
    };
    /** A click or key press only reaches the focused window, so it also repairs a missed focus event. */
    const onActivate = () => {
      focused = true;
      onInput();
    };
    const setFocused = (next: boolean) => {
      focused = next;
      if (next) return onInput();
      // A drag cut short by switching apps may never see its pointerup; don't keep rendering for it.
      // Otherwise nothing to do: a running loop eases to rest and stops by itself.
      dragging = false;
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
      const width = Math.max(1, Math.round(entry.contentRect.width));
      const height = Math.max(1, Math.round(entry.contentRect.height));
      viewer.setSize(width, height);
      // Resizing clears the canvas; draw again before this frame is painted.
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
    // Native focus events are reliable when another app comes to the front (and when minimized).
    const offFocus = onWindowFocus(setFocused);

    loadedSkin.current = null;
    stage.current = {
      request: () => loop.request(),
      wake: () => {
        inputAt = performance.now();
        loop.request();
      },
      turnTo: (to, smooth) => {
        const from = viewer.playerWrapper.rotation.y;
        if (!smooth || Math.abs(to - from) < 1e-4) {
          tween = null;
          viewer.playerWrapper.rotation.y = to;
        } else tween = { from, to, start: performance.now() };
        loop.request();
      },
    };
    loop.request();

    return () => {
      stage.current = INERT_STAGE;
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
      // Give the WebGL context back right away; WebKit allows only a few at once, and Home's
      // character needs one too.
      viewer.renderer.forceContextLoss();
      viewer.dispose();
      canvas.remove();
      viewerRef.current = null;
    };
  }, []);

  // Textures change only when the skin or cape does; stale loads (a cape hovered past) are dropped.
  useEffect(() => {
    const viewer = viewerRef.current;
    if (!viewer) return;
    const modelType = model === "slim" ? "slim" : "default";
    if (!skin) {
      loadedSkin.current = null;
      viewer.loadSkin(null);
      stage.current.wake();
      return;
    }
    if (loadedSkin.current?.viewer === viewer && loadedSkin.current.url === skin) {
      viewer.playerObject.skin.modelType = modelType;
      stage.current.wake();
      return;
    }
    let stale = false;
    decodeImage(images.current, skin).then(
      (img) => {
        if (stale || viewer.disposed) return;
        viewer.loadSkin(img, { model: modelType });
        loadedSkin.current = { viewer, url: skin };
        stage.current.wake();
      },
      () => {},
    );
    return () => {
      stale = true;
    };
  }, [skin, model, failed]);

  useEffect(() => {
    const viewer = viewerRef.current;
    if (!viewer) return;
    if (!cape) {
      viewer.loadCape(null);
      stage.current.wake();
      return;
    }
    let stale = false;
    decodeImage(images.current, cape).then(
      (img) => {
        if (stale || viewer.disposed) return;
        viewer.loadCape(img);
        stage.current.wake();
      },
      () => {},
    );
    return () => {
      stale = true;
    };
  }, [cape, failed]);

  useEffect(() => {
    const viewer = viewerRef.current;
    if (!viewer) return;
    const effective = animate ? pose : "still";
    viewer.animation = effective === "walk" ? new WalkingAnimation() : effective === "idle" ? new IdleAnimation() : null;
    if (viewer.animation) viewer.animation.speed = effective === "walk" ? 0.7 : 1;
    stage.current.wake();
  }, [pose, animate, failed]);

  const spin = useCallback((to: number) => stage.current.turnTo(to, animate), [animate]);

  useEffect(() => {
    spin(showBack ? yaw.current + Math.PI : yaw.current);
  }, [showBack, spin]);

  const zoomBy = (d: number) => {
    const v = viewerRef.current;
    if (!v) return;
    v.zoom = Math.max(ZOOM.min, Math.min(ZOOM.max, v.zoom + d));
    stage.current.request();
  };
  const turn = (d: number) => {
    yaw.current += d;
    spin(yaw.current);
  };
  const reset = () => {
    const v = viewerRef.current;
    if (!v) return;
    v.resetCameraPose();
    v.zoom = ZOOM.initial;
    yaw.current = YAW;
    spin(YAW);
  };

  if (failed) return <EmptyState icon="shirt" title="3D preview unavailable" body="This computer couldn't start the 3D renderer. Your skin can still be changed." />;
  return (
    <>
      <div className="skins-floor" aria-hidden />
      <div ref={host} className="skins-canvas" role="img" aria-label={label} />
      <div className="skins-tools" role="toolbar" aria-label="Preview controls">
        <IconButton icon="chevronLeft" label="Turn left" onClick={() => turn(-Math.PI / 4)} />
        <IconButton icon="chevronRight" label="Turn right" onClick={() => turn(Math.PI / 4)} />
        <span className="skins-tools-sep" />
        <IconButton icon="zoomOut" label="Zoom out" onClick={() => zoomBy(-ZOOM.step)} />
        <IconButton icon="zoomIn" label="Zoom in" onClick={() => zoomBy(ZOOM.step)} />
        <IconButton icon="rotate" label="Reset view" onClick={reset} />
        {animate && (
          <>
            <span className="skins-tools-sep" />
            <Segmented
              label="Pose"
              value={pose}
              onChange={setPose}
              options={[
                { value: "idle", label: "Idle" },
                { value: "walk", label: "Walk" },
                { value: "still", label: "Still" },
              ]}
            />
          </>
        )}
      </div>
    </>
  );
});
