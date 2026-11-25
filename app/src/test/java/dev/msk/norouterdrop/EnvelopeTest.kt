package dev.msk.norouterdrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnvelopeTest {
    @Test fun roundTripKeepsEveryField() {
        val e = Envelope(Envelope.Kind.OFFER, "id-1", "Phone A", transferId = "t1", fileName = "a b.png",
            fileSize = 1234567890123L, sha256Hex = "ab".repeat(32), text = "hi \"there\"\n", address = "aa:bb", seq = 7, timestamp = 42L)
        val back = Envelope.parse(e.toBytes())
        assertNotNull(back)
        assertEquals(e, back)
    }

    @Test fun everyKindSurvivesTheWire() {
        for (k in Envelope.Kind.values()) {
            assertEquals(k, Envelope.parse(Envelope(k, "x", "y").toBytes())?.kind)
        }
    }

    @Test fun missingOptionalFieldsGetDefaults() {
        val b = """{"kind":"HELLO","fromId":"abc"}""".toByteArray()
        val e = Envelope.parse(b)!!
        assertEquals("abc", e.fromId)
        assertEquals("?", e.fromName)
        assertEquals(0L, e.fileSize)
    }

    @Test fun garbageIsRejected() {
        assertNull(Envelope.parse("not json".toByteArray()))
        assertNull(Envelope.parse("""{"kind":"NOPE","fromId":"a"}""".toByteArray()))
        assertNull(Envelope.parse("""{"fromId":"a"}""".toByteArray()))
        assertNull(Envelope.parse(ByteArray(0)))
    }

    @Test fun wireFramingRoundTrip() {
        val payload = "hello".toByteArray()
        val framed = Wire.frame(payload)
        val back = Wire.readFrame(java.io.DataInputStream(java.io.ByteArrayInputStream(framed)))
        assertEquals("hello", String(back!!))
    }

    @Test fun wireRejectsBadLengthAndReportsCleanClose() {
        val bad = java.nio.ByteBuffer.allocate(4).putInt(0).array()
        try { Wire.readFrame(java.io.DataInputStream(java.io.ByteArrayInputStream(bad))); throw AssertionError("expected failure") }
        catch (_: IllegalArgumentException) { }
        assertNull(Wire.readFrame(java.io.DataInputStream(java.io.ByteArrayInputStream(ByteArray(0)))))
    }
}
