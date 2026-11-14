import assert from "node:assert/strict";
import { test } from "node:test";
import { storeLink } from "./storeLinks.ts";

test("a kind opens the Store's catalog for it; none opens the Store's home", () => {
  assert.equal(storeLink("https://store.flintclient.com", "wings"), "https://store.flintclient.com/cosmetics/?kind=wings");
  assert.equal(storeLink("https://store.flintclient.com/", "headwear"), "https://store.flintclient.com/cosmetics/?kind=headwear");
  assert.equal(storeLink("https://store.flintclient.com"), "https://store.flintclient.com/");
  assert.equal(storeLink("https://store.flintclient.com", null), "https://store.flintclient.com/");
  assert.equal(storeLink("https://store.flintclient.com", "x&y=1"), "https://store.flintclient.com/", "only a plain kind name");
});
