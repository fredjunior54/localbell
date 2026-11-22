package io.github.fredjunior54.localbell.ui.screens

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fredjunior54.localbell.MainActivity
import io.github.fredjunior54.localbell.service.PhoneSessionService
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReverseSessionControlsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun showsListenerSettingsAndDisablesTheTestActionUntilConnected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val launch =
            Intent(context, MainActivity::class.java)
                .putExtra("io.github.fredjunior54.localbell.REVERSE_PREVIEW", true)
        ActivityScenario.launch<MainActivity>(launch).use {
            try {
                compose.onNodeWithText("Server Configuration").assertDoesNotExist()
                compose.onNodeWithText("Forwarding").assertDoesNotExist()
                compose.onNodeWithText("App Filters").assertIsDisplayed()
                compose.onNodeWithText("Send Test Notification").assertIsNotEnabled()
                compose.onNodeWithText("Forwarded this session: 0").assertExists()
                compose.onNodeWithText("Listener settings").performClick()
                compose.onNodeWithText("Port").assertIsDisplayed()
                compose.onNodeWithText("19420").assertIsDisplayed()
            } finally {
                context.stopService(Intent(context, PhoneSessionService::class.java))
            }
        }
    }
}
