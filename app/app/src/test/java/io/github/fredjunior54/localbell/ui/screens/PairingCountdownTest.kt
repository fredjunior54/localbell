package io.github.fredjunior54.localbell.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingCountdownTest {
    @Test
    fun `remaining time is based on the original monotonic deadline`() {
        assertEquals(30_000L, approvalTimeRemaining(40_000L, 10_000L))
        assertEquals(12_000L, approvalTimeRemaining(40_000L, 28_000L))
        assertEquals(0L, approvalTimeRemaining(40_000L, 40_000L))
        assertEquals(0L, approvalTimeRemaining(40_000L, 43_000L))
    }
}
