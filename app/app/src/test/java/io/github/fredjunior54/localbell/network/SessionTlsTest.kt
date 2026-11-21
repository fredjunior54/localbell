package io.github.fredjunior54.localbell.network

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTlsTest {
    @Test
    fun `restricts the listener to local and overlay IPv4 peers`() {
        val allowed =
            listOf(
                "10.0.0.1",
                "172.16.0.1",
                "172.31.0.1",
                "192.168.0.1",
                "169.254.1.1",
                "100.64.0.1",
                "100.127.1.1",
            )
        val blocked =
            listOf(
                "172.15.0.1",
                "172.32.0.1",
                "100.63.0.1",
                "100.128.0.1",
                "127.0.0.1",
                "8.8.8.8",
                "::1",
            )
        allowed.forEach { assertTrue(it, SessionTls.permitsPeer(InetAddress.getByName(it))) }
        blocked.forEach { assertFalse(it, SessionTls.permitsPeer(InetAddress.getByName(it))) }
    }
}
