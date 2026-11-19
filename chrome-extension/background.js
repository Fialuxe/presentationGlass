// Relays between slide tabs (content.js) and the Android app (WebSocket server).
//
// The phone runs the server, because a Chrome extension cannot listen for connections.
// During the talk only tiny messages flow ({"type":"slide","index":3}), so a slow network
// still updates the glasses quickly. The slide images are sent once, up front.

importScripts("lib/protocol.js", "lib/i18n.js");

const { protocol, defaultSettings, i18n } = globalThis.PresentationGlass;
const { t } = i18n;
const { phone: MSG, internal: INTERNAL } = protocol;

const KEEPALIVE_INTERVAL_MS = 20000; // Also keeps this service worker alive (Chrome 116+).
const RECONNECT_MIN_MS = 1000;
const RECONNECT_MAX_MS = 10000;
const MAX_LOG_LINES = 30;

const Connection = Object.freeze({
  UNCONFIGURED: "unconfigured",
  CONNECTING: "connecting",
  CONNECTED: "connected",
  RECONNECTING: "reconnecting",
  REJECTED: "rejected",
});
let socket = null;
let connection = Connection.UNCONFIGURED;
let reconnectDelayMs = RECONNECT_MIN_MS;
let reconnectTimer = null;
let keepaliveTimer = null;

// The latest state from the slide tab. Resent whenever the phone (re)connects.
let latestDeck = null;
let latestSlide = null;
const latestImages = new Map(); // slide index -> slide-image message
const latestStatuses = new Map(); // code -> status message

let activePort = null; // The slide tab that reported most recently.
const mediaFrames = new Map(); // "tabId:frameId" -> { tabId, frameId }, frames in Google Slides
const playingFrames = new Map(); // "tabId:frameId" -> whether its videos are playing
let lastPlaying = false;
const ports = new Set();
const logLines = [];

function log(text) {
  logLines.push(`${new Date().toLocaleTimeString()} ${text}`);
  if (logLines.length > MAX_LOG_LINES) logLines.shift();
}

function setConnection(next, detail = "") {
  connection = next;
  log(`${t(`conn_${next}`)}${detail ? `: ${detail}` : ""}`);
}

async function getSettings() {
  const stored = await chrome.storage.local.get(Object.keys(defaultSettings));
  const settings = { ...defaultSettings, ...stored };
  return {
    host: String(settings.host).trim(),
    port: Number(settings.port) || defaultSettings.port,
    pin: String(settings.pin).trim(),
  };
}

// ---- Phone connection ----

function sendToPhone(message) {
  if (socket && socket.readyState === WebSocket.OPEN && connection === Connection.CONNECTED) {
    socket.send(JSON.stringify(message));
  }
}

function resendStateToPhone() {
  if (latestDeck) sendToPhone(latestDeck);
  if (latestSlide) sendToPhone(latestSlide);
  for (const image of latestImages.values()) sendToPhone(image);
  for (const status of latestStatuses.values()) sendToPhone(status);
}

