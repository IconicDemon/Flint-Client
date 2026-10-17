import { lazy, Suspense, useEffect, useRef, useState } from "react";
import { RELEASE_NOTES } from "../../data/releaseNotes";
import { api, errorText, type NewsEntry, type Profile, type ProfileState } from "../../lib/api";
import { bytes, relativeTime, shortDate } from "../../lib/format";
import { useSettings } from "../../lib/settings";
import { sound } from "../../lib/sound";
import { isBusy, seqOf, stateOf, useStoreSelect } from "../../lib/store";
import { loaderLabel, platformBlock, versionMeta } from "../../lib/versions";
import { Badge, Button, IconButton, Progress, Spinner } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { Menu, Popover, useToast } from "../../ui/overlays";
import type { Navigate } from "../shell/Shell";
import { preloadWallpapers, Wallpaper } from "./Wallpaper";

const SkinStage = lazy(() => import("./SkinStage"));

export function Home({ navigate, active }: { navigate: Navigate; active: boolean }) {
  // Home stays mounted behind every page: it reads only what it shows, so download progress and
  // other live updates don't re-render it (LaunchPanel follows the selected profile's state itself).
  const { profile, account, skin, wallpapers } = useStoreSelect((s) => ({
    profile: s.selected,
    account: s.activeAccount,
    skin: s.activeAccount ? s.skins[s.activeAccount.uuid] : undefined,
    wallpapers: s.profiles.map((p) => versionMeta(p.gameVersion).wallpaper).join("|"),
  }));
  const { settings, motionOk, effectsOk, effects } = useSettings();
  const stageMotion = !motionOk ? "static" : effects === "full" ? "full" : "balanced";

  useEffect(() => preloadWallpapers(wallpapers.split("|").filter(Boolean)), [wallpapers]);

  return (
    <div className="home">
      <Wallpaper
        src={versionMeta(profile?.gameVersion ?? "").wallpaper}
        parallax={active && settings.parallax && motionOk && effects === "full"}
        crossfade={motionOk}
      />
      <div className="home-layout">
        <LaunchPanel profile={profile} navigate={navigate} />
        <div className="home-stage">
          {account ? (
            <Suspense fallback={<div className="skin-stage" />}>
              <SkinStage
                skin={skin?.dataUrl ?? null}
                cape={skin?.capeUrl ?? null}
                model={skin?.model ?? account.skinModel ?? "classic"}
                username={account.username}
                motion={stageMotion}
                enabled3d={effectsOk}
                active={active}
              />
            </Suspense>
          ) : (
            <div className="home-signin">
              <Icon name="user" size={22} />
              <p>Sign in with your Microsoft account to see your character and play.</p>
              <Button variant="primary" icon="microsoft" onClick={() => navigate("settings", "accounts")}>
                Sign in
              </Button>
            </div>
          )}
        </div>
        <aside className="home-side" aria-label="News and friends">
          <NewsSection />
          <FriendsRow />
        </aside>
      </div>
    </div>
  );
}

const isPaused = (s: ProfileState) => s.state === "uninstalled" && !!s.message?.startsWith("Installation paused");

/** Short label for the status chip beside the version and loader. */
function stateChip(state: ProfileState, missing: number | null): string {
  if (isPaused(state)) return "Paused";
  switch (state.state) {
    case "uninstalled":
      return missing ? `Not installed · ${bytes(missing)}` : "Not installed";
    case "checking":
      return "Checking";
    case "downloading":
      return state.progress != null ? `Downloading ${Math.round(state.progress * 100)}%` : "Downloading";
    case "installing":
      return "Installing";
    case "launching":
      return "Starting";
    case "running":
      return state.demo ? "Running · demo" : "Running";
    case "error":
      return "Needs attention";
    default:
      return "Ready";
  }
}

/** What the panel's own click started, and the state number it saw then (see `seqOf`). */
type Action = { kind: "install" | "launch" | "stop"; seq: number };

/**
 * The state the panel shows. While its own launch checks and installs files, the panel keeps
 * saying "Starting" instead of flicking through Checking and Installing within a few milliseconds;
 * a launch that has to download still shows the download (and can be paused). Right after an
 * Install or Resume click it shows "Checking", the backend's first step, instead of the old label.
 */
