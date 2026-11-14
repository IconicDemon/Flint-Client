import { Suspense, lazy, useEffect, useLayoutEffect, useMemo, useRef, useState, type CSSProperties, type KeyboardEvent as ReactKeyboardEvent } from "react";
import curseforgeLogo from "../../assets/launchers/curseforge.svg";
import modrinthLogo from "../../assets/launchers/modrinth.png";
import { api, errorText, type Category, type InstalledItem, type LibraryHit, type LibraryKind, type LibraryResults, type LibrarySearchParams, type Profile } from "../../lib/api";
import { prefetchNext, rememberSource, sessionSource, SOURCE_NAME, SOURCES, unavailableOf, type ContentSource } from "../../lib/contentSource";
import { ago, compact } from "../../lib/format";
import { useT, type MessageKey } from "../../lib/i18n";
import { dependentsOf, installedByProject, uninstallItems, withoutItems } from "../../lib/libraryUninstall";
import { removableWhileRunning } from "../../lib/livePacks";
import { useSettings } from "../../lib/settings";
import { sound } from "../../lib/sound";
import { useStoreSelect } from "../../lib/store";
import { loaderLabel, versionMeta } from "../../lib/versions";
import { Button, EmptyState, IconButton, Segmented } from "../../ui/controls";
import { FlintMark } from "../../ui/FlintMark";
import { Icon, type IconName } from "../../ui/Icon";
import { Dropdown, Menu, Modal, Popover, useToast } from "../../ui/overlays";
import type { Navigate } from "../shell/Shell";
import { PartnersDirectory } from "../partners/PartnersDirectory";
import { InstallDialog, ProjectIcon, type InstallTarget } from "./InstallDialog";
import { ProjectDrawer, UninstallButton, type UninstallAction } from "./ProjectDrawer";

const FlintAI = lazy(() => import("./FlintAI"));

type Tab = LibraryKind | "partners";

const TABS: { id: Tab; label: MessageKey; icon: IconName }[] = [
  { id: "modpack", label: "library.tabs.modpacks", icon: "package" },
  { id: "mod", label: "library.tabs.mods", icon: "extension" },
  { id: "resourcepack", label: "library.tabs.resourcePacks", icon: "palette" },
  { id: "datapack", label: "library.tabs.dataPacks", icon: "braces" },
  { id: "shader", label: "library.tabs.shaders", icon: "sunset" },
  { id: "partners", label: "library.tabs.partners", icon: "handshake" },
];

/** The search box's hint and the empty state's message, by kind. */
const SEARCH_HINT: Record<LibraryKind, MessageKey> = {
  modpack: "library.search.modpack",
  mod: "library.search.mod",
  resourcepack: "library.search.resourcepack",
  datapack: "library.search.datapack",
  shader: "library.search.shader",
};
const NONE_FOR: Record<LibraryKind, MessageKey> = {
  modpack: "library.results.noneFor.modpack",
  mod: "library.results.noneFor.mod",
  resourcepack: "library.results.noneFor.resourcepack",
  datapack: "library.results.noneFor.datapack",
  shader: "library.results.noneFor.shader",
};

/** `curseforge`: the label there when it differs (CurseForge has no follows; that slot sorts by popularity). */
const SORTS: { value: LibrarySearchParams["index"]; label: MessageKey; curseforge?: MessageKey }[] = [
  { value: "relevance", label: "library.sort.relevance" },
  { value: "downloads", label: "library.sort.downloads" },
  { value: "follows", label: "library.sort.follows", curseforge: "library.sort.popularity" },
  { value: "updated", label: "library.sort.updated" },
  { value: "newest", label: "library.sort.newest" },
];

const SOURCE_LOGO: Record<ContentSource, string> = { modrinth: modrinthLogo, curseforge: curseforgeLogo };

/** Searches wait this long after the last key press; a page change or filter goes at once. */
const SEARCH_DELAY = 220;
/** The next page is fetched ahead once the player has stayed on a page this long (so paging and typing don't double requests). */
const PREFETCH_AFTER = 700;

const PAGE = 24;
/** How many result pages the Library keeps for this session (the oldest go first). */
const RESULTS_KEPT = 60;
/** The Library's content kinds, in tab order (Partnerships isn't one). */
const KINDS: LibraryKind[] = ["modpack", "mod", "resourcepack", "datapack", "shader"];
const ANY = "__any__";
const NONE: string[] = [];
const NO_ITEMS: InstalledItem[] = [];

