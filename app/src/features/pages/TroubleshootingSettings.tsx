// Settings → Troubleshooting: this copy of Flint and the computer, the details support asks for, Flint's folders and
// backups, and what went wrong recently (src-tauri/src/troubleshoot.rs).
import { useEffect, useState } from "react";
import { api, errorText, releaseLabel, type EnvironmentInfo, type ErrorEntry } from "../../lib/api";
import { copyText } from "../../lib/clipboard";
import { bytes, relativeTime } from "../../lib/format";
import { formatLocale, useT, type MessageKey } from "../../lib/i18n";
import { useStore } from "../../lib/store";
import { Button, Spinner } from "../../ui/controls";
import { Icon, type IconName } from "../../ui/Icon";
import { useToast } from "../../ui/overlays";
import { useStorageOverview } from "./StorageSettings";

export function EnvironmentCard() {
  const t = useT();
  const store = useStore();
  const [env, setEnv] = useState<EnvironmentInfo | null>(null);
  useEffect(() => {
    void api.environmentInfo().then(setEnv).catch(() => {});
  }, []);
  const { info } = store;
  const processor = env ? [env.cpu, t("settings.troubleshooting.env.threads", { count: env.threads })].filter(Boolean).join(" · ") : "…";
  const rows: { id: string; term: string; value: string; mono?: boolean }[] = [
    { id: "flint", term: "Flint", value: releaseLabel(info) },
    { id: "system", term: t("settings.troubleshooting.env.system"), value: env ? `${env.os} · ${env.arch}` : `${info.platform} · ${info.arch}` },
    { id: "processor", term: t("settings.troubleshooting.env.processor"), value: processor },
    {
      id: "memory",
      term: t("settings.troubleshooting.env.memory"),
      value: env?.memoryMb ? t("settings.troubleshooting.env.memorySize", { gigabytes: Math.round(env.memoryMb / 1024) }) : env ? t("settings.troubleshooting.env.unknown") : "…",
    },
    { id: "data", term: t("settings.troubleshooting.env.dataFolder"), value: info.dataDir, mono: true },
  ];
  return (
    <dl className="env-card">
      {rows.map((row) => (
        <div key={row.id}>
          <dt className="faint">{row.term}</dt>
          <dd className={row.mono ? "mono" : undefined}>{row.value}</dd>
        </div>
      ))}
    </dl>
  );
}

/**
 * Copies the launcher's troubleshooting details (redacted by the launcher), for a bug report. The details themselves,
 * like the diagnostics file below, come from the launcher in English: they're for Flint's developers, not translated.
 */
export function CopyDetailsButton() {
  const t = useT();
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const copy = async () => {
    setBusy(true);
    try {
      await copyText(await api.diagnosticsText());
      toast({ tone: "success", title: t("settings.troubleshooting.copy.done"), body: t("settings.troubleshooting.copy.doneBody") });
    } catch (e) {
      toast({ tone: "danger", title: t("settings.troubleshooting.copy.failed"), body: errorText(e) });
    } finally {
      setBusy(false);
    }
  };
  return (
    <Button size="sm" icon="copy" onClick={() => void copy()} loading={busy}>
      {t("settings.troubleshooting.copy.button")}
    </Button>
  );
}

export function ExportDiagnosticsButton() {
  const t = useT();
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const save = async () => {
    setBusy(true);
    try {
      const saved = await api.exportDiagnostics();
      if (saved) {
        toast({
          tone: "success",
          title: t("settings.troubleshooting.diagnostics.saved"),
          body: t("settings.troubleshooting.diagnostics.savedBody", { size: bytes(saved.size), count: saved.files }),
          action: { label: t("settings.troubleshooting.diagnostics.showFile"), onClick: () => void api.revealDiagnostics(saved.path).catch(() => {}) },
        });
      }
    } catch (e) {
      toast({ tone: "danger", title: t("settings.troubleshooting.diagnostics.failed"), body: errorText(e) });
    } finally {
      setBusy(false);
    }
  };
  return (
    <Button size="sm" icon="download" onClick={() => void save()} loading={busy}>
      {t("settings.troubleshooting.diagnostics.save")}
    </Button>
  );
}

