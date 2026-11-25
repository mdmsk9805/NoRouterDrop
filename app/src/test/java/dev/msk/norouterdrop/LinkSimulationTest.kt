package dev.msk.norouterdrop

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

/**
 * A MODEL of two phones and the radio, driven by the real decision code (SendPlanner, LinkPolicy, LinkRegistry) on a virtual clock.
 * It checks the logic of the send queue, tie-break, channel reopen and group handling. It is NOT the Android Wi-Fi Direct stack:
 * invite timing, collisions, "busy" and "peer not visible" are assumptions written into this model, not measurements.
 */
class LinkSimulationTest {
    private class P(val id: String) {
        var queued = 0; var delivered = 0; var dropped = 0
        var busyUntil = 0L; var waitStart = -1L; var retryAt = 0L; var tries = 0
        var inviteEnd = -1L; var collided = false; var reopenAt = -1L; var cantSeeUntil = 0L
        val negotiating get() = inviteEnd >= 0
    }
    private class Result(val a: P, val b: P, val finishedAt: Long, val allDone: Boolean)

    private class Sim(val legacy: Boolean, seed: Long) {
        val rnd = Random(seed)
        val a = P("aaa"); val b = P("bbb")      // a has the smaller id, so a invites first
        var t = 0L; var groupUp = false; var owner: P? = null; var link = false
        fun other(p: P) = if (p === a) b else a
        fun role(p: P) = if (!groupUp) LinkRole.NONE else if (owner === p) LinkRole.OWNER else LinkRole.CLIENT
        fun dropLink() { link = false; if (!legacy && groupUp) { val c = if (owner === a) b else a; c.reopenAt = t + 1500 } }
        fun dropGroup() { groupUp = false; owner = null; link = false; a.reopenAt = -1; b.reopenAt = -1 }

        fun step(p: P) {
            val o = other(p)
            if (p.negotiating) {
                if (t >= p.inviteEnd) {
                    p.inviteEnd = -1
                    if (p.collided) { p.collided = false; p.tries++; p.retryAt = t + 3000 + rnd.nextInt(4000); dropGroup(); failIfTooMany(p) }
                    else { groupUp = true; owner = p; o.reopenAt = t + 1000; p.tries = 0 }
                }
                return
            }
            if (p.queued <= 0 || t < p.busyUntil || t < p.retryAt) return
            val iFirst = LinkPolicy.iInviteFirst(p.id, o.id)
            val step: SendStep
            if (legacy) {
                step = if (link) SendStep.OFFER else if (groupUp && (t - maxOf(p.waitStart, 0L)) < 20_000) SendStep.WAIT else SendStep.INVITE
                if (step == SendStep.WAIT && p.waitStart < 0) p.waitStart = t
            } else {
                if (p.waitStart < 0) p.waitStart = t
                val act = SendPlanner.next(link, groupUp, role(p), o.negotiating, iFirst, t - p.waitStart)
                step = act.step
                if (step == SendStep.REOPEN_SOCKET && p.reopenAt < 0 && role(p) == LinkRole.CLIENT) p.reopenAt = t + 500
            }
            when (step) {
                SendStep.OFFER -> { p.busyUntil = t + 1000; p.queued--; o.delivered++; p.waitStart = -1 }
                SendStep.INVITE -> {
                    p.waitStart = -1
                    if (groupUp) dropGroup()                       // teardown of the existing group
                    if (t < p.cantSeeUntil) { p.tries++; p.retryAt = t + 20_000; failIfTooMany(p); return }
                    p.inviteEnd = t + 8000 + rnd.nextInt(2000)
                    if (o.negotiating) { p.collided = true; o.collided = true }   // both invite at once: both fail
                }
                else -> { }
            }
        }
        fun failIfTooMany(p: P) { if (p.tries >= 3) { p.queued--; p.dropped++; p.tries = 0 } }

        fun tick() {
            // a client whose channel was dropped reopens it (fixed build only); the first open after a group forms always happens
            for (p in listOf(a, b)) if (p.reopenAt in 0..t && groupUp && !link && role(p) == LinkRole.CLIENT) { link = true; p.reopenAt = -1 }
            if (rnd.nextBoolean()) { step(a); step(b) } else { step(b); step(a) }
            t += 100
        }
        fun runUntil(limit: Long, events: Map<Long, () -> Unit> = emptyMap(), done: () -> Boolean): Result {
            val ev = events.toSortedMap()
            while (t < limit) {
                for ((k, v) in ev.entries.toList()) if (k <= t) { v(); ev.remove(k) }
                tick()
                if (ev.isEmpty() && done()) return Result(a, b, t, true)
            }
            return Result(a, b, t, false)
        }
    }