/** An Uninstall waiting for its confirm: what goes, from which profile. */
type Uninstall = { profileId: string; projectId: string; title: string; items: InstalledItem[] };

export default function LibraryPage({ navigate, target }: { navigate: Navigate; target?: string }) {
  const t = useT();
  // Only what this page shows: download progress elsewhere in the app doesn't re-render the results.
  const store = useStoreSelect((s) => ({ profiles: s.profiles, selectedId: s.selectedId, selected: s.selected, contentRevision: s.contentRevision, touchContent: s.touchContent }));
  const toast = useToast();
  // Settings → General → Flint Assist: when off, the Library is plain search with no AI entry point.
  const { flintAssist } = useSettings().settings;
  const initialProfile = target && store.profiles.some((p) => p.id === target.split(":")[0]) ? target.split(":")[0] : store.selectedId ?? ANY;
  const initialTab = (target?.split(":")[1] as Tab | undefined) ?? "mod";
  const [tab, setTab] = useState<Tab>(TABS.some((item) => item.id === initialTab) ? initialTab : "mod");
  // The tabs' sliding highlight: placed under the selected tab, and moved (not faded) when another is chosen. It appears
  // in place the first time, then slides; it follows the tabs when the window or the language changes their widths.
  const tabsRef = useRef<HTMLElement>(null);
  const [thumb, setThumb] = useState<{ x: number; w: number; ready: boolean } | null>(null);
  useLayoutEffect(() => {
    const nav = tabsRef.current;
    if (!nav) return;
    const place = () => {
      const active = nav.querySelector<HTMLElement>('.lib-tab[aria-selected="true"]');
      setThumb((prev) => (active ? { x: active.offsetLeft, w: active.offsetWidth, ready: prev !== null } : null));
    };
    place();
    const resized = new ResizeObserver(place);
    resized.observe(nav);
    return () => resized.disconnect();
  }, [tab, t]);
  const [profileId, setProfileId] = useState<string>(initialProfile);
  // Modrinth or CurseForge, kept for this run of the app. Switching keeps the tab, the search and the profile.
  const [source, pickSource] = useState<ContentSource>(sessionSource);
  const setSource = (next: ContentSource) => {
    rememberSource(next);
    if (next === source) return;
    pickSource(next);
    // The other site's results don't stand in while its own load: placeholders show instead.
    setResults(null);
  };
  const [query, setQuery] = useState("");
  const [debounced, setDebounced] = useState("");
  const [sort, setSort] = useState<LibrarySearchParams["index"]>("relevance");
  const [view, setView] = useState<"grid" | "list">(() => (localStorage.getItem("flint-library-view") as "grid" | "list") ?? "grid");
  // Category picks belong to a tab and a source (the two sites' categories differ), and the page to one set of filters:
  // both are derived while rendering (not reset by an effect afterwards), so a change sends one search, not a stale one first.
  const [categoryPick, setCategoryPick] = useState<{ tab: Tab; source: ContentSource; list: string[] }>({ tab, source, list: NONE });
  const categories = categoryPick.tab === tab && categoryPick.source === source ? categoryPick.list : NONE;
  const setCategories = (list: string[]) => setCategoryPick({ tab, source, list });
  const filters = [debounced, sort, categories.join(","), tab, profileId, source].join("\n");
  const [pageAt, setPageAt] = useState({ filters, page: 0 });
  const page = pageAt.filters === filters ? pageAt.page : 0;
  const setPage = (n: number) => setPageAt({ filters, page: n });
  const [results, setResults] = useState<LibraryResults | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);
  const [tags, setTags] = useState<Category[]>([]);
  /** CurseForge's categories, asked for the first time CurseForge is picked (null until then, or to ask again). */
  const [cfTags, setCfTags] = useState<Category[] | null>(null);
  const [open, setOpen] = useState<LibraryHit | null>(null);
  /** The kind of the project open in the drawer when it came from Flint AI rather than this tab. */
  const [openKind, setOpenKind] = useState<LibraryKind | null>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const [installing, setInstalling] = useState<InstallTarget | null>(null);
  /** The chosen profile's installed items, kept with the profile they were read for. */
  const [owned, setOwned] = useState<{ profileId: string; items: InstalledItem[] } | null>(null);
  const [uninstall, setUninstall] = useState<Uninstall | null>(null);
  const [uninstalling, setUninstalling] = useState<string | null>(null);
  const [assisting, setAssisting] = useState(false);
  const request = useRef(0);
  /** Results seen this session, by site, tab, search and profile: shown at once when they come back into view. */
  const resultsCache = useRef(new Map<string, LibraryResults>());
  /** The site and tab whose results are on screen (another's never stand in while one loads). */
  const shownView = useRef("");
  /** The sites and profiles whose other tabs were already loaded ahead. */
  const warmed = useRef(new Set<string>());
  const listRef = useRef<HTMLDivElement>(null);

  const profile: Profile | null = profileId === ANY ? null : store.profiles.find((p) => p.id === profileId) ?? null;
  /** Flint AI always works for one profile: the one picked here, else the one selected for Play. */
  const aiProfile = profile ?? store.selected ?? null;
  const kind = tab === "partners" ? null : tab;
  const running = useStoreSelect((s) => !!profile && s.states[profile.id]?.state === "running");
  // Only the chosen profile's own list counts: while another profile's loads, nothing shows as installed (or uninstallable).
  const ownedItems = profile && owned?.profileId === profile.id ? owned.items : NO_ITEMS;
  const byProject = useMemo(() => installedByProject(ownedItems), [ownedItems]);

  useEffect(() => localStorage.setItem("flint-library-view", view), [view]);

  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(query.trim()), SEARCH_DELAY);
    return () => window.clearTimeout(timer);
  }, [query]);

  useEffect(() => {
    void api
      .libraryTags()
      .then((all) => setTags(all.categories))
      .catch(() => setTags([]));
  }, []);

  useEffect(() => {
    if (source !== "curseforge" || cfTags !== null) return;
    let live = true;
    void api
      .curseforgeCategories()
      .then((all) => live && setCfTags(all.categories))
      .catch(() => live && setCfTags([]));
    return () => {
      live = false;
    };
  }, [source, cfTags]);

  const revision = profile ? store.contentRevision[profile.id] ?? 0 : 0;
  useEffect(() => {
    if (!profile) return setOwned(null);
    const profileId = profile.id;
    let live = true;
    void api
      .content(profileId)
      .then((c) => {
        if (!live) return;
        setOwned({ profileId, items: [...c.mods, ...c.resourcepacks, ...c.shaders, ...c.datapacks] });
      })
      .catch(() => {});
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- keyed on the profile's id: a profile list reload gives new objects for the same profile
  }, [profile?.id, revision]);

  useEffect(() => {
    if (!kind) return;
    const id = ++request.current;
    const paramsFor = (k: LibraryKind, offset: number, picked: string[]): LibrarySearchParams => ({
      kind: k,
      query: debounced,
      index: sort,
      offset,
      limit: PAGE,
      gameVersion: profile && k !== "modpack" ? profile.gameVersion : null,
      loader: profile && k !== "modpack" ? profile.loader : k === "modpack" ? "fabric" : null,
      categories: picked,
    });
    const keyOf = (p: LibrarySearchParams) =>
      [source, p.kind, p.query, p.index, p.offset, p.categories.join(","), p.gameVersion ?? "", p.loader ?? ""].join("|");
    const params = paramsFor(kind, page * PAGE, categories);
    const key = keyOf(params);
    // What was already seen this session shows at once (and is checked again quietly); another tab's or site's results
    // never stand in while this one loads, so a switch without a copy shows placeholders, not the wrong list dimmed.
    const cached = resultsCache.current.get(key);
    const view = `${source}|${kind}`;
    if (cached) {
      setResults(cached);
      setLoading(false);
    } else {
      if (shownView.current !== view) setResults(null);
      setLoading(true);
    }
    shownView.current = view;
    setError(null);
    const search = source === "curseforge" ? api.curseforgeSearch : api.librarySearch;
    const remember = (k: string, r: LibraryResults) => {
      if (unavailableOf(r)) return;
      const cache = resultsCache.current;
      cache.delete(k);
      cache.set(k, r);
      if (cache.size > RESULTS_KEPT) cache.delete(cache.keys().next().value!);
    };
    let ahead = 0;
    let others = 0;
    // A newer search makes this one's answer stale: it's dropped when it comes (request.current moved on).
    void search(params)
      .then((r) => {
        remember(key, r);
        if (id !== request.current) return;
        setResults(r);
        if (!cached) listRef.current?.scrollTo({ top: 0 });
        if (unavailableOf(r)) return;
        // The next page is fetched ahead into the backend's short cache, so Next answers at once.
        if (prefetchNext(r.total, page, PAGE)) {
          ahead = window.setTimeout(() => id === request.current && void search({ ...params, offset: (page + 1) * PAGE }).catch(() => {}), PREFETCH_AFTER);
        }
        // The other tabs' first page, once per site and profile, so the first switch to each is instant too.
        const warm = `${source}|${profile?.id ?? ANY}|${debounced}|${sort}`;
        if (page === 0 && !warmed.current.has(warm)) {
          warmed.current.add(warm);
          const rest = KINDS.filter((k) => k !== kind).map((k) => paramsFor(k, 0, NONE)).filter((p) => !resultsCache.current.has(keyOf(p)));
          others = window.setTimeout(() => {
            rest.forEach((p, i) => window.setTimeout(() => void search(p).then((got) => remember(keyOf(p), got)).catch(() => {}), i * 150));
          }, PREFETCH_AFTER);
        }
      })
      .catch((e) => id === request.current && !cached && setError(errorText(e)))
      .finally(() => id === request.current && setLoading(false));
    return () => {
      window.clearTimeout(ahead);
      window.clearTimeout(others);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- keyed on what the search uses, not the profile object, so a profile list reload doesn't search again
  }, [kind, source, debounced, sort, page, categories, profile?.id, profile?.gameVersion, profile?.loader, retry]);

  const kindTags = useMemo(() => {
    if (!kind) return [];
    if (source === "curseforge") return (cfTags ?? []).filter((tag) => tag.projectType === kind);
    const type = tags.some((tag) => tag.projectType === kind) ? kind : "mod";
    return tags.filter((tag) => tag.projectType === type && tag.header !== "resolutions" && !["fabric", "forge", "neoforge", "quilt", "iris", "optifine", "canvas"].includes(tag.name));
  }, [tags, cfTags, kind, source]);
  const unavailable = source === "curseforge" ? unavailableOf(results) : null;
  const tryAgain = () => {
    if (source === "curseforge" && cfTags?.length === 0) setCfTags(null);
    setRetry((r) => r + 1);
  };

  const pages = results ? Math.max(1, Math.ceil(Math.min(results.total, 10_000) / PAGE)) : 1;

  const startInstall = (hit: LibraryHit) =>
    kind && setInstalling({ kind, projectId: hit.projectId, title: hit.title, iconUrl: hit.iconUrl });

  /** Uninstall for a card or the drawer: null when there's nothing it can remove (modpacks, a data pack in several worlds). */
  const uninstallFor = (hit: LibraryHit, k: LibraryKind | null): UninstallAction | null => {
    const items = profile ? uninstallItems(byProject, hit.projectId, k) : null;
    if (!profile || !items || !k) return null;
    return {
      locked: running && !removableWhileRunning(items[0].kind),
      busy: uninstalling === hit.projectId,
      ask: () => setUninstall({ profileId: profile.id, projectId: hit.projectId, title: hit.title, items }),
    };
  };
  // The drawer can come from Flint AI for another profile than the one picked here: Uninstall only for the picked one.
  const drawerUninstall = open && (!openKind || aiProfile?.id === profile?.id) ? uninstallFor(open, openKind ?? kind) : null;

  /** The same removal as the profile page's Remove: the files go from this profile only. */
  const confirmUninstall = async (job: Uninstall) => {
    setUninstall(null);
    setUninstalling(job.projectId);
    try {
      await api.removeContent(
        job.profileId,
        job.items.map((i) => ({ kind: i.kind, fileName: i.fileName, world: i.world })),
      );
      setOwned((o) => (o && o.profileId === job.profileId ? { ...o, items: withoutItems(o.items, job.items) } : o));
      toast({ tone: "success", title: t("workspace.toast.removedOne", { name: job.title }) });
      store.touchContent(job.profileId);
    } catch (e) {
      toast({ tone: "danger", title: t("workspace.toast.failed"), body: errorText(e) });
    } finally {
      setUninstalling(null);
    }
  };
  const uninstallDependents = uninstall ? dependentsOf(ownedItems, uninstall.items) : [];

  return (
    <div className="page-body library">
      <header className="lib-head">
        <div>
          <h1>{t("library.title")}</h1>
          <p className="muted">{t(tab === "partners" ? "partners.directory.intro" : source === "curseforge" ? "library.subtitleCurseForge" : "library.subtitle")}</p>
        </div>
        {/* Partnerships installs nothing: no profile to pick there. */}
        {kind && (
          <div className="lib-head-actions">
            <SourcePicker value={source} onChange={setSource} />
            <ProfilePicker profiles={store.profiles} value={profileId} onChange={setProfileId} kind={kind} />
          </div>
        )}
      </header>

      <nav ref={tabsRef} className="lib-tabs" role="tablist" aria-label={t("library.tabs.label")}>
        {thumb && (
          <span
            className={`lib-tab-thumb ${thumb.ready ? "is-ready" : ""} ${tab === "partners" ? "is-partner" : ""}`}
            style={{ width: thumb.w, transform: `translateX(${thumb.x}px)` }}
            aria-hidden="true"
          />
        )}
        {TABS.map((item) => (
          <button
            key={item.id}
            type="button"
            role="tab"
            aria-selected={tab === item.id}
            className={`lib-tab ${tab === item.id ? "is-active" : ""} ${item.id === "partners" ? "is-partner" : ""}`}
            onClick={() => setTab(item.id)}
          >
            <Icon name={item.icon} size={16} />
            {t(item.label)}
          </button>
        ))}
      </nav>

      {tab === "partners" ? (
        <div className="lib-results lib-partners">
          <PartnersDirectory navigate={navigate} />
        </div>
      ) : (
        <>
          <div className="lib-toolbar">
            <label className="lib-search">
              <Icon name="search" size={16} />
              <input
                ref={searchRef}
                type="search"
                placeholder={kind ? t(SEARCH_HINT[kind]) : undefined}
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                aria-label={t(source === "curseforge" ? "library.search.labelCurseForge" : "library.search.label")}
                spellCheck={false}
              />
              {query && <IconButton icon="x" size="sm" label={t("library.search.clear")} onClick={() => setQuery("")} />}
            </label>
            {flintAssist && (
              <button
                type="button"
                className="lib-ai"
                aria-expanded={assisting}
                disabled={!aiProfile}
                title={t(aiProfile ? "library.ai.hint" : "library.ai.needsProfile")}
                onClick={() => (sound.play(assisting ? "tap" : "open"), setAssisting((a) => !a))}
              >
                <FlintMark size={20} />
                {t("library.ai.button")}
              </button>
            )}
            <CategoryFilter tags={kindTags} value={categories} onChange={setCategories} />
            <Dropdown
              label={t("library.sort.label")}
              value={sort}
              onChange={setSort}
              options={SORTS.map((s) => ({ value: s.value, label: t(source === "curseforge" && s.curseforge ? s.curseforge : s.label) }))}
              className="lib-sort"
            />
            <Segmented
              label={t("library.view.label")}
              value={view}
              onChange={setView}
              options={[
                { value: "grid", label: t("library.view.grid") },
                { value: "list", label: t("library.view.list") },
              ]}
            />
          </div>

          <div className="lib-context">
            {profile && kind !== "modpack" ? (
              <span>
                {t.rich(kind === "datapack" ? "library.context.dataPacks" : "library.context.results", { version: <strong>Minecraft {profile.gameVersion}</strong> })}
                {kind === "mod" || kind === "shader" ? ` · ${kind === "shader" ? "Iris" : loaderLabel(profile.loader)}` : ""}
              </span>
            ) : kind === "modpack" ? (
              <span>{t("library.context.modpacks")}</span>
            ) : (
              <span>{t("library.context.anyVersion")}</span>
            )}
            {categories.length > 0 && (
              <button type="button" className="lib-clear" onClick={() => setCategories([])}>
                {t("library.filters.clear", { count: categories.length })}
              </button>
            )}
            {results?.stale && <span className="lib-stale">{t("library.results.offline")}</span>}
            {results?.staging && <span className="lib-stale">{t("library.results.staging")}</span>}
            {results && !unavailable && <span className="lib-count">{t("library.results.count", { total: compact(results.total) })}</span>}
          </div>

          <div className="lib-results" ref={listRef}>
            {flintAssist && assisting && aiProfile && (
              <Suspense fallback={null}>
                <FlintAI
                  key={aiProfile.id}
                  profile={aiProfile}
                  onClose={() => setAssisting(false)}
                  onInspect={(hit, k) => (setOpenKind(k), setOpen(hit))}
                  onBrowse={(q) => {
                    setAssisting(false);
                    if (q) setQuery(q.slice(0, 80));
                    searchRef.current?.focus();
                  }}
                />
              </Suspense>
            )}
            {error ? (
              <EmptyState
                icon="wifiOff"
                title={t(source === "curseforge" ? "library.curseforge.unreachable" : "library.results.unreachable")}
                body={error}
                action={
                  <Button icon="refresh" onClick={tryAgain}>
                    {t("common.tryAgain")}
                  </Button>
                }
              />
            ) : unavailable === "ornithe" ? (
              // CurseForge doesn't mark 1.8.9's Ornithe builds, so it offers no 1.8.9 mods (see the launcher's ornithe.rs).
              <EmptyState
                icon="info"
                title={t("library.curseforge.ornithe")}
                body={t("library.curseforge.ornitheBody")}
                action={
                  <Button variant="ghost" onClick={() => setSource("modrinth")}>
                    {t("library.curseforge.useModrinth")}
                  </Button>
                }
              />
            ) : unavailable ? (
              // CurseForge can't be asked right now (Flint's proxy isn't up, offline, or busy): a calm note; Modrinth still works.
              <EmptyState
                icon={unavailable === "offline" ? "wifiOff" : unavailable === "busy" ? "clock" : "info"}
                title={t("library.curseforge.unavailable")}
                body={
                  unavailable === "busy"
                    ? t("library.curseforge.busy", { seconds: results?.retryIn ?? 60 })
                    : unavailable === "offline"
                      ? t("library.curseforge.offline")
                      : t("library.curseforge.unavailableBody")
                }
                action={
                  <span className="lib-unavailable-actions">
                    <Button icon="refresh" onClick={tryAgain} loading={loading}>
                      {t("common.tryAgain")}
                    </Button>
                    <Button variant="ghost" onClick={() => setSource("modrinth")}>
                      {t("library.curseforge.useModrinth")}
                    </Button>
                  </span>
                }
              />
            ) : loading && !results ? (
              <Skeletons view={view} />
            ) : results && results.hits.length === 0 ? (
              <EmptyState
                icon="search"
                title={debounced ? t("library.results.noMatch", { query: debounced }) : t("library.results.empty")}
                body={profile && kind ? t(NONE_FOR[kind], { version: profile.gameVersion }) : t("library.results.tryDifferent")}
              />
            ) : (
              <div className={`lib-${view} ${loading ? "is-refreshing" : ""}`}>
                {results?.hits.map((hit) => (
                  <ResultCard
                    key={hit.projectId}
                    hit={hit}
                    view={view}
                    installed={byProject.has(hit.projectId)}
                    canInstall={kind === "modpack" || !!profile}
                    uninstall={uninstallFor(hit, kind)}
                    onOpen={() => (setOpenKind(null), setOpen(hit))}
                    onInstall={() => startInstall(hit)}
                  />
                ))}
              </div>
            )}
          </div>

          {results && results.total > PAGE && (
            <footer className="lib-pager">
              <Button size="sm" icon="chevronLeft" disabled={page === 0 || loading} onClick={() => setPage(page - 1)}>
                {t("library.pager.previous")}
              </Button>
              <span className="faint">{t("library.pager.page", { page: page + 1, pages: compact(pages) })}</span>
              <Button size="sm" iconRight="chevronRight" disabled={page + 1 >= pages || loading} onClick={() => setPage(page + 1)}>
                {t("library.pager.next")}
              </Button>
            </footer>
          )}
        </>
      )}

      <ProjectDrawer
        hit={open}
        kind={openKind ?? kind}
        profile={openKind ? aiProfile : profile}
        installed={open ? byProject.has(open.projectId) : false}
        uninstall={drawerUninstall}
        onClose={() => setOpen(null)}
        onInstall={(next) => setInstalling(next)}
        onOpenProfile={(id) => navigate("profiles", `${id}:content`)}
      />
      <InstallDialog target={installing} profile={openKind ? aiProfile : profile} onClose={() => setInstalling(null)} />
      <Modal
        open={!!uninstall}
        onClose={() => setUninstall(null)}
        title={uninstall ? t("workspace.remove.titleOne", { name: uninstall.title }) : ""}
        width={460}
        footer={
          <>
            <Button variant="ghost" onClick={() => setUninstall(null)}>
              {t("common.cancel")}
            </Button>
            <Button variant="danger" icon="trash" onClick={() => uninstall && void confirmUninstall(uninstall)}>
              {t("common.remove")}
            </Button>
          </>
        }
      >
        <p className="muted modal-copy">{t("workspace.remove.body")}</p>
        {uninstallDependents.length > 0 && (
          <p className="install-warn">
            <Icon name="alert" size={15} />
            {uninstallDependents.length === 1
              ? t("workspace.remove.dependentOne", { name: uninstallDependents[0].title })
              : t("workspace.remove.dependents", { names: uninstallDependents.map((m) => m.title).join(", ") })}
          </p>
        )}
      </Modal>
    </div>
  );
}

