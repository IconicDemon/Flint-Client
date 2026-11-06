import { useEffect, useLayoutEffect, useRef } from "react";
import { t, useT } from "../../lib/i18n";
import { Button } from "../../ui/controls";
import { LauncherCards, LauncherMark } from "../import/Launchers";
import { importLabel } from "../import/model";
import { PreviewView } from "../import/Preview";
import { ProfileTable } from "../import/ProfileTable";
import { CancelImport, DoneView, ProgressView } from "../import/Run";
import { SelectionBar } from "../import/SelectionBar";
import type { ImportFlow, ImportScreen } from "../import/useImportFlow";

const ORDER: ImportScreen[] = ["launchers", "profiles", "preview", "progress", "done"];

/** The footer's main button on each screen of the step (also what Enter does). Null while copying. */
export function importPrimary(flow: ImportFlow, onDone: () => void): { label: string; run: () => void; disabled: boolean; loading: boolean } | null {
  switch (flow.screen) {
    case "launchers": {
      const picked = flow.launchers?.find((l) => l.id === flow.picked && l.status === "found");
      return { label: t("common.continue"), run: () => picked && void flow.open(picked.id), disabled: !picked, loading: !!picked && flow.opening === picked.id };
    }
    case "profiles":
      return { label: importLabel(flow.selected.length), run: flow.review, disabled: flow.selected.length === 0, loading: false };
    case "preview": {
      const ready = !!flow.preview && !flow.previewLoading && flow.preview.enoughSpace && flow.preview.profiles.some((p) => !p.blocker);
      return { label: t("import.action.confirm"), run: () => void flow.confirm(), disabled: !ready, loading: flow.starting };
    }
    case "progress":
      return null;
    case "done":
      return { label: t("common.continue"), run: onDone, disabled: false, loading: false };
  }
}

/**
 * Onboarding's Import step, "Bring your profiles to Flint": a launcher, its profiles, the preview, the copy and
 * the finished screen, in one step. Each inner screen keeps its scroll position for when Back returns to it.
 */
export function ImportStep({ flow }: { flow: ImportFlow }) {
  const t = useT();
  const { screen, listing } = flow;
  useEffect(() => {
    if (!flow.launchers && !flow.scanning && !flow.scanError) void flow.scan();
    // Once, on arriving at the step; Scan again is the player's to press after that.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Forward starts at the top; Back returns to where the player was. The direction also sets the slide.
  const root = useRef<HTMLDivElement>(null);
  const scrolls = useRef<Partial<Record<ImportScreen, number>>>({});
  const shown = useRef(screen);
  const dir = useRef<1 | -1>(1);
  if (shown.current !== screen) {
    dir.current = ORDER.indexOf(screen) > ORDER.indexOf(shown.current) ? 1 : -1;
    shown.current = screen;
  }
  useEffect(() => {
    const main = root.current?.closest<HTMLElement>(".ob-main");
    if (!main) return;
    const onScroll = () => {
      scrolls.current[shown.current] = main.scrollTop;
    };
    main.addEventListener("scroll", onScroll, { passive: true });
    return () => main.removeEventListener("scroll", onScroll);
  }, []);
  useLayoutEffect(() => {
    const main = root.current?.closest<HTMLElement>(".ob-main");
    if (main) main.scrollTop = dir.current < 0 ? (scrolls.current[screen] ?? 0) : 0;
  }, [screen]);

  const slide = `imp-screen dir-${dir.current}`;
  const wide = screen === "profiles" || screen === "preview";
  return (
    <div ref={root} className={`ob-step imp-ob is-${screen} ${wide ? "ob-wide" : "ob-mid"}`}>
      {screen === "launchers" && (
        <div className={slide}>
          <header className="ob-head">
            <h1>{t("import.step.launchers.title")}</h1>
            <p className="ob-sub">{t("import.step.launchers.sub")}</p>
          </header>
          <LauncherCards flow={flow} />
        </div>
      )}
      {/* Kept while the preview shows, so Back finds the search, filter and scroll as they were. */}
      {listing && (
        <div className={`${slide} imp-choose`} hidden={screen !== "profiles"}>
          <header className="ob-head">
            <span className="imp-crumb">
              <LauncherMark id={listing.launcher.id} size={22} />
              {listing.launcher.name}
              {flow.folder && <span className="faint imp-crumb-path">{flow.folder}</span>}
            </span>
            <h1>{t("import.step.profiles.title")}</h1>
            <p className="ob-sub">{t("import.step.profiles.sub")}</p>
          </header>
          <ProfileTable key={`${listing.launcher.id}:${flow.folder ?? ""}`} listing={listing} selected={flow.selected} onSelect={flow.setSelected} loading={!!flow.opening} />
        </div>
      )}
      {screen === "preview" && (
        <div className={slide}>
          <header className="ob-head">
            <h1>{t("import.preview.heading")}</h1>
            <p className="ob-sub">{t("import.step.preview.sub")}</p>
          </header>
          <PreviewView flow={flow} />
        </div>
      )}
      {screen === "progress" && (
        <div className={slide}>
          <header className="ob-head">
            <h1>{t("import.step.progress.title")}</h1>
            <p className="ob-sub">{t("import.step.progress.sub")}</p>
          </header>
          <ProgressView flow={flow} />
        </div>
      )}
      {screen === "done" && flow.finished && (
        <div className={slide}>
          <DoneView flow={flow} />
          <button type="button" className="ob-link imp-again" onClick={flow.reset}>
            {t("import.step.again")}
          </button>
        </div>
      )}
    </div>
  );
}

/** The step's footer: Back (inside the step first), Skip for now, and the screen's main button; the selection bar on the table. */
export function ImportFooter({ flow, onBack, onSkip, onDone }: { flow: ImportFlow; onBack: () => void; onSkip: () => void; onDone: () => void }) {
  const t = useT();
  const { screen } = flow;
  const primary = importPrimary(flow, onDone);
  const back = screen === "launchers" ? onBack : screen === "profiles" || screen === "preview" ? flow.back : null;
  const backButton = back && (
    <Button variant="ghost" icon="chevronLeft" onClick={back}>
      {t("common.back")}
    </Button>
  );
  const skip = (
    <Button variant="ghost" onClick={onSkip}>
      {t("import.step.skip")}
    </Button>
  );
  if (screen === "profiles" && flow.listing) {
    return (
      <SelectionBar
        className="is-foot"
        profiles={flow.listing.profiles}
        selected={flow.selected}
        onSelect={flow.setSelected}
        worlds={flow.worlds}
        onWorlds={flow.setWorlds}
        onImport={flow.review}
        start={backButton}
        end={skip}
      />
    );
  }
  return (
    <>
      <div>{backButton ?? (screen === "done" && flow.finished?.outcome.imported ? <span className="faint imp-foot-note">{t("import.done.findThem")}</span> : null)}</div>
      <div className="ob-foot-right">
        {screen === "progress" && <CancelImport flow={flow} />}
        {screen !== "progress" && screen !== "done" && skip}
        {primary && (
          <Button variant="primary" size="lg" iconRight="arrowRight" onClick={primary.run} disabled={primary.disabled} loading={primary.loading}>
            {primary.label}
          </Button>
        )}
      </div>
    </>
  );
}
