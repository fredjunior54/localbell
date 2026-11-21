package io.github.fredjunior54.localbell.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.fredjunior54.localbell.ui.theme.LocalBellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun pairSubmitsSavedAddressAndPort() {
        var submitted: String? = null
        compose.setContent {
            LocalBellTheme {
                PairingDialog(
                    MainScreenViewModel.PairingUi(),
                    "https://100.70.80.90:12345/",
                    { submitted = it },
                    {},
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithText("IP address or hostname").assertIsDisplayed()
        compose.onNodeWithText("Port").assertIsDisplayed()
        compose.onNodeWithText("Pair").performClick()
        assertEquals("100.70.80.90:12345", submitted)
    }

    @Test
    fun cancelDoesNotSubmitDefaultEndpoint() {
        var closed = false
        compose.setContent {
            LocalBellTheme {
                PairingDialog(
                    MainScreenViewModel.PairingUi(),
                    null,
                    { error("Unexpected pairing") },
                    {},
                    {},
                    { closed = true },
                )
            }
        }
        compose.onNodeWithText("19420").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(closed)
    }
}
