// Adapter for Google Slides.
//
// Everything that depends on how Google Slides builds its page lives in this file. When Google
// changes the page, fix the SELECTORS below first. To support another slide service, add a new
// file in this folder that pushes an adapter with the same shape to PresentationGlass.adapters,
// and list it in manifest.json after lib/*.js and before content.js.
//
// Adapter shape:
//   name                     Display name for messages.
//   detect()                 Returns true when this adapter handles the current page.
//   loadDeck(fetchArrayBuffer)
//                            Resolves to { title, pdf }. pdf is an ArrayBuffer of the whole deck,
//                            one page per slide. fetchArrayBuffer(url) downloads with the user's
//                            login, also across origins.
//   readState()              Returns { presenting, index, source }. index is null when unknown.
//   navigationMethods(state) Returns [{ name, run(action, state) }], tried in order until the
//                            slide number changes. action is "next" or "prev". run() returns
//                            false when the method cannot be used right now.

(() => {
  const SELECTORS = {
    // The slideshow is drawn inside this iframe (same origin, so we can read it).
    presentIframe: "iframe.punch-present-iframe",
    // Screen-reader label on the slide: "スライド 1/2: タイトル" or "Slide 1 of 2: Title".
    slideA11yLabel: ".punch-viewer-svgpage-a11yelement",
    // Slide number shown in the slideshow's bottom bar, as plain digits.
    navbarCaption: ".docs-material-menu-button-flat-default-caption",
    // Buttons in the slideshow's bottom bar. They work even while the bar is hidden.
    nextButton: ".punch-viewer-navbar-next",
    prevButton: ".punch-viewer-navbar-prev",
    // Editor filmstrip. "filmstrip-slide-<index>-<slide id>-bg" exists once per slide.
    filmstripBackground: '[id^="filmstrip-slide-"][id$="-bg"]',
  };
  const A11Y_NUMBER = /(\d+)\s*(?:\/|of)\s*\d+/;
  const FILMSTRIP_ID = /^filmstrip-slide-(\d+)-(.+)-bg$/;
  const URL_SLIDE_ID = /slide=id\.([\w-]+)/;

  function presentationId() {
    return (location.pathname.match(/\/presentation\/d\/([^/]+)/) || [])[1] || null;
  }

  function presentDoc() {
    const iframe = document.querySelector(SELECTORS.presentIframe);
    try {
      return iframe ? iframe.contentDocument : null;
    } catch (e) {
      return null;
    }
  }

  function filmstripSlideIds() {
    const byIndex = new Map();
    for (const el of document.querySelectorAll(SELECTORS.filmstripBackground)) {
      const m = el.id.match(FILMSTRIP_ID);
      if (m && !byIndex.has(Number(m[1]))) byIndex.set(Number(m[1]), m[2]);
    }
    return [...byIndex.entries()].sort((a, b) => a[0] - b[0]).map((e) => e[1]);
  }

  // ---- Ways to read the current slide number, most reliable first ----

  const indexReaders = [
    {
      name: "screen-reader label",
      read(doc) {
        const el = doc && doc.querySelector(SELECTORS.slideA11yLabel);
        const m = el && (el.getAttribute("aria-label") || "").match(A11Y_NUMBER);
        return m ? Number(m[1]) - 1 : null;
      },
    },
    {
      name: "bottom bar number",
      read(doc) {
        const el = doc && doc.querySelector(SELECTORS.navbarCaption);
        const text = el ? el.textContent.trim() : "";
        return /^\d{1,4}$/.test(text) ? Number(text) - 1 : null;
      },
    },
    {
      name: "URL and slide list",
      read() {
        const m = location.href.match(URL_SLIDE_ID);
        const i = m ? filmstripSlideIds().indexOf(m[1]) : -1;
        return i >= 0 ? i : null;
      },
    },
  ];

  // ---- Ways to move the slides ----

  function click(el, x, y) {
    if (x === undefined) {
      const rect = el.getBoundingClientRect();
      x = rect.x + rect.width / 2;
      y = rect.y + rect.height / 2;
    }
    const view = el.ownerDocument.defaultView;
    const opts = { bubbles: true, cancelable: true, button: 0, view, clientX: x, clientY: y };
    for (const type of ["pointerover", "mouseover", "pointerdown", "mousedown", "pointerup", "mouseup", "click"]) {
      const Ctor = type.startsWith("pointer") ? view.PointerEvent : view.MouseEvent;
      el.dispatchEvent(new Ctor(type, opts));
    }
  }

  const slideshowMethods = [
    {
      name: "bottom bar buttons",
      run(action) {
        const doc = presentDoc();
        const button =
          doc && doc.querySelector(action === "next" ? SELECTORS.nextButton : SELECTORS.prevButton);
        if (!button) return false;
        click(button);
        return true;
      },
    },
    {
      // Clicks whatever is in the middle of the slideshow. It uses no class names, so it keeps
      // working when Google renames them. A click can only move forward.
      name: "click on the slide centre",
      run(action) {
        if (action !== "next") return false;
        const doc = presentDoc();
        const win = doc && doc.defaultView;
        if (!win) return false;
        const x = win.innerWidth / 2;
        const y = win.innerHeight / 2;
        const target = doc.elementFromPoint(x, y);
        if (!target) return false;
        click(target, x, y);
        return true;
      },
    },
  ];

  const editorMethods = [
    {
      name: "slide in the URL",
      run(action, state) {
        const ids = filmstripSlideIds();
        const target = (state.index ?? 0) + (action === "next" ? 1 : -1);
        if (!ids[target]) return false;
        location.hash = `slide=id.${ids[target]}`;
        return true;
      },
    },
  ];

  globalThis.PresentationGlass.adapters.push({
    name: "Google Slides",

    detect() {
      return location.hostname === "docs.google.com" && !!presentationId();
    },

    async loadDeck(fetchArrayBuffer) {
      // One download for the whole deck. Google answers HTTP 429 to downloads that come too often.
      const url = `https://docs.google.com/presentation/d/${presentationId()}/export/pdf`;
      const pdf = await fetchArrayBuffer(url);
      const title = document.title.replace(/\s*-\s*Google\s*(スライド|Slides)\s*$/i, "");
      return { title, pdf };
    },

    readState() {
      const doc = presentDoc();
      const presenting = !!doc;
      for (const reader of indexReaders) {
        const index = reader.read(doc);
        if (index !== null) return { presenting, index, source: reader.name };
      }
      return { presenting, index: null, source: null };
    },

    navigationMethods(state) {
      return state.presenting ? slideshowMethods : editorMethods;
    },
  });
})();