export function FolderButtons() {
  const t = useT();
  const toast = useToast();
  const open = (which: "data" | "game") =>
    void api.revealFolder(which).catch((e) => toast({ tone: "danger", title: t("settings.troubleshooting.folders.openFailed"), body: errorText(e) }));
  return (
    <div className="row-inline">
      <Button size="sm" icon="folder" onClick={() => open("data")}>
        {t("settings.troubleshooting.folders.data")}
      </Button>
      <Button size="sm" icon="folder" onClick={() => open("game")}>
        {t("settings.troubleshooting.folders.game")}
      </Button>
    </div>
  );
}

/** Whether any profile has backups, so the Backups row only shows when there is something to open. */
export function useHasBackups() {
  const { overview } = useStorageOverview();
  return !!overview?.profiles.some((p) => p.id && (p.rollbackFiles > 0 || p.syncBackups > 0));
}

/** Each profile's backups folder: earlier versions of updated content, and Auto Sync's backups. */
export function BackupsList() {
  const t = useT();
  const toast = useToast();
  const { overview } = useStorageOverview();
  const withBackups = (overview?.profiles ?? []).filter((p) => p.id && (p.rollbackFiles > 0 || p.syncBackups > 0));
  return (
    <ul className="backup-list">
      {withBackups.map((p) => {
        const parts = [
          p.rollbackFiles > 0 && t("settings.troubleshooting.backups.rollback", { count: p.rollbackFiles }),
          p.syncBackups > 0 && t("settings.troubleshooting.backups.sync", { count: p.syncBackups }),
        ].filter(Boolean);
        return (
          <li key={p.folder}>
            <span className="backup-text">
              <strong>{p.name}</strong>
              <span className="faint">
                {parts.join(" · ")} · {bytes(p.rollbackBytes + p.syncBackupBytes)}
              </span>
            </span>
            <Button
              size="sm"
              variant="ghost"
              icon="folder"
              onClick={() => void api.revealFolder("backups", p.id!).catch((e) => toast({ tone: "danger", title: t("settings.troubleshooting.folders.openFailed"), body: errorText(e) }))}
            >
              {t("settings.troubleshooting.backups.open")}
            </Button>
          </li>
        );
      })}
    </ul>
  );
}

/** How each kind of failure is introduced (a job's own title already says what it was doing). */
const ERROR_KINDS: Record<ErrorEntry["kind"], { label: MessageKey | null; icon: IconName }> = {
  job: { label: null, icon: "download" },
  profile: { label: "settings.troubleshooting.errors.profile", icon: "alert" },
  game: { label: "settings.troubleshooting.errors.game", icon: "stop" },
};

/** "Just now", "12 min ago", "3 h ago", then the date and time. */
function when(at: number) {
  if (Date.now() / 1000 - at < 6 * 3600) return relativeTime(at);
  return new Date(at * 1000).toLocaleString(formatLocale(), { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
}

export function ErrorHistory() {
  const t = useT();
  const toast = useToast();
  const [entries, setEntries] = useState<ErrorEntry[] | null>(null);
  useEffect(() => {
    void api
      .errorHistory()
      .then(setEntries)
      .catch(() => setEntries([]));
  }, []);
  const clear = async () => {
    try {
      await api.clearErrorHistory();
      setEntries([]);
    } catch (e) {
      toast({ tone: "danger", title: t("settings.troubleshooting.errors.clearFailed"), body: errorText(e) });
    }
  };
  if (!entries) {
    return (
      <p className="muted errors-empty">
        <Spinner size={14} /> {t("common.loading")}
      </p>
    );
  }
  if (entries.length === 0) {
    return <p className="faint errors-empty">{t("settings.troubleshooting.errors.empty")}</p>;
  }
  return (
    <div className="errors">
      <ol className="errors-list">
        {entries.map((e, i) => (
          <li key={`${e.at}-${i}`}>
            <Icon name={ERROR_KINDS[e.kind]?.icon ?? "alert"} size={15} />
            <div className="errors-text">
              <span className="errors-head">
                {/* The title (a profile's name or a job's title) and the message come from the launcher. */}
                <strong>
                  {ERROR_KINDS[e.kind]?.label && `${t(ERROR_KINDS[e.kind].label!)} · `}
                  {e.title}
                  {e.gameVersion && <span className="faint"> ({e.gameVersion})</span>}
                </strong>
                <time className="faint" dateTime={new Date(e.at * 1000).toISOString()}>
                  {when(e.at)}
                </time>
              </span>
              <span className="muted">{e.message}</span>
            </div>
          </li>
        ))}
      </ol>
      <Button size="sm" variant="ghost" icon="trash" onClick={() => void clear()}>
        {t("settings.troubleshooting.errors.clear")}
      </Button>
    </div>
  );
}
