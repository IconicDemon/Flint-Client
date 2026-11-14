// Screenshots of the wardrobe (Store page → cosmetics) against the browser dev mock: every category, a selected item,
// an empty state, the skin changer (add by username, select, Apply: the mock records it, no real account is touched) and
// outfits, at 1280×800 and 1920×1080.
//
//   node scripts/wardrobe-shots.cjs <out dir> [base url]   (vite running: npx vite --port 5821 --strictPort)
//
// Needs playwright-core (FLINT_PLAYWRIGHT, or the playwright-core package on the module path) and Microsoft Edge.
const path = require("node:path");
const fs = require("node:fs");
const { chromium } = require(process.env.FLINT_PLAYWRIGHT || "playwright-core");

const out = path.resolve(process.argv[2] || "wardrobe-shots");
const base = process.argv[3] || "http://localhost:5821";
fs.mkdirSync(out, { recursive: true });

const CATEGORIES = ["Capes", "Wings", "Hats", "Auras", "Pets", "Shields", "Gloves", "Emotes", "Bodywear", "Outfits", "Skin Changer", "Badges"];

async function open(page, store) {
  await page.goto(`${base}/?demo&reset&platform=windows&page=store&store=${store}`, { waitUntil: "networkidle", timeout: 60000 });
  await page.waitForSelector(".wardrobe", { timeout: 20000 });
  await page.waitForTimeout(1200);
}

const category = async (page, name) => {
  await page.locator(".wd-cat", { hasText: name }).first().click();
  await page.waitForTimeout(700);
};

(async () => {
  const browser = await chromium.launch({ channel: "msedge", headless: true });
  for (const [w, h] of [
    [1280, 800],
    [1920, 1080],
  ]) {
    const size = `${w}x${h}`;
    const page = await browser.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: 1 });
    page.on("pageerror", (e) => console.error("page error:", e.message));
    const shot = async (name) => {
      await page.waitForTimeout(500);
      await page.screenshot({ path: path.join(out, `${size}-${name}.png`) });
      console.log("shot", `${size}-${name}.png`);
    };

    await open(page, "v2,anim");
    for (const name of CATEGORIES) {
      await category(page, name);
      await shot(`cat-${name.toLowerCase().replace(/\s+/g, "-")}`);
      // Each 3D kind selected, on the preview.
      if (["Auras", "Shields", "Gloves"].includes(name)) {
        await page.locator(".wd-card").first().click();
        await page.waitForTimeout(700);
        await shot(`selected-${name.toLowerCase()}`);
      }
    }

    // A selected item (wings, not worn) on the preview, then equipped.
    await category(page, "Wings");
    await page.locator(".wd-card", { hasText: "Frost Wings" }).click();
    await shot("selected-wings");
    await page.locator(".wd-panel .btn", { hasText: "Equip" }).click();
    await page.waitForTimeout(900);
    await shot("selected-wings-equipped");
    await category(page, "Pets");
    await page.locator(".wd-card").first().click();
    await shot("selected-pet");

    // The emote wheel: pick an emote, then a place.
    await category(page, "Emotes");
    await page.locator(".wd-card", { hasText: "Dab" }).click();
    await page.waitForTimeout(300);
    await shot("emotes-placing");
    await page.locator(".wd-wheel-slot").nth(3).click();
    await page.waitForTimeout(800);
    await shot("emotes-placed");

    // Outfits: create one from what is worn, then select it.
    await category(page, "Outfits");
    await page.locator(".wd-outfit-new").click();
    await page.waitForTimeout(300);
    await page.locator(".wd-name-form input").fill("Ember set");
    await page.locator(".wd-name-form button[type=submit]").click();
    await page.waitForTimeout(700);
    await shot("outfits-saved");

    // The skin changer: add by username, select it, choose Slim, Apply (mocked).
    await category(page, "Skin Changer");
    await page.locator(".wd-skin-add input").fill("Kestrel");
    await page.locator(".wd-skin-add button[type=submit]").click();
    await page.waitForSelector(".wd-skin", { timeout: 5000 });
    await page.waitForTimeout(600);
    await shot("skins-added");
    await page.locator(".wd-skin-add input").fill("not a name!");
    await page.locator(".wd-skin-add button[type=submit]").click();
    await page.waitForTimeout(300);
    await shot("skins-error");
    await page.locator(".wd-skin-add input").fill("");
    await page.locator(".wd-skin-pick").first().click();
    await page.waitForTimeout(800);
    await shot("skins-selected");
    await page.locator(".wd-panel .seg button, .wd-panel [role=radio]", { hasText: "Slim" }).first().click().catch(() => {});
    await page.locator(".wd-panel .btn", { hasText: "Apply" }).click();
    await page.waitForTimeout(1300);
    await shot("skins-applied");
    const applied = await page.evaluate(() => window.__flintAppliedSkins ?? []);
    console.log("applied (mock only):", JSON.stringify(applied));

    // An empty state: an account that owns nothing.
    await open(page, "empty,v2");
    await category(page, "Pets");
    await shot("empty-pets");

    // A kind Flint doesn't know yet shows under More.
    await open(page, "v2,future");
    await category(page, "More");
    await page.locator(".wd-card").first().click();
    await shot("more-unknown-kind");
    await page.close();
  }
  await browser.close();
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
