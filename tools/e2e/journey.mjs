// The whole journey in a real browser against the real stack (no demo mode):
//   alice signs in on Keycloak's own page, searches, buys, watches the saga confirm the order,
//   a simulated courier delivers it while the page follows over SSE; a declined card ends on
//   the vermilion branch; ops signs in and sees the network. Screenshots and a JSON summary go
//   to OUT. Exits non-zero if any step does not happen.
//
//   node tools/e2e/journey.mjs   (env: WEB, TRACKING, OUT, JAVA)
import { spawn } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import puppeteer from "puppeteer";

const WEB = process.env.WEB ?? "http://localhost:5280";
const TRACKING = process.env.TRACKING ?? "http://localhost:8085";
const OUT = process.env.OUT ?? "build/e2e";
const JAVA = process.env.JAVA ?? "java";
mkdirSync(OUT, { recursive: true });
const summary = { steps: [] };
const t0 = Date.now();
const step = (name, extra = {}) => {
  const s = { name, atMs: Date.now() - t0, ...extra };
  summary.steps.push(s);
  console.log(`[${(s.atMs / 1000).toFixed(1)} s] ${name}`, Object.keys(extra).length ? JSON.stringify(extra) : "");
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await puppeteer.launch({ headless: true, args: ["--no-sandbox"] });
const errors = [];

async function signIn(page, user) {
  await page.goto(`${WEB}/`, { waitUntil: "networkidle0" });
  const buttons = await page.$$("header button");
  for (const b of buttons) {
    if ((await b.evaluate((e) => e.textContent)).trim() === "Sign in") {
      await b.click();
      break;
    }
  }
  await page.waitForSelector("#username", { timeout: 30000 }); // Keycloak's own login form
  await page.type("#username", user);
  await page.type("#password", user);
  await Promise.all([
    page.waitForNavigation({ waitUntil: "networkidle0", timeout: 30000 }),
    page.click("#kc-login"),
  ]);
  await page.waitForFunction(
    (u) => document.querySelector("header .who")?.textContent.includes(u),
    { timeout: 30000 },
    user,
  );
  step(`${user} signed in through Keycloak`);
}

async function nowSign(page) {
  return page.$eval(".platform dd", (e) => e.textContent.trim()).catch(() => "");
}

async function buy(page, query, pick) {
  await page.goto(`${WEB}/?q=${encodeURIComponent(query)}`, { waitUntil: "networkidle0" });
  await page.waitForSelector(".row-head", { timeout: 30000 });
  const heads = await page.$$(".row-head");
  const prices = await page.$$eval(".row-price", (els) => els.map((e) => e.textContent.trim()));
  const i = Math.max(0, prices.findIndex(pick));
  await heads[i].click();
  await page.waitForSelector(".buy-act .btn");
  const clicked = Date.now();
  await Promise.all([
    page.waitForFunction(() => location.pathname.startsWith("/orders/"), { timeout: 30000 }),
    page.click(".buy-act .btn"),
  ]);
  const orderId = page.url().split("/orders/")[1];
  step("order placed", { orderId, price: prices[i], clickToOrderPageMs: Date.now() - clicked });
  return orderId;
}

try {
  const ctx = await browser.createBrowserContext();
  const page = await ctx.newPage();
  page.on("pageerror", (e) => errors.push(e.message));
  await page.setViewport({ width: 1440, height: 900 });
  await signIn(page, "alice");

  // 1. a normal order: the saga confirms it, a courier delivers it
  const orderId = await buy(page, "kettle", (p) => !p.endsWith(".13") && !p.endsWith(".37"));
  const placedAt = Date.now();
  await page.waitForFunction(
    () => /READY AT FC|CONFIRMED/.test(document.querySelector(".platform dd")?.textContent ?? ""),
    { timeout: 120000 },
  );
  step("saga confirmed the order", { orderId, placedToConfirmedMs: Date.now() - placedAt, now: await nowSign(page) });
  await page.screenshot({ path: `${OUT}/1-confirmed.png`, fullPage: true });

  writeFileSync(`${OUT}/ids.txt`, `${orderId}\n`);
  const sim = spawn(JAVA, ["tools/simulator/CourierSim.java", TRACKING, "0", "1", "1", "90", `${OUT}/ids.txt`, "40"], {
    stdio: "inherit",
  });
  await page.waitForFunction(
    () => document.querySelectorAll(".reportlog tbody tr[data-kind='position']").length >= 5,
    { timeout: 90000 },
  );
  step("courier positions arriving over SSE", { now: await nowSign(page) });
  await page.screenshot({ path: `${OUT}/2-out-for-delivery.png`, fullPage: true });
  await page.waitForFunction(() => /DELIVERED/.test(document.querySelector(".platform dd")?.textContent ?? ""), {
    timeout: 120000,
  });
  step("delivered, seen live on the order page");
  await page.screenshot({ path: `${OUT}/3-delivered.png`, fullPage: true });
  sim.kill();

  // 2. a declined card: the seeded catalog has items priced at .13
  await buy(page, "Deluxe Camping Lantern 248", (p) => p.endsWith(".13"));
  await page.waitForFunction(() => /PAYMENT FAILED/.test(document.querySelector(".platform dd")?.textContent ?? ""), {
    timeout: 120000,
  });
  step("declined card ended on the failure branch");
  await page.screenshot({ path: `${OUT}/4-declined.png`, fullPage: true });

  // 3. ops sees the network and the orders
  const opsCtx = await browser.createBrowserContext();
  const ops = await opsCtx.newPage();
  ops.on("pageerror", (e) => errors.push(e.message));
  await ops.setViewport({ width: 1440, height: 900 });
  await signIn(ops, "ops");
  await ops.goto(`${WEB}/ops`, { waitUntil: "networkidle0" });
  await ops.waitForSelector(".ops-table tbody tr", { timeout: 30000 });
  await sleep(3000);
  const rows = await ops.$$eval(".ops-table tbody tr", (r) => r.length);
  step("ops network view and order search", {
    orderRows: rows,
    legend: await ops.$eval(".ops-legend", (e) => e.textContent.replace(/\s+/g, " ").trim()),
  });
  await ops.screenshot({ path: `${OUT}/5-ops.png`, fullPage: true });
  summary.orderId = orderId;
} catch (e) {
  summary.failed = String(e);
  console.error("E2E FAILED:", e);
} finally {
  summary.pageErrors = errors;
  writeFileSync(`${OUT}/summary.json`, JSON.stringify(summary, null, 2));
  await browser.close();
}
process.exit(summary.failed || errors.length ? 1 : 0);
