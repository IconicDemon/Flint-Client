import { useEffect, useRef, useState } from "react";
import { api, errorText, type ContentUpdate, type Job, type Profile, type UnmanagedFile, type UpdateOutcome, type UpdateResult, type UpdateVersion } from "../../lib/api";
import {
  addedDependencies,
  addsLabel,
  choose,
  chosen,
  headerState,
  initialSelection,
  requests,
  resultSummary,
  showInPlace,
  toggle,
  toggleAll,
  typeLabel,
  updateKey,
  updateLabel,
  versionsOf,
  type Choices,
} from "../../lib/contentUpdates";
import { shortDate } from "../../lib/format";
import { useT } from "../../lib/i18n";
import { Markdown } from "../../lib/markdown";
import { useStoreSelect } from "../../lib/store";
import { Badge, Button, Checkbox, EmptyState, Progress, Spinner } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { Dropdown, Modal, useToast } from "../../ui/overlays";
import { KIND_LABEL, ProjectIcon } from "../library/InstallDialog";

type Phase =
  | { step: "review" }
  | { step: "working"; job: string; batch: ContentUpdate[] }
  | { step: "done"; job: string; batch: ContentUpdate[]; result: UpdateResult | null; error: string | null };

type Undo = { state: "busy" | "done" | "failed"; message?: string };

/**
 * Update projects: every update for the profile, ticked unless it's pinned or can't go in, each with its version to
 * pick and its changelog to read. One job updates the ticked ones; the dialog follows it and then shows what
 * happened, with an undo for every item that changed.
 */
