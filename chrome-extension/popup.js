const { protocol, defaultSettings, i18n } = globalThis.PresentationGlass;
const { t } = i18n;
const { internal: INTERNAL, phone: MSG } = protocol;
const REFRESH_INTERVAL_MS = 1000;

const $ = (id) => document.getElementById(id);

async function loadSettings() {
  const stored = await chrome.storage.local.get(Object.keys(defaultSettings));
  const settings = { ...defaultSettings, ...stored };
  $("host").value = settings.host;
  $("port").value = settings.port;
  $("pin").value = settings.pin;
}

async function refresh() {
  const s = await chrome.runtime.sendMessage({ type: INTERNAL.GET_STATUS });
  if (!s) return;
  $("status").textContent = t("popup_phone", t(`conn_${s.connection}`));
  const slide = s.index === null ? "-" : s.index + 1;
  $("detail").textContent =
    s.tabs === 0
      ? t("popup_no_tab")
      : t("popup_slide", slide, s.slides, s.tabs);
  $("logs").textContent = s.logs.join("\n");
}

$("save").addEventListener("click", async () => {
  await chrome.storage.local.set({
    host: $("host").value.trim(),
    port: Number($("port").value) || defaultSettings.port,
    pin: $("pin").value.trim(),
  });
  await chrome.runtime.sendMessage({ type: INTERNAL.RECONNECT });
  refresh();
});

$("reload").addEventListener("click", async () => {
  const reply = await chrome.runtime.sendMessage({ type: INTERNAL.RELOAD_DECK });
  if (!reply.ok) $("detail").textContent = t("popup_open_tab");
});

$("timer").addEventListener("click", () => {
  chrome.runtime.sendMessage({ type: MSG.RESET_TIMER });
});

i18n.translatePage();
loadSettings();
refresh();
setInterval(refresh, REFRESH_INTERVAL_MS);
