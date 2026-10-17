import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState, useSyncExternalStore, type ReactNode } from "react";
import { api, subscribe, type Account, type AppInfo, type Job, type Profile, type ProfileState } from "./api";

export type Skin = { dataUrl: string | null; capeUrl?: string | null; model: "classic" | "slim" };

type Store = {
  info: AppInfo;
  accounts: Account[];
  activeAccount: Account | null;
  profiles: Profile[];
  selectedId: string | null;
  selected: Profile | null;
  states: Record<string, ProfileState>;
  skins: Record<string, Skin>;
  jobs: Job[];
  contentRevision: Record<string, number>;
  reloadAccounts: () => Promise<void>;
  reloadProfiles: () => Promise<void>;
  selectProfile: (id: string) => Promise<void>;
  switchAccount: (uuid: string) => Promise<void>;
  refreshSkin: (uuid: string) => Promise<void>;
  touchContent: (profileId: string) => void;
  dismissJob: (id: string) => void;
};

/**
 * The store is published through a stable handle rather than as the context value itself, so a
 * change (for example a download progress event, several a second) only re-renders the components
 * that read the part that changed: `useStoreSelect` compares what a component selects, `useStore`
 * subscribes to everything as before.
 */
type StoreHandle = { get: () => Store; subscribe: (listener: () => void) => () => void };
const StoreContext = createContext<StoreHandle | null>(null);

