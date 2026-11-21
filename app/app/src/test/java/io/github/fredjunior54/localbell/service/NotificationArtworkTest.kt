package io.github.fredjunior54.localbell.service

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.Base64
import io.github.fredjunior54.localbell.network.NotificationRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationArtworkTest {
    @Test
    fun launcherArtworkUsesBoundedRgba() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.packageManager)
            .setApplicationIcon(context.packageName, ColorDrawable(Color.BLUE))
        val request =
            NotificationRequest("test", "LocalBell", context.packageName, "Test", "Sample")
        val artwork = checkNotNull(request.withAppArtwork(context).icon_rgba)
        val pixels = Base64.decode(artwork, Base64.NO_WRAP)
        assertEquals(48 * 48 * 4, pixels.size)
        assertTrue(pixels.indices.any { it % 4 == 3 && pixels[it] != 0.toByte() })
        assertNull(
            request.copy(package_name = "invalid.package.name").withAppArtwork(context).icon_rgba,
        )
        assertNull(request.copy(message = "x".repeat(30_000)).withAppArtwork(context).icon_rgba)
    }
}
