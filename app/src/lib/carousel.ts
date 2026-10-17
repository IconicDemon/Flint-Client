/*
 * Pure layout and input helpers for the Profiles carousel. Nothing here touches the DOM, so the
 * geometry and the hover-intent timing are tested with Node's runner (`carousel.test.ts`).
 *
 * Geometry is in CSS pixels relative to the centre of the carousel's own box: a slot's `x` is the
 * horizontal offset of the card's centre, and the card is drawn at `cardWidth * scale`.
 */

/** Scale per distance from the focused card. The last entry is only used for hidden, tucked cards. */
export const SLOT_SCALES = [1, 0.86, 0.74, 0.64];
/** Darkening laid over a card per distance; cards stay opaque so overlaps never show through. */
export const SLOT_DIM = [0, 0.3, 0.52, 0.6];
/** Fraction of a card's own width that sits behind the nearer card. */
export const SLOT_OVERLAP = 0.22;
/** Portrait cards: height = width × this. */
export const CARD_RATIO = 1.4;
/** Cards within this distance of the focused one are rendered; the rest aren't mounted at all. */
export const RENDER_RADIUS = 3;
/** Content width at which two neighbours per side are shown, and at which one is. */
export const WIDE_MIN = 1280;
export const MEDIUM_MIN = 720;
/** Below this, neighbours are dropped rather than shrinking every card further. */
export const MIN_CARD_WIDTH = 180;
/** Space kept clear at each edge of the carousel box (focus and selection rings live here). */
export const EDGE_GUTTER = 10;
/**
 * How long a card takes to glide to a new slot. The page sets it as `--pc-move` (profiles.css owns
 * the easing), and clicks that land within it after a hover moved the cards are redirected (see
 * `HoverIntent.recentFire`).
 */
export const MOVE_MS = 580;

export type Slot = {
  index: number;
  /** Signed distance from the focused index. */
  offset: number;
  /** Shown and interactive. Rendered-but-hidden cards wait, tucked and transparent, to slide in. */
  visible: boolean;
  x: number;
  scale: number;
  opacity: number;
  /** Opacity of the dark layer over the card's art. */
  dim: number;
  z: number;
};

export type Layout = {
  focus: number;
  /** Neighbours shown on each side. */
  sides: number;
  cardWidth: number;
  cardHeight: number;
  /** Only cards within RENDER_RADIUS of the focus, in index order. */
  slots: Slot[];
};

export const clampIndex = (index: number, count: number) => (count <= 0 ? 0 : Math.min(count - 1, Math.max(0, Math.round(index))));

/** Neighbours per side that the container width allows, before checking the cards actually fit. */
export function sidesForWidth(width: number): number {
  if (width >= WIDE_MIN) return 2;
  if (width >= MEDIUM_MIN) return 1;
  return 0;
}

/** Centre offsets of slots 0..sides for a card of width 1. Each card tucks SLOT_OVERLAP of itself behind the nearer one. */
function unitCentres(sides: number): number[] {
  const centres = [0];
  for (let k = 1; k <= sides; k++) {
    const prev = SLOT_SCALES[k - 1];
    const own = SLOT_SCALES[k];
    centres.push(centres[k - 1] + prev / 2 + own / 2 - SLOT_OVERLAP * own);
  }
  return centres;
}

/** Distance from the centre to the outer edge of the outermost card, for a card of width 1. */
function unitExtent(sides: number): number {
  return unitCentres(sides)[sides] + SLOT_SCALES[sides] / 2;
}

/**
 * Positions for the cards around `focus`. The arrangement is always symmetric around the centre
 * (it doesn't shift towards the end of the list), cards never wrap around, and no card, shown or
 * hidden, extends past `width / 2 - EDGE_GUTTER` on either side.
 */
export function computeLayout(count: number, focusIndex: number, width: number, baseWidth: number): Layout {
  const focus = clampIndex(focusIndex, count);
  const half = Math.max(0, width / 2 - EDGE_GUTTER);
  if (count <= 0 || half <= 0) return { focus, sides: 0, cardWidth: 0, cardHeight: 0, slots: [] };

  // Fewer neighbours when the list is short: three profiles never need two per side.
  let sides = Math.min(sidesForWidth(width), Math.max(0, count - 1));
  let cardWidth = 0;
  for (;;) {
    cardWidth = Math.min(baseWidth, half / unitExtent(sides));
    if (sides === 0 || cardWidth >= MIN_CARD_WIDTH) break;
    sides--;
  }
  cardWidth = Math.max(0, Math.floor(cardWidth));
  const centres = unitCentres(sides).map((c) => c * cardWidth);

  const slots: Slot[] = [];
  const from = Math.max(0, focus - RENDER_RADIUS);
  const to = Math.min(count - 1, focus + RENDER_RADIUS);
  for (let index = from; index <= to; index++) {
    const offset = index - focus;
    const distance = Math.abs(offset);
    const sign = Math.sign(offset);
    const visible = distance <= sides;
    // Hidden cards wait behind the outermost visible slot, one size smaller, so they slide in from there.
    const slot = visible ? distance : sides;
    const scale = visible ? SLOT_SCALES[distance] : SLOT_SCALES[Math.min(sides + 1, SLOT_SCALES.length - 1)];
    slots.push({
      index,
      offset,
      visible,
      x: sign * centres[slot] || 0,
      scale,
      opacity: visible ? 1 : 0,
      dim: SLOT_DIM[Math.min(distance, SLOT_DIM.length - 1)],
      z: 10 - distance,
    });
  }
  return { focus, sides, cardWidth, cardHeight: Math.round(cardWidth * CARD_RATIO), slots };
}

