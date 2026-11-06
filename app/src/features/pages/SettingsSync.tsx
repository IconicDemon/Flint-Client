// Settings → Syncing: Auto Sync's switch, one switch per kind of thing it shares, the profiles that take part and
// their backups. Every value comes from the launcher (auto_sync_overview); turning something on asks which profile
// it starts from first.
import { useCallback, useEffect, useState } from "react";
import { api, errorText, type AutoSyncOverview, type AutoSyncSwitchId } from "../../lib/api";
import { SYNC_SWITCHES, profileName, since, sourceChoices, startExplainer, startedLine, syncStatusLine } from "../../lib/autoSync";
import { useT } from "../../lib/i18n";
import { useSettings } from "../../lib/settings";
import { Badge, Button, Callout, IconButton, Switch } from "../../ui/controls";
import { Dropdown, Modal, useToast } from "../../ui/overlays";
import type { RowDef } from "./SettingsPage";

/** What is being turned on: Auto Sync itself, or one of its switches. */
type Starting = { kind: "all" } | { kind: "switch"; id: AutoSyncSwitchId };

export function useSyncRows(): RowDef[] {
  const t = useT();
  const { settings, update } = useSettings();
  const toast = useToast();
  const [overview, setOverview] = useState<AutoSyncOverview | null>(null);
  const [starting, setStarting] = useState<Starting | null>(null);

  const reload = useCallback(() => api.autoSyncOverview().then(setOverview).catch(() => {}), []);
  useEffect(() => void reload(), [reload]);

  const turnOff = useCallback(
    async (what: Starting) => {
      try {
        if (what.kind === "all") {
          await api.autoSyncSetEnabled(false);
          update({ autoSync: false });
        } else {
          setOverview(await api.autoSyncSetSwitch(what.id, false));
        }
      } catch (e) {
        toast({ tone: "danger", title: t("settings.sync.changeFailed"), body: errorText(e) });
      }
      void reload();
    },
    [reload, t, toast, update],
  );

  const on = settings.autoSync;
  // One confirmation, shown by the row that asked for it (search may hide the others).
  const dialog = (
    <StartDialog
      starting={starting}
      overview={overview}
      onClose={() => setStarting(null)}
      onStarted={(next) => {
        if (starting?.kind === "all") update({ autoSync: true });
        setStarting(null);
        if (next) setOverview(next);
        void reload();
      }}
    />
  );
  const asks = (what: Starting) => (starting?.kind === what.kind && (what.kind === "all" || (starting.kind === "switch" && starting.id === what.id)) ? dialog : null);

  const line = syncStatusLine(overview ? { ...overview, enabled: on } : null);
  const switchRow = (s: (typeof SYNC_SWITCHES)[number]): RowDef => {
    const state = overview?.switches.find((x) => x.id === s.id);
    const started = overview && on ? startedLine(overview, s.id) : null;
    return {
      section: "sync",
      title: s.title,
      desc: s.desc,
      keywords: s.keywords,
      status: started ? <span className="faint">{started}</span> : undefined,
      render: () => (
        <>
          <Switch
            label={s.title}
            checked={!!state?.on}
            disabled={!overview || !on}
            onChange={(v) => (v ? setStarting({ kind: "switch", id: s.id }) : void turnOff({ kind: "switch", id: s.id }))}
          />
          {asks({ kind: "switch", id: s.id })}
        </>
      ),
    };
  };
  return [
    {
      section: "sync",
      title: t("settings.sync.autoSync.title"),
      desc: t("settings.sync.autoSync.desc"),
      keywords: "settings.sync.autoSync.keywords",
      status: line ? <SyncLine text={line.text} dismissId={line.dismissId} onDismiss={(id) => void api.autoSyncDismiss(id).then(reload)} /> : undefined,
      render: () => (
        <>
          <Switch label={t("settings.sync.autoSync.title")} checked={on} onChange={(v) => (v ? setStarting({ kind: "all" }) : void turnOff({ kind: "all" }))} />
          {asks({ kind: "all" })}
        </>
      ),
    },
    ...SYNC_SWITCHES.map(switchRow),
    {
      section: "sync",
      title: t("settings.sync.profiles.title"),
      desc: t("settings.sync.profiles.desc"),
      keywords: "settings.sync.profiles.keywords",
      block: true,
      render: () => <SyncProfiles overview={overview} disabled={!on} onChange={setOverview} />,
    },
    {
      section: "sync",
      title: t("settings.sync.backups.title"),
      desc: t("settings.sync.backups.desc"),
      keywords: "settings.sync.backups.keywords",
      render: () => <LatestBackups overview={overview} />,
    },
  ];
}

function SyncLine({ text, dismissId, onDismiss }: { text: string; dismissId?: string; onDismiss: (id: string) => void }) {
  const t = useT();
  return (
    <span className="muted">
      {text}
      {dismissId && (
        <>
          {" "}
          <button type="button" className="launch-link" onClick={() => onDismiss(dismissId)}>
            {t("common.dismiss")}
          </button>
        </>
      )}
    </span>
  );
}

