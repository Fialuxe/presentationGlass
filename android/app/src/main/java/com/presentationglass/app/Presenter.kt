package com.presentationglass.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.edit
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/**
 * Where the time goes in one screen update, in milliseconds.
 *
 * @property fromInput from the swipe on the glasses to the end of sending, or null when the update
 *   was not caused by a swipe.
 */
data class RenderTiming(
    val composeMs: Long,
    val sendMs: Long,
    val fromInputMs: Long?,
    /** Size of the image sent to the glasses. */
    val bytes: Int,
    /** PREVIEW: quick, rough image right after a swipe. FINAL: the full-quality screen. */
    val stage: RenderStage,
)

enum class RenderStage {
  PREVIEW,
  FINAL,
}

enum class GlassesStatus {
  IDLE,
  CONNECTING,
  READY,
  ERROR,
}

data class PresenterState(
    val serverPort: Int = Protocol.PORT,
    val pin: String = "",
    val addresses: List<String> = emptyList(),
    val pcConnected: Boolean = false,
    /** Problems reported by the PC that are not resolved yet, by status code. */
    val pcWarnings: Map<String, String> = emptyMap(),
    val deckTitle: String = "",
    val slideCount: Int = 0,
    /** Images and first lines of text of the slides, by slide index. They arrive one by one. */
    val slideImages: Map<Int, Bitmap> = emptyMap(),
    val slideTitles: Map<Int, String> = emptyMap(),
    val currentIndex: Int = 0,
    val startedAtMillis: Long? = null,
    val glassesStatus: GlassesStatus = GlassesStatus.IDLE,
    val usePhonePreview: Boolean = false,
    val chromePreviewUrl: String? = null,
    val lastMessage: String? = null,
    /** Whether a video on the current slide is playing on the PC. */
    val videoPlaying: Boolean = false,
    /** Timing of the last screen update on the glasses, for finding where the delay comes from. */
    val lastTiming: RenderTiming? = null,
    val displayPattern: DisplayPattern = DisplayPattern.TWO_SLIDES,
)

/** How the glasses screen is laid out. */
enum class DisplayPattern {
  /** Pattern 1: the current slide, and the next slide smaller below it. */
  TWO_SLIDES,
  /** Pattern 2: the current slide as large as possible, and only the next slide's title. */
  FOCUS,
}

/**
 * App-wide state holder.
 *
 * The PC (Chrome extension) is the single source of truth for the current slide. The phone only
 * relays: it shows what the PC reports, and forwards "next" / "prev" requests from the glasses back
 * to the PC.
 */
object Presenter {
  private const val TAG = "Presenter"
  private const val CONNECTION_LOST_TIMEOUT_SECONDS = 10
  private const val SERVER_STOP_TIMEOUT_MS = 1000

  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private const val PREFS = "presenter"
  /** After a swipe, slide numbers from the PC that differ from the target are ignored this long. */
  private const val PENDING_NAVIGATION_TIMEOUT_MS = 2000L
  private const val PREF_PIN = "pin"
  private const val PREF_PATTERN = "display_pattern"

  private val _state = MutableStateFlow(PresenterState())
  val state: StateFlow<PresenterState> = _state.asStateFlow()

  // These hold only the application context, which lives as long as the process. Lint cannot
  // tell, so the leak warning is suppressed.
  @SuppressLint("StaticFieldLeak") private lateinit var appContext: Context
  @SuppressLint("StaticFieldLeak") private var server: DeckServer? = null

  /** The slide the glasses moved to on a swipe, before the PC confirmed it. */
  private data class PendingNavigation(val target: Int, val startedAt: Long)

  @Volatile private var pendingNavigation: PendingNavigation? = null
  @SuppressLint("StaticFieldLeak")
  lateinit var glasses: GlassesDisplay
    private set

  /** The phone as a Bluetooth keyboard and mouse for the PC. */
  @SuppressLint("StaticFieldLeak")
  lateinit var hid: HidController
    private set

  fun init(context: Context) {
    if (::glasses.isInitialized) return
    appContext = context.applicationContext
    glasses = GlassesDisplay(appContext, scope)
    hid = HidController(appContext)
    // The PIN is kept, so that the Chrome extension pairs only once.
    val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val pin = prefs.getString(PREF_PIN, null) ?: newPin().also { prefs.edit { putString(PREF_PIN, it) } }
    val pattern =
        DisplayPattern.entries.firstOrNull { it.name == prefs.getString(PREF_PATTERN, null) }
            ?: DisplayPattern.TWO_SLIDES
    _state.update { it.copy(pin = pin, displayPattern = pattern) }
  }