/** After `removedId` is deleted, the id that should take its place: the next one, else the previous one. */
export function neighbourAfterRemoval(ids: readonly string[], removedId: string): string | null {
  const i = ids.indexOf(removedId);
  if (i === -1) return null;
  return ids[i + 1] ?? ids[i - 1] ?? null;
}

/**
 * Focus steps for a finished drag of `dx` pixels: dragging left moves to later profiles. Short
 * drags are clicks or jitter and do nothing; one card width or so moves one step, at most three.
 */
export function swipeSteps(dx: number, cardWidth: number): number {
  const threshold = Math.max(36, cardWidth * 0.12);
  if (Math.abs(dx) < threshold) return 0;
  const steps = Math.min(3, Math.max(1, Math.round(Math.abs(dx) / Math.max(1, cardWidth * 0.6))));
  return dx < 0 ? steps : -steps;
}

export type Timers = {
  set: (fn: () => void, ms: number) => unknown;
  clear: (handle: unknown) => void;
};

const realTimers: Timers = {
  set: (fn, ms) => setTimeout(fn, ms),
  clear: (handle) => clearTimeout(handle as ReturnType<typeof setTimeout>),
};

/** How long the pointer must rest on a card before it moves to the centre. */
export const HOVER_DELAY_MS = 200;
/** After a focus change, the pointer must move this far before hover can move focus again. */
export const HOVER_SLOP_PX = 6;

/**
 * Hover intent with one pending target. A new target supersedes the old one (nothing queues), leaving
 * cancels, and each scheduled timer fires at most once.
 *
 * When focus changes, cards slide under a pointer that hasn't moved. `settle` remembers where the
 * pointer is, and hover is ignored until it actually moves, so a resting pointer can't walk focus
 * through the whole list one card at a time.
 */
export class HoverIntent<T> {
  private readonly fire: (target: T) => void;
  private readonly delay: number;
  private readonly slop: number;
  private readonly timers: Timers;
  private handle: unknown = null;
  private target: T | null = null;
  private token = 0;
  private readonly now: () => number;
  private last: { x: number; y: number } | null = null;
  private anchor: { x: number; y: number } | null = null;
  private fired: { target: T; at: number } | null = null;

  constructor(fire: (target: T) => void, options: { delay?: number; slop?: number; timers?: Timers; now?: () => number } = {}) {
    this.fire = fire;
    this.delay = options.delay ?? HOVER_DELAY_MS;
    this.slop = options.slop ?? HOVER_SLOP_PX;
    this.timers = options.timers ?? realTimers;
    this.now = options.now ?? (() => performance.now());
  }

  /** True while hover is ignored because the pointer hasn't moved since focus last changed. */
  get resting(): boolean {
    return this.anchor !== null;
  }

  /**
   * The target hover last moved focus to, if that happened less than `withinMs` ago and the pointer
   * hasn't really moved since. A click in that window was aimed at that card, even though the cards
   * are still sliding and a different one may be under the pointer by the time the button goes down.
   */
  recentFire(withinMs: number): T | null {
    if (!this.fired || !this.anchor || this.now() - this.fired.at > withinMs) return null;
    return this.fired.target;
  }

  /** The target that will fire if the pointer stays, if any. */
  get pending(): T | null {
    return this.target;
  }

  /** The pointer is over `target` at (x, y). Resting on the pending target keeps its timer running. */
  move(target: T, x: number, y: number) {
    this.last = { x, y };
    if (this.anchor) {
      if (Math.hypot(x - this.anchor.x, y - this.anchor.y) <= this.slop) return;
      this.anchor = null;
      this.fired = null;
    }
    if (this.target === target && this.handle !== null) return;
    this.cancel();
    const token = ++this.token;
    this.target = target;
    this.handle = this.timers.set(() => {
      // A cleared timer can still run if the platform had already dequeued it; the token catches that.
      if (token !== this.token || this.target === null) return;
      const fired = this.target;
      this.handle = null;
      this.target = null;
      this.settle();
      this.fired = { target: fired, at: this.now() };
      this.fire(fired);
    }, this.delay);
  }

  /** The pointer left `target` (or, with no argument, everything). */
  leave(target?: T) {
    if (target === undefined || target === this.target) this.cancel();
  }

