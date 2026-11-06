import { useEffect, useRef, useState } from "react";
import { api, errorText, type Job, type PackPreview, type PackSection, type PackSectionInfo, type Profile } from "../../lib/api";
import { PACK_SECTIONS } from "../../lib/flintpack";
import { bytes } from "../../lib/format";
import { useT, type MessageKey, type Translate } from "../../lib/i18n";
import { useStoreSelect } from "../../lib/store";
import { loaderLabel, versionMeta } from "../../lib/versions";
import { Button, Callout, Checkbox, Progress, Spinner, TextField } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { Modal, useToast } from "../../ui/overlays";

/** Each section's name and what it holds; `PACK_SECTIONS` gives the order. */
const SECTION_TEXT: Record<PackSection, { name: MessageKey; note: MessageKey }> = {
  mods: { name: "profiles.pack.sections.mods.name", note: "profiles.pack.sections.mods.note" },
  resourcepacks: { name: "profiles.pack.sections.resourcepacks.name", note: "profiles.pack.sections.resourcepacks.note" },
  shaderpacks: { name: "profiles.pack.sections.shaderpacks.name", note: "profiles.pack.sections.shaderpacks.note" },
  config: { name: "profiles.pack.sections.config.name", note: "profiles.pack.sections.config.note" },
  options: { name: "profiles.pack.sections.options.name", note: "profiles.pack.sections.options.note" },
  flint: { name: "profiles.pack.sections.flint.name", note: "profiles.pack.sections.flint.note" },
  worlds: { name: "profiles.pack.sections.worlds.name", note: "profiles.pack.sections.worlds.note" },
};

/** "12 files · 4.2 MB", "2 worlds · 310 MB". */
function sectionSize(info: PackSectionInfo, t: Translate): string {
  const count = info.id === "worlds" && info.worlds ? t("profiles.pack.worlds", { count: info.worlds }) : t("profiles.pack.files", { count: info.files });
  return `${count} · ${bytes(info.size)}`;
}

function jobFraction(job: Job): number | null {
  if (job.bytesTotal) return Math.min(1, job.bytesDone / job.bytesTotal);
  if (job.itemsTotal) return Math.min(1, job.itemsDone / job.itemsTotal);
  return null;
}

/** A running export or import, shown in its dialog: the stage, the bytes, and a bar. */
function JobProgress({ job }: { job: Job | null }) {
  const t = useT();
  if (!job) return <Progress value={null} label={t("profiles.pack.starting")} />;
  return (
    <div className="pack-progress" aria-live="polite">
      <div className="pack-progress-line">
        <strong>{job.stage}</strong>
        {job.bytesTotal ? (
          <span className="faint">{t("profiles.pack.progress", { done: bytes(job.bytesDone), total: bytes(job.bytesTotal) })}</span>
        ) : job.itemsTotal ? (
          <span className="faint">{t("profiles.pack.progress", { done: Math.min(job.itemsDone + 1, job.itemsTotal), total: job.itemsTotal })}</span>
        ) : null}
      </div>
      <Progress value={jobFraction(job)} label={job.title} />
    </div>
  );
}

const useJob = (id: string | null) => useStoreSelect((s) => (id ? s.jobs.find((j) => j.id === id) ?? null : null));

/**
 * Export: pick what goes into the `.flintpack` (only what this profile actually has), then where to save
 * it. The export runs as a job, so the dialog can be closed while it works; when it finishes, a toast
 * offers "Show in folder" (Shell's JobsTray).
 */
