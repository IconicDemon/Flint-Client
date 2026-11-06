import { useCallback, useEffect, useRef, useState } from "react";
import {
  api,
  errorText,
  subscribe,
  type ImportLauncher,
  type ImportLauncherId,
  type ImportListing,
  type ImportPreview,
  type ImportProgress,
  type ImportResult,
  type ImportResultProfile,
  type Job,
} from "../../lib/api";
import { sound } from "../../lib/sound";
import { jobActive, useStoreSelect } from "../../lib/store";
import { keepSelectable, outcome, type Outcome } from "./model";

/** Where an import is: choosing a launcher, choosing its profiles, the preview, copying, finished. */
export type ImportScreen = "launchers" | "profiles" | "preview" | "progress" | "done";

export type ImportFinished = { result: ImportResult; outcome: Outcome; message: string | null };

/*
 * Import jobs a page is showing right now. The Downloads tray leaves their "finished" toast to that page, so the
 * player doesn't hear and read the same news twice; once the page is gone, the tray reports it as usual.
 */
const onScreen = new Set<string>();
export const importOnScreen = (jobId: string) => onScreen.has(jobId);

/** A result from what the job last reported, for a job that ended without one (it failed before finishing). */
function resultFromProgress(progress: ImportProgress | null, job: Job): ImportResult {
  return {
    profiles: (progress?.profiles ?? []).map((p): ImportResultProfile => ({
      key: p.key,
      name: p.name,
      profileId: p.state === "done" ? p.profileId : null,
      state: p.state === "done" || p.state === "failed" || p.state === "skipped" ? p.state : job.state === "cancelled" ? "cancelled" : "failed",
      error: p.error ?? (p.state === "done" ? null : (job.message ?? null)),
      warnings: [],
      skipped: [],
    })),
  };
}

/**
 * One import, from finding launchers to the finished screen: the engine behind the onboarding step and the
 * Import Profile page. `resume`: pick up an import that is already running (the page opened mid-import).
 */
