package com.budgetme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

// Made-up merchants and amounts only (public repo).
class FeedTest {
    private val from = LocalDate.parse("2026-11-01")  // bank connected Nov 2: older charges aren't added

    private fun txn(id: Long, cents: Long, date: String, account: String? = CARD_ACCOUNT, merchant: String = "Maple Cafe",
                    feedId: String? = null, status: String = "confirmed") =
        Txn(id = id, source = "alert", account = account, rawDescriptor = merchant, merchant = merchant, amountCents = cents,
            date = date, budgetMonth = date.take(7), categoryId = "fun", status = status, feedId = feedId)

    private fun feed(id: String, cents: Long, date: String, account: String = CARD_ACCOUNT, raw: String = "MAPLE CAFE",
                     category: String? = "FOOD_AND_DRINK", replaces: String? = null) =
        FeedTxn(id, account, cents, LocalDate.parse(date), cleanMerchant(raw), raw, false, category, replaces)

    @Test fun parsesTheBackendSyncReply() {
        val items = parseSync("""[
          {"id":"item_c","institution":"Chase","cursor":"c2",
           "accounts":[{"id":"acc_card","type":"credit","subtype":"credit card"}],
           "added":[{"id":"t1","account":"acc_card","amount":12.4,"date":"2026-11-02","merchant":"Maple Cafe","raw":"SQ *MAPLE CAFE",
                     "pending":true,"replaces":null,"category":"FOOD_AND_DRINK"}],
           "modified":[{"id":"t2","account":"acc_card","amount":-5.0,"date":"2026-11-03","merchant":null,"raw":"REFUND",
                     "pending":false,"replaces":"t0","category":null}],
           "removed":[]},
          {"id":"item_w","institution":"Wells Fargo","cursor":"w9",
           "accounts":[{"id":"acc_chk","type":"depository","subtype":"checking"},{"id":"acc_sav","type":"depository","subtype":"savings"}],
           "added":[{"id":"t3","account":"acc_sav","amount":100,"date":"2026-11-02","merchant":null,"raw":"TO SAVINGS","pending":false}],
           "modified":[],"removed":[]},
          {"id":"item_x","institution":"Old Bank","error":"ITEM_LOGIN_REQUIRED"}]""")
        val (c, w, x) = items
        assertEquals(listOf(CARD_ACCOUNT, CARD_ACCOUNT), c.txns.map { it.account })
        assertEquals(1240L, c.txns[0].cents); assertEquals("Maple Cafe", c.txns[0].merchant); assertTrue(c.txns[0].pending)
        assertEquals(-500L, c.txns[1].cents); assertEquals("Refund", c.txns[1].merchant); assertEquals("t0", c.txns[1].replaces)
        assertEquals("c2", c.cursor)
        assertTrue(w.txns.isEmpty())  // savings isn't tracked
        assertEquals("ITEM_LOGIN_REQUIRED", x.error)
    }

    @Test fun cleansBankDescriptionsIntoMerchantNames() {
        // Wells Fargo's formats with made-up merchants, references and card digits
        assertEquals("Lantern-12", bankName("Purchase Authorized On 10/26 Lantern-12 Ny S123456789012345 Card 1234"))
        assertEquals("Pixelbox.com/bill", bankName("Recurring Payment Authorized On 11/01 Pixelbox.com/bill Ca S123456789012345 Card 1234"))
        assertEquals("Streamly", bankName("PURCHASE Streamly 8005551234 TX CARD1234"))
        assertEquals("Gymco", bankName("Recurring Payment Authorized On 10/31 Gymco123 Gymco@mail Ny S123456789012345 Card 1234"))
        assertEquals("LANDLORD Management", bankName("LANDLORD Management WEB PMTS 110326 ABC1DE"))
        // clean names pass through: no state stripped outside Wells Fargo's format, "Purchase" kept when it's the name
        assertEquals("Maple Cafe", bankName("SQ *MAPLE CAFE"))
        assertEquals("Blue And Gold Bar In", bankName("Blue And Gold Bar In"))
        assertEquals("Purchase Street Pizza", bankName("Purchase Street Pizza"))
        assertTrue(listOf("Lantern-12", "Streamly").none { "1234" in it })
        // which existing names get renamed: raw descriptions only
        assertTrue(listOf("Purchase Authorized On 10/26 Lantern-12 Ny S123456789012345 Card 1234", "PURCHASE Streamly 8005551234 TX CARD1234",
            "LANDLORD Management WEB PMTS 110326 ABC1DE").all(::isBankDescription))
        assertTrue(listOf("Maple Cafe", "Blue And Gold Bar In", "Purchase Street Pizza", "Lantern-12", "Gymco").none(::isBankDescription))
    }