export function UpdateDialog({
  open,
  profile,
  updates,
  unmanaged,
  only,
  running,
  onClose,
  onChanged,
}: {
  open: boolean;
  profile: Profile;
  /** The profile's updates, as the content list has them now (`currentUpdates`). */
  updates: ContentUpdate[];
  unmanaged: UnmanagedFile[];
  /** Opened for some rows only (picked items, one row's menu): only those are ticked. */
  only: string[] | null;
  running: boolean;
  onClose: () => void;
  /** Something was rolled back: the list and its updates are out of date. */
  onChanged: () => void;
}) {
  const t = useT();
  const toast = useToast();
  const [phase, setPhase] = useState<Phase>({ step: "review" });
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [choices, setChoices] = useState<Choices>({});
  const [viewing, setViewing] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const [undo, setUndo] = useState<Record<string, Undo>>({});
  const [wasOpen, setWasOpen] = useState(false);
  const body = useRef<HTMLDivElement>(null);
  const returnTo = useRef<string | null>(null);

  // A fresh start each time it opens. A re-check while it's open keeps what the player ticked.
  if (open !== wasOpen) {
    setWasOpen(open);
    if (open) {
      setPhase({ step: "review" });
      setSelected(initialSelection(updates, only));
      setChoices({});
      setViewing(null);
      setUndo({});
    }
  }

  const jobId = phase.step === "working" ? phase.job : null;
  const job = useStoreSelect((s) => (jobId ? (s.jobs.find((j) => j.id === jobId) ?? null) : null));

  useEffect(() => {
    if (phase.step !== "working" || !job) return;
    if (job.state === "done") setPhase({ step: "done", job: phase.job, batch: phase.batch, result: job.result as UpdateResult, error: null });
    else if (job.state === "failed" || job.state === "cancelled") setPhase({ step: "done", job: phase.job, batch: phase.batch, result: null, error: job.message ?? null });
  }, [job, phase]);

  // While the dialog follows its job, the job's outcome isn't also a toast; once it's closed, it is again.
  const followed = open && phase.step !== "review" ? phase.job : null;
  useEffect(() => {
    if (!followed) return;
    showInPlace(followed, true);
    return () => showInPlace(followed, false);
  }, [followed]);

  // Keyboard focus follows the view: the changelog's Back button, then the row's View button again.
  useEffect(() => {
    if (viewing) body.current?.querySelector<HTMLElement>(".upd-back")?.focus();
    else if (returnTo.current) body.current?.querySelector<HTMLElement>(`[data-view="${CSS.escape(returnTo.current)}"]`)?.focus();
  }, [viewing]);

  const start = async () => {
    const list = requests(updates, selected, choices);
    if (!list.length) return;
    setStarting(true);
    try {
      const id = await api.updateContent(profile.id, list);
      setViewing(null);
      // Each project as it goes in: the version picked for it.
      const batch = updates.filter((u) => selected.has(updateKey(u))).map((u) => ({ ...u, ...chosen(u, choices) }));
      showInPlace(id, true);
      setPhase({ step: "working", job: id, batch });
    } catch (e) {
      toast({ tone: "danger", title: t("updates.dialog.startFailed"), body: errorText(e) });
    } finally {
      setStarting(false);
    }
  };

  const rollBack = async (o: UpdateOutcome) => {
    const key = updateKey({ ...o, fileName: o.newFileName ?? o.fileName });
    setUndo((u) => ({ ...u, [key]: { state: "busy" } }));
    try {
      await api.rollbackContent(profile.id, o.kind, o.projectId!, o.world);
      setUndo((u) => ({ ...u, [key]: { state: "done" } }));
      onChanged();
    } catch (e) {
      setUndo((u) => ({ ...u, [key]: { state: "failed", message: errorText(e) } }));
    }
  };

  const count = requests(updates, selected, choices).length;
  const deps = addedDependencies(updates, selected, choices);
  const viewed = viewing ? updates.find((u) => updateKey(u) === viewing) : undefined;
  const pick = (u: ContentUpdate, versionId: string) => {
    const next = choose(u, versionId, selected, choices);
    setChoices(next.choices);
    setSelected(next.selected);
  };

  const footer =
    phase.step === "review" ? (
      <>
        <div className="upd-foot-info">
          <strong>
            {t("updates.dialog.selected", { count })}
            {deps.length > 0 ? ` · ${addsLabel(deps, "inline")}` : ""}
          </strong>
          <span className="faint">{t("updates.dialog.undoNote")}</span>
        </div>
        <Button variant="ghost" onClick={onClose}>
          {t("common.cancel")}
        </Button>
        <Button variant="primary" icon="arrowUp" onClick={() => void start()} loading={starting} disabled={count === 0 || running}>
          {updateLabel(count)}
        </Button>
      </>
    ) : phase.step === "working" ? (
      <>
        <span className="upd-foot-info faint">{t("updates.dialog.closeNote")}</span>
        {job && job.stage !== "Installing" && (
          <Button variant="ghost" onClick={() => void api.cancelJob(phase.job).catch((e) => toast({ tone: "danger", title: t("updates.dialog.cancelFailed"), body: errorText(e) }))}>
            {t("updates.dialog.cancel")}
          </Button>
        )}
        <Button onClick={onClose}>{t("common.close")}</Button>
      </>
    ) : (
      <Button variant="primary" onClick={onClose}>
        {t("common.done")}
      </Button>
    );

  return (
    <Modal open={open} onClose={onClose} title={t("updates.dialog.title")} width={940} footer={footer}>
      <div ref={body} className="upd">
        {phase.step === "review" &&
          (viewed ? (
            <Changelog
              update={viewed}
              version={chosen(viewed, choices)}
              onPick={(versionId) => pick(viewed, versionId)}
              onBack={() => {
                returnTo.current = viewing;
                setViewing(null);
              }}
            />
          ) : updates.length === 0 ? (
            <EmptyState compact icon="check" title={t("updates.dialog.emptyTitle")} body={t("updates.dialog.emptyBody")} />
          ) : (
            <>
              <p className="upd-context">{t(profile.loader === "fabric" ? "updates.dialog.offeredFabric" : "updates.dialog.offered", { version: profile.gameVersion })}</p>
              {running && (
                <p className="install-warn">
                  <Icon name="lock" size={15} />
                  {t("updates.dialog.running")}
                </p>
              )}
              <UpdateTable
                updates={updates}
                selected={selected}
                choices={choices}
                onSelect={setSelected}
                onPick={pick}
                onView={(key) => setViewing(key)}
              />
              <Unmanaged files={unmanaged} />
            </>
          ))}
        {phase.step === "working" && <Working job={job} batch={phase.batch} />}
        {phase.step === "done" && <Result phase={phase} undo={undo} running={running} onUndo={(o) => void rollBack(o)} />}
      </div>
    </Modal>
  );
}

