import { FONTS, THEMES, type FontId, type ThemeId } from "../lib/settings";
import { Icon } from "./Icon";

const Check = ({ active }: { active: boolean }) =>
  active ? (
    <span className="pick-check" aria-hidden>
      <Icon name="check" size={10} />
    </span>
  ) : null;

/** Each card is a miniature of Flint itself (rail, title, chips, Play) in that theme's own palette. */
export function ThemePicker({ value, onChange }: { value: ThemeId; onChange: (v: ThemeId) => void }) {
  return (
    <div className="pick-grid themes" role="radiogroup" aria-label="Theme">
      {THEMES.map((t) => (
        <button key={t.id} type="button" role="radio" aria-checked={value === t.id} className={`pick-card ${value === t.id ? "is-active" : ""}`} onClick={() => onChange(t.id)}>
          <span className="theme-preview" data-theme={t.id} aria-hidden>
            <span className="tp-rail">
              <i />
              <i />
              <i />
            </span>
            <span className="tp-scene">
              <span className="tp-title" />
              <span className="tp-chips">
                <i />
                <i />
              </span>
              <span className="tp-play" />
            </span>
          </span>
          <span className="pick-meta">
            <strong>
              <span className="pick-brand">Flint</span> {t.name}
            </strong>
            <span className="pick-note">{t.note}</span>
          </span>
          <Check active={value === t.id} />
        </button>
      ))}
    </div>
  );
}

/** Each card sets one line in that option's own fonts. */
export function FontPicker({ value, onChange }: { value: FontId; onChange: (v: FontId) => void }) {
  return (
    <div className="pick-grid fonts" role="radiogroup" aria-label="Typography">
      {FONTS.map((f) => (
        <button key={f.id} type="button" role="radio" aria-checked={value === f.id} className={`pick-card font-card ${value === f.id ? "is-active" : ""}`} data-font-preview={f.id} onClick={() => onChange(f.id)}>
          <span className="font-specimen" aria-hidden>
            <span className="font-aa">Aa</span>
            <span className="font-line">Fast where it matters.</span>
          </span>
          <span className="pick-meta">
            <strong>{f.name}</strong>
            <span className="pick-note">{f.note}</span>
          </span>
          <Check active={value === f.id} />
        </button>
      ))}
    </div>
  );
}
