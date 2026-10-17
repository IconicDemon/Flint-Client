import type { SkinViewer } from "skinview3d";

/**
 * Renders Minecraft skins and capes with the colours their owners painted.
 *
 * skinview3d switches three.js colour management off, so textures are used exactly as stored
 * (sRGB values). The screen must then receive those values unchanged. With FXAA on, the scene goes
 * through an off-screen buffer that never encodes; with FXAA off, three.js renders straight to the
 * screen and, by default, applies a linear→sRGB encoding to values that are already sRGB. That
 * double encoding washed colours out toward white. Setting a linear output colour space makes both
 * paths identical.
 *
 * Lighting uses three.js physical units (diffuse = albedo × irradiance / π). The ambient light
 * supplies 85% and a key light above-left of the camera the remaining 15% on a camera-facing
 * surface, so front faces show their texture colour exactly while sides and tops get gentle,
 * Minecraft-like shading (about 85–92%). The key light has no distance falloff, so zooming doesn't
 * change brightness.
 */
const KEY_DIRECTION = { x: -0.35, y: 0.45, z: 1 };
const KEY_COS_FRONT = KEY_DIRECTION.z / Math.hypot(KEY_DIRECTION.x, KEY_DIRECTION.y, KEY_DIRECTION.z);
const AMBIENT_SHARE = 0.85;
/** Rough camera-to-model distance used to place the key light in camera space. */
const KEY_DISTANCE = 60;

export function applyTrueColor(viewer: SkinViewer) {
  viewer.renderer.outputColorSpace = "srgb-linear" as typeof viewer.renderer.outputColorSpace;
  viewer.globalLight.intensity = Math.PI * AMBIENT_SHARE;
  viewer.cameraLight.intensity = (Math.PI * (1 - AMBIENT_SHARE)) / KEY_COS_FRONT;
  viewer.cameraLight.decay = 0;
  viewer.cameraLight.position.set(KEY_DIRECTION.x * KEY_DISTANCE, KEY_DIRECTION.y * KEY_DISTANCE, 0);
  // FXAA blurs texture pixels. High-density screens don't need it for smooth edges, and skipping
  // it saves a full-screen pass per frame; both paths now produce the same colours.
  viewer.fxaaPass.enabled = (window.devicePixelRatio || 1) < 1.5;
}
