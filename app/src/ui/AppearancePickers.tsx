import { useT, type MessageKey } from "../lib/i18n";
import { FONTS, THEMES, type EffectsLevel, type FontId, type ThemeId } from "../lib/settings";
import { Icon } from "./Icon";

/*
 * Theme, font and effects text in the interface language. lib/settings.tsx keeps the English originals;
 * theme names (Ember, Obsidian…) are names and stay as they are.
 */
export const THEME_NOTES: Record<ThemeId, MessageKey> = {
  ember: "appearance.themes.ember.note",
  obsidian: "appearance.themes.obsidian.note",
  moss: "appearance.themes.moss.note",
  bloodstone: "appearance.themes.bloodstone.note",
  dusk: "appearance.themes.dusk.note",
};

export const FONT_TEXT: Record<FontId, { name: MessageKey; note: MessageKey }> = {
  default: { name: "appearance.fonts.default.name", note: "appearance.fonts.default.note" },
  minecraft: { name: "appearance.fonts.minecraft.name", note: "appearance.fonts.minecraft.note" },
};

export const EFFECT_TEXT: Record<EffectsLevel, { name: MessageKey; note: MessageKey }> = {
  full: { name: "appearance.effects.full.name", note: "appearance.effects.full.note" },
  balanced: { name: "appearance.effects.balanced.name", note: "appearance.effects.balanced.note" },
  minimal: { name: "appearance.effects.minimal.name", note: "appearance.effects.minimal.note" },
};

const Check = ({ active }: { active: boolean }) =>
  active ? (
    <span className="pick-check" aria-hidden>
      <Icon name="check" size={10} />
    </span>
  ) : null;

/** Each card is a miniature of Flint itself (rail, title, chips, Play) in that theme's own palette. */
export function ThemePicker({ value, onChange }: { value: ThemeId; onChange: (v: ThemeId) => void }) {
  const t = useT();
  return (
    <div className="pick-grid themes" role="radiogroup" aria-label={t("ui.appearance.themes")}>
      {THEMES.map((theme) => (
        <button key={theme.id} type="button" role="radio" aria-checked={value === theme.id} className={`pick-card ${value === theme.id ? "is-active" : ""}`} onClick={() => onChange(theme.id)}>
          <span className="theme-preview" data-theme={theme.id} aria-hidden>
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
              <span className="pick-brand">Flint</span> {theme.name}
            </strong>
            <span className="pick-note">{t(THEME_NOTES[theme.id])}</span>
          </span>
          <Check active={value === theme.id} />
        </button>
      ))}
    </div>
  );
}

/** Each card sets one line in that option's own fonts. */
export function FontPicker({ value, onChange }: { value: FontId; onChange: (v: FontId) => void }) {
  const t = useT();
  return (
    <div className="pick-grid fonts" role="radiogroup" aria-label={t("ui.appearance.fonts")}>
      {FONTS.map((f) => (
        <button key={f.id} type="button" role="radio" aria-checked={value === f.id} className={`pick-card font-card ${value === f.id ? "is-active" : ""}`} data-font-preview={f.id} onClick={() => onChange(f.id)}>
          <span className="font-specimen" aria-hidden>
            <span className="font-aa">Aa</span>
            <span className="font-line">{t("ui.appearance.fontSpecimen")}</span>
          </span>
          <span className="pick-meta">
            <strong>{t(FONT_TEXT[f.id].name)}</strong>
            <span className="pick-note">{t(FONT_TEXT[f.id].note)}</span>
          </span>
          <Check active={value === f.id} />
        </button>
      ))}
    </div>
  );
}
