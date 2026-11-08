// The release notes live in releaseNotes.json so the in-game runtime (game-runtime/) bundles the
// same text for its "What's new" card.
import notes from "./releaseNotes.json";

/**
 * `page_url`: the version's page on flintclient.com, exactly https://flintclient.com/releases/<version>/. `image`: the release's cover on flintclient.com (the owner's art), passed to the public feed (scripts/release-notes-feed.mjs).
 */
export type ReleaseNote = {
  version: string;
  date: string;
  page_url: string;
  title: string;
  summary: string;
  image?: { url: string; width: number; height: number; alt: string };
  points: string[];
};

export const RELEASE_NOTES: ReleaseNote[] = notes;
