import { useEffect, useMemo, useRef, useState } from "react";
import { api, errorText } from "../../lib/api";
import { relativeTime } from "../../lib/format";
import { useT } from "../../lib/i18n";
import { useSettings } from "../../lib/settings";
import { errorKey, friendSections, giftUrl, isPlayerName, MY_STATUS_KEYS, type Friend, type FriendRequest, type MyStatus, type SearchPlayer, type SocialSnapshot } from "../../lib/social";
import { sound } from "../../lib/sound";
import { useStoreSelect } from "../../lib/store";
import { Button, EmptyState, IconButton, Spinner, Switch, Tabs, TextField } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { Menu, Modal, Popover, useToast, type MenuItem } from "../../ui/overlays";
import type { Navigate } from "../shell/Shell";
import { ChatPanel } from "./Chat";
import { FriendHead, StatusDot, useStatusText } from "./FriendHead";
import { useFriends } from "./FriendsProvider";

const FALLBACK_STORE = "https://store.flintclient.com";
let storeUrl: string | null = null;

export default function FriendsPage({ navigate, target }: { navigate: Navigate; target?: string }) {
  const t = useT();
  const toast = useToast();
  const { snapshot, loaded, setView } = useFriends();
  const account = useStoreSelect((s) => s.activeAccount);
  const reloadAccounts = useStoreSelect((s) => s.reloadAccounts);
  const [chat, setChat] = useState<string | null>(target ?? null);
  const [removing, setRemoving] = useState<Friend | null>(null);
  const [signingIn, setSigningIn] = useState(false);
  const [retrying, setRetrying] = useState(false);

  // Opening the page signs in now if the background couldn't (a session only the player may ask for).
  useEffect(() => {
    void api.socialRefresh().catch(() => {});
  }, []);
  useEffect(() => {
    setView({ open: true, chat });
    void api.socialFocus(chat).catch(() => {});
  }, [chat, setView]);
  useEffect(
    () => () => {
      setView({ open: false, chat: null });
      void api.socialFocus(null).catch(() => {});
    },
    [setView],
  );

  const friends = snapshot.friends;
  const open = chat ? (friends.find((f) => f.uuid === chat) ?? null) : null;
  // A friend removed (here or by them) closes their chat.
  useEffect(() => {
    if (chat && snapshot.phase === "ready" && !friends.some((f) => f.uuid === chat)) setChat(null);
  }, [chat, friends, snapshot.phase]);

  const actions = useMemo(
    () => ({
      open: (uuid: string) => setChat(uuid),
      pin: (friend: Friend) =>
        void api.socialPin(friend.uuid, !friend.pinned).catch((e) => toast({ tone: "danger", title: t("friends.errors.title"), body: t(errorKey(errorText(e))) })),
      gift: async (friend: Friend) => {
        storeUrl ??= (await api.storeInfo().catch(() => null))?.storeUrl ?? FALLBACK_STORE;
        await api.openExternal(giftUrl(storeUrl, friend.uuid, friend.name)).catch((e) => toast({ tone: "danger", title: t("friends.gift.failed"), body: errorText(e) }));
      },
      remove: (friend: Friend) => setRemoving(friend),
    }),
    [t, toast],
  );

  const signIn = async () => {
    setSigningIn(true);
    try {
      const added = await api.signIn();
      await reloadAccounts();
      toast({ tone: "success", title: t("shell.account.signedInAs", { name: added.username }) });
    } catch (e) {
      toast({ tone: "danger", title: t("shell.account.signInFailed"), body: errorText(e) });
    } finally {
      setSigningIn(false);
    }
  };

  const retry = async () => {
    setRetrying(true);
    await api.socialRefresh(true).catch(() => {});
    setRetrying(false);
  };

  // What the page can show: an account is needed (not the offline test identity), and the backend's first answer.
  const state = !snapshot.configured ? "notConfigured" : !account || account.qa ? "signedOut" : !loaded || snapshot.phase === "signed_out" ? "connecting" : snapshot.phase;

  if (state === "notConfigured" || state === "signedOut" || state === "unavailable" || state === "error") {
    const notice = {
      notConfigured: { icon: "users" as const, title: t("friends.notConfigured.title"), body: t("friends.notConfigured.body"), action: null },
      signedOut: {
        icon: "users" as const,
        title: t("friends.signedOut.title"),
        body: account?.qa ? t("friends.signedOut.testIdentity") : t("friends.signedOut.body"),
        action: (
          <Button variant="primary" icon="microsoft" loading={signingIn} onClick={() => void signIn()}>
            {t("friends.signedOut.button")}
          </Button>
        ),
      },
      unavailable: { icon: "users" as const, title: t("friends.unavailable.title"), body: t("friends.unavailable.body"), action: null },
      error: {
        icon: snapshot.offline ? ("wifiOff" as const) : ("alert" as const),
        title: snapshot.offline ? t("friends.offline.title") : t("friends.error.title"),
        body: t(errorKey(snapshot.error)),
        action: (
          <Button icon="refresh" loading={retrying} onClick={() => void retry()}>
            {t("common.tryAgain")}
          </Button>
        ),
      },
    }[state];
    return (
      <div className="page-body friends-notice">
        <EmptyState icon={notice.icon} title={notice.title} body={notice.body} action={notice.action ?? undefined} />
      </div>
    );
  }

  if (state === "connecting") return <FriendsSkeleton />;

  return (
    <div className="page-body friends">
      <FriendsSide snapshot={snapshot} chat={chat} actions={actions} navigate={navigate} />
      <section className="chat" aria-label={open ? t("friends.chat.label", { name: open.name }) : t("friends.title")}>
        {open && snapshot.me ? (
          <ChatPanel key={open.uuid} friend={open} me={snapshot.me} onGift={() => void actions.gift(open)} onPin={() => actions.pin(open)} />
        ) : friends.length === 0 ? (
          <div className="chat-empty">
            <EmptyState icon="userPlus" title={t("friends.empty.title")} body={t("friends.empty.body")} />
          </div>
        ) : (
          <div className="chat-empty">
            <EmptyState icon="chat" title={t("friends.chat.noneTitle")} body={t("friends.chat.noneBody")} />
          </div>
        )}
      </section>
      <Modal
        open={!!removing}
        onClose={() => setRemoving(null)}
        title={t("friends.remove.title", { name: removing?.name ?? "" })}
        width={440}
        footer={
          <>
            <Button variant="ghost" onClick={() => setRemoving(null)}>
              {t("common.cancel")}
            </Button>
            <Button
              variant="danger"
              onClick={() => {
                const friend = removing;
                setRemoving(null);
                if (!friend) return;
                if (chat === friend.uuid) setChat(null);
                void api.socialRemove(friend.uuid).catch((e) => toast({ tone: "danger", title: t("friends.errors.title"), body: t(errorKey(errorText(e))) }));
              }}
            >
              {t("friends.remove.confirm")}
            </Button>
          </>
        }
      >
        <p className="muted">{t("friends.remove.body", { name: removing?.name ?? "" })}</p>
      </Modal>
    </div>
  );
}

