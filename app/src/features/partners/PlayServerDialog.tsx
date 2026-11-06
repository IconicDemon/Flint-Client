import { useEffect, useMemo, useState } from "react";
import { api, errorText, type PartnerPlayPreview, type PartnerServerCheck, type Profile } from "../../lib/api";
import { list } from "../../lib/format";
import { useT } from "../../lib/i18n";
import { canJoin, freshStats, playChoice, serverAddress, versionFits, versionsText, type Partner } from "../../lib/partners";
import { useNow } from "../../lib/partnersFeed";
import { sound } from "../../lib/sound";
import { useStoreSelect } from "../../lib/store";
import { loaderLabel, versionMeta } from "../../lib/versions";
import { Badge, Button, Spinner } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { Modal, useToast } from "../../ui/overlays";
import type { Navigate } from "../shell/Shell";
import { LabelPill, PartnerLogo, whenText } from "./partnerUi";

/**
 * Play on a partner's server: never without this confirmation. It names the server and its address, the profile it
 * will be added to and started with (one whose version the server takes, chosen with the player), and what changes:
 * one new entry at the end of that profile's Multiplayer list, unless it's already there. The launcher checks it all
 * again before it writes anything (src-tauri/src/partners/play.rs).
 */
export function PlayServerDialog({ target, navigate, onClose }: { target: { partner: Partner; choose: boolean } | null; navigate: Navigate; onClose: () => void }) {
  const t = useT();
  const toast = useToast();
  const now = useNow();
  const store = useStoreSelect((s) => ({ profiles: s.profiles, selectedId: s.selectedId, account: s.activeAccount }));
  const partner = target?.partner ?? null;
  const server = partner && canJoin(partner) ? partner.server : null;
  const choice = useMemo(() => (server ? playChoice(server, store.profiles, store.selectedId) : null), [server, store.profiles, store.selectedId]);
  const [profileId, setProfileId] = useState<string | null>(null);
  const [choosing, setChoosing] = useState(false);
  const [preview, setPreview] = useState<PartnerPlayPreview | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [check, setCheck] = useState<PartnerServerCheck | "checking" | "failed">("checking");

  // A new target: start from the suggested profile, the list closed unless "Play with another profile".
  useEffect(() => {
    setProfileId(choice?.pick?.id ?? null);
    setChoosing(!!target?.choose || !choice?.pick);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- once per opened listing
  }, [partner?.id, target?.choose]);

  // Flint's own look at the server (from this computer), once per opened listing.
  useEffect(() => {
    if (!partner || !server) return;
    let live = true;
    setCheck("checking");
    void api
      .partnersCheckServer(partner.id)
      .then((r) => live && setCheck(r))
      .catch(() => live && setCheck("failed"));
    return () => {
      live = false;
    };
  }, [partner, server]);

  // What Play would do with the chosen profile (whether the server is already in its list, a running game).
  useEffect(() => {
    setPreview(null);
    setPreviewError(null);
    if (!partner || !profileId) return;
    let live = true;
    void api
      .partnersPlayPreview(partner.id, profileId)
      .then((p) => live && setPreview(p))
      .catch((e) => live && setPreviewError(errorText(e)));
    return () => {
      live = false;
    };
  }, [partner, profileId]);

  const profile = store.profiles.find((p) => p.id === profileId) ?? null;
  const versions = server ? versionsText(server.versions, (l) => list(l, "or")) : null;
  const stats = partner ? freshStats(partner, now) : null;
  const ready = !!partner && !!profile && !!preview && !preview.blocked && !!store.account && !previewError;

  const confirm = () => {
    if (!partner || !profile || !ready) return;
    const name = partner.name;
    onClose();
    sound.play("launch");
    toast({ tone: "neutral", title: t("partners.play.starting", { profile: profile.name }), body: t("partners.play.startingBody", { name }), sound: null });
    void api
      .partnersPlay(partner.id, profile.id)
      .then((r) => {
        if (r.added) toast({ tone: "success", title: t("partners.play.added", { name, profile: profile.name }), body: r.address });
        else if (r.problem) toast({ tone: "neutral", title: t("partners.play.notAdded"), body: r.problem });
      })
      .catch((e) => {
        const message = errorText(e);
        if (message !== "Installation paused.") toast({ tone: "danger", title: t("partners.play.failed", { name }), body: message });
      });
  };

  const profileRow = (p: Profile, fits: boolean, picked: boolean) => (
    <button
      key={p.id}
      type="button"
      role="radio"
      aria-checked={picked}
      disabled={!fits}
      className={`p-play-profile ${picked ? "is-picked" : ""}`}
      onClick={() => {
        setProfileId(p.id);
        setChoosing(false);
      }}
    >
      <img src={versionMeta(p.gameVersion).thumb} alt="" />
      <span className="p-play-profile-text">
        <strong>{p.name}</strong>
        <span className="faint">
          {t("partners.play.profileMeta", { version: p.gameVersion, loader: loaderLabel(p.loader) })}
          {!fits && versions ? ` · ${t("partners.play.needs", { versions })}` : ""}
        </span>
      </span>
      {fits && server?.recommendedVersion === p.gameVersion && <Badge tone="accent">{t("partners.play.recommended")}</Badge>}
      {picked && <Icon name="check" size={16} className="p-play-check-mark" />}
    </button>
  );

  return (
    <Modal
      open={!!target}
      onClose={onClose}
      title={partner ? t("partners.play.title", { name: partner.name }) : ""}
      width={560}
      footer={
        partner && (
          <>
            <Button variant="ghost" onClick={onClose}>
              {t("common.cancel")}
            </Button>
            <Button variant="primary" icon="play" disabled={!ready} loading={!!profile && !preview && !previewError} onClick={confirm} data-autofocus>
              {preview?.listed ? t("partners.play.confirmListed") : t("partners.play.confirm")}
            </Button>
          </>
        )
      }
    >
      {partner && server && (
        <div className="p-play">
          <div className="p-play-server">
            <PartnerLogo partner={partner} size={46} />
            <span className="p-play-server-text">
              <strong>{partner.name}</strong>
              <span className="p-mono">{serverAddress(server)}</span>
            </span>
            <LabelPill partner={partner} />
          </div>

          <p className={`p-play-status ${check === "checking" ? "" : typeof check === "object" && check.reachable ? "is-good" : "is-warn"}`}>
            {check === "checking" ? (
              <>
                <Spinner size={13} /> {t("partners.play.checking")}
              </>
            ) : typeof check === "object" && check.reachable ? (
              <>
                <span className="p-live-dot" aria-hidden /> {check.pingMs != null ? t("partners.play.reachablePing", { ms: Math.round(check.pingMs) }) : t("partners.play.reachable")}
              </>
            ) : (
              <>
                <Icon name="alert" size={14} /> {t("partners.play.unreachable")}
              </>
            )}
            {stats && !stats.online && <span className="p-play-status-note">{t("partners.play.reportedOffline", { when: whenText(stats.checkedAt) })}</span>}
          </p>

          <div className="p-play-section">
            <div className="p-play-label">
              <span>{t("partners.play.profile")}</span>
              {versions && <span className="faint">{t("partners.play.serverTakes", { versions })}</span>}
            </div>
            {choice && choice.compatible.length === 0 ? (
              <div className="p-play-none">
                <Icon name="info" size={16} />
                <div>
                  <p>
                    {server.recommendedVersion
                      ? t("partners.play.noneFitsRecommended", { versions: versions ?? "", version: server.recommendedVersion })
                      : t("partners.play.noneFits", { versions: versions ?? "" })}
                  </p>
                  <Button size="sm" icon="plus" onClick={() => (onClose(), navigate("profiles", "new"))}>
                    {t("partners.play.newProfile")}
                  </Button>
                </div>
              </div>
            ) : choosing || !profile ? (
              <div className="p-play-profiles" role="radiogroup" aria-label={t("partners.play.profile")}>
                {choice?.compatible.map((p) => profileRow(p, true, p.id === profileId))}
                {choice?.incompatible.map((p) => profileRow(p, false, false))}
              </div>
            ) : (
              <div className="p-play-chosen">
                {profileRow(profile, versionFits(profile.gameVersion, server.versions), true)}
                {(choice?.compatible.length ?? 0) > 1 && (
                  <Button size="sm" variant="ghost" onClick={() => setChoosing(true)}>
                    {t("partners.play.change")}
                  </Button>
                )}
              </div>
            )}
          </div>

          {profile && (
            <ul className="p-play-steps">
              <li>
                <Icon name={preview?.listed ? "check" : "plus"} size={15} />
                <span>
                  {preview?.listed
                    ? t("partners.play.alreadyListed", { name: partner.name, profile: profile.name })
                    : t("partners.play.willAdd", { name: partner.name, address: serverAddress(server), profile: profile.name })}
                </span>
              </li>
              <li>
                <Icon name="play" size={15} />
                <span>{t("partners.play.willJoin", { profile: profile.name })}</span>
              </li>
            </ul>
          )}

          {!store.account && <p className="p-play-problem">{t("partners.play.needsAccount")}</p>}
          {previewError && <p className="p-play-problem">{previewError}</p>}
          {preview?.blocked && <p className="p-play-problem">{preview.blocked}</p>}
          {preview?.listProblem && <p className="p-play-note">{t("partners.play.listProblem", { problem: preview.listProblem })}</p>}
          {preview && preview.othersRunning.length > 0 && !preview.blocked && <p className="p-play-note">{t("partners.play.othersRunning", { names: list(preview.othersRunning) })}</p>}
        </div>
      )}
    </Modal>
  );
}