  fun setDisplayPattern(pattern: DisplayPattern) {
    appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
      putString(PREF_PATTERN, pattern.name)
    }
    _state.update { it.copy(displayPattern = pattern) }
    glasses.requestRender()
  }

  // -- PC link --

  fun startServer() {
    if (server != null) return
    val newServer =
        DeckServer(
            context = appContext,
            port = Protocol.PORT,
            isValidPin = { it == _state.value.pin },
            onMessageFromPc = ::onPcMessage,
            onAuthorizedCountChanged = ::onPcCountChanged,
            onServerError = { error ->
              server = null
              showMessage(R.string.message_server_error, error.message.orEmpty())
            },
        )
    newServer.isReuseAddr = true
    newServer.connectionLostTimeout = CONNECTION_LOST_TIMEOUT_SECONDS
    newServer.start()
    server = newServer
    refreshAddresses()
    Log.i(TAG, "WebSocket server started on port ${Protocol.PORT}")
  }

  fun stopServer() {
    runCatching { server?.stop(SERVER_STOP_TIMEOUT_MS) }
    server = null
    _state.update { it.copy(pcConnected = false) }
  }

  fun regeneratePin() {
    val pin = newPin()
    appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(PREF_PIN, pin) }
    _state.update { it.copy(pin = pin) }
    server?.closeAll()
  }

  fun refreshAddresses() {
    _state.update { it.copy(addresses = localIpv4Addresses()) }
  }

  private fun onPcCountChanged(count: Int) {
    _state.update { it.copy(pcConnected = count > 0) }
    glasses.requestRender()
  }

  private fun onPcMessage(message: JSONObject) {
    when (message.optString("type")) {
      Protocol.Type.DECK -> onDeck(message)
      Protocol.Type.SLIDE -> onSlide(message)
      Protocol.Type.SLIDE_IMAGE -> onSlideImage(message)
      Protocol.Type.PLAYBACK -> {
        _state.update { it.copy(videoPlaying = message.optBoolean("playing")) }
        glasses.requestRender()
      }
      Protocol.Type.STATUS -> onStatus(message)
      Protocol.Type.RESET_TIMER -> {
        _state.update { it.copy(startedAtMillis = System.currentTimeMillis()) }
        glasses.requestRender()
      }
      else -> Log.w(TAG, "Unknown message type: ${message.optString("type")}")
    }
  }

  private fun onDeck(message: JSONObject) {
    val count = message.optInt("count", 0)
    _state.update {
      it.copy(
          deckTitle = message.optString("title"),
          slideCount = count,
          slideImages = emptyMap(),
          slideTitles = emptyMap(),
          currentIndex = it.currentIndex.coerceIn(0, (count - 1).coerceAtLeast(0)),
      )
    }
    showMessage(R.string.message_deck_received, count)
    glasses.requestRender()
  }

  private fun onSlide(message: JSONObject) {
    val index = message.optInt("index", 0)
    // The glasses already moved on a swipe. While the PC catches up, it may report the slides it
    // passes through. Ignore those, so that the glasses do not jump back and forth.
    pendingNavigation?.let { pending ->
      val expired = SystemClock.elapsedRealtime() - pending.startedAt > PENDING_NAVIGATION_TIMEOUT_MS
      if (index == pending.target || expired) {
        pendingNavigation = null
      } else {
        return
      }
    }
    if (index == _state.value.currentIndex) return
    _state.update {
      it.copy(
          currentIndex = index,
          startedAtMillis = it.startedAtMillis ?: System.currentTimeMillis(),
      )
    }
    glasses.requestRender()
  }

  private fun onSlideImage(message: JSONObject) {
    val index = message.optInt("index", -1)
    val bytes = runCatching { Base64.decode(message.optString("data"), Base64.DEFAULT) }.getOrNull()
    val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    if (index < 0 || bitmap == null) {
      Log.w(TAG, "Ignored a slide image that could not be decoded: index=$index")
      return
    }
    val title = message.optString("title").trim()
    _state.update {
      it.copy(
          slideImages = it.slideImages + (index to bitmap),
          slideTitles = it.slideTitles + (index to title),
      )
    }
    // Only the current and the next slide are on the glasses.
    val current = _state.value.currentIndex
    if (index == current || index == current + 1) glasses.requestRender()
  }

  /** Problems the PC reports by code, shown in the phone's language. */
  private val PC_WARNING_TEXT =
      mapOf(
          "deck-load-failed" to R.string.pc_warning_deck_load_failed,
          "slide-number-unreadable" to R.string.pc_warning_slide_number_unreadable,
          "navigation-failed" to R.string.pc_warning_navigation_failed,
      )

  private fun onStatus(message: JSONObject) {
    val code = message.optString("code")
    // The PC's own text is used only for codes this app does not know.
    val text =
        if (message.optString("level") == Protocol.Level.WARN) {
          PC_WARNING_TEXT[code]?.let { appContext.getString(it) } ?: message.optString("message")
        } else {
          message.optString("message")
        }
    _state.update {
      val warnings =
          if (message.optString("level") == Protocol.Level.WARN) it.pcWarnings + (code to text)
          else it.pcWarnings - code
      it.copy(pcWarnings = warnings, lastMessage = text)
    }
    glasses.requestRender()
  }

  /** Called from the glasses (Neural Band pinch on a button) or from the phone screen. */
  fun requestNavigation(action: NavigationAction) {
    val activeServer = server
    val useKeyboard = hid.isConnected
    if (!useKeyboard && (activeServer == null || !_state.value.pcConnected)) {
      showMessage(R.string.message_pc_not_connected)
      return
    }
    // Move the glasses first. The slide images are already on the phone, so the glasses do not
    // need to wait for the PC's answer.
    val current = _state.value
    val target =
        (current.currentIndex + if (action == NavigationAction.NEXT) 1 else -1)
            .coerceIn(0, (current.slideCount - 1).coerceAtLeast(0))
    if (target != current.currentIndex) {
      val now = SystemClock.elapsedRealtime()
      pendingNavigation = PendingNavigation(target, now)
      _state.update { it.copy(currentIndex = target) }
      glasses.requestRender(inputAt = now)
    }
    // A real arrow key from the Bluetooth keyboard works in any slide software. Without it, the
    // Chrome extension moves the slides through the page.
    if (useKeyboard) {
      if (action == NavigationAction.NEXT) hid.pressRightArrow() else hid.pressLeftArrow()
      return
    }
    val message =
        JSONObject().put("type", Protocol.Type.NAVIGATE).put("action", action.wireName).toString()
    activeServer?.broadcastToAuthorized(message)
  }

  /** Plays or pauses the videos on the current slide. Called on a pinch on the glasses. */
  fun requestPlaybackToggle() {
    val activeServer = server
    if (activeServer == null || !_state.value.pcConnected) {
      showMessage(R.string.message_pc_not_connected)
      return
    }
    // Chrome starts a video with sound only after a real user input on the page. A real key press
    // from the Bluetooth keyboard counts as one; the Pause key does nothing in slide software.
    if (hid.isConnected) hid.pressPauseKey()
    val message = JSONObject().put("type", Protocol.Type.PLAYBACK).put("action", "toggle").toString()
    activeServer.broadcastToAuthorized(message)
  }

  // -- Phone preview (no glasses) --

  fun setPhonePreview(enabled: Boolean, chromeUrl: String? = null) {
    _state.update { it.copy(usePhonePreview = enabled, chromePreviewUrl = chromeUrl) }
  }

  // -- Status shown on the phone --

  fun setGlassesStatus(status: GlassesStatus, message: String? = null) {
    _state.update { it.copy(glassesStatus = status, lastMessage = message ?: it.lastMessage) }
  }

  fun showMessage(@StringRes resId: Int, vararg args: Any) {
    _state.update { it.copy(lastMessage = appContext.getString(resId, *args)) }
  }

  fun recordTiming(timing: RenderTiming) {
    _state.update { it.copy(lastTiming = timing) }
  }

  fun showMessage(message: String) {
    _state.update { it.copy(lastMessage = message) }
  }

  private fun newPin(): String = Random.nextInt(0, 10000).toString().padStart(4, '0')

  private fun localIpv4Addresses(): List<String> =
      runCatching {
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif ->
                  nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map {
                    "${it.hostAddress} (${nif.name})"
                  }
                }
          }
          .getOrDefault(emptyList())
}
