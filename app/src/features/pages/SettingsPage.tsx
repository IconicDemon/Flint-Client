import { useEffect, useMemo, useState, type ReactNode } from "react";
import { api, errorText, releaseLabel, subscribe, type Account, type DiscordStatus } from "../../lib/api";
import { dashedUuid } from "../../lib/format";
import { DEFAULT_SETTINGS, EFFECTS, useSettings } from "../../lib/settings";
import { sound } from "../../lib/sound";
import { useStore } from "../../lib/store";
import { Badge, Button, IconButton, Segmented, Slider, Switch, TextField } from "../../ui/controls";
import { Icon, type IconName } from "../../ui/Icon";
import { searchable, translator, useT, type MessageKey } from "../../lib/i18n";
import { useJavaRows } from "./SettingsJava";
import { requestConnectionsFocus, useConnectedAccountsRows } from "./ConnectedAccounts";
import { LanguagePicker } from "./LanguageSettings";
import { useSyncRows } from "./SettingsSync";
import { EFFECT_TEXT, FontPicker, ThemePicker } from "../../ui/AppearancePickers";
import { Modal, useToast } from "../../ui/overlays";
import { Avatar } from "../onboarding/steps";
import { checkForUpdates, useUpdates } from "../../lib/updater";
import { DOWNLOAD_LIMITS } from "../../lib/storage";
import { CleanupButton, StorageOverviewCard } from "./StorageSettings";
import { BackupsList, CopyDetailsButton, EnvironmentCard, ErrorHistory, ExportDiagnosticsButton, FolderButtons, useHasBackups } from "./TroubleshootingSettings";

export type SectionId = "general" | "appearance" | "language" | "accounts" | "minecraft" | "java" | "sync" | "audio" | "storage" | "troubleshooting" | "advanced";

const SECTIONS: { id: SectionId; label: MessageKey; icon: IconName }[] = [
  { id: "general", label: "settings.sections.general", icon: "sliders" },
  { id: "appearance", label: "settings.sections.appearance", icon: "palette" },
  { id: "language", label: "settings.sections.language", icon: "globe" },
  { id: "accounts", label: "settings.sections.accounts", icon: "users" },
  { id: "minecraft", label: "settings.sections.minecraft", icon: "cube" },
  { id: "java", label: "settings.sections.java", icon: "cup" },
  { id: "sync", label: "settings.sections.sync", icon: "sync" },
  { id: "audio", label: "settings.sections.audio", icon: "volume" },
  { id: "storage", label: "settings.sections.storage", icon: "drive" },
  { id: "troubleshooting", label: "settings.sections.troubleshooting", icon: "wrench" },
  { id: "advanced", label: "settings.sections.advanced", icon: "lock" },
];

/** "Installs at once" as Flint started with it: a change applies from the next start. */
let installsAtStart: number | null = null;

/**
 * One setting. `keywords` is a message of extra search words; search matches them in the interface
 * language and in English, so "ram" or "fps" still finds its row in any language.
 */
export type RowDef = { section: SectionId; title: string; desc?: string; keywords?: MessageKey; render: () => ReactNode; block?: boolean; status?: ReactNode };

const english = translator("en", {});

/** A section, or a target inside one: "connections" is Accounts → Connected accounts. */
const sectionOf = (target: string | undefined): SectionId | null =>
  target === "connections" ? "accounts" : SECTIONS.some((s) => s.id === target) ? (target as SectionId) : null;

