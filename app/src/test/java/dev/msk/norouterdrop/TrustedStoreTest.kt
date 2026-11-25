package dev.msk.norouterdrop

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrustedStoreTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun peersSurviveARestart() {
        TrustedStore(ctx).add(TrustedPeer("p1", "Phone B", "aa:bb:cc", "wrappedkey"))
        val again = TrustedStore(ctx)           // new instance reads from disk
        val p = again.find("p1")
        assertNotNull(p)
        assertEquals("Phone B", p!!.name)
        assertEquals("wrappedkey", p.keyB64)
        assertTrue(again.isTrusted("aa:bb:cc"))  // lookup by address works too
    }

    @Test fun addReplacesSameId() {
        val s = TrustedStore(ctx)
        s.add(TrustedPeer("p1", "Old", "", "k1"))
        s.add(TrustedPeer("p1", "New", "", "k2"))
        assertEquals(1, TrustedStore(ctx).all().size)
        assertEquals("k2", TrustedStore(ctx).find("p1")!!.keyB64)
    }

    @Test fun updateAndRemovePersist() {
        val s = TrustedStore(ctx)
        s.add(TrustedPeer("p2", "Name", "", "k"))
        s.update("p2", "Renamed", "11:22")
        assertEquals("Renamed", TrustedStore(ctx).find("p2")!!.name)
        assertEquals("11:22", TrustedStore(ctx).find("p2")!!.address)
        s.update("p2", "", null)                 // blank name keeps the old one
        assertEquals("Renamed", TrustedStore(ctx).find("p2")!!.name)
        s.remove("p2")
        assertNull(TrustedStore(ctx).find("p2"))
        assertFalse(TrustedStore(ctx).isTrusted("p2"))
    }

    @Test fun unknownPeerIsNotTrusted() {
        assertFalse(TrustedStore(ctx).isTrusted("never-paired"))
    }

    @Test fun localIdIsStable() {
        val a = TrustedStore.localId(ctx)
        assertEquals(a, TrustedStore.localId(ctx))
        assertTrue(a.length >= 32)
    }
}
