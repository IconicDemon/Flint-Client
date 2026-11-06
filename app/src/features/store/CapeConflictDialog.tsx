import { useMemo, type ReactNode } from "react";
import { capeNotice, type CapeConflict, type Message } from "../../lib/capeConflict";
import { useT, type Translate } from "../../lib/i18n";
import { Button } from "../../ui/controls";
import { Modal } from "../../ui/overlays";

/** A message, its `strong` placeholders in bold. */
function rich(t: Translate, message: Message) {
  if (!message.strong?.length) return t(message.key, message.vars);
  const vars: Record<string, ReactNode> = { ...message.vars };
  for (const name of message.strong) vars[name] = <strong>{message.vars[name]}</strong>;
  return t.rich(message.key, vars);
}

/**
 * The small notice wearing a Flint cape gets while a mod that shows its own capes is on in one of the player's profiles
 * (lib/capeConflict.ts): which mods, where, and that turning them off brings the Flint cape back. The cape is already
 * worn; OK (or Escape) only closes it.
 */
export function CapeConflictDialog({ conflicts, onClose }: { conflicts: CapeConflict[] | null; onClose: () => void }) {
  const t = useT();
  const notice = useMemo(() => (conflicts?.length ? capeNotice(conflicts) : null), [conflicts]);
  return (
    <Modal
      open={!!notice}
      onClose={onClose}
      title={notice ? t(notice.title.key, notice.title.vars) : ""}
      width={440}
      footer={
        <Button variant="primary" onClick={onClose} data-autofocus>
          {t("store.capeConflict.ok")}
        </Button>
      }
    >
      {notice && (
        <>
          <p className="muted modal-copy">{rich(t, notice.body)}</p>
          {notice.rows.length > 0 && (
            <ul className="cape-conflict-list">
              {notice.rows.map((row) => (
                <li key={row.profileId}>
                  <strong>{row.profile}</strong>
                  <span>{row.mods}</span>
                </li>
              ))}
              {notice.more && <li className="cape-conflict-more">{t(notice.more.key, notice.more.vars)}</li>}
            </ul>
          )}
          {notice.outro && <p className="muted modal-copy">{t(notice.outro.key, notice.outro.vars)}</p>}
        </>
      )}
    </Modal>
  );
}