/** Asks which profile Auto Sync (or one switch) starts from, and says what turning it on does. */
function StartDialog({ starting, overview, onClose, onStarted }: { starting: Starting | null; overview: AutoSyncOverview | null; onClose: () => void; onStarted: (next: AutoSyncOverview | null) => void }) {
  const t = useT();
  const [source, setSource] = useState<string>("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const choices = overview ? sourceChoices(overview) : [];

  useEffect(() => {
    if (!starting) return;
    // Only when the dialog opens: a refreshed list mustn't move the player's pick.
    setSource(choices[0]?.value ?? "");
    setError(null);
  }, [starting]);

  const meta = starting?.kind === "switch" ? SYNC_SWITCHES.find((s) => s.id === starting.id) : null;
  const title = meta?.confirmTitle ?? t("settings.sync.autoSync.confirmTitle");
  const noun = meta?.noun ?? t("settings.sync.autoSync.noun");
  const chosen = overview ? profileName(overview.profiles, source) : null;

  const confirm = async () => {
    if (!starting) return;
    setBusy(true);
    setError(null);
    try {
      if (starting.kind === "all") {
        await api.autoSyncSetEnabled(true, source || undefined);
        onStarted(null);
      } else {
        onStarted(await api.autoSyncSetSwitch(starting.id, true, source || undefined));
      }
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      open={!!starting}
      onClose={onClose}
      title={title}
      width={480}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {t("common.cancel")}
          </Button>
          <Button variant="primary" loading={busy} onClick={() => void confirm()} data-autofocus>
            {t("settings.sync.start.turnOn")}
          </Button>
        </>
      }
    >
      <div className="sync-start">
        {choices.length > 0 && (
          <div className="sync-start-pick">
            <span className="muted">{t("settings.sync.start.pick")}</span>
            <Dropdown label={t("settings.sync.start.pick")} value={source} onChange={setSource} options={choices} />
          </div>
        )}
        <p className="muted modal-copy">{startExplainer(noun, choices.length > 0 ? chosen : null)}</p>
        {error && (
          <Callout tone="danger" live>
            {error}
          </Callout>
        )}
      </div>
    </Modal>
  );
}

/** Each profile with its switch ("Don't sync this profile" when off) and its backups. */
function SyncProfiles({ overview, disabled, onChange }: { overview: AutoSyncOverview | null; disabled: boolean; onChange: (o: AutoSyncOverview) => void }) {
  const t = useT();
  const toast = useToast();
  if (!overview) return <p className="faint">{t("settings.sync.profiles.loading")}</p>;
  if (overview.profiles.length === 0) return <p className="faint">{t("settings.sync.profiles.none")}</p>;
  return (
    <div className="sync-profiles">
      {overview.profiles.map((p) => (
        <div key={p.id} className={`sync-profile ${p.excluded ? "is-out" : ""}`}>
          <span className="sync-profile-text">
            <strong>
              {p.name} <Badge>{p.gameVersion}</Badge>
            </strong>
            <span className="faint">
              {p.excluded
                ? t("settings.sync.profiles.leftOut")
                : (p.note ?? (p.lastPlayed ? t("settings.sync.profiles.played", { when: since(p.lastPlayed) }) : t("settings.sync.profiles.notPlayed")))}
            </span>
          </span>
          {p.backups > 0 && (
            <IconButton
              icon="history"
              size="sm"
              label={t("settings.sync.profiles.openBackups", { name: p.name, backups: p.backups })}
              onClick={() => void api.autoSyncOpenBackups(p.id).catch((e) => toast({ tone: "danger", title: t("settings.sync.backups.openFailed"), body: errorText(e) }))}
            />
          )}
          <Switch
            label={t("settings.sync.profiles.syncProfile", { name: p.name })}
            checked={!p.excluded}
            disabled={disabled}
            onChange={async (v) => {
              try {
                onChange(await api.autoSyncSetExcluded(p.id, !v));
              } catch (e) {
                toast({ tone: "danger", title: t("settings.sync.profiles.changeFailed"), body: errorText(e) });
              }
            }}
          />
        </div>
      ))}
    </div>
  );
}

/** Opens the backups of the profile Auto Sync changed last. */
function LatestBackups({ overview }: { overview: AutoSyncOverview | null }) {
  const t = useT();
  const toast = useToast();
  const latest = overview?.profiles.filter((p) => p.backups > 0).sort((a, b) => (b.lastBackup ?? 0) - (a.lastBackup ?? 0))[0];
  return (
    <Button
      size="sm"
      icon="folder"
      disabled={!latest}
      title={latest ? t("settings.sync.backups.newestIn", { name: latest.name }) : t("settings.sync.backups.noneYet")}
      onClick={() => latest && void api.autoSyncOpenBackups(latest.id).catch((e) => toast({ tone: "danger", title: t("settings.sync.backups.openFailed"), body: errorText(e) }))}
    >
      {t("settings.sync.backups.open")}
    </Button>
  );
}
