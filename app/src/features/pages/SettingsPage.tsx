import { useEffect, useMemo, useState, type ReactNode } from "react";
import { api, errorText, releaseLabel, subscribe, type Account, type AutoSyncStatus, type DiscordStatus } from "../../lib/api";
import { bytes, dashedUuid } from "../../lib/format";
import { autoSyncLine } from "../../lib/autoSync";
import { DEFAULT_SETTINGS, EFFECTS, useSettings } from "../../lib/settings";
import { sound } from "../../lib/sound";
import { useStore } from "../../lib/store";
import { Badge, Button, IconButton, Segmented, Slider, Switch, TextField } from "../../ui/controls";
import { Icon, type IconName } from "../../ui/Icon";
import { FontPicker, ThemePicker } from "../../ui/AppearancePickers";
import { Modal, useToast } from "../../ui/overlays";
import { Avatar } from "../onboarding/steps";
import { checkForUpdates, useUpdates } from "../../lib/updater";

type SectionId = "general" | "minecraft" | "java" | "storage" | "appearance" | "accounts" | "audio" | "advanced";

const SECTIONS: { id: SectionId; label: string; icon: IconName }[] = [
  { id: "general", label: "General", icon: "sliders" },
  { id: "minecraft", label: "Minecraft", icon: "cube" },
  { id: "java", label: "Java", icon: "cup" },
  { id: "storage", label: "Storage", icon: "drive" },
  { id: "appearance", label: "Appearance", icon: "palette" },
  { id: "accounts", label: "Accounts", icon: "users" },
  { id: "audio", label: "Audio", icon: "volume" },
  { id: "advanced", label: "Advanced", icon: "lock" },
];

type RowDef = { section: SectionId; title: string; desc?: string; keywords?: string; render: () => ReactNode; block?: boolean; status?: ReactNode };

