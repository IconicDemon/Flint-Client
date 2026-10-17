import { useEffect, useId, useLayoutEffect, useMemo, useRef, useState, type CSSProperties, type KeyboardEvent, type PointerEvent } from "react";
import type { Profile, ProfileState } from "../../lib/api";
import { CARD_RATIO, computeLayout, dotWindow, HoverIntent, MOVE_MS, RepeatThrottle, swipeSteps, WheelStepper } from "../../lib/carousel";
import { sound } from "../../lib/sound";
import { isBusy, stateOf, useStoreSelect } from "../../lib/store";
import { loaderLabel, platformBlock, versionMeta } from "../../lib/versions";
import { Badge, IconButton, Spinner } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { STATE_LABEL } from "./ProfilesPage";

/** Card width before the container or window height asks for something smaller. */
const BASE_CARD_WIDTH = 310;
/** Vertical space the page keeps for its header, the dots and the action row below the cards. */
const RESERVED_HEIGHT = 390;
/** Room above and below the cards for the selection and focus rings. */
const STAGE_PAD = 14;

const cardWidthFor = (viewportHeight: number) => Math.max(200, Math.min(BASE_CARD_WIDTH, Math.round((viewportHeight - RESERVED_HEIGHT) / CARD_RATIO)));

/**
 * Profiles as a row of portrait cards around a focused, centred one. Focus is only a preview:
 * hovering (after a short rest), arrows, dots, drag and horizontal scrolling move it. Clicking a
 * card, or Enter/Space, calls `onActivate`, which the page turns into "select for Play". Hover
 * never selects, and nothing here launches the game.
 *
 * Motion is CSS transitions on transform and opacity only (profiles.css), so a new focus retargets
 * every card from wherever it is mid-glide; nothing queues and no script runs per frame. The stage
 * carries a few attributes the styles read: `data-resting` (the pointer hasn't moved since the cards
 * slid under it, so the card it's on gets no hover preview), `data-dragging`, and `data-instant`
 * for the one style pass after a resize, which re-lays the cards without animating.
 */