/**
 * Where the Library's content comes from: Modrinth (the default) or CurseForge, beside the profile picker and as tall as it.
 * Two sources, so a switch rather than a menu: one click, and the highlight slides to the chosen one in its colour. A radio
 * group: the arrow keys move it too.
 */
function SourcePicker({ value, onChange }: { value: ContentSource; onChange: (source: ContentSource) => void }) {
  const t = useT();
  const index = SOURCES.indexOf(value);
  const onKey = (e: ReactKeyboardEvent<HTMLDivElement>) => {
    if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
    e.preventDefault();
    const next = SOURCES[(index + (e.key === "ArrowRight" ? 1 : SOURCES.length - 1)) % SOURCES.length];
    onChange(next);
    e.currentTarget.querySelector<HTMLElement>(`[data-source="${next}"]`)?.focus();
  };
  return (
    <div
      className="src-switch"
      role="radiogroup"
      aria-label={t("library.source.label")}
      data-active={value}
      style={{ "--src-index": index, "--src-count": SOURCES.length } as CSSProperties}
      onKeyDown={onKey}
    >
      <span className="src-thumb" aria-hidden="true" />
      {SOURCES.map((s) => (
        <button
          key={s}
          type="button"
          role="radio"
          aria-checked={s === value}
          tabIndex={s === value ? 0 : -1}
          data-source={s}
          className={`src-opt is-${s}`}
          title={t(s === "modrinth" ? "library.source.modrinthHint" : "library.source.curseforgeHint")}
          onClick={() => s !== value && onChange(s)}
        >
          <img src={SOURCE_LOGO[s]} alt="" />
          <span>{SOURCE_NAME[s]}</span>
        </button>
      ))}
    </div>
  );
}

