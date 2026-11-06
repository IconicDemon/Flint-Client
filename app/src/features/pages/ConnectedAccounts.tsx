import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api, errorText } from "../../lib/api";
import {
  ATTEMPT_POLL_MS,
  attemptProblem,
  forAccount,
  isConditional,
  linkStatus,
  offerSignedOut,
  pollAgain,
  showConnections,
  type AttemptState,
  type ConnectionsView,
  type DiscordLink,
} from "../../lib/community";
import { useT } from "../../lib/i18n";
import { useStoreSelect } from "../../lib/store";
import { canonicalUuid } from "../../lib/storeSync";
import { Badge, Button, Callout, Spinner } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { Modal, useToast } from "../../ui/overlays";
import type { RowDef } from "./SettingsPage";

/**
 * Settings → Accounts → Connected accounts: the active Minecraft account's Discord link (the Store's contract
 * docs/connections-rewards-api-v1.md, through src-tauri/src/store/community.rs). Discord is the only one.
 *
 * - Linking is Discord's own page in the system browser (Rust checks the address first). Flint never asks for a Discord
 *   password, code or token, and never sends anything about Discord.
 * - Shown only when the Store has the feature (a Store without it answers `off`: no row at all, no error). With linking
 *   switched off, an existing link stays so it can be removed.
 * - Per account: an answer for another account is never shown, and switching accounts starts again.
 */
export function useConnectedAccountsRows(): RowDef[] {
  const t = useT();
  const account = useStoreSelect((s) => s.activeAccount);
  const uuid = account && !account.qa ? account.uuid : null;
  const [answer, setAnswer] = useState<ConnectionsView | null>(null);
  // Signed out of the Store: offered only when this Store has the feature on. Its public catalog is the only signal there
  // is without a session (lib/community.ts offerSignedOut).
  const [offer, setOffer] = useState(false);

  const load = useCallback(async () => {
    if (!uuid) return;
    try {
      const next = forAccount(uuid, await api.storeConnections(uuid));
      if (!next) return;
      // An unreachable Store keeps what was shown, with the reason.
      setAnswer((shown) => (next.state === "unavailable" && forAccount(uuid, shown)?.state === "ok" ? { ...shown!, problem: next.problem } : next));
      if (next.state === "signedOut") {
        setOffer(offerSignedOut(await api.storeCommunityCatalog().catch(() => null)));
      }
    } catch {
      // Not even Rust answered (no such account any more): nothing to show.
    }
  }, [uuid]);

  useEffect(() => {
    setAnswer(null);
    setOffer(false);
    void load();
  }, [load]);

  // Only this account's answer, even for the one render before the switch's effect clears it.
  const view = uuid ? forAccount(uuid, answer) : null;
  const shown = !!uuid && (showConnections(view) || (view?.state === "signedOut" && offer));
  const name = account?.username ?? "";
  return useMemo<RowDef[]>(
    () =>
      shown && uuid && view
        ? [
            {
              section: "accounts",
              title: t("connections.title"),
              desc: t("connections.desc", { name }),
              keywords: "connections.keywords",
              block: true,
              render: () => <ConnectedAccounts key={uuid} uuid={uuid} name={name} view={view} onView={setAnswer} onReload={load} />,
            },
          ]
        : [],
    [shown, uuid, view, name, load, t],
  );
}

/** Settings opened at Connected Accounts (from a Store reward's Connect Discord): the row scrolls into view once it shows. */
let focusWanted = false;
export const requestConnectionsFocus = () => {
  focusWanted = true;
};

const DISCORD_LOGO = "#5865F2";

