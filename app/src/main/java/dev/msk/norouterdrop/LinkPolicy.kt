package dev.msk.norouterdrop

/** Small pure rules for the Wi-Fi Direct link, kept apart so they can be unit tested. */
object LinkPolicy {
    /** Wait before the next discovery attempt after "busy" (code 2): 3, 6, 12, 24, then 30 s. [n] is how many busy answers in a row (1 = first). */
    fun busyBackoffMs(n: Int): Long = minOf(30_000L, 3_000L * (1L shl (maxOf(n, 1) - 1).coerceAtMost(10)))

    /** Tie-break when both phones may want to invite at the same moment: the phone with the smaller id invites at once, the other waits [TIEBREAK_WAIT_MS] and first watches for an incoming invite. */
    fun iInviteFirst(myId: String, peerId: String): Boolean = myId <= peerId

    const val TIEBREAK_WAIT_MS = 4_000L
    /** How long a send waits for the other phone to connect to a group that is already up. */
    const val GROUP_WAIT_MS = 20_000L
    /** How long a send waits while the other phone's invite is being negotiated. */
    const val INCOMING_WAIT_MS = 15_000L

    /** How long (ms since the send first had to wait) the send should keep waiting; 0 or less means go on and invite. */
    fun remainingWaitMs(groupUp: Boolean, incoming: Boolean, iInviteFirst: Boolean, waitedMs: Long): Long {
        val limit = when {
            groupUp -> GROUP_WAIT_MS
            incoming -> INCOMING_WAIT_MS
            !iInviteFirst -> TIEBREAK_WAIT_MS
            else -> 0L
        }
        return limit - waitedMs
    }
}

enum class LinkRole { NONE, OWNER, CLIENT }
enum class SendStep { OFFER, INVITE, WAIT, REOPEN_SOCKET }
data class SendAction(val step: SendStep, val waitMs: Long = 0, val why: String = "")

/** Decides what the send queue does next for the job at its head. Pure, so it can be simulated on the JVM. */
object SendPlanner {
    fun next(hasOpenLink: Boolean, groupUp: Boolean, role: LinkRole, incoming: Boolean, iInviteFirst: Boolean, waitedMs: Long): SendAction {
        if (hasOpenLink) return SendAction(SendStep.OFFER)
        val left = LinkPolicy.remainingWaitMs(groupUp, incoming, iInviteFirst, waitedMs)
        if (left <= 0) return SendAction(SendStep.INVITE)
        return when {
            // The group is still up but the socket is gone: a client reopens its socket to the owner at once; an owner waits for the client.
            groupUp && role == LinkRole.CLIENT -> SendAction(SendStep.REOPEN_SOCKET, minOf(left, 1_500L), "a group is already up, reopening the channel to the other phone")
            groupUp -> SendAction(SendStep.WAIT, minOf(left, 1_500L), "a group is already up, waiting for the other phone to connect to it")
            incoming -> SendAction(SendStep.WAIT, minOf(left, 1_500L), "the other phone is connecting to this one")
            else -> SendAction(SendStep.WAIT, minOf(left, 1_500L), "short pause so only one phone invites")
        }
    }
}

/** Keeps one live link per trusted phone: when a trusted phone opens a second channel, the older one is closed and the new one kept. */
object LinkRegistry {
    fun <T> olderToClose(sessions: List<T>, newest: T, samePeer: (T) -> Boolean, isOpen: (T) -> Boolean): List<T> =
        sessions.filter { it !== newest && samePeer(it) && isOpen(it) }
}