function ProfilePicker({ profiles, value, onChange, kind }: { profiles: Profile[]; value: string; onChange: (id: string) => void; kind: LibraryKind | null }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const anchor = useRef<HTMLButtonElement>(null);
  const current = profiles.find((p) => p.id === value);
  return (
    <>
      <button ref={anchor} type="button" className="lib-profile" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((o) => !o)} disabled={kind === "modpack"}>
        {current ? <img src={versionMeta(current.gameVersion).thumb} alt="" /> : <span className="lib-profile-any"><Icon name="globe" size={16} /></span>}
        <span>
          <span className="faint">{t(kind === "modpack" ? "library.profile.modpacksCreate" : "library.profile.installingTo")}</span>
          <strong>{kind === "modpack" ? t("library.profile.newProfile") : current ? current.name : t("library.profile.none")}</strong>
        </span>
        <Icon name="chevronDown" size={15} />
      </button>
      <Popover anchor={anchor} open={open} onClose={() => setOpen(false)} placement="bottom-end" label={t("library.profile.label")} className="profile-pop">
        <Menu
          onClose={() => setOpen(false)}
          items={[
            { type: "heading", label: t("library.profile.heading") },
            ...profiles.map((p) => ({
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
              active: p.id === value,
              onSelect: () => onChange(p.id),
            })),
            { type: "separator" },
            { label: t("library.profile.noneAll"), icon: <Icon name="globe" size={15} />, active: value === ANY, onSelect: () => onChange(ANY) },
          ]}
        />
      </Popover>
    </>
  );
}

