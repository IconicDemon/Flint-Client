// Settings → Storage: what Flint's folder holds (src-tauri/src/storage.rs), the cleanups that free space, and how much
// Flint downloads at once. Settings → Troubleshooting shares the overview (for its backups) and Clear cache.
import { useEffect, useState, type CSSProperties } from "react";
import { api, errorText, type CleanupKind, type CleanupPlan, type StorageOverview, type StorageProfile } from "../../lib/api";
import { bytes } from "../../lib/format";
import { useT, type MessageKey } from "../../lib/i18n";
import { CLEANUPS, STORAGE_CATEGORIES, barSegments, categoryLabel, files, shareLabel } from "../../lib/storage";
import { Button, Callout, IconButton, Spinner } from "../../ui/controls";
import { Modal, useToast } from "../../ui/overlays";

// ---------- the overview, shared by every part of Settings that shows it ----------

type OverviewState = { overview: StorageOverview | null; loading: boolean; error: string | null };
let shared: OverviewState = { overview: null, loading: false, error: null };
const listeners = new Set<(s: OverviewState) => void>();
const publish = (next: Partial<OverviewState>) => {
  shared = { ...shared, ...next };
  listeners.forEach((l) => l(shared));
};

/** Measures Flint's folder again (`force` skips the launcher's few-second reuse, after a cleanup). */
export function refreshStorage(force = false) {
  if (shared.loading) return;
  publish({ loading: true, error: null });
  api
    .storageOverview(force)
    .then((overview) => publish({ overview, loading: false }))
    .catch((e) => publish({ loading: false, error: errorText(e) }));
}

export function useStorageOverview(): OverviewState {
  const [state, setState] = useState(shared);
  useEffect(() => {
    listeners.add(setState);
    // Measured once per visit to Settings; an older measurement shows meanwhile.
    if (!shared.loading && (!shared.overview || Date.now() / 1000 - shared.overview.scannedAt > 15)) refreshStorage();
    return () => void listeners.delete(setState);
  }, []);
  return state;
}

const swatch = (id: string) => ({ "--storage-swatch": `var(--storage-${id})` }) as CSSProperties;

// ---------- the overview card ----------

export function StorageOverviewCard() {
  const t = useT();
  const { overview, loading, error } = useStorageOverview();
  const [hovered, setHovered] = useState<string | null>(null);
  if (!overview) {
    return (
      <div className="storage-card" aria-busy={loading}>
        {error ? (
          <Callout tone="danger" title={t("settings.storage.overview.measureFailed")} action={<Button size="sm" onClick={() => refreshStorage(true)}>{t("common.tryAgain")}</Button>}>
            {error}
          </Callout>
        ) : (
          <>
            <p className="storage-total">
              <Spinner size={15} /> <span className="muted">{t("settings.storage.overview.measuring")}</span>
            </p>
            <div className="storage-bar is-empty" aria-hidden />
          </>
        )}
      </div>
    );
  }
  const segments = barSegments(overview.categories);
  const byId = new Map(overview.categories.map((c) => [c.id, c]));
  const described = segments.map((s) => t("settings.storage.overview.barItem", { category: categoryLabel(s.id), size: bytes(s.bytes) })).join(", ");
  return (
    <div className="storage-card">
      <div className="storage-head">
        {/* The size is the big number; the caption around it stays muted (the strong sets its own colour). */}
        <p className="storage-total muted">{t.rich("settings.storage.overview.total", { size: <strong>{bytes(overview.total)}</strong> })}</p>
        <IconButton icon="refresh" label={t("settings.storage.overview.measureAgain")} size="sm" disabled={loading} onClick={() => refreshStorage(true)} />
      </div>
      {overview.busy && <Callout title={t("settings.storage.overview.busy")}>{overview.busy}</Callout>}
      <div className="storage-bar" role="img" aria-label={t("settings.storage.overview.barLabel", { categories: described })} onPointerLeave={() => setHovered(null)}>
        {segments.map((s) => (
          <span
            key={s.id}
            className={`storage-segment ${hovered && hovered !== s.id ? "is-dim" : ""}`}
            style={{ ...swatch(s.id), flexGrow: s.percent }}
            title={t("settings.storage.overview.segment", { category: categoryLabel(s.id), size: bytes(s.bytes), share: shareLabel(s.bytes, overview.total) })}
            onPointerEnter={() => setHovered(s.id)}
          />
        ))}
      </div>
      <ul className="storage-legend">
        {STORAGE_CATEGORIES.map((c) => {
          const size = byId.get(c.id)?.bytes ?? 0;
          return (
            <li
              key={c.id}
              className={`${size === 0 ? "is-empty" : ""} ${hovered === c.id ? "is-active" : hovered ? "is-dim" : ""}`}
              onPointerEnter={() => size > 0 && setHovered(c.id)}
              onPointerLeave={() => setHovered(null)}
            >
              <span className="storage-swatch" style={swatch(c.id)} aria-hidden />
              <span className="storage-legend-text">
                <span className="storage-legend-name">{t(c.label)}</span>
                <span className="faint">{t(c.note)}</span>
              </span>
              <span className="storage-legend-size">
                {size > 0 ? bytes(size) : t("settings.storage.overview.none")}
                {size > 0 && <span className="faint">{shareLabel(size, overview.total)}</span>}
              </span>
            </li>
          );
        })}
      </ul>
      {overview.linkedBytes > 0 && <p className="faint storage-note">{t("settings.storage.overview.linked", { size: bytes(overview.linkedBytes) })}</p>}
      {overview.profiles.length > 0 && <ProfileSizes profiles={overview.profiles} />}
    </div>
  );
}