export default function SettingsPage({ initialSection }: { initialSection?: string }) {
  const [section, setSection] = useState<SectionId>(() => sectionOf(initialSection) ?? "general");
  const [query, setQuery] = useState("");
  const rows = useRows();
  const t = useT();

  useEffect(() => {
    const target = sectionOf(initialSection);
    if (target) setSection(target);
    if (initialSection === "connections") requestConnectionsFocus();
  }, [initialSection]);

  const q = searchable(query.trim());
  const searchText = (r: RowDef) => searchable([r.title, r.desc, r.keywords && t(r.keywords), r.keywords && english(r.keywords), r.section].join(" "));
  const visible = q ? rows.filter((r) => searchText(r).includes(q)) : rows.filter((r) => r.section === section);
  const groups = SECTIONS.filter((s) => visible.some((r) => r.section === s.id));

  return (
    <div className="page-body settings">
      <aside className="settings-side">
        <h1>{t("settings.page.title")}</h1>
        <TextField icon="search" placeholder={t("settings.page.search")} value={query} onChange={(e) => setQuery(e.target.value)} aria-label={t("settings.page.search")} />
        <nav aria-label={t("settings.page.sections")}>
          {SECTIONS.map((s) => (
            <button
              key={s.id}
              type="button"
              className={`settings-nav ${!q && section === s.id ? "is-active" : ""}`}
              aria-current={!q && section === s.id ? "page" : undefined}
              onClick={() => {
                sound.play("nav");
                setQuery("");
                setSection(s.id);
              }}
            >
              <Icon name={s.icon} size={16} />
              {t(s.label)}
            </button>
          ))}
        </nav>
      </aside>
      <div className="settings-main scroll">
        {groups.length === 0 && <p className="muted settings-none">{t("settings.page.noMatch", { query })}</p>}
        {groups.map((g) => (
          <section key={g.id} className="settings-group">
            <h2>{t(g.label)}</h2>
            <div className="settings-rows">
              {visible
                .filter((r) => r.section === g.id)
                .map((r) => (
                  <div key={r.title} className={`settings-row ${r.block ? "is-block" : ""}`}>
                    <div className="settings-row-text">
                      <strong>{r.title}</strong>
                      {r.desc && <span className="muted">{r.desc}</span>}
                      {r.status}
                    </div>
                    <div className="settings-row-control">{r.render()}</div>
                  </div>
                ))}
            </div>
          </section>
        ))}
      </div>
    </div>
  );
}

/** A quiet sentence after the Rich Presence description; it only changes when Flint's connection does. */
const DISCORD_NOTE: Record<DiscordStatus, MessageKey | null> = {
  off: null,
  idle: "settings.general.discord.noteIdle",
  connected: "settings.general.discord.noteConnected",
  unavailable: "settings.general.discord.noteUnavailable",
};

