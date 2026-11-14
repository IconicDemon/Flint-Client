// The owner's preview set of the wardrobe (browser dev mock) and its 500-item measurement:
// - capes with one selected, hats, the skin changer (1920×1080, three themes);
// - the launcher's minimum window (1080×660) and an ultrawide one (2560×1080);
// - 500 items: category switch, search and scroll timings.
//
//   node scripts/wardrobe-preview.cjs <out dir> [base url] [prefix]   (vite: npx vite --port 5821 --strictPort)
const path = require("node:path");
const fs = require("node:fs");
const { chromium } = require(process.env.FLINT_PLAYWRIGHT || "playwright-core");

const out = path.resolve(process.argv[2] || "wardrobe-preview");
const base = process.argv[3] || "http://localhost:5821";
const prefix = process.argv[4] || "9 launcher cosmetics - ";
fs.mkdirSync(out, { recursive: true });

async function open(page, store, theme) {
  await page.goto(`${base}/?demo&reset&platform=windows&page=store&store=${store}`, { waitUntil: "networkidle", timeout: 60000 });
  await page.waitForSelector(".wardrobe", { timeout: 20000 });
  await page.evaluate((t) => (document.documentElement.dataset.theme = t), theme);
  await page.waitForTimeout(900);
}
const category = async (page, name) => {
  await page.locator(".wd-cat", { hasText: name }).first().click();
  await page.waitForTimeout(600);
};

(async () => {
  const browser = await chromium.launch({ channel: "msedge", headless: true });
  const shot = async (page, name) => {
    await page.mouse.move(5, 5);
    await page.waitForTimeout(500);
    await page.screenshot({ path: path.join(out, `${prefix}${name}.png`) });
    console.log("shot", `${prefix}${name}.png`);
  };

  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  page.on("pageerror", (e) => console.error("page error:", e.message));

  await open(page, "v2,anim", "ember");
  await category(page, "Capes");
  await page.locator(".wd-card", { hasText: "Aurora Borealis" }).click();
  await page.waitForTimeout(900);
  await shot(page, "1 capes selected (Ember, 1920x1080)");

  await open(page, "v2,anim", "obsidian");
  await category(page, "Hats");
  await shot(page, "2 hats (Obsidian, 1920x1080)");

  await open(page, "v2,anim", "moss");
  await category(page, "Skin Changer");
  await page.locator(".wd-skin-add input").fill("Kestrel");
  await page.locator(".wd-skin-add button[type=submit]").click();
  await page.waitForSelector(".wd-skin");
  await page.locator(".wd-skin-add input").fill("Notch");
  await page.locator(".wd-skin-add button[type=submit]").click();
  await page.waitForTimeout(700);
  await page.locator(".wd-skin-pick").first().click();
  await page.waitForTimeout(900);
  await shot(page, "3 skin changer (Moss, 1920x1080)");

  await page.setViewportSize({ width: 1080, height: 660 });
  await open(page, "v2,anim", "ember");
  await category(page, "Wings");
  await page.locator(".wd-card").nth(1).click();
  await page.waitForTimeout(800);
  await shot(page, "4 minimum window (Ember, 1080x660)");

  await page.setViewportSize({ width: 2560, height: 1080 });
  await open(page, "v2,anim", "dusk");
  await category(page, "Pets");
  await page.locator(".wd-card").first().click();
  await page.waitForTimeout(800);
  await shot(page, "5 ultrawide (Dusk, 2560x1080)");
  await page.close();

  // 500 items: how long a category takes to show, a search to answer, and how smooth a scroll through 308 capes is.
  const perf = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  await open(perf, "v2,anim,scale", "ember");
  const timed = async (fn, settle) => {
    const t0 = Date.now();
    await fn();
    await perf.waitForFunction(settle, null, { timeout: 10000 });
    return Date.now() - t0;
  };
  const switchMs = await timed(() => perf.locator(".wd-cat", { hasText: "Capes" }).first().click(), () => document.querySelectorAll(".wd-card").length > 0);
  const counts = await perf.evaluate(() => ({ items: Number(document.querySelector(".wd-main-count")?.textContent), drawn: document.querySelectorAll(".wd-card").length }));
  const searchMs = await timed(() => perf.locator(".wd-search input").fill("Ember Cape 1"), () => Number(document.querySelector(".wd-main-count")?.textContent) < 50);
  await perf.locator(".wd-search input").fill("");
  await perf.waitForTimeout(400);
  const scroll = await perf.evaluate(
    () =>
      new Promise((resolve) => {
        const body = document.querySelector(".wd-main-body");
        const frames = [];
        let last = performance.now();
        const step = (now) => {
          frames.push(now - last);
          last = now;
          body.scrollTop += 60;
          if (body.scrollTop + body.clientHeight >= body.scrollHeight - 2 || frames.length > 600) {
            const sorted = [...frames.slice(1)].sort((a, b) => a - b);
            resolve({
              frames: sorted.length,
              avgMs: +(sorted.reduce((a, b) => a + b, 0) / sorted.length).toFixed(2),
              p95Ms: +sorted[Math.floor(sorted.length * 0.95)].toFixed(2),
              maxMs: +sorted[sorted.length - 1].toFixed(2),
              drawnAtEnd: document.querySelectorAll(".wd-card").length,
            });
          } else requestAnimationFrame(step);
        };
        requestAnimationFrame(step);
      }),
  );
  const result = { categorySwitchMs: switchMs, ...counts, searchMs, scroll };
  console.log("500-item measurement:", JSON.stringify(result));
  fs.writeFileSync(path.join(out, "wardrobe-500-items.json"), JSON.stringify(result, null, 2));
  await browser.close();
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