function ConnectedAccounts({
  uuid,
  name,
  view,
  onView,
  onReload,
}: {
  uuid: string;
  name: string;
  view: ConnectionsView;
  onView: (view: ConnectionsView) => void;
  onReload: () => Promise<void>;
}) {
  const t = useT();
  const toast = useToast();
  const ref = useRef<HTMLDivElement>(null);
  const [linking, setLinking] = useState(false);
  const [unlinking, setUnlinking] = useState(false);
  const [signingIn, setSigningIn] = useState(false);

  useEffect(() => {
    if (!focusWanted) return;
    focusWanted = false;
    ref.current?.scrollIntoView({ block: "center", behavior: "smooth" });
  }, []);

  // A link that is still being checked: asked again a few times, a few seconds apart (never a loop).
  const checking = view.discord?.guild.state === "checking";
  useEffect(() => {
    if (!checking) return;
    let tries = 0;
    const timer = window.setInterval(() => {
      tries += 1;
      void onReload();
      if (tries >= 3) window.clearInterval(timer);
    }, 4000);
    return () => window.clearInterval(timer);
  }, [checking, onReload]);

  const signIn = async () => {
    setSigningIn(true);
    try {
      await api.storeSignIn(uuid);
      await onReload();
    } catch (e) {
      toast({ tone: "danger", title: t("connections.discord.signInFailed"), body: errorText(e) });
    } finally {
      setSigningIn(false);
    }
  };

  const link = view.discord;
  return (
    <div className="connections" ref={ref} id="connected-accounts">
      <div className={`connection ${link ? "is-linked" : ""}`}>
        <DiscordFace link={link} />
        <span className="connection-text">
          <strong>
            {link ? (link.displayName ?? link.username) : t("connections.discord.name")}
            {link ? <Badge tone="success">{t("connections.discord.connected")}</Badge> : view.state === "ok" && <Badge>{t("connections.discord.notConnected")}</Badge>}
          </strong>
          {link ? (
            <>
              {link.displayName && <span className="faint">@{link.username}</span>}
              <LinkState link={link} />
            </>
          ) : (
            <span className="faint">{view.state === "signedOut" ? t("connections.discord.signedOut") : t("connections.discord.notConnectedBody", { name })}</span>
          )}
        </span>
        <span className="connection-actions">
          {view.state === "signedOut" ? (
            <Button size="sm" loading={signingIn} onClick={() => void signIn()}>
              {t("connections.discord.signIn")}
            </Button>
          ) : link ? (
            <>
              {view.canLink && (
                <Button size="sm" variant="ghost" onClick={() => setLinking(true)}>
                  {t("connections.discord.useAnother")}
                </Button>
              )}
              <Button size="sm" variant="ghost" onClick={() => setUnlinking(true)}>
                {t("connections.discord.disconnect")}
              </Button>
            </>
          ) : (
            view.canLink && (
              <Button size="sm" className="btn-discord" icon="discord" onClick={() => setLinking(true)}>
                {t("connections.discord.connect")}
              </Button>
            )
          )}
        </span>
      </div>
      {link && !view.canLink && view.state === "ok" && <p className="faint connections-note">{t("connections.discord.linkingOff")}</p>}
      {view.problem && (
        <p className="connections-note is-warn" role="status">
          <Icon name="wifiOff" size={13} />
          {view.problem}
        </p>
      )}
      <LinkDialog
        open={linking}
        uuid={uuid}
        name={name}
        replacing={link}
        onClose={() => setLinking(false)}
        onLinked={async () => {
          // What linking changed: the connection, the rewards and the inventory (the Store page and previews follow).
          await Promise.all([onReload(), api.storeRewards(uuid).catch(() => null), api.storeInventory(uuid).catch(() => null)]);
        }}
      />
      <UnlinkDialog
        open={unlinking}
        uuid={uuid}
        link={link}
        onClose={() => setUnlinking(false)}
        onDone={(next) => {
          const mine = forAccount(uuid, next);
          if (mine) onView(mine);
          setUnlinking(false);
          toast({ tone: "neutral", title: t("connections.unlink.done") });
          void api.storeInventory(uuid).catch(() => {});
        }}
      />
    </div>
  );
}

/** The linked avatar (only ever from Discord's CDN, checked in Rust) with the Discord mark, or the mark alone. */
function DiscordFace({ link }: { link: DiscordLink | null }) {
  const [broken, setBroken] = useState(false);
  if (link?.avatarUrl && !broken) {
    return (
      <span className="connection-face">
        <img src={link.avatarUrl} alt="" referrerPolicy="no-referrer" onError={() => setBroken(true)} />
        <span className="connection-mark is-badge">
          <Icon name="discord" size={11} />
        </span>
      </span>
    );
  }
  return (
    <span className="connection-face is-mark" style={{ background: DISCORD_LOGO }}>
      <Icon name="discord" size={22} />
    </span>
  );
}

function LinkState({ link }: { link: DiscordLink }) {
  const t = useT();
  const status = linkStatus(link);
  return (
    <span className={`connection-state is-${status.tone}`}>
      {status.checking ? <Spinner size={12} /> : <Icon name={status.tone === "warning" ? "alert" : status.tone === "success" ? "check" : "info"} size={12} />}
      {t(status.label)}
    </span>
  );
}

type Phase =
  | { kind: "disclosure" }
  | { kind: "starting" }
  | { kind: "waiting"; attemptId: string; startedAt: number; opened: boolean; trouble: boolean }
  | { kind: "linked" }
  | { kind: "ended"; state: AttemptState }
  | { kind: "error"; text: string }
  | { kind: "off" };

/** Polls failing this many times in a row (about 10 s) stop: the player can try again. */
const POLL_FAILURES = 5;