function CategoryFilter({ tags, value, onChange }: { tags: Category[]; value: string[]; onChange: (v: string[]) => void }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const anchor = useRef<HTMLButtonElement>(null);
  const groups = useMemo(() => {
    const map = new Map<string, Category[]>();
    for (const tag of tags) map.set(tag.header, [...(map.get(tag.header) ?? []), tag]);
    return [...map.entries()];
  }, [tags]);
  const toggle = (name: string) => onChange(value.includes(name) ? value.filter((v) => v !== name) : [...value, name].slice(0, 6));
  return (
    <>
      <button ref={anchor} type="button" className={`dropdown-trigger lib-filter ${value.length ? "has-value" : ""}`} aria-haspopup="dialog" aria-expanded={open} onClick={() => setOpen((o) => !o)} disabled={!tags.length}>
        <Icon name="filter" size={15} />
        <span>{value.length ? t("library.categories.selected", { count: value.length }) : t("library.categories.label")}</span>
      </button>
      <Popover anchor={anchor} open={open} onClose={() => setOpen(false)} placement="bottom-start" label={t("library.categories.label")} className="lib-filter-pop">
        {/* Category names and headers come from Modrinth's or CurseForge's own lists, in English. */}
        {groups.map(([header, list]) => (
          <section key={header}>
            <span className="eyebrow">{header}</span>
            <div className="chip-wrap">
              {list.map((tag) => (
                <button key={tag.name} type="button" className={`chip ${value.includes(tag.name) ? "is-on" : ""}`} aria-pressed={value.includes(tag.name)} onClick={() => toggle(tag.name)}>
                  {tag.label ?? tag.name.replace(/-/g, " ")}
                </button>
              ))}
            </div>
          </section>
        ))}
      </Popover>
    </>
  );
}

