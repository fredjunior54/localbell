package io.github.fredjunior54.localbell.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrCodeParserTest {
    private val fingerprint = "a".repeat(64)
    private val code = "b".repeat(64)

    @Test
    fun parsesPairingCode() {
        assertEquals(
            PairingCode("https://192.168.1.2:19420", fingerprint, code),
            QrCodeParser.parse("localbell:1|192.168.1.2|19420|$fingerprint|$code"),
        )
    }

    @Test
    fun rejectsLegacyPublicAndMalformedCodes() {
        listOf(
                "$code|$fingerprint",
                "localbell:2|192.168.1.2|19420|$fingerprint|$code",
                "localbell:1|8.8.8.8|19420|$fingerprint|$code",
                "localbell:1|127.0.0.1|19420|$fingerprint|$code",
                "localbell:1|192.168.1.2|0|$fingerprint|$code",
                "localbell:1|192.168.1.2|19420|bad|$code",
            )
            .forEach { assertNull(QrCodeParser.parse(it)) }
    }
}
