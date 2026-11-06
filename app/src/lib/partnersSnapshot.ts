/*
 * The window's copy of the partner listings, as a pure function of the launcher's answers (partnersFeed.ts keeps it and
 * talks to the launcher). Kept apart so it is tested without the bridge (partnersSnapshot.test.ts).
 */
import type { PartnersAnswer } from "./api";
import { parsePartnerFeed, type Dropped, type PartnerFeed } from "./partners.ts";

/**
 * `loading`: nothing known yet. `ready`: listings to show (maybe none). `offline` / `unavailable`: nothing to show, and
 * the Store can't be reached / isn't answering.
 */
export type FeedStatus = "loading" | "ready" | "offline" | "unavailable";

export type PartnersSnapshot = {
  status: FeedStatus;
  feed: PartnerFeed | null;
  /** The listings shown are the cached copy: the Store couldn't be asked just now. */
  stale: boolean;
  /** When the Store last sent or confirmed them (ms). */
  fetchedAt: number | null;
  mediaBase: string;
  favorites: string[];
};

export const INITIAL: PartnersSnapshot = { status: "loading", feed: null, stale: false, fetchedAt: null, mediaBase: "", favorites: [] };

/**
 * The snapshot after an answer, given the body text the current snapshot was parsed from. Pure (tested). The same body
 * keeps the same feed object, and a snapshot equal to the last one is the last one, so nothing re-renders. A cached-only
 * answer with nothing in it (`final` false) leaves the snapshot loading: the Store is asked next.
 */
export function applyAnswer(prev: PartnersSnapshot, prevRaw: string | null, answer: PartnersAnswer, final: boolean): { next: PartnersSnapshot; raw: string | null; dropped: Dropped[] } {
  let feed: PartnerFeed | null = null;
  let raw: string | null = null;
  let dropped: Dropped[] = [];
  if (answer.body != null) {
    raw = JSON.stringify(answer.body);
    if (raw === prevRaw && prev.feed) feed = prev.feed;
    else {
      const parsed = parsePartnerFeed(answer.body, { mediaProjects: answer.mediaProjects, allowLoopback: answer.allowLoopback });
      feed = parsed.feed;
      dropped = parsed.dropped;
      if (!feed) raw = null;
    }
  }
  const status: FeedStatus = feed ? "ready" : !final && !answer.problem ? "loading" : answer.problem === "offline" ? "offline" : answer.problem ? "unavailable" : answer.source === "none" ? "ready" : "unavailable";
  const next: PartnersSnapshot = {
    status,
    feed,
    stale: !!feed && !!answer.problem,
    fetchedAt: feed ? answer.fetchedAt : null,
    mediaBase: answer.mediaBase,
    favorites: prev.favorites,
  };
  const same = next.status === prev.status && next.feed === prev.feed && next.stale === prev.stale && next.fetchedAt === prev.fetchedAt && next.mediaBase === prev.mediaBase;
  return { next: same ? prev : next, raw, dropped };
}
