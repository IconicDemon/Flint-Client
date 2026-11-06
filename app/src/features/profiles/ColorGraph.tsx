import { useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from "react";
import { hexToHsv, hsvToHex, normalizeHex, type Hsv } from "../../lib/color";
import { useT } from "../../lib/i18n";

/** The colours picked most recently (newest first), kept on this computer. */
const RECENT_KEY = "flint.iconColors";
const RECENT_MAX = 8;

function readRecent(): string[] {
  try {
    const list = JSON.parse(localStorage.getItem(RECENT_KEY) ?? "[]");
    return Array.isArray(list) ? list.map((c) => normalizeHex(String(c))).filter((c): c is string => !!c).slice(0, RECENT_MAX) : [];
  } catch {
    return [];
  }
}

/** Keeps `hex` at the front of the recent colours (when an icon is saved with it). */
export function rememberColor(hex: string) {
  const color = normalizeHex(hex);
  if (!color) return;
  try {
    localStorage.setItem(RECENT_KEY, JSON.stringify([color, ...readRecent().filter((c) => c !== color)].slice(0, RECENT_MAX)));
  } catch {
    // Without storage the recent colours just aren't kept.
  }
}

const clamp01 = (n: number) => Math.min(1, Math.max(0, n));

/**
 * Any colour: a square of saturation (across) and brightness (up), a hue bar under it, the colour as hex to type or paste,
 * and the colours picked recently. Pointer and arrow keys work on both the square and the bar.
 */
export function ColorGraph({ value, onChange }: { value: string; onChange: (hex: string) => void }) {
  const t = useT();
  const [hsv, setHsv] = useState<Hsv>(() => hexToHsv(value) ?? { h: 20, s: 0.78, v: 0.93 });
  const [text, setText] = useState(value);
  const [recent] = useState(readRecent);
  const ours = useRef(value);
  const area = useRef<HTMLDivElement>(null);
  const hue = useRef<HTMLDivElement>(null);

  // A colour set from outside (another profile, a recent swatch): follow it, keeping the hue where grey has none.
  useEffect(() => {
    if (value === ours.current) return;
    ours.current = value;
    const next = hexToHsv(value);
    if (next) setHsv((h) => ({ ...next, h: next.s === 0 || next.v === 0 ? h.h : next.h }));
    setText(value);
  }, [value]);

  const apply = (next: Hsv) => {
    setHsv(next);
    const hex = hsvToHex(next);
    ours.current = hex;
    setText(hex);
    onChange(hex);
  };

  const fromArea = (e: PointerEvent<HTMLDivElement>) => {
    const r = area.current!.getBoundingClientRect();
    apply({ ...hsv, s: clamp01((e.clientX - r.left) / r.width), v: clamp01(1 - (e.clientY - r.top) / r.height) });
  };
  const fromHue = (e: PointerEvent<HTMLDivElement>) => {
    const r = hue.current!.getBoundingClientRect();
    apply({ ...hsv, h: clamp01((e.clientX - r.left) / r.width) * 359.9 });
  };
  const drag = (move: (e: PointerEvent<HTMLDivElement>) => void) => ({
    onPointerDown: (e: PointerEvent<HTMLDivElement>) => {
      e.currentTarget.setPointerCapture(e.pointerId);
      move(e);
    },
    onPointerMove: (e: PointerEvent<HTMLDivElement>) => {
      if (e.currentTarget.hasPointerCapture(e.pointerId)) move(e);
    },
  });

  const areaKeys = (e: KeyboardEvent) => {
    const step = e.shiftKey ? 0.1 : 0.02;
    const delta: Record<string, [number, number]> = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, step], ArrowDown: [0, -step] };
    const d = delta[e.key];
    if (!d) return;
    e.preventDefault();
    apply({ ...hsv, s: clamp01(hsv.s + d[0]), v: clamp01(hsv.v + d[1]) });
  };
  const hueKeys = (e: KeyboardEvent) => {
    const step = e.shiftKey ? 20 : 4;
    const d = e.key === "ArrowLeft" || e.key === "ArrowDown" ? -step : e.key === "ArrowRight" || e.key === "ArrowUp" ? step : 0;
    if (!d) return;
    e.preventDefault();
    apply({ ...hsv, h: (hsv.h + d + 360) % 360 });
  };

  const typed = (raw: string) => {
    setText(raw);
    const hex = normalizeHex(raw);
    if (!hex) return;
    const next = hexToHsv(hex)!;
    setHsv((h) => ({ ...next, h: next.s === 0 || next.v === 0 ? h.h : next.h }));
    ours.current = hex;
    onChange(hex);
  };

  const pure = hsvToHex({ h: hsv.h, s: 1, v: 1 });
  const current = hsvToHex(hsv);
  return (
    <div className="cg">
      <div
        ref={area}
        className="cg-area"
        style={{ backgroundColor: pure }}
        role="slider"
        tabIndex={0}
        aria-label={t("workspace.color.area")}
        aria-valuetext={current}
        onKeyDown={areaKeys}
        {...drag(fromArea)}
      >
        <span className="cg-thumb" style={{ left: `${hsv.s * 100}%`, top: `${(1 - hsv.v) * 100}%`, backgroundColor: current }} />
      </div>
      <div
        ref={hue}
        className="cg-hue"
        role="slider"
        tabIndex={0}
        aria-label={t("workspace.color.hue")}
        aria-valuemin={0}
        aria-valuemax={360}
        aria-valuenow={Math.round(hsv.h)}
        onKeyDown={hueKeys}
        {...drag(fromHue)}
      >
        <span className="cg-hue-thumb" style={{ left: `${(hsv.h / 360) * 100}%`, backgroundColor: pure }} />
      </div>
      <label className="cg-hex">
        <span className="cg-swatch" style={{ backgroundColor: current }} />
        <input
          value={text}
          maxLength={7}
          spellCheck={false}
          aria-label={t("workspace.color.hex")}
          onChange={(e) => typed(e.target.value)}
          onBlur={() => setText(current)}
        />
      </label>
      {recent.length > 0 && (
        <div className="cg-recent" role="group" aria-label={t("workspace.color.recent")}>
          {recent.map((c) => (
            <button key={c} type="button" className={`cg-recent-swatch ${c === current ? "is-on" : ""}`} style={{ backgroundColor: c }} aria-label={c} title={c} onClick={() => typed(c)} />
          ))}
        </div>
      )}
    </div>
  );
}
