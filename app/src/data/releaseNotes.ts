// The release notes live in releaseNotes.json so the in-game runtime (game-runtime/) bundles the
// same text for its "What's new" card.
import notes from "./releaseNotes.json";

export type ReleaseNote = { version: string; date: string; title: string; summary: string; points: string[] };

export const RELEASE_NOTES: ReleaseNote[] = notes;