type Actions = { open: (uuid: string) => void; pin: (friend: Friend) => void; gift: (friend: Friend) => Promise<void>; remove: (friend: Friend) => void };

function FriendsSide({ snapshot, chat, actions, navigate }: { snapshot: SocialSnapshot; chat: string | null; actions: Actions; navigate: Navigate }) {
  const t = useT();
  const [tab, setTab] = useState<"friends" | "requests">("friends");
  const [query, setQuery] = useState("");
  const searching = query.trim().length > 0;
  return (
    <aside className="friends-side">
      <h1>{t("friends.title")}</h1>
      {snapshot.me && <MeCard me={snapshot.me} offline={snapshot.offline} navigate={navigate} />}
      <TextField
        icon="userPlus"
        className="friends-search"
        placeholder={t("friends.search.placeholder")}
        aria-label={t("friends.search.placeholder")}
        value={query}
        maxLength={16}
        spellCheck={false}
        onChange={(e) => setQuery(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Escape" && query) {
            e.stopPropagation();
            setQuery("");
          }
        }}
      />
      {searching ? (
        <SearchResults query={query.trim()} snapshot={snapshot} onOpen={(uuid) => (setQuery(""), actions.open(uuid))} />
      ) : (
        <>
          <Tabs
            value={tab}
            onChange={setTab}
            label={t("friends.title")}
            tabs={[
              { value: "friends", label: t("friends.tabs.friends") },
              { value: "requests", label: t("friends.tabs.requests"), count: snapshot.incoming.length || undefined },
            ]}
          />
          <div className="friends-list scroll">{tab === "friends" ? <FriendList friends={snapshot.friends} chat={chat} actions={actions} stale={snapshot.stale} /> : <Requests snapshot={snapshot} />}</div>
        </>
      )}
    </aside>
  );
}

