package io.github.fredjunior54.localbell.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.fredjunior54.localbell.network.PhoneSessionRuntime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListenerStatusTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun highlightsReadyAndWaitingAndOnlyReportsTestRejection() {
        val dark = mutableStateOf(true)
        val status =
            mutableStateOf(
                PhoneSessionRuntime.Status(PhoneSessionRuntime.Phase.READY, testAccepted = true),
            )
        compose.setContent {
            MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                ListenerStatus(19420, status.value)
            }
        }
        compose.onNodeWithText("Ready to forward").assertIsDisplayed()
        compose.onNodeWithText("Test notification delivered.").assertDoesNotExist()
        compose.runOnIdle { status.value = status.value.copy(testAccepted = false) }
        compose.onNodeWithText("Could not deliver the test notification.").assertIsDisplayed()
        compose.runOnIdle {
            dark.value = false
            status.value = PhoneSessionRuntime.Status(PhoneSessionRuntime.Phase.LISTENING)
        }
        compose.onNodeWithText("Waiting for desktop").assertIsDisplayed()
        compose.onNodeWithText("Listen port: 19420").assertIsDisplayed()
        compose.onNodeWithText("Could not deliver the test notification.").assertDoesNotExist()
    }
}
