import { useEffect, useId, useRef, useState, type KeyboardEvent } from "react";
import { useT } from "../lib/i18n";
import { gbLabel, memoryInputText, memoryUnit, parseMemoryInput } from "../lib/memory";
import { Slider } from "./controls";

type Props = {
  /** MB, as the slider shows it. */
  value: number;
  min: number;
  max: number;
  step: number;
  /** A new value in MB: from the slider, or typed and committed. */
  onChange: (mb: number) => void;
  /** The slider's name (the field is named after it). */
  label: string;
};

/**
 * Memory for Minecraft: a slider with its value as a field beside it. The field takes a size typed in ("6", "6.5", "6144
 * MB"), set on Enter or when it loses focus, the slider's way: on its steps, between its ends (lib/memory.ts
 * parseMemoryInput), with a short line saying when the value was rounded or held at an end. Escape puts the value back.
 * Up and Down step it like the slider's arrow keys.
 */
export function MemorySlider({ value, min, max, step, onChange, label }: Props) {
  const t = useT();
  const hintId = useId();
  const input = useRef<HTMLInputElement>(null);
  /** What the player is typing; null shows the value. */
  const [draft, setDraft] = useState<string | null>(null);
  const [hint, setHint] = useState<{ text: string; error: boolean } | null>(null);
  const unit = memoryUnit();
  const shown = draft ?? memoryInputText(value);

  const commit = (text: string | null): boolean => {
    if (text === null) return true;
    const entry = parseMemoryInput(text, { min, max, step }, unit);
    if (!entry) {
      setHint({ text: t("ui.memory.invalid", { whole: memoryInputText(6144).replace(/[.,]0$/, ""), half: memoryInputText(6656) }), error: true });
      return false;
    }
    setDraft(null);
    setHint(
      entry.adjusted === "rounded"
        ? { text: t("ui.memory.rounded", { size: gbLabel(entry.mb) }), error: false }
        : entry.adjusted
          ? { text: t("ui.memory.range", { min: gbLabel(min), max: gbLabel(max) }), error: false }
          : null,
    );
    if (entry.mb !== value) onChange(entry.mb);
    return true;
  };

  // A size typed but not yet set is set when the field goes away with its panel (an outside click closes a popover).
  const latest = useRef({ draft, commit });
  latest.current = { draft, commit };
  useEffect(
    () => () => {
      const { draft: left, commit: set } = latest.current;
      if (left !== null) set(left);
    },
    [],
  );

  // Escape while typing puts the value back and stops there: it reaches the popover or dialog around only when nothing
  // is being typed. (Their own Escape listeners run in the document's capture phase, so this one listens before them.)
  useEffect(() => {
    if (draft === null) return;
    const onKey = (e: globalThis.KeyboardEvent) => {
      if (e.key !== "Escape" || document.activeElement !== input.current) return;
      e.stopPropagation();
      e.preventDefault();
      setDraft(null);
      setHint(null);
    };
    window.addEventListener("keydown", onKey, true);
    return () => window.removeEventListener("keydown", onKey, true);
  }, [draft]);

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter") {
      e.preventDefault();
      if (commit(draft)) e.currentTarget.select();
    } else if (e.key === "ArrowUp" || e.key === "ArrowDown") {
      e.preventDefault();
      const next = Math.min(max, Math.max(min, value + (e.key === "ArrowUp" ? step : -step)));
      setDraft(null);
      setHint(null);
      if (next !== value) onChange(next);
    }
  };

  return (
    <div className="memory-slider">
      <div className="memory-slider-row">
        <Slider
          label={label}
          min={min}
          max={max}
          step={step}
          value={value}
          format={gbLabel}
          showValue={false}
          onChange={(mb) => {
            setDraft(null);
            setHint(null);
            onChange(mb);
          }}
        />
        <label className={`memory-input ${hint?.error ? "is-invalid" : ""}`}>
          <input
            ref={input}
            type="text"
            inputMode="decimal"
            role="spinbutton"
            aria-label={t("ui.memory.inputLabel", { label, unit })}
            aria-valuemin={min / 1024}
            aria-valuemax={max / 1024}
            aria-valuenow={value / 1024}
            aria-valuetext={gbLabel(value)}
            aria-invalid={hint?.error || undefined}
            aria-describedby={hint ? hintId : undefined}
            autoComplete="off"
            spellCheck={false}
            size={4}
            value={shown}
            onFocus={(e) => e.currentTarget.select()}
            onChange={(e) => {
              setDraft(e.target.value);
              if (hint?.error) setHint(null);
            }}
            onKeyDown={onKeyDown}
            onBlur={() => {
              // A size that isn't one goes back to the value, and the line below says what the field takes.
              if (!commit(draft)) setDraft(null);
            }}
          />
          <span className="memory-input-unit" aria-hidden>
            {unit}
          </span>
        </label>
      </div>
      {hint && (
        <p id={hintId} className={`memory-hint ${hint.error ? "is-error" : ""}`} role={hint.error ? "alert" : "status"}>
          {hint.text}
        </p>
      )}
    </div>
  );
}