function useRows(): RowDef[] {
  const t = useT();
  const { settings, update } = useSettings();
  const store = useStore();
  const toast = useToast();
  const [loginItem, setLoginItem] = useState<boolean | null>(null);
  const hasBackups = useHasBackups();
  installsAtStart ??= settings.maxInstalls;
  const [discord, setDiscord] = useState<DiscordStatus>("idle");
  const syncRows = useSyncRows();
  const javaRows = useJavaRows();
  const connectionRows = useConnectedAccountsRows();

  // The backend reports changes as they happen; nothing here polls.
  useEffect(() => {
    void api.discordStatus().then(setDiscord).catch(() => {});
    return subscribe<DiscordStatus>("discord-status", setDiscord);
  }, []);

  useEffect(() => {
    void api.launchAtLoginGet().then(setLoginItem).catch(() => setLoginItem(null));
  }, []);

  const discordNote = DISCORD_NOTE[settings.discordPresence ? discord : "off"];
  const rows = useMemo<RowDef[]>(
    () => [
      {
        section: "general",
        title: t("settings.general.openAtLogin.title"),
        desc: t("settings.general.openAtLogin.desc"),
        keywords: "settings.general.openAtLogin.keywords",
        render: () => (
          <Switch
            label={t("settings.general.openAtLogin.title")}
            checked={loginItem ?? settings.launchAtLogin}
            onChange={async (v) => {
              try {
                const actual = await api.launchAtLoginSet(v);
                setLoginItem(actual);
                update({ launchAtLogin: actual });
              } catch (e) {
                toast({ tone: "danger", title: t("settings.general.openAtLogin.failed"), body: errorText(e) });
              }
            }}
          />
        ),
      },
      {
        section: "general",
        title: t("settings.general.flintAssist.title"),
        desc: t("settings.general.flintAssist.desc"),
        keywords: "settings.general.flintAssist.keywords",
        render: () => <Switch label={t("settings.general.flintAssist.title")} checked={settings.flintAssist} onChange={(v) => update({ flintAssist: v })} />,
      },
      {
        section: "general",
        title: t("settings.general.gameStart.title"),
        desc: t(
          settings.gameStartBehavior === "tray"
            ? "settings.general.gameStart.descTray"
            : settings.gameStartBehavior === "keep"
              ? "settings.general.gameStart.descKeep"
              : "settings.general.gameStart.descMinimize",
        ),
        keywords: "settings.general.gameStart.keywords",
        render: () => (
          <Segmented
            label={t("settings.general.gameStart.title")}
            value={settings.gameStartBehavior}
            onChange={(v) => update({ gameStartBehavior: v })}
            options={[
              { value: "minimize", label: t("settings.general.gameStart.minimize") },
              { value: "keep", label: t("settings.general.gameStart.keep") },
              { value: "tray", label: t("settings.general.gameStart.tray") },
            ]}
          />
        ),
      },
      ...(store.info.platform === "windows"
        ? [
            {
              section: "general" as const,
              title: t("settings.general.maximized.title"),
              desc: t("settings.general.maximized.desc"),
              keywords: "settings.general.maximized.keywords" as const,
              render: () => <Switch label={t("settings.general.maximized.title")} checked={settings.gameMaximized} onChange={(v) => update({ gameMaximized: v })} />,
            },
          ]
        : []),
      {
        section: "general",
        title: t("settings.general.importInSidebar.title"),
        desc: t("settings.general.importInSidebar.desc"),
        keywords: "settings.general.importInSidebar.keywords",
        render: () => <Switch label={t("settings.general.importInSidebar.title")} checked={settings.importInSidebar} onChange={(v) => update({ importInSidebar: v })} />,
      },
      {
        section: "general",
        title: t("settings.general.friendsInSidebar.title"),
        desc: t("settings.general.friendsInSidebar.desc"),
        keywords: "settings.general.friendsInSidebar.keywords",
        render: () => <Switch label={t("settings.general.friendsInSidebar.title")} checked={settings.friendsInSidebar} onChange={(v) => update({ friendsInSidebar: v })} />,
      },
      {
        section: "general",
        title: t("settings.general.discord.title"),
        desc: discordNote ? `${t("settings.general.discord.desc")} ${t(discordNote)}` : t("settings.general.discord.desc"),
        keywords: "settings.general.discord.keywords",
        render: () => <Switch label={t("settings.general.discord.title")} checked={settings.discordPresence} onChange={(v) => update({ discordPresence: v })} />,
      },
      {
        section: "general",
        title: t("settings.general.discordDetails.title"),
        desc: t("settings.general.discordDetails.desc"),
        keywords: "settings.general.discordDetails.keywords",
        render: () => (
          <Switch label={t("settings.general.discordDetails.title")} checked={settings.discordDetails} disabled={!settings.discordPresence} onChange={(v) => update({ discordDetails: v })} />
        ),
      },
      {
        section: "general",
        title: t("settings.general.about.title"),
        desc: t("settings.general.about.desc", { version: releaseLabel(store.info), platform: store.info.platform, arch: store.info.arch }),
        keywords: "settings.general.about.keywords",
        render: () => <AboutVersion version={store.info.version} />,
      },
      {
        section: "minecraft",
        title: t("settings.minecraft.performanceMods.title"),
        desc: t("settings.minecraft.performanceMods.desc"),
        keywords: "settings.minecraft.performanceMods.keywords",
        render: () => <Switch label={t("settings.minecraft.performanceMods.title")} checked={settings.performanceMods} onChange={(v) => update({ performanceMods: v })} />,
      },
      {
        section: "minecraft",
        title: t("settings.minecraft.optimization.title"),
        desc: t("settings.minecraft.optimization.desc"),
        keywords: "settings.minecraft.optimization.keywords",
        render: () => <Switch label={t("settings.minecraft.optimization.title")} checked={settings.automaticOptimization} onChange={(v) => update({ automaticOptimization: v })} />,
      },
      {
        section: "minecraft",
        title: t("settings.minecraft.inGameMenu.title"),
        desc: t("settings.minecraft.inGameMenu.desc"),
        keywords: "settings.minecraft.inGameMenu.keywords",
        render: () => <Switch label={t("settings.minecraft.inGameMenu.title")} checked={settings.inGameMenu} onChange={(v) => update({ inGameMenu: v })} />,
      },
      {
        section: "minecraft",
        title: t("settings.minecraft.profilesFolder.title"),
        desc: t("settings.minecraft.profilesFolder.desc"),
        keywords: "settings.minecraft.profilesFolder.keywords",
        render: () => (
          <Button size="sm" icon="folder" onClick={() => void api.revealFolder("profiles").catch((e) => toast({ tone: "danger", title: t("settings.folder.openFailed"), body: errorText(e) }))}>
            {t("settings.folder.open")}
          </Button>
        ),
      },
      {
        section: "appearance",
        title: t("settings.appearance.theme.title"),
        desc: t("settings.appearance.theme.desc"),
        keywords: "settings.appearance.theme.keywords",
        block: true,
        render: () => <ThemePicker value={settings.theme} onChange={(v) => update({ theme: v })} />,
      },
      {
        section: "appearance",
        title: t("settings.appearance.typography.title"),
        desc: t("settings.appearance.typography.desc"),
        keywords: "settings.appearance.typography.keywords",
        block: true,
        render: () => <FontPicker value={settings.font} onChange={(v) => update({ font: v })} />,
      },
      {
        section: "appearance",
        title: t("settings.appearance.animations.title"),
        desc: t("settings.appearance.animations.desc"),
        keywords: "settings.appearance.animations.keywords",
        render: () => <Switch label={t("settings.appearance.animations.title")} checked={settings.animations} onChange={(v) => update({ animations: v })} />,
      },
      {
        section: "appearance",
        title: t("settings.appearance.parallax.title"),
        desc: t("settings.appearance.parallax.desc"),
        keywords: "settings.appearance.parallax.keywords",
        render: () => <Switch label={t("settings.appearance.parallax.title")} checked={settings.parallax} onChange={(v) => update({ parallax: v })} />,
      },
      {
        section: "appearance",
        title: t("settings.appearance.wallpaperDim.title"),
        desc: t("settings.appearance.wallpaperDim.desc"),
        keywords: "settings.appearance.wallpaperDim.keywords",
        render: () => (
          <Slider
            label={t("settings.appearance.wallpaperDim.slider")}
            min={0}
            max={1}
            step={0.05}
            value={settings.wallpaperDim}
            onChange={(v) => update({ wallpaperDim: v })}
            format={(v) => (v < 0.05 ? t("settings.appearance.wallpaperDim.brightest") : t("settings.appearance.wallpaperDim.dimmer", { percent: Math.round(v * 100) }))}
          />
        ),
      },
      {
        section: "appearance",
        title: t("settings.appearance.effects.title"),
        desc: t(EFFECT_TEXT[settings.effects].note),
        keywords: "settings.appearance.effects.keywords",
        render: () => (
          <Segmented
            label={t("settings.appearance.effects.title")}
            value={settings.effects}
            onChange={(v) => update({ effects: v })}
            options={EFFECTS.map((e) => ({ value: e.id, label: t(EFFECT_TEXT[e.id].name) }))}
          />
        ),
      },
      {
        section: "accounts",
        title: t("settings.accounts.list.title"),
        desc: t("settings.accounts.list.desc"),
        keywords: "settings.accounts.list.keywords",
        block: true,
        render: () => <AccountsControl />,
      },
      {
        section: "audio",
        title: t("settings.audio.sounds.title"),
        desc: t("settings.audio.sounds.desc"),
        keywords: "settings.audio.sounds.keywords",
        render: () => <Switch label={t("settings.audio.sounds.title")} checked={settings.sounds} onChange={(v) => update({ sounds: v })} />,
      },
      {
        section: "audio",
        title: t("settings.audio.volume.title"),
        keywords: "settings.audio.volume.keywords",
        render: () => (
          <div className="row-inline">
            <Slider
              label={t("settings.audio.volume.title")}
              min={0}
              max={1}
              step={0.05}
              value={settings.volume}
              disabled={!settings.sounds}
              onChange={(v) => update({ volume: v })}
              format={(v) => t("settings.audio.volume.value", { percent: Math.round(v * 100) })}
            />
            <IconButton icon="play" label={t("settings.audio.volume.preview")} size="sm" disabled={!settings.sounds} onClick={() => sound.play("success", true)} />
          </div>
        ),
      },
      {
        section: "storage",
        title: t("settings.rows.storage.spaceUsed.title"),
        keywords: "settings.rows.storage.spaceUsed.keywords",
        block: true,
        render: () => <StorageOverviewCard />,
      },
      {
        section: "storage",
        title: t("settings.rows.storage.clearCache.title"),
        desc: t("settings.rows.storage.clearCache.desc"),
        keywords: "settings.rows.storage.clearCache.keywords",
        render: () => <CleanupButton kind="cache" />,
      },
      {
        section: "storage",
        title: t("settings.rows.storage.temporary.title"),
        desc: t("settings.rows.storage.temporary.desc"),
        keywords: "settings.rows.storage.temporary.keywords",
        render: () => <CleanupButton kind="temporary" />,
      },
      {
        section: "storage",
        title: t("settings.rows.storage.unusedGame.title"),
        desc: t("settings.rows.storage.unusedGame.desc"),
        keywords: "settings.rows.storage.unusedGame.keywords",
        render: () => <CleanupButton kind="unusedGame" />,
      },
      {
        section: "storage",
        title: t("settings.rows.storage.rollback.title"),
        desc: t("settings.rows.storage.rollback.desc"),
        keywords: "settings.rows.storage.rollback.keywords",
        render: () => <CleanupButton kind="rollback" />,
      },
      {
        section: "storage",
        title: t("settings.rows.storage.downloads.title"),
        desc: t("settings.rows.storage.downloads.desc"),
        keywords: "settings.rows.storage.downloads.keywords",
        render: () => (
          <div className="row-inline">
            {settings.maxDownloads !== DOWNLOAD_LIMITS.maxDownloads.default && (
              <Button size="sm" variant="ghost" onClick={() => update({ maxDownloads: DOWNLOAD_LIMITS.maxDownloads.default })}>
                {t("settings.rows.storage.downloads.useDefault", { value: DOWNLOAD_LIMITS.maxDownloads.default })}
              </Button>
            )}
            <Slider
              label={t("settings.rows.storage.downloads.title")}
              min={DOWNLOAD_LIMITS.maxDownloads.min}
              max={DOWNLOAD_LIMITS.maxDownloads.max}
              value={settings.maxDownloads}
              onChange={(v) => update({ maxDownloads: v })}
              format={(v) => String(v)}
            />
          </div>
        ),
      },
      {
        section: "storage",
        title: t("settings.rows.storage.installs.title"),
        desc: t("settings.rows.storage.installs.desc"),
        keywords: "settings.rows.storage.installs.keywords",
        status: settings.maxInstalls !== installsAtStart && <span className="faint settings-hint">{t("settings.rows.storage.installs.restart", { count: settings.maxInstalls })}</span>,
        render: () => (
          <Segmented
            label={t("settings.rows.storage.installs.title")}
            value={String(settings.maxInstalls) as "1" | "2" | "3" | "4"}
            onChange={(v) => update({ maxInstalls: Number(v) })}
            options={(["1", "2", "3", "4"] as const).map((n) => ({ value: n, label: n }))}
          />
        ),
      },
      {
        section: "storage",
        title: t("settings.rows.storage.dataFolder.title"),
        desc: store.info.dataDir,
        keywords: "settings.rows.storage.dataFolder.keywords",
        render: () => (
          <Button size="sm" icon="folder" onClick={() => void api.revealFolder("data").catch((e) => toast({ tone: "danger", title: t("settings.folder.openFailed"), body: errorText(e) }))}>
            {t("settings.folder.open")}
          </Button>
        ),
      },
      {
        section: "troubleshooting",
        title: t("settings.rows.troubleshooting.environment.title"),
        keywords: "settings.rows.troubleshooting.environment.keywords",
        block: true,
        render: () => <EnvironmentCard />,
      },
      {
        section: "troubleshooting",
        title: t("settings.rows.troubleshooting.copyDetails.title"),
        desc: t("settings.rows.troubleshooting.copyDetails.desc"),
        keywords: "settings.rows.troubleshooting.copyDetails.keywords",
        render: () => <CopyDetailsButton />,
      },
      {
        section: "troubleshooting",
        title: t("settings.rows.troubleshooting.exportDiagnostics.title"),
        desc: t("settings.rows.troubleshooting.exportDiagnostics.desc"),
        keywords: "settings.rows.troubleshooting.exportDiagnostics.keywords",
        render: () => <ExportDiagnosticsButton />,
      },
      {
        section: "troubleshooting",
        title: t("settings.rows.troubleshooting.clearCache.title"),
        desc: t("settings.rows.troubleshooting.clearCache.desc"),
        keywords: "settings.rows.troubleshooting.clearCache.keywords",
        render: () => <CleanupButton kind="cache" />,
      },
      {
        section: "troubleshooting",
        title: t("settings.rows.troubleshooting.folders.title"),
        desc: t("settings.rows.troubleshooting.folders.desc"),
        keywords: "settings.rows.troubleshooting.folders.keywords",
        render: () => <FolderButtons />,
      },
      ...(hasBackups
        ? [
            {
              section: "troubleshooting" as const,
              title: t("settings.rows.troubleshooting.backups.title"),
              desc: t("settings.rows.troubleshooting.backups.desc"),
              keywords: "settings.rows.troubleshooting.backups.keywords" as const,
              block: true,
              render: () => <BackupsList />,
            },
          ]
        : []),
      {
        section: "troubleshooting",
        title: t("settings.rows.troubleshooting.errorHistory.title"),
        desc: t("settings.rows.troubleshooting.errorHistory.desc"),
        keywords: "settings.rows.troubleshooting.errorHistory.keywords",
        block: true,
        render: () => <ErrorHistory />,
      },
      {
        section: "advanced",
        title: t("settings.advanced.reset.title"),
        desc: t("settings.advanced.reset.desc"),
        keywords: "settings.advanced.reset.keywords",
        render: () => <ResetControl />,
      },
      // Development builds only (compiled out of releases), so it stays English and has no search words.
      ...(import.meta.env.DEV
        ? [
            {
              section: "advanced" as const,
              title: "Reopen onboarding (development)",
              desc: "Shows first-run setup again. Keeps accounts, profiles and mods. Compiled out of release builds.",
              render: () => (
                <Button
                  size="sm"
                  onClick={async () => {
                    const current = await api.onboardingGet().catch(() => ({}));
                    await api.onboardingSet({ ...current, completed: false, step: 0, profileId: null, modsInstalled: false });
                    window.location.reload();
                  }}
                >
                  Reopen
                </Button>
              ),
            },
          ]
        : []),
    ],
    [settings, update, store.info, loginItem, toast, hasBackups, discordNote, t],
  );
  const languageRows = useMemo<RowDef[]>(
    () => [{ section: "language", title: t("settings.language.title"), desc: t("settings.language.desc"), keywords: "settings.language.keywords", block: true, render: () => <LanguagePicker /> }],
    [t],
  );
  return [...rows, ...connectionRows, ...languageRows, ...javaRows, ...syncRows];
}

