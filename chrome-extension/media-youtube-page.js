// Runs in the YouTube player frame, in the page's own JavaScript world (manifest "world": "MAIN").
//
// Google Slides shows YouTube and Google Drive videos with the YouTube player. The player does
// not load the video until someone presses play, so calling play() on its <video> element does
// nothing. The player element has its own playVideo() / pauseVideo() methods, which load and
// start the video. Those methods exist only in the page's world, not in the extension's isolated
// world, so media.js asks this script with a DOM event.
//
// After each toggle, this script reports what happened, for the extension's log: the player
// state one second later, and whether Chrome lets this frame start playback without a click.

(() => {
  const PLAYING = 1; // YouTube player state for "playing".
  const STATE_NAMES = { "-1": "unstarted", 0: "ended", 1: "playing", 2: "paused", 3: "buffering", 5: "cued" };
  const REPORT_DELAY_MS = 1000;

  function report(text) {
    window.dispatchEvent(new CustomEvent("presentationglass-playback-log", { detail: text }));
  }

  window.addEventListener("presentationglass-playback-toggle", () => {
    const player = document.getElementById("movie_player");
    if (!player || typeof player.getPlayerState !== "function") {
      report("YouTube player not found");
      return;
    }
    const before = player.getPlayerState();
    if (before === PLAYING) player.pauseVideo();
    else player.playVideo();

    setTimeout(() => {
      const video = document.querySelector("video");
      const allowsAutoplay = document.featurePolicy
        ? document.featurePolicy.allowsFeature("autoplay")
        : "unknown";
      const activated = navigator.userActivation ? navigator.userActivation.hasBeenActive : "unknown";
      report(
        `YouTube: ${STATE_NAMES[before] ?? before} → ${STATE_NAMES[player.getPlayerState()] ?? player.getPlayerState()}` +
          ` (autoplay allowed: ${allowsAutoplay}, user input in this frame: ${activated}, ` +
          `muted: ${video ? video.muted : "no video"})`,
      );
    }, REPORT_DELAY_MS);
  });
})();