function UpdateTable({
  updates,
  selected,
  choices,
  onSelect,
  onPick,
  onView,
}: {
  updates: ContentUpdate[];
  selected: Set<string>;
  choices: Choices;
  onSelect: (selected: Set<string>) => void;
  onPick: (update: ContentUpdate, versionId: string) => void;
  onView: (key: string) => void;
}) {
  const t = useT();
  const state = headerState(updates, selected, choices);
  return (
    <div className="upd-table" role="table" aria-label={t("updates.table.label")}>
      <div className="upd-row upd-head" role="row">
        <span role="columnheader">
          <Checkbox label={t("updates.table.selectAll")} checked={state === "all"} indeterminate={state === "some"} onChange={(v) => onSelect(toggleAll(updates, selected, choices, v))} />
        </span>
        <span role="columnheader">{t("updates.table.project")}</span>
        <span role="columnheader">{t("updates.table.installed")}</span>
        <span role="columnheader" aria-hidden />
        <span role="columnheader">{t("updates.table.updateTo")}</span>
        <span role="columnheader" className="sr-only">
          {t("updates.table.changelog")}
        </span>
      </div>
      {updates.map((u) => {
        const key = updateKey(u);
        const version = chosen(u, choices);
        const versions = versionsOf(u);
        return (
          <div key={key} className={`upd-row ${version.blocked ? "is-blocked" : ""}`} role="row">
            <span role="cell">
              <Checkbox label={t("updates.table.updateItem", { name: u.title })} checked={selected.has(key)} disabled={!!version.blocked} onChange={(v) => onSelect(toggle(u, v, selected, choices))} />
            </span>
            <span role="cell" className="upd-project">
              <ProjectIcon src={u.iconUrl} title={u.title} kind={u.kind} size={32} />
              <span className="upd-project-text">
                <strong title={u.title}>{u.title}</strong>
                <span className="upd-notes">
                  {u.kind !== "mod" && (
                    <span>
                      {KIND_LABEL[u.kind]}
                      {u.world ? ` · ${u.world}` : ""}
                    </span>
                  )}
                  {u.pinned && (
                    <span className="upd-note">
                      <Icon name="pin" size={12} />
                      {t("updates.table.pinned")}
                    </span>
                  )}
                  {version.versionType !== "release" && <Badge tone="warning">{typeLabel(version.versionType)}</Badge>}
                  {version.adds.length > 0 && (
                    <span className="upd-note is-adds" title={t("updates.table.neededBy", { names: version.adds.map((d) => d.title).join(", ") })}>
                      <Icon name="plus" size={12} />
                      {addsLabel(version.adds)}
                    </span>
                  )}
                  {version.blocked && (
                    <span className="upd-note is-blocked" title={version.blocked}>
                      {t("updates.table.blocked", { reason: version.blocked })}
                    </span>
                  )}
                </span>
              </span>
            </span>
            <span role="cell" className="upd-from" title={u.currentVersion ?? undefined}>
              {u.currentVersion || t("updates.unknownVersion")}
            </span>
            <span role="cell" className="upd-arrow" aria-hidden>
              <Icon name="arrowRight" size={14} />
            </span>
            <span role="cell" className="upd-to">
              {versions.length > 1 ? <VersionPicker update={u} version={version} onPick={(id) => onPick(u, id)} /> : <span title={version.versionNumber}>{version.versionNumber}</span>}
            </span>
            <span role="cell" className="upd-view">
              <Button size="sm" variant="ghost" data-view={key} onClick={() => onView(key)} aria-label={t("updates.table.viewLabel", { name: u.title, version: version.versionNumber })}>
                {t("updates.table.view")}
              </Button>
            </span>
          </div>
        );
      })}
    </div>
  );
}

