// Names shared by every part of the extension, so that a message type or a status code is
// spelled in one place only. Loaded by background.js (importScripts), the content scripts
// (manifest.json), and popup.html.
//
// Messages to and from the phone are described in docs/protocol.md.

(() => {
  const PresentationGlass = (globalThis.PresentationGlass = globalThis.PresentationGlass || {
    adapters: [],
  });

  PresentationGlass.protocol = Object.freeze({
    VERSION: 3,

    // Between the extension and the phone (docs/protocol.md).
    phone: Object.freeze({
      HELLO: "hello",
      WELCOME: "welcome",
      ERROR: "error",
      DECK: "deck",
      SLIDE: "slide",
      SLIDE_IMAGE: "slide-image",
      STATUS: "status",
      RESET_TIMER: "reset-timer",
      NAVIGATE: "navigate",
      PLAYBACK: "playback",
      PING: "ping",
      PONG: "pong",
    }),

    // Inside the extension (content script, background, popup).
    internal: Object.freeze({
      FETCH_BASE64: "fetch-base64",
      GET_STATUS: "get-status",
      RECONNECT: "reconnect",
      RELOAD_DECK: "reload-deck",
      LOG: "log",
      PLAYBACK_TOGGLE: "playback-toggle",
      PLAYBACK_STATE: "playback-state",
      MEDIA_FRAME_READY: "media-frame-ready",
    }),

    // Values of `level` and `code` in a status message.
    level: Object.freeze({ OK: "ok", WARN: "warn" }),
    statusCode: Object.freeze({
      DECK_LOAD_FAILED: "deck-load-failed",
      SLIDE_NUMBER_UNREADABLE: "slide-number-unreadable",
      NAVIGATION_FAILED: "navigation-failed",
    }),

    // The port name a content script uses to talk to the background script.
    PORT_NAME: "slides",
  });

  PresentationGlass.defaultSettings = Object.freeze({ host: "", port: 8765, pin: "" });
})();
