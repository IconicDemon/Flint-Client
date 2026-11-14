/**
 * A 3D cosmetic's Bedrock entity geometry (Blockbench "Bedrock Entity", `minecraft:geometry`, format 1.12+) as three.js
 * meshes on skinview3d's player, for the wardrobe's preview. The Store checked the files and Rust checked their hashes
 * (src-tauri/src/store/model.rs); this reads them defensively all the same: a cube or bone it can't use is skipped.
 *
 * Coordinates: Bedrock's player model is 16 px to a block with the feet at y 0, the neck at y 24 and the back at +z; its x
 * runs the other way from three's. skinview3d's skin has the neck at y 0 and the face at +z. So a Bedrock point
 * (x, y, z) is (x, y − 24, −z) in the skin's space, and a rotation (rx, ry, rz) is three's (rx, −ry, −rz) in ZYX order
 * (Blockbench's own import, turned to face the camera). Each root bone hangs on the body part its name (or the item's
 * `attach.anchor`) says, so it moves with that part.
 */
import { BoxGeometry, DoubleSide, Group, Mesh, MeshStandardMaterial, NearestFilter, SRGBColorSpace, Texture, type Object3D } from "three";
import type { PlayerObject } from "skinview3d";
import { ANCHORS, type Anchor } from "./cosmeticTypes.ts";

type Vec3 = [number, number, number];
type FaceUv = { uv?: number[]; uv_size?: number[] };
type Cube = { origin?: number[]; size?: number[]; uv?: number[] | Record<string, FaceUv>; inflate?: number; mirror?: boolean; pivot?: number[]; rotation?: number[] };
type Bone = { name?: string; parent?: string; pivot?: number[]; rotation?: number[]; mirror?: boolean; cubes?: Cube[] };

export type ParsedGeometry = { textureWidth: number; textureHeight: number; bones: Bone[] };

/** The most cubes Flint draws for one cosmetic (the Store's limit). */
export const MAX_CUBES = 160;

const vec = (v: unknown, fallback: Vec3 = [0, 0, 0]): Vec3 =>
  Array.isArray(v) && v.length >= 3 && v.slice(0, 3).every((n) => typeof n === "number" && Number.isFinite(n)) ? [v[0], v[1], v[2]] : fallback;

/** The first geometry of a Bedrock geometry file, or null when it isn't one. */
export function parseGeometry(text: string): ParsedGeometry | null {
  let json: unknown;
  try {
    json = JSON.parse(text);
  } catch {
    return null;
  }
  const list = (json as Record<string, unknown>)?.["minecraft:geometry"];
  const geometry = Array.isArray(list) ? (list[0] as Record<string, unknown>) : null;
  if (!geometry || typeof geometry !== "object") return null;
  const description = (geometry.description ?? {}) as Record<string, unknown>;
  const size = (n: unknown) => (typeof n === "number" && n > 0 && n <= 4096 ? n : 64);
  const bones = Array.isArray(geometry.bones) ? (geometry.bones as Bone[]).filter((b) => b && typeof b === "object") : [];
  return { textureWidth: size(description.texture_width), textureHeight: size(description.texture_height), bones };
}

/** Where each anchor sits in the skin's space (skinview3d's part groups), to place a root bone on its part. */
const PART_ORIGIN: Record<Anchor, Vec3> = {
  anchor_body: [0, -6, 0],
  anchor_head: [0, 0, 0],
  anchor_right_arm: [-5, -2, 0],
  anchor_left_arm: [5, -2, 0],
};

export function partFor(player: PlayerObject, anchor: Anchor): Object3D {
  const skin = player.skin;
  return anchor === "anchor_head" ? skin.head : anchor === "anchor_right_arm" ? skin.rightArm : anchor === "anchor_left_arm" ? skin.leftArm : skin.body;
}

const toSkin = ([x, y, z]: Vec3): Vec3 => [x, y - 24, -z];
const DEG = Math.PI / 180;
function rotate(object: Object3D, r: Vec3) {
  object.rotation.order = "ZYX";
  object.rotation.set(r[0] * DEG, -r[1] * DEG, -r[2] * DEG);
}

