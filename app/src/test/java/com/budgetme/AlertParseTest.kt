package com.budgetme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Real alert wordings (captured Oct 2026) with merchants, amounts and card digits swapped for fake ones.
// The repo is public: anonymize any new sample the same way before adding it.
class AlertParseTest {
    private val chaseTitle = "You have received a notification"

    @Test fun chase() = assertEquals(ParsedAlert("Corner Coffee Marke", 2340),
        parseAlert("com.chase.sig.android", chaseTitle,
            "Chase Sapphire Preferred Visa: You made a \$23.40 transaction with CORNER COFFEE MARKE on Oct 1, 2026 at 9:30 PM ET."))

    @Test fun chaseProcessorPrefix() = assertEquals(ParsedAlert("Blank Street", 125000),
        parseAlert("com.chase.sig.android", chaseTitle,
            "Chase Freedom Visa: You made a \$1,250.00 transaction with SQ *BLANK STREET on Sep 3, 2026 at 8:05 AM ET."))

    @Test fun wellsFargoWithAmount() = assertEquals(ParsedAlert(WF_CARD, 785),
        parseAlert("com.wf.wellsfargomobile", "Wells Fargo", "Your Debit Card ending in 00 was used to make a purchase of 7.85USD."))

    @Test fun wellsFargoOnline() = assertEquals(ParsedAlert(WF_ONLINE, 0),
        parseAlert("com.wf.wellsfargomobile", "Wells Fargo", "Your Debit Card ending in 00 was used to make a purchase online, by phone, or mail.\n"))

    @Test fun ignoresNonPurchases() {
        assertNull(parseAlert("com.wf.wellsfargomobile", "Wells Fargo", "You received \$1.00 with Zelle(R). We deposited it into your acct 0000."))
        assertNull(parseAlert("com.chase.sig.android", chaseTitle,
            "You successfully validated your device and signed in. Call us or use the app to manage your devices if this wasn't you."))
    }

    // Guessed: no real Venmo samples yet.
    @Test fun venmo() = assertEquals(ParsedAlert("Venmo", 2000, "Alex Kim: pizza"),
        parseAlert("com.venmo", "Venmo", "You paid Alex Kim \$20.00 - pizza"))

    @Test fun venmoIncoming() = assertNull(parseAlert("com.venmo", "Venmo", "Alex Kim paid you \$20.00"))
}