function AccountsControl() {
  const t = useT();
  const store = useStore();
  const toast = useToast();
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<Account | null>(null);

  const add = async () => {
    setAdding(true);
    try {
      const account = await api.signIn();
      await store.reloadAccounts();
      toast({ tone: "success", title: t("settings.accounts.signedIn", { name: account.username }) });
    } catch (e) {
      toast({ tone: "danger", title: t("settings.accounts.signInFailed"), body: errorText(e) });
    } finally {
      setAdding(false);
    }
  };

  const refresh = async (a: Account) => {
    try {
      await api.refreshAccount(a.uuid);
      await store.reloadAccounts();
      toast({ tone: "success", title: t("settings.accounts.refreshed", { name: a.username }) });
    } catch (e) {
      toast({ tone: "danger", title: t("settings.accounts.refreshFailed"), body: errorText(e) });
    }
  };

  return (
    <div className="accounts">
      {store.accounts.map((a) => (
        <div key={a.uuid} className={`account-item ${a.uuid === store.activeAccount?.uuid ? "is-active" : ""}`}>
          <Avatar account={a} size={36} />
          <span className="account-item-text">
            <strong>
              {a.username} {a.qa && <Badge tone="warning">QA</Badge>}
            </strong>
            <span className="faint mono">{dashedUuid(a.uuid)}</span>
          </span>
          {a.uuid === store.activeAccount?.uuid ? (
            <Badge tone="accent">{t("settings.accounts.active")}</Badge>
          ) : (
            <Button size="sm" onClick={() => void store.switchAccount(a.uuid)}>
              {t("settings.accounts.use")}
            </Button>
          )}
          <IconButton icon="refresh" label={t("settings.accounts.refresh", { name: a.username })} size="sm" onClick={() => void refresh(a)} />
          <IconButton icon="logout" label={t("settings.accounts.signOut", { name: a.username })} size="sm" onClick={() => setRemoving(a)} />
        </div>
      ))}
      <Button icon="microsoft" onClick={() => void add()} loading={adding}>
        {adding ? t("settings.accounts.waiting") : t("settings.accounts.add")}
      </Button>
      {adding && (
        <button type="button" className="ob-link" onClick={() => void api.cancelSignIn()}>
          {t("settings.accounts.cancelSignIn")}
        </button>
      )}
      <Modal
        open={!!removing}
        onClose={() => setRemoving(null)}
        title={t("settings.accounts.remove.title", { name: removing?.username ?? "" })}
        width={420}
        footer={
          <>
            <Button variant="ghost" onClick={() => setRemoving(null)}>
              {t("common.cancel")}
            </Button>
            <Button
              variant="danger"
              onClick={async () => {
                if (!removing) return;
                try {
                  await api.removeAccount(removing.uuid);
                } catch (e) {
                  toast({ tone: "danger", title: t("settings.accounts.remove.failed"), body: errorText(e) });
                }
                await store.reloadAccounts();
                setRemoving(null);
              }}
            >
              {t("settings.accounts.remove.confirm")}
            </Button>
          </>
        }
      >
        <p className="muted">{t("settings.accounts.remove.body")}</p>
      </Modal>
    </div>
  );
}

