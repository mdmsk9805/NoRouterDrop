package dev.msk.norouterdrop

import org.junit.Assert.*
import org.junit.Test

class LinkPolicyTest {
    @Test fun backoffGrowsAndCaps() {
        assertEquals(3_000L, LinkPolicy.busyBackoffMs(1))
        assertEquals(6_000L, LinkPolicy.busyBackoffMs(2))
        assertEquals(24_000L, LinkPolicy.busyBackoffMs(4))
        assertEquals(30_000L, LinkPolicy.busyBackoffMs(5))
        assertEquals(30_000L, LinkPolicy.busyBackoffMs(50))
        assertEquals(3_000L, LinkPolicy.busyBackoffMs(0))
    }
    @Test fun exactlyOnePhoneInvitesFirst() {
        assertTrue(LinkPolicy.iInviteFirst("aaa", "bbb"))
        assertFalse(LinkPolicy.iInviteFirst("bbb", "aaa"))
    }
    @Test fun waitsForExistingGroupAndIncomingInvite() {
        assertTrue(LinkPolicy.remainingWaitMs(true, false, true, 5_000) > 0)
        assertTrue(LinkPolicy.remainingWaitMs(true, false, true, 21_000) <= 0)
        assertTrue(LinkPolicy.remainingWaitMs(false, true, true, 1_000) > 0)
        assertTrue(LinkPolicy.remainingWaitMs(false, true, true, 16_000) <= 0)
    }
    @Test fun smallIdInvitesAtOnceLargeIdWaits() {
        assertTrue(LinkPolicy.remainingWaitMs(false, false, true, 0) <= 0)
        assertTrue(LinkPolicy.remainingWaitMs(false, false, false, 0) > 0)
        assertTrue(LinkPolicy.remainingWaitMs(false, false, false, 4_500) <= 0)
    }
}
