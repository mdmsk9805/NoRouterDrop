package dev.msk.norouterdrop

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryStoreTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    @Before fun clean() { HistoryStore.clear(ctx) }

    @Test fun newestFirstAndCapped() {
        for (i in 1..105) HistoryStore.add(ctx, HistoryEntry(i.toLong(), i % 2 == 0, "f$i", true, "p"))
        val all = HistoryStore.all(ctx)
        assertEquals(HistoryStore.MAX, all.size)
        assertEquals("f105", all.first().name)
        assertEquals("f6", all.last().name)
    }
    @Test fun failureAndDirectionKept() {
        HistoryStore.add(ctx, HistoryEntry(5, false, "a.jpg", false, "Honor"))
        val e = HistoryStore.all(ctx).single()
        assertFalse(e.ok); assertFalse(e.incoming); assertTrue(e.line().contains("(failed)"))
    }
    @Test fun canBeSwitchedOff() {
        ctx.getSharedPreferences("norouterdrop_ui", 0).edit().putBoolean("history_on", false).apply()
        HistoryStore.add(ctx, HistoryEntry(1, true, "x", true, "p"))
        assertTrue(HistoryStore.all(ctx).isEmpty())
        ctx.getSharedPreferences("norouterdrop_ui", 0).edit().putBoolean("history_on", true).apply()
    }
    @Test fun clearEmpties() {
        HistoryStore.add(ctx, HistoryEntry(1, true, "x", true, "p")); HistoryStore.clear(ctx)
        assertTrue(HistoryStore.all(ctx).isEmpty())
    }
}
