import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { api, errorText, subscribe } from "../../lib/api";
import { useT } from "../../lib/i18n";
import { useSettings } from "../../lib/settings";
import { badgeCount, EMPTY_SNAPSHOT, errorKey, type ChatMessage, type PendingMessage, type SocialEvent, type SocialSnapshot } from "../../lib/social";
import { sound } from "../../lib/sound";
import { useStoreSelect } from "../../lib/store";
import { useToast } from "../../ui/overlays";
import type { Navigate } from "../shell/Shell";
import { FriendHead } from "./FriendHead";

type View = { open: boolean; chat: string | null };

type FriendsContext = {
  snapshot: SocialSnapshot;
  /** The backend's snapshot arrived at least once. */
  loaded: boolean;
  badge: number;
  /** Sends on their way (or failed), per the friend they go to. */
  pending: PendingMessage[];
  /** Sends the server confirmed that the next snapshot may not have yet. */
  confirmed: ChatMessage[];
  send: (to: string, body: string, sensitive: boolean) => void;
  retry: (key: string) => void;
  discard: (key: string) => void;
  /** The Friends page says whether it is open, and which chat. */
  setView: (view: View) => void;
  respond: (id: number | string, accept: boolean) => Promise<void>;
};

const Context = createContext<FriendsContext | null>(null);

export function useFriends(): FriendsContext {
  const ctx = useContext(Context);
  if (!ctx) throw new Error("FriendsProvider missing");
  return ctx;
}

/** Several messages in one sync from one friend make one note. */
const BATCH_MS = 80;

/**
 * Friends for the whole window: the backend's state, the sidebar's count, and the notes and sounds for what happens while
 * Flint is open (a request, an accepted request, a message for a chat that isn't open, a friend coming online). The
 * backend never reports anything for its first sync, so opening Flint is silent.
 */
