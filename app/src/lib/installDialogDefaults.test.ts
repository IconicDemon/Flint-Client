import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

// Owner rule (1.0.4): a resource pack or shader installed while the game runs lands in its folder and is NOT turned on.
// The install dialog's "turn on in the running game" box must start unticked, also each time the dialog opens again.
test("the install dialog never ticks 'turn on in the running game' by itself", () => {
  const src = readFileSync(new URL("../features/library/InstallDialog.tsx", import.meta.url), "utf8");
  assert.match(src, /useState\(false\)/);
  assert.doesNotMatch(src, /setEnableLive\(true\)/);
});
