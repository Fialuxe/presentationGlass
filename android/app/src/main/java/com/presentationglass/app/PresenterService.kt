package com.presentationglass.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps the app alive while the phone is in a pocket with the screen off. It runs the WebSocket
 * server for the PC; the glasses session itself is driven by [Presenter.glasses].
 */
class PresenterService : Service() {

  companion object {
    private const val CHANNEL_ID = "presenting"
    private const val NOTIFICATION_ID = 1

    fun start(context: Context) {
      context.startForegroundService(Intent(context, PresenterService::class.java))
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, PresenterService::class.java))
    }
  }

  private var wakeLock: PowerManager.WakeLock? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    startForeground(
        NOTIFICATION_ID,
        buildNotification(),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
    )
    if (wakeLock == null) {
      val pm = getSystemService(PowerManager::class.java)
      wakeLock =
          pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PresentationGlass:presenting").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L)
          }
    }
    Presenter.startServer()
    Presenter.hid.start()
    return START_STICKY
  }

  override fun onDestroy() {
    Presenter.stopServer()
    Presenter.hid.stop()
    wakeLock?.release()
    wakeLock = null
    super.onDestroy()
  }

  private fun buildNotification(): Notification {
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
    )
    val openApp =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
    return Notification.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.notification_text))
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentIntent(openApp)
        .setOngoing(true)
        .build()
  }
}
