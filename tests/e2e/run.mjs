// End-to-end test for the Chrome extension, against a real Google Slides page.
//
// It starts a fake phone (a WebSocket server that answers like the Android app), opens the
// slides in Chromium with the extension loaded, and checks that:
//   - the slide count, the slide images and the current slide number reach the phone,
//   - "next" / "prev" from the phone move the slides, in the editor and in the slideshow,
//   - a pinch plays and pauses a YouTube video in the deck, if there is one,
//   - when Google's page changes (simulated by renaming the bottom-bar buttons), the extension
//     falls back to another method, or warns the presenter.
//
// Usage: node run.mjs <public Google Slides URL with 2 or more slides> [path to chrome.exe]

import { chromium } from "playwright";
import { WebSocketServer } from "ws";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const EXT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../chrome-extension");
const [slidesUrl, executablePath] = process.argv.slice(2);
if (!slidesUrl) {
  console.error("Usage: node run.mjs <Google Slides URL> [chrome.exe]");
  process.exit(2);
}

const PIN = "1234";
let received = [];
let phone = null;
let failures = 0;

function check(name, ok, detail) {
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${ok ? "" : `  ${JSON.stringify(detail)}`}`);
  if (!ok) failures++;
}
const slides = () => received.filter((m) => m.type === "slide").map((m) => m.index);
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
async function sendCmd(action) {
  received = [];
  phone.send(JSON.stringify({ type: "navigate", action }));
  await wait(3500);
}

const wss = new WebSocketServer({ port: 8765 });
wss.on("connection", (socket) => {
  socket.on("message", (raw) => {
    const m = JSON.parse(raw.toString());
    if (m.type === "hello") {
      socket.send(JSON.stringify(m.pin === PIN && m.version === 3 ? { type: "welcome", version: 3 } : { type: "error", message: "bad pin" }));
      phone = socket;
    }
    if (m.type !== "ping") received.push(m);
  });
});

const ctx = await chromium.launchPersistentContext(fs.mkdtempSync(path.join(os.tmpdir(), "pg-")), {
  headless: false, // Extensions need a headed browser. The window is placed off screen.
  executablePath,
  args: [`--disable-extensions-except=${EXT}`, `--load-extension=${EXT}`, "--window-position=-2000,0"],
  viewport: { width: 1280, height: 800 },
});

try {
  const sw = ctx.serviceWorkers()[0] || (await ctx.waitForEvent("serviceworker"));
  await sw.evaluate((pin) => chrome.storage.local.set({ host: "127.0.0.1", port: 8765, pin }), PIN);

  const page = await ctx.newPage();
  ctx.on("console", (m) => m.text().includes("[PresentationGlass]") && console.log("      ", m.text()));
  await page.goto(slidesUrl, { waitUntil: "domcontentloaded" });
  await wait(8000);

  const deck = received.filter((m) => m.type === "deck").pop();
  check("スライドの枚数が届く", deck && deck.count >= 2, deck);
  check("編集画面の番号が届く", slides().includes(0), received);
  const images = received.filter((m) => m.type === "slide-image");
  check(
    "スライドの画像が届く",
    deck && images.length === deck.count && images.every((m) => m.data.length > 1000),
    images.map((m) => ({ index: m.index, title: m.title, bytes: m.data.length })),
  );

  await sendCmd("next");
  check("編集画面で次へ", slides().includes(1), received);
  await sendCmd("prev");
  check("編集画面で前へ", slides().at(-1) === 0, received);

  await page.keyboard.press("Control+Shift+F5"); // Start the slideshow from the beginning.
  await wait(5000);
  await sendCmd("next");
  check("スライドショーで次へ", slides().includes(1), received);
  await sendCmd("prev");
  check("スライドショーで前へ", slides().at(-1) === 0, received);

  // Play and pause a YouTube video, if the deck has one. Walk forward to the slide that shows it.
  const hasVideoFrame = () => page.frames().some((f) => /youtube/.test(f.url()));
  let steps = 0;
  while (!hasVideoFrame() && steps < (deck ? deck.count : 0)) {
    await page.keyboard.press("ArrowRight");
    await wait(1500);
    steps++;
  }
  if (hasVideoFrame()) {
    await wait(2000); // Let the YouTube player load.
    received = [];
    phone.send(JSON.stringify({ type: "playback", action: "toggle" }));
    await wait(4000);
    check("つまむと動画が再生される", received.some((m) => m.type === "playback" && m.playing), received);
    received = [];
    phone.send(JSON.stringify({ type: "playback", action: "toggle" }));
    await wait(3000);
    check("もう一度つまむと動画が止まる", received.some((m) => m.type === "playback" && !m.playing), received);
  } else {
    console.log("SKIP  動画のスライドがないので、動画の試験は飛ばしました");
  }
  for (let i = 0; i < steps; i++) {
    await page.keyboard.press("ArrowLeft");
    await wait(800);
  }
  await wait(1500);

  // Simulate a page change by Google: the bottom-bar buttons get other class names.
  await page.evaluate(() => {
    const doc = document.querySelector("iframe.punch-present-iframe").contentDocument;
    for (const el of doc.querySelectorAll(".punch-viewer-navbar-next,.punch-viewer-navbar-prev")) {
      el.className = "renamed";
    }
  });
  await sendCmd("next");
  check("ボタンがなくても予備の方法で次へ", slides().includes(1), received);
  await sendCmd("prev");
  const warned = received.some((m) => m.type === "status" && m.level === "warn");
  check("前へに失敗したら警告が届く", warned, received);
} finally {
  await ctx.close();
  wss.close();
}

console.log(failures === 0 ? "\nすべて成功しました" : `\n${failures} 件失敗しました`);
process.exit(failures === 0 ? 0 : 1);