const STATUSES: MyStatus[] = ["online", "busy", "away", "invisible"];

function MeCard({ me, offline, navigate }: { me: NonNullable<SocialSnapshot["me"]>; offline: boolean; navigate: Navigate }) {
  const t = useT();
  const toast = useToast();
  const { settings, update } = useSettings();
  const statusAnchor = useRef<HTMLButtonElement>(null);
  const prefsAnchor = useRef<HTMLButtonElement>(null);
  const [picking, setPicking] = useState(false);
  const [prefs, setPrefs] = useState(false);
  const change = (patch: { status?: MyStatus; allowRequests?: boolean }) =>
    void api.socialSettings(patch).catch((e) => toast({ tone: "danger", title: t("friends.errors.title"), body: t(errorKey(errorText(e))) }));
  return (
    <div className="me-card">
      <FriendHead uuid={me.uuid} name={me.name} size={40} status={offline ? "invisible" : me.status} />
      <div className="me-text">
        <strong title={me.name}>{me.name}</strong>
        <button ref={statusAnchor} type="button" className="me-status" aria-haspopup="menu" aria-expanded={picking} onClick={() => setPicking((o) => !o)}>
          {offline ? t("friends.offline.short") : t(MY_STATUS_KEYS[me.status])}
          <Icon name="chevronDown" size={12} />
        </button>
      </div>
      <IconButton ref={prefsAnchor} icon="sliders" size="sm" label={t("friends.settings.title")} aria-expanded={prefs} onClick={() => setPrefs((o) => !o)} />
      <Popover anchor={statusAnchor} open={picking} onClose={() => setPicking(false)} label={t("friends.status.label")} className="status-pop">
        <Menu
          onClose={() => setPicking(false)}
          items={STATUSES.map(
            (status): MenuItem => ({
              label: t(MY_STATUS_KEYS[status]),
              hint: status === "invisible" ? t("friends.status.invisibleHint") : undefined,
              icon: <StatusDot status={status} />,
              active: me.status === status,
              onSelect: () => status !== me.status && change({ status }),
            }),
          )}
        />
      </Popover>
      <Popover anchor={prefsAnchor} open={prefs} onClose={() => setPrefs(false)} placement="bottom-end" label={t("friends.settings.title")} className="friends-prefs">
        <strong className="friends-prefs-title">{t("friends.settings.title")}</strong>
        <PrefRow title={t("friends.settings.allowRequests")} desc={t("friends.settings.allowRequestsDesc")} checked={me.allowRequests} onChange={(allowRequests) => change({ allowRequests })} />
        <PrefRow title={t("friends.settings.notifyOnline")} desc={t("friends.settings.notifyOnlineDesc")} checked={settings.friendsNotifyOnline} onChange={(friendsNotifyOnline) => update({ friendsNotifyOnline })} />
        <PrefRow title={t("friends.settings.sounds")} desc={t("friends.settings.soundsDesc")} checked={settings.friendsSounds} onChange={(friendsSounds) => update({ friendsSounds })} />
        {!settings.sounds && (
          <button
            type="button"
            className="friends-prefs-note"
            onClick={() => {
              setPrefs(false);
              navigate("settings", "audio");
            }}
          >
            {t("friends.settings.soundsOff")}
          </button>
        )}
      </Popover>
    </div>
  );
}

