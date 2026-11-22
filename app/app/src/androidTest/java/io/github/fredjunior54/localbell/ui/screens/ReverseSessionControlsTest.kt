package io.github.fredjunior54.localbell.ui.screens

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fredjunior54.localbell.MainActivity
import io.github.fredjunior54.localbell.service.PhoneSessionService
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReverseSessionControlsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun showsListenerSettingsAndDisablesTheTestActionUntilConnected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val launch = Intent(context, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(launch).use {
            try {
                compose.onNodeWithText("Server Configuration").assertDoesNotExist()
                compose.onNodeWithText("Forwarding").assertDoesNotExist()
                compose.onNodeWithText("App Filters").assertIsDisplayed()
                compose.onNodeWithText("Send Test Notification").assertIsNotEnabled()
                assertEmptyForwardingCount()
                compose.onNodeWithText("Listener settings").performClick()
                compose.onNodeWithText("Port").assertIsDisplayed()
                compose.onNodeWithText("19420").assertIsDisplayed()
            } finally {
                context.stopService(Intent(context, PhoneSessionService::class.java))
            }
        }
    }

    @Test
    fun pairsWithRustAndDeliversATestNotification() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("reverseInterop") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            android.os.Build.FINGERPRINT.contains("generic") ||
                android.os.Build.HARDWARE.contains("ranchu"),
        )
        check(!context.noBackupFilesDir.resolve("desktop-v1.digest").exists()) {
            "Use a fresh isolated emulator without a saved LocalBell pairing"
        }
        val launch = Intent(context, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(launch).use {
            try {
                awaitText("Numbers match")
                compose.onNodeWithText("Numbers match").performClick()
                awaitText("Ready to forward")
                compose.onNodeWithText("Send Test Notification").performScrollTo().performClick()
                awaitText("Last result: Accepted", substring = true)
                compose.onNodeWithText("Test notification delivered.").assertDoesNotExist()
                assertEmptyForwardingCount()
            } finally {
                PhoneSessionService.active?.unpair()
                context.stopService(Intent(context, PhoneSessionService::class.java))
            }
        }
    }

    private fun assertEmptyForwardingCount() {
        compose.onNodeWithText("Forwarded this session").assertExists()
        compose.onNodeWithText("0").assertExists()
    }

    private fun awaitText(value: String, substring: Boolean = false) {
        compose.waitUntil(timeoutMillis = 20_000) {
            compose
                .onAllNodes(hasText(value, substring = substring))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }
}