function VersionPicker({ update, version, onPick }: { update: ContentUpdate; version: UpdateVersion; onPick: (versionId: string) => void }) {
  const t = useT();
  return (
    <Dropdown
      className="upd-pick"
      label={t("updates.picker.label", { name: update.title })}
      value={version.versionId}
      onChange={onPick}
      options={versionsOf(update).map((v) => ({
        value: v.versionId,
        label: v.versionNumber,
        hint: [v.blocked ? t("updates.picker.blocked") : typeLabel(v.versionType), shortDate(v.datePublished)].filter(Boolean).join(" · "),
      }))}
    />
  );
}

/** One update's changelog, inside the dialog. Changing the version here changes what the update installs. */
function Changelog({ update, version, onPick, onBack }: { update: ContentUpdate; version: UpdateVersion; onPick: (versionId: string) => void; onBack: () => void }) {
  const t = useT();
  return (
    <section className="upd-log" aria-label={t("updates.changelog.label", { name: update.title })}>
      <button type="button" className="upd-back" onClick={onBack}>
        <Icon name="chevronLeft" size={15} />
        {t("updates.changelog.back")}
      </button>
      <header className="upd-log-head">
        <ProjectIcon src={update.iconUrl} title={update.title} kind={update.kind} size={40} />
        <span className="upd-log-title">
          <strong>{update.title}</strong>
          <span className="faint">
            {update.currentVersion || t("updates.unknownVersion")} → {version.versionNumber} · {typeLabel(version.versionType)}
            {version.datePublished ? ` · ${shortDate(version.datePublished)}` : ""}
          </span>
        </span>
        {versionsOf(update).length > 1 && <VersionPicker update={update} version={version} onPick={onPick} />}
      </header>
      {version.blocked && (
        <p className="install-warn is-block">
          <Icon name="alert" size={15} />
          {version.blocked}
        </p>
      )}
      {version.adds.length > 0 && (
        <p className="install-note">
          <Icon name="plus" size={15} />
          {addsLabel(version.adds, "needed")}
        </p>
      )}
      <div className="upd-log-body">{version.changelog.trim() ? <Markdown source={version.changelog} className="is-small" /> : <p className="faint">{t("updates.changelog.none")}</p>}</div>
      <button type="button" className="launch-link upd-log-link" onClick={() => void api.openExternal(`https://modrinth.com/project/${update.projectId}/version/${version.versionId}`)}>
        {t("updates.changelog.onModrinth")}
      </button>
    </section>
  );
}

function Unmanaged({ files }: { files: UnmanagedFile[] }) {
  const t = useT();
  if (files.length === 0) return null;
  return (
    <details className="upd-unmanaged">
      <summary>
        <Icon name="info" size={14} />
        {t("updates.unmanaged.summary", { count: files.length })}
        <Icon name="chevronDown" size={15} className="upd-unmanaged-chev" />
      </summary>
      <p className="faint">{t(files.length === 1 ? "updates.unmanaged.explainOne" : "updates.unmanaged.explainMany")}</p>
      <ul>
        {files.map((f) => (
          <li key={updateKey(f)}>
            <span>{f.title}</span>
            <span className="faint">
              {KIND_LABEL[f.kind]}
              {f.world ? ` · ${f.world}` : ""} · {f.fileName}
            </span>
          </li>
        ))}
      </ul>
    </details>
  );
}

