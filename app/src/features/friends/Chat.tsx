import { useEffect, useLayoutEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { api } from "../../lib/api";
import { privateParts, looksPrivate } from "../../lib/credentials";
import { formatLocale, useT } from "../../lib/i18n";
import { useSettings } from "../../lib/settings";
import { chatItems, COUNTER_FROM, isPending, MAX_MESSAGE, mergeMessages, type ChatMessage, type Friend, type Me, type PendingMessage } from "../../lib/social";
import { Button, IconButton, Spinner } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { Modal, Tooltip } from "../../ui/overlays";
import { FriendHead, useStatusText } from "./FriendHead";
import { useFriends } from "./FriendsProvider";

const PAGE = 50;

const timeText = (ms: number) => new Date(ms).toLocaleTimeString(formatLocale(), { hour: "numeric", minute: "2-digit" });

/** One conversation: its header, the messages (older ones load on scrolling up) and the composer. */
export function ChatPanel({ friend, me, onGift, onPin }: { friend: Friend; me: Me; onGift: () => void; onPin: () => void }) {
  const t = useT();
  const { motionOk } = useSettings();
  const statusText = useStatusText();
  const { snapshot, pending, confirmed, send, retry, discard } = useFriends();
  const conversation = snapshot.conversations[friend.uuid];
  const [older, setOlder] = useState<ChatMessage[]>([]);
  const [complete, setComplete] = useState(false);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [revealed, setRevealed] = useState<Set<string>>(() => new Set());
  const scroller = useRef<HTMLDivElement>(null);
  const pinnedToBottom = useRef(true);
  const restore = useRef<number | null>(null);

  // The latest page, when the backend has none for this friend yet (a restart, or a chat never opened).
  useEffect(() => {
    if (conversation?.loaded) return;
    void api
      .socialHistory(friend.uuid, null, PAGE)
      .then((page) => page.length < PAGE && setComplete(true))
      .catch(() => {});
    // Once per chat.
  }, [friend.uuid]);

  const messages = useMemo(
    () => mergeMessages(older, conversation?.messages ?? [], confirmed.filter((m) => m.to === friend.uuid)),
    [older, conversation, confirmed, friend.uuid],
  );
  const mine = useMemo(() => pending.filter((p) => p.to === friend.uuid), [pending, friend.uuid]);
  const items = useMemo(() => chatItems([...messages, ...mine], me.uuid), [messages, mine, me.uuid]);
  const fewMessages = conversation?.loaded && messages.length < PAGE;

  // Read: what they sent, while this chat is in front of the player.
  const lastTheirs = [...messages].reverse().find((m) => m.from === friend.uuid);
  useEffect(() => {
    if (!lastTheirs || (lastTheirs.read && friend.unread === 0)) return;
    const mark = () => {
      if (document.hasFocus()) void api.socialRead(friend.uuid, lastTheirs.id).catch(() => {});
    };
    mark();
    window.addEventListener("focus", mark);
    return () => window.removeEventListener("focus", mark);
  }, [lastTheirs, friend.uuid, friend.unread]);

  // Stays at the newest message unless the player scrolled up; after older ones load, stays where it was.
  useLayoutEffect(() => {
    const el = scroller.current;
    if (!el) return;
    if (restore.current !== null) {
      el.scrollTop = el.scrollHeight - restore.current;
      restore.current = null;
    } else if (pinnedToBottom.current) {
      el.scrollTo({ top: el.scrollHeight, behavior: items.length > 0 && motionOk && el.scrollTop > 0 ? "smooth" : "auto" });
    }
  }, [items, motionOk]);

  const loadOlder = async () => {
    const first = messages[0];
    if (!first || complete || loadingOlder || fewMessages) return;
    setLoadingOlder(true);
    try {
      const page = await api.socialHistory(friend.uuid, first.id, PAGE);
      if (page.length < PAGE) setComplete(true);
      const el = scroller.current;
      if (el) restore.current = el.scrollHeight - el.scrollTop;
      setOlder((list) => mergeMessages(page, list));
    } catch {
      // Tried again on the next scroll.
    } finally {
      setLoadingOlder(false);
    }
  };

  const onScroll = () => {
    const el = scroller.current;
    if (!el) return;
    pinnedToBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
    if (el.scrollTop < 120) void loadOlder();
  };

  const toggle = (key: string) =>
    setRevealed((set) => {
      const next = new Set(set);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  const playing = friend.inGame && friend.status !== "offline";
  return (
    <>
      <header className="chat-head">
        <FriendHead uuid={friend.uuid} name={friend.name} size={40} status={friend.status} />
        <div className="chat-who">
          <strong>{friend.name}</strong>
          <span className={`friend-sub ${playing ? "is-playing" : ""}`}>{statusText(friend)}</span>
        </div>
        <Button variant="ghost" size="sm" icon="gift" onClick={onGift}>
          {t("friends.menu.gift")}
        </Button>
        <Tooltip label={friend.pinned ? t("friends.menu.unpin") : t("friends.menu.pin")} side="bottom">
          <IconButton icon="pin" size="sm" className={`chat-pin ${friend.pinned ? "is-on" : ""}`} aria-pressed={friend.pinned} label={friend.pinned ? t("friends.menu.unpin") : t("friends.menu.pin")} onClick={onPin} />
        </Tooltip>
      </header>

      <div ref={scroller} className="chat-scroll scroll" onScroll={onScroll} role="log" aria-live="polite" aria-label={t("friends.chat.label", { name: friend.name })}>
        {items.length === 0 ? (
          <div className="chat-start">
            <FriendHead uuid={friend.uuid} name={friend.name} size={56} />
            <strong>{t("friends.chat.startTitle", { name: friend.name })}</strong>
            <span className="faint">{t("friends.chat.startBody")}</span>
          </div>
        ) : (
          <>
            {(complete || fewMessages) && <p className="chat-beginning faint">{t("friends.chat.beginning", { name: friend.name })}</p>}
            {loadingOlder && (
              <div className="chat-older">
                <Spinner size={14} />
              </div>
            )}
            {items.map((item) => {
              if (item.type === "day") return <DaySeparator key={item.key} at={item.at} />;
              const message = item.message;
              const pendingOne = isPending(message);
              const hidden = message.sensitive && !revealed.has(item.key);
              const at = message.at ?? Date.now();
              return (
                <div key={item.key} className={`msg ${item.mine ? "is-mine" : "is-theirs"} ${item.first ? "is-first" : ""} ${item.last ? "is-last" : ""}`}>
                  {message.sensitive ? (
                    <button type="button" className={`bubble is-private ${hidden ? "is-hidden" : ""}`} aria-label={hidden ? t("friends.chat.show") : t("friends.chat.hide")} onClick={() => toggle(item.key)}>
                      <span className="bubble-text">{message.body}</span>
                      {hidden && (
                        <span className="bubble-veil">
                          <Icon name="lock" size={12} />
                          {t("friends.chat.clickToShow")}
                        </span>
                      )}
                    </button>
                  ) : (
                    <div className={`bubble ${pendingOne && !(message as PendingMessage).failed ? "is-sending" : ""}`}>
                      <span className="bubble-text">{message.body}</span>
                    </div>
                  )}
                  {pendingOne && (message as PendingMessage).failed ? (
                    <span className="msg-meta is-failed">
                      {t("friends.chat.notSent")} ·{" "}
                      <button type="button" onClick={() => retry((message as PendingMessage).key)}>
                        {t("friends.chat.retry")}
                      </button>{" "}
                      ·{" "}
                      <button type="button" onClick={() => discard((message as PendingMessage).key)}>
                        {t("friends.chat.discard")}
                      </button>
                    </span>
                  ) : (
                    item.last && <span className="msg-meta">{pendingOne ? t("friends.chat.sending") : timeText(at)}</span>
                  )}
                </div>
              );
            })}
          </>
        )}
      </div>

      <Composer friend={friend} onSend={(body, sensitive) => send(friend.uuid, body, sensitive)} onSent={() => (pinnedToBottom.current = true)} />
    </>
  );
}

function DaySeparator({ at }: { at: number }) {
  const t = useT();
  const day = new Date(at);
  const today = new Date();
  const yesterday = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 1);
  const same = (a: Date, b: Date) => a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
  const label = same(day, today)
    ? t("friends.chat.today")
    : same(day, yesterday)
      ? t("friends.chat.yesterday")
      : day.toLocaleDateString(formatLocale(), { weekday: "long", month: "long", day: "numeric", year: day.getFullYear() === today.getFullYear() ? undefined : "numeric" });
  return (
    <div className="chat-day" role="separator">
      <span>{label}</span>
    </div>
  );
}

/** The message box: Enter sends, Shift+Enter starts a new line; anything that looks private is asked about first. */
function Composer({ friend, onSend, onSent }: { friend: Friend; onSend: (body: string, sensitive: boolean) => void; onSent: () => void }) {
  const t = useT();
  const [draft, setDraft] = useState("");
  const [check, setCheck] = useState<string | null>(null);
  const box = useRef<HTMLTextAreaElement>(null);
  const length = Array.from(draft.trim()).length;
  const over = length > MAX_MESSAGE;
  const canSend = length > 0 && !over;

  useLayoutEffect(() => {
    const el = box.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 168)}px`;
  }, [draft]);

  useEffect(() => box.current?.focus({ preventScroll: true }), [friend.uuid]);

  const go = (body: string, sensitive: boolean) => {
    onSend(body, sensitive);
    onSent();
    setDraft("");
    box.current?.focus({ preventScroll: true });
  };

  const submit = (e?: FormEvent) => {
    e?.preventDefault();
    const body = draft.trim();
    if (!canSend) return;
    if (looksPrivate(body)) setCheck(body);
    else go(body, false);
  };

  return (
    <>
      <form className={`composer ${check !== null ? "is-checking" : ""}`} onSubmit={submit}>
        <textarea
          ref={box}
          rows={1}
          value={draft}
          placeholder={t("friends.chat.placeholder", { name: friend.name })}
          aria-label={t("friends.chat.placeholder", { name: friend.name })}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
              e.preventDefault();
              submit();
            }
          }}
        />
        {length >= COUNTER_FROM && (
          <span className={`composer-count ${over ? "is-over" : ""}`} aria-live="polite">
            {length}/{MAX_MESSAGE}
          </span>
        )}
        <IconButton icon="send" type="submit" className={`composer-send ${canSend ? "is-ready" : ""}`} label={t("friends.chat.send")} disabled={!canSend} />
      </form>
      <Modal
        open={check !== null}
        onClose={() => setCheck(null)}
        title={t("friends.private.title")}
        width={460}
        footer={
          <>
            <Button
              variant="ghost"
              onClick={() => {
                const body = check;
                setCheck(null);
                if (body) go(body, true);
              }}
            >
              {t("friends.private.sendAnyway")}
            </Button>
            <Button
              variant="primary"
              data-autofocus
              onClick={() => {
                setCheck(null);
                setDraft("");
              }}
            >
              {t("friends.private.dontSend")}
            </Button>
          </>
        }
      >
        <div className="private-check">
          <p className="private-preview">
            {privateParts(check ?? "").map((part, i) =>
              part.hidden ? (
                <span key={i} className="private-blur" aria-label={t("friends.private.hiddenPart")}>
                  {part.text}
                </span>
              ) : (
                <span key={i}>{part.text}</span>
              ),
            )}
          </p>
          <p className="private-warning">{t("friends.private.body")}</p>
        </div>
      </Modal>
    </>
  );
}
