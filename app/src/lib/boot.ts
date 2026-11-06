/**
 * Start-up helpers. Only what the first screen genuinely needs is awaited before rendering (app
 * info, settings for the theme, onboarding state); everything else loads afterwards. Each of those
 * requests is local and normally answers in milliseconds, so a generous per-request limit turns a
 * stalled backend into a visible, recoverable error instead of an endless start-up screen.
 */
import { t } from "./i18n.ts";

export const BOOT_TIMEOUT_MS = 15_000;

export class BootTimeout extends Error {
  readonly step: string;
  constructor(step: string, ms = BOOT_TIMEOUT_MS) {
    super(`${step} didn't respond within ${ms / 1000} seconds.`);
    this.name = "BootTimeout";
    this.step = step;
  }
}

/** Rejects with `BootTimeout` if `work` hasn't settled after `ms`. The work itself isn't cancelled. */
export function withTimeout<T>(work: Promise<T>, step: string, ms = BOOT_TIMEOUT_MS): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new BootTimeout(step, ms)), ms);
  });
  return Promise.race([work, timeout]).finally(() => clearTimeout(timer));
}

export type BootFailure = { title: string; body: string; detail: string };

/**
 * A short explanation for players plus technical details, which are only shown on request. The details
 * stay in English (they are for bug reports), like the backend's own errors.
 */
export function describeBootFailure(error: unknown): BootFailure {
  const detail = error instanceof Error ? `${error.name}: ${error.message}` : String(error);
  if (error instanceof BootTimeout) {
    return { title: t("boot.timeout.title"), body: t("boot.timeout.body"), detail };
  }
  return { title: t("boot.failed.title"), body: t("boot.failed.body"), detail };
}