function ResetControl() {
  const t = useT();
  const { settings, update } = useSettings();
  const toast = useToast();
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button size="sm" onClick={() => setOpen(true)}>
        {t("settings.advanced.reset.button")}
      </Button>
      <Modal
        open={open}
        onClose={() => setOpen(false)}
        title={t("settings.advanced.reset.confirmTitle")}
        width={440}
        footer={
          <>
            <Button variant="ghost" onClick={() => setOpen(false)}>
              {t("common.cancel")}
            </Button>
            <Button
              variant="danger"
              onClick={() => {
                update({ ...DEFAULT_SETTINGS, launchAtLogin: settings.launchAtLogin });
                setOpen(false);
                toast({ tone: "success", title: t("settings.advanced.reset.done") });
              }}
            >
              {t("settings.advanced.reset.confirm")}
            </Button>
          </>
        }
      >
        <p className="muted modal-copy">{t("settings.advanced.reset.confirmBody")}</p>
      </Modal>
    </>
  );
}

/** About Flint's control: the version, a manual "Check for updates" and what it found. */
function AboutVersion({ version }: { version: string }) {
  const t = useT();
  const update = useUpdates();
  const status =
    update.phase === "checking" ? t("settings.general.about.checking")
    : update.phase === "current" ? t("settings.general.about.upToDate")
    : update.phase === "available" ? t("settings.general.about.available", { version: update.version })
    : update.phase === "downloading" ? t("settings.general.about.downloading", { version: update.version })
    : update.phase === "ready" ? t("settings.general.about.restart")
    : update.phase === "error" && update.during === "check" ? update.message
    : null;
  return (
    <span className="about-version">
      {status && <span className={`faint ${update.phase === "error" ? "is-danger" : ""}`}>{status}</span>}
      <Button size="sm" variant="ghost" disabled={update.phase === "checking" || update.phase === "downloading" || update.phase === "installing"} onClick={() => void checkForUpdates(true)}>
        {t("settings.general.about.check")}
      </Button>
      <Badge>v{version}</Badge>
    </span>
  );
}
