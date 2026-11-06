// Settings → Java and memory: the memory Minecraft gets, the Java each version runs on (from the launcher's own
// rules, src-tauri/src/javaplan.rs), the runtimes Flint keeps, a custom Java and extra Java arguments.
import { useCallback, useEffect, useState } from "react";
import { api, errorText, type JavaOverview } from "../../lib/api";
import { bytes } from "../../lib/format";
import { useT } from "../../lib/i18n";
import { customJavaWarnings, groupVersions, rowDetail, rowJava, runtimeLabel } from "../../lib/javaPlan";
import { UNKNOWN_LIMITS, effectiveMemory, limitsFrom, machineLabel, memoryAdvice, overridesLine, type MemoryLimits } from "../../lib/memory";
import { useSettings } from "../../lib/settings";
import { useStore } from "../../lib/store";
import { Badge, Button, TextField } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { MemorySlider } from "../../ui/MemorySlider";
import type { RowDef } from "./SettingsPage";

export function useJavaRows(): RowDef[] {
  const t = useT();
  const { settings, update } = useSettings();
  const store = useStore();
  const [limits, setLimits] = useState<MemoryLimits | null>(null);
  const [java, setJava] = useState<JavaOverview | null>(null);

  const reloadJava = useCallback(() => api.javaOverview().then(setJava).catch(() => {}), []);
  useEffect(() => {
    void reloadJava();
    void api
      .systemInfo()
      .then((i) => setLimits(limitsFrom(i)))
      .catch(() => setLimits(UNKNOWN_LIMITS));
  }, [reloadJava]);

  // An allocation saved above what this computer can spare (an older Flint, or another computer) is
  // brought down to its limit, once the real memory size is known.
  useEffect(() => {
    if (limits?.totalMb != null && settings.memoryMb > limits.maxMb) update({ memoryMb: limits.maxMb });
  }, [limits, settings.memoryMb, update]);

  const memory = limits ? effectiveMemory(settings.memoryMb, limits) : null;
  const overrides = overridesLine(store.profiles);
  const totalJava = java?.runtimes.reduce((sum, r) => sum + r.size, 0) ?? 0;

  return [
    {
      section: "java",
      title: t("settings.java.memory.title"),
      desc: limits ? memoryAdvice(limits) : t("settings.java.memory.descLoading"),
      keywords: "settings.java.memory.keywords",
      block: true,
      status: overrides ? <span className="faint">{overrides}</span> : undefined,
      render: () =>
        limits &&
        memory != null && (
          <div className="memory-control">
            <MemorySlider label={t("settings.java.memory.title")} min={1024} max={limits.maxMb} step={512} value={memory} onChange={(v) => update({ memoryMb: v })} />
            {memory !== limits.recommendedMb && (
              <Button size="sm" variant="ghost" icon="undo" onClick={() => update({ memoryMb: limits.recommendedMb })}>
                {t("settings.java.memory.restore", { size: machineLabel(limits.recommendedMb) })}
              </Button>
            )}
            {limits.warnMb != null && memory > limits.warnMb && <p className="settings-warn">{t("settings.java.memory.warn")}</p>}
          </div>
        ),
    },
    {
      section: "java",
      title: t("settings.java.plan.title"),
      desc: t("settings.java.plan.desc"),
      keywords: "settings.java.plan.keywords",
      block: true,
      render: () => <JavaVersions java={java} />,
    },
    {
      section: "java",
      title: t("settings.java.downloaded.title"),
      desc: java
        ? java.runtimes.length
          ? t("settings.java.downloaded.summary", {
              runtimes: java.runtimes.map((r) => `${runtimeLabel(r)}${r.version ? ` (${r.version})` : ""}`).join(", "),
              size: bytes(totalJava),
            })
          : t("settings.java.downloaded.none")
        : t("settings.java.checking"),
      keywords: "settings.java.downloaded.keywords",
      render: () => <Badge tone="success">{t("settings.java.downloaded.automatic")}</Badge>,
    },
    {
      section: "java",
      title: t("settings.java.custom.title"),
      desc: t("settings.java.custom.desc"),
      keywords: "settings.java.custom.keywords",
      block: true,
      render: () => <JavaPathControl java={java} onSaved={() => void reloadJava()} />,
    },
    {
      section: "java",
      title: t("settings.java.args.title"),
      desc: t(settings.automaticOptimization ? "settings.java.args.descAuto" : "settings.java.args.descManual"),
      keywords: "settings.java.args.keywords",
      block: true,
      render: () => <JavaArgsControl />,
    },
  ];
}

/** Each group of versions and the Java it runs on. */
function JavaVersions({ java }: { java: JavaOverview | null }) {
  const t = useT();
  if (!java) return <p className="faint">{t("settings.java.checking")}</p>;
  return (
    <div className="java-plan">
      {groupVersions(java.versions).map((row) => {
        const detail = rowDetail(row, java.inGameMenu);
        return (
          <div key={row.versions[0]} className="java-plan-row">
            <span className="java-plan-versions">{row.versions.join(", ")}</span>
            <span className="java-plan-java">
              <strong>{rowJava(row, java.inGameMenu)}</strong>
              {detail && <span className="faint">{detail}</span>}
            </span>
          </div>
        );
      })}
    </div>
  );
}