export function ExportProfile({ profile, onClose }: { profile: Profile | null; onClose: () => void }) {
  const t = useT();
  const toast = useToast();
  const [sections, setSections] = useState<PackSectionInfo[] | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const [chosen, setChosen] = useState<Set<PackSection>>(new Set());
  const [picking, setPicking] = useState(false);
  const [jobId, setJobId] = useState<string | null>(null);
  const job = useJob(jobId);

  useEffect(() => {
    setSections(null);
    setFailed(null);
    setJobId(null);
    if (!profile) return;
    let alive = true;
    api
      .packScan(profile.id)
      .then((r) => {
        if (!alive) return;
        setSections(r.sections);
        // Everything but worlds, which are often large and personal.
        setChosen(new Set(r.sections.map((s) => s.id).filter((id) => id !== "worlds")));
      })
      .catch((e) => alive && setFailed(errorText(e)));
    return () => {
      alive = false;
    };
  }, [profile]);

  // Done: the toast takes over. Failed or cancelled: back to the choices, with the reason.
  useEffect(() => {
    if (!job) return;
    if (job.state === "done") onClose();
    else if (job.state === "failed" || job.state === "cancelled") {
      setJobId(null);
      if (job.state === "failed") setFailed(job.message ?? t("profiles.pack.exportUnfinished"));
    }
  }, [job, onClose, t]);

  const working = !!jobId;
  const listed = PACK_SECTIONS.filter((s) => sections?.some((x) => x.id === s.id));
  const total = (sections ?? []).filter((s) => chosen.has(s.id)).reduce((sum, s) => sum + s.size, 0);
  const toggle = (id: PackSection, on: boolean) =>
    setChosen((prev) => {
      const next = new Set(prev);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });

  const start = async () => {
    if (!profile) return;
    setPicking(true);
    setFailed(null);
    try {
      const path = await api.packPickSave(profile.id);
      if (!path) return;
      setJobId(await api.packExport(profile.id, PACK_SECTIONS.map((s) => s.id).filter((id) => chosen.has(id)), path));
    } catch (e) {
      toast({ tone: "danger", title: t("profiles.pack.exportFailed"), body: errorText(e) });
    } finally {
      setPicking(false);
    }
  };

  const title = profile
    ? t(working ? "profiles.pack.exportingTitle" : "profiles.pack.exportTitle", { name: profile.name })
    : t(working ? "profiles.pack.exportingTitleNoName" : "profiles.pack.exportTitleNoName");

  return (
    <Modal
      open={!!profile}
      onClose={onClose}
      title={title}
      width={540}
      footer={
        working ? (
          <>
            <Button variant="ghost" onClick={() => jobId && void api.cancelJob(jobId)}>
              {t("profiles.pack.cancelExport")}
            </Button>
            <Button onClick={onClose}>{t("profiles.pack.background")}</Button>
          </>
        ) : (
          <>
            <Button variant="ghost" onClick={onClose}>
              {t("common.cancel")}
            </Button>
            <Button variant="primary" icon="upload" onClick={() => void start()} loading={picking} disabled={!sections}>
              {t("profiles.pack.export")}
            </Button>
          </>
        )
      }
    >
      {profile && (
        <div className="pack-dialog">
          <div className="pack-head">
            <img src={versionMeta(profile.gameVersion).thumb} alt="" />
            <span>
              <strong>{profile.name}</strong>
              <span className="faint">
                Minecraft {profile.gameVersion} · {loaderLabel(profile.loader)}
                {profile.loaderVersion ? ` ${profile.loaderVersion}` : ""}
              </span>
            </span>
          </div>
          {working ? (
            <JobProgress job={job} />
          ) : (
            <>
              <p className="muted modal-copy">{t.rich("profiles.pack.exportIntro", { file: <strong>.flintpack</strong> })}</p>
              {failed && (
                <Callout tone="danger" live>
                  {failed}
                </Callout>
              )}
              {sections === null && !failed ? (
                <div className="pack-loading">
                  <Spinner size={16} />
                  <span className="muted">{t("profiles.pack.scanning")}</span>
                </div>
              ) : listed.length > 0 ? (
                <ul className="pack-sections" aria-label={t("profiles.pack.includeLabel")}>
                  {listed.map((s) => {
                    const info = sections!.find((x) => x.id === s.id)!;
                    const text = SECTION_TEXT[s.id];
                    return (
                      <li key={s.id}>
                        <Checkbox checked={chosen.has(s.id)} onChange={(on) => toggle(s.id, on)} label={t(text.name)}>
                          <span className="pack-section-text">
                            <strong>{t(text.name)}</strong>
                            <span className="faint">{t(text.note)}</span>
                          </span>
                        </Checkbox>
                        <span className="pack-section-size">{sectionSize(info, t)}</span>
                      </li>
                    );
                  })}
                </ul>
              ) : sections ? (
                <p className="muted pack-empty">{t("profiles.pack.exportEmpty")}</p>
              ) : null}
              <p className="pack-foot-note">
                <Icon name="lock" size={14} />
                <span>
                  {t("profiles.pack.neverIncluded")}
                  {total > 0 ? ` ${t("profiles.pack.upTo", { size: bytes(total) })}` : ""}
                </span>
              </p>
            </>
          )}
        </div>
      )}
    </Modal>
  );
}

/**
 * Import: a preview of a `.flintpack` (name, version, what's inside) with the new profile's name, then a
 * job that unpacks it, downloads and verifies its Modrinth files, creates the profile and starts
 * installing its Minecraft version and loader.
 */
