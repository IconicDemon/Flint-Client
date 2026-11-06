// The release notes live in releaseNotes.json so the in-game runtime (game-runtime/) bundles the
// same text for its "What's new" card.
import notes from "./releaseNotes.json";

/** `page_url`: the version's page on flintclient.com, exactly https://flintclient.com/releases/<version>/. */
export type ReleaseNote = { version: string; date: string; page_url: string; title: string; summary: string; points: string[] };

export const RELEASE_NOTES: ReleaseNote[] = notes;
