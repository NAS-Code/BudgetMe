package com.budgetme

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.launch

/** Bank app package -> account label. */
val BANKS = mapOf(
    "com.chase.sig.android" to "Chase Credit Card",
    "com.wf.wellsfargomobile" to "Wells Fargo",
    "com.venmo" to "Venmo",
)

class AlertListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val account = BANKS[sbn.packageName] ?: return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val ctx = applicationContext
        Brain.scope.launch { Brain.onAlert(ctx, sbn.packageName, account, title, text, sbn.postTime) }
    }
}

data class ParsedAlert(val merchant: String, val cents: Long, val note: String? = null)

private val AMOUNT = Regex("""\$\s?([0-9][0-9,]*\.[0-9]{2})""")
private val NOT_SPENDING = Regex("""(?i)paid you|sent you|deposit|received|credited|refund|payment (?:posted|received|scheduled)|balance|statement|password|verification|security code|sign.?in|autopay""")
private val VENMO = Regex("""(?i)you paid (.+?)\s+\$[0-9,]+\.[0-9]{2}\s*(?:[-:–—]\s*|for\s+)?(.*)""")
private val MERCHANT = Regex("""(?i)\b(?:transaction with|purchase (?:at|with)|charged at|spent at|at|with)\s+(.+?)(?=\s+on\s|\s+using\s|\s+with (?:your )?(?:card|debit|credit)|\s+was\s|\s+has\s|\s+exceeded|\s+for\s+\$|\.\s|\.?$|,)""")
private val PROCESSOR = Regex("""(?i)^(?:sq|tst|sp|py|pp|dd|cke|paypal|pypl|goog|google|apl|in)\s?\*\s*""")

/**
 * ponytail: provisional, pattern-based. The real Chase / Wells Fargo / Venmo wordings aren't known yet
 * (ROADMAP step 2). Everything from those apps is logged to RawAlert (Settings > Captured alerts);
 * replace this with one tested parser per bank once there are real samples.
 */
fun parseAlert(pkg: String, title: String, text: String): ParsedAlert? {
    val s = "$title. $text".replace(Regex("\\s+"), " ").trim()
    if (NOT_SPENDING.containsMatchIn(s)) return null
    val amt = AMOUNT.find(s) ?: return null
    val cents = dollarsToCents(amt.groupValues[1].replace(",", ""))
    if (pkg == "com.venmo") {
        val m = VENMO.find(s) ?: return null
        val note = listOf(m.groupValues[1].trim(), m.groupValues[2].trim().trimEnd('.')).filter { it.isNotEmpty() }.joinToString(": ")
        return ParsedAlert("Venmo", cents, note)  // history files Venmo as one merchant; the note carries the intent
    }
    // look after the amount first ("$12.34 transaction with STARBUCKS"), then anywhere ("At STARBUCKS, $12.34")
    val m = MERCHANT.find(s, amt.range.last + 1) ?: MERCHANT.find(s) ?: return null
    return ParsedAlert(cleanMerchant(m.groupValues[1]).ifEmpty { return null }, cents)
}

fun cleanMerchant(raw: String): String {
    val s = raw.trim().replace(PROCESSOR, "").replace(Regex("""\s*#\s*\d+.*$"""), "").trim()
    return if (s.any { it.isLowerCase() }) s  // already mixed case: keep
    else s.lowercase().split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}