  /** Drops the pending target, if any. */
  cancel() {
    if (this.handle !== null) this.timers.clear(this.handle);
    this.handle = null;
    this.target = null;
    this.token++;
  }

  /**
   * Records where the pointer is without hovering anything, for movement the cards don't see (over
   * the focused card, between cards, or during a drag), so a later `settle()` anchors at the real spot.
   */
  point(x: number, y: number) {
    this.last = { x, y };
  }

  /**
   * Focus moved under the pointer: cancel, and ignore hover until the pointer moves past the slop.
   * With no position, it anchors at the last one seen; a given position (the end of a drag) also
   * becomes the last one and forgets the last hover move, which the drag has overridden.
   */
  settle(x?: number, y?: number) {
    this.cancel();
    if (x !== undefined && y !== undefined) {
      this.last = { x, y };
      this.fired = null;
    }
    this.anchor = this.last ? { ...this.last } : null;
  }

  dispose() {
    this.cancel();
    this.last = null;
    this.anchor = null;
    this.fired = null;
  }
}

/** Gap between wheel events that ends a gesture (trackpad momentum keeps a gesture alive). */
export const WHEEL_GESTURE_GAP_MS = 220;
export const WHEEL_THRESHOLD = 28;
/** Minimum time between two wheel steps, however the second swipe was recognised. */
export const WHEEL_RESTEP_MS = 140;
/** Momentum has died down once events shrink to this fraction of the swipe's peak... */
export const WHEEL_DECAY = 0.5;
/** ...and a new swipe in the same direction shows as events growing again by at least this much. */
export const WHEEL_RISE_PX = 6;

/**
 * Turns horizontal wheel/trackpad scrolling into one focus step per swipe. Vertical scrolling is
 * ignored entirely (the caller never prevents it). `now` is passed in for testing.
 *
 * After a step, the swipe's momentum keeps arriving in the same direction and is swallowed. A new
 * swipe still counts without waiting for the momentum to end: one in the other direction starts
 * adding up at once, and one in the same direction is told apart by its events growing again after
 * the momentum had faded. Each step is the newest target, so quick swipes move focus one card each.
 */
export class WheelStepper {
  private sum = 0;
  private lastAt = -Infinity;
  private stepAt = -Infinity;
  /** Direction of the step whose momentum is still arriving, or 0. */
  private blocked = 0;
  /** Largest event since that step, and the smallest since that peak. */
  private peak = 0;
  private low = 0;

  /** Returns -1, 0 or 1. */
  push(deltaX: number, deltaY: number, now: number): number {
    if (now - this.lastAt > WHEEL_GESTURE_GAP_MS) {
      this.sum = 0;
      this.blocked = 0;
    }
    this.lastAt = now;
    if (Math.abs(deltaX) <= Math.abs(deltaY)) return 0;
    const dir = Math.sign(deltaX);
    const size = Math.abs(deltaX);
    if (dir === this.blocked) {
      const faded = this.low <= this.peak * WHEEL_DECAY;
      const fresh = faded && size >= Math.max(this.low * 2, this.low + WHEEL_RISE_PX) && now - this.stepAt >= WHEEL_RESTEP_MS;
      if (!fresh) {
        if (size > this.peak) this.peak = this.low = size;
        else this.low = Math.min(this.low, size);
        return 0;
      }
      this.blocked = 0;
      this.sum = 0;
    }
    // Movement the other way doesn't count towards this way.
    if (Math.sign(this.sum) === -dir) this.sum = 0;
    this.sum += deltaX;
    if (Math.abs(this.sum) < WHEEL_THRESHOLD || now - this.stepAt < WHEEL_RESTEP_MS) return 0;
    this.sum = 0;
    this.blocked = dir;
    this.stepAt = now;
    this.peak = this.low = size;
    return dir;
  }
}

/** While an arrow key is held, auto-repeat moves focus at most once per this long; separate presses always step. */
export const KEY_REPEAT_MS = 110;

/** Paces key auto-repeat so a held arrow glides through the cards at a pace the eye can follow. */
export class RepeatThrottle {
  private at = -Infinity;

  allow(repeat: boolean, now: number): boolean {
    if (repeat && now - this.at < KEY_REPEAT_MS) return false;
    this.at = now;
    return true;
  }
}

/** Up to this many profiles get one dot each. */
export const DOTS_MAX = 12;
/** Beyond that, this many dots around the focused one, with a "5 / 24" count beside them. */
export const DOTS_WINDOW = 9;

/** Which dots to draw: indices `start` up to (not including) `end`. `compact` when that isn't all of them. */
export function dotWindow(count: number, focus: number): { start: number; end: number; compact: boolean } {
  if (count <= DOTS_MAX) return { start: 0, end: Math.max(0, count), compact: false };
  const start = Math.min(Math.max(0, clampIndex(focus, count) - Math.floor(DOTS_WINDOW / 2)), count - DOTS_WINDOW);
  return { start, end: start + DOTS_WINDOW, compact: true };
}