type Rect = [number, number, number, number];
/** BoxGeometry's faces: +x, −x, +y, −y, +z (front), −z (back). */
const FACE_NAMES = ["west", "east", "up", "down", "north", "south"] as const;

function boxRects(u: number, v: number, w: number, h: number, d: number): Rect[] {
  return [
    [u + d + w, v + d, u + d + w + d, v + d + h], // +x: the player's left (west)
    [u, v + d, u + d, v + d + h], // −x: the player's right (east)
    [u + d, v, u + d + w, v + d], // up
    [u + d + w, v, u + d + w + w, v + d], // down
    [u + d, v + d, u + d + w, v + d + h], // front (north)
    [u + d + w + d, v + d, u + d + w + d + w, v + d + h], // back (south)
  ];
}

/** Writes the faces' texture rectangles (pixels on the geometry's UV grid) into the box, as skinview3d orders them. */
function setRects(box: BoxGeometry, rects: (Rect | null)[], tw: number, th: number) {
  const data: number[] = [];
  rects.forEach((rect, face) => {
    if (!rect) {
      data.push(0, 0, 0, 0, 0, 0, 0, 0);
      return;
    }
    const [x1, y1, x2, y2] = rect;
    const p = [
      [x1 / tw, 1 - y2 / th],
      [x2 / tw, 1 - y2 / th],
      [x2 / tw, 1 - y1 / th],
      [x1 / tw, 1 - y1 / th],
    ];
    const order = face === 3 ? [0, 1, 3, 2] : [3, 2, 0, 1];
    for (const i of order) data.push(p[i][0], p[i][1]);
  });
  box.attributes.uv.array.set(data);
  box.attributes.uv.needsUpdate = true;
}

function cubeRects(cube: Cube, size: Vec3, mirror: boolean): (Rect | null)[] {
  const [w, h, d] = size;
  let rects: (Rect | null)[];
  if (Array.isArray(cube.uv)) {
    rects = boxRects(cube.uv[0] ?? 0, cube.uv[1] ?? 0, Math.floor(w), Math.floor(h), Math.floor(d));
  } else if (cube.uv && typeof cube.uv === "object") {
    const faces = cube.uv;
    rects = FACE_NAMES.map((name) => {
      const f = faces[name];
      if (!f?.uv || f.uv.length < 2) return null;
      const [fw, fh] = f.uv_size ?? [0, 0];
      return [f.uv[0], f.uv[1], f.uv[0] + fw, f.uv[1] + fh];
    });
  } else {
    rects = boxRects(0, 0, w, h, d);
  }
  if (mirror) {
    [rects[0], rects[1]] = [rects[1], rects[0]];
    rects = rects.map((r) => (r ? [r[2], r[1], r[0], r[3]] : r));
  }
  return rects;
}

export type BuiltModel = { roots: { anchor: Anchor; group: Group }[]; material: MeshStandardMaterial; dispose(): void };

/**
 * The geometry's meshes, one group per root bone with its anchor. `fallback` is where bones whose name isn't an anchor
 * hang (the item's `attach.anchor`, else the body). `mirrorX` flips the model to the other side (a pet with `side: left`).
 */