/**
 * Connect Discord: the disclosure (the Store's exact words), then Discord's page in the browser while Flint waits for the
 * Store's answer (every 2 s, at most 10 minutes, stopping at the first answer that isn't "waiting"), then the outcome.
 * Closing the dialog stops the polling.
 */
function LinkDialog({
  open,
  uuid,
  name,
  replacing,
  onClose,
  onLinked,
}: {
  open: boolean;
  uuid: string;
  name: string;
  replacing: DiscordLink | null;
  onClose: () => void;
  onLinked: () => Promise<void>;
}) {
  const t = useT();
  const toast = useToast();
  const [phase, setPhase] = useState<Phase>({ kind: "disclosure" });
  const [reopening, setReopening] = useState(false);
  const linked = useRef(onLinked);
  linked.current = onLinked;

  useEffect(() => {
    if (open) setPhase({ kind: "disclosure" });
  }, [open]);

  const start = async () => {
    setPhase({ kind: "starting" });
    try {
      const started = await api.storeDiscordLinkStart(uuid);
      setPhase(started.state === "off" ? { kind: "off" } : { kind: "waiting", attemptId: started.attemptId, startedAt: Date.now(), opened: started.opened, trouble: false });
    } catch (e) {
      setPhase({ kind: "error", text: errorText(e) });
    }
  };

  const attemptId = phase.kind === "waiting" ? phase.attemptId : null;
  const startedAt = phase.kind === "waiting" ? phase.startedAt : 0;
  useEffect(() => {
    if (!open || !attemptId) return;
    let stopped = false;
    let timer: number | undefined;
    let failures = 0;
    const ask = async () => {
      if (stopped) return;
      if (!pollAgain("waiting", startedAt, Date.now())) {
        setPhase({ kind: "ended", state: "expired" });
        return;
      }
      try {
        const answer = await api.storeDiscordLinkAttempt(uuid, attemptId);
        if (stopped) return;
        failures = 0;
        if (answer.state === "linked") {
          setPhase({ kind: "linked" });
          await linked.current();
          return;
        }
        if (answer.state !== "waiting") {
          setPhase({ kind: "ended", state: answer.state });
          return;
        }
        setPhase((p) => (p.kind === "waiting" && p.trouble ? { ...p, trouble: false } : p));
      } catch (e) {
        if (stopped) return;
        failures += 1;
        if (failures >= POLL_FAILURES) {
          setPhase({ kind: "error", text: errorText(e) });
          return;
        }
        setPhase((p) => (p.kind === "waiting" ? { ...p, trouble: true } : p));
      }
      timer = window.setTimeout(() => void ask(), ATTEMPT_POLL_MS);
    };
    timer = window.setTimeout(() => void ask(), ATTEMPT_POLL_MS);
    return () => {
      stopped = true;
      window.clearTimeout(timer);
    };
  }, [open, uuid, attemptId, startedAt]);

  const reopen = async () => {
    if (phase.kind !== "waiting") return;
    setReopening(true);
    try {
      await api.storeDiscordLinkReopen(uuid, phase.attemptId);
    } catch (e) {
      toast({ tone: "danger", title: t("connections.link.reopenFailed"), body: errorText(e) });
    } finally {
      setReopening(false);
    }
  };

  const problem = phase.kind === "ended" ? attemptProblem(phase.state) : null;
  const footer =
    phase.kind === "disclosure" || phase.kind === "starting" ? (
      <>
        <Button variant="ghost" onClick={onClose}>
          {t("common.cancel")}
        </Button>
        <Button className="btn-discord" icon="discord" iconRight="external" loading={phase.kind === "starting"} onClick={() => void start()} data-autofocus>
          {t("connections.link.continue")}
        </Button>
      </>
    ) : phase.kind === "waiting" ? (
      <>
        <Button variant="ghost" onClick={onClose}>
          {t("common.cancel")}
        </Button>
        <Button icon="external" loading={reopening} onClick={() => void reopen()}>
          {t("connections.link.reopen")}
        </Button>
      </>
    ) : phase.kind === "linked" || phase.kind === "off" ? (
      <Button variant="primary" onClick={onClose} data-autofocus>
        {t("common.done")}
      </Button>
    ) : (
      <>
        <Button variant="ghost" onClick={onClose}>
          {t("common.close")}
        </Button>
        <Button variant="primary" icon="refresh" onClick={() => void start()} data-autofocus>
          {t("common.tryAgain")}
        </Button>
      </>
    );

  return (
    <Modal open={open} onClose={onClose} title={t("connections.disclosure.title")} width={520} footer={footer}>
      {phase.kind === "disclosure" || phase.kind === "starting" ? (
        <Disclosure replacing={replacing} />
      ) : phase.kind === "waiting" ? (
        <div className="link-state">
          <span className="link-state-icon is-waiting">
            <Spinner size={22} />
          </span>
          <h3>{t("connections.link.waiting.title")}</h3>
          <p className="muted">{t("connections.link.waiting.body")}</p>
          {!phase.opened && <p className="muted">{t("connections.link.waiting.notOpened")}</p>}
          <p className={`faint link-state-status ${phase.trouble ? "is-warn" : ""}`} role="status">
            {phase.trouble ? t("connections.link.waiting.trouble") : t("connections.link.waiting.status")}
          </p>
        </div>
      ) : phase.kind === "linked" ? (
        <div className="link-state">
          <span className="link-state-icon is-done">
            <Icon name="check" size={22} />
          </span>
          <h3>{t("connections.link.done.title")}</h3>
          <p className="muted">{t("connections.link.done.body", { name })}</p>
        </div>
      ) : phase.kind === "off" ? (
        <div className="link-state">
          <span className="link-state-icon">
            <Icon name="info" size={22} />
          </span>
          <h3>{t("connections.link.off.title")}</h3>
          <p className="muted">{t("connections.link.off.body")}</p>
        </div>
      ) : (
        <div className="link-state">
          <span className="link-state-icon is-problem">
            <Icon name="alert" size={22} />
          </span>
          <h3>{problem ? t(problem.title) : t("connections.link.startFailed")}</h3>
          <p className="muted">{problem ? t(problem.body) : phase.kind === "error" ? phase.text : ""}</p>
        </div>
      )}
    </Modal>
  );
}

