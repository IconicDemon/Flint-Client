import { useEffect, useLayoutEffect, useMemo, useRef, useState, type RefObject } from "react";
import { useT } from "../../lib/i18n";
import { liveSlides, nextIndex, rotation, type Slide } from "../../lib/showcase";
import { knownPicture, openSlide, slidePicture, useShowcase } from "../../lib/showcaseFeed";
import { sound } from "../../lib/sound";
import { onWindowMinimized } from "../../lib/windowFocus";
import { Icon } from "../../ui/Icon";

/**
 * Home's small sponsored card: Flint's own slides (sponsors, partners, promotions) from Flint Admin, 3:1, under the side
 * column's other cards. Labelled "Sponsored" so players always know what it is. Rotates with a thin progress line, pauses
 * while the pointer or keyboard is on it (and while Home isn't shown), fades between slides (no animation with reduced
 * motion), and a click opens the slide's link in the system browser. With no slides, or none whose picture loaded, nothing
 * is rendered: no gap, no error. When the side column has no room for it (a small window with a featured partner), it steps
 * aside rather than crowd Home.
 */
export function ShowcaseCard({ active }: { active: boolean }) {
  const t = useT();
  const { slides: all, rotateMs } = useShowcase();
  const now = useScheduleClock(all);
  const slides = useMemo(() => liveSlides(all, now), [all, now]);
  const keys = slides.map((s) => s.imageKey).join("|");

  // Pictures load in the Store's display order. A slide whose picture can't be had (the feed can name an image deleted a
  // minute ago) or won't decode is skipped.
  const [pictures, setPictures] = useState<Record<string, string>>(() => known(slides));
  const [broken, setBroken] = useState<ReadonlySet<string>>(() => new Set());
  useEffect(() => {
    let live = true;
    void (async () => {
      for (const slide of slides) {
        const url = await slidePicture(slide);
        if (!live) return;
        if (url) setPictures((p) => (p[slide.imageKey] === url ? p : { ...p, [slide.imageKey]: url }));
      }
    })();
    return () => {
      live = false;
    };
  }, [keys]);

  const ready = slides.filter((s) => pictures[s.imageKey] && !broken.has(s.imageKey));
  const [chosen, setChosen] = useState<string | null>(null);
  const [cycle, setCycle] = useState(0);
  // The Store's order, from its first slide; the slide on screen stays while it is still showing.
  const index = Math.max(0, ready.findIndex((s) => s.id === chosen));
  const current: Slide | undefined = ready[index];
  const card = useRef<HTMLElement>(null);
  const room = useRoom(card, !!current);

  // Paused while pointed at or keyboard-focused, while Home is behind another page, and while the window is hidden or
  // minimized. (A mouse click's focus doesn't pause it: the slide's link has just opened in the browser.)
  const [hover, setHover] = useState(false);
  const [focus, setFocus] = useState(false);
  const hidden = useWindowHidden();
  const paused = hover || focus || !active || hidden || !room;
  const rotates = ready.length > 1;

  const clock = useRef(rotation(rotateMs));
  const step = `${current?.id}:${cycle}:${rotateMs}`;
  // The timer reads the slides as they are when it fires (a picture may have arrived since it was set).
  const latest = useRef({ ready, index });
  latest.current = { ready, index };
  const go = (to: number) => {
    setChosen(latest.current.ready[to]?.id ?? null);
    setCycle((c) => c + 1);
  };
  useEffect(() => {
    clock.current = rotation(rotateMs);
  }, [step]);
  useEffect(() => {
    if (!rotates) return;
    if (paused) {
      clock.current.pause();
      return;
    }
    clock.current.resume();
    const timer = window.setTimeout(() => go(nextIndex(latest.current.index, latest.current.ready.length)), clock.current.left());
    return () => window.clearTimeout(timer);
  }, [step, paused, rotates]);

  if (!current) return null;

  const open = (slide: Slide) => {
    sound.play("tap");
    void openSlide(slide);
  };

  return (
    <section
      ref={card}
      hidden={!room}
      className={`side-showcase${paused ? " is-paused" : ""}`}
      aria-labelledby="showcase-title"
      aria-roledescription={rotates ? "carousel" : undefined}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      onFocus={(e) => setFocus(e.target.matches(":focus-visible"))}
      onBlur={(e) => !e.currentTarget.contains(e.relatedTarget as Node | null) && setFocus(false)}
    >
      <header>
        <h2 id="showcase-title">{t("home.showcase.sponsored")}</h2>
        {current.linkHost && <span className="showcase-host">{current.linkHost}</span>}
      </header>
      <div className="showcase-frame">
        {ready.map((slide) => {
          const shown = slide.id === current.id;
          const className = `showcase-slide${shown ? " is-current" : ""}${slide.linkHost ? " is-link" : ""}`;
          const picture = (
            <img
              src={pictures[slide.imageKey]}
              alt=""
              width={slide.width}
              height={slide.height}
              decoding="async"
              draggable={false}
              onError={() => setBroken((b) => new Set(b).add(slide.imageKey))}
            />
          );
          return slide.linkHost ? (
            <button
              key={slide.id}
              type="button"
              className={className}
              inert={!shown}
              aria-hidden={!shown}
              tabIndex={shown ? 0 : -1}
              title={slide.title}
              aria-label={t("home.showcase.open", { title: slide.alt, host: slide.linkHost })}
              onClick={() => open(slide)}
            >
              {picture}
              <span className="showcase-go" aria-hidden="true">
                <Icon name="external" size={12} />
              </span>
            </button>
          ) : (
            <div key={slide.id} className={className} inert={!shown} aria-hidden={!shown} role="img" aria-label={slide.alt} title={slide.title}>
              {picture}
            </div>
          );
        })}
      </div>
      {rotates && (
        <div className="showcase-progress">
          {ready.map((slide, i) => (
            <button
              key={slide.id}
              type="button"
              className={`showcase-step${i < index ? " is-done" : i === index ? " is-current" : ""}`}
              aria-label={t("home.showcase.goTo", { index: i + 1, count: ready.length })}
              aria-current={i === index}
              onClick={() => i !== index && go(i)}
            >
              <span key={i === index ? step : "rest"} className="showcase-fill" style={i === index ? { animationDuration: `${rotateMs}ms` } : undefined} />
            </button>
          ))}
        </div>
      )}
    </section>
  );
}

