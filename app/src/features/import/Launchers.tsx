import { useState, type CSSProperties } from "react";
import atlauncherLogo from "../../assets/launchers/atlauncher.png";
import curseforgeLogo from "../../assets/launchers/curseforge.svg";
import dawnLogo from "../../assets/launchers/dawn.png";
import featherLogo from "../../assets/launchers/feather.png";
import gdlauncherLogo from "../../assets/launchers/gdlauncher.png";
import lunarLogo from "../../assets/launchers/lunar.png";
import minecraftLogo from "../../assets/launchers/minecraft.png";
import modrinthLogo from "../../assets/launchers/modrinth.png";
import multimcLogo from "../../assets/launchers/multimc.svg";
import prismLogo from "../../assets/launchers/prism.svg";
import type { ImportLauncher, ImportLauncherId } from "../../lib/api";
import { useT, type Translate } from "../../lib/i18n";
import { sound } from "../../lib/sound";
import { Button, Callout } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { LAUNCHER_MARK } from "./model";
import type { ImportFlow } from "./useImportFlow";

/**
 * Each launcher's own app icon, from its project (Modrinth, Prism, MultiMC, ATLauncher, GDLauncher and CurseForge from their
 * repositories, Feather and the Minecraft Launcher from their sites, Lunar from its installed app, Dawn from its source),
 * shown only to say which launcher a profile comes from. 128 px or vector, so they stay sharp when the window zooms.
 */
const LAUNCHER_LOGO: Partial<Record<ImportLauncherId, string>> = {
  modrinth: modrinthLogo,
  curseforge: curseforgeLogo,
  prism: prismLogo,
  multimc: multimcLogo,
  lunar: lunarLogo,
  feather: featherLogo,
  dawn: dawnLogo,
  official: minecraftLogo,
  gdlauncher: gdlauncherLogo,
  atlauncher: atlauncherLogo,
};

/** A launcher's icon; its monogram on a faint tint of its own hue if the picture can't load; a folder for "folder". */
export function LauncherMark({ id, size = 40 }: { id: ImportLauncherId; size?: number }) {
  const { mark, hue } = LAUNCHER_MARK[id];
  const [broken, setBroken] = useState(false);
  const logo = broken ? undefined : LAUNCHER_LOGO[id];
  if (logo) {
    return (
      <span className="imp-mark has-logo" style={{ "--size": `${size}px` } as CSSProperties} aria-hidden>
        <img src={logo} alt="" draggable={false} onError={() => setBroken(true)} />
      </span>
    );
  }
  return (
    <span className={`imp-mark ${hue === null ? "is-folder" : ""} ${mark.length > 1 ? "is-pair" : ""}`} style={{ "--hue": hue ?? 0, "--size": `${size}px` } as CSSProperties} aria-hidden>
      {hue === null ? <Icon name="folder" size={Math.round(size * 0.46)} /> : mark}
    </span>
  );
}

/** What a launcher card or tab says under its name. */
function launcherNote(l: ImportLauncher, t: Translate): string {
  if (l.status === "empty") return t("import.launchers.empty");
  if (l.status === "unreadable") return t("import.launchers.unreadable");
  return t("import.launchers.profileCount", { count: l.profiles });
}

/** While Flint looks: the shape of what's coming, briefly (the scan takes as long as it takes, nothing more). */
function Scanning({ cards }: { cards: boolean }) {
  const t = useT();
  return (
    <div className={cards ? "imp-cards is-scanning" : "imp-tabs is-scanning"} role="status" aria-label={t("import.launchers.scanningLabel")}>
      {[0, 1, 2].map((i) => (
        <span key={i} className={cards ? "imp-card is-ghost" : "imp-tab is-ghost"} style={{ "--i": i } as CSSProperties} aria-hidden>
          <span className="imp-ghost-mark" />
          <span className="imp-ghost-line" />
          {cards && <span className="imp-ghost-line is-short" />}
        </span>
      ))}
    </div>
  );
}

/**
 * Onboarding: the launchers on this computer as cards. A click picks one (Continue opens it), a double-click or
 * Enter on the picked card opens it straight away; "Another launcher" asks for a folder.
 */