function Working({ job, batch }: { job: Job | null; batch: ContentUpdate[] }) {
  const t = useT();
  const progress = job?.state === "running" && job.itemsTotal ? Math.min(1, job.itemsDone / job.itemsTotal) : null;
  return (
    <div className="upd-working">
      <div className="upd-working-head" role="status">
        <Spinner size={16} />
        <strong>{job?.stage && job.stage !== "Waiting" ? job.stage : t("updates.working.starting")}</strong>
        {job?.file && <span className="faint">{job.file}</span>}
      </div>
      <Progress value={progress} label={t("updates.working.progress")} />
      <p className="faint">{t("updates.working.note")}</p>
      <ul className="upd-batch" aria-label={t("updates.working.listLabel")}>
        {batch.map((u) => (
          <li key={updateKey(u)}>
            <ProjectIcon src={u.iconUrl} title={u.title} kind={u.kind} size={24} />
            <span className="upd-batch-title">{u.title}</span>
            <span className="faint">
              {u.currentVersion || t("updates.unknownVersion")} → {u.versionNumber}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function Result({ phase, undo, running, onUndo }: { phase: Extract<Phase, { step: "done" }>; undo: Record<string, Undo>; running: boolean; onUndo: (o: UpdateOutcome) => void }) {
  const t = useT();
  const icons = new Map(phase.batch.map((u) => [updateKey(u), u.iconUrl]));
  if (!phase.result) {
    return (
      <p className="install-warn is-block" role="alert">
        <Icon name="alert" size={15} />
        {phase.error ?? t("updates.dialog.stopped")}
      </p>
    );
  }
  const { updated, added, failed, skipped } = phase.result;
  const summary = resultSummary(phase.result);
  return (
    <div className="upd-result">
      <p className={`upd-result-head is-${summary.tone}`}>
        <Icon name={summary.tone === "danger" ? "alert" : "check"} size={16} />
        <span>
          <strong>{summary.title}</strong>
          {failed.length === 0 && summary.body && <span className="faint">{summary.body}</span>}
        </span>
      </p>
      {updated.length > 0 && (
        <section className="upd-group">
          <span className="eyebrow">{t("updates.result.updated")}</span>
          <ul>
            {updated.map((o) => {
              const key = updateKey({ ...o, fileName: o.newFileName ?? o.fileName });
              const state = undo[key];
              return (
                <li key={key}>
                  <ProjectIcon src={icons.get(updateKey(o))} title={o.title} kind={o.kind} size={28} />
                  <span className="upd-group-text">
                    <strong>{o.title}</strong>
                    <span className="faint">
                      {o.from || t("updates.unknownVersion")} → {o.to}
                    </span>
                    {state?.state === "failed" && <span className="upd-group-error">{state.message}</span>}
                  </span>
                  {state?.state === "done" ? (
                    <span className="faint">{t("updates.result.backOn", { version: o.from ?? "" })}</span>
                  ) : (
                    <Button size="sm" variant="ghost" icon="undo" loading={state?.state === "busy"} disabled={running || !o.projectId} onClick={() => onUndo(o)}>
                      {t("updates.result.undo")}
                    </Button>
                  )}
                </li>
              );
            })}
          </ul>
        </section>
      )}
      {added.length > 0 && (
        <section className="upd-group">
          <span className="eyebrow">{t("updates.result.added")}</span>
          <ul>
            {added.map((a) => (
              <li key={a.projectId}>
                <ProjectIcon title={a.title} kind={a.kind} size={28} />
                <span className="upd-group-text">
                  <strong>{a.title}</strong>
                  <span className="faint">{a.versionNumber}</span>
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}
      {failed.length > 0 && (
        <section className="upd-group">
          <span className="eyebrow">{t("updates.result.failed")}</span>
          <ul>
            {failed.map((o) => (
              <li key={updateKey(o)}>
                <ProjectIcon src={icons.get(updateKey(o))} title={o.title} kind={o.kind} size={28} />
                <span className="upd-group-text">
                  <strong>{o.title}</strong>
                  <span className="upd-group-error">{o.reason}</span>
                </span>
                <span className="faint">{t("updates.result.stillOn")}</span>
              </li>
            ))}
          </ul>
        </section>
      )}
      {skipped.length > 0 && (
        <section className="upd-group">
          <span className="eyebrow">{t("updates.result.skipped")}</span>
          <ul>
            {skipped.map((o) => (
              <li key={updateKey(o)}>
                <ProjectIcon src={icons.get(updateKey(o))} title={o.title} kind={o.kind} size={28} />
                <span className="upd-group-text">
                  <strong>{o.title}</strong>
                  <span className="faint">{o.reason}</span>
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}
      <p className="faint upd-result-note">{t("updates.result.applyNote")}</p>
    </div>
  );
}