export function buildModel(geometry: ParsedGeometry, texture: Texture | null, fallback: Anchor, mirrorX = false): BuiltModel {
  const material = new MeshStandardMaterial({ map: texture, transparent: true, alphaTest: 0.05, side: DoubleSide, color: texture ? 0xffffff : 0xb9b2a8 });
  const groups = new Map<string, Group>();
  const pivots = new Map<string, Vec3>();
  const roots: { anchor: Anchor; group: Group }[] = [];
  const geometries: BoxGeometry[] = [];
  const { textureWidth: tw, textureHeight: th } = geometry;
  let cubes = 0;

  for (const bone of geometry.bones) {
    const name = typeof bone.name === "string" ? bone.name : `bone${groups.size}`;
    const pivot = toSkin(vec(bone.pivot));
    const group = new Group();
    group.name = name;
    if (bone.rotation) rotate(group, vec(bone.rotation));
    groups.set(name, group);
    pivots.set(name, pivot);
    for (const cube of bone.cubes ?? []) {
      if (++cubes > MAX_CUBES) break;
      const size = vec(cube.size, [0, 0, 0]);
      if (size.some((n) => n < 0 || n > 512)) continue;
      const inflate = typeof cube.inflate === "number" && Math.abs(cube.inflate) <= 16 ? cube.inflate : 0;
      const origin = vec(cube.origin);
      const box = new BoxGeometry(Math.max(0.001, size[0] + inflate * 2), Math.max(0.001, size[1] + inflate * 2), Math.max(0.001, size[2] + inflate * 2));
      setRects(box, cubeRects(cube, size, !!(cube.mirror ?? bone.mirror)), tw, th);
      geometries.push(box);
      const mesh = new Mesh(box, material);
      // The cube's centre in the skin's space (z turns around, so its far corner is −(z + depth)).
      const centre: Vec3 = [origin[0] + size[0] / 2, origin[1] + size[1] / 2 - 24, -(origin[2] + size[2] / 2)];
      if (cube.rotation) {
        const cubePivot = toSkin(vec(cube.pivot, vec(bone.pivot)));
        const holder = new Group();
        holder.position.set(cubePivot[0] - pivot[0], cubePivot[1] - pivot[1], cubePivot[2] - pivot[2]);
        rotate(holder, vec(cube.rotation));
        mesh.position.set(centre[0] - cubePivot[0], centre[1] - cubePivot[1], centre[2] - cubePivot[2]);
        holder.add(mesh);
        group.add(holder);
      } else {
        mesh.position.set(centre[0] - pivot[0], centre[1] - pivot[1], centre[2] - pivot[2]);
        group.add(mesh);
      }
    }
  }

  for (const bone of geometry.bones) {
    const name = typeof bone.name === "string" ? bone.name : "";
    const group = groups.get(name);
    if (!group) continue;
    const pivot = pivots.get(name)!;
    const parent = typeof bone.parent === "string" ? groups.get(bone.parent) : undefined;
    if (parent && parent !== group) {
      const at = pivots.get(bone.parent!)!;
      group.position.set(pivot[0] - at[0], pivot[1] - at[1], pivot[2] - at[2]);
      parent.add(group);
    } else {
      const anchor = (ANCHORS as string[]).includes(name) ? (name as Anchor) : fallback;
      const origin = PART_ORIGIN[anchor];
      group.position.set(pivot[0] - origin[0], pivot[1] - origin[1], pivot[2] - origin[2]);
      const root = new Group();
      root.add(group);
      if (mirrorX) root.scale.x = -1;
      roots.push({ anchor, group: root });
    }
  }

  return {
    roots,
    material,
    dispose() {
      for (const { group } of roots) group.removeFromParent();
      geometries.forEach((g) => g.dispose());
      material.dispose();
    },
  };
}

/** A pixel-art texture from a decoded image; a vertical strip of `frames` shows one frame at a time (see `showFrame`). */
export function modelTexture(image: HTMLImageElement, frames: number): Texture {
  const texture = new Texture(image);
  texture.magFilter = NearestFilter;
  texture.minFilter = NearestFilter;
  texture.generateMipmaps = false;
  texture.colorSpace = SRGBColorSpace;
  if (frames > 1) texture.repeat.set(1, 1 / frames);
  showFrame(texture, 0, frames);
  texture.needsUpdate = true;
  return texture;
}

/** Shows frame `f` (from the top) of a texture strip. */
export function showFrame(texture: Texture, f: number, frames: number) {
  if (frames <= 1) return;
  texture.offset.set(0, 1 - (f + 1) / frames);
}

/** Frame durations for `frames` frames from the Store's list (one value: every frame; none: 100 ms; at least 50 ms). */
export function frameDurations(frames: number, frameMs: readonly number[] | undefined): number[] {
  const list = frameMs ?? [];
  return Array.from({ length: Math.max(1, frames) }, (_, i) => Math.max(50, list[i] ?? list[list.length - 1] ?? 100));
}
