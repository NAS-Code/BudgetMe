package com.budgetme

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.launch

/** A bank app the listener can read. tested = parser written from real alerts; others use the generic fallback. */
data class Bank(val label: String, val tested: Boolean)

// ponytail: the untested package names are best-known values, not verified on a device; the generic parser
// handles common wordings and every alert is logged raw (Settings > Captured alerts) to write a real parser.
val BANKS = linkedMapOf(
    "com.chase.sig.android" to Bank("Chase Credit Card", true),
    "com.wf.wellsfargomobile" to Bank("Wells Fargo", true),
    "com.venmo" to Bank("Venmo", false),
    "com.infonow.bofa" to Bank("Bank of America", false),
    "com.konylabs.capitalone" to Bank("Capital One", false),
    "com.citi.citimobile" to Bank("Citi", false),
    "com.americanexpress.android.acctsvcs.us" to Bank("American Express", false),
    "com.discoverfinancial.mobile" to Bank("Discover", false),
    "com.usaa.mobile.android.usaa" to Bank("USAA", false),
    "com.squareup.cash" to Bank("Cash App", false),
    "com.paypal.android.p2pmobile" to Bank("PayPal", false),
)

class AlertListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val account = BANKS[sbn.packageName]?.label ?: return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val ctx = applicationContext
        Brain.scope.launch { Brain.onAlert(ctx, sbn.packageName, account, title, text, sbn.postTime) }
    }
}

/** cents = 0 means the alert didn't say (Wells Fargo online purchases); the bank sync fills it in later. */
data class ParsedAlert(val merchant: String, val cents: Long, val note: String? = null)

/** Stand-in names for alerts that don't name the merchant. Never aliased: the user's correction is per purchase. */
const val WF_CARD = "Wells Fargo card purchase"
const val WF_ONLINE = "Wells Fargo online purchase"
val PLACEHOLDER_MERCHANTS = setOf(WF_CARD, WF_ONLINE)

// Real wordings captured Oct 2026 (anonymized copies in AlertParseTest).
private val CHASE = Regex("""You made an? \$([0-9,]+\.[0-9]{2}) transaction with (.+?) on [A-Z][a-z]{2} \d""")
private val WF_AMOUNT = Regex("""(?i)card ending in \d+ was used to make a purchase of \$?([0-9,]+\.[0-9]{2}) ?USD""")
private val WF_NO_AMOUNT = Regex("""(?i)card ending in \d+ was used to make a purchase online, by phone, or mail""")

// Fallback for wordings not seen yet (Venmo, other Chase/WF alert types).
private val AMOUNT = Regex("""\$\s?([0-9][0-9,]*\.[0-9]{2})""")
private val NOT_SPENDING = Regex("""(?i)paid you|sent you|deposit|received|credited|refund|payment (?:posted|received|scheduled)|balance|statement|password|verification|security code|sign.?in|signed in|autopay""")
private val VENMO = Regex("""(?i)you paid (.+?)\s+\$[0-9,]+\.[0-9]{2}\s*(?:[-:–—]\s*|for\s+)?(.*)""")
private val MERCHANT = Regex("""(?i)\b(?:transaction with|purchase (?:at|with)|charged at|spent at|at|with)\s+(.+?)(?=\s+on\s|\s+using\s|\s+with (?:your )?(?:card|debit|credit)|\s+was\s|\s+has\s|\s+exceeded|\s+for\s+\$|\.\s|\.?$|,)""")
private val PROCESSOR = Regex("""(?i)^(?:sq|tst|sp|py|pp|dd|cke|paypal|pypl|goog|google|apl|in)\s?\*\s*""")

private fun cents(s: String) = dollarsToCents(s.replace(",", ""))
private fun clean(text: String) = text.replace('\u00A0', ' ').replace(Regex("""\s+"""), " ").trim()  // Wells Fargo uses non-breaking spaces

/** A Wells Fargo alert about money in the account rather than a purchase. */
sealed interface BankAlert
data class DepositAlert(val cents: Long, val kind: String) : BankAlert   // paycheck | deposit | zelle
data class BalanceAlert(val cents: Long) : BankAlert                     // the account's ending balance (end of the previous day)

// Real wordings captured Oct 2026 (anonymized copies in AlertParseTest).
private val WF_PAID = Regex("""(?i)you just got paid!?\s*\$([0-9,]+\.[0-9]{2}) is now in your account""")
private val WF_DEPOSIT = Regex("""(?i)a deposit of \$([0-9,]+\.[0-9]{2}) is now in (?:your )?account""")
private val WF_ZELLE_IN = Regex("""(?i)you received \$([0-9,]+\.[0-9]{2}) with zelle""")
private val WF_BALANCE = Regex("""(?i)ending balance for account \S+ is (-)?\$(-)?([0-9,]+\.[0-9]{2})""")
private val VENMO_SENT = Regex("""(?i)your payment of \$([0-9,]+\.[0-9]{2}) was sent to (.+?)\.?$""")

fun parseBankAlert(pkg: String, text: String): BankAlert? {
    if (pkg != "com.wf.wellsfargomobile") return null
    val t = clean(text)
    WF_BALANCE.find(t)?.let { val c = cents(it.groupValues[3]); return BalanceAlert(if (it.groupValues[1].isNotEmpty() || it.groupValues[2].isNotEmpty()) -c else c) }
    WF_PAID.find(t)?.let { return DepositAlert(cents(it.groupValues[1]), "paycheck") }
    WF_DEPOSIT.find(t)?.let { return DepositAlert(cents(it.groupValues[1]), "deposit") }
    WF_ZELLE_IN.find(t)?.let { return DepositAlert(cents(it.groupValues[1]), "zelle") }
    return null
}

fun parseAlert(pkg: String, title: String, text: String): ParsedAlert? {
    val t = clean(text)  // the title is generic ("You have received a notification")
    if (pkg == "com.venmo") VENMO_SENT.find(t)?.let { return ParsedAlert("Venmo", cents(it.groupValues[1]), it.groupValues[2].trim()) }
    CHASE.find(t)?.let { return ParsedAlert(cleanMerchant(it.groupValues[2]), cents(it.groupValues[1])) }
    WF_AMOUNT.find(t)?.let { return ParsedAlert(WF_CARD, cents(it.groupValues[1])) }
    if (WF_NO_AMOUNT.containsMatchIn(t)) return ParsedAlert(WF_ONLINE, 0)
    // ponytail: guessed patterns below; Venmo has no real samples yet
    if (NOT_SPENDING.containsMatchIn(t)) return null
    val s = "$title. $t"
    val amt = AMOUNT.find(s) ?: return null
    if (pkg == "com.venmo") {
        val m = VENMO.find(s) ?: return null
        val note = listOf(m.groupValues[1].trim(), m.groupValues[2].trim().trimEnd('.')).filter { it.isNotEmpty() }.joinToString(": ")
        return ParsedAlert("Venmo", cents(amt.groupValues[1]), note)  // history files Venmo as one merchant; the note carries the intent
    }
    val m = MERCHANT.find(s, amt.range.last + 1) ?: MERCHANT.find(s) ?: return null
    return ParsedAlert(cleanMerchant(m.groupValues[1]).ifEmpty { return null }, cents(amt.groupValues[1]))
}

fun cleanMerchant(raw: String): String {
    val s = raw.trim().replace(PROCESSOR, "").replace(Regex("""\s*#\s*\d+.*$"""), "").trim()
    return if (s.any { it.isLowerCase() }) s  // already mixed case: keep
    else s.lowercase().split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}