    @Test fun settlesTipsAndFillsAmountlessAlerts() {
        val alert = txn(1, 2000, "2026-11-02")
        val online = txn(2, 0, "2026-11-03", BANK_ACCOUNT, WF_ONLINE, status = "needs_review")
        val p = planFeed(listOf(feed("f1", 2400, "2026-11-03"), feed("f2", 3199, "2026-11-04", BANK_ACCOUNT, "ONLINE STORE", "GENERAL_MERCHANDISE")),
            listOf(alert, online), from)
        assertEquals(listOf(1L to "f1", 2L to "f2"), p.settle.map { (t, f) -> t.id to f.id })  // $20 + tip, amount-less alert
        assertTrue(p.add.isEmpty())
    }

    @Test fun amountlessAlertsTakeTheChargeWhoseMerchantFits() {
        // an amount-less alert named "Streamly": an earlier bike ride in the window must not be taken
        val alert = txn(1, 0, "2026-11-02", BANK_ACCOUNT, "Streamly", status = "needs_review")
        val p = planFeed(listOf(feed("bike", 310, "2026-11-01", BANK_ACCOUNT, "CITIBIK*RIDE", "TRANSPORTATION"),
            feed("sub", 750, "2026-11-02", BANK_ACCOUNT, "STREAMLY", "ENTERTAINMENT")), listOf(alert), from)
        assertEquals(1L to "sub", p.settle.single().let { (t, f) -> t.id to f.id }); assertEquals(listOf("bike"), p.add.map { it.id })
        assertTrue(nameFits("Citi Bike", cleanMerchant("CITIBIK*RIDE")))
        assertTrue(nameFits("Blue And Gold Bar In", cleanMerchant("TST*BLUE AND GOLD")))
        assertTrue(nameFits(WF_ONLINE, "Anything"))  // the generic placeholder names no merchant
        assertTrue(!nameFits("Streamly", "Citibik*ride"))
    }

    @Test fun tipsOnlyFromTheSameMerchant() {
        // an $11.00 grocery alert must not become another shop's $13.50 charge two days earlier (within the tip limit)
        val grocery = txn(1, 1100, "2026-11-04", merchant = "Corner Grocer")
        val p = planFeed(listOf(feed("garage", 1350, "2026-11-02", raw = "SQ *PARTS PLACE"), feed("groc", 1100, "2026-11-04", raw = "CORNER GROCER")),
            listOf(grocery), from)
        assertEquals(1L to "groc", p.settle.single().let { (t, f) -> t.id to f.id }); assertEquals(listOf("garage"), p.add.map { it.id })
        // an old bad link (linked to another shop's charge) is never settled again when that charge changes
        val misLinked = txn(2, 1350, "2026-11-02", merchant = "Corner Grocer", feedId = "garage")
        assertTrue(planFeed(listOf(feed("garage", 1600, "2026-11-02", raw = "SQ *PARTS PLACE")), listOf(misLinked), from).settle.isEmpty())
    }