async function connect() {
  clearTimeout(reconnectTimer);
  const { host, port, pin } = await getSettings();
  if (!host || !pin) {
    setConnection(Connection.UNCONFIGURED);
    return;
  }
  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
    return;
  }

  setConnection(Connection.CONNECTING, `${host}:${port}`);
  let ws;
  try {
    ws = new WebSocket(`ws://${host}:${port}`);
  } catch (e) {
    setConnection(Connection.RECONNECTING, e.message);
    scheduleRe// Content scripts run only in tabs opened after the extension was loaded. Add them to the slide
// tabs that are already open, so that the presenter does not need to reload them.
chrome.runtime.onInstalled.addListener(async () => {
  const manifest = chrome.runtime.getManifest();
  for (const script of manifest.content_scripts) {
    const tabs = await chrome.tabs.query({ url: script.matches });
    for (const tab of tabs) {
      chrome.scripting
        .executeScript({ target: { tabId: tab.id }, files: script.js })
        .catch((e) => log(t("log_inject_failed", e.message)));
    }
  }
});

connect();
    return;
  }
  socket = ws;

  ws.onopen = () => {
    ws.send(JSON.stringify({ type: MSG.HELLO, version: protocol.VERSION, pin, client: "chrome" }));
  };

  ws.onmessage = (event) => {
    let message;
    try {
      message = JSON.parse(event.data);
    } catch (e) {
      return;
    }
    if (message.type === MSG.WELCOME) {
      setConnection(Connection.CONNECTED);
      reconnectDelayMs = RECONNECT_MIN_MS;
      resendStateToPhone(); // The phone may have restarted.
    } else if (message.type === MSG.NAVIGATE) {
      log(t("log_from_glasses", message.action));
      if (activePort) activePort.postMessage(message);
    } else if (message.type === MSG.PLAYBACK) {
      log(t("log_playback_toggle"));
      // Sent to each frame that said it is inside Google Slides, in whatever tab or window.
      for (const [key, { tabId, frameId }] of mediaFrames) {
        chrome.tabs
          .sendMessage(tabId, { type: INTERNAL.PLAYBACK_TOGGLE }, { frameId })
          .catch(() => mediaFrames.delete(key)); // The frame is gone.
      }
      log(mediaFrames.size === 0 ? t("log_no_video_frames") : t("log_sent_to_video_frames", mediaFrames.size));
    } else if (message.type === MSG.ERROR) {
      setConnection(Connection.REJECTED, message.message);
    }
  };

  ws.onclose = () => {
    if (socket === ws) socket = null;
    if (connection !== Connection.REJECTED) setConnection(Connection.RECONNECTING);
    scheduleRe// Content scripts run only in tabs opened after the extension was loaded. Add them to the slide
// tabs that are already open, so that the presenter does not need to reload them.
chrome.runtime.onInstalled.addListener(async () => {
  const manifest = chrome.runtime.getManifest();
  for (const script of manifest.content_scripts) {
    const tabs = await chrome.tabs.query({ url: script.matches });
    for (const tab of tabs) {
      chrome.scripting
        .executeScript({ target: { tabId: tab.id }, files: script.js })
        .catch((e) => log(t("log_inject_failed", e.message)));
    }
  }
});

connect();
  };
  // onerror is always followed by onclose, which handles reconnecting.
}

function scheduleReconnect() {
  clearTimeout(reconnectTimer);
  reconnectTimer = setTimeout(connect, reconnectDelayMs);
  reconnectDelayMs = Math.min(reconnectDelayMs * 2, RECONNECT_MAX_MS);
}

function disconnect() {
  clearTimeout(reconnectTimer);
  if (!socket) return;
  const ws = socket;
  socket = null;
  ws.onclose = null;
  ws.close();
}

function startKeepalive() {
  if (keepaliveTimer) return;
  keepaliveTimer = setInterval(() => {
    sendToPhone({ type: MSG.PING });
    if (!socket) // Content scripts run only in tabs opened after the extension was loaded. Add them to the slide
// tabs that are already open, so that the presenter does not need to reload them.
chrome.runtime.onInstalled.addListener(async () => {
  const manifest = chrome.runtime.getManifest();
  for (const script of manifest.content_scripts) {
    const tabs = await chrome.tabs.query({ url: script.matches });
    for (const tab of tabs) {
      chrome.scripting
        .executeScript({ target: { tabId: tab.id }, files: script.js })
        .catch((e) => log(t("log_inject_failed", e.message)));
    }
  }
});

connect();
  }, KEEPALIVE_INTERVAL_MS);
}

// ---- Slide tabs ----

chrome.runtime.onConnect.addListener((port) => {
  if (port.name !== protocol.PORT_NAME) return;
  ports.add(port);
  if (!activePort) activePort = port;
  // Content scripts run only in tabs opened after the extension was loaded. Add them to the slide
// tabs that are already open, so that the presenter does not need to reload them.
chrome.runtime.onInstalled.addListener(async () => {
  const manifest = chrome.runtime.getManifest();
  for (const script of manifest.content_scripts) {
    const tabs = await chrome.tabs.query({ url: script.matches });
    for (const tab of tabs) {
      chrome.scripting
        .executeScript({ target: { tabId: tab.id }, files: script.js })
        .catch((e) => log(t("log_inject_failed", e.message)));
    }
  }
});

connect();
  startKeepalive();

  port.onMessage.addListener((message) => {
    activePort = port;
    if (message.type === MSG.DECK) {
      latestDeck = message;
      latestImages.clear();
      log(t("log_deck_loaded", message.count));
    } else if (message.type === MSG.SLIDE_IMAGE) {
      latestImages.set(message.index, message);
    } else if (message.type === MSG.SLIDE) {
      latestSlide = message;
    } else if (message.type === MSG.STATUS) {
      if (message.level === protocol.level.WARN) latestStatuses.set(message.code, message);
      else latestStatuses.delete(message.code);
      log(message.detail ? `${message.message}: ${message.detail}` : message.message);
    } else if (message.type === INTERNAL.LOG) {
      log(message.message);
      return; // Only for the extension's own log, not for the phone.
    } else {
      return;
    }
    sendToPhone(message);
  });

  port.onDisconnect.addListener(() => {
    ports.delete(port);
    if (activePort === port) activePort = [...ports].pop() || null;
  });
});