export default function SettingsPage({ initialSection }: { initialSection?: string }) {
  const [section, setSection] = useState<SectionId>(() => (SECTIONS.some((s) => s.id === initialSection) ? (initialSection as SectionId) : "general"));
  const [query, setQuery] = useState("");
  const rows = useRows();

  useEffect(() => {
    if (initialSection && SECTIONS.some((s) => s.id === initialSection)) setSection(initialSection as SectionId);
  }, [initialSection]);

  const q = query.trim().toLowerCase();
  const visible = q ? rows.filter((r) => `${r.title} ${r.desc ?? ""} ${r.keywords ?? ""} ${r.section}`.toLowerCase().includes(q)) : rows.filter((r) => r.section === section);
  const groups = SECTIONS.filter((s) => visible.some((r) => r.section === s.id));

  return (
    <div className="page-body settings">
      <aside className="settings-side">
        <h1>Settings</h1>
        <TextField icon="search" placeholder="Search settings" value={query} onChange={(e) => setQuery(e.target.value)} aria-label="Search settings" />
        <nav aria-label="Settings sections">
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
              {s.label}
            </button>
          ))}
        </nav>
      </aside>
      <div className="settings-main scroll">
        {groups.length === 0 && <p className="muted settings-none">No settings match “{query}”.</p>}
        {groups.map((g) => (
          <section key={g.id} className="settings-group">
            <h2>{g.label}</h2>
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

/** A quiet line under the Rich Presence switch; it only changes when Flint's connection does. */
const DISCORD_NOTE: Record<DiscordStatus, string> = {
  off: "",
  idle: " It appears while a game launched from Flint is running.",
  connected: " Shared with Discord now.",
  unavailable: " Can't reach Discord right now. Flint connects when Discord is open.",
};

function useRows(): RowDef[] {
  const { settings, update } = useSettings();
  const store = useStore();
  const toast = useToast();
  const [loginItem, setLoginItem] = useState<boolean | null>(null);
  const [usage, setUsage] = useState<Awaited<ReturnType<typeof api.storageUsage>> | null>(null);
  const [totalMemory, setTotalMemory] = useState<number | null>(null);
  const [discord, setDiscord] = useState<DiscordStatus>("idle");
  const [autoSync, setAutoSync] = useState<AutoSyncStatus | null>(null);

  // The backend reports changes as they happen; nothing here polls.
  useEffect(() => {
    void api.discordStatus().then(setDiscord).catch(() => {});
    return subscribe<DiscordStatus>("discord-status", setDiscord);
  }, []);

  useEffect(() => {
    void api.autoSyncStatus().then(setAutoSync).catch(() => {});
  }, []);

  useEffect(() => {
    void api.launchAtLoginGet().then(setLoginItem).catch(() => setLoginItem(null));
    void api.storageUsage().then(setUsage).catch(() => {});
    void api
      .systemInfo()
      .then((i) => setTotalMemory(i.totalMemory))
      .catch(() => {});
  }, []);

  const memory = useMemo(() => memoryAdvice(totalMemory), [totalMemory]);

  return useMemo<RowDef[]>(
    () => [
      {
        section: "general",
        title: "Open Flint at login",
        desc: "Starts Flint when you sign in to your computer. Only for your user account.",
        keywords: "startup boot autostart launch",
        render: () => (
          <Switch
            label="Open Flint at login"
            checked={loginItem ?? settings.launchAtLogin}
            onChange={async (v) => {
              try {
                const actual = await api.launchAtLoginSet(v);
                setLoginItem(actual);
                update({ launchAtLogin: actual });
              } catch (e) {
                toast({ tone: "danger", title: "Couldn't change login item", body: errorText(e) });
              }
            }}
          />
        ),
      },
      {
        section: "general",
        title: "Flint Assist",
        desc: "Enable Flint's AI-powered recommendations and assistant features.",
        keywords: "ai flint ai assistant suggestions recommendations smart",
        render: () => <Switch label="Flint Assist" checked={settings.flintAssist} onChange={(v) => update({ flintAssist: v })} />,
      },
      {
        section: "general",
        title: "Minimize when the game starts",
        desc: "Keeps Flint out of the way while you play.",
        keywords: "hide window",
        render: () => <Switch label="Minimize when the game starts" checked={settings.minimizeOnLaunch} onChange={(v) => update({ minimizeOnLaunch: v })} />,
      },
      {
        section: "general",
        title: "Auto Sync",
        desc: "Keep your compatible game settings, resource packs, shaders, and mod configurations consistent across Flint profiles.",
        keywords: "sync profiles options resource packs shaders mods configs copy consistent",
        status: <AutoSyncLine on={settings.autoSync} status={autoSync} onDismiss={(id) => void api.autoSyncDismiss(id).then(setAutoSync).catch(() => {})} />,
        render: () => (
          <Switch
            label="Auto Sync"
            checked={settings.autoSync}
            onChange={(v) => {
              update({ autoSync: v });
              void api.autoSyncSetEnabled(v).then(setAutoSync).catch((e) => toast({ tone: "danger", title: "Couldn't change Auto Sync", body: errorText(e) }));
            }}
          />
        ),
      },
      {
        section: "general",
        title: "Discord Rich Presence",
        desc: `Show your Minecraft activity on Discord when playing through Flint.${DISCORD_NOTE[settings.discordPresence ? discord : "off"]}`,
        keywords: "discord rich presence activity status profile",
        render: () => <Switch label="Discord Rich Presence" checked={settings.discordPresence} onChange={(v) => update({ discordPresence: v })} />,
      },
      {
        section: "general",
        title: "Show Minecraft version and loader",
        desc: "Adds the version and loader, like “Minecraft 26.3 · Fabric”. When off, Discord shows only “Playing Minecraft”.",
        keywords: "discord privacy version loader details",
        render: () => (
          <Switch label="Show Minecraft version and loader" checked={settings.discordDetails} disabled={!settings.discordPresence} onChange={(v) => update({ discordDetails: v })} />
        ),
      },
      {
        section: "general",
        title: "About Flint",
        desc: `Version ${releaseLabel(store.info)} · ${store.info.platform} ${store.info.arch}`,
        keywords: "version build update release",
        render: () => <AboutVersion version={store.info.version} />,
      },
      {
        section: "minecraft",
        title: "Memory",
        desc: memory.desc,
        keywords: "ram xmx allocation",
        block: true,
        render: () => (
          <div className="memory-control">
            <Slider label="Memory" min={1024} max={memory.max} step={512} value={Math.min(settings.memoryMb, memory.max)} onChange={(v) => update({ memoryMb: v })} format={(v) => `${(v / 1024).toFixed(1)} GB`} />
            {memory.recommended && settings.memoryMb !== memory.recommended && (
              <Button size="sm" variant="ghost" onClick={() => update({ memoryMb: memory.recommended! })}>
                Use {(memory.recommended / 1024).toFixed(0)} GB
              </Button>
            )}
            {memory.warn && settings.memoryMb > memory.warn && <p className="settings-warn">That leaves little memory for your system. Minecraft may stutter or your computer may slow down.</p>}
          </div>
        ),
      },
      {
        section: "minecraft",
        title: "Performance mods for new profiles",
        desc: "New Fabric profiles start with Sodium, Lithium, FerriteCore, ImmediatelyFast, EntityCulling and Dynamic FPS where they support the version. Each can be removed in the profile. Existing profiles are never changed.",
        keywords: "fps sodium lithium optimization performance",
        render: () => <Switch label="Performance mods for new profiles" checked={settings.performanceMods} onChange={(v) => update({ performanceMods: v })} />,
      },
      {
        section: "minecraft",
        title: "Automatic Optimization",
        desc: "Lets Flint Performance Core choose Java settings for each Minecraft version and run Flint's in-game optimizations (with Flint's main menu, Minecraft 26.3). Your Minecraft video settings, mods and worlds are never changed. Takes effect the next time you press Play.",
        keywords: "performance fps java gc automatic optimization tuning",
        render: () => <Switch label="Automatic Optimization" checked={settings.automaticOptimization} onChange={(v) => update({ automaticOptimization: v })} />,
      },
      {
        section: "minecraft",
        title: "Flint main menu in Minecraft",
        desc: "Replaces Minecraft's title screen with Flint's menu. Works with Minecraft 26.3 profiles that use Fabric; other profiles keep Minecraft's own menu. Takes effect the next time you press Play.",
        keywords: "title screen main menu in-game runtime mod",
        render: () => <Switch label="Flint main menu in Minecraft" checked={settings.inGameMenu} onChange={(v) => update({ inGameMenu: v })} />,
      },
      {
        section: "minecraft",
        title: "Profiles folder",
        desc: "Worlds, mods and screenshots live inside each profile.",
        keywords: "saves worlds directory",
        render: () => (
          <Button size="sm" icon="folder" onClick={() => void api.revealFolder("profiles").catch((e) => toast({ tone: "danger", title: "Couldn't open the folder", body: errorText(e) }))}>
            Open
          </Button>
        ),
      },
      {
        section: "java",
        title: "Managed Java",
        desc: "Flint downloads Mojang's official Java for each version: Java 25 for 26.x, Java 21 for 1.21.x, Java 8 for 1.8.9.",
        keywords: "runtime jre jdk",
        render: () => <Badge tone="success">Automatic</Badge>,
      },
      {
        section: "java",
        title: "Custom Java executable",
        desc: "Leave empty to use managed Java. A custom Java must match the version the profile needs, or Minecraft won't start.",
        keywords: "runtime path jre jdk",
        block: true,
        render: () => <JavaPathControl />,
      },
      {
        section: "storage",
        title: "Disk usage",
        desc: usage
          ? `Game files ${bytes(usage.game)} · Java ${bytes(usage.runtimes)} · Profiles ${bytes(usage.profiles)} · Cache ${bytes(usage.cache)}`
          : "Calculating…",
        keywords: "space size",
        render: () => (
          <Button size="sm" icon="folder" onClick={() => void api.revealFolder("data")}>
            Show in folder
          </Button>
        ),
      },
      {
        section: "storage",
        title: "Clear cache",
        desc: "Removes cached skins, news and version lists. Worlds, mods, configs, profiles and accounts are never touched.",
        keywords: "clean temporary",
        render: () => (
          <Button
            size="sm"
            onClick={async () => {
              try {
                const result = await api.clearCache();
                if (result.failed.length > 0) {
                  toast({
                    tone: "danger",
                    title: "Some cached files couldn't be removed",
                    body: `${result.failed.map((f) => f.name).join(", ")} may be in use. Freed ${bytes(result.removedBytes)}.`,
                  });
                } else {
                  toast({ tone: "success", title: "Cache cleared", body: result.removedBytes > 0 ? `Freed ${bytes(result.removedBytes)}.` : "The cache was already empty." });
                }
              } catch (e) {
                toast({ tone: "danger", title: "Couldn't clear the cache", body: errorText(e) });
              }
              setUsage(await api.storageUsage().catch(() => null));
            }}
          >
            Clear
          </Button>
        ),
      },
      {
        section: "appearance",
        title: "Theme",
        desc: "Changes colours across Flint right away.",
        keywords: "color colour dark ember obsidian moss bloodstone red dusk violet purple",
        block: true,
        render: () => <ThemePicker value={settings.theme} onChange={(v) => update({ theme: v })} />,
      },
      {
        section: "appearance",
        title: "Typography",
        desc: "All fonts are bundled with Flint and licensed for redistribution (SIL Open Font License).",
        keywords: "font typeface minecraft pixel modern technical",
        block: true,
        render: () => <FontPicker value={settings.font} onChange={(v) => update({ font: v })} />,
      },
      {
        section: "appearance",
        title: "Animations",
        desc: "Page transitions and the character's idle motion.",
        keywords: "motion",
        render: () => <Switch label="Animations" checked={settings.animations} onChange={(v) => update({ animations: v })} />,
      },
      {
        section: "appearance",
        title: "Wallpaper parallax",
        desc: "The Home wallpaper shifts a few pixels with your cursor.",
        keywords: "motion mouse",
        render: () => <Switch label="Wallpaper parallax" checked={settings.parallax} onChange={(v) => update({ parallax: v })} />,
      },
      {
        section: "appearance",
        title: "Wallpaper brightness",
        desc: "How much Flint darkens the version wallpaper behind panels and text.",
        keywords: "background dim dark bright wallpaper",
        render: () => (
          <Slider
            label="Wallpaper dimming"
            min={0}
            max={1}
            step={0.05}
            value={settings.wallpaperDim}
            onChange={(v) => update({ wallpaperDim: v })}
            format={(v) => (v < 0.05 ? "Brightest" : `${Math.round(v * 100)}% dimmer`)}
          />
        ),
      },
      {
        section: "appearance",
        title: "Visual effects",
        desc: EFFECTS.find((e) => e.id === settings.effects)?.note ?? "",
        keywords: "performance blur 3d low lag fps reduced",
        render: () => <Segmented label="Visual effects" value={settings.effects} onChange={(v) => update({ effects: v })} options={EFFECTS.map((e) => ({ value: e.id, label: e.name }))} />,
      },
      {
        section: "accounts",
        title: "Minecraft accounts",
        desc: "Sign-in tokens are stored in your system keychain, never in Flint's files.",
        keywords: "microsoft login sign in",
        block: true,
        render: () => <AccountsControl />,
      },
      {
        section: "audio",
        title: "Interface sounds",
        desc: "Quiet cues when a download finishes, a game launches or something fails.",
        keywords: "sound sfx",
        render: () => <Switch label="Interface sounds" checked={settings.sounds} onChange={(v) => update({ sounds: v })} />,
      },
      {
        section: "audio",
        title: "Volume",
        keywords: "sound loud",
        render: () => (
          <div className="row-inline">
            <Slider label="Volume" min={0} max={1} step={0.05} value={settings.volume} disabled={!settings.sounds} onChange={(v) => update({ volume: v })} format={(v) => `${Math.round(v * 100)}%`} />
            <IconButton icon="play" label="Preview sound" size="sm" disabled={!settings.sounds} onClick={() => sound.play("success", true)} />
          </div>
        ),
      },
      {
        section: "advanced",
        title: "Mod assistant service",
        desc: !settings.flintAssist
          ? "Flint Assist is off (Settings → General), so the assistant service isn't used."
          : store.info.gatewayConfigured || settings.aiGatewayUrl
            ? "Flint's AI gateway is configured. Leave this empty to use the built-in service, or set another https address."
            : "No AI gateway configured. Mod suggestions use Flint's built-in keyword matching.",
        keywords: "ai gateway url assistant",
        block: true,
        render: () => (
          <TextField
            placeholder="https://flint-ai-gateway.netlify.app"
            value={settings.aiGatewayUrl}
            onChange={(e) => update({ aiGatewayUrl: e.target.value.trim() })}
            disabled={!settings.flintAssist}
            aria-label="AI gateway URL"
            spellCheck={false}
            hint="Leave empty to use Flint's built-in gateway. Only https addresses are used. Flint never sends sign-in tokens to the assistant."
          />
        ),
      },
      {
        section: "advanced",
        title: "Reset preferences",
        desc: "Restores theme, fonts, sounds, memory, Java and assistant settings to their defaults. Accounts, profiles, worlds and mods are not touched.",
        keywords: "default restore",
        render: () => <ResetControl />,
      },
      {
        section: "advanced",
        title: "Data folder",
        desc: store.info.dataDir,
        keywords: "path directory",
        render: () => (
          <Button size="sm" icon="folder" onClick={() => void api.revealFolder("data")}>
            Open
          </Button>
        ),
      },
      ...(import.meta.env.DEV
        ? [
            {
              section: "advanced" as const,
              title: "Reopen onboarding (development)",
              desc: "Shows first-run setup again. Keeps accounts, profiles and mods. Compiled out of release builds.",
              keywords: "dev qa test first run",
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
    [settings, update, store.info, loginItem, usage, toast, memory, autoSync],
  );
}

/** The one quiet line under Auto Sync's switch: a notice to dismiss, or that it waits for a first game. */
function AutoSyncLine({ on, status, onDismiss }: { on: boolean; status: AutoSyncStatus | null; onDismiss: (id: string) => void }) {
  const line = autoSyncLine(on, status);
  if (!line) return null;
  const { text, dismissId } = line;
  return (
    <span className="muted">
      {text}
      {dismissId && (
        <>
          {" "}
          <button type="button" className="launch-link" onClick={() => onDismiss(dismissId)}>
            Dismiss
          </button>
        </>
      )}
    </span>
  );
}

function AccountsControl() {
  const store = useStore();
  const toast = useToast();
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<Account | null>(null);

  const add = async () => {
    setAdding(true);
    try {
      const account = await api.signIn();
      await store.reloadAccounts();
      toast({ tone: "success", title: `Signed in as ${account.username}` });
    } catch (e) {
      toast({ tone: "danger", title: "Sign-in didn't finish", body: errorText(e) });
    } finally {
      setAdding(false);
    }
  };

  const refresh = async (a: Account) => {
    try {
      await api.refreshAccount(a.uuid);
      await store.reloadAccounts();
      toast({ tone: "success", title: `${a.username} is up to date` });
    } catch (e) {
      toast({ tone: "danger", title: "Couldn't refresh", body: errorText(e) });
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
            <Badge tone="accent">Active</Badge>
          ) : (
            <Button size="sm" onClick={() => void store.switchAccount(a.uuid)}>
              Use
            </Button>
          )}
          <IconButton icon="refresh" label={`Refresh ${a.username}`} size="sm" onClick={() => void refresh(a)} />
          <IconButton icon="logout" label={`Sign out ${a.username}`} size="sm" onClick={() => setRemoving(a)} />
        </div>
      ))}
      <Button icon="microsoft" onClick={() => void add()} loading={adding}>
        {adding ? "Waiting for browser…" : "Add Microsoft account"}
      </Button>
      {adding && (
        <button type="button" className="ob-link" onClick={() => void api.cancelSignIn()}>
          Cancel sign-in
        </button>
      )}
      <Modal
        open={!!removing}
        onClose={() => setRemoving(null)}
        title={`Sign out ${removing?.username ?? ""}?`}
        width={420}
        footer={
          <>
            <Button variant="ghost" onClick={() => setRemoving(null)}>
              Cancel
            </Button>
            <Button
              variant="danger"
              onClick={async () => {
                if (!removing) return;
                try {
                  await api.removeAccount(removing.uuid);
                } catch (e) {
                  toast({ tone: "danger", title: "Couldn't sign out", body: errorText(e) });
                }
                await store.reloadAccounts();
                setRemoving(null);
              }}
            >
              Sign out
            </Button>
          </>
        }
      >
        <p className="muted">Flint forgets this account and deletes its saved sign-in from your keychain. Profiles and worlds stay.</p>
      </Modal>
    </div>
  );
}

function memoryAdvice(total: number | null) {
  const GB = 1024;
  if (!total) return { max: 16 * GB, recommended: null as number | null, warn: null as number | null, desc: "How much RAM Minecraft may use. 4 GB suits most modded profiles." };
  const totalMb = Math.floor(total / (1024 * 1024));
  const max = Math.max(2 * GB, Math.min(32 * GB, Math.floor((totalMb * 0.75) / 512) * 512));
  const recommended = totalMb <= 8 * GB ? 3 * GB : totalMb <= 16 * GB ? 4 * GB : 6 * GB;
  const warn = Math.floor((totalMb * 0.6) / 512) * 512;
  return {
    max,
    recommended: Math.min(recommended, max),
    warn,
    desc: `This computer has ${(totalMb / GB).toFixed(0)} GB. ${(Math.min(recommended, max) / GB).toFixed(0)} GB is a good fit for modded profiles; more rarely helps and can cause longer pauses.`,
  };
}

function JavaPathControl() {
  const { settings, update } = useSettings();
  const [value, setValue] = useState(settings.javaPath);
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null);
  const [checking, setChecking] = useState(false);

  useEffect(() => setValue(settings.javaPath), [settings.javaPath]);

  const check = async () => {
    const path = value.trim();
    if (!path) {
      update({ javaPath: "" });
      setResult(null);
      return;
    }
    setChecking(true);
    try {
      const info = await api.validateJava(path);
      update({ javaPath: path });
      setResult({ ok: true, text: info.summary || `Java ${info.version}` });
    } catch (e) {
      setResult({ ok: false, text: errorText(e) });
    } finally {
      setChecking(false);
    }
  };

  return (
    <div className="java-control">
      <div className="row-inline">
        <TextField
          placeholder="/path/to/java"
          value={value}
          onChange={(e) => {
            setValue(e.target.value);
            setResult(null);
          }}
          onKeyDown={(e) => e.key === "Enter" && void check()}
          aria-label="Custom Java executable"
          spellCheck={false}
        />
        <Button size="sm" onClick={() => void check()} loading={checking}>
          {value.trim() ? "Check & save" : "Use managed Java"}
        </Button>
      </div>
      {result && (
        <p className={result.ok ? "settings-ok" : "settings-warn"}>
          <Icon name={result.ok ? "check" : "alert"} size={13} /> {result.text}
        </p>
      )}
      {!result && settings.javaPath && value === settings.javaPath && <p className="faint settings-hint">Using {settings.javaPath}</p>}
    </div>
  );
}

function ResetControl() {
  const { settings, update } = useSettings();
  const toast = useToast();
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button size="sm" onClick={() => setOpen(true)}>
        Reset…
      </Button>
      <Modal
        open={open}
        onClose={() => setOpen(false)}
        title="Reset preferences?"
        width={440}
        footer={
          <>
            <Button variant="ghost" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button
              variant="danger"
              onClick={() => {
                update({ ...DEFAULT_SETTINGS, launchAtLogin: settings.launchAtLogin });
                setOpen(false);
                toast({ tone: "success", title: "Preferences reset" });
              }}
            >
              Reset
            </Button>
          </>
        }
      >
        <p className="muted modal-copy">Flint's look, sounds, memory, Java and assistant settings go back to their defaults. Your accounts, profiles, worlds and mods stay exactly as they are.</p>
      </Modal>
    </>
  );
}

/** About Flint's control: the version, a manual "Check for updates" and what it found. */
function AboutVersion({ version }: { version: string }) {
  const update = useUpdates();
  const status =
    update.phase === "checking" ? "Checking…"
    : update.phase === "current" ? "You're up to date"
    : update.phase === "available" ? `Flint ${update.version} is available`
    : update.phase === "downloading" ? `Downloading Flint ${update.version}`
    : update.phase === "ready" ? "Restart Flint to finish updating"
    : update.phase === "error" && update.during === "check" ? update.message
    : null;
  return (
    <span className="about-version">
      {status && <span className={`faint ${update.phase === "error" ? "is-danger" : ""}`}>{status}</span>}
      <Button size="sm" variant="ghost" disabled={update.phase === "checking" || update.phase === "downloading" || update.phase === "installing"} onClick={() => void checkForUpdates(true)}>
        Check for updates
      </Button>
      <Badge>v{version}</Badge>
    </span>
  );
}