function PrefRow({ title, desc, checked, onChange }: { title: string; desc: string; checked: boolean; onChange: (value: boolean) => void }) {
  return (
    <div className="friends-pref">
      <span>
        <strong>{title}</strong>
        <span className="faint">{desc}</span>
      </span>
      <Switch checked={checked} onChange={onChange} label={title} />
    </div>
  );
}

function SearchResults({ query, snapshot, onOpen }: { query: string; snapshot: SocialSnapshot; onOpen: (uuid: string) => void }) {
  const t = useT();
  const toast = useToast();
  const { settings } = useSettings();
  const [results, setResults] = useState<SearchPlayer[] | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [sent, setSent] = useState<Record<string, true>>({});
  const asked = useRef(0);

  useEffect(() => {
    const id = ++asked.current;
    setResults(null);
    const timer = window.setTimeout(() => {
      void api
        .socialSearch(query)
        .then((players) => id === asked.current && setResults(players))
        .catch(() => id === asked.current && setResults([]));
    }, 300);
    return () => window.clearTimeout(timer);
  }, [query]);

  const add = async (player: SearchPlayer) => {
    setBusy(player.uuid);
    try {
      const result = await api.socialRequest(player.name);
      if (settings.friendsSounds) sound.play("sent");
      if (result === "accepted") toast({ tone: "success", title: t("friends.search.nowFriends", { name: player.name }) });
      else setSent((s) => ({ ...s, [player.uuid]: true }));
    } catch (e) {
      toast({ tone: "danger", title: t("friends.search.failed", { name: player.name }), body: t(errorKey(errorText(e))) });
    } finally {
      setBusy(null);
    }
  };

  const valid = isPlayerName(query);
  const friends = new Set(snapshot.friends.map((f) => f.uuid));
  const outgoing = new Set(snapshot.outgoing.map((r) => r.uuid));
  const incoming = new Set(snapshot.incoming.map((r) => r.uuid));
  // The snapshot is newer than the search when a request was answered meanwhile.
  const relation = (p: SearchPlayer) =>
    p.relation === "self" ? "self" : friends.has(p.uuid) ? "friend" : incoming.has(p.uuid) ? "incoming" : outgoing.has(p.uuid) || sent[p.uuid] ? "outgoing" : p.relation === "friend" ? "none" : p.relation;

  return (
    <div className="friends-list scroll" aria-live="polite">
      <h2 className="friends-heading">{t("friends.search.heading")}</h2>
      {results === null ? (
        <div className="friends-searching">
          <Spinner size={14} /> {t("friends.search.searching")}
        </div>
      ) : results.length === 0 ? (
        <p className="friends-hint">{valid ? t("friends.search.notOnFlint", { name: query }) : t("friends.search.notAName")}</p>
      ) : (
        <ul className="friend-rows">
          {results.map((player) => {
            const rel = relation(player);
            return (
              <li key={player.uuid} className={`friend-row is-static ${rel === "self" ? "is-self" : ""}`}>
                <div className="friend-main">
                  <FriendHead uuid={player.uuid} name={player.name} />
                  <span className="friend-text">
                    <strong>{player.name}</strong>
                    {rel === "self" && <span className="friend-sub">{t("friends.search.you")}</span>}
                    {rel === "none" && !player.accepts && <span className="friend-sub">{t("friends.search.notAccepting")}</span>}
                  </span>
                </div>
                <span className="friend-action">
                  {rel === "none" && player.accepts && (
                    <Button size="sm" icon="userPlus" loading={busy === player.uuid} onClick={() => void add(player)}>
                      {t("friends.search.add")}
                    </Button>
                  )}
                  {rel === "incoming" && (
                    <Button size="sm" variant="primary" loading={busy === player.uuid} onClick={() => void add(player)}>
                      {t("friends.requests.accept")}
                    </Button>
                  )}
                  {rel === "outgoing" && <span className="friend-tag">{t("friends.search.pending")}</span>}
                  {rel === "friend" && (
                    <Button size="sm" variant="ghost" icon="chat" onClick={() => onOpen(player.uuid)}>
                      {t("friends.menu.message")}
                    </Button>
                  )}
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

/** `stale`: the list is the last run's, until the first sync: no status shows, nothing is marked offline. */
function FriendList({ friends, chat, actions, stale }: { friends: Friend[]; chat: string | null; actions: Actions; stale: boolean }) {
  const t = useT();
  const statusText = useStatusText();
  const anchor = useRef<HTMLElement | null>(null);
  const [menuFor, setMenuFor] = useState<Friend | null>(null);
  const { pinned, online, offline } = friendSections(friends);
  const openMenu = (friend: Friend, at: HTMLElement) => {
    anchor.current = at;
    setMenuFor(friend);
  };

  if (friends.length === 0) return <p className="friends-hint">{t("friends.list.empty")}</p>;

  const section = (key: "pinned" | "online" | "offline", list: Friend[]) =>
    list.length > 0 && (
      <section key={key} className="friends-section">
        <h2 className="friends-heading">
          {key === "pinned" ? t("friends.list.pinned") : key === "online" ? t("friends.list.online") : stale ? t("friends.list.everyone") : t("friends.list.offline")}
          <span className="friends-count">{list.length}</span>
        </h2>
        <ul className="friend-rows">
          {list.map((friend) => (
            <li
              key={friend.uuid}
              className={`friend-row ${chat === friend.uuid ? "is-active" : ""} ${!stale && friend.status === "offline" ? "is-offline" : ""}`}
              onContextMenu={(e) => {
                e.preventDefault();
                openMenu(friend, e.currentTarget.querySelector<HTMLElement>(".friend-more") ?? e.currentTarget);
              }}
            >
              <button type="button" className="friend-main" aria-current={chat === friend.uuid ? "true" : undefined} onClick={() => actions.open(friend.uuid)}>
                <FriendHead uuid={friend.uuid} name={friend.name} status={stale ? undefined : friend.status} />
                <span className="friend-text">
                  <strong>{friend.name}</strong>
                  <span className={`friend-sub ${!stale && friend.inGame && friend.status !== "offline" ? "is-playing" : ""}`}>{stale ? t("friends.list.connecting") : statusText(friend)}</span>
                </span>
                {friend.unread > 0 && (
                  <span className="friend-unread" aria-label={t("friends.list.unread", { count: friend.unread })}>
                    {friend.unread > 99 ? "99+" : friend.unread}
                  </span>
                )}
              </button>
              <button type="button" className="friend-more" aria-label={t("friends.menu.label", { name: friend.name })} aria-haspopup="menu" onClick={(e) => openMenu(friend, e.currentTarget)}>
                <Icon name="more" size={15} />
              </button>
            </li>
          ))}
        </ul>
      </section>
    );

  return (
    <>
      {section("pinned", pinned)}
      {section("online", online)}
      {section("offline", offline)}
      <Popover anchor={anchor} open={!!menuFor} onClose={() => setMenuFor(null)} placement="bottom-end" label={menuFor?.name ?? t("friends.title")}>
        {menuFor && <FriendMenu friend={menuFor} actions={actions} onClose={() => setMenuFor(null)} />}
      </Popover>
    </>
  );
}

export function FriendMenu({ friend, actions, onClose }: { friend: Friend; actions: Actions; onClose: () => void }) {
  const t = useT();
  return (
    <Menu
      onClose={onClose}
      items={[
        { label: t("friends.menu.message"), icon: <Icon name="chat" size={15} />, onSelect: () => actions.open(friend.uuid) },
        { label: friend.pinned ? t("friends.menu.unpin") : t("friends.menu.pin"), icon: <Icon name="pin" size={15} />, onSelect: () => actions.pin(friend) },
        { label: t("friends.menu.gift"), icon: <Icon name="gift" size={15} />, onSelect: () => void actions.gift(friend) },
        { type: "separator" },
        { label: t("friends.menu.remove"), icon: <Icon name="trash" size={15} />, danger: true, onSelect: () => actions.remove(friend) },
      ]}
    />
  );
}

function Requests({ snapshot }: { snapshot: SocialSnapshot }) {
  const t = useT();
  const toast = useToast();
  const { respond } = useFriends();
  const [busy, setBusy] = useState<string | null>(null);
  const run = async (key: string, work: () => Promise<void>) => {
    setBusy(key);
    try {
      await work();
    } finally {
      setBusy(null);
    }
  };
  const cancel = (request: FriendRequest) =>
    run(`cancel-${request.id}`, () => api.socialCancel(request.id).catch((e) => toast({ tone: "danger", title: t("friends.requests.failed"), body: t(errorKey(errorText(e))) })));

  if (snapshot.incoming.length === 0 && snapshot.outgoing.length === 0) return <p className="friends-hint">{t("friends.requests.none")}</p>;

  const row = (request: FriendRequest, buttons: React.ReactNode) => (
    <li key={`${request.uuid}-${request.id}`} className="friend-row is-static">
      <div className="friend-main">
        <FriendHead uuid={request.uuid} name={request.name} />
        <span className="friend-text">
          <strong>{request.name}</strong>
          {request.at && <span className="friend-sub">{relativeTime(request.at / 1000)}</span>}
        </span>
      </div>
      <span className="friend-action">{buttons}</span>
    </li>
  );

  return (
    <>
      {snapshot.incoming.length > 0 && (
        <section className="friends-section">
          <h2 className="friends-heading">
            {t("friends.requests.received")}
            <span className="friends-count">{snapshot.incoming.length}</span>
          </h2>
          <ul className="friend-rows">
            {snapshot.incoming.map((request) =>
              row(
                request,
                <>
                  <IconButton icon="x" size="sm" label={t("friends.requests.declineName", { name: request.name })} disabled={!!busy} onClick={() => void run(`no-${request.id}`, () => respond(request.id, false))} />
                  <Button size="sm" loading={busy === `yes-${request.id}`} disabled={!!busy} onClick={() => void run(`yes-${request.id}`, () => respond(request.id, true))}>
                    {t("friends.requests.accept")}
                  </Button>
                </>,
              ),
            )}
          </ul>
        </section>
      )}
      {snapshot.outgoing.length > 0 && (
        <section className="friends-section">
          <h2 className="friends-heading">
            {t("friends.requests.sent")}
            <span className="friends-count">{snapshot.outgoing.length}</span>
          </h2>
          <ul className="friend-rows">
            {snapshot.outgoing.map((request) =>
              row(
                request,
                <Button size="sm" variant="ghost" loading={busy === `cancel-${request.id}`} disabled={!!busy} onClick={() => void cancel(request)}>
                  {t("friends.requests.cancel")}
                </Button>,
              ),
            )}
          </ul>
        </section>
      )}
    </>
  );
}

/** While Friends connects: the page's shape, quietly. */
function FriendsSkeleton() {
  const t = useT();
  return (
    <div className="page-body friends" aria-busy="true">
      <aside className="friends-side">
        <h1>{t("friends.title")}</h1>
        <div className="me-card is-skeleton">
          <span className="sk fsk-head" />
          <span className="fsk-lines">
            <span className="sk sk-line" style={{ width: "55%" }} />
            <span className="sk sk-line" style={{ width: "35%" }} />
          </span>
        </div>
        <span className="sk fsk-field" />
        <div className="friends-list">
          {[62, 48, 70, 54, 40].map((w, i) => (
            <div key={i} className="friend-row is-static is-skeleton">
              <span className="sk fsk-head is-small" />
              <span className="fsk-lines">
                <span className="sk sk-line" style={{ width: `${w}%` }} />
                <span className="sk sk-line" style={{ width: `${w / 2}%` }} />
              </span>
            </div>
          ))}
        </div>
      </aside>
      <section className="chat is-skeleton" aria-label={t("friends.connecting")}>
        <div className="chat-empty">
          <p className="faint">{t("friends.connecting")}</p>
        </div>
      </section>
    </div>
  );
}