export function StoreProvider({ info, children }: { info: AppInfo; children: ReactNode }) {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [activeUuid, setActiveUuid] = useState<string | null>(null);
  const [profiles, setProfiles] = useState<Profile[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [states, setStates] = useState<Record<string, ProfileState>>({});
  const [skins, setSkins] = useState<Record<string, Skin>>({});
  const [jobs, setJobs] = useState<Job[]>([]);
  const [contentRevision, setContentRevision] = useState<Record<string, number>>({});

  const touchContent = useCallback((profileId: string) => {
    setContentRevision((prev) => ({ ...prev, [profileId]: (prev[profileId] ?? 0) + 1 }));
  }, []);

  const reloadAccounts = useCallback(async () => {
    const result = await api.accounts();
    setAccounts(result.accounts);
    setActiveUuid(result.active);
  }, []);

  const reloadProfiles = useCallback(async () => {
    const [result, live] = await Promise.all([api.profiles(), api.profileStates()]);
    setProfiles(result.profiles);
    setSelectedId(result.selected ?? result.profiles[0]?.id ?? null);
    // The newer of the fetched state and the last event wins, by the backend's sequence number: a
    // fetch answered just before a final event can't put a finished download back to "Downloading",
    // and a missed final event can't leave a spinner forever.
    setStates((prev) => Object.fromEntries(Object.entries(live).map(([id, fetched]) => [id, prev[id] && seqOf(prev[id]) > seqOf(fetched) ? prev[id] : fetched])));
  }, []);

  useEffect(() => {
    void reloadAccounts();
    void reloadProfiles();
    void api.jobs().then(setJobs).catch(() => {});
    const offState = subscribe<ProfileState>("profile-state", (state) => {
      setStates((prev) => (prev[state.profileId] && seqOf(prev[state.profileId]) > seqOf(state) ? prev : { ...prev, [state.profileId]: state }));
      if (state.state === "ready" || state.state === "running") {
        void api.profiles().then((r) => setProfiles(r.profiles));
      }
    });
    const offJobs = subscribe<Job>("job", (job) => {
      setJobs((prev) => {
        const index = prev.findIndex((j) => j.id === job.id);
        if (index === -1) return [...prev, job];
        const next = prev.slice();
        next[index] = job;
        return next;
      });
      if (job.state === "done") {
        if (job.profileId) touchContent(job.profileId);
        if (job.kind === "modpack") void reloadProfiles();
      }
    });
    return () => {
      offState();
      offJobs();
    };
  }, [reloadAccounts, reloadProfiles, touchContent]);

  useEffect(() => {
    for (const account of accounts) {
      if (skins[account.uuid]) continue;
      setSkins((prev) => ({ ...prev, [account.uuid]: { dataUrl: null, model: account.skinModel ?? "classic" } }));
      void api
        .skin(account.uuid)
        .then((skin) => setSkins((prev) => ({ ...prev, [account.uuid]: skin })))
        .catch(() => {});
    }
  }, [accounts, skins]);

  const dismissJob = useCallback((id: string) => {
    setJobs((prev) => prev.filter((j) => j.id !== id));
    void api.dismissJob(id).catch(() => {});
  }, []);

  const refreshSkin = useCallback(async (uuid: string) => {
    const skin = await api.skin(uuid);
    setSkins((prev) => ({ ...prev, [uuid]: skin }));
  }, []);

  const selectProfile = useCallback(async (id: string) => {
    let previous: string | null = null;
    setSelectedId((current) => {
      previous = current;
      return id;
    });
    try {
      await api.selectProfile(id);
    } catch (e) {
      setSelectedId(previous);
      throw e;
    }
  }, []);

  const switchAccount = useCallback(async (uuid: string) => {
    setActiveUuid(uuid);
    await api.setActiveAccount(uuid);
  }, []);

  const value = useMemo<Store>(() => {
    const activeAccount = accounts.find((a) => a.uuid === activeUuid) ?? null;
    const selected = profiles.find((p) => p.id === selectedId) ?? profiles[0] ?? null;
    return {
      info,
      accounts,
      activeAccount,
      profiles,
      selectedId: selected?.id ?? null,
      selected,
      states,
      skins,
      jobs,
      contentRevision,
      reloadAccounts,
      reloadProfiles,
      selectProfile,
      switchAccount,
      refreshSkin,
      touchContent,
      dismissJob,
    };
  }, [info, accounts, activeUuid, profiles, selectedId, states, skins, jobs, contentRevision, reloadAccounts, reloadProfiles, selectProfile, switchAccount, refreshSkin, touchContent, dismissJob]);

  const current = useRef(value);
  const listeners = useRef(new Set<() => void>());
  useLayoutEffect(() => {
    if (current.current === value) return;
    current.current = value;
    for (const listener of listeners.current) listener();
  }, [value]);
  const handle = useMemo<StoreHandle>(
    () => ({
      get: () => current.current,
      subscribe: (listener) => {
        listeners.current.add(listener);
        return () => listeners.current.delete(listener);
      },
    }),
    [],
  );

  return <StoreContext.Provider value={handle}>{children}</StoreContext.Provider>;
}

function useHandle() {
  const handle = useContext(StoreContext);
  if (!handle) throw new Error("StoreProvider missing");
  return handle;
}

/** The whole store; the component re-renders on every change. Prefer `useStoreSelect` in components that stay mounted. */
export function useStore() {
  const handle = useHandle();
  return useSyncExternalStore(handle.subscribe, handle.get);
}

/**
 * Part of the store; the component re-renders only when the selected value changes (compared
 * shallowly by default: equal primitives, or objects and arrays whose entries are the same).
 */
export function useStoreSelect<T>(selector: (store: Store) => T, isEqual: (a: T, b: T) => boolean = shallowEqual): T {
  const handle = useHandle();
  const last = useRef<{ value: T } | null>(null);
  const snapshot = () => {
    const next = selector(handle.get());
    if (last.current && isEqual(last.current.value, next)) return last.current.value;
    last.current = { value: next };
    return next;
  };
  return useSyncExternalStore(handle.subscribe, snapshot);
}

export function shallowEqual<T>(a: T, b: T): boolean {
  if (Object.is(a, b)) return true;
  if (typeof a !== "object" || typeof b !== "object" || a === null || b === null) return false;
  const ka = Object.keys(a);
  const kb = Object.keys(b);
  if (ka.length !== kb.length) return false;
  for (const k of ka) {
    if (!Object.is((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k])) return false;
  }
  return true;
}

/** The backend numbers every state it reports; states it computed without an event have none. */
export const seqOf = (s: ProfileState) => (s as ProfileState & { seq?: number }).seq ?? 0;

export const isBusy = (s?: ProfileState) =>
  !!s && (s.state === "checking" || s.state === "downloading" || s.state === "installing" || s.state === "launching");

export const jobActive = (j: Job) => j.state === "queued" || j.state === "running";

export function stateOf(states: Record<string, ProfileState>, profile: Profile | null): ProfileState {
  if (!profile) return { profileId: "", state: "uninstalled" };
  return states[profile.id] ?? { profileId: profile.id, state: profile.installed ? "ready" : "uninstalled" };
}