function shownState(state: ProfileState, action: Action | null): ProfileState {
  if (!action) return state;
  const unchanged = seqOf(state) === action.seq;
  if (action.kind === "launch" && (unchanged || state.state === "checking" || state.state === "installing")) {
    return { ...state, state: "launching" };
  }
  if (action.kind === "install" && unchanged && !isBusy(state)) {
    return { profileId: state.profileId, state: "checking" };
  }
  return state;
}

function stateLine(state: ProfileState, profile: Profile, missing: number | null) {
  if (isPaused(state)) return "Paused · resume any time";
  switch (state.state) {
    case "uninstalled":
      return missing ? `Not installed · ${bytes(missing)} to download` : "Not installed yet";
    case "checking":
      // One stable text for the version, file and hash checks (their labels change within
      // milliseconds); a long check (a repair) shows how far it got.
      return state.filesTotal && state.filesDone != null
        ? `Checking game files · ${Math.floor((state.filesDone / state.filesTotal) * 100)}%`
        : "Checking game files…";
    case "downloading":
      return state.bytesTotal ? `Downloading · ${bytes(state.bytesDone ?? 0)} of ${bytes(state.bytesTotal)}` : "Downloading…";
    case "installing":
      return "Installing…";
    case "launching":
      return "Starting Minecraft…";
    case "running":
      return state.demo ? "Running in demo mode" : "Minecraft is running";
    case "error":
      return state.message ?? "Something went wrong";
    default:
      return profile.lastPlayed ? `Ready · played ${relativeTime(profile.lastPlayed)}` : "Ready to play";
  }
}

