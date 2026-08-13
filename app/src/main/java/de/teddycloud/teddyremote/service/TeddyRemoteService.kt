package de.teddycloud.teddyremote.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import de.teddycloud.teddyremote.MainActivity
import de.teddycloud.teddyremote.R
import de.teddycloud.teddyremote.TeddyRemoteApplication
import de.teddycloud.teddyremote.model.ConnectionStatus
import de.teddycloud.teddyremote.model.LinkStatus
import de.teddycloud.teddyremote.model.WifiGateState
import de.teddycloud.teddyremote.model.userMessage
import de.teddycloud.teddyremote.repository.TeddyRemoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TeddyRemoteService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mediaSessions: BoxMediaSessionManager
    private lateinit var repository: TeddyRemoteRepository

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        repository = (application as TeddyRemoteApplication).container.repository
        mediaSessions = BoxMediaSessionManager(this, repository, scope)
        startForeground(CONNECTION_NOTIFICATION_ID, connectionNotification(repository.connection.value))
        scope.launch {
            repository.boxes.collectLatest(mediaSessions::update)
        }
        scope.launch {
            repository.connection.collectLatest { status ->
                getSystemService(NotificationManager::class.java)
                    .notify(CONNECTION_NOTIFICATION_ID, connectionNotification(status))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_RECONNECT -> scope.launch { repository.connect() }
            ACTION_STOP -> scope.launch {
                repository.disconnect()
                stopSelf()
            }
            else -> {
                val boxId = intent?.getStringExtra(EXTRA_BOX_ID)
                if (boxId != null && action != null) mediaSessions.handleAction(boxId, action)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mediaSessions.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun connectionNotification(status: ConnectionStatus): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = serviceAction(ACTION_STOP, REQUEST_STOP)
        val paused = status.wifiGate != WifiGateState.AVAILABLE
        val connectionInterrupted = status.apiStatus in setOf(LinkStatus.ERROR, LinkStatus.DISCONNECTED) ||
            status.mqttStatus == LinkStatus.ERROR
        val canReconnect = status.desiredConnected && !paused && connectionInterrupted
        val title = when {
            paused -> getString(R.string.service_paused)
            status.apiStatus == LinkStatus.CONNECTING -> getString(R.string.service_connecting)
            status.isApiUsable -> getString(R.string.service_connected)
            else -> getString(R.string.service_interrupted)
        }
        val detail = when {
            paused -> status.wifiGate.userMessage
            status.message != null -> status.message
            else -> getString(R.string.service_waiting)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(openApp)
            .addAction(notificationAction(R.string.service_action_stop, stop))
            .apply {
                if (canReconnect) {
                    addAction(
                        notificationAction(
                            R.string.service_action_reconnect,
                            serviceAction(ACTION_RECONNECT, REQUEST_RECONNECT),
                        ),
                    )
                }
            }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, TeddyRemoteService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notificationAction(label: Int, intent: PendingIntent): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notification),
            getString(label),
            intent,
        ).build()

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
        const val ACTION_RECONNECT = "de.teddycloud.teddyremote.RECONNECT"
        const val ACTION_STOP = "de.teddycloud.teddyremote.STOP"
        private const val CONNECTION_NOTIFICATION_ID = 80
        private const val REQUEST_RECONNECT = 81
        private const val REQUEST_STOP = 82
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
