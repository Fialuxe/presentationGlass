package com.presentationglass.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Makes the phone a Bluetooth keyboard and mouse for the PC (Bluetooth HID device profile).
 *
 * The PC then gets real key presses and mouse clicks, not script events in a web page. That is
 * why this path works where the extension cannot: Chrome lets a video start with sound only after
 * a real user input, and slide software of any kind (Google Slides, PowerPoint, Keynote) reacts to
 * real arrow keys.
 *
 * Pairing is done once, from the PC: the phone becomes discoverable, and the presenter adds
 * "PresentationGlass" in the PC's Bluetooth settings. After that the PC reconnects by itself.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is requested before this class is used.
class HidController(private val context: Context) {

  enum class Status {
    UNAVAILABLE,
    REGISTERING,
    WAITING_FOR_PC,
    CONNECTED,
  }

  private companion object {
    const val TAG = "HidController"
    const val REPORT_KEYBOARD: Byte = 1
    const val REPORT_MOUSE: Byte = 2
    const val KEY_RIGHT_ARROW: Byte = 0x4F
    const val KEY_LEFT_ARROW: Byte = 0x50
    const val KEY_PAUSE: Byte = 0x48

    /**
     * One report: a keyboard (report 1) and a three-button mouse with a wheel (report 2). The app
     * no longer sends mouse input, but the mouse stays in the descriptor: Windows keeps the
     * descriptor from pairing, and changing it would force every PC to pair again.
     */
    val DESCRIPTOR =
        byteArrayOf(
            // Keyboard
            0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01, 0x85.toByte(), REPORT_KEYBOARD,
            0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(), 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02, // modifier keys
            0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x01, // reserved
            0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07,
            0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00, // up to six keys
            0xC0.toByte(),
            // Mouse
            0x05, 0x01, 0x09, 0x02, 0xA1.toByte(), 0x01, 0x85.toByte(), REPORT_MOUSE,
            0x09, 0x01, 0xA1.toByte(), 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01,
            0x95.toByte(), 0x03, 0x75, 0x01, 0x81.toByte(), 0x02, // buttons
            0x95.toByte(), 0x01, 0x75, 0x05, 0x81.toByte(), 0x01, // padding
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
            0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x03,
            0x81.toByte(), 0x06, // x, y, wheel (relative)
            0xC0.toByte(), 0xC0.toByte(),
        )
  }

  private val _status = MutableStateFlow(Status.UNAVAILABLE)
  val status: StateFlow<Status> = _status.asStateFlow()

  private val adapter: BluetoothAdapter? =
      context.getSystemService(BluetoothManager::class.java)?.adapter
  private val executor = Executors.newSingleThreadExecutor()
  private var hid: BluetoothHidDevice? = null
  @Volatile private var host: BluetoothDevice? = null

  private val prefs = context.getSharedPreferences("hid", Context.MODE_PRIVATE)
  @Volatile private var registered = false

  private val callback =
      object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
          Log.i(TAG, "App registered: $registered, plugged: $pluggedDevice")
          this@HidController.registered = registered
          if (!registered) {
            host = null
            _status.value = Status.UNAVAILABLE
            return
          }
          // A PC may already be connected; no connection callback comes for it then.
          if (!refresh()) {
            // Reconnect to the PC used last time, so that the presenter does nothing on the PC.
            val known = pluggedDevice ?: lastHost()
            if (known != null) connectTo(known)
          }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
          Log.i(TAG, "Connection to ${device.address}: $state")
          when (state) {
            BluetoothProfile.STATE_CONNECTED -> {
              host = device
              prefs.edit().putString("host", device.address).apply()
              _status.value = Status.CONNECTED
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
              if (host == device) host = null
              refresh()
            }
          }
        }
      }

  /**
   * Windows finishes pairing but often waits for the keyboard to open the connection. So when a
   * PC bonds with the phone, the phone connects to it.
   */
  private val bondReceiver =
      object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          val device =
              (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
              } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
              }) ?: return
          when (intent.action) {
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
              val bond = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
              Log.i(TAG, "Bond with ${device.address}: $bond")
              if (bond == BluetoothDevice.BOND_BONDED && isComputer(device)) connectTo(device)
            }
            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> refresh()
          }
        }
      }
  private var receiverRegistered = false

  private fun isComputer(device: BluetoothDevice): Boolean {
    val major = device.bluetoothClass?.majorDeviceClass ?: return true
    return major == BluetoothClass.Device.Major.COMPUTER ||
        major == BluetoothClass.Device.Major.UNCATEGORIZED
  }

  private fun lastHost(): BluetoothDevice? {
    val address = prefs.getString("host", null) ?: return null
    return adapter?.bondedDevices?.firstOrNull { it.address == address }
  }

  private fun connectTo(device: BluetoothDevice) {
    if (!registered) return
    Log.i(TAG, "Connecting to ${device.address}")
    hid?.connect(device)
  }

  /**
   * Reads the real connection from the system and updates the status. The callback alone can miss
   * a connection, which left the app showing "waiting" while the PC was connected.
   * Returns true when a PC is connected.
   */
  fun refresh(): Boolean {
    val proxy = hid
    if (proxy == null || !registered) return false
    val connected = proxy.connectedDevices.firstOrNull()
    host = connected
    _status.value = if (connected != null) Status.CONNECTED else Status.WAITING_FOR_PC
    return connected != null
  }

  /** Registers the phone as a keyboard and mouse. Safe to call more than once. */
  fun start() {
    if (hid != null || adapter == null) return
    _status.value = Status.REGISTERING
    if (!receiverRegistered) {
      val filter =
          IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
          }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED)
      } else {
        context.registerReceiver(bondReceiver, filter)
      }
      receiverRegistered = true
    }
    val ok =
        adapter.getProfileProxy(
            context,
            object : BluetoothProfile.ServiceListener {
              override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val device = proxy as BluetoothHidDevice
                hid = device
                val sdp =
                    BluetoothHidDeviceAppSdpSettings(
                        "PresentationGlass",
                        "Neural Band remote",
                        "PresentationGlass",
                        BluetoothHidDevice.SUBCLASS1_COMBO,
                        DESCRIPTOR,
                    )
                device.registerApp(sdp, null, null, executor, callback)
              }

              override fun onServiceDisconnected(profile: Int) {
                hid = null
                _status.value = Status.UNAVAILABLE
              }
            },
            BluetoothProfile.HID_DEVICE,
        )
    if (!ok) {
      Log.e(TAG, "This phone does not offer the Bluetooth HID device profile")
      _status.value = Status.UNAVAILABLE
    }
  }

  fun stop() {
    if (receiverRegistered) {
      context.unregisterReceiver(bondReceiver)
      receiverRegistered = false
    }
    registered = false
    hid?.unregisterApp()
    hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
    hid = null
    host = null
    _status.value = Status.UNAVAILABLE
  }

  val isConnected: Boolean
    get() = host != null

  // -- Input --

  fun pressRightArrow() = pressKey(KEY_RIGHT_ARROW)

  fun pressLeftArrow() = pressKey(KEY_LEFT_ARROW)

  /** A key with no effect in slide software. It gives the page a real user input. */
  fun pressPauseKey() = pressKey(KEY_PAUSE)

  private fun pressKey(key: Byte) {
    sendKeyboard(byteArrayOf(0, 0, key, 0, 0, 0, 0, 0))
    sendKeyboard(ByteArray(8)) // release
  }

  private fun sendKeyboard(report: ByteArray) {
    val target = host ?: return
    hid?.sendReport(target, REPORT_KEYBOARD.toInt(), report)
  }
}