function ResultCard({
  hit,
  view,
  installed,
  canInstall,
  uninstall,
  onOpen,
  onInstall,
}: {
  hit: LibraryHit;
  view: "grid" | "list";
  installed: boolean;
  canInstall: boolean;
  uninstall: UninstallAction | null;
  onOpen: () => void;
  onInstall: () => void;
}) {
  const t = useT();
  const tags = (hit.categories ?? []).filter((c) => !["fabric", "forge", "neoforge", "quilt", "ornithe", "legacy-fabric", "babric", "iris", "optifine", "minecraft", "datapack"].includes(c)).slice(0, view === "grid" ? 2 : 4);
  return (
    <article className={`result result-${view}`}>
      <button type="button" className="result-open" onClick={onOpen} aria-label={t("library.card.open", { title: hit.title })}>
        {view === "grid" && (
          <span className="result-banner" style={hit.color != null ? { background: `#${hit.color.toString(16).padStart(6, "0")}` } : undefined}>
            {hit.gallery && <img src={hit.gallery} alt="" loading="lazy" />}
          </span>
        )}
        <span className="result-main">
          <ProjectIcon src={hit.iconUrl} title={hit.title} kind={hit.projectType} size={view === "grid" ? 48 : 52} />
          <span className="result-text">
            <strong>{hit.title}</strong>
            <span className="faint">{t("library.card.by", { author: hit.author })}</span>
          </span>
        </span>
        <span className="result-desc">{hit.description}</span>
        <span className="result-meta">
          <span title={t("library.card.downloads")}>
            <Icon name="download" size={13} />
            {compact(hit.downloads)}
          </span>
          {hit.follows != null && (
            <span title={t("library.card.followers")}>
              <Icon name="heart" size={13} />
              {compact(hit.follows)}
            </span>
          )}
          {hit.dateModified && <span title={t("library.card.updated")}>{ago(hit.dateModified)}</span>}
          {tags.map((tag) => (
            <span key={tag} className="result-tag">
              {tag.replace(/-/g, " ")}
            </span>
          ))}
        </span>
      </button>
      <div className="result-action">
        {installed ? (
          <>
            <span className="result-installed">
              <Icon name="check" size={14} /> {t("library.card.installed")}
            </span>
            {uninstall && <UninstallButton action={uninstall} />}
          </>
        ) : (
          <Button size="sm" variant="secondary" icon="download" onClick={onInstall} disabled={!canInstall} title={canInstall ? undefined : t("library.card.needsProfile")}>
            {t("library.card.install")}
          </Button>
        )}
      </div>
    </article>
  );
}

function Skeletons({ view }: { view: "grid" | "list" }) {
  const t = useT();
  return (
    <div className={`lib-${view}`} aria-busy="true" aria-label={t("library.results.loading")}>
      {Array.from({ length: view === "grid" ? 12 : 8 }, (_, i) => (
        <div key={i} className={`result result-${view} is-skeleton`}>
          {view === "grid" && <span className="result-banner" />}
          <span className="result-main">
            <span className="sk sk-icon" />
            <span className="result-text">
              <span className="sk sk-line" style={{ width: "60%" }} />
              <span className="sk sk-line" style={{ width: "34%" }} />
            </span>
          </span>
          <span className="sk sk-line" style={{ width: "92%" }} />
          <span className="sk sk-line" style={{ width: "70%" }} />
        </div>
      ))}
    </div>
  );
}
