package io.github.fredjunior54.localbell.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.fredjunior54.localbell.network.HealthMonitor
import io.github.fredjunior54.localbell.ui.theme.LocalBellTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainScreenTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun mainScreen_displaysSetupSection() {
        composeTestRule.setContent {
            LocalBellTheme {
                MainScreenContent(
                    isConfigured = false,
                    serviceEnabled = false,
                    connectionState = HealthMonitor.ConnectionState.DISCONNECTED,
                    serverUrl = null,
                    hasNotificationAccess = false,
                    isBatteryOptimizationDisabled = false,
                    onNotificationAccessClick = {},
                    onBatteryOptimizationClick = {},
                    onServerConfigClick = {},
                    onServiceEnabledChange = {},
                    onAboutClick = {},
                    onFilterClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Setup").assertIsDisplayed()
        composeTestRule.onNodeWithText("Notification Access").assertIsDisplayed()
        composeTestRule.onNodeWithText("Battery Optimization").assertIsDisplayed()
        composeTestRule.onNodeWithText("Server Configuration").assertIsDisplayed()
    }

    @Test
    fun mainScreen_displaysStatusSection() {
        composeTestRule.setContent {
            LocalBellTheme {
                MainScreenContent(
                    isConfigured = false,
                    serviceEnabled = false,
                    connectionState = HealthMonitor.ConnectionState.DISCONNECTED,
                    serverUrl = null,
                    hasNotificationAccess = false,
                    isBatteryOptimizationDisabled = false,
                    onNotificationAccessClick = {},
                    onBatteryOptimizationClick = {},
                    onServerConfigClick = {},
                    onServiceEnabledChange = {},
                    onAboutClick = {},
                    onFilterClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Status").assertIsDisplayed()
        composeTestRule.onNodeWithText("Server Status").assertIsDisplayed()
        composeTestRule.onNodeWithText("Forwarding").assertIsDisplayed()
    }

    @Test
    fun mainScreen_showsNotConfiguredStatus_whenNotConfigured() {
        composeTestRule.setContent {
            LocalBellTheme {
                MainScreenContent(
                    isConfigured = false,
                    serviceEnabled = false,
                    connectionState = HealthMonitor.ConnectionState.DISCONNECTED,
                    serverUrl = null,
                    hasNotificationAccess = true,
                    isBatteryOptimizationDisabled = true,
                    onNotificationAccessClick = {},
                    onBatteryOptimizationClick = {},
                    onServerConfigClick = {},
                    onServiceEnabledChange = {},
                    onAboutClick = {},
                    onFilterClick = {},
                )
            }
        }

        composeTestRule
            .onNode(hasText("Server Status") and hasText("Configure first"))
            .assertIsDisplayed()
    }

    @Test
    fun mainScreen_showsConnectedStatus_whenConnected() {
        composeTestRule.setContent {
            LocalBellTheme {
                MainScreenContent(
                    isConfigured = true,
                    serviceEnabled = true,
                    connectionState = HealthMonitor.ConnectionState.CONNECTED,
                    serverUrl = "https://192.168.1.100:19420",
                    hasNotificationAccess = true,
                    isBatteryOptimizationDisabled = true,
                    onNotificationAccessClick = {},
                    onBatteryOptimizationClick = {},
                    onServerConfigClick = {},
                    onServiceEnabledChange = {},
                    onAboutClick = {},
                    onFilterClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("https://192.168.1.100:19420").assertIsDisplayed()
    }

    @Test
    fun mainScreen_showsDiscoveringStatus_whenConnecting() {
        composeTestRule.setContent {
            LocalBellTheme {
                MainScreenContent(
                    isConfigured = true,
                    serviceEnabled = false,
                    connectionState = HealthMonitor.ConnectionState.CONNECTING,
                    serverUrl = null,
                    hasNotificationAccess = true,
                    isBatteryOptimizationDisabled = true,
                    onNotificationAccessClick = {},
                    onBatteryOptimizationClick = {},
                    onServerConfigClick = {},
                    onServiceEnabledChange = {},
                    onAboutClick = {},
                    onFilterClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Discovering...").assertIsDisplayed()
    }

    @Test
    fun mainScreen_showsGrantedStatus_whenNotificationAccessGranted() {
        composeTestRule.setContent {
            LocalBellTheme {
                MainScreenContent(
                    isConfigured = false,
                    serviceEnabled = false,
                    connectionState = HealthMonitor.ConnectionState.DISCONNECTED,
                    serverUrl = null,
                    hasNotificationAccess = true,
                    isBatteryOptimizationDisabled = false,
                    onNotificationAccessClick = {},
                    onBatteryOptimizationClick = {},
                    onServerConfigClick = {},
                    onServiceEnabledChange = {},
                    onAboutClick = {},
                    onFilterClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Granted").assertIsDisplayed()
    }

    @Test
    fun mainScreen_showsTapToGrantStatus_whenNotificationAccessNotGranted() {
        composeTestRule.setContent {
            LocalBellTheme {
                MainScreenContent(
                    isConfigured = false,
                    serviceEnabled = false,
                    connectionState = HealthMonitor.ConnectionState.DISCONNECTED,
                    serverUrl = null,
                    hasNotificationAccess = false,
                    isBatteryOptimizationDisabled = false,
                    onNotificationAccessClick = {},
                    onBatteryOptimizationClick = {},
                    onServerConfigClick = {},
                    onServiceEnabledChange = {},
                    onAboutClick = {},
                    onFilterClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Tap to grant").assertIsDisplayed()
    }
}