function LaunchPanel({ profile, navigate }: { profile: Profile | null; navigate: Navigate }) {
  const store = useStoreSelect((s) => ({
    selectedState: profile ? s.states[profile.id] : undefined,
    info: s.info,
    activeAccount: s.activeAccount,
    profiles: s.profiles,
    selectProfile: s.selectProfile,
  }));
  const toast = useToast();
  const [switcher, setSwitcher] = useState(false);
  const [more, setMore] = useState(false);
  const [missing, setMissing] = useState<number | null>(null);
  const [pending, setPending] = useState<Action | null>(null);
  const switchRef = useRef<HTMLButtonElement>(null);
  const moreRef = useRef<HTMLButtonElement>(null);
  const live = store.selectedState ?? stateOf({}, profile);
  const state = shownState(live, pending);
  const blocked = profile ? platformBlock(profile.gameVersion, store.info) : null;

  // A quiet size probe, once per selected profile: it shows no "Checking" state and never holds
  // the profile. (Depending on the live state here made every probe's own result start the next
  // one, a loop that kept a CPU core busy for as long as an uninstalled profile was selected.)
  const profileId = profile?.id;
  const needsProbe = !!profile && !profile.installed && !blocked;
  useEffect(() => {
    setMissing(null);
    if (!profileId || !needsProbe) return;
    let live = true;
    void api
      .checkProfile(profileId, { quiet: true })
      .then((r) => live && setMissing(r.missingBytes))
      .catch(() => {});
    return () => {
      live = false;
    };
  }, [profileId, needsProbe]);

  if (!profile) {
    return (
      <section className="home-launch is-empty" aria-label="Launch">
        <span className="launch-eyebrow">No profile yet</span>
        <h1 className="launch-title">Choose a Minecraft version</h1>
        <p className="launch-meta">A profile keeps its own version, mods and worlds.</p>
        <div className="launch-actions">
          <Button variant="primary" size="lg" icon="plus" onClick={() => navigate("profiles", "new")}>
            New profile
          </Button>
        </div>
      </section>
    );
  }

  const run = async (kind: Action["kind"], fn: () => Promise<unknown>) => {
    setPending({ kind, seq: seqOf(live) });
    try {
      await fn();
    } catch (e) {
      const message = errorText(e);
      if (message !== "Installation paused.") toast({ tone: "danger", title: "That didn't work", body: message });
    } finally {
      setPending(null);
    }
  };

  const busy = state.state === "downloading" || state.state === "checking" || state.state === "installing";
  // Only checks and downloads can be paused (also while shown as "Starting", so a launch stuck on a
  // slow network can be stopped); the install step after them is short and always finishes.
  const pausable = live.state === "downloading" || live.state === "checking";
  const paused = isPaused(state);
  const primary = () => {
    if (blocked) return;
    if (!store.activeAccount) return navigate("settings", "accounts");
    if (pausable) return void api.cancelInstall(profile.id);
    // A launch, install step or stop is already in flight: ignore repeated clicks.
    if (pending || busy) return;
    if (state.state === "running") return void run("stop", () => api.stop(profile.id));
    if (state.state === "ready") {
      sound.play("launch");
      return void run("launch", () => api.launch(profile.id));
    }
    return void run("install", () => api.installProfile(profile.id, state.state === "error" && profile.installed));
  };

  let label = "Play";
  let tone = "is-play";
  let icon: "play" | "download" | "stop" | "refresh" | "pause" | null = "play";
  if (blocked) {
    label = "Not supported";
    icon = null;
    tone = "is-muted";
  } else if (!store.activeAccount) {
    label = "Sign in to play";
    icon = null;
  } else if (paused) {
    label = "Resume";
    icon = "download";
  } else if (state.state === "uninstalled") {
    label = "Install";
    icon = "download";
  } else if (state.state === "checking") {
    label = "Checking";
    icon = null;
    tone = "is-busy";
  } else if (state.state === "installing") {
    label = "Installing";
    icon = null;
    tone = "is-busy";
  } else if (state.state === "downloading") {
    label = state.progress != null ? `Pause · ${Math.round(state.progress * 100)}%` : "Pause";
    icon = "pause";
    tone = "is-busy";
  } else if (state.state === "launching" || (pending && state.state !== "running")) {
    label = "Starting";
    icon = null;
    tone = "is-busy";
  } else if (state.state === "running") {
    label = "Stop";
    icon = "stop";
    tone = "is-running";
  } else if (state.state === "error") {
    label = profile.installed ? "Repair" : "Retry";
    icon = "refresh";
    tone = "is-error";
  }
  const spinning = !blocked && (state.state === "launching" || state.state === "checking" || state.state === "installing" || (!!pending && !busy && state.state !== "running"));
  const v = versionMeta(profile.gameVersion);
  const stateClass = blocked ? "state-blocked" : paused ? "state-paused" : `state-${state.state}`;

  const caption = blocked ?? stateLine(state, profile, missing);
  // The caption repeats the chip only when it adds something (when played, sizes, errors, progress).
  const showCaption = !!blocked || state.state === "error" || busy || paused || (state.state === "uninstalled" && missing !== null) || (state.state === "ready" && !!profile.lastPlayed) || state.state === "running";

  return (
    <section className={`home-launch ${stateClass}`} aria-label="Launch">
      <button
        ref={switchRef}
        type="button"
        className="launch-switch"
        aria-haspopup="menu"
        aria-expanded={switcher}
        onClick={() => setSwitcher((o) => !o)}
      >
        <img src={v.thumb} alt="" />
        <span>{store.profiles.length > 1 ? "Switch profile" : "Profile"}</span>
        <Icon name="chevronDown" size={14} className="launch-switch-chevron" />
      </button>
      <h1 className="launch-title" title={profile.name}>
        {profile.name}
      </h1>
      <ul className="launch-chips" aria-label="Profile details">
        <li>Java Edition {profile.gameVersion}</li>
        <li>
          {loaderLabel(profile.loader)}
          {profile.loaderVersion ? ` ${profile.loaderVersion}` : ""}
        </li>
        {profile.modpack && <li>{profile.modpack.title}</li>}
        <li className={`launch-status ${stateClass}`}>
          <span className="state-dot" />
          {blocked ? "Not supported here" : stateChip(state, missing)}
        </li>
      </ul>
      <div className="launch-actions">
        <button
          type="button"
          className={`play-btn ${tone}`}
          onClick={primary}
          disabled={!!blocked || live.state === "launching"}
          aria-label={`${label} ${profile.name}`}
        >
          {spinning ? <Spinner size={18} /> : icon && <Icon name={icon} size={18} />}
          <span>{label}</span>
        </button>
        <IconButton ref={moreRef} icon="more" label="Profile actions" variant="solid" className="launch-more" aria-haspopup="menu" aria-expanded={more} onClick={() => setMore((m) => !m)} />
      </div>
      {busy && <Progress value={state.progress ?? null} label="Install progress" className="launch-progress" />}
      <p className={`launch-state ${stateClass} ${showCaption ? "" : "is-quiet"}`} role="status" aria-live="polite">
        <span className="launch-state-text">{showCaption ? caption : ""}</span>
        {state.demo && state.state === "running" && <Badge tone="warning">Demo</Badge>}
        {state.state === "error" && (
          <button type="button" className="launch-link" onClick={() => navigate("profiles", `${profile.id}:logs`)}>
            View logs
          </button>
        )}
      </p>

      <Popover anchor={switchRef} open={switcher} onClose={() => setSwitcher(false)} placement="top-start" label="Profiles" className="profile-pop">
        <Menu
          onClose={() => setSwitcher(false)}
          items={[
            { type: "heading", label: "Launch profile" },
            ...store.profiles.map((p) => ({
              label: (
                <span className="profile-row">
                  <img src={versionMeta(p.gameVersion).thumb} alt="" />
                  <span>
                    <strong>{p.name}</strong>
                    <span className="faint">
                      Minecraft {p.gameVersion} · {loaderLabel(p.loader)}
                    </span>
                  </span>
                </span>
              ),
              active: p.id === profile.id,
              onSelect: () => void store.selectProfile(p.id),
            })),
            { type: "separator" },
            { label: "New profile", icon: <Icon name="plus" size={15} />, onSelect: () => navigate("profiles", "new") },
            { label: "Manage profiles", icon: <Icon name="layers" size={15} />, onSelect: () => navigate("profiles") },
          ]}
        />
      </Popover>
      <Popover anchor={moreRef} open={more} onClose={() => setMore(false)} placement="top-start" label="Profile actions">
        <Menu
          onClose={() => setMore(false)}
          items={[
            { label: "Open profile", icon: <Icon name="layers" size={15} />, onSelect: () => navigate("profiles", `${profile.id}:content`) },
            { label: "Add content", icon: <Icon name="library" size={15} />, onSelect: () => navigate("library", profile.id) },
            ...(profile.installed && !blocked
              ? [{ label: "Repair game files", icon: <Icon name="wrench" size={15} />, disabled: isBusy(state) || state.state === "running", onSelect: () => void run("install", () => api.installProfile(profile.id, true)) }]
              : []),
            { label: "Open folder", icon: <Icon name="folder" size={15} />, onSelect: () => void api.revealFolder("profile", profile.id) },
          ]}
        />
      </Popover>
    </section>
  );
}

