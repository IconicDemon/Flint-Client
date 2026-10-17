import { useCallback, useEffect, useState } from "react";
import { Onboarding } from "./features/onboarding/Onboarding";
import { Shell } from "./features/shell/Shell";
import { nextPaint, PERF, perfMark } from "./lib/perf";
import { useStoreSelect } from "./lib/store";

export default function App({ onboarding }: { onboarding: Record<string, unknown> }) {
  // Only what App itself uses, so download progress doesn't re-render the whole tree from here.
  const store = useStoreSelect((s) => ({ info: s.info, reloadProfiles: s.reloadProfiles, reloadAccounts: s.reloadAccounts }));
  const [showOnboarding, setShowOnboarding] = useState(() => !onboarding.completed || store.info.forceOnboarding);
  const finish = useCallback(() => {
    setShowOnboarding(false);
    void store.reloadProfiles();
    void store.reloadAccounts();
  }, [store]);

  useEffect(() => {
    if (!PERF) return;
    perfMark("firstCommit");
    void nextPaint().then(() => perfMark("firstPaint"));
  }, []);

  return showOnboarding ? <Onboarding initial={onboarding} onDone={finish} /> : <Shell />;
}
