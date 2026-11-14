import assert from "node:assert/strict";
import { test } from "node:test";
import fixtures from "./showcaseFixtures.json" with { type: "json" };
import { EMPTY, imageType, liveSlides, nextIndex, rotation, sameSet, sane, type Slide } from "./showcase.ts";

const slide = (over: Partial<Slide> = {}): Slide => ({
  id: "6f1c2a8e-3b4d-4e5f-9a0b-1c2d3e4f5a6b",
  title: "Emberfall SMP",
  alt: "Emberfall SMP",
  width: 2400,
  height: 800,
  imageKey: "a".repeat(64),
  linkHost: "emberfall.example.com",
  endsAt: null,
  weight: 1,
  ...over,
});

test("a slide set is trusted only in its shape: slides with what they need, rotation within 3 to 30 s", () => {
  assert.deepEqual(sane(null), EMPTY);
  assert.deepEqual(sane("slides"), EMPTY);
  assert.deepEqual(sane({ slides: "x", rotateMs: 6000 }), EMPTY);
  const kept = sane({ slides: [slide(), null, { id: 4 }, { id: "x", title: "no picture" }], rotateMs: 100 });
  assert.equal(kept.slides.length, 1);
  assert.equal(kept.rotateMs, 3000);
  assert.equal(sane({ slides: [], rotateMs: 1e9 }).rotateMs, 30_000);
  assert.equal(sane({ slides: [], rotateMs: Number.NaN }).rotateMs, 6000);
  assert.equal(sane({ slides: [] }).rotateMs, 6000);
});

test("a slide whose end passes between refreshes leaves the rotation", () => {
  const now = Date.parse(fixtures.now);
  const slides = [slide({ id: "a" }), slide({ id: "b", endsAt: now + 20 * 60_000 }), slide({ id: "c", endsAt: now - 1 })];
  assert.deepEqual(liveSlides(slides, now).map((s) => s.id), ["a", "b"]);
  assert.deepEqual(liveSlides(slides, now + 20 * 60_000).map((s) => s.id), ["a"]);
  assert.deepEqual(liveSlides([], now), []);
});

test("slides go round", () => {
  assert.equal(nextIndex(0, 3), 1);
  assert.equal(nextIndex(2, 3), 0);
  assert.equal(nextIndex(0, 1), 0);
  assert.equal(nextIndex(4, 0), 0);
});

test("only PNG, JPEG and WebP pictures are shown", () => {
  assert.equal(imageType(new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0])), "image/png");
  assert.equal(imageType(new Uint8Array([0xff, 0xd8, 0xff, 0xe0]).buffer), "image/jpeg");
  assert.equal(imageType(new TextEncoder().encode("RIFF\0\0\0\0WEBPVP8X")), "image/webp");
  assert.equal(imageType(new TextEncoder().encode("<svg xmlns='http://www.w3.org/2000/svg'/>")), null);
  assert.equal(imageType(new TextEncoder().encode("GIF89a")), null);
  assert.equal(imageType(new Uint8Array()), null);
});

test("a refresh with the same slides changes nothing", () => {
  const a = { slides: [slide()], rotateMs: 6000 };
  assert.ok(sameSet(a, { slides: [slide()], rotateMs: 6000 }));
  assert.ok(!sameSet(a, { slides: [slide({ imageKey: "b".repeat(64) })], rotateMs: 6000 }));
  assert.ok(!sameSet(a, { slides: [slide()], rotateMs: 7000 }));
  assert.ok(!sameSet(a, EMPTY));
});

test("the rotation pauses: only time not paused counts", () => {
  let now = 0;
  const r = rotation(6000, () => now);
  now = 2000;
  assert.equal(r.left(), 4000);
  r.pause();
  now = 60_000;
  assert.ok(r.paused());
  assert.equal(r.left(), 4000);
  r.resume();
  now = 63_000;
  assert.equal(r.left(), 1000);
  now = 70_000;
  assert.equal(r.left(), 0);
  // Pausing twice, or resuming twice, changes nothing.
  const s = rotation(6000, () => now);
  s.pause();
  s.pause();
  s.resume();
  s.resume();
  assert.equal(s.left(), 6000);
});

test("the preview's fixtures are what the launcher accepts: 3:1 at 1200×400 or more, showcase-media pictures", () => {
  for (const [name, set] of Object.entries(fixtures.sets)) {
    for (const s of set.slides as { id: string; image_url: string; width: number; height: number; link_url: string | null }[]) {
      assert.ok(s.width >= 1200 && s.height >= 400 && 50 * Math.abs(s.width - 3 * s.height) <= 3 * s.height, `${name} ${s.id}: shape`);
      assert.match(s.image_url, new RegExp(`^https://[a-z0-9]{20}\\.supabase\\.co/storage/v1/object/public/showcase-media/slides/${s.id}/[0-9a-f]{64}\\.(png|jpg|webp)$`));
      assert.ok(s.link_url === null || s.link_url.startsWith("https://"));
    }
  }
});