export function LauncherCards({ flow }: { flow: ImportFlow }) {
  const t = useT();
  const { launchers, scanError, picked, opening, openError } = flow;
  if (!launchers) {
    return scanError ? (
      <Callout
        tone="danger"
        title={t("import.launchers.scanFailed")}
        action={
          <Button size="sm" icon="refresh" onClick={() => void flow.scan()}>
            {t("common.tryAgain")}
          </Button>
        }
        live
      >
        {scanError}
      </Callout>
    ) : (
      <>
        <Scanning cards />
        <p className="imp-scan-note faint">{t("import.launchers.scanningNote")}</p>
      </>
    );
  }
  return (
    <div className="imp-launchers">
      <div className="imp-launchers-head">
        <span className="ob-label">{launchers.length ? t("import.launchers.found") : t("import.launchers.noneFound")}</span>
        <button type="button" className="ob-link imp-rescan" onClick={() => void flow.scan()} disabled={flow.scanning}>
          {flow.scanning ? t("import.launchers.scanning") : t("import.launchers.scanAgain")}
        </button>
      </div>
      {/* The message names every launcher Flint looks for (update it when one is added). */}
      {launchers.length === 0 && <p className="imp-none muted">{t("import.launchers.noneFoundNote")}</p>}
      <div className="imp-cards" role="radiogroup" aria-label={t("import.launchers.groupLabel")}>
        {/* Launchers with profiles, then "Another launcher", then the ones with nothing to bring. */}
        {[...launchers.filter((l) => l.status === "found"), null, ...launchers.filter((l) => l.status !== "found")].map((l, i) => {
          if (!l) {
            return (
              <button key="folder" type="button" className="imp-card is-other" style={{ "--i": i } as CSSProperties} onClick={() => void flow.pickFolder()}>
                <span className="imp-mark is-folder is-dashed" style={{ "--size": "44px" } as CSSProperties} aria-hidden>
                  <Icon name="folderPlus" size={20} />
                </span>
                <span className="imp-card-text">
                  <strong>{t("import.launchers.another")}</strong>
                  <span className="faint">{opening === "folder" ? t("import.launchers.reading") : t("import.launchers.chooseItsFolder")}</span>
                </span>
              </button>
            );
          }
          const usable = l.status === "found";
          const isPicked = usable && picked === l.id;
          return (
            <button
              key={l.id}
              type="button"
              role="radio"
              aria-checked={isPicked}
              aria-disabled={!usable || undefined}
              className={`imp-card ${isPicked ? "is-picked" : ""} ${usable ? "" : `is-${l.status}`}`}
              style={{ "--i": i } as CSSProperties}
              title={l.message ?? undefined}
              onClick={() => {
                if (!usable) return;
                sound.play("toggle");
                flow.setPicked(l.id);
              }}
              onDoubleClick={() => usable && void flow.open(l.id)}
              onKeyDown={(e) => {
                // Enter on the card already picked opens it, like Continue.
                if (e.key === "Enter" && isPicked) {
                  e.preventDefault();
                  void flow.open(l.id);
                }
              }}
            >
              <LauncherMark id={l.id} size={44} />
              <span className="imp-card-text">
                <strong>{l.name}</strong>
                <span className={l.status === "unreadable" ? "imp-card-warn" : "faint"}>{opening === l.id ? t("import.launchers.reading") : launcherNote(l, t)}</span>
                {l.status === "unreadable" && l.message && <span className="imp-card-message faint">{l.message}</span>}
              </span>
              <span className="imp-card-check" aria-hidden>
                <Icon name="check" size={13} />
              </span>
            </button>
          );
        })}
      </div>
      {openError && (
        <Callout tone="danger" title={openError.launcher === "folder" ? t("import.launchers.folderEmpty") : t("import.launchers.openFailed")} live>
          {openError.message}
        </Callout>
      )}
    </div>
  );
}

/**
 * The Import Profile page: the launchers as a row of compact tabs, and "Choose a folder". A click opens one;
 * clicking the open one again closes it, back to the overview.
 */
export function LauncherTabs({ flow }: { flow: ImportFlow }) {
  const t = useT();
  const { launchers, scanError, listing, opening, folder } = flow;
  if (!launchers) {
    return scanError ? (
      <Callout
        tone="danger"
        title={t("import.launchers.scanFailed")}
        action={
          <Button size="sm" icon="refresh" onClick={() => void flow.scan()}>
            {t("common.tryAgain")}
          </Button>
        }
        live
      >
        {scanError}
      </Callout>
    ) : (
      <Scanning cards={false} />
    );
  }
  const openId = opening ?? listing?.launcher.id ?? null;
  const folderOpen = openId === "folder";
  // Launchers with nothing to bring aren't tabs; the overview under them says why.
  const found = launchers.filter((l) => l.status === "found");
  return (
    <div className="imp-tabs" role="group" aria-label={t("import.launchers.groupLabel")}>
      {found.map((l, i) => {
        const active = openId === l.id;
        return (
          <button
            key={l.id}
            type="button"
            className={`imp-tab ${active ? "is-active" : ""}`}
            style={{ "--i": i } as CSSProperties}
            aria-pressed={active}
            onClick={() => {
              sound.play("nav");
              if (active) flow.close();
              else void flow.open(l.id);
            }}
          >
            <LauncherMark id={l.id} size={26} />
            <span className="imp-tab-name">{l.name}</span>
            <span className="imp-tab-count">{l.profiles}</span>
          </button>
        );
      })}
      <button
        type="button"
        className={`imp-tab is-folder ${folderOpen ? "is-active" : ""}`}
        style={{ "--i": found.length } as CSSProperties}
        aria-pressed={folderOpen}
        title={folderOpen && folder ? folder : undefined}
        onClick={() => {
          if (folderOpen) {
            sound.play("nav");
            flow.close();
          } else void flow.pickFolder();
        }}
      >
        <Icon name={folderOpen ? "folder" : "folderPlus"} size={16} />
        <span className="imp-tab-name">{folderOpen && listing ? listing.launcher.name : t("import.launchers.chooseFolder")}</span>
        {folderOpen && listing && <span className="imp-tab-count">{listing.profiles.length}</span>}
      </button>
    </div>
  );
}
