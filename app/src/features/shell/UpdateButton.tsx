import { useEffect, useRef, useState, type CSSProperties } from "react";
import { useT } from "../../lib/i18n";
import { useStoreSelect } from "../../lib/store";
import { canInstallNow, downloadUpdate, installAndRestart, megabytes, progressLabel, releaseName, retryUpdate, useUpdates } from "../../lib/updater";
import { Button, Progress, Spinner } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { FlintMark } from "../../ui/Logo";
import { Popover } from "../../ui/overlays";

/**
 * The top bar's Update action, beside the account chip. Nothing shows while Flint is current (or while a quiet check
 * runs); once a newer version is published it appears as a compact "Update" chip. Its panel is short on purpose (owner,
 * 1.0.4): the version, the release's name and one Update button. The full notes are in News and on flintclient.com.
 * Update downloads with real progress, checks the signature, then restarts Flint into the new version by itself, unless
 * Minecraft is running: then it waits, so a game is never cut short.
 */
export function UpdateButton() {
  const t = useT();
  const update = useUpdates();
  const gameRunning = useStoreSelect((s) => Object.values(s.states).some((st) => st.state === "running" || st.state === "launching"));
  const [open, setOpen] = useState(false);
  // The release's name is known while the update is "available"; it stays shown through download and install.
  const [name, setName] = useState<string | null>(null);
  // Update was pressed here: once the download is ready, Flint restarts into it by itself (when no game runs).
  const [auto, setAuto] = useState(false);
  const anchor = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (update.phase === "available") setName(releaseName(update.notes));
  }, [update]);
  useEffect(() => {
    if (auto && update.phase === "ready" && canInstallNow(update, gameRunning)) void installAndRestart(gameRunning);
  }, [auto, update, gameRunning]);

  const visible =
    update.phase === "available" ||
    update.phase === "downloading" ||
    update.phase === "ready" ||
    update.phase === "installing" ||
    (update.phase === "error" && update.during !== "check");
  if (!visible) return null;

  const label =
    update.phase === "downloading" ? progressLabel(update.received, update.total)
    : update.phase === "ready" ? t("shell.update.restart")
    : update.phase === "installing" ? t("shell.update.installing")
    : update.phase === "error" ? t("shell.update.failed")
    : t("shell.update.chip");
  const version = "version" in update ? update.version : null;
  const share = update.phase === "downloading" && update.total ? Math.min(1, update.received / update.total) : null;
  const start = () => {
    setAuto(true);
    void downloadUpdate();
  };

  return (
    <>
      <button
        ref={anchor}
        type="button"
        className={`update-chip is-${update.phase}`}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label={version ? t("shell.update.chipLabel", { version, label }) : label}
        style={share !== null ? ({ "--update-share": share } as CSSProperties) : undefined}
        onClick={() => setOpen((o) => !o)}
      >
        <span className="update-chip-icon" aria-hidden="true">
          {update.phase === "downloading" || update.phase === "installing" ? (
            <Spinner size={12} />
          ) : (
            <Icon name={update.phase === "ready" ? "refresh" : update.phase === "error" ? "x" : "download"} size={13} />
          )}
        </span>
        <span className="update-chip-label">{label}</span>
      </button>
      <Popover anchor={anchor} open={open} onClose={() => setOpen(false)} placement="bottom-end" label={t("shell.update.panelLabel")} className="update-pop">
        <div className="update-card">
          <span className="update-mark" aria-hidden="true">
            <FlintMark size={22} />
          </span>
          <span className="update-title">
            <strong>Flint {version}</strong>
            {name && <span title={name}>{name}</span>}
          </span>
        </div>
        {update.phase === "available" && (
          <Button variant="primary" icon="download" className="update-go" onClick={start}>
            {t("shell.update.chip")}
          </Button>
        )}
        {update.phase === "downloading" && (
          <div className="update-progress">
            <Progress value={update.total ? update.received / update.total : null} label={t("shell.update.downloading")} />
            <span className="faint">
              {update.total ? t("shell.update.receivedOfTotal", { received: megabytes(update.received), total: megabytes(update.total) }) : megabytes(update.received)}
            </span>
          </div>
        )}
        {update.phase === "ready" && (
          <>
            {gameRunning && <p className="update-note">{t("shell.update.waitForGame")}</p>}
            <Button variant="primary" icon="refresh" className="update-go" disabled={!canInstallNow(update, gameRunning)} onClick={() => void installAndRestart(gameRunning)}>
              {t("shell.update.restart")}
            </Button>
          </>
        )}
        {update.phase === "installing" && <p className="update-note">{t("shell.update.installingNote")}</p>}
        {update.phase === "error" && (
          <>
            <p className="update-note is-danger">{update.message}</p>
            <Button variant="secondary" icon="refresh" className="update-go" onClick={() => retryUpdate()}>
              {t("common.retry")}
            </Button>
          </>
        )}
      </Popover>
    </>
  );
}