    @Test fun findsCardPaymentsToRecord() {
        val since = LocalDate.parse("2026-11-03")
        fun pay(id: String, cents: Long, date: String, raw: String = "BIGBANK CREDIT CRD EPAY 261103 123456", pending: Boolean = false,
                replaces: String? = null, account: String = BANK_ACCOUNT) =
            FeedTxn(id, account, cents, LocalDate.parse(date), raw, raw, pending, "LOAN_PAYMENTS", replaces)
        val feed = listOf(
            pay("new", 52010, "2026-11-05"),                                   // records
            pay("old", 31000, "2026-11-02"),                                   // before the feature: recorded by hand
            pay("pend", 10000, "2026-11-05", pending = true),                  // waits until posted
            pay("done", 5000, "2026-11-04"),                                   // already handled
            pay("reposted", 6000, "2026-11-06", replaces = "done"),            // same payment, posted under a new id
            pay("byhand", 7000, "2026-11-06"),                                 // the user pressed the button for it
            pay("mortgage", 150000, "2026-11-05", raw = "HOMELOAN MORTGAGE PMT"), // not a card payment
            pay("cardside", 52010, "2026-11-05", account = CARD_ACCOUNT),      // the card's "payment thank you"
            feed("shop", 2500, "2026-11-05", BANK_ACCOUNT, "CARD SHOP NYC"),   // a purchase, not a payment
        )
        val manual = listOf(LocalDate.parse("2026-11-04") to 7000L)
        assertEquals(listOf("new"), cardPaymentsToRecord(feed, since, setOf("done"), manual).map { it.id })
    }

    @Test fun aPartialPaymentTicksTheOldestAndSplitsTheNext() {
        // a payment that matches no run of charges: the six oldest are ticked, the rest goes toward the seventh (made-up amounts)
        val unpaid = listOf(1000L, 2000L, 3000L, 4000L, 5000L, 9000L, 8800L, 2500L).mapIndexed { i, c -> CardItem("t${i + 1}", "2026-10-${10 + i}", "Shop $i", c) }
        val picked = pickPayment(unpaid, 30000)
        assertEquals(setOf("t1", "t2", "t3", "t4", "t5", "t6"), picked)
        val (item, part) = partialPayment(unpaid, picked, 30000)!!
        assertEquals("t7" to 6000L, item.key to part)  // $240 ticked + $60 toward the seventh
    }

    @Test fun addsChargesWithNoAlertAndSkipsTransfersAndCredits() {
        val p = planFeed(listOf(
            feed("sub", 1599, "2026-11-02", BANK_ACCOUNT, "STREAMCO MONTHLY", "ENTERTAINMENT"),
            feed("too_big", 5000, "2026-11-02"),                                              // 2.5x the alert: not a tip
            feed("pay", 30000, "2026-11-02", BANK_ACCOUNT, "CHASE CREDIT CRD EPAY", "LOAN_PAYMENTS"),
            feed("pay2", 30000, "2026-11-02", BANK_ACCOUNT, "CHASE CREDIT CRD EPAY", null),    // no category: wording catches it
            feed("xfer", 10000, "2026-11-02", BANK_ACCOUNT, "TO SAVINGS", "TRANSFER_OUT"),
            feed("refund", -800, "2026-11-02"),
            feed("old", 999, "2026-10-30", BANK_ACCOUNT, "OLD SHOP", "GENERAL_MERCHANDISE"),   // before connecting the bank
        ), listOf(txn(1, 2000, "2026-11-02")), from)
        assertEquals(listOf("sub", "too_big"), p.add.map { it.id })
        assertTrue(p.settle.isEmpty())
    }

