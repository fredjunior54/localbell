package io.github.fredjunior54.localbell.network

data class NotificationRequest(
    val key: String,
    val app_name: String,
    val package_name: String,
    val title: String,
    val message: String,
    val urgency: Int = 1,
    val timeout_ms: Int = -1,
    val icon_rgba: String? = null,
)
