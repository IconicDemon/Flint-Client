/**
 * Flint AI's search allowance as players see it. The server is the only authority: these helpers
 * only phrase its numbers. Countdowns are durations the server computed with its own clock
 * (`resetInMs`), advanced locally with a monotonic timer, so a wrong system clock can't change them.
 */
import { t } from "./i18n.ts";

export type Quota = {
  limit: number;
  remaining: number;
  resetInMs: number | null;
};

/** Reads `AI_QUOTA:{...}` errors from the backend (out of searches). */
export function quotaFromError(code: string): Quota | null {
  if (!code.startsWith("AI_QUOTA")) return null;
  try {
    const q = JSON.parse(code.slice("AI_QUOTA:".length)) as Partial<Quota>;
    const limit = typeof q.limit === "number" && q.limit > 0 ? q.limit : 3;
    return {
      limit,
      remaining: 0,
      resetInMs: typeof q.resetInMs === "number" && q.resetInMs >= 0 ? q.resetInMs : null,
    };
  } catch {
    return { limit: 3, remaining: 0, resetInMs: null };
  }
}

/** "11h 24m", "24m", "under a minute". */
export function formatDuration(ms: number): string {
  const minutes = Math.ceil(ms / 60_000);
  if (minutes <= 1) return t(ms <= 0 ? "ai.duration.moment" : "ai.duration.underMinute");
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  if (!h) return t("ai.duration.minutes", { m });
  return m ? t("ai.duration.hoursMinutes", { h, m }) : t("ai.duration.hours", { h });
}

/** The quota pill: "3 searches remaining", "1 search left", "Your searches reset in 11h 24m". */
export function quotaLabel(q: Quota, msLeft: number | null = q.resetInMs): string {
  if (q.remaining >= 1) return t("ai.quota.remaining", { count: q.remaining });
  return msLeft !== null && msLeft > 0 ? t("ai.quota.resetIn", { duration: formatDuration(msLeft) }) : t("ai.quota.resetSoon");
}