    @Test fun exactAmountWinsAndDatesAndAccountsMustFit() {
        val near = txn(1, 2000, "2026-11-03"); val exact = txn(2, 2200, "2026-11-01")
        assertEquals(2L, planFeed(listOf(feed("f", 2200, "2026-11-03")), listOf(near, exact), from).settle.single().first.id)

        assertEquals(1, planFeed(listOf(feed("f", 2000, "2026-11-08")), listOf(txn(1, 2000, "2026-11-03")), from).add.size)  // 5 days
        assertEquals(1, planFeed(listOf(feed("f", 2000, "2026-11-03", BANK_ACCOUNT)), listOf(txn(1, 2000, "2026-11-03")), from).add.size)
        // a Wells Fargo debit can be a Venmo payment the Venmo alert already caught
        val venmo = txn(3, 4500, "2026-11-03", "Venmo", "Venmo")
        assertEquals(3L, planFeed(listOf(feed("v", 4500, "2026-11-04", BANK_ACCOUNT, "VENMO PAYMENT")), listOf(venmo), from).settle.single().first.id)
        // an older alert purchase still settles; only adding is limited to after connecting
        assertEquals(1, planFeed(listOf(feed("f", 2000, "2026-10-30")), listOf(txn(4, 2000, "2026-10-30")), from).settle.size)
    }

    @Test fun linkedPurchasesFollowThePostedVersion() {
        val linked = txn(1, 2000, "2026-11-03", feedId = "pend")
        assertTrue(planFeed(listOf(feed("pend", 2000, "2026-11-03")), listOf(linked), from).let { it.settle.isEmpty() && it.add.isEmpty() })
        assertEquals(2300L, planFeed(listOf(feed("pend", 2300, "2026-11-03")), listOf(linked), from).settle.single().second.cents)
        // posted under a new id: Plaid says which pending charge it replaces, so it relinks instead of being added twice
        val p = planFeed(listOf(feed("posted", 2450, "2026-11-04", replaces = "pend")), listOf(linked), from)
        assertEquals(1L to "posted", p.settle.single().let { (t, f) -> t.id to f.id }); assertTrue(p.add.isEmpty())
    }

    @Test fun cancelledChargesAreFound() {
        val alert = txn(1, 4200, "2026-11-04", account = "Wells Fargo", merchant = "Maple Cafe", feedId = "p1")
        val other = txn(2, 1500, "2026-11-04", account = "Wells Fargo", merchant = "Birch Books", feedId = "p2")
        fun item(txns: List<FeedTxn>, removed: List<String>) = FeedItem("i", "Wells Fargo", txns, "c", null, removed)
        // the bank dropped the pending charge and nothing replaced it
        assertEquals(listOf(alert), cancelledCharges(item(emptyList(), listOf("p1")), listOf(alert, other)))
        // dropped because it posted: the posted charge names it, so the purchase stays
        val posted = feed("s1", 4200, "2026-11-05", account = "Wells Fargo", replaces = "p1")
        assertEquals(emptyList<Txn>(), cancelledCharges(item(listOf(posted), listOf("p1")), listOf(alert, other)))
        // a Wells Fargo REVERSAL credit for the same amount and merchant
        val reversal = feed("r1", -4200, "2026-11-04", account = "Wells Fargo", raw = "REVERSAL MAPLE CAFE 5550100 NY CARD0000")
        assertEquals(listOf(alert), cancelledCharges(item(listOf(reversal), emptyList()), listOf(alert, other)))
        // a refund for a different amount, or a reversal of another shop, cancels nothing
        val refund = feed("r2", -1000, "2026-11-04", account = "Wells Fargo", raw = "REVERSAL MAPLE CAFE")
        val elsewhere = feed("r3", -1500, "2026-11-04", account = "Wells Fargo", raw = "REVERSAL PINE DELI")
        assertEquals(emptyList<Txn>(), cancelledCharges(item(listOf(refund, elsewhere), emptyList()), listOf(alert, other)))
        // an unlinked purchase is never cancelled
        assertEquals(emptyList<Txn>(), cancelledCharges(item(emptyList(), listOf("p9")), listOf(alert.copy(feedId = null))))
    }

    @Test fun parsesRemovedIds() {
        val items = parseSync("""[{"id":"item_w","institution":"Wells Fargo","cursor":"w2",
          "accounts":[{"id":"acc_chk","type":"depository","subtype":"checking"}],"added":[],"modified":[],"removed":["p1","p2"]}]""")
        assertEquals(listOf("p1", "p2"), items[0].removed)
    }
}
