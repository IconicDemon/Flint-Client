import { useMemo, useState, type ReactNode } from "react";
import type { ImportProfileProgress, ImportResultProfile } from "../../lib/api";
import { bytes, number } from "../../lib/format";
import { useT, type Translate } from "../../lib/i18n";
import { useStoreSelect } from "../../lib/store";
import { Button, Callout, Progress, Spinner } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { Modal } from "../../ui/overlays";
import { ProfileIcon } from "../profiles/ProfileIcon";
import { cameOver, outcome, partsByStatus } from "./model";
import type { ImportFlow } from "./useImportFlow";

/** What one row says while the import runs. */
function rowDetail(p: ImportProfileProgress, t: Translate): string {
  switch (p.state) {
    case "waiting":
      return t("import.run.row.waiting");
    case "copying": {
      // `current` is what the launcher backend says it's copying ("Mods", "World: Survival").
      const files = { done: number(p.filesDone), count: p.filesTotal };
      return p.current != null ? t("import.run.row.copyingWhat", { ...files, current: p.current }) : t("import.run.row.copying", files);
    }
    case "verifying":
      return t("import.run.row.verifying");
    case "done":
      return t("import.run.row.done", { count: p.filesTotal });
    case "failed":
      return p.error ?? t("import.run.row.failed");
    case "skipped":
      return p.error ?? t("import.run.row.skipped");
    case "cancelled":
      return t("import.run.notImported");
  }
}

function StateIcon({ state }: { state: ImportProfileProgress["state"] }) {
  if (state === "copying" || state === "verifying") return <Spinner size={14} />;
  if (state === "done") return <Icon name="check" size={13} />;
  if (state === "failed") return <Icon name="x" size={13} />;
  if (state === "cancelled" || state === "skipped") return <Icon name="minimize" size={13} />;
  return <span className="imp-wait-dot" />;
}

