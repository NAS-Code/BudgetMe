package com.budgetme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// ponytail: these wordings are GUESSES until real samples arrive (ROADMAP step 2). Replace them with
// copied text from Settings > Captured alerts, one block per bank.
class AlertParseTest {
    @Test fun chase() = assertEquals(ParsedAlert("Blank Street", 2340),
        parseAlert("com.chase.sig.android", "Chase Freedom", "You made a \$23.40 transaction with BLANK STREET on Sep 30, 2026 at 8:15 AM ET."))

    @Test fun processorPrefix() = assertEquals(ParsedAlert("Corner Coffee New York", 1244),
        parseAlert("com.chase.sig.android", "Chase", "You made a \$12.44 transaction with SQ *CORNER COFFEE NEW YORK on Sep 30"))

    @Test fun wellsFargo() = assertEquals(ParsedAlert("Foodtown", 1999),
        parseAlert("com.wf.wellsfargomobile", "Wells Fargo", "Purchase of \$19.99 at FOODTOWN #123 using card ending 1234."))

    @Test fun venmo() = assertEquals(ParsedAlert("Venmo", 2000, "Alex Kim: pizza"),
        parseAlert("com.venmo", "Venmo", "You paid Alex Kim \$20.00 - pizza"))

    @Test fun ignoresIncomingAndNoise() {
        assertNull(parseAlert("com.venmo", "Venmo", "Alex Kim paid you \$20.00"))
        assertNull(parseAlert("com.wf.wellsfargomobile", "Wells Fargo", "Your statement is ready. Balance \$1,204.33"))
    }
}
