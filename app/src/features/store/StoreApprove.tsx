import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { api, errorText } from "../../lib/api";
import { useT } from "../../lib/i18n";
import { normalizeStoreCode } from "../../lib/storeSync";
import { Button, TextField } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { Modal, useToast } from "../../ui/overlays";

/**
 * Approving a Flint Store sign-in shown in a browser (from the Store page's menu): the player types the code the Store
 * website shows, then confirms the code and the account before Flint approves it. Which Store's codes this Flint
 * approves is said in both steps.
 */
export function StoreApproveDialog({ open, onClose, uuid, name, host, disabled }: { open: boolean; onClose: () => void; uuid: string; name: string; host: string; disabled: boolean }) {
  const t = useT();
  const toast = useToast();
  const [code, setCode] = useState("");
  const [confirming, setConfirming] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const normalized = normalizeStoreCode(code);
  const approveButton = useRef<HTMLButtonElement>(null);
  const codeField = useRef<HTMLInputElement>(null);
  const stepped = useRef(false);

  // Each step starts where the player goes on: the approve button, or the code again after Back.
  useEffect(() => {
    if (confirming) approveButton.current?.focus();
    else if (stepped.current) codeField.current?.focus();
    stepped.current = !!confirming;
  }, [confirming]);

  const latest = useRef({ busy, onClose });
  latest.current = { busy, onClose };
  // One function for the dialog's whole life: the Modal sets focus and Escape up again whenever it changes.
  const close = useCallback(() => {
    if (latest.current.busy) return;
    setConfirming(null);
    setError(null);
    latest.current.onClose();
  }, []);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (normalized && !disabled) {
      setError(null);
      setConfirming(normalized);
    }
  };
  const approve = async () => {
    if (!confirming) return;
    setBusy(true);
    setError(null);
    try {
      await api.storeApproveLink(uuid, confirming);
      setConfirming(null);
      setCode("");
      onClose();
      toast({ tone: "success", title: t("store.approve.done"), body: t("store.approve.doneBody", { name }) });
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      open={open}
      onClose={close}
      title={confirming ? t("store.approve.confirmTitle") : t("store.approve.title")}
      width={460}
      footer={
        confirming ? (
          <>
            <Button variant="ghost" onClick={() => setConfirming(null)} disabled={busy}>
              {t("common.back")}
            </Button>
            <Button ref={approveButton} variant="primary" loading={busy} onClick={() => void approve()}>
              {t("store.approve.confirm", { name })}
            </Button>
          </>
        ) : (
          <>
            <Button variant="ghost" onClick={close}>
              {t("common.cancel")}
            </Button>
            <Button variant="primary" type="submit" form="store-approve-form" disabled={disabled || !normalized}>
              {t("common.continue")}
            </Button>
          </>
        )
      }
    >
      {confirming ? (
        <>
          <p className="muted modal-copy">{t.rich("store.approve.confirmIntro", { name: <strong>{name}</strong> })}</p>
          <p className="store-code" aria-label={t("store.approve.codeAria", { code: confirming })}>
            {confirming}
          </p>
          <p className="muted modal-copy">{t("store.approve.confirmWarning", { host })}</p>
          {error && (
            <p className="install-warn" role="alert">
              <Icon name="alert" size={15} />
              {error}
            </p>
          )}
        </>
      ) : (
        <form id="store-approve-form" className="store-code-form" onSubmit={submit}>
          <p className="muted modal-copy">{disabled ? t("store.approve.offline") : t("store.approve.intro", { host, name })}</p>
          <TextField
            ref={codeField}
            label={t("store.approve.codeLabel")}
            placeholder="ABCD-1234"
            value={code}
            maxLength={12}
            autoComplete="off"
            spellCheck={false}
            disabled={disabled}
            data-autofocus
            onChange={(e) => setCode(e.target.value)}
          />
        </form>
      )}
    </Modal>
  );
}
