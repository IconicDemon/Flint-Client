import { useRef, useState } from "react";
import { useStoreSelect } from "../../lib/store";
import { canInstallNow, downloadUpdate, installAndRestart, progressLabel, retryUpdate, useUpdates } from "../../lib/updater";
import { Button, Progress, Spinner } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { Popover } from "../../ui/overlays";

/**
 * The top bar's Update action, beside the account chip. Nothing shows while Flint is current (or while a
 * quiet check runs); once a newer version is published it appears as a compact "Update" chip. Its panel
 * shows the version and release notes, downloads with real progress, and restarts Flint into the new
 * version, which it holds back while Minecraft is running so a game is never cut short.
 */
export function UpdateButton() {
  const update = useUpdates();
  const gameRunning = useStoreSelect((s) => Object.values(s.states).some((st) => st.state === "running" || st.state === "launching"));
  const [open, setOpen] = useState(false);
  const anchor = useRef<HTMLButtonElement>(null);

  const visible =
    update.phase === "available" ||
    update.phase === "downloading" ||
    update.phase === "ready" ||
    update.phase === "installing" ||
    (update.phase === "error" && update.during !== "check");
  if (!visible) return null;

  const label =
    update.phase === "downloading" ? progressLabel(update.received, update.total)
    : update.phase === "ready" ? "Restart to update"
    : update.phase === "installing" ? "Installing…"
    : update.phase === "error" ? "Update failed"
    : "Update";
  const version = "version" in update ? update.version : null;

  return (
    <>
      <button
        ref={anchor}
        type="button"
        className={`update-chip is-${update.phase}`}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label={version ? `Flint ${version}: ${label}` : label}
        onClick={() => setOpen((o) => !o)}
      >
        {update.phase === "downloading" || update.phase === "installing" ? (
          <Spinner size={14} />
        ) : (
          <Icon name={update.phase === "ready" ? "refresh" : update.phase === "error" ? "x" : "download"} size={15} />
        )}
        <span>{label}</span>
      </button>
      <Popover anchor={anchor} open={open} onClose={() => setOpen(false)} placement="bottom-end" label="Flint update" className="update-pop">
        <header className="update-head">
          <strong>Flint {version}</strong>
          <span className="faint">Signed by Flint · checked before installing</span>
        </header>
        {update.phase === "available" && (
          <>
            {update.notes && <p className="update-notes">{update.notes}</p>}
            <Button variant="primary" icon="download" onClick={() => void downloadUpdate()}>
              Download update
            </Button>
          </>
        )}
        {update.phase === "downloading" && (
          <div className="update-progress">
            <Progress value={update.total ? update.received / update.total : null} label="Downloading the update" />
            <span className="faint">
              {(update.received / 1_000_000).toFixed(1)} MB{update.total ? ` of ${(update.total / 1_000_000).toFixed(1)} MB` : ""}
            </span>
          </div>
        )}
        {update.phase === "ready" && (
          <>
            {gameRunning ? (
              <p className="update-notes">Minecraft is running. Close it first: Flint restarts to finish updating, and your game won't be interrupted.</p>
            ) : (
              <p className="update-notes">Downloaded and verified. Flint restarts to finish; your profiles, worlds and settings stay as they are.</p>
            )}
            <Button variant="primary" icon="refresh" disabled={!canInstallNow(update, gameRunning)} onClick={() => void installAndRestart(gameRunning)}>
              Restart to update
            </Button>
          </>
        )}
        {update.phase === "installing" && <p className="update-notes">Installing… Flint will restart.</p>}
        {update.phase === "error" && (
          <>
            <p className="update-notes is-danger">{update.message}</p>
            <Button variant="secondary" icon="refresh" onClick={() => retryUpdate()}>
              Retry
            </Button>
          </>
        )}
      </Popover>
    </>
  );
}
