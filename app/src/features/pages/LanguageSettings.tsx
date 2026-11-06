import { useMemo, useState, type ReactNode } from "react";
import { formatLocale, LANGUAGES, resolveLanguage, searchable, useT, type LanguageSetting } from "../../lib/i18n";
import { languageCoverage } from "../../lib/i18nCoverage";
import { useSettings } from "../../lib/settings";
import { sound } from "../../lib/sound";
import { Callout, TextField } from "../../ui/controls";
import { Icon } from "../../ui/Icon";

type Language = (typeof LANGUAGES)[number];

/** "Português (Brasil)" / "Portuguese (Brazil)": a language's name, with its region where the variant matters. */
const nativeName = (l: Language) => (l.region ? `${l.native} (${l.region.native})` : l.native);
const englishName = (l: Language) => (l.region ? `${l.english} (${l.region.english})` : l.english);

/**
 * Settings → Language. Each language by its own name and its English one (no flags: a language isn't a
 * country), with the share of the interface it translates as the build counted it from the catalogs.
 * English is the original; every other language is marked Beta until people who speak it have reviewed
 * it, and the note says how the translations were made.
 */
export function LanguagePicker() {
  const t = useT();
  const { settings, update } = useSettings();
  const [query, setQuery] = useState("");
  const percent = useMemo(() => new Intl.NumberFormat(formatLocale(), { style: "percent" }), [t]);

  const system = LANGUAGES.find((l) => l.id === resolveLanguage("system")) ?? LANGUAGES[0];
  // The computer speaks a language Flint doesn't have yet, so "System default" means English.
  const systemUnavailable = system.id === "en" && !/^en\b/i.test(navigator.language ?? "en");

  const q = searchable(query.trim());
  const matches = (...names: (string | undefined)[]) => !q || names.some((n) => n !== undefined && searchable(n).includes(q));
  const shown = LANGUAGES.filter((l) => matches(l.native, l.english, l.region?.native, l.region?.english, l.id));
  const showSystem = matches(t("settings.language.system"), "system");
  const full = shown.filter((l) => languageCoverage(l.id).complete);
  const partial = shown.filter((l) => !languageCoverage(l.id).complete);

  const choose = (value: LanguageSetting) => {
    if (value === settings.language) return;
    sound.play("tap");
    update({ language: value });
  };

  const option = (l: Language) => {
    const coverage = languageCoverage(l.id);
    const source = l.id === "en";
    return (
      <Option
        key={l.id}
        selected={settings.language === l.id}
        onSelect={() => choose(l.id)}
        mark={l.id.slice(0, 2).toUpperCase()}
        lang={l.id}
        name={nativeName(l)}
        detail={source ? t("settings.language.source") : englishName(l)}
        aside={
          source ? null : (
            <>
              <span title={t("settings.language.coverageDetail", { translated: coverage.translated, count: coverage.total })}>
                {t("settings.language.coverage", { percent: percent.format(coverage.percent / 100) })}
              </span>
            </>
          )
        }
      />
    );
  };

  return (
    <div className="lang-picker">
      <TextField icon="search" placeholder={t("settings.language.search")} aria-label={t("settings.language.search")} value={query} onChange={(e) => setQuery(e.target.value)} />
      <div className="lang-groups" role="radiogroup" aria-label={t("settings.language.title")}>
        {showSystem && (
          <div className="lang-list">
            <Option
              selected={settings.language === "system"}
              onSelect={() => choose("system")}
              mark={<Icon name="globe" size={16} />}
              name={t("settings.language.system")}
              detail={systemUnavailable ? t("settings.language.systemUnavailable") : t("settings.language.systemFollows", { language: nativeName(system) })}
            />
          </div>
        )}
        {full.length > 0 && <Group title={t("settings.language.full")}>{full.map(option)}</Group>}
        {partial.length > 0 && <Group title={t("settings.language.partial")}>{partial.map(option)}</Group>}
        {!showSystem && shown.length === 0 && <p className="muted">{t("settings.language.none", { query: query.trim() })}</p>}
      </div>
      <Callout title={t("settings.language.note.title")}>{t("settings.language.note.body")}</Callout>
    </div>
  );
}

function Group({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="lang-group" role="group" aria-label={title}>
      <h3>{title}</h3>
      <div className="lang-list">{children}</div>
    </div>
  );
}

function Option({
  selected,
  onSelect,
  mark,
  name,
  detail,
  aside,
  lang,
}: {
  selected: boolean;
  onSelect: () => void;
  mark: ReactNode;
  name: string;
  detail: string;
  aside?: ReactNode;
  /** The language the name is written in, so screen readers pronounce "Deutsch" as German. */
  lang?: string;
}) {
  return (
    <button type="button" role="radio" aria-checked={selected} className={`lang-option ${selected ? "is-active" : ""}`} onClick={onSelect}>
      <span className="lang-mark" aria-hidden>
        {mark}
      </span>
      <span className="lang-names">
        <strong lang={lang}>{name}</strong>
        <span>{detail}</span>
      </span>
      <span className="lang-aside">{aside}</span>
      <span className="lang-check" aria-hidden>
        {selected && <Icon name="check" size={10} />}
      </span>
    </button>
  );
}
