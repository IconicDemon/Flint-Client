import { lazy, Suspense, useEffect, useRef, useState, type ReactNode } from "react";
import flintMark from "../../assets/brand/flint-icon.svg";
// Flint's news picture: sparks off a campfire (Wes Hicks, Unsplash License), cropped to the news thumbnail.
import flintSparks from "../../assets/news/flint-sparks.jpg";
// The 1.0.1 release banner: the owner's original 1920×1080 PNG, byte for byte (SHA-256 e294ee29…a5910b3e9). The website
// uses the same file as the release's cover.
import banner101 from "../../assets/news/flint-1.0.1-release.png";
import { RELEASE_NOTES } from "../../data/releaseNotes";
import { api, errorText, type FeedRelease, type Profile, type ProfileState } from "../../lib/api";
import { useDisplayedCape } from "../../lib/displayedCape";
import { bytes, relativeTime, shortDate } from "../../lib/format";
import { useT, type Translate } from "../../lib/i18n";
import { useSettings } from "../../lib/settings";
import { sound } from "../../lib/sound";
import { isBusy, seqOf, stateOf, useStoreSelect } from "../../lib/store";
import { loaderLabel, platformBlock, versionMeta } from "../../lib/versions";
import { Badge, Button, IconButton, Progress, Spinner } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { Menu, Popover, useToast } from "../../ui/overlays";
import type { Navigate } from "../shell/Shell";
import { useFriends } from "../friends/FriendsProvider";
import { newsReleases, releasePage } from "../../lib/newsLinks";
import { FeaturedPartner } from "../partners/FeaturedPartner";
import { preloadWallpapers, Wallpaper } from "./Wallpaper";

const SkinStage = lazy(() => import("./SkinStage"));

export function Home({ navigate, active }: { navigate: Navigate; active: boolean }) {
  const t = useT();
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
  // The Flint Store cape the account wears, else its Minecraft cape, else none (lib/capeSource.ts).
  const cape = useDisplayedCape(account?.uuid);

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
                cape={cape}
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
              <p>{t("home.signIn.prompt")}</p>
              <Button variant="primary" icon="microsoft" onClick={() => navigate("settings", "accounts")}>
                {t("home.signIn.button")}
              </Button>
            </div>
          )}
        </div>
        <aside className="home-side" aria-label={t("home.side.label")}>
          <NewsSection />
          <FeaturedPartner navigate={navigate} />
          <FriendsRow navigate={navigate} />
        </aside>
      </div>
    </div>
  );
}

const isPaused = (s: ProfileState) => s.state === "uninstalled" && !!s.message?.startsWith("Installation paused");

