package com.budgetme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeTest {
    private val min = 60_000L
    private val t0 = 1_800_000_000_000L

    @Test fun observeKeepsTheFirstSightingAndThePosting() {
        var e = ProbeEntry(CARD_ACCOUNT, t0)
        e = observe(e, linked = false, pending = null, now = t0 + 1 * min)
        assertEquals(null, e.seenAt)
        e = observe(e, linked = true, pending = true, now = t0 + 3 * min)
        e = observe(e, linked = true, pending = true, now = t0 + 5 * min)   // seen again: the first sighting stays
        assertEquals(t0 + 3 * min, e.seenAt); assertEquals(true, e.seenPending); assertEquals(null, e.postedAt)
        e = observe(e, linked = true, pending = false, now = t0 + 30 * 60 * min)
        assertEquals(t0 + 30 * 60 * min, e.postedAt)
    }

    @Test fun reportsDelaysPerAccount() {
        val now = t0 + 48 * 60 * min
        val es = listOf(
            ProbeEntry(CARD_ACCOUNT, t0, t0 + 2 * min, true, t0 + 30 * 60 * min),
            ProbeEntry(CARD_ACCOUNT, t0, t0 + 4 * min, true),
            ProbeEntry(CARD_ACCOUNT, t0, t0 + 90 * min, false),             // not within the hour
            ProbeEntry(BANK_ACCOUNT, t0),                                   // never seen
            ProbeEntry(BANK_ACCOUNT, now - 10 * min),                       // first hour not over: doesn't count yet
        )
        val r = probeReport(es, now).lines()
        assertEquals("Chase Credit Card: 3 alerts, 2/3 seen within 1 h, first seen after 4 min median, 4 min 80%, 90 min max, " +
            "2/3 first seen pending, posted after 30.0 h median", r[0])
        assertEquals("Wells Fargo: 2 alerts, 0/1 seen within 1 h", r[1])
        assertTrue(probeReport(emptyList(), now) == "no alerts yet")
    }
}
