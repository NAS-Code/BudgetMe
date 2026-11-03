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
}
