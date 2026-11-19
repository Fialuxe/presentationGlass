// Plays and pauses the videos in a slideshow, when the presenter pinches on the glasses.
//
// Google Slides shows a YouTube or Google Drive video in a frame from another site. A script in
// the slide page cannot reach into such a frame, so this file runs in those frames too
// (manifest.json, all_frames). Each copy that is inside Google Slides tells the background
// script where it is (MEDIA_FRAME_READY). On a pinch, the background script sends
// PLAYBACK_TOGGLE to exactly those frames, and each one toggles the videos it can see.
//
// The slideshow may be in another tab or window than the editor (for example with the presenter
// view), so frames are addressed directly instead of through the slide tab.
//
// Each copy reports when its videos start or stop, so that the glasses can show "playing".

(() => {
  const { protocol } = globalThis.PresentationGlass;
  const { internal: INTERNAL } = protocol;
  const SLIDES_ORIGIN = "https://docs.google.com";
  // The background script forgets frames when Chrome stops it, so frames say hello again.
  const READY_INTERVAL_MS = 15000;

  // Only frames inside Google Slides take part. YouTube players on other sites are left alone.
  const inSlides =
    location.origin === SLIDES_ORIGIN ||
    [...(location.ancestorOrigins || [])].includes(SLIDES_ORIGIN);
  if (!inSlides) return;

  // Videos in this document, and in same-origin frames such as the Slides slideshow frame.
  function findVideos(doc = document, found = []) {
    found.push(...doc.querySelectorAll("video"));
    for (const frame of doc.querySelectorAll("iframe")) {
      try {
        if (frame.contentDocument) findVideos(frame.contentDocument, found);
      } catch (e) {
        // A frame from another site. Its own copy of this script handles it.
      }
    }
    return found;
  }

  function isVisible(video) {
    const rect = video.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0;
  }

  // The YouTube player (also used for Google Drive videos) is controlled through its own
  // methods, which only media-youtube-page.js can call.
  const IS_YOUTUBE_PLAYER = /(^|\.)youtube(-nocookie)?\.com$|^youtube\.googleapis\.com$/.test(
    location.hostname,
  );

  // Pauses every playing video; if none is playing, plays the visible ones.
  async function toggle() {
    if (IS_YOUTUBE_PLAYER) {
      window.dispatchEvent(new CustomEvent("presentationglass-playback-toggle"));
      return;
    }
    const videos = findVideos().filter(isVisible);
    if (videos.length === 0) return;
    const playing = videos.filter((v) => !v.paused);
    if (playing.length > 0) {
      playing.forEach((v) => v.pause());
    } else {
      for (const video of videos) {
        try {
          await video.play();
        } catch (e) {
          console.warn("[PresentationGlass] Could not play the video", e);
        }
      }
    }
  }

  // Reports this frame's state when it changes. Frames without videos stay silent, so that they
  // do not hide a video playing in another frame. The background script combines the frames.
  const POLL_INTERVAL_MS = 500;
  let lastPlaying = null;

  function reportState() {
    const videos = findVideos();
    if (videos.length === 0 && lastPlaying === null) return;
    const playing = videos.some((v) => !v.paused && !v.ended);
    if (playing === lastPlaying) return;
    lastPlaying = playing;
    chrome.runtime.sendMessage({ type: INTERNAL.PLAYBACK_STATE, playing }).catch(() => {});
  }

  // Polling also covers videos inside same-origin frames, whose events do not reach this document.
  setInterval(reportState, POLL_INTERVAL_MS);

  chrome.runtime.onMessage.addListener((message) => {
    if (message.type === INTERNAL.PLAYBACK_TOGGLE) toggle().then(reportState);
  });

  // media-youtube-page.js reports what happened after a toggle. Pass it to the extension's log.
  window.addEventListener("presentationglass-playback-log", (event) => {
    chrome.runtime.sendMessage({ type: INTERNAL.LOG, message: event.detail }).catch(() => {});
  });

  function sayReady() {
    chrome.runtime.sendMessage({ type: INTERNAL.MEDIA_FRAME_READY }).catch(() => {});
  }
  sayReady();
  setInterval(sayReady, READY_INTERVAL_MS);
})();
