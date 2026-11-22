package io.github.fredjunior54.localbell.service

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.fredjunior54.localbell.di.AppModule
import io.github.fredjunior54.localbell.network.NotificationRequest
import io.github.fredjunior54.localbell.network.PhoneSessionRuntime
import io.github.fredjunior54.localbell.network.SessionDelivery
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

class NotificationForwarderService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationForwarder"
        private const val PROGRESS_THROTTLE_MS = 2_000L
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lastProgressForward = ConcurrentHashMap<String, Long>()
    private val settingsRepository by lazy { AppModule.settingsRepository }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing) return
        if (sbn.notification.flags and Notification.FLAG_NO_CLEAR != 0) return
        // A group summary repeats its children, which are forwarded on their own
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        serviceScope.launch {
            try {
                settingsRepository.addKnownApp(sbn.packageName)
                val reverse = PhoneSessionService.active ?: return@launch
                if (reverse.status.value.phase != PhoneSessionRuntime.Phase.READY) return@launch
                if (!shouldForward(sbn.packageName, sbn.key, sbn.notification)) return@launch
                val request = makeRequest(sbn)?.withArtwork(this@NotificationForwarderService, sbn)
                if (request != null)
                    reverse.offer(SessionDelivery.Kind.AUTOMATIC, sbn.key, request.sessionPayload())
            } catch (_: Exception) {
                Log.e(TAG, "Error forwarding notification")
            }
        }
    }

    private suspend fun shouldForward(
        packageName: String,
        key: String,
        notification: Notification,
    ): Boolean {
        if (packageName in settingsRepository.blacklistedApps.first()) return false
        val progressMax = notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX)
        if (progressMax > 0) {
            val progress = notification.extras.getInt(Notification.EXTRA_PROGRESS)
            val isComplete = progress >= progressMax
            if (!isComplete) {
                val now = System.currentTimeMillis()
                val last = lastProgressForward[key] ?: 0L
                if (now - last < PROGRESS_THROTTLE_MS) {
                    Log.d(TAG, "Progress notification throttled")
                    return false
                }
                lastProgressForward[key] = now
            }
        }
        return true
    }

    internal fun makeRequest(sbn: StatusBarNotification): NotificationRequest? {
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (text.isBlank()) {
            Log.d(TAG, "Empty notification text, skipping")
            return null
        }
        val appName = getAppName(sbn.packageName)
        return NotificationRequest(
            key = sbn.key,
            app_name = appName,
            package_name = sbn.packageName,
            title = title,
            message = text,
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return

        if (sbn.packageName == packageName) return

        lastProgressForward.remove(sbn.key)

        PhoneSessionService.active?.offer(SessionDelivery.Kind.DISMISS, sbn.key)
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }
}

internal fun NotificationRequest.sessionPayload(): JSONObject =
    JSONObject()
        .put("key", key)
        .put("app_name", app_name)
        .put("title", title)
        .put("message", message)
        .put("urgency", urgency)
        .put("timeout_ms", timeout_ms)
        .put("icon_rgba", icon_rgba)
