package io.github.fredjunior54.localbell.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthReadinessTest {
    @Test
    fun requiresExactHttpAndJsonReadiness() {
        val ready = """{"status":"ready"}"""
        assertTrue(isReadyResponse(200, ready))
        for (status in listOf(201, 204, 401, 403, 500)) assertFalse(isReadyResponse(status, ready))
        for (body in
            listOf(
                "",
                "{}",
                "null",
                "[]",
                "{status:'ready'}",
                """{"status":true}""",
                "$ready extra",
            )) {
            assertFalse(isReadyResponse(200, body))
        }
    }

    @Test
    fun onlyCurrentReadyPairingCanDeliver() {
        val ready =
            HealthMonitor.Readiness(
                HealthMonitor.ConnectionState.CONNECTED,
                "saved",
                "https://receiver",
            )
        assertEquals("https://receiver", ready.urlFor("saved"))
        assertNull(ready.urlFor("changed"))
        assertNull(ready.copy(state = HealthMonitor.ConnectionState.CONNECTING).urlFor("saved"))
        assertNull(ready.copy(state = HealthMonitor.ConnectionState.DISCONNECTED).urlFor("saved"))
    }
}
