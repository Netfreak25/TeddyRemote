package de.teddycloud.teddyremote.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import de.teddycloud.teddyremote.MainActivity
import de.teddycloud.teddyremote.R
import de.teddycloud.teddyremote.TeddyRemoteApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TeddyRemoteService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mediaSessions: BoxMediaSessionManager

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val repository = (application as TeddyRemoteApplication).container.repository
        mediaSessions = BoxMediaSessionManager(this, repository, scope)
        startForeground(CONNECTION_NOTIFICATION_ID, connectionNotification())
        scope.launch {
            repository.boxes.collectLatest(mediaSessions::update)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val boxId = intent?.getStringExtra(EXTRA_BOX_ID)
        val action = intent?.action
        if (boxId != null && action != null) mediaSessions.handleAction(boxId, action)
        return START_STICKY
    }

    override fun onDestroy() {
        mediaSessions.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun connectionNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.service_connected))
            .setContentText(getString(R.string.service_waiting))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.service_channel_description)
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val CHANNEL_ID = "teddyremote-media"
        const val EXTRA_BOX_ID = "de.teddycloud.teddyremote.BOX_ID"
        const val ACTION_PREVIOUS = "de.teddycloud.teddyremote.PREVIOUS"
        const val ACTION_PLAY_PAUSE = "de.teddycloud.teddyremote.PLAY_PAUSE"
        const val ACTION_NEXT = "de.teddycloud.teddyremote.NEXT"
        const val ACTION_VOLUME_DOWN = "de.teddycloud.teddyremote.VOLUME_DOWN"
        const val ACTION_VOLUME_UP = "de.teddycloud.teddyremote.VOLUME_UP"
        private const val CONNECTION_NOTIFICATION_ID = 80
        private const val LOG_TAG = "TeddyRemoteService"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TeddyRemoteService::class.java))
            } catch (error: IllegalStateException) {
                // Android 12+ rejects foreground-service starts while the process is cached.
                // The application lifecycle retries when the UI next enters the foreground.
                Log.w(LOG_TAG, "Foreground service start deferred until the app is visible", error)
            }
        }

        @SuppressLint("ImplicitSamInstance")
        fun stop(context: Context) {
            context.stopService(Intent(context, TeddyRemoteService::class.java))
        }
    }
}
