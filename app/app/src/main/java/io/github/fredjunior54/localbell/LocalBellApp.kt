package io.github.fredjunior54.localbell

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import io.github.fredjunior54.localbell.di.AppModule

class LocalBellApp : Application() {

    companion object {
        const val CHANNEL_ID = "localbell_service"
    }

    override fun onCreate() {
        super.onCreate()
        AppModule.init(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                    CHANNEL_ID,
                    "Notification Forwarder",
                    NotificationManager.IMPORTANCE_LOW,
                )
                .apply {
                    description = "Shows when LocalBell is forwarding notifications"
                    setShowBadge(false)
                }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
}
