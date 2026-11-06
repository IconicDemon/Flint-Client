import { useEffect, useState } from "react";
import { api } from "../../lib/api";
import { headFromSkin } from "../../lib/avatar";
import { relativeTime } from "../../lib/format";
import { useT } from "../../lib/i18n";
import { statusKey, statusTone, type Friend, type FriendStatus, type MyStatus } from "../../lib/social";

/** Each player's face, read once per run from their skin (the backend caches skins for hours). */
const heads = new Map<string, Promise<string | null>>();

function headOf(uuid: string): Promise<string | null> {
  let head = heads.get(uuid);
  if (!head) {
    head = api
      .skin(uuid)
      .then((skin) => (skin.dataUrl ? headFromSkin(skin.dataUrl, 64) : null))
      .catch(() => null);
    heads.set(uuid, head);
  }
  return head;
}

/** A player's head, with their status as a small dot at its corner when given. */
export function FriendHead({ uuid, name, size = 34, status }: { uuid: string; name: string; size?: number; status?: FriendStatus | MyStatus }) {
  const [src, setSrc] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    setSrc(null);
    void headOf(uuid).then((head) => live && setSrc(head));
    return () => {
      live = false;
    };
  }, [uuid]);
  return (
    <span className="fhead" style={{ width: size, height: size }}>
      <span className="fhead-face">{src ? <img src={src} alt="" /> : <span>{name.slice(0, 1).toUpperCase()}</span>}</span>
      {status && <span className={`fdot is-${statusTone(status)}`} aria-hidden />}
    </span>
  );
}

/** A status as a dot alone (menus, the status picker). */
export function StatusDot({ status }: { status: FriendStatus | MyStatus }) {
  return <span className={`fdot is-${statusTone(status)} is-inline`} aria-hidden />;
}

/** "Offline · 2 h ago", "In game", "Busy". */
export function useStatusText() {
  const t = useT();
  return (friend: Friend) => {
    const label = t(statusKey(friend));
    return friend.status === "offline" && friend.lastSeen ? `${label} · ${relativeTime(friend.lastSeen / 1000)}` : label;
  };
}