/** The Store's disclosure, word for word (docs/connections-rewards-api-v1.md "Player-facing disclosure"). */
function Disclosure({ replacing }: { replacing: DiscordLink | null }) {
  const t = useT();
  return (
    <div className="disclosure">
      <p>{t.rich("connections.disclosure.intro", { permission: <code>identify</code> })}</p>
      <h4>{t("connections.disclosure.usesTitle")}</h4>
      <ul>
        <li>{t("connections.disclosure.usesId")}</li>
        <li>{t("connections.disclosure.usesNames")}</li>
        <li>{t("connections.disclosure.usesAvatar")}</li>
        <li>{t("connections.disclosure.usesMember")}</li>
        <li>{t("connections.disclosure.usesBooster")}</li>
      </ul>
      <p>{t.rich("connections.disclosure.notRequested", { label: <strong>{t("connections.disclosure.notRequestedLabel")}</strong> })}</p>
      <p className="muted">{t("connections.disclosure.how")}</p>
      {replacing && (
        <Callout tone="info" icon="info">
          {t("connections.link.replacing", { name: replacing.displayName ?? replacing.username })}
        </Callout>
      )}
    </div>
  );
}

/** Disconnect: says what ends (this account's Discord reward capes, by name when Flint has them) and what stays. */
function UnlinkDialog({
  open,
  uuid,
  link,
  onClose,
  onDone,
}: {
  open: boolean;
  uuid: string;
  link: DiscordLink | null;
  onClose: () => void;
  onDone: (view: ConnectionsView) => void;
}) {
  const t = useT();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [ending, setEnding] = useState<string[]>([]);

  useEffect(() => {
    if (!open) return;
    setError(null);
    setEnding([]);
    let cancelled = false;
    // The capes that end: this account's conditional (Discord) items, as the Store last listed them.
    api.storeInventory(uuid).then(
      (view) => !cancelled && canonicalUuid(view.uuid) === canonicalUuid(uuid) && setEnding((view.inventory?.owned ?? []).filter(isConditional).map((o) => o.name)),
      () => {},
    );
    return () => {
      cancelled = true;
    };
  }, [open, uuid]);

  const confirm = async () => {
    setBusy(true);
    setError(null);
    try {
      onDone(await api.storeDiscordUnlink(uuid));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t("connections.unlink.title")}
      width={440}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {t("common.cancel")}
          </Button>
          <Button variant="danger" loading={busy} onClick={() => void confirm()}>
            {t("connections.unlink.confirm")}
          </Button>
        </>
      }
    >
      <div className="modal-copy unlink-copy">
        {link && <p className="muted">{t("connections.unlink.who", { name: link.displayName ?? link.username })}</p>}
        <p className="muted">{t("connections.unlink.body")}</p>
        {ending.length > 0 && (
          <p>
            <strong>{t("connections.unlink.ending")}</strong> {ending.join(", ")}
          </p>
        )}
        {error && (
          <p className="store-inline-error" role="alert">
            <Icon name="alert" size={14} />
            {error}
          </p>
        )}
      </div>
    </Modal>
  );
}
