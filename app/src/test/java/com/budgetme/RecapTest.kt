package com.budgetme

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

// The Monday recap's monthly budget lines. Made-up amounts (public repo).
class RecapTest {
    private val day9 = LocalDate.of(2026, 11, 9)  // 9 of 30 days: an even pace on a $500 budget is $150

    @Test fun fasterThanPaceSaysHowMuchAndWhatsLeft() =
        assertEquals("$240.00 of $500.00 · $90.00 faster than an even pace, $260.00 left for 21 days", paceText(24000, 50000, day9))

    @Test fun wellUnderIsUnderNotOnPace() =
        assertEquals("$20.00 of $150.00 · $25.00 under an even pace", paceText(2000, 15000, day9))

    @Test fun within5PercentIsOnPace() {
        assertEquals("$170.00 of $500.00 · on pace", paceText(17000, 50000, day9))   // $20 over even, within $25
        assertEquals("$130.00 of $500.00 · on pace", paceText(13000, 50000, day9))
    }

    @Test fun pastTheBudget() = assertEquals("$260.00 of $250.00 · $10.00 over budget", paceText(26000, 25000, day9))

    @Test fun lastDay() = assertEquals("$300.00 of $500.00 · $200.00 under an even pace", paceText(30000, 50000, LocalDate.of(2026, 11, 30)))
}