function ProfileSizes({ profiles }: { profiles: StorageProfile[] }) {
  const t = useT();
  const toast = useToast();
  const largest = Math.max(1, ...profiles.map((p) => p.bytes));
  return (
    <details className="storage-profiles">
      <summary>{t("settings.storage.profiles.title")}</summary>
      <ul>
        {profiles.map((p) => (
          <li key={p.folder}>
            <span className="storage-profile-text">
              <strong>{p.name}</strong>
              <span className="faint">
                {p.id
                  ? t(p.loader === "fabric" ? "settings.storage.profiles.gameFabric" : "settings.storage.profiles.game", { version: p.gameVersion ?? "" })
                  : t("settings.storage.profiles.orphan")}
                {p.rollbackBytes + p.syncBackupBytes > 0 && ` · ${t("settings.storage.profiles.backups", { size: bytes(p.rollbackBytes + p.syncBackupBytes) })}`}
              </span>
            </span>
            <span className="storage-profile-meter" aria-hidden>
              <span style={{ width: `${Math.max(2, (p.bytes / largest) * 100)}%` }} />
            </span>
            <span className="storage-profile-size">{bytes(p.bytes)}</span>
            {p.id ? (
              <IconButton
                icon="folder"
                label={t("settings.storage.profiles.openFolder", { name: p.name })}
                size="sm"
                onClick={() => void api.revealFolder("profile", p.id!).catch((e) => toast({ tone: "danger", title: t("settings.storage.profiles.openFailed"), body: errorText(e) }))}
              />
            ) : (
              <span className="storage-profile-gap" aria-hidden />
            )}
          </li>
        ))}
      </ul>
    </details>
  );
}

// ---------- cleanups ----------

/** What each cleanup says about itself in its row, and what its confirmation promises. */
const CLEANUP_COPY: Record<CleanupKind, { question: MessageKey; keeps: MessageKey; after?: MessageKey; danger: boolean }> = {
  cache: {
    question: "settings.storage.cleanup.cache.question",
    keeps: "settings.storage.cleanup.cache.keeps",
    after: "settings.storage.cleanup.cache.after",
    danger: false,
  },
  temporary: {
    question: "settings.storage.cleanup.temporary.question",
    keeps: "settings.storage.cleanup.temporary.keeps",
    danger: false,
  },
  unusedGame: {
    question: "settings.storage.cleanup.unusedGame.question",
    keeps: "settings.storage.cleanup.unusedGame.keeps",
    after: "settings.storage.cleanup.unusedGame.after",
    danger: false,
  },
  rollback: {
    question: "settings.storage.cleanup.rollback.question",
    keeps: "settings.storage.cleanup.rollback.keeps",
    after: "settings.storage.cleanup.rollback.after",
    danger: true,
  },
};

