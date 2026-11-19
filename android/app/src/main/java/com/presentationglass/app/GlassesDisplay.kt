package com.presentationglass.app

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import android.util.Log
import androidx.annotation.StringRes
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.removeDisplay
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.views.ContentScope
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.ImageSize
import com.meta.wearable.dat.inputs.addInputs
import com.meta.wearable.dat.inputs.removeInputs
import com.meta.wearable.dat.inputs.types.InputSource
import com.meta.wearable.dat.inputs.types.InputsConfiguration
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the DAT session, the display and the input capabilities.
 *
 * The glasses show slides only: no buttons and no text chrome. The whole 600 x 600 screen is one
 * image drawn by [SlideComposer]. Gestures on the Neural Band and the temple touchpad are turned
 * into actions on the PC by [GestureMapper].
 *
 * Without buttons on the screen, the content is not interactive, and the Inputs capability gets
 * the swipes as Nav events. Every sendContent call replaces the whole screen, so renders are
 * conflated: if several updates arrive while one is being sent, only the newest one is drawn.
 */
class GlassesDisplay(
    private val context: Context,
    private val scope: CoroutineScope,
) {
  private companion object {
    const val TAG = "GlassesDisplay"
    const val CHROME_PREVIEW_PORT = 9000
    /** The device ends a session for reasons the SDK does not report. Then we start again. */
    const val RESTART_DELAY_MS = 2000L
    const val MAX_RESTARTS = 5
    /**
     * Image settings. Measured on 2026-11-19: quality 40 looked no worse than 85 on the glasses,
     * and the delay grows by about 65 ms per KB.
     */
    const val FINAL_JPEG_QUALITY = 50
    const val PREVIEW_JPEG_QUALITY = 40
    const val PREVIEW_SIZE_PX = 300
  }

  private val lock = Any()
  private var session: DeviceSession? = null
  private var display: Display? = null
  private val jobs = mutableListOf<Job>()

  /** A render request. inputAt is the time of the swipe that caused it, if any. */
  private data class RenderRequest(val inputAt: Long?)

  private val renderRequests = Channel<RenderRequest>(Channel.CONFLATED)
  private var renderLoop: Job? = null

  /** True while the presenter wants the glasses on. False after they press "stop". */
  @Volatile private var wanted = false
  private var restarts = 0

  private val mockDeviceKit by lazy { MockDeviceKit.getInstance(context) }
  private var mockGlasses: MockGlasses? = null

  // -- Lifecycle --

  /** Start a session with real glasses, or with simulated glasses when phone preview is on. */
  fun start() {
    wanted = true
    restarts = 0
    startSession()
  }

  /** Stops the session because the presenter asked for it. */
  fun stop() {
    wanted = false
    stopSession()
    Presenter.setGlassesStatus(GlassesStatus.IDLE)
  }

  private fun startSession() {
    synchronized(lock) { if (session != null) return }
    val selector: DeviceSelector =
        if (Presenter.state.value.usePhonePreview) {
          val glasses = enableMockGlasses() ?: return
          SpecificDeviceSelector(glasses.deviceIdentifier)
        } else {
          AutoDeviceSelector(filter = { it.isDisplayCapable() })
        }

    Presenter.setGlassesStatus(GlassesStatus.CONNECTING, str(R.string.message_connecting_glasses))
    Wearables.createSession(selector)
        .fold(
            onSuccess = { newSession ->
              synchronized(lock) { session = newSession }
              track(scope.launch { observeSession(newSession) })
              track(
                  scope.launch {
                    newSession.errors.collect { error ->
                      Log.e(TAG, "Session error: ${error.description}")
                      onSessionLost(error.description)
                    }
                  }
              )
              newSession.start()
            },
            onFailure = { error, _ ->
              Log.e(TAG, "createSession failed: ${error.description}")
              Presenter.setGlassesStatus(GlassesStatus.ERROR, error.description)
            },
        )
  }

  private suspend fun observeSession(newSession: DeviceSession) {
    var hasStarted = false
    newSession.state.collect { state ->
      Log.i(TAG, "Session state: $state")
      when (state) {
        DeviceSessionState.STARTED -> {
          hasStarted = true
          restarts = 0
          attachDisplay(newSession)
          attachInputs(newSession)
        }
        DeviceSessionState.STOPPED ->
            if (hasStarted) onSessionLost(str(R.string.message_glasses_disconnected))
        else -> {}
      }
    }
  }

  private fun stopSession() {
    val (oldSession, oldJobs) =
        synchronized(lock) {
          val s = session
          val j = jobs.toList()
          session = null
          display = null
          jobs.clear()
          s to j
        }
    oldJobs.forEach { it.cancel() }
    renderLoop?.cancel()
    runCatching { oldSession?.removeInputs() }
    runCatching { oldSession?.removeDisplay() }
    runCatching { oldSession?.stop() }
  }

  /** The device ended the session. If the presenter still wants the glasses on, start again. */
  private fun onSessionLost(reason: String) {
    stopSession()
    if (wanted && restarts < MAX_RESTARTS) {
      restarts++
      Presenter.setGlassesStatus(GlassesStatus.CONNECTING, str(R.string.message_reconnecting_glasses))
      scope.launch {
        delay(RESTART_DELAY_MS)
        if (wanted) startSession()
      }
    } else {
      Presenter.setGlassesStatus(GlassesStatus.ERROR, reason)
    }
  }

  private fun attachDisplay(currentSession: DeviceSession) {
    currentSession
        .addDisplay()
        .fold(
            onSuccess = { newDisplay ->
              synchronized(lock) { display = newDisplay }
              track(
                  scope.launch {
                    var hasStarted = false
                    newDisplay.state.collect { state ->
                      Log.i(TAG, "Display state: $state")
                      when (state) {
                        DisplayState.STARTED -> {
                          hasStarted = true
                          Presenter.setGlassesStatus(
                              GlassesStatus.READY,
                              str(R.string.message_glasses_ready),
                          )
                          startRenderLoop()
                          requestRender()
                        }
                        // Two-finger temple tap ("Back") ends the display on the glasses.
                        DisplayState.STOPPED,
                        DisplayState.CLOSED ->
                            if (hasStarted) onSessionLost(str(R.string.message_display_closed))
                        else -> {}
                      }
                    }
                  }
              )
            },
            onFailure = { error, _ ->
              Log.e(TAG, "addDisplay failed: ${error.description}")
              Presenter.setGlassesStatus(GlassesStatus.ERROR, error.description)
            },
        )
  }

  private fun attachInputs(currentSession: DeviceSession) {
    val configuration =
        InputsConfiguration(
            sources =
                setOf(InputSource.NEURAL_BAND, InputSource.CAPTOUCH),
            consumeBack = false, // Keep the system's "back" so that the presenter can leave.
        )
    currentSession
        .addInputs(configuration)
        .fold(
            onSuccess = { inputs ->
              track(scope.launch { inputs.events.collect(gestures::onInput) })
              track(scope.launch { inputs.state.collect { Log.i(TAG, "Inputs state: $it") } })
            },
            onFailure = { error, _ ->
              Log.e(TAG, "addInputs failed: ${error.description}")
              Presenter.showMessage(R.string.message_inputs_failed, error.description)
            },
        )
  }

  private val gestures = GestureMapper()

  private fun track(job: Job) {
    synchronized(lock) { jobs += job }
  }

  private fun str(@StringRes resId: Int, vararg args: Any): String = context.getString(resId, *args)

  // -- Rendering --

  fun requestRender(inputAt: Long? = null) {
    renderRequests.trySend(RenderRequest(inputAt))
  }

  private fun startRenderLoop() {
    if (renderLoop?.isActive == true) return
    renderLoop =
        scope.launch {
          var request: RenderRequest? = renderRequests.receive()
          while (request != null) {
            // After a swipe, a rough image of the current slide goes first, because the time to
            // reach the glasses grows with the size (about 65 ms per KB over Bluetooth, measured
            // on 2026-11-19). The full screen with the next slide follows, unless the presenter
            // has swiped again in the meantime.
            if (request.inputAt != null) send(request, RenderStage.PREVIEW)
            val newer = renderRequests.tryReceive().getOrNull()
            if (newer != null) {
              request = newer
              continue
            }
            send(request, RenderStage.FINAL)
            request = renderRequests.tryReceive().getOrNull() ?: renderRequests.receive()
          }
        }
  }

  private suspend fun send(request: RenderRequest, stage: RenderStage) {
    val target = synchronized(lock) { display } ?: return
    val started = SystemClock.elapsedRealtime()
    val dataUri =
        when (stage) {
          RenderStage.PREVIEW -> {
            val screen = composeScreen(showNext = false)
            val small = screen.scale(PREVIEW_SIZE_PX, PREVIEW_SIZE_PX)
            toJpegDataUri(small, PREVIEW_JPEG_QUALITY)
          }
          RenderStage.FINAL -> toJpegDataUri(composeScreen(showNext = true), FINAL_JPEG_QUALITY)
        }
    val composed = SystemClock.elapsedRealtime()
    val result = target.sendContent { slidesView(dataUri.uri) }
    val sent = SystemClock.elapsedRealtime()
    val timing =
        RenderTiming(
            composeMs = composed - started,
            sendMs = sent - composed,
            fromInputMs = request.inputAt?.let { sent - it },
            bytes = dataUri.bytes,
            stage = stage,
        )
    Log.i(TAG, "Render timing: $timing")
    Presenter.recordTiming(timing)
    result.fold(
        onSuccess = {},
        onFailure = { error, _ -> Log.e(TAG, "sendContent failed: ${error.description}") },
    )
  }

  private fun composeScreen(showNext: Boolean): Bitmap {
    val state = Presenter.state.value
    val index = state.currentIndex
    val warning =
        when {
          !state.pcConnected -> str(R.string.glasses_pc_disconnected)
          else -> state.pcWarnings.values.firstOrNull()
        }
    if (state.displayPattern == DisplayPattern.FOCUS) {
      val nextNumber = index + 2 // 1-based number of the next slide
      val nextTitle =
          when {
            index + 1 >= state.slideCount -> str(R.string.glasses_last_slide)
            else ->
                state.slideTitles[index + 1]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: str(R.string.glasses_untitled_slide, nextNumber)
          }
      return SlideComposer.composeFocus(
          current = state.slideImages[index],
          nextTitle = if (showNext) str(R.string.glasses_next_title, nextTitle) else null,
          warning = warning,
          playing = state.videoPlaying,
      )
    }
    return SlideComposer.compose(
        current = state.slideImages[index],
        next = state.slideImages[index + 1],
        showNext = showNext,
        warning = warning,
        playing = state.videoPlaying,
    )
  }

  private class DataUri(val uri: String, val bytes: Int)

  /**
   * Encodes the screen ourselves instead of passing a Bitmap. The SDK would always use JPEG
   * quality 85, and the time to reach the glasses grows with the size (Bluetooth). Measured on
   * 2026-11-19: 370-2231 ms per update at quality 85.
   */
  private fun toJpegDataUri(screen: Bitmap, quality: Int): DataUri {
    val out = ByteArrayOutputStream()
    screen.compress(Bitmap.CompressFormat.JPEG, quality, out)
    val bytes = out.toByteArray()
    return DataUri("data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP), bytes.size)
  }

  private fun ContentScope.slidesView(uri: String) {
    flexBox(padding = 0, background = FlexBoxBackground.NONE) {
      image(uri = uri, sizePreset = ImageSize.FILL)
    }
  }

  // -- Phone preview (simulated glasses) --

  private fun enableMockGlasses(): MockGlasses? {
    mockGlasses?.let { return it }
    mockDeviceKit.enable()
    val glasses =
        mockDeviceKit
            .pairGlasses(GlassesModel.META_RAYBAN_DISPLAY)
            .map {
              it.powerOn()
              it.don()
              it
            }
            .onFailure { error, _ ->
              Log.e(TAG, "Mock glasses failed: $error")
              Presenter.setGlassesStatus(GlassesStatus.ERROR, str(R.string.message_mock_failed))
            }
            .getOrNull() ?: return null
    mockGlasses = glasses
    mockDeviceKit
        .startTestServer(CHROME_PREVIEW_PORT)
        .map { port -> Presenter.setPhonePreview(true, "http://127.0.0.1:$port/") }
    return glasses
  }

  fun disableMockGlasses() {
    runCatching { mockDeviceKit.stopTestServer() }
    mockGlasses?.let {
      it.doff()
      it.powerOff()
    }
    mockGlasses = null
    runCatching { mockDeviceKit.disable() }
    Presenter.setPhonePreview(false)
  }
}
