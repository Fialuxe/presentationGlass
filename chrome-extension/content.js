// Core logic that runs inside a slide page. It does not know about any particular slide service:
// the page-specific parts come from an adapter in adapters/. This file
//   - sends an image of every slide and the current slide number to the background script,
//   - moves the slides when the glasses ask for "next" / "prev", and checks that it worked,
//   - reports problems, so that the presenter sees them on the glasses.

(() => {
  const POLL_INTERVAL_MS = 300;
  const VERIFY_TIMEOUT_MS = 1500;
  const RECONNECT_DELAY_MS = 1000;
  // Slide images are shrunk before sending. The glasses screen is 600 x 600 pixels.
  const IMAGE_WIDTH_PX = 560;
  const IMAGE_JPEG_QUALITY = 0.8;
  // Google refuses downloads that come too often (HTTP 429). A slideshow start reloads the deck
  // only when the last load is older than this, and failed downloads wait before retrying.
  const RELOAD_MIN_INTERVAL_MS = 5 * 60 * 1000;
  const RETRY_DELAYS_MS = [5000, 15000, 30000];
  const LOG_PREFIX = "[PresentationGlass]";

  const { protocol, adapters, i18n } = globalThis.PresentationGlass;
  const { t } = i18n;
  const { phone: MSG, internal: INTERNAL, level: LEVEL, statusCode: CODE } = protocol;

  const STATUS_TEXT = {
    // The phone shows known codes in its own language; this text is the fallback.
    [CODE.DECK_LOAD_FAILED]: { warn: t("status_deck_load_failed"), ok: t("status_deck_load_ok") },
    [CODE.SLIDE_NUMBER_UNREADABLE]: {
      warn: t("status_slide_number_unreadable"),
      ok: t("status_slide_number_ok"),
    },
    [CODE.NAVIGATION_FAILED]: {
      warn: t("status_navigation_failed"),
      ok: t("status_navigation_ok"),
    },
  };

  const adapter = adapters.find((a) => a.detect());
  if (!adapter) return;

  let port = null;
  let deck = null; // { title, count }, as sent to the phone
  let slideImages = []; // slide-image messages, by slide index
  let loading = null; // The running load, so that two loads never overlap.
  let lastLoadedAt = 0;
  let currentIndex = 0;
  let lastSentIndex = -1;
  let wasPresenting = false;
  let preferredMethodName = null; // The navigation method that worked last time.
  const activeWarnings = new Map(); // code -> status message, for problems not yet resolved.
  let navigationQueue = Promise.resolve();

  // ---- Connection to the background script ----

  function connectPort() {
    try {
      port = chrome.runtime.connect({ name: protocol.PORT_NAME });
    } catch (e) {
      port = null; // The extension was reloaded; this copy of the script is orphaned.
      return;
    }
    port.onMessage.addListener(onBackgroundMessage);
    port.onDisconnect.addListener(() => {
      port = null;
      // The service worker may have been stopped. Reconnecting wakes it up again.
      setTimeout(connectPort, RECONNECT_DELAY_MS);
    });
    if (deck) send({ type: MSG.DECK, ...deck });
    for (const image of slideImages) if (image) send(image);
    for (const warning of activeWarnings.values()) send(warning);
    lastSentIndex = -1;
  }

  function send(message) {
    if (!port) return;
    try {
      port.postMessage(message);
    } catch (e) {
      port = null;
    }
  }

  function onBackgroundMessage(message) {
    if (message.type === MSG.NAVIGATE) {
      navigationQueue = navigationQueue
        .then(() => navigate(message.action))
        .catch((e) => console.error(LOG_PREFIX, e));
    } else if (message.type === INTERNAL.RELOAD_DECK) {
      loadDeck({ force: true });
    }
  }

  // ---- Warnings shown on the glasses ----

  // detail is the technical reason. It goes to the extension's log, not to the glasses.
  function warn(code, detail = "") {
    if (activeWarnings.has(code)) return;
    const status = {
      type: MSG.STATUS,
      level: LEVEL.WARN,
      code,
      message: STATUS_TEXT[code].warn,
      detail,
    };
    activeWarnings.set(code, status);
    console.warn(LOG_PREFIX, status.message);
    send(status);
  }

  function resolve(code) {
    if (!activeWarnings.delete(code)) return;
    const status = { type: MSG.STATUS, level: LEVEL.OK, code, message: STATUS_TEXT[code].ok };
    console.info(LOG_PREFIX, status.message);
    send(status);
  }

  // ---- Deck ----

  async function fetchArrayBuffer(url) {
    try {
      const res = await fetch(url, { credentials: "include" });
      if (res.ok) return await res.arrayBuffer();
    } catch (e) {
      // Fall through: the download may redirect to another origin that blocks CORS here.
    }
    const reply = await chrome.runtime.sendMessage({ type: INTERNAL.FETCH_BASE64, url });
    if (!reply || !reply.ok) throw new Error(reply ? reply.error : "no reply from background");
    const binary = atob(reply.data);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes.buffer;
  }

  const sleep = (ms) => new Promise((done) => setTimeout(done, ms));

  async function withRetries(task) {
    for (let attempt = 0; ; attempt++) {
      try {
        return await task();
      } catch (e) {
        if (attempt >= RETRY_DELAYS_MS.length) throw e;
        console.warn(LOG_PREFIX, `Failed, retrying: ${e.message}`);
        await sleep(RETRY_DELAYS_MS[attempt]);
      }
    }
  }

  // Loads the deck as one PDF and sends an image of every slide. Only one load runs at a time.
  //   force: false skips the load when the deck was loaded recently (RELOAD_MIN_INTERVAL_MS).
  function loadDeck({ force = false } = {}) {
    if (loading) return loading;
    if (!force && deck && Date.now() - lastLoadedAt < RELOAD_MIN_INTERVAL_MS) {
      return Promise.resolve();
    }
    loading = loadDeckOnce().finally(() => {
      loading = null;
    });
    return loading;
  }

  async function loadDeckOnce() {
    let loaded;
    try {
      loaded = await withRetries(() => adapter.loadDeck(fetchArrayBuffer));
    } catch (e) {
      console.error(LOG_PREFIX, e);
      warn(CODE.DECK_LOAD_FAILED, e.message);
      return;
    }
    lastLoadedAt = Date.now();

    try {
      await globalThis.PresentationGlass.pdfSlides.render(
        loaded.pdf,
        { width: IMAGE_WIDTH_PX, quality: IMAGE_JPEG_QUALITY, order: currentSlideFirst },
        {
          onCount(count) {
            deck = { title: loaded.title, count };
            slideImages = [];
            send({ type: MSG.DECK, ...deck });
            lastSentIndex = -1;
          },
          onSlide(index, { title, data }) {
            const message = { type: MSG.SLIDE_IMAGE, index, title, mime: "image/jpeg", data };
            slideImages[index] = message;
            send(message);
          },
        },
      );
    } catch (e) {
      console.error(LOG_PREFIX, e);
      warn(CODE.DECK_LOAD_FAILED, e.message);
      return;
    }
    resolve(CODE.DECK_LOAD_FAILED);
    console.info(LOG_PREFIX, `Loaded ${deck.count} slides`);
  }

  // Page indexes ordered by distance from the current slide, so that the glasses get the slide
  // they show now (and the next one) first.
  function currentSlideFirst(count) {
    return [...Array(count).keys()].sort(
      (a, b) => Math.abs(a - currentIndex) - Math.abs(b - currentIndex) || b - a,
    );
  }

  // ---- Current slide ----

  function clamp(index) {
    const lastIndex = deck ? deck.count - 1 : Infinity;
    return Math.max(0, Math.min(index, lastIndex));
  }

  function readState() {
    const state = adapter.readState();
    return { ...state, index: state.index === null ? null : clamp(state.index) };
  }

  function poll() {
    const state = readState();
    if (state.presenting && !wasPresenting) loadDeck(); // Notes may have been edited.
    wasPresenting = state.presenting;

    if (state.index !== null) {
      currentIndex = state.index;
      resolve(CODE.SLIDE_NUMBER_UNREADABLE);
    } else if (state.presenting) {
      warn(CODE.SLIDE_NUMBER_UNREADABLE);
    }

    if (currentIndex !== lastSentIndex) {
      lastSentIndex = currentIndex;
      send({ type: MSG.SLIDE, index: currentIndex });
    }
  }

  // ---- Moving the slides ----

  function waitForIndexChange(before) {
    return new Promise((done) => {
      const startedAt = Date.now();
      const timer = setInterval(() => {
        const { index } = readState();
        if (index !== null && index !== before) {
          clearInterval(timer);
          done(true);
        } else if (Date.now() - startedAt > VERIFY_TIMEOUT_MS) {
          clearInterval(timer);
          done(false);
        }
      }, 100);
    });
  }

  async function navigate(action) {
    console.info(LOG_PREFIX, `From the glasses: ${action}`);
    const state = readState();
    const before = state.index ?? currentIndex;
    const lastIndex = deck ? deck.count - 1 : Infinity;
    if ((action === "next" && before >= lastIndex) || (action === "prev" && before <= 0)) return;

    // Try the method that worked last time first, then the others in the adapter's order.
    const knownState = { ...state, index: before };
    const methods = [...adapter.navigationMethods(knownState)].sort(
      (a, b) => (b.name === preferredMethodName) - (a.name === preferredMethodName),
    );

    for (const method of methods) {
      if (!method.run(action, knownState)) continue;

      if (state.index === null) {
        // The page shows no slide number, so the result cannot be checked. Count instead.
        currentIndex = clamp(before + (action === "next" ? 1 : -1));
        preferredMethodName = method.name;
        return;
      }
      if (await waitForIndexChange(before)) {
        if (preferredMethodName !== method.name) console.info(LOG_PREFIX, `Navigation method: ${method.name}`);
        preferredMethodName = method.name;
        resolve(CODE.NAVIGATION_FAILED);
        return;
      }
      console.warn(LOG_PREFIX, `Did not move with: ${method.name}`);
    }
    warn(CODE.NAVIGATION_FAILED);
  }

  connectPort();
  loadDeck();
  setInterval(poll, POLL_INTERVAL_MS);
})();