function JavaPathControl({ java, onSaved }: { java: JavaOverview | null; onSaved: () => void }) {
  const t = useT();
  const { settings, update } = useSettings();
  const [value, setValue] = useState(settings.javaPath);
  const [result, setResult] = useState<{ ok: boolean; text: string; major?: number | null } | null>(null);
  const [checking, setChecking] = useState(false);

  useEffect(() => setValue(settings.javaPath), [settings.javaPath]);

  const check = async () => {
    const path = value.trim();
    if (!path) {
      update({ javaPath: "" });
      setResult(null);
      onSaved();
      return;
    }
    setChecking(true);
    try {
      const info = await api.validateJava(path);
      update({ javaPath: path });
      setResult({ ok: true, text: info.summary || `Java ${info.version}`, major: info.major });
      onSaved();
    } catch (e) {
      setResult({ ok: false, text: errorText(e) });
    } finally {
      setChecking(false);
    }
  };

  // The custom Java in use: just checked, or as the launcher found it.
  const saved = settings.javaPath && value === settings.javaPath;
  const custom = saved ? java?.custom : undefined;
  const major = result?.ok ? result.major : saved ? custom?.major : null;
  const warnings = saved && java ? customJavaWarnings(major, java.versions, java.inGameMenu) : [];
  return (
    <div className="java-control">
      <div className="row-inline">
        <TextField
          placeholder={t("settings.java.custom.placeholder")}
          value={value}
          onChange={(e) => {
            setValue(e.target.value);
            setResult(null);
          }}
          onKeyDown={(e) => e.key === "Enter" && void check()}
          aria-label={t("settings.java.custom.title")}
          spellCheck={false}
        />
        <Button size="sm" onClick={() => void check()} loading={checking}>
          {t(value.trim() ? "settings.java.checkAndSave" : "settings.java.custom.useFlint")}
        </Button>
      </div>
      {result && (
        <p className={result.ok ? "settings-ok" : "settings-warn"}>
          <Icon name={result.ok ? "check" : "alert"} size={13} /> {result.text}
        </p>
      )}
      {!result && saved && custom?.problem && (
        <p className="settings-warn">
          <Icon name="alert" size={13} /> {custom.problem} {t("settings.java.custom.problemStops")}
        </p>
      )}
      {!result && saved && custom && !custom.problem && (
        <p className="faint settings-hint">
          {custom.version
            ? t("settings.java.custom.usingVersion", { path: settings.javaPath, version: custom.version })
            : t("settings.java.custom.using", { path: settings.javaPath })}
        </p>
      )}
      {warnings.map((w) => (
        <p key={w} className="settings-warn">
          <Icon name="alert" size={13} /> {w}
        </p>
      ))}
    </div>
  );
}

/** Extra Java arguments: reviewed by the launcher (the rules a launch uses, and what would stop Java) before saving. */
function JavaArgsControl() {
  const t = useT();
  const { settings, update } = useSettings();
  const [value, setValue] = useState(settings.jvmArgs);
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null);
  const [notes, setNotes] = useState<string[]>([]);
  const [checking, setChecking] = useState(false);

  useEffect(() => setValue(settings.jvmArgs), [settings.jvmArgs]);

  // What the saved arguments do, shown when the page opens.
  useEffect(() => {
    if (!settings.jvmArgs) return setNotes([]);
    api
      .reviewJavaArgs(settings.jvmArgs)
      .then((r) => setNotes(r.notes))
      .catch((e) => setResult({ ok: false, text: t("settings.java.args.unusable", { error: errorText(e) }) }));
  }, [settings.jvmArgs, t]);

  const save = async (text: string) => {
    setChecking(true);
    try {
      const review = await api.reviewJavaArgs(text);
      update({ jvmArgs: text });
      setNotes(review.notes);
      setResult(
        text
          ? { ok: true, text: t("settings.java.args.saved", { count: review.args.length }) }
          : { ok: true, text: t("settings.java.args.cleared") },
      );
    } catch (e) {
      setResult({ ok: false, text: errorText(e) });
    } finally {
      setChecking(false);
    }
  };

  const changed = value.trim() !== settings.jvmArgs;
  return (
    <div className="java-control">
      <div className="row-inline">
        <TextField
          placeholder="-Dfile.encoding=UTF-8"
          value={value}
          onChange={(e) => {
            setValue(e.target.value);
            setResult(null);
          }}
          onKeyDown={(e) => e.key === "Enter" && changed && void save(value.trim())}
          aria-label={t("settings.java.args.title")}
          spellCheck={false}
        />
        <Button size="sm" onClick={() => void save(value.trim())} loading={checking} disabled={!changed}>
          {t("settings.java.checkAndSave")}
        </Button>
        {settings.jvmArgs && (
          <Button size="sm" variant="ghost" icon="undo" onClick={() => void save("")}>
            {t("settings.java.args.restore")}
          </Button>
        )}
      </div>
      {result && (
        <p className={result.ok ? "settings-ok" : "settings-warn"}>
          <Icon name={result.ok ? "check" : "alert"} size={13} /> {result.text}
        </p>
      )}
      {!changed &&
        notes.map((n) => (
          <p key={n} className={/won't start/.test(n) ? "settings-warn" : "faint settings-hint"}>
            <Icon name={/won't start/.test(n) ? "alert" : "info"} size={13} /> {n}
          </p>
        ))}
    </div>
  );
}
