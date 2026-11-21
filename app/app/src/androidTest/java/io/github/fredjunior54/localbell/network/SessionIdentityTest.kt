package io.github.fredjunior54.localbell.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionIdentityTest {
    @Test
    fun reloadsTheSameKeystoreIdentity() {
        val first = SessionTls.load().fingerprint()
        assertEquals(32, first.size)
        assertArrayEquals(first, SessionTls.load().fingerprint())
    }
}