export function ImportProfile({ path, onClose, onImported, onPickAnother }: { path: string | null; onClose: () => void; onImported: (profileId: string) => void; onPickAnother: () => void }) {
  const t = useT();
  const toast = useToast();
  const [preview, setPreview] = useState<PackPreview | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [starting, setStarting] = useState(false);
  const [jobId, setJobId] = useState<string | null>(null);
  const job = useJob(jobId);
  const nameField = useRef<HTMLInputElement>(null);

  // The name is the one thing to decide, so it has focus as soon as the preview arrives.
  useEffect(() => {
    if (!preview || preview.blocker) return;
    nameField.current?.focus();
    nameField.current?.select();
  }, [preview]);

  useEffect(() => {
    setPreview(null);
    setFailed(null);
    setJobId(null);
    if (!path) return;
    let alive = true;
    api
      .packInspect(path)
      .then((p) => {
        if (!alive) return;
        setPreview(p);
        setName(p.name);
      })
      .catch((e) => alive && setFailed(errorText(e)));
    return () => {
      alive = false;
    };
  }, [path]);

  useEffect(() => {
    if (!job) return;
    if (job.state === "done") {
      const id = (job.result as { profileId?: string } | null)?.profileId;
      if (id) onImported(id);
      onClose();
    } else if (job.state === "failed" || job.state === "cancelled") {
      setJobId(null);
      if (job.state === "failed") setFailed(job.message ?? t("profiles.pack.importUnfinished"));
    }
  }, [job, onClose, onImported, t]);

  const working = !!jobId;
  const run = async () => {
    if (!path || !preview || preview.blocker || !name.trim()) return;
    setStarting(true);
    setFailed(null);
    try {
      setJobId(await api.packImport(path, name.trim()));
    } catch (e) {
      toast({ tone: "danger", title: t("profiles.pack.importFailed"), body: errorText(e) });
    } finally {
      setStarting(false);
    }
  };
  const fileName = preview?.fileName ?? path?.split(/[\\/]/).pop() ?? "";
  const listed = PACK_SECTIONS.flatMap((s) => {
    const info = preview?.sections.find((x) => x.id === s.id);
    return info ? [{ ...s, info }] : [];
  });
  const title = !working
    ? t("profiles.pack.importTitle")
    : name.trim()
      ? t("profiles.pack.importingTitle", { name: name.trim() })
      : t("profiles.pack.importingTitleNoName");

  return (
    <Modal
      open={!!path}
      onClose={onClose}
      title={title}
      width={540}
      footer={
        working ? (
          <>
            <Button variant="ghost" onClick={() => jobId && void api.cancelJob(jobId)}>
              {t("profiles.pack.cancelImport")}
            </Button>
            <Button onClick={onClose}>{t("profiles.pack.background")}</Button>
          </>
        ) : (
          <>
            <Button variant="ghost" onClick={onClose}>
              {t("common.cancel")}
            </Button>
            {preview ? (
              <Button variant="primary" icon="download" onClick={() => void run()} loading={starting} disabled={!!preview.blocker || !name.trim()}>
                {t("profiles.add.import")}
              </Button>
            ) : (
              <Button icon="folder" onClick={onPickAnother} disabled={!failed}>
                {t("profiles.pack.chooseAnother")}
              </Button>
            )}
          </>
        )
      }
    >
      <div className="pack-dialog">
        {preview ? (
          <>
            <div className="pack-head">
              <img src={versionMeta(preview.gameVersion).thumb} alt="" />
              <span>
                <strong>{preview.packName}</strong>
                <span className="faint">
                  Minecraft {preview.gameVersion} · {loaderLabel(preview.loader)}
                  {preview.loaderVersion ? ` ${preview.loaderVersion}` : ""}
                  {preview.modpack?.title ? ` · ${t("profiles.pack.fromModpack", { modpack: preview.modpack.title })}` : ""}
                </span>
              </span>
            </div>
            {working ? (
              <JobProgress job={job} />
            ) : (
              <>
                {preview.blocker && (
                  <Callout tone="danger" title={t("profiles.pack.blockedTitle")}>
                    {preview.blocker} {t("profiles.pack.blockedHint")}
                  </Callout>
                )}
                {failed && (
                  <Callout tone="danger" live>
                    {failed}
                  </Callout>
                )}
                <TextField
                  ref={nameField}
                  label={t("profiles.field.name")}
                  value={name}
                  maxLength={40}
                  onChange={(e) => setName(e.target.value)}
                  onKeyDown={(e) => e.key === "Enter" && void run()}
                  hint={preview.name !== preview.packName ? t("profiles.pack.renamed", { name: preview.packName }) : undefined}
                />
                <div className="pack-inside">
                  <span className="eyebrow">{t("profiles.pack.inside", { file: fileName })}</span>
                  {listed.length > 0 ? (
                    <ul className="pack-sections is-readonly">
                      {listed.map(({ id, info }) => (
                        <li key={id}>
                          <span className="pack-section-text">
                            <strong>{t(SECTION_TEXT[id].name)}</strong>
                            {info.downloads ? (
                              <span className="faint">{info.downloads === info.files ? t("profiles.pack.allDownloaded") : t("profiles.pack.someDownloaded", { count: info.downloads })}</span>
                            ) : null}
                          </span>
                          <span className="pack-section-size">{sectionSize(info, t)}</span>
                        </li>
                      ))}
                    </ul>
                  ) : (
                    <p className="muted pack-empty">{t("profiles.pack.importEmpty")}</p>
                  )}
                </div>
                <p className="pack-foot-note">
                  <Icon name="lock" size={14} />
                  <span>
                    {preview.downloads > 0 ? `${t("profiles.pack.downloadsChecked")} ` : ""}
                    {t("profiles.pack.filesChecked")}
                  </span>
                </p>
              </>
            )}
          </>
        ) : failed ? (
          <Callout tone="danger" title={fileName ? t("profiles.pack.cantImport", { file: fileName }) : t("profiles.pack.cantImportThis")} live>
            {failed}
          </Callout>
        ) : (
          <div className="pack-loading">
            <Spinner size={16} />
            <span className="muted">{t("profiles.pack.checking", { file: fileName })}</span>
          </div>
        )}
      </div>
    </Modal>
  );
}
