package com.presentationglass.app

import android.content.Context
import android.util.Log
import java.net.InetSocketAddress
import java.util.Collections
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject

/**
 * WebSocket server that the Chrome extension connects to.
 *
 * A client must first send a "hello" message with the right PIN and protocol version. Until then,
 * every other message is ignored, so that other devices on the same network cannot read the
 * slides.
 */
class DeckServer(
    private val context: Context,
    port: Int,
    private val isValidPin: (String) -> Boolean,
    private val onMessageFromPc: (JSONObject) -> Unit,
    private val onAuthorizedCountChanged: (Int) -> Unit,
    private val onServerError: (Exception) -> Unit,
) : WebSocketServer(InetSocketAddress(port)) {

  private companion object {
    const val TAG = "DeckServer"
    const val CLOSE_BAD_HELLO = 4001
    const val CLOSE_PIN_CHANGED = 4002
  }

  private val authorized = Collections.synchronizedSet(mutableSetOf<WebSocket>())

  override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
    Log.i(TAG, "Client connected: ${conn.remoteSocketAddress}")
  }

  override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
    if (authorized.remove(conn)) onAuthorizedCountChanged(authorized.size)
    Log.i(TAG, "Client closed: $code $reason")
  }

  override fun onMessage(conn: WebSocket, message: String) {
    val json = runCatching { JSONObject(message) }.getOrNull() ?: return
    val type = json.optString("type")

    if (type == Protocol.Type.HELLO) {
      handleHello(conn, json)
      return
    }
    if (conn !in authorized) return

    if (type == Protocol.Type.PING) {
      conn.send(JSONObject().put("type", Protocol.Type.PONG).toString())
      return
    }
    onMessageFromPc(json)
  }

  private fun handleHello(conn: WebSocket, json: JSONObject) {
    val error =
        when {
          json.optInt("version") != Protocol.VERSION ->
              context.getString(
                  R.string.error_version_mismatch,
                  json.optInt("version"),
                  Protocol.VERSION,
              )
          !isValidPin(json.optString("pin")) -> context.getString(R.string.error_wrong_pin)
          else -> null
        }
    if (error != null) {
      conn.send(JSONObject().put("type", Protocol.Type.ERROR).put("message", error).toString())
      conn.close(CLOSE_BAD_HELLO, error)
      return
    }
    authorized.add(conn)
    conn.send(
        JSONObject().put("type", Protocol.Type.WELCOME).put("version", Protocol.VERSION).toString()
    )
    onAuthorizedCountChanged(authorized.size)
  }

  override fun onError(conn: WebSocket?, ex: Exception) {
    Log.e(TAG, "WebSocket error", ex)
    // Without a connection, the error is about the server itself, for example a port in use.
    if (conn == null) onServerError(ex)
  }

  override fun onStart() {
    Log.i(TAG, "Server ready")
  }

  fun broadcastToAuthorized(text: String) {
    val targets = synchronized(authorized) { authorized.toList() }
    targets.forEach { runCatching { it.send(text) } }
  }

  fun closeAll() {
    val targets = synchronized(authorized) { authorized.toList() }
    targets.forEach { runCatching { it.close(CLOSE_PIN_CHANGED, "pin changed") } }
  }
}
