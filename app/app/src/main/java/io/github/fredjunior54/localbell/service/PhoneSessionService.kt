package io.github.fredjunior54.localbell.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.fredjunior54.localbell.LocalBellApp
import io.github.fredjunior54.localbell.MainActivity
import io.github.fredjunior54.localbell.R
import io.github.fredjunior54.localbell.network.PhoneSessionRuntime
import io.github.fredjunior54.localbell.util.LocalNetworkPermission
import java.io.File
import java.util.concurrent.ScheduledThreadPoolExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android owns the listener independently of notification access. Start from a visible activity.
 */
internal class PhoneSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val deadlines = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private var session: PhoneSessionRuntime? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
        if (port !in 1..65535 || LocalNetworkPermission.isMissing(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(
            NOTIFICATION_ID,
            createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        if (session == null) {
            val runtime =
                PhoneSessionRuntime(File(noBackupFilesDir, "desktop-v1.digest"), deadlines)
            session = runtime
            active = runtime
            scope.launch { runtime.run(port) }
        }
        return START_NOT_STICKY
    }

    private fun createNotification(): android.app.Notification =
        NotificationCompat.Builder(this, LocalBellApp.CHANNEL_ID)
            .setContentTitle("LocalBell")
            .setContentText("Listening for your desktop")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOngoing(true)
            .setSilent(true)
            .build()

    override fun onDestroy() {
        if (active === session) active = null
        session?.close()
        deadlines.shutdownNow()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PORT = "port"
        const val DEFAULT_PORT = 19420
        private const val NOTIFICATION_ID = 19420

        @Volatile
        var active: PhoneSessionRuntime? = null
            private set
    }
}
