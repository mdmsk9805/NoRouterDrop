package dev.msk.norouterdrop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PairingSessionTest {
    @Test fun bothSidesGetSameSecretAndCode() {
        val a = PairingSession(); val b = PairingSession()
        val ra = a.finish(b.publicKeyEncoded); val rb = b.finish(a.publicKeyEncoded)
        assertNotNull(ra); assertNotNull(rb)
        assertArrayEquals(ra!!.first, rb!!.first)
        assertEquals(ra.second, rb.second)
        assertEquals(6, ra.second.length)
        assertEquals(32, a.publicKeyEncoded.size)
    }
    @Test fun badPeerKeyIsRejected() {
        org.junit.Assert.assertNull(PairingSession().finish(ByteArray(5)))
    }
}