    private fun scenario(name: String, legacy: Boolean, seed: Long): Result {
        val s = Sim(legacy, seed)
        val ev = HashMap<Long, () -> Unit>()
        val all = { s.a.queued == 0 && s.b.queued == 0 && s.t > s.a.busyUntil && s.t > s.b.busyUntil }
        when (name) {
            "A2B" -> s.a.queued = 3
            "B2A" -> s.b.queued = 3
            "both at once" -> { s.a.queued = 2; ev[s.rnd.nextInt(300).toLong()] = { s.b.queued = 2 } }
            "B2A, honor cannot see samsung for 30 s" -> { s.b.queued = 2; s.b.cantSeeUntil = 30_000 }
            "send, channel drops (group stays), client sends, again owner sends" -> {
                s.a.queued = 1
                ev[20_000] = { s.dropLink(); s.b.queued = 2 }
                ev[60_000] = { s.dropLink(); s.a.queued = 2 }
            }
            "send, group and channel drop, client sends, again owner sends" -> {
                s.a.queued = 1
                ev[20_000] = { s.dropGroup(); s.b.queued = 2 }
                ev[80_000] = { s.dropGroup(); s.a.queued = 2 }
            }
            "channel drops right after DONE, both queue next files" -> {
                s.a.queued = 1
                ev[12_000] = { s.dropLink(); s.a.queued = 1; s.b.queued = 1 }
            }
        }
        return s.runUntil(240_000, ev) { all() }
    }

    private val names = listOf("A2B", "B2A", "both at once", "B2A, honor cannot see samsung for 30 s",
        "send, channel drops (group stays), client sends, again owner sends",
        "send, group and channel drop, client sends, again owner sends",
        "channel drops right after DONE, both queue next files")
    private val expected = mapOf(
        "A2B" to (3 to 0), "B2A" to (0 to 3), "both at once" to (2 to 2), "B2A, honor cannot see samsung for 30 s" to (0 to 2),
        names[4] to (3 to 2), names[5] to (3 to 2), names[6] to (2 to 1))

    @Test fun everyScenarioDeliversEveryFileInBothDirections_runTwice() {
        for (run in 1..2) for (seed in listOf(11L * run, 101L * run)) for (n in names) {
            val r = scenario(n, false, seed)
            println("SIM fixed run=$run seed=$seed [$n] finished=${r.finishedAt}ms done=${r.allDone}")
            val (ab, ba) = expected.getValue(n)
            // a.delivered counts files that went b->a; b.delivered counts a->b
            assertTrue("$n (run $run, seed $seed) not finished at ${r.finishedAt} ms", r.allDone)
            assertEquals("$n a->b", ab, r.b.delivered)
            assertEquals("$n b->a", ba, r.a.delivered)
            assertEquals("$n dropped files", 0, r.a.dropped + r.b.dropped)
            assertTrue("$n took ${r.finishedAt} ms", r.finishedAt <= 150_000)
        }
    }

    @Test fun controlTheOldLogicFailsOrIsMuchSlowerAfterAChannelDrop() {
        // Negative control: with the old rules (tear the group down after 20 s, client never reopens) the model reproduces the reported bug.
        val n = names[4]
        for (seed in listOf(11L, 101L)) {
            val fixed = scenario(n, false, seed)
            val old = scenario(n, true, seed)
            println("SIM control seed=$seed old: finished=${old.finishedAt} done=${old.allDone} dropped=${old.a.dropped + old.b.dropped}; fixed: finished=${fixed.finishedAt}")
            assertTrue(fixed.allDone)
            assertTrue("old logic should be slower or lose files (old=${old.finishedAt}, fixed=${fixed.finishedAt}, dropped=${old.a.dropped + old.b.dropped})",
                !old.allDone || old.a.dropped + old.b.dropped > 0 || old.finishedAt >= fixed.finishedAt + 15_000)
        }
        // And the old rules collide when both phones send at once.
        var collisionsHurt = 0
        for (seed in 1L..10L) { val old = scenario("both at once", true, seed); val fx = scenario("both at once", false, seed); if (old.finishedAt > fx.finishedAt) collisionsHurt++ }
        assertTrue("old logic should be slower in most seeds, was in $collisionsHurt of 10", collisionsHurt >= 6)
    }

    @Test fun plannerDecisions() {
        // channel gone, group up: client reopens, owner waits, both give up after 20 s and invite
        assertEquals(SendStep.REOPEN_SOCKET, SendPlanner.next(false, true, LinkRole.CLIENT, false, false, 0).step)
        assertEquals(SendStep.WAIT, SendPlanner.next(false, true, LinkRole.OWNER, false, true, 0).step)
        assertEquals(SendStep.INVITE, SendPlanner.next(false, true, LinkRole.OWNER, false, true, 20_001).step)
        assertEquals(SendStep.OFFER, SendPlanner.next(true, true, LinkRole.OWNER, false, true, 0).step)
        // nothing up: smaller id invites at once, larger waits and yields to an incoming invite
        assertEquals(SendStep.INVITE, SendPlanner.next(false, false, LinkRole.NONE, false, true, 0).step)
        assertEquals(SendStep.WAIT, SendPlanner.next(false, false, LinkRole.NONE, false, false, 0).step)
        assertEquals(SendStep.WAIT, SendPlanner.next(false, false, LinkRole.NONE, true, false, 3_000).step)
    }

    @Test fun secondChannelFromTheSamePhoneReplacesTheOlderOne() {
        class S(val peer: String, var open: Boolean)
        val old = S("honor", true); val other = S("tablet", true); val newer = S("honor", true); val closed = S("honor", false)
        val toClose = LinkRegistry.olderToClose(listOf(old, other, newer, closed), newer, { it.peer == "honor" }, { it.open })
        assertEquals(listOf(old), toClose)
    }

    @Test fun busyBackoffNeverExceedsThirtySeconds() {
        var total = 0L
        for (i in 1..20) { val w = LinkPolicy.busyBackoffMs(i); assertTrue(w in 3_000..30_000); total += w }
        assertTrue(total > 0)
    }
}