export function useImportFlow({ resume = false }: { resume?: boolean } = {}) {
  const reloadProfiles = useStoreSelect((s) => s.reloadProfiles);
  const [screen, setScreen] = useState<ImportScreen>("launchers");
  const [launchers, setLaunchers] = useState<ImportLauncher[] | null>(null);
  const [scanning, setScanning] = useState(false);
  const [scanError, setScanError] = useState<string | null>(null);
  const [picked, setPicked] = useState<ImportLauncherId | null>(null);
  const [listing, setListing] = useState<ImportListing | null>(null);
  const [folder, setFolder] = useState<string | null>(null);
  const [opening, setOpening] = useState<ImportLauncherId | null>(null);
  const [openError, setOpenError] = useState<{ launcher: ImportLauncherId; message: string } | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [worlds, setWorlds] = useState(false);
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [previewTry, setPreviewTry] = useState(0);
  const [jobId, setJobId] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const [startError, setStartError] = useState<string | null>(null);
  const [progress, setProgress] = useState<ImportProgress | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [finished, setFinished] = useState<ImportFinished | null>(null);
  const job = useStoreSelect((s) => (jobId ? (s.jobs.find((j) => j.id === jobId) ?? null) : null));

  // Answers that arrive after the player moved on are dropped.
  const scanReq = useRef(0);
  const openReq = useRef(0);
  const current = useRef({ listing, folder });
  current.current = { listing, folder };
  const progressRef = useRef(progress);
  progressRef.current = progress;

  const scan = useCallback(async () => {
    const req = ++scanReq.current;
    setScanning(true);
    setScanError(null);
    try {
      const found = await api.importLaunchers();
      if (req === scanReq.current) setLaunchers(found);
    } catch (e) {
      if (req === scanReq.current) setScanError(errorText(e));
    } finally {
      if (req === scanReq.current) setScanning(false);
    }
  }, []);

  /** Lists a launcher's profiles (or a chosen folder's) and shows them. Reopening the same one keeps the picks. */
  const open = useCallback(async (launcher: ImportLauncherId, path: string | null = null) => {
    const req = ++openReq.current;
    setPicked(launcher);
    setOpening(launcher);
    setOpenError(null);
    try {
      const result = await api.importProfiles(launcher, path);
      if (req !== openReq.current) return;
      const was = current.current;
      const same = was.listing?.launcher.id === launcher && was.folder === path;
      setListing(result);
      setFolder(path);
      setSelected((prev) => (same ? keepSelectable(prev, result.profiles) : []));
      setScreen("profiles");
    } catch (e) {
      if (req !== openReq.current) return;
      setOpenError({ launcher, message: errorText(e) });
      sound.play("error");
    } finally {
      if (req === openReq.current) setOpening(null);
    }
  }, []);

  /** "Another launcher": the system's folder dialog, then that folder's profiles. */
  const pickFolder = useCallback(async () => {
    setOpenError(null);
    try {
      const path = await api.importPickFolder();
      if (path) await open("folder", path);
    } catch (e) {
      setOpenError({ launcher: "folder", message: errorText(e) });
    }
  }, [open]);

  /** Back to the launchers, with nothing open (the page's tabs: clicking the open one again). */
  const close = useCallback(() => {
    openReq.current++;
    setOpening(null);
    setOpenError(null);
    setListing(null);
    setFolder(null);
    setPicked(null);
    setSelected([]);
    setScreen("launchers");
  }, []);

  // The preview, asked again whenever what it covers changes (the worlds choice, mostly).
  useEffect(() => {
    if (screen !== "preview" || !listing) return;
    let live = true;
    const chosen = new Set(selected);
    // The order the launcher lists them in (newest played first), so the import starts with the one played last.
    const keys = listing.profiles.filter((p) => chosen.has(p.key)).map((p) => p.key);
    setPreviewLoading(true);
    setPreviewError(null);
    api
      .importPreview({ launcher: listing.launcher.id, folder, keys, worlds })
      .then((p) => live && setPreview(p))
      .catch((e) => live && setPreviewError(errorText(e)))
      .finally(() => live && setPreviewLoading(false));
    return () => {
      live = false;
    };
  }, [screen, listing, folder, selected, worlds, previewTry]);

  const review = useCallback(() => {
    setPreview(null);
    setStartError(null);
    setScreen("preview");
  }, []);

  const confirm = useCallback(async () => {
    const { listing: list, folder: path } = current.current;
    if (!preview || !list || !preview.enoughSpace) return;
    // Blocked profiles are listed in the preview and left out here.
    const keys = preview.profiles.filter((p) => !p.blocker).map((p) => p.key);
    if (!keys.length) return;
    setStarting(true);
    setStartError(null);
    try {
      const id = await api.importStart({ launcher: list.launcher.id, folder: path, keys, worlds });
      onScreen.add(id);
      setProgress(null);
      setFinished(null);
      setCancelling(false);
      setJobId(id);
      setScreen("progress");
    } catch (e) {
      setStartError(errorText(e));
      sound.play("error");
    } finally {
      setStarting(false);
    }
  }, [preview, worlds]);

  const cancel = useCallback(async () => {
    if (!jobId) return;
    setCancelling(true);
    try {
      await api.cancelJob(jobId);
    } catch (e) {
      setCancelling(false);
      setStartError(errorText(e));
    }
  }, [jobId]);

  // Live rows: the event as it comes, and the job's state now for a page that opened mid-import.
  useEffect(() => {
    if (!jobId) return;
    let live = true;
    const off = subscribe<ImportProgress>("import-progress", (p) => {
      if (p.job === jobId) setProgress(p);
    });
    void api
      .importProgress(jobId)
      .then((p) => live && p && setProgress((prev) => prev ?? p))
      .catch(() => {});
    return () => {
      live = false;
      off();
    };
  }, [jobId]);

  // The job ended (finished, stopped or failed): the finished screen, and the new profiles in Flint's list.
  useEffect(() => {
    if (!job || jobActive(job) || screen !== "progress") return;
    const reported = job.result as ImportResult | null | undefined;
    const result = reported && Array.isArray(reported.profiles) ? reported : resultFromProgress(progressRef.current, job);
    const told = outcome(result, job.state === "cancelled");
    setFinished({ result, outcome: told, message: job.state === "failed" ? (job.message ?? null) : null });
    setCancelling(false);
    setScreen("done");
    void reloadProfiles().catch(() => {});
    if (told.imported) sound.play("success");
    else if (told.tone === "failed") sound.play("error");
  }, [job, screen, reloadProfiles]);

  // The page opened while an import runs: show it. Both kinds of `.flintpack` and launcher imports are "import"
  // jobs; only a launcher import has progress rows.
  const running = useStoreSelect((s) =>
    s.jobs
      .filter((j) => j.kind === "import" && jobActive(j))
      .map((j) => j.id)
      .join(" "),
  );
  // Checked once, when the page opens; a check cut short (the page closed, or React's development double effect)
  // doesn't count.
  const resumed = useRef(false);
  useEffect(() => {
    if (!resume || resumed.current || jobId || !running) return;
    let live = true;
    void (async () => {
      for (const id of running.split(" ")) {
        const p = await api.importProgress(id).catch(() => null);
        if (!live) return;
        if (!p) continue;
        resumed.current = true;
        onScreen.add(id);
        setProgress(p);
        setJobId(id);
        setScreen("progress");
        return;
      }
      resumed.current = true;
    })();
    return () => {
      live = false;
    };
  }, [resume, running, jobId]);

  const jobRef = useRef(jobId);
  jobRef.current = jobId;
  useEffect(
    () => () => {
      if (jobRef.current) onScreen.delete(jobRef.current);
    },
    [],
  );

  /** After the finished screen: ready for another import, with the open launcher listed afresh. */
  const reset = useCallback(() => {
    if (jobRef.current) onScreen.delete(jobRef.current);
    setJobId(null);
    setProgress(null);
    setFinished(null);
    setPreview(null);
    setSelected([]);
    setWorlds(false);
    // The listing is read again: the profiles just imported now show as in Flint.
    const { listing: list, folder: path } = current.current;
    setScreen(list ? "profiles" : "launchers");
    if (list) void open(list.launcher.id, path);
  }, [open]);

  /** One screen back: the preview to the table, the table to the launchers. */
  const back = useCallback(() => {
    if (screen === "preview") setScreen("profiles");
    else if (screen === "profiles") setScreen("launchers");
  }, [screen]);

  return {
    screen,
    setScreen,
    launchers,
    scanning,
    scanError,
    scan,
    picked,
    setPicked,
    listing,
    folder,
    opening,
    openError,
    open,
    pickFolder,
    close,
    selected,
    setSelected,
    worlds,
    setWorlds,
    review,
    preview,
    previewLoading,
    previewError,
    retryPreview: () => setPreviewTry((n) => n + 1),
    confirm,
    starting,
    startError,
    jobId,
    job,
    progress,
    cancel,
    cancelling,
    finished,
    reset,
    back,
  };
}

export type ImportFlow = ReturnType<typeof useImportFlow>;