function NewsSection() {
  const [news, setNews] = useState<NewsEntry[] | null>(null);
  const [offline, setOffline] = useState(false);
  const note = RELEASE_NOTES[0];
  useEffect(() => {
    void api
      .news()
      .then((r) => {
        setNews(r.entries.slice(0, 2));
        setOffline(r.offline);
      })
      .catch(() => {
        setNews([]);
        setOffline(true);
      });
  }, []);
  return (
    <section className="side-section side-news" aria-labelledby="news-title">
      <header>
        <h2 id="news-title">News</h2>
        {offline && <span className="faint">Offline · saved</span>}
      </header>
      <article className="news-item is-flint">
        <span className="news-badge">
          <Icon name="sparkle" size={14} />
        </span>
        <span>
          <strong>
            Flint {note.version} · {note.title}
          </strong>
          <span className="faint">{note.points[0]}</span>
        </span>
      </article>
      {news === null ? (
        <div className="news-loading">
          <Spinner size={14} />
        </div>
      ) : (
        news.map((n) => (
          <article key={n.id} className="news-item">
            {n.image ? (
              <img src={n.image} alt="" loading="lazy" decoding="async" />
            ) : (
              <span className="news-badge">
                <Icon name="news" size={14} />
              </span>
            )}
            <span>
              <strong>{n.title}</strong>
              <span className="faint">
                Mojang · {shortDate(n.date)}
              </span>
            </span>
          </article>
        ))
      )}
    </section>
  );
}

/** Friends aren't built yet: one quiet line that says so, plus the community link. */
function FriendsRow() {
  const info = useStoreSelect((s) => s.info);
  return (
    <section className="side-friends" aria-label="Friends">
      <span className="side-friends-text">
        <Icon name="users" size={15} />
        Friends are coming later
      </span>
      {info.discordUrl && (
        <button type="button" className="side-link" onClick={() => void api.openExternal(info.discordUrl!)}>
          <Icon name="discord" size={15} />
          <span>Discord</span>
          <Icon name="external" size={12} className="side-link-ext" />
        </button>
      )}
    </section>
  );
}