export function CleanupButton({ kind }: { kind: CleanupKind }) {
  const t = useT();
  const { overview } = useStorageOverview();
  const [open, setOpen] = useState(false);
  const size = overview?.reclaimable[kind] ?? 0;
  const { action, button } = CLEANUPS[kind];
  return (
    <>
      <Button size="sm" disabled={!overview || size === 0 || !!overview.busy} title={overview?.busy ?? undefined} onClick={() => setOpen(true)}>
        {!overview ? t(action) : size === 0 ? t("settings.storage.cleanup.nothing") : t(button, { size: bytes(size) })}
      </Button>
      <CleanupDialog kind={kind} open={open} onClose={() => setOpen(false)} />
    </>
  );
}

function CleanupDialog({ kind, open, onClose }: { kind: CleanupKind; open: boolean; onClose: () => void }) {
  const t = useT();
  const toast = useToast();
  const [plan, setPlan] = useState<CleanupPlan | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [working, setWorking] = useState(false);
  const copy = CLEANUP_COPY[kind];

  const load = () => {
    setPlan(null);
    api
      .storagePlan(kind)
      .then(setPlan)
      .catch((e) => setProblem(errorText(e)));
  };
  useEffect(() => {
    if (!open) return;
    setProblem(null);
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- a fresh plan each time the dialog opens
  }, [open, kind]);

  const run = async () => {
    if (!plan) return;
    setWorking(true);
    try {
      const result = await api.storageClean(kind, plan.id);
      if (result.failed.length > 0) {
        toast({
          tone: "danger",
          title: t("settings.storage.cleanup.partialTitle", { size: bytes(result.removedBytes) }),
          body: t("settings.storage.cleanup.partialBody", { files: result.failed.map((f) => f.name).join(", ") }),
        });
      } else {
        toast({ tone: "success", title: t(CLEANUPS[kind].done), body: t("settings.storage.cleanup.freed", { size: bytes(result.removedBytes) }) });
      }
      onClose();
    } catch (e) {
      // Something changed since the plan was shown, or a game started: show what is true now.
      setProblem(errorText(e));
      load();
    } finally {
      setWorking(false);
      refreshStorage(true);
    }
  };

  const blocked = plan?.busy ?? null;
  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t(copy.question)}
      width={480}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {t("common.cancel")}
          </Button>
          <Button variant={copy.danger ? "danger" : "primary"} loading={working} disabled={!plan || plan.bytes === 0 || !!blocked} onClick={() => void run()}>
            {plan && plan.bytes > 0 ? t(CLEANUPS[kind].confirm, { size: bytes(plan.bytes) }) : t(CLEANUPS[kind].action)}
          </Button>
        </>
      }
    >
      <div className="cleanup">
        {problem && (
          <Callout tone="warn" live>
            {problem}
          </Callout>
        )}
        {blocked && <Callout tone="warn">{blocked}</Callout>}
        {!plan ? (
          <p className="muted cleanup-loading">
            <Spinner size={14} /> {t("settings.storage.cleanup.checking")}
          </p>
        ) : plan.groups.length === 0 ? (
          <p className="muted modal-copy">{t("settings.storage.cleanup.empty")}</p>
        ) : (
          <>
            {/* The groups' names and the note come from the launcher (src-tauri/src/storage.rs), in English. */}
            <ul className="cleanup-list">
              {plan.groups.map((g) => (
                <li key={g.label}>
                  <span>{g.label}</span>
                  <span className="faint">{files(g.files)}</span>
                  <strong>{bytes(g.bytes)}</strong>
                </li>
              ))}
            </ul>
            {plan.note && <p className="faint cleanup-note">{plan.note}</p>}
          </>
        )}
        <p className="muted modal-copy">
          {t(copy.keeps)}
          {copy.after && ` ${t(copy.after)}`}
        </p>
      </div>
    </Modal>
  );
}

