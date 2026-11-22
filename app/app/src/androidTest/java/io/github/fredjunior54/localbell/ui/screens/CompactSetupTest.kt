package io.github.fredjunior54.localbell.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompactSetupTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun permissionRowsKeepAccessibleStateAndAllSetupActions() {
        val granted = mutableStateOf(false)
        val clicked = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                MainScreenContent(
                    hasNotificationAccess = granted.value,
                    isBatteryOptimizationDisabled = granted.value,
                    onNotificationAccessClick = { clicked += "Access" },
                    onBatteryOptimizationClick = { clicked += "Battery" },
                    onAboutClick = {},
                    onFilterClick = { clicked += "Filters" },
                )
            }
        }
        val access = compose.onNodeWithText("Notification Access")
        val battery = compose.onNodeWithText("Battery Optimization")
        fun state(value: String) =
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)
        access.assert(state("Not granted")).performClick()
        battery.assert(state("Restricted")).performClick()
        compose.onNodeWithText("App Filters").performClick()
        compose.runOnIdle {
            assertEquals(listOf("Access", "Battery", "Filters"), clicked)
            granted.value = true
        }
        access.assert(state("Granted"))
        battery.assert(state("Unrestricted"))
        listOf("Granted", "Unrestricted", "Tap to grant", "Tap to disable", "All apps forwarded")
            .forEach {
                compose.onNodeWithText(it).assertDoesNotExist()
            }
    }
}