/** The import as it runs: one bar for everything, then a row per profile with its own bytes and files. */
export function ProgressView({ flow }: { flow: ImportFlow }) {
  const t = useT();
  const rows = flow.progress?.profiles ?? [];
  const total = rows.reduce((n, p) => n + p.bytesTotal, 0);
  const done = rows.reduce((n, p) => n + p.bytesDone, 0);
  const finished = rows.filter((p) => p.state === "done").length;
  const now = rows.find((p) => p.state === "copying" || p.state === "verifying");
  const next = rows.find((p) => p.state === "waiting");
  const job = flow.job;
  const overall = total ? done / total : job?.bytesTotal ? job.bytesDone / job.bytesTotal : null;
  const headline = flow.cancelling
    ? t("import.run.head.stopping")
    : now
      ? t(now.state === "verifying" ? "import.run.head.checking" : "import.run.head.copying", { name: now.name })
      : next
        ? t("import.run.head.starting", { name: next.name })
        : rows.length
          ? t("import.run.head.finishing")
          : t("import.run.head.startingAll");
  return (
    <div className="imp-run">
      <div className="imp-run-head">
        <div className="imp-run-line">
          <strong aria-live="polite">{headline}</strong>
          <span className="faint">
            {rows.length ? t("import.run.profilesDone", { done: number(finished), count: rows.length }) : ""}
            {total ? ` · ${t("import.run.bytesOf", { done: bytes(done), total: bytes(total) })}` : ""}
          </span>
        </div>
        <Progress value={rows.length ? overall : null} label={t("import.run.progressLabel")} />
      </div>
      {flow.startError && (
        <Callout tone="danger" live>
          {flow.startError}
        </Callout>
      )}
      <ul className="imp-run-list">
        {rows.map((p) => (
          <li key={p.key} className={`imp-run-row is-${p.state}`}>
            <span className="imp-run-icon">
              <StateIcon state={p.state} />
            </span>
            <span className="imp-run-text">
              <strong>{p.name}</strong>
              <span className={p.state === "failed" ? "imp-run-error" : "faint"}>{rowDetail(p, t)}</span>
            </span>
            {(p.state === "copying" || p.state === "verifying" || p.state === "done") && p.bytesTotal > 0 && (
              <span className="imp-run-bytes">
                <span className="faint">{p.state === "done" ? bytes(p.bytesTotal) : t("import.run.bytesOf", { done: bytes(p.bytesDone), total: bytes(p.bytesTotal) })}</span>
                <Progress value={p.state === "done" ? 1 : p.bytesDone / p.bytesTotal} label={t("import.run.rowProgressLabel", { name: p.name })} />
              </span>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}

/** "Cancel import", asked first; profiles already copied stay. Then "Stopping…" until the job says it stopped. */
export function CancelImport({ flow, size }: { flow: ImportFlow; size?: "md" | "lg" }) {
  const t = useT();
  const [asking, setAsking] = useState(false);
  return (
    <>
      <Button variant="ghost" size={size} onClick={() => setAsking(true)} loading={flow.cancelling} disabled={!flow.jobId}>
        {flow.cancelling ? t("import.run.stopping") : t("import.run.cancel")}
      </Button>
      <Modal
        open={asking}
        onClose={() => setAsking(false)}
        title={t("import.run.confirmStop.title")}
        width={440}
        footer={
          <>
            <Button variant="ghost" onClick={() => setAsking(false)} data-autofocus>
              {t("import.run.confirmStop.keep")}
            </Button>
            <Button
              variant="danger"
              onClick={() => {
                setAsking(false);
                void flow.cancel();
              }}
            >
              {t("import.run.confirmStop.stop")}
            </Button>
          </>
        }
      >
        <p>{t("import.run.confirmStop.body")}</p>
      </Modal>
    </>
  );
}

/** The finished screen's mark: a ring and a check that draws itself once (still, with reduced motion). */
function DoneMark({ tone }: { tone: "success" | "partial" | "failed" | "stopped" }) {
  const path = tone === "failed" ? "M17 17l14 14M31 17 17 31" : tone === "stopped" ? "M16 24h16" : "m15 24.5 6 6L33 18";
  return (
    <span className={`imp-done-mark is-${tone}`} aria-hidden>
      <svg width="64" height="64" viewBox="0 0 48 48">
        <circle className="imp-done-ring" cx="24" cy="24" r="21" />
        <path className="imp-done-tick" d={path} />
      </svg>
    </span>
  );
}

/**
 * The finished screen: an honest headline, then each profile with what came over, its warnings and the files that
 * stayed behind ("See why"). `actions` are the host's buttons (Continue, Done).
 */
export function DoneView({ flow, actions, heading = "h1" }: { flow: ImportFlow; actions?: ReactNode; heading?: "h1" | "h2" }) {
  const t = useT();
  const finished = flow.finished!;
  const profiles = useStoreSelect((s) => s.profiles);
  const [why, setWhy] = useState<ImportResultProfile | null>(null);
  const plans = useMemo(() => new Map((flow.preview?.profiles ?? []).map((p) => [p.key, p])), [flow.preview]);
  const sources = useMemo(() => new Map((flow.listing?.profiles ?? []).map((p) => [p.key, p])), [flow.listing]);
  // Worded again in the current language (the flow wrote it when the import ended); only a stopped import has that tone.
  const told = useMemo(() => outcome(finished.result, finished.outcome.tone === "stopped"), [finished, t]);
  const Heading = heading;
  return (
    <div className={`imp-done is-${told.tone}`}>
      <div className="imp-done-head">
        <DoneMark tone={told.tone} />
        <Heading>{told.title}</Heading>
        <p className="imp-done-sub">{told.body}</p>
      </div>
      {finished.message && (
        <Callout tone="danger" title={t("import.done.error")}>
          {finished.message}
        </Callout>
      )}
      <ul className="imp-done-list">
        {finished.result.profiles.map((p) => {
          const profile = p.profileId ? profiles.find((x) => x.id === p.profileId) : undefined;
          const plan = plans.get(p.key);
          const left = p.skipped.length;
          return (
            <li key={p.key} className={`imp-done-row is-${p.state}`}>
              <span className="imp-done-icon">
                {profile ? <ProfileIcon profile={profile} size={36} /> : <Icon name={p.state === "failed" ? "x" : "minimize"} size={15} />}
                {p.state === "done" && (
                  <span className="imp-done-badge">
                    <Icon name="check" size={10} />
                  </span>
                )}
              </span>
              <span className="imp-done-text">
                <strong>
                  {p.name}
                  {profile && profile.name !== p.name && <span className="faint"> {t("import.done.renamedAs", { name: profile.name })}</span>}
                </strong>
                <span className={p.state === "failed" ? "imp-run-error" : "faint"}>
                  {p.state === "done"
                    ? plan
                      ? t("import.done.importedWith", { what: cameOver(plan, sources.get(p.key)?.mods ?? null) })
                      : t("import.done.imported")
                    : p.state === "failed"
                      ? (p.error ?? t("import.done.failed"))
                      : p.state === "skipped"
                        ? (p.error ?? t("import.done.skipped"))
                        : t("import.run.notImported")}
                </span>
                {/* What stayed in the other launcher by design (Forge mods on a settings-only profile, say). */}
                {p.state === "done" &&
                  plan &&
                  partsByStatus(plan).unsupported.map((part) =>
                    part.note ? (
                      <span key={part.id} className="imp-done-left">
                        <Icon name="minimize" size={12} />
                        {part.note}
                      </span>
                    ) : null,
                  )}
                {p.warnings.map((w) => (
                  <span key={w} className="imp-done-warn">
                    <Icon name="alert" size={12} />
                    {w}
                  </span>
                ))}
                {left > 0 && (
                  <span className="imp-done-warn">
                    <Icon name="alert" size={12} />
                    {t("import.done.filesLeft", { count: left })}
                    <button type="button" className="imp-why" onClick={() => setWhy(p)}>
                      {t("import.done.seeWhy")}
                    </button>
                  </span>
                )}
              </span>
            </li>
          );
        })}
      </ul>
      {actions && <div className="imp-done-actions">{actions}</div>}
      <Modal
        open={!!why}
        onClose={() => setWhy(null)}
        title={why ? t("import.done.why.titleFrom", { name: why.name }) : t("import.done.why.title")}
        width={560}
        footer={
          <Button variant="primary" onClick={() => setWhy(null)}>
            {t("common.close")}
          </Button>
        }
      >
        {why && (
          <>
            <p className="imp-why-intro">{t("import.done.why.intro")}</p>
            <ul className="imp-why-list">
              {why.skipped.map((s) => (
                <li key={s.path}>
                  <span className="mono">{s.path}</span>
                  <span className="muted">{s.reason}</span>
                </li>
              ))}
            </ul>
          </>
        )}
      </Modal>
    </div>
  );
}