export function ProfileCarousel({
  profiles,
  focusIndex,
  selectedId,
  onFocus,
  onActivate,
}: {
  profiles: Profile[];
  focusIndex: number;
  selectedId: string | null;
  onFocus: (id: string) => void;
  /** A click or Enter/Space. `focused` is false when the click landed on a neighbour, which also moves focus to it. */
  onActivate: (profile: Profile, focused: boolean) => void;
}) {
  // Cards show only each profile's state name, so progress events during a download (same state,
  // new numbers) don't re-render the carousel; it re-renders when a state actually changes.
  const store = useStoreSelect((s) => ({ info: s.info, stateNames: profiles.map((p) => stateOf(s.states, p).state).join(" ") }));
  const stateNames = store.stateNames.split(" ");
  const baseId = useId();
  const stageRef = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(0);
  const [viewportHeight, setViewportHeight] = useState(() => window.innerHeight);
  const count = profiles.length;
  const layout = useMemo(() => computeLayout(count, focusIndex, width, cardWidthFor(viewportHeight)), [count, focusIndex, width, viewportHeight]);
  const focus = layout.focus;

  // Measured before paint so the first frame is already laid out. A resize changes card sizes at
  // once, so the positions follow at once too instead of gliding after them.
  useLayoutEffect(() => {
    const stage = stageRef.current;
    if (!stage) return;
    let measured = 0;
    let height = window.innerHeight;
    const measure = () => {
      const next = stage.clientWidth;
      if (measured && next !== measured) stage.setAttribute("data-instant", "");
      measured = next;
      setWidth(next);
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(stage);
    const onResize = () => {
      if (window.innerHeight === height) return;
      height = window.innerHeight;
      stage.setAttribute("data-instant", "");
      setViewportHeight(height);
    };
    window.addEventListener("resize", onResize);
    return () => {
      observer.disconnect();
      window.removeEventListener("resize", onResize);
    };
  }, []);

  // After a resize render: apply the new geometry with transitions off, then turn them back on.
  useLayoutEffect(() => {
    const stage = stageRef.current;
    if (!stage?.hasAttribute("data-instant")) return;
    void stage.offsetWidth;
    stage.removeAttribute("data-instant");
  });

  const go = (index: number) => {
    const next = Math.min(count - 1, Math.max(0, index));
    if (next !== focus && profiles[next]) onFocus(profiles[next].id);
  };

  // The latest props are read through refs so the intent and gesture helpers live for the whole mount.
  const latest = useRef({ profiles, onFocus });
  useLayoutEffect(() => {
    latest.current = { profiles, onFocus };
  });
  const intent = useRef<HoverIntent<string> | null>(null);
  intent.current ??= new HoverIntent<string>((id) => {
    if (latest.current.profiles.some((p) => p.id === id)) latest.current.onFocus(id);
  });
  const wheel = useRef(new WheelStepper());
  const keyPace = useRef(new RepeatThrottle());
  const drag = useRef<{ pointer: number; x: number; moved: boolean } | null>(null);
  const suppressClick = useRef(false);
  const syncResting = () => stageRef.current?.toggleAttribute("data-resting", !!intent.current?.resting);

  useEffect(() => () => intent.current?.dispose(), []);
  // Cards slide under a pointer that may not have moved; wait for real movement before hover counts
  // again. Before paint, so the card arriving under the pointer never flashes a hover preview.
  useLayoutEffect(() => {
    intent.current?.settle();
    syncResting();
  }, [focus]);

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const keys: Record<string, () => void> = {
      ArrowLeft: () => go(focus - 1),
      ArrowRight: () => go(focus + 1),
      Home: () => go(0),
      End: () => go(count - 1),
      Enter: () => profiles[focus] && onActivate(profiles[focus], true),
      " ": () => profiles[focus] && onActivate(profiles[focus], true),
    };
    const action = keys[e.key];
    if (!action || e.altKey || e.metaKey || e.ctrlKey) return;
    e.preventDefault();
    // A held Enter would select and then open the profile; a held arrow glides at a followable pace.
    if (e.key === "Enter" || e.key === " " ? !e.repeat : keyPace.current.allow(e.repeat, e.timeStamp)) action();
  };

  const onPointerDown = (e: PointerEvent<HTMLDivElement>) => {
    if (e.button !== 0) return;
    drag.current = { pointer: e.pointerId, x: e.clientX, moved: false };
  };

  const onPointerMove = (e: PointerEvent<HTMLDivElement>) => {
    // Cards only report hover; the stage sees every move (and all of them while a drag holds capture).
    if (e.pointerType === "mouse") intent.current?.point(e.clientX, e.clientY);
    const d = drag.current;
    if (!d || d.pointer !== e.pointerId || d.moved || Math.abs(e.clientX - d.x) < 8) return;
    d.moved = true;
    intent.current?.cancel();
    e.currentTarget.setAttribute("data-dragging", "");
    e.currentTarget.setPointerCapture(e.pointerId);
  };

  const endDrag = (e: PointerEvent<HTMLDivElement>, cancelled: boolean) => {
    const d = drag.current;
    if (!d || d.pointer !== e.pointerId) return;
    drag.current = null;
    if (!d.moved) return;
    e.currentTarget.removeAttribute("data-dragging");
    // The release point is where the pointer rests now: hover waits for real movement from here,
    // including after the focus change below, so the card left under the pointer can't pull focus back.
    intent.current?.settle(e.clientX, e.clientY);
    syncResting();
    suppressClick.current = true;
    window.setTimeout(() => (suppressClick.current = false), 0);
    if (!cancelled) {
      const steps = swipeSteps(e.clientX - d.x, layout.cardWidth);
      if (steps) go(focus + steps);
    }
  };

  const ready = layout.cardWidth > 0;
  const active = profiles[focus];
  const dots = dotWindow(count, focus);

  return (
    <div className="pc" style={{ "--pc-move": `${MOVE_MS}ms` } as CSSProperties}>
      <div
        ref={stageRef}
        className="pc-stage"
        role="listbox"
        aria-label="Profiles"
        aria-orientation="horizontal"
        aria-activedescendant={active && ready ? `${baseId}-${active.id}` : undefined}
        tabIndex={0}
        style={{ height: layout.cardHeight + STAGE_PAD * 2 }}
        onKeyDown={onKeyDown}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={(e) => endDrag(e, false)}
        onPointerCancel={(e) => endDrag(e, true)}
        onPointerLeave={() => intent.current?.leave()}
        onWheel={(e) => {
          const step = wheel.current.push(e.deltaX, e.deltaY, e.timeStamp);
          if (step) go(focus + step);
        }}
      >
        {ready &&
          layout.slots.map((slot) => {
            const p = profiles[slot.index];
            const isFocus = slot.offset === 0;
            const isSelected = p.id === selectedId;
            const s = { profileId: p.id, state: stateNames[slot.index] as ProfileState["state"] };
            const blocked = platformBlock(p.gameVersion, store.info);
            const meta = versionMeta(p.gameVersion);
            const status = blocked ? "Not supported" : STATE_LABEL[s.state];
            const loader = `${loaderLabel(p.loader)}${p.loaderVersion ? ` ${p.loaderVersion}` : ""}`;
            return (
              <div
                key={p.id}
                id={`${baseId}-${p.id}`}
                role="option"
                aria-selected={isSelected}
                aria-hidden={slot.visible ? undefined : true}
                aria-label={`${p.name}, Minecraft ${p.gameVersion}, ${loader}, ${status}${isSelected ? ", selected for Play" : ""}`}
                className={`pc-card ${isFocus ? "is-focus" : ""} ${isSelected ? "is-selected" : ""} ${slot.visible ? "" : "is-hidden"}`}
                style={
                  {
                    width: layout.cardWidth,
                    height: layout.cardHeight,
                    marginLeft: -layout.cardWidth / 2,
                    transform: `translate3d(${slot.x}px, 0, 0) scale(${slot.scale})`,
                    opacity: slot.opacity,
                    zIndex: slot.z,
                    "--dim": slot.dim,
                  } as CSSProperties
                }
                onClick={(e) => {
                  // Only the first click of a double-click counts: the second would land on the card it
                  // just centred and selected, and open the profile's content by accident.
                  if (suppressClick.current || !slot.visible || e.detail > 1) return;
                  sound.play("tap");
                  // Hover moved this card to the centre a moment ago and the pointer hasn't moved since:
                  // the click was aimed at it, even if the slide has put a different card under the
                  // pointer. It selects that card, and never opens it.
                  const aimed = intent.current?.recentFire(MOVE_MS);
                  if (aimed && active && aimed === active.id) {
                    onActivate(active, false);
                    return;
                  }
                  if (!isFocus) onFocus(p.id);
                  onActivate(p, isFocus);
                }}
                onPointerMove={(e) => {
                  if (e.pointerType !== "mouse" || drag.current?.moved || !slot.visible) return;
                  if (isFocus) intent.current?.leave();
                  else intent.current?.move(p.id, e.clientX, e.clientY);
                  syncResting();
                }}
                onPointerLeave={() => intent.current?.leave(p.id)}
              >
                <span className="pc-glow" aria-hidden />
                <span className="pc-face">
                  <img className="pc-art" src={meta.card} alt="" width={600} height={840} decoding="async" draggable={false} />
                  <span className="pc-shade" aria-hidden />
                  <span className="pc-dim" aria-hidden />
                  <span className="pc-top">
                    {isSelected ? <span className="pc-flag is-play">Selected for Play</span> : isFocus ? <span className="pc-flag">Previewing</span> : null}
                    {p.modpack && <Badge>Modpack</Badge>}
                  </span>
                  <span className="pc-info">
                    <strong className="pc-name" title={p.name}>
                      {p.name}
                    </strong>
                    <span className="pc-meta">
                      Minecraft {p.gameVersion} · {loader}
                    </span>
                    <span className={`state-pill state-${blocked ? "error" : s.state}`}>
                      {isBusy(s) && <Spinner size={10} />}
                      {status}
                    </span>
                  </span>
                  {/* What a click on the centred card does: select it, or open it once it's selected. */}
                  {isFocus && !blocked && (
                    <span className={`pc-cta ${isSelected ? "is-open" : ""}`} aria-hidden>
                      {isSelected ? "Open" : "Select"}
                      <Icon name={isSelected ? "chevronRight" : "check"} size={13} />
                    </span>
                  )}
                </span>
              </div>
            );
          })}
      </div>
      {count > 1 && (
        <div className="pc-nav">
          <IconButton icon="chevronLeft" label="Previous profile" onClick={() => go(focus - 1)} disabled={focus === 0} />
          <div className={`pc-dots ${dots.compact ? "is-compact" : ""}`}>
            {profiles.slice(dots.start, dots.end).map((p, k) => {
              const i = dots.start + k;
              // In a window, the end dots shrink when more profiles lie beyond them.
              const edge = (i === dots.start && i > 0) || (i === dots.end - 1 && i < count - 1);
              return (
                <button
                  key={p.id}
                  type="button"
                  className={`pc-dot ${i === focus ? "is-focus" : ""} ${p.id === selectedId ? "is-selected" : ""} ${edge ? "is-edge" : ""}`}
                  aria-label={`Show ${p.name}${p.id === selectedId ? " (selected for Play)" : ""}`}
                  aria-current={i === focus ? "true" : undefined}
                  onClick={() => go(i)}
                />
              );
            })}
          </div>
          {dots.compact && (
            <span className="pc-count" aria-hidden>
              {focus + 1} / {count}
            </span>
          )}
          <IconButton icon="chevronRight" label="Next profile" onClick={() => go(focus + 1)} disabled={focus === count - 1} />
        </div>
      )}
    </div>
  );
}