/** Pictures already fetched this run, so the card shows at once when Home comes back. */
function known(slides: Slide[]): Record<string, string> {
  const out: Record<string, string> = {};
  for (const slide of slides) {
    const url = knownPicture(slide);
    if (url) out[slide.imageKey] = url;
  }
  return out;
}

/** The time now, moved on when the next slide's end comes (so it leaves the rotation then, between refreshes). */
function useScheduleClock(slides: Slide[]): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const next = Math.min(...slides.map((s) => (s.endsAt != null && s.endsAt > now ? s.endsAt : Infinity)));
    if (!Number.isFinite(next)) return;
    const timer = window.setTimeout(() => setNow(Date.now()), Math.min(2 ** 31 - 1, Math.max(1000, next - Date.now() + 50)));
    return () => window.clearTimeout(timer);
  }, [slides, now]);
  return now;
}

/**
 * Whether Home's side column has room for the card: with it, the column (news, the featured partner, friends) must still
 * end on the launch row's baseline without running past the bottom of the window. Without room the card steps aside
 * (hidden, not rotating) and comes back when the window grows or the column gets shorter.
 */
function useRoom(card: RefObject<HTMLElement | null>, present: boolean): boolean {
  const [room, setRoom] = useState(true);
  const measured = useRef(0);
  useLayoutEffect(() => {
    const el = card.current;
    const side = el?.parentElement;
    const layout = side?.parentElement;
    if (!el || !side || !layout || typeof ResizeObserver === "undefined") return;
    const check = () => {
      const style = getComputedStyle(layout);
      const available = layout.clientHeight - parseFloat(style.paddingTop) - parseFloat(style.paddingBottom);
      const gap = parseFloat(getComputedStyle(side).rowGap) || 0;
      if (!el.hidden) measured.current = el.offsetHeight;
      // Before it was ever shown: its 3:1 picture plus the label and progress line.
      const height = measured.current || side.clientWidth / 3 + 52;
      const others = side.offsetHeight - (el.hidden ? 0 : height + gap);
      setRoom(others + gap + height <= available + 1);
    };
    check();
    const observer = new ResizeObserver(check);
    observer.observe(side);
    observer.observe(layout);
    return () => observer.disconnect();
  }, [card, present]);
  return room;
}

/** The window is hidden or minimized (nothing to rotate for). */
function useWindowHidden(): boolean {
  const [hidden, setHidden] = useState(() => typeof document !== "undefined" && document.visibilityState === "hidden");
  const [minimized, setMinimized] = useState(false);
  useEffect(() => {
    const update = () => setHidden(document.visibilityState === "hidden");
    document.addEventListener("visibilitychange", update);
    const off = onWindowMinimized(setMinimized);
    return () => {
      document.removeEventListener("visibilitychange", update);
      off();
    };
  }, []);
  return hidden || minimized;
}
