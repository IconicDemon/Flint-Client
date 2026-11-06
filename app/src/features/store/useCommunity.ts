/**
 * The Store page's community rewards for one account: the public catalog (Rust asks the Store at most once a minute, with
 * its ETag) and the account's standing on each reward, again whenever the inventory changes (the Store bumps the
 * inventory's version for every grant, eligibility change and unlink), and "Check again".
 *
 * The page mounts this per account (StoreAccount is keyed by the account), and every answer is checked to be for this
 * account, so another account's rewards are never shown. Before the Store has these routes, the catalog answers `off`
 * and nothing is asked or shown.
 */
import { useCallback, useEffect, useRef, useState } from "react";
import { api, errorText } from "../../lib/api";
import { forAccount, type CommunityCatalog, type RewardsView } from "../../lib/community";

export type RecheckState = { busy: boolean; nextAllowedAt: number | null; error: string | null };

export function useCommunity(uuid: string, live: boolean, version: number | null, reloadInventory: () => Promise<void>) {
  const [catalog, setCatalog] = useState<CommunityCatalog | null>(null);
  const [rewards, setRewards] = useState<RewardsView | null>(null);
  const [recheck, setRecheck] = useState<RecheckState>({ busy: false, nextAllowedAt: null, error: null });
  const busy = useRef(false);

  // The catalog: with the page and after each inventory change (Rust answers from its copy within the minute).
  useEffect(() => {
    if (!live) return;
    let cancelled = false;
    api.storeCommunityCatalog().then(
      // An unreachable Store keeps what was shown.
      (next) => !cancelled && setCatalog((shown) => (next.state === "unavailable" && shown ? shown : next)),
      () => {},
    );
    return () => {
      cancelled = true;
    };
  }, [live, version]);

  const listed = !!catalog && catalog.state === "ok" && catalog.enabled && catalog.rewards.length > 0;

  // The account's standing: only while something is listed, and again when the inventory's version moves.
  useEffect(() => {
    if (!live || !listed) return;
    let cancelled = false;
    api.storeRewards(uuid).then(
      (next) => {
        const mine = forAccount(uuid, next);
        if (!cancelled && mine) setRewards((shown) => (mine.state === "unavailable" && shown?.state === "ok" ? shown : mine));
      },
      () => {},
    );
    return () => {
      cancelled = true;
    };
  }, [uuid, live, listed, version]);

  /** "Check again": once, never looped; a 429 (or Flint's own minute) answers when it may run again. */
  const checkAgain = useCallback(async () => {
    if (busy.current) return;
    busy.current = true;
    setRecheck((r) => ({ ...r, busy: true, error: null }));
    try {
      const answer = await api.storeRewardsRecheck(uuid);
      if (answer.state === "ok") {
        const mine = forAccount(uuid, answer.rewards);
        if (mine) setRewards(mine);
        setRecheck({ busy: false, nextAllowedAt: Date.now() + 60_000, error: null });
        // What it unlocked (or ended) is in the inventory: the Store page shows it now rather than at the next change check.
        await reloadInventory();
      } else if (answer.state === "limited") {
        setRecheck({ busy: false, nextAllowedAt: answer.nextAllowedAt, error: null });
      } else {
        setRecheck({ busy: false, nextAllowedAt: null, error: null });
      }
    } catch (e) {
      setRecheck({ busy: false, nextAllowedAt: null, error: errorText(e) });
    } finally {
      busy.current = false;
    }
  }, [uuid, reloadInventory]);

  return { catalog, rewards, recheck, checkAgain };
}