// ---- Requests from the popup and content scripts ----

function toBase64(buffer) {
  const bytes = new Uint8Array(buffer);
  const CHUNK = 0x8000;
  let binary = "";
  for (let i = 0; i < bytes.length; i += CHUNK) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK));
  }
  return btoa(binary);
}

const handlers = {
  async [INTERNAL.LOG]({ message }) {
    log(message);
    return { ok: true };
  },
  // A frame of the slide tab reports whether its videos are playing.
  async [INTERNAL.MEDIA_FRAME_READY](message, sender) {
    const key = `${sender.tab.id}:${sender.frameId}`;
    mediaFrames.set(key, { tabId: sender.tab.id, frameId: sender.frameId });
    return { ok: true };
  },
  async [INTERNAL.PLAYBACK_STATE]({ playing }, sender) {
    playingFrames.set(`${sender.tab.id}:${sender.frameId}`, playing);
    const anyPlaying = [...playingFrames.values()].some(Boolean);
    if (anyPlaying !== lastPlaying) {
      lastPlaying = anyPlaying;
      log(anyPlaying ? t("log_video_playing") : t("log_video_stopped"));
      sendToPhone({ type: MSG.PLAYBACK, playing: anyPlaying });
    }
    return { ok: true };
  },
  // Downloads with the user's login. The service worker is not limited by the page's CORS rules.
  async [INTERNAL.FETCH_BASE64]({ url }) {
    try {
      const res = await fetch(url, { credentials: "include" });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      return { ok: true, data: toBase64(await res.arrayBuffer()) };
    } catch (e) {
      return { ok: false, error: e.message };
    }
  },
  async [INTERNAL.GET_STATUS]() {
    return {
      connection, // The popup shows it in the browser's language.
      slides: latestDeck ? latestDeck.count : 0,
      index: latestSlide ? latestSlide.index : null,
      tabs: ports.size,
      logs: logLines.slice(-15),
    };
  },
  async [INTERNAL.RECONNECT]() {
    dis// Content scripts run only in tabs opened after the extension was loaded. Add them to the slide
// tabs that are already open, so that the presenter does not need to reload them.
chrome.runtime.onInstalled.addListener(async () => {
  const manifest = chrome.runtime.getManifest();
  for (const script of manifest.content_scripts) {
    const tabs = await chrome.tabs.query({ url: script.matches });
    for (const tab of tabs) {
      chrome.scripting
        .executeScript({ target: { tabId: tab.id }, files: script.js })
        .catch((e) => log(t("log_inject_failed", e.message)));
    }
  }
});

connect();
    reconnectDelayMs = RECONNECT_MIN_MS;
    await // Content scripts run only in tabs opened after the extension was loaded. Add them to the slide
// tabs that are already open, so that the presenter does not need to reload them.
chrome.runtime.onInstalled.addListener(async () => {
  const manifest = chrome.runtime.getManifest();
  for (const script of manifest.content_scripts) {
    const tabs = await chrome.tabs.query({ url: script.matches });
    for (const tab of tabs) {
      chrome.scripting
        .executeScript({ target: { tabId: tab.id }, files: script.js })
        .catch((e) => log(t("log_inject_failed", e.message)));
    }
  }
});

connect();
    startKeepalive();
    return { ok: true };
  },
  async [INTERNAL.RELOAD_DECK]() {
    if (!activePort) return { ok: false };
    activePort.postMessage({ type: INTERNAL.RELOAD_DECK });
    return { ok: true };
  },
  async [MSG.RESET_TIMER]() {
    sendToPhone({ type: MSG.RESET_TIMER });
    return { ok: true };
  },
};

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  const handler = handlers[message.type];
  if (!handler) return false;
  handler(message, sender).then(sendResponse);
  return true; // The response is sent asynchronously.
});

// Content scripts run only in tabs opened after the extension was loaded. Add them to the slide
// tabs that are already open, so that the presenter does not need to reload them.
chrome.runtime.onInstalled.addListener(async () => {
  const manifest = chrome.runtime.getManifest();
  for (const script of manifest.content_scripts) {
    const tabs = await chrome.tabs.query({ url: script.matches });
    for (const tab of tabs) {
      chrome.scripting
        .executeScript({ target: { tabId: tab.id }, files: script.js })
        .catch((e) => log(t("log_inject_failed", e.message)));
    }
  }
});

connect();
