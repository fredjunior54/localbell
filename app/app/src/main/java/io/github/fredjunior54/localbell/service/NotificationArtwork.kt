package io.github.fredjunior54.localbell.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.service.notification.StatusBarNotification
import android.util.Base64
import com.google.gson.Gson
import io.github.fredjunior54.localbell.network.NotificationRequest
import kotlin.math.roundToInt

private const val SIDE = 48
private const val MAX_REQUEST_BYTES = 32 * 1024
private val gson = Gson()

internal fun NotificationRequest.withArtwork(
    context: Context,
    notification: StatusBarNotification,
): NotificationRequest {
    val artwork =
        runCatching { context.packageManager.getApplicationIcon(notification.packageName).toRgba() }
            .getOrNull()
            ?: runCatching {
                val source = context.createPackageContext(notification.packageName, 0)
                notification.notification.smallIcon?.loadDrawable(source)?.toRgba()
            }
                .getOrNull()
            ?: return this
    val decorated = copy(icon_rgba = artwork)
    return if (gson.toJson(decorated).toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES)
        decorated
    else this
}

private fun Drawable.toRgba(): String {
    val size = maxOf(intrinsicWidth, intrinsicHeight, 1).toFloat()
    val width = (SIDE * intrinsicWidth.coerceAtLeast(1) / size).roundToInt().coerceAtLeast(1)
    val height = (SIDE * intrinsicHeight.coerceAtLeast(1) / size).roundToInt().coerceAtLeast(1)
    val x = (SIDE - width) / 2
    val y = (SIDE - height) / 2
    val bitmap = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(SIDE * SIDE)
    try {
        setBounds(x, y, x + width, y + height)
        draw(Canvas(bitmap))
        bitmap.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE)
    } finally {
        bitmap.recycle()
    }
    val bytes = ByteArray(pixels.size * 4)
    pixels.forEachIndexed { index, pixel ->
        val offset = index * 4
        bytes[offset] = (pixel ushr 16).toByte()
        bytes[offset + 1] = (pixel ushr 8).toByte()
        bytes[offset + 2] = pixel.toByte()
        bytes[offset + 3] = (pixel ushr 24).toByte()
    }
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
}