/** Short label for the status chip beside the version and loader. */
function stateChip(t: Translate, state: ProfileState, missing: number | null): string {
  if (isPaused(state)) return t("home.chip.paused");
  switch (state.state) {
    case "uninstalled":
      return missing ? t("home.chip.notInstalledSize", { size: bytes(missing) }) : t("home.chip.notInstalled");
    case "checking":
      return t("home.chip.checking");
    case "downloading":
      return state.progress != null ? t("home.chip.downloadingPercent", { percent: Math.round(state.progress * 100) }) : t("home.chip.downloading");
    case "installing":
      return t("home.chip.installing");
    case "launching":
      return t("home.chip.starting");
    case "running":
      return state.demo ? t("home.chip.runningDemo") : t("home.chip.running");
    case "error":
      return t("home.chip.error");
    default:
      return t("home.chip.ready");
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

function stateLine(t: Translate, state: ProfileState, profile: Profile, missing: number | null) {
  if (isPaused(state)) return t("home.status.paused");
  switch (state.state) {
    case "uninstalled":
      return missing ? t("home.status.notInstalledSize", { size: bytes(missing) }) : t("home.status.notInstalled");
    case "checking":
      // One stable text for the version, file and hash checks (their labels change within
      // milliseconds); a long check (a repair) shows how far it got.
      return state.filesTotal && state.filesDone != null
        ? t("home.status.checkingPercent", { percent: Math.floor((state.filesDone / state.filesTotal) * 100) })
        : t("home.status.checking");
    case "downloading":
      return state.bytesTotal
        ? t("home.status.downloadingBytes", { done: bytes(state.bytesDone ?? 0), total: bytes(state.bytesTotal) })
        : t("home.status.downloading");
    case "installing":
      return t("home.status.installing");
    case "launching":
      return t("home.status.starting");
    case "running":
      return state.demo ? t("home.status.runningDemo") : t("home.status.running");
    case "error":
      return state.message ?? t("home.status.error");
    default:
      return profile.lastPlayed ? t("home.status.readyPlayed", { when: relativeTime(profile.lastPlayed) }) : t("home.status.ready");
  }
}

function LaunchPanel({ profile, navigate }: { profile: Profile | null; navigate: Navigate }) {
  const t = useT();
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
      <section className="home-launch is-empty" aria-label={t("home.launch.label")}>
        <span className="launch-eyebrow">{t("home.empty.eyebrow")}</span>
        <h1 className="launch-title">{t("home.empty.title")}</h1>
        <p className="launch-meta">{t("home.empty.body")}</p>
        <div className="launch-actions">
          <Button variant="primary" size="lg" icon="plus" onClick={() => navigate("profiles", "new")}>
            {t("home.empty.newProfile")}
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
      if (message !== "Installation paused.") toast({ tone: "danger", title: t("home.launch.failed"), body: message });
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

  let label = t("home.play.play");
  let tone = "is-play";
  let icon: "play" | "download" | "stop" | "refresh" | "pause" | null = "play";
  if (blocked) {
    label = t("home.play.blocked");
    icon = null;
    tone = "is-muted";
  } else if (!store.activeAccount) {
    label = t("home.play.signIn");
    icon = null;
  } else if (paused) {
    label = t("home.play.resume");
    icon = "download";
  } else if (state.state === "uninstalled") {
    label = t("home.play.install");
    icon = "download";
  } else if (state.state === "checking") {
    label = t("home.play.checking");
    icon = null;
    tone = "is-busy";
  } else if (state.state === "installing") {
    label = t("home.play.installing");
    icon = null;
    tone = "is-busy";
  } else if (state.state === "downloading") {
    label = state.progress != null ? t("home.play.pausePercent", { percent: Math.round(state.progress * 100) }) : t("home.play.pause");
    icon = "pause";
    tone = "is-busy";
  } else if (state.state === "launching" || (pending && state.state !== "running")) {
    label = t("home.play.starting");
    icon = null;
    tone = "is-busy";
  } else if (state.state === "running") {
    label = t("home.play.stop");
    icon = "stop";
    tone = "is-running";
  } else if (state.state === "error") {
    label = profile.installed ? t("home.play.repair") : t("common.retry");
    icon = "refresh";
    tone = "is-error";
  }
  const spinning = !blocked && (state.state === "launching" || state.state === "checking" || state.state === "installing" || (!!pending && !busy && state.state !== "running"));
  const v = versionMeta(profile.gameVersion);
  const stateClass = blocked ? "state-blocked" : paused ? "state-paused" : `state-${state.state}`;

  const caption = blocked ?? stateLine(t, state, profile, missing);
  // The caption repeats the chip only when it adds something (when played, sizes, errors, progress).
  const showCaption = !!blocked || state.state === "error" || busy || paused || (state.state === "uninstalled" && missing !== null) || (state.state === "ready" && !!profile.lastPlayed) || state.state === "running";

  return (
    <section className={`home-launch ${stateClass}`} aria-label={t("home.launch.label")}>
      <button
        ref={switchRef}
        type="button"
        className="launch-switch"
        aria-haspopup="menu"
        aria-expanded={switcher}
        onClick={() => setSwitcher((o) => !o)}
      >
        <img src={v.thumb} alt="" />
        <span>{store.profiles.length > 1 ? t("home.launch.switchProfile") : t("home.launch.profile")}</span>
        <Icon name="chevronDown" size={14} className="launch-switch-chevron" />
      </button>
      <h1 className="launch-title" title={profile.name}>
        {profile.name}
      </h1>
      <ul className="launch-chips" aria-label={t("home.launch.details")}>
        <li>{t("home.launch.javaEdition", { version: profile.gameVersion })}</li>
        <li>
          {loaderLabel(profile.loader)}
          {profile.loaderVersion ? ` ${profile.loaderVersion}` : ""}
        </li>
        {profile.modpack && <li>{profile.modpack.title}</li>}
        <li className={`launch-status ${stateClass}`}>
          <span className="state-dot" />
          {blocked ? t("home.chip.blocked") : stateChip(t, state, missing)}
        </li>
      </ul>
      <div className="launch-actions">
        <button
          type="button"
          className={`play-btn ${tone}`}
          onClick={primary}
          disabled={!!blocked || live.state === "launching"}
          aria-label={t("home.play.buttonLabel", { action: label, name: profile.name })}
        >
          {spinning ? <Spinner size={18} /> : icon && <Icon name={icon} size={18} />}
          <span>{label}</span>
        </button>
        <IconButton ref={moreRef} icon="more" label={t("home.launch.actions")} variant="solid" className="launch-more" aria-haspopup="menu" aria-expanded={more} onClick={() => setMore((m) => !m)} />
      </div>
      {busy && <Progress value={state.progress ?? null} label={t("home.launch.progress")} className="launch-progress" />}
      <p className={`launch-state ${stateClass} ${showCaption ? "" : "is-quiet"}`} role="status" aria-live="polite">
        <span className="launch-state-text">{showCaption ? caption : ""}</span>
        {state.demo && state.state === "running" && <Badge tone="warning">{t("home.launch.demo")}</Badge>}
        {state.state === "error" && (
          <button type="button" className="launch-link" onClick={() => navigate("profiles", `${profile.id}:logs`)}>
            {t("home.launch.viewLogs")}
          </button>
        )}
      </p>

      <Popover anchor={switchRef} open={switcher} onClose={() => setSwitcher(false)} placement="top-start" label={t("home.switcher.label")} className="profile-pop">
        <Menu
          onClose={() => setSwitcher(false)}
          items={[
            { type: "heading", label: t("home.switcher.heading") },
            ...store.profiles.map((p) => ({
              label: (
                <span className="profile-row">
                  <img src={versionMeta(p.gameVersion).thumb} alt="" />
                  <span>
                    <strong>{p.name}</strong>
                    <span className="faint">{t("home.switcher.profileMeta", { version: p.gameVersion, loader: loaderLabel(p.loader) })}</span>
                  </span>
                </span>
              ),
              active: p.id === profile.id,
              onSelect: () => void store.selectProfile(p.id),
            })),
            { type: "separator" },
            { label: t("home.switcher.newProfile"), icon: <Icon name="plus" size={15} />, onSelect: () => navigate("profiles", "new") },
            { label: t("home.switcher.manage"), icon: <Icon name="layers" size={15} />, onSelect: () => navigate("profiles") },
          ]}
        />
      </Popover>
      <Popover anchor={moreRef} open={more} onClose={() => setMore(false)} placement="top-start" label={t("home.launch.actions")}>
        <Menu
          onClose={() => setMore(false)}
          items={[
            { label: t("home.actions.openProfile"), icon: <Icon name="layers" size={15} />, onSelect: () => navigate("profiles", `${profile.id}:content`) },
            { label: t("home.actions.addContent"), icon: <Icon name="library" size={15} />, onSelect: () => navigate("library", profile.id) },
            ...(profile.installed && !blocked
              ? [{ label: t("home.actions.repair"), icon: <Icon name="wrench" size={15} />, disabled: isBusy(state) || state.state === "running", onSelect: () => void run("install", () => api.installProfile(profile.id, true)) }]
              : []),
            { label: t("home.actions.openFolder"), icon: <Icon name="folder" size={15} />, onSelect: () => void api.revealFolder("profile", profile.id) },
          ]}
        />
      </Popover>
    </section>
  );
}

/** A release's own banner, by version. A release without one keeps the sparks picture beside its text. */
const RELEASE_BANNERS: Record<string, string | undefined> = { "1.0.1": banner101 };

/**
 * Home's news is Flint's own updates only (owner, 1.0.1), newest first: the releases this build ships notes for, shown at
 * once, plus any newer ones from Flint's public release feed once it arrives (lib/newsLinks.ts `newsReleases`; without
 * the feed, this build's own notes stay). The latest is featured with its banner when it has one; two more follow.
 * Minecraft's patch notes aren't shown.
 */
function NewsSection() {
  const t = useT();
  const [feed, setFeed] = useState<FeedRelease[] | null>(null);
  useEffect(() => {
    let live = true;
    void api
      .news()
      .then((r) => live && setFeed(r.entries))
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, []);
  return (
    <section className="side-section side-news" aria-labelledby="news-title">
      <header>
        <h2 id="news-title">{t("home.news.title")}</h2>
      </header>
      {newsReleases(RELEASE_NOTES, feed).map((note, i) => {
        const banner = i === 0 ? RELEASE_BANNERS[note.version] : undefined;
        return (
          <NewsItem
            key={note.version}
            flint
            featured={!!banner}
            href={releasePage(note)}
            label={t("home.news.openFlint", { version: note.version })}
            art={
              banner ? (
                // A release banner is always shown whole (16:9, never cropped): its logo is top left and its text runs
                // down the left half.
                <span className="news-art is-banner" aria-hidden="true">
                  <img src={banner} alt="" width={1920} height={1080} decoding="async" />
                </span>
              ) : (
                <span className="news-art" aria-hidden="true">
                  <img src={flintSparks} alt="" decoding="async" />
                  <img className="news-art-mark" src={flintMark} alt="" />
                </span>
              )
            }
            title={`Flint ${note.version} · ${note.title}`}
            // A date without a time is that day where the player is, not midnight UTC (the day before in the Americas).
            meta={`Flint · ${shortDate(`${note.date}T12:00:00`)}`}
            description={note.summary}
          />
        );
      })}
    </section>
  );
}

/**
 * One news item: picture, title (two lines at most), source and date, and a line of its description. With a page to
 * open it is a button (pointer, hover and press feedback, keyboard focus, Enter or Space) that opens it in the browser.
 * A featured item (a release with its own banner) puts its picture above the text, the news panel's full width.
 */
function NewsItem({ href, label, art, title, meta, description, flint = false, featured = false }: { href: string | null; label: string; art: ReactNode; title: string; meta: string; description: string; flint?: boolean; featured?: boolean }) {
  const kind = `${flint ? "is-flint" : ""} ${featured ? "is-featured" : ""}`;
  const body = (
    <>
      {art}
      <span className="news-text">
        <strong>{title}</strong>
        <span className="news-meta">{meta}</span>
        {description && <span className="news-desc">{description}</span>}
      </span>
      {href && <Icon name="external" size={13} className="news-go" />}
    </>
  );
  if (!href) return <article className={`news-item ${kind}`}>{body}</article>;
  return (
    <button type="button" className={`news-item is-link ${kind}`} title={title} aria-label={label} onClick={() => (sound.play("tap"), void api.openExternal(href))}>
      {body}
    </button>
  );
}

/** Friends at a glance (how many are online; opens Friends), plus the community link. */
function FriendsRow({ navigate }: { navigate: Navigate }) {
  const t = useT();
  const info = useStoreSelect((s) => s.info);
  const { snapshot } = useFriends();
  const online = snapshot.phase === "ready" ? snapshot.friends.filter((f) => f.status !== "offline").length : 0;
  return (
    <section className="side-friends" aria-label={t("home.friends.label")}>
      <button type="button" className="side-friends-text side-friends-open" onClick={() => navigate("friends")}>
        <Icon name="users" size={15} />
        {online > 0 ? t("home.friends.online", { count: online }) : t("home.friends.label")}
      </button>
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