export function FriendsProvider({ navigate, children }: { navigate: Navigate; children: ReactNode }) {
  const t = useT();
  const toast = useToast();
  const { settings } = useSettings();
  const activeUuid = useStoreSelect((s) => s.activeAccount?.uuid ?? null);
  const [snapshot, setSnapshot] = useState<SocialSnapshot>(EMPTY_SNAPSHOT);
  const [loaded, setLoaded] = useState(false);
  const [pending, setPending] = useState<PendingMessage[]>([]);
  const [confirmed, setConfirmed] = useState<ChatMessage[]>([]);
  const view = useRef<View>({ open: false, chat: null });
  const prefs = useRef(settings);
  prefs.current = settings;
  const latest = useRef({ t, toast, navigate });
  latest.current = { t, toast, navigate };

  useEffect(() => {
    let live = true;
    void api
      .socialSnapshot()
      .then((s) => {
        if (!live) return;
        setSnapshot(s);
        setLoaded(true);
      })
      .catch(() => {});
    const off = subscribe<SocialSnapshot>("social:update", (s) => {
      setSnapshot(s);
      setLoaded(true);
    });
    return () => {
      live = false;
      off();
    };
  }, []);

  // Another account chosen: Friends follows at once (the first one is picked up by the backend on its own).
  const firstAccount = useRef(true);
  useEffect(() => {
    if (firstAccount.current) {
      firstAccount.current = false;
      return;
    }
    void api.socialRefresh().catch(() => {});
  }, [activeUuid]);

  // Confirmed sends the snapshot now has are dropped.
  useEffect(() => {
    setConfirmed((list) => {
      const next = list.filter((m) => !snapshot.conversations[m.to]?.messages.some((x) => x.id === m.id));
      return next.length === list.length ? list : next;
    });
  }, [snapshot]);

  const respond = useCallback(async (id: number | string, accept: boolean) => {
    const { t, toast } = latest.current;
    try {
      await api.socialRespond(id, accept);
    } catch (e) {
      // Accepting when they have no room left: the request stays for later.
      const code = errorText(e);
      toast({ tone: "danger", title: t("friends.requests.failed"), body: t(accept && code === "not_accepting" ? "friends.errors.theyAreFull" : errorKey(code)) });
    }
  }, []);

  const deliver = useCallback((item: PendingMessage) => {
    void api
      .socialSend(item.to, item.body, item.sensitive, item.clientId)
      .then((message) => {
        setConfirmed((list) => [...list.filter((m) => m.id !== message.id), message]);
        setPending((list) => list.filter((x) => x.key !== item.key));
      })
      .catch((e) => {
        setPending((list) => list.map((x) => (x.key === item.key ? { ...x, failed: true } : x)));
        const { t, toast } = latest.current;
        toast({ tone: "danger", title: t("friends.chat.notSent"), body: t(errorKey(errorText(e))) });
      });
  }, []);

  const send = useCallback(
    (to: string, body: string, sensitive: boolean) => {
      const item: PendingMessage = { key: `p${Date.now().toString(36)}${Math.random().toString(36).slice(2, 7)}`, clientId: crypto.randomUUID(), to, body, sensitive, at: Date.now(), failed: false };
      setPending((list) => [...list, item]);
      if (prefs.current.friendsSounds) sound.play("sent");
      deliver(item);
    },
    [deliver],
  );

  const retry = useCallback(
    (key: string) => {
      setPending((list) => {
        const item = list.find((x) => x.key === key);
        if (item) queueMicrotask(() => deliver(item));
        return list.map((x) => (x.key === key ? { ...x, failed: false } : x));
      });
    },
    [deliver],
  );

  const discard = useCallback((key: string) => setPending((list) => list.filter((x) => x.key !== key)), []);

  const setView = useCallback((next: View) => {
    view.current = next;
  }, []);

  // What happened, as notes and sounds.
  const batch = useRef<Map<string, { name: string; messages: ChatMessage[] }>>(new Map());
  const timer = useRef<number | undefined>(undefined);
  useEffect(() => {
    const head = (uuid: string, name: string) => <FriendHead uuid={uuid} name={name} size={30} />;
    const flushMessages = () => {
      const { t, toast, navigate } = latest.current;
      const quiet = !prefs.current.friendsSounds;
      for (const [from, { name, messages }] of batch.current) {
        const last = messages[messages.length - 1];
        const reading = view.current.open && view.current.chat === from && document.hasFocus();
        if (reading) {
          if (!quiet) sound.play("message");
          continue;
        }
        const preview = last.sensitive ? t("friends.toast.sentPrivate") : last.body.replace(/\s+/g, " ").slice(0, 120);
        toast({
          tone: "neutral",
          title: name || t("friends.toast.someone"),
          body: messages.length > 1 ? t("friends.toast.messages", { count: messages.length, preview }) : preview,
          icon: head(from, name),
          sound: quiet ? null : "message",
          action: { label: t("friends.toast.reply"), onClick: () => navigate("friends", from) },
        });
      }
      batch.current.clear();
    };
    return subscribe<SocialEvent>("social:event", (event) => {
      const { t, toast, navigate } = latest.current;
      const quiet = !prefs.current.friendsSounds;
      switch (event.kind) {
        case "request":
          toast({
            tone: "neutral",
            title: t("friends.toast.request", { name: event.name }),
            body: t("friends.toast.requestBody"),
            icon: head(event.uuid, event.name),
            sound: quiet ? null : "request",
            actions: [
              { label: t("friends.requests.accept"), primary: true, onClick: () => void respond(event.id, true) },
              { label: t("friends.requests.decline"), onClick: () => void respond(event.id, false) },
            ],
          });
          break;
        case "accepted":
          toast({
            tone: "neutral",
            title: t("friends.toast.accepted", { name: event.name }),
            body: t("friends.toast.acceptedBody"),
            icon: head(event.uuid, event.name),
            sound: quiet ? null : "request",
            action: { label: t("friends.toast.sayHi"), onClick: () => navigate("friends", event.uuid) },
          });
          break;
        case "online":
          if (!prefs.current.friendsNotifyOnline) break;
          toast({ tone: "neutral", title: t("friends.toast.online", { name: event.name }), icon: head(event.uuid, event.name), sound: quiet ? null : "online" });
          break;
        case "message": {
          const from = event.message.from;
          const entry = batch.current.get(from) ?? { name: event.name, messages: [] };
          entry.messages.push(event.message);
          batch.current.set(from, entry);
          window.clearTimeout(timer.current);
          timer.current = window.setTimeout(flushMessages, BATCH_MS);
          break;
        }
      }
    });
  }, [respond]);

  const value = useMemo<FriendsContext>(
    () => ({ snapshot, loaded, badge: badgeCount(snapshot), pending, confirmed, send, retry, discard, setView, respond }),
    [snapshot, loaded, pending, confirmed, send, retry, discard, setView, respond],
  );
  return <Context.Provider value={value}>{children}</Context.Provider>;
}

/** The number on the sidebar's Friends button: pending requests and unread messages, hidden at 0. */
export function FriendsBadge() {
  const { badge } = useFriends();
  if (badge <= 0) return null;
  return (
    <span className="rail-badge" aria-hidden>
      {badge > 9 ? "9+" : badge}
    </span>
  );
}
