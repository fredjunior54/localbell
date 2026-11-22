package io.github.fredjunior54.localbell.ui.screens

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.fredjunior54.localbell.ui.theme.LocalBellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TestNotificationActionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun onlyReadyIdleActionCanSend() {
        var sends = 0
        val ready = mutableStateOf(false)
        val state = mutableStateOf(MainScreenViewModel.TestNotificationUi())
        compose.setContent {
            LocalBellTheme { TestNotificationAction(state.value, ready.value) { sends++ } }
        }
        compose.onNodeWithText("Send Test Notification").assertIsNotEnabled()
        compose.runOnIdle { ready.value = true }
        compose.onNodeWithText("Send Test Notification").performClick()
        compose.runOnIdle {
            assertEquals(1, sends)
            state.value = MainScreenViewModel.TestNotificationUi(sending = true)
        }
        compose.onNodeWithText("Sending test notification…").assertIsNotEnabled()
    }
}
