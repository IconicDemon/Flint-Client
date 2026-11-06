import { Suspense, lazy, useEffect, useMemo, useRef, useState } from "react";
import { api, errorText, type Category, type LibraryHit, type LibraryKind, type LibraryResults, type LibrarySearchParams, type Profile } from "../../lib/api";
import { ago, compact } from "../../lib/format";
import { useT, type MessageKey } from "../../lib/i18n";
import { useSettings } from "../../lib/settings";
import { sound } from "../../lib/sound";
import { useStoreSelect } from "../../lib/store";
import { loaderLabel, versionMeta } from "../../lib/versions";
import { Button, EmptyState, IconButton, Segmented } from "../../ui/controls";
import { FlintMark } from "../../ui/FlintMark";
import { Icon, type IconName } from "../../ui/Icon";
import { Dropdown, Menu, Popover } from "../../ui/overlays";
import type { Navigate } from "../shell/Shell";
import { PartnersDirectory } from "../partners/PartnersDirectory";
import { InstallDialog, ProjectIcon, type InstallTarget } from "./InstallDialog";
import { ProjectDrawer } from "./ProjectDrawer";

const FlintAI = lazy(() => import("./FlintAI"));

type Tab = LibraryKind | "partners";

const TABS: { id: Tab; label: MessageKey; icon: IconName }[] = [
  { id: "modpack", label: "library.tabs.modpacks", icon: "box" },
  { id: "mod", label: "library.tabs.mods", icon: "puzzle" },
  { id: "resourcepack", label: "library.tabs.resourcePacks", icon: "image" },
  { id: "datapack", label: "library.tabs.dataPacks", icon: "database" },
  { id: "shader", label: "library.tabs.shaders", icon: "sun" },
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

const SORTS: { value: LibrarySearchParams["index"]; label: MessageKey }[] = [
  { value: "relevance", label: "library.sort.relevance" },
  { value: "downloads", label: "library.sort.downloads" },
  { value: "follows", label: "library.sort.follows" },
  { value: "updated", label: "library.sort.updated" },
  { value: "newest", label: "library.sort.newest" },
];

const PAGE = 24;
const ANY = "__any__";
const NONE: string[] = [];

export default function LibraryPage({ navigate, target }: { navigate: Navigate; target?: string }) {
  const t = useT();
  // Only what this page shows: download progress elsewhere in the app doesn't re-render the results.
  const store = useStoreSelect((s) => ({ profiles: s.profiles, selectedId: s.selectedId, selected: s.selected, contentRevision: s.contentRevision }));
  // Settings → General → Flint Assist: when off, the Library is plain search with no AI entry point.
  const { flintAssist } = useSettings().settings;
  const initialProfile = target && store.profiles.some((p) => p.id === target.split(":")[0]) ? target.split(":")[0] : store.selectedId ?? ANY;
  const initialTab = (target?.split(":")[1] as Tab | undefined) ?? "mod";
  const [tab, setTab] = useState<Tab>(TABS.some((item) => item.id === initialTab) ? initialTab : "mod");
  const [profileId, setProfileId] = useState<string>(initialProfile);
  const [query, setQuery] = useState("");
  const [debounced, setDebounced] = useState("");
  const [sort, setSort] = useState<LibrarySearchParams["index"]>("relevance");
  const [view, setView] = useState<"grid" | "list">(() => (localStorage.getItem("flint-library-view") as "grid" | "list") ?? "grid");
  // Category picks belong to a tab, and the page to one set of filters: both are derived while
  // rendering (not reset by an effect afterwards), so a change sends one search, not a stale one first.
  const [categoryPick, setCategoryPick] = useState<{ tab: Tab; list: string[] }>({ tab, list: NONE });
  const categories = categoryPick.tab === tab ? categoryPick.list : NONE;
  const setCategories = (list: string[]) => setCategoryPick({ tab, list });
  const filters = [debounced, sort, categories.join(","), tab, profileId].join("\n");
  const [pageAt, setPageAt] = useState({ filters, page: 0 });
  const page = pageAt.filters === filters ? pageAt.page : 0;
  const setPage = (n: number) => setPageAt({ filters, page: n });
  const [results, setResults] = useState<LibraryResults | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);
  const [tags, setTags] = useState<Category[]>([]);
  const [open, setOpen] = useState<LibraryHit | null>(null);
  /** The kind of the project open in the drawer when it came from Flint AI rather than this tab. */
  const [openKind, setOpenKind] = useState<LibraryKind | null>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const [installing, setInstalling] = useState<InstallTarget | null>(null);
  const [installed, setInstalled] = useState<Set<string>>(new Set());
  const [assisting, setAssisting] = useState(false);
  const request = useRef(0);
  const listRef = useRef<HTMLDivElement>(null);

  const profile: Profile | null = profileId === ANY ? null : store.profiles.find((p) => p.id === profileId) ?? null;
  /** Flint AI always works for one profile: the one picked here, else the one selected for Play. */
  const aiProfile = profile ?? store.selected ?? null;
  const kind = tab === "partners" ? null : tab;

  useEffect(() => localStorage.setItem("flint-library-view", view), [view]);

  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(query.trim()), 320);
    return () => window.clearTimeout(timer);
  }, [query]);

  useEffect(() => {
    void api
      .libraryTags()
      .then((all) => setTags(all.categories))
      .catch(() => setTags([]));
  }, []);

  const revision = profile ? store.contentRevision[profile.id] ?? 0 : 0;
  useEffect(() => {
    if (!profile) return setInstalled(new Set());
    let live = true;
    void api
      .content(profile.id)
      .then((c) => {
        if (!live) return;
        const ids = [...c.mods, ...c.resourcepacks, ...c.shaders, ...c.datapacks].map((i) => i.projectId).filter((x): x is string => !!x);
        setInstalled(new Set(ids));
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
    setLoading(true);
    setError(null);
    const params: LibrarySearchParams = {
      kind,
      query: debounced,
      index: sort,
      offset: page * PAGE,
      limit: PAGE,
      gameVersion: profile && kind !== "modpack" ? profile.gameVersion : null,
      loader: profile && kind !== "modpack" ? profile.loader : kind === "modpack" ? "fabric" : null,
      categories,
    };
    void api
      .librarySearch(params)
      .then((r) => {
        if (id !== request.current) return;
        setResults(r);
        listRef.current?.scrollTo({ top: 0 });
      })
      .catch((e) => id === request.current && setError(errorText(e)))
      .finally(() => id === request.current && setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps -- keyed on what the search uses, not the profile object, so a profile list reload doesn't search again
  }, [kind, debounced, sort, page, categories, profile?.id, profile?.gameVersion, profile?.loader, retry]);

  const kindTags = useMemo(() => {
    if (!kind) return [];
    const type = tags.some((tag) => tag.projectType === kind) ? kind : "mod";
    return tags.filter((tag) => tag.projectType === type && tag.header !== "resolutions" && !["fabric", "forge", "neoforge", "quilt", "iris", "optifine", "canvas"].includes(tag.name));
  }, [tags, kind]);

  const pages = results ? Math.max(1, Math.ceil(Math.min(results.total, 10_000) / PAGE)) : 1;

  const startInstall = (hit: LibraryHit) =>
    kind && setInstalling({ kind, projectId: hit.projectId, title: hit.title, iconUrl: hit.iconUrl });

  return (
    <div className="page-body library">
      <header className="lib-head">
        <div>
          <h1>{t("library.title")}</h1>
          <p className="muted">{t(tab === "partners" ? "partners.directory.intro" : "library.subtitle")}</p>
        </div>
        {/* Partnerships installs nothing: no profile to pick there. */}
        {kind && (
          <div className="lib-head-actions">
            <ProfilePicker profiles={store.profiles} value={profileId} onChange={setProfileId} kind={kind} />
          </div>
        )}
      </header>

      <nav className="lib-tabs" role="tablist" aria-label={t("library.tabs.label")}>
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
                aria-label={t("library.search.label")}
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
            <Dropdown label={t("library.sort.label")} value={sort} onChange={setSort} options={SORTS.map((s) => ({ value: s.value, label: t(s.label) }))} className="lib-sort" />
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
            {results && <span className="lib-count">{t("library.results.count", { total: compact(results.total) })}</span>}
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
                title={t("library.results.unreachable")}
                body={error}
                action={
                  <Button icon="refresh" onClick={() => setRetry((r) => r + 1)}>
                    {t("common.tryAgain")}
                  </Button>
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
                    installed={installed.has(hit.projectId)}
                    canInstall={kind === "modpack" || !!profile}
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
        installed={open ? installed.has(open.projectId) : false}
        onClose={() => setOpen(null)}
        onInstall={(next) => setInstalling(next)}
        onOpenProfile={(id) => navigate("profiles", `${id}:content`)}
      />
      <InstallDialog target={installing} profile={openKind ? aiProfile : profile} onClose={() => setInstalling(null)} />
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
        {/* Category names and headers come from Modrinth's tag list, in English. */}
        {groups.map(([header, list]) => (
          <section key={header}>
            <span className="eyebrow">{header}</span>
            <div className="chip-wrap">
              {list.map((tag) => (
                <button key={tag.name} type="button" className={`chip ${value.includes(tag.name) ? "is-on" : ""}`} aria-pressed={value.includes(tag.name)} onClick={() => toggle(tag.name)}>
                  {tag.name.replace(/-/g, " ")}
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
  onOpen,
  onInstall,
}: {
  hit: LibraryHit;
  view: "grid" | "list";
  installed: boolean;
  canInstall: boolean;
  onOpen: () => void;
  onInstall: () => void;
}) {
  const t = useT();
  const tags = (hit.categories ?? []).filter((c) => !["fabric", "forge", "neoforge", "quilt", "iris", "optifine", "minecraft", "datapack"].includes(c)).slice(0, view === "grid" ? 2 : 4);
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
          <span title={t("library.card.followers")}>
            <Icon name="heart" size={13} />
            {compact(hit.follows)}
          </span>
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
          <span className="result-installed">
            <Icon name="check" size={14} /> {t("library.card.installed")}
          </span>
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
