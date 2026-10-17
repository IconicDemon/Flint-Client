// Settings → Auto Sync: what the one quiet line under its switch says (pure, so it is tested with
// `npm test`). Nothing else about Auto Sync is shown anywhere in the launcher.
import type { AutoSyncStatus } from "./api";

export const WAITING_TEXT = "Waiting for your first game";

export type AutoSyncLine = { text: string; dismissId?: string } | null;

/** A notice (with its Dismiss) wins over "waiting"; nothing when Auto Sync is off or there's nothing to say. */
export function autoSyncLine(on: boolean, status: AutoSyncStatus | null): AutoSyncLine {
  if (!on || !status) return null;
  if (status.notice) return { text: status.notice.text, dismissId: status.notice.id };
  return status.waiting ? { text: WAITING_TEXT } : null;
}
