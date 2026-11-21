package io.github.fredjunior54.localbell.network

import io.github.fredjunior54.localbell.util.LanEndpoint
import java.net.InetAddress
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PairingProtocolTest {
    @Test
    fun comparisonVectorBindsLeaf() {
        val expected = "eagle creek iris rose pine moon reed mint grove basil"
        val code = "1".repeat(64)
        val nonce = "2".repeat(64)
        assertEquals(expected, PairingClient.phrase("0".repeat(64), code, nonce))
        assertNotEquals(expected, PairingClient.phrase("3".repeat(64), code, nonce))
    }

    @Test
    fun onlyPrivateAndOverlayLiteralsAreAllowed() {
        for (host in listOf("100.64.0.0", "100.127.255.255", "192.168.1.3")) {
            assertNotNull(LanEndpoint.parse(host))
        }
        for (host in
            listOf(
                "100.63.255.255",
                "100.128.0.0",
                "8.8.8.8",
                "https://host",
                "host/path",
                "x@y",
                "host:0",
            )) {
            assertNull(LanEndpoint.parse(host))
        }
        assertEquals(12345, LanEndpoint.parse("receiver.example.ts.net:12345")?.port)
    }

    @Test(expected = UnknownHostException::class)
    fun rejectsMixedPrivateAndPublicDnsAnswers() {
        LanEndpoint.validatedIpv4(
            listOf(InetAddress.getByName("100.64.0.10"), InetAddress.getByName("8.8.8.8")),
        )
    }
}
