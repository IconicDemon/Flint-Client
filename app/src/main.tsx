import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import { api, initBridge } from "./lib/api";
import { describeBootFailure, withTimeout } from "./lib/boot";
import { applyAppearance, normalizeSettings, SettingsProvider } from "./lib/settings";
import { StoreProvider } from "./lib/store";
import { ToastProvider } from "./ui/overlays";
import { PerfArea, perfMark } from "./lib/perf";
import "./styles/tokens.css";
import "./styles/base.css";
import "./styles/components.css";
import "./styles/shell.css";
import "./styles/home.css";
import "./styles/pages.css";
import "./styles/library.css";
import "./styles/flintai.css";
import "./styles/profiles.css";
import "./styles/onboarding.css";

const root = ReactDOM.createRoot(document.getElementById("root") as HTMLElement);

// The web view's own menu (Back, Refresh, Save as, Print, Emoji…) doesn't belong in the app, text fields included;
// Ctrl+C/Ctrl+V still copy and paste.
if (import.meta.env.PROD) window.addEventListener("contextmenu", (e) => e.preventDefault());

perfMark("script");

async function boot() {
  await initBridge();
  // Only what the first screen needs is awaited here; accounts, profiles, skins and news load
  // after it renders. Each request is local, so a long silence means the backend is stuck.
  const [info, rawSettings, onboarding] = await Promise.all([
    withTimeout(api.appInfo(), "App info"),
    withTimeout(api.settingsGet(), "Settings"),
    withTimeout(api.onboardingGet(), "Onboarding state"),
  ]);
  perfMark("bootData");
  const settings = normalizeSettings(rawSettings);
  applyAppearance(settings, window.matchMedia("(prefers-reduced-motion: reduce)").matches);
  document.documentElement.dataset.platform = info.platform;
  root.render(
    <React.StrictMode>
      <ToastProvider>
        <SettingsProvider initial={settings}>
          <StoreProvider info={info}>
            <PerfArea id="app">
              <App onboarding={onboarding} />
            </PerfArea>
          </StoreProvider>
        </SettingsProvider>
      </ToastProvider>
    </React.StrictMode>,
  );
  // Spelled out (not PERF) so the bundler drops the scenarios chunk entirely from normal builds.
  if (import.meta.env.MODE === "perf" && info.perfRun) {
    // Measurement builds only: the scripted scenarios start once the app has settled.
    void import("./lib/perfScenarios").then((m) => m.runSuite(info.perfRun!));
  }
}

function StartupFailure({ error }: { error: unknown }) {
  const failure = describeBootFailure(error);
  const [retrying, setRetrying] = React.useState(false);
  return (
    <div className="fatal" role="alert">
      <h1>{failure.title}</h1>
      <p>{failure.body}</p>
      {/* Reloading restarts only the interface. The backend keeps running, so this can't start a
          second sign-in, download or game. */}
      <button
        type="button"
        className="btn btn-secondary btn-md"
        disabled={retrying}
        onClick={() => {
          setRetrying(true);
          window.location.reload();
        }}
      >
        {retrying ? "Retrying…" : "Try again"}
      </button>
      <details className="fatal-details">
        <summary>Show details</summary>
        <pre>{failure.detail}</pre>
      </details>
    </div>
  );
}

boot().catch((error) => {
  console.error("Flint start-up failed:", error);
  root.render(<StartupFailure error={error} />);
});
