import { useEffect } from "react";
import type { ImportLauncher } from "../../lib/api";
import { useT, type MessageKey } from "../../lib/i18n";
import { useStoreSelect } from "../../lib/store";
import { Button, Checkbox } from "../../ui/controls";
import { Icon, type IconName } from "../../ui/Icon";
import { LauncherMark, LauncherTabs } from "../import/Launchers";
import { PreviewView } from "../import/Preview";
import { ProfileTable } from "../import/ProfileTable";
import { CancelImport, DoneView, ProgressView } from "../import/Run";
import { SelectionBar } from "../import/SelectionBar";
import { useImportFlow } from "../import/useImportFlow";
import type { Navigate } from "../shell/Shell";
import { PageHeader } from "./PageHeader";

const FACTS: { icon: IconName; title: MessageKey; body: MessageKey }[] = [
  { icon: "copy", title: "import.facts.copied.title", body: "import.facts.copied.body" },
  { icon: "layers", title: "import.facts.mods.title", body: "import.facts.mods.body" },
  { icon: "lock", title: "import.facts.accounts.title", body: "import.facts.accounts.body" },
];

/**
 * Import Profile (sidebar, when Settings → General shows it): the launchers on this computer as tabs, the open
 * one's profiles below, then the preview, the copy and the finished screen in place. The bar at the bottom stays.
 */
export default function ImportPage({ navigate }: { navigate: Navigate }) {
  const t = useT();
  const flow = useImportFlow({ resume: true });
  const { screen, listing } = flow;
  const importedBefore = useStoreSelect((s) => s.profiles.filter((p) => p.origin).length);
  useEffect(() => {
    if (!flow.launchers && !flow.scanning && !flow.scanError) void flow.scan();
    // Once, on opening the page.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const choosing = screen === "launchers" || screen === "profiles";
  const ok = flow.preview?.profiles.filter((p) => !p.blocker).length ?? 0;
  const ready = !!flow.preview && !flow.previewLoading && flow.preview.enoughSpace && ok > 0;

  return (
    <div className={`page-body import-page is-${screen}`}>
      <PageHeader title={t("import.page.title")} subtitle={t("import.page.subtitle")} />
      {choosing ? (
        <LauncherTabs flow={flow} />
      ) : (
        <div className="imp-source">
          {/* An import picked up mid-way (the page reopened) has only its job to name it. */}
          {listing ? <LauncherMark id={listing.launcher.id} size={26} /> : <Icon name="import" size={18} />}
          <strong>{listing?.launcher.name ?? flow.job?.title ?? t("import.page.sourceFallback")}</strong>
          {listing && (
            <span className="faint">
              {screen === "preview" ? t("import.preview.heading") : screen === "progress" ? t("import.page.stage.importing") : t("import.page.stage.finished")}
            </span>
          )}
        </div>
      )}
      <div className="imp-sep" role="separator" />

      {screen === "launchers" && (
        <div className="imp-page-main">
          <Overview
            launchers={flow.launchers}
            opening={!!flow.opening}
            error={flow.openError?.message ?? null}
            importedBefore={importedBefore}
            onFolder={() => void flow.pickFolder()}
            onScan={() => void flow.scan()}
            scanning={flow.scanning}
          />
        </div>
      )}
      {listing && (
        <div className="imp-page-table" hidden={screen !== "profiles"}>
          <ProfileTable key={`${listing.launcher.id}:${flow.folder ?? ""}`} listing={listing} selected={flow.selected} onSelect={flow.setSelected} loading={!!flow.opening} />
        </div>
      )}
      {screen === "preview" && (
        <div className="imp-page-main">
          <PreviewView flow={flow} />
        </div>
      )}
      {screen === "progress" && (
        <div className="imp-page-main">
          <ProgressView flow={flow} />
        </div>
      )}
      {screen === "done" && flow.finished && (
        <div className="imp-page-main">
          <DoneView flow={flow} heading="h2" />
        </div>
      )}

      <div className="imp-dock">
        {screen === "profiles" && listing && (
          <SelectionBar profiles={listing.profiles} selected={flow.selected} onSelect={flow.setSelected} worlds={flow.worlds} onWorlds={flow.setWorlds} onImport={flow.review} />
        )}
        {screen === "preview" && (
          <div className="imp-bar">
            <Button variant="ghost" icon="chevronLeft" onClick={flow.back}>
              {t("common.back")}
            </Button>
            <span className="imp-bar-hint faint">{t("import.preview.nothingCopiedYet")}</span>
            <div className="imp-bar-end">
              <Checkbox className="imp-worlds" checked={flow.worlds} onChange={flow.setWorlds}>
                {t("import.copyWorlds.label")}
              </Checkbox>
              <Button variant="primary" size="lg" iconRight="arrowRight" onClick={() => void flow.confirm()} disabled={!ready} loading={flow.starting}>
                {t("import.action.confirm")}
              </Button>
            </div>
          </div>
        )}
        {screen === "progress" && (
          <div className="imp-bar">
            <span className="imp-bar-hint faint">{t("import.page.keepsGoing")}</span>
            <div className="imp-bar-end">
              <CancelImport flow={flow} size="lg" />
            </div>
          </div>
        )}
        {screen === "done" && (
          <div className="imp-bar">
            <span className="imp-bar-hint faint">{flow.finished?.outcome.imported ? t("import.done.findThem") : t("import.page.launcherUnchanged")}</span>
            <div className="imp-bar-end">
              {!!flow.finished?.outcome.imported && (
                <Button size="lg" icon="layers" onClick={() => navigate("profiles")}>
                  {t("import.page.showInProfiles")}
                </Button>
              )}
              <Button variant="primary" size="lg" onClick={flow.reset}>
                {t("common.done")}
              </Button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * With no launcher open: what importing does, in three lines, so the page reads as a start, not an empty panel;
 * and the launchers found with nothing to bring, with why.
 */
function Overview({
  launchers,
  opening,
  error,
  importedBefore,
  onFolder,
  onScan,
  scanning,
}: {
  launchers: ImportLauncher[] | null;
  opening: boolean;
  error: string | null;
  importedBefore: number;
  onFolder: () => void;
  onScan: () => void;
  scanning: boolean;
}) {
  const t = useT();
  const none = launchers?.length === 0;
  const idle = launchers?.filter((l) => l.status !== "found") ?? [];
  return (
    <div className="imp-overview">
      <div className="imp-overview-lead">
        <span className="imp-overview-icon" aria-hidden>
          <Icon name="import" size={22} />
        </span>
        <div>
          <h2>{none ? t("import.launchers.noneFound") : opening ? t("import.overview.reading") : t("import.overview.choose")}</h2>
          <p className="muted">
            {none
              ? t("import.overview.noneFoundNote")
              : importedBefore
                ? t("import.overview.pickWithHistory", { count: importedBefore })
                : t("import.overview.pick")}
          </p>
          {none && (
            <Button icon="folderPlus" onClick={onFolder}>
              {t("import.launchers.chooseFolder")}
            </Button>
          )}
          {error && <p className="imp-overview-error">{error}</p>}
        </div>
      </div>
      {idle.length > 0 && (
        <div className="imp-idle">
          <ul>
            {idle.map((l) => (
              <li key={l.id}>
                <LauncherMark id={l.id} size={22} />
                <strong>{l.name}</strong>
                <span className="faint">{l.message ?? (l.status === "empty" ? t("import.overview.idleEmpty") : t("import.overview.idleUnreadable"))}</span>
              </li>
            ))}
          </ul>
          <button type="button" className="ob-link" onClick={onScan} disabled={scanning}>
            {scanning ? t("import.launchers.scanning") : t("import.launchers.scanAgain")}
          </button>
        </div>
      )}
      <ul className="imp-facts">
        {FACTS.map((f) => (
          <li key={f.title}>
            <Icon name={f.icon} size={16} />
            <strong>{t(f.title)}</strong>
            <span className="muted">{t(f.body)}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
