package com.budgetme

import android.content.Context
import android.util.Log
import androidx.work.*
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.TimeUnit

// Test 1 (Nov 2026, for the iPhone plan): how long after a bank alert does the purchase show up in Plaid, pending and
// posted? The alert is the clock. After each alert: check-now + sync at +1, +3, +5, +10, +20, +40, +60 min; every 3 hours a
// pass without check-now catches postings. Stores per alert purchase only times, its account and pending/posted (no
// merchants or amounts), on the phone. Off by itself after DAYS. Read: adb shell run-as com.budgetme cat
// shared_prefs/probe.xml (the "report" entry), or logcat -s BudgetMeProbe.

/** One alert purchase: when the alert came, when Plaid first had it (linked), whether that was pending, when it posted. */
data class ProbeEntry(val account: String, val alertAt: Long, val seenAt: Long? = null, val seenPending: Boolean? = null, val postedAt: Long? = null)

/** Update an entry from what the bank feed shows now: linked to a feed transaction, and is that pending. */
fun observe(e: ProbeEntry, linked: Boolean, pending: Boolean?, now: Long): ProbeEntry {
    var r = e
    if (linked && r.seenAt == null) r = r.copy(seenAt = now, seenPending = pending)
    if (linked && pending == false && r.postedAt == null) r = r.copy(postedAt = now)
    return r
}

/** Per account: alerts, how many showed within the hour, delay to first seen (median / 80% / max), pending share, posting. */
fun probeReport(entries: List<ProbeEntry>, now: Long): String = entries.groupBy { it.account }.toSortedMap().map { (acct, es) ->
    fun min(ms: Long) = ms / 60_000.0
    val old = es.filter { now - it.alertAt >= 60 * 60_000 }  // only alerts whose first hour is over count toward "within the hour"
    val delays = es.mapNotNull { e -> e.seenAt?.let { min(it - e.alertAt) } }.sorted()
    fun pct(p: Double) = delays[((delays.size - 1) * p).toInt()]
    val seen = es.filter { it.seenAt != null }
    val posted = es.mapNotNull { e -> e.postedAt?.let { (it - e.alertAt) / 3_600_000.0 } }.sorted()
    buildString {
        append("$acct: ${es.size} alerts")
        if (old.isNotEmpty()) append(", ${old.count { it.seenAt != null && it.seenAt - it.alertAt <= 60 * 60_000 }}/${old.size} seen within 1 h")
        if (delays.isNotEmpty()) append(", first seen after %.0f min median, %.0f min 80%%, %.0f min max".format(pct(0.5), pct(0.8), delays.last()))
        if (seen.isNotEmpty()) append(", ${seen.count { it.seenPending == true }}/${seen.size} first seen pending")
        if (posted.isNotEmpty()) append(", posted after %.1f h median".format(posted[(posted.size - 1) / 2]))
    }
}.joinToString("\n").ifEmpty { "no alerts yet" }

object Probe {
    const val DAYS = 14L
    private val OFFSETS = listOf(1L, 3, 5, 10, 20, 40, 60)
    private fun p(ctx: Context) = ctx.getSharedPreferences("probe", Context.MODE_PRIVATE)

    /** The first call starts the 14 days. */
    private fun active(ctx: Context): Boolean {
        val start = p(ctx).getLong("start", 0).takeIf { it > 0 } ?: System.currentTimeMillis().also { p(ctx).edit().putLong("start", it).apply() }
        return System.currentTimeMillis() < start + DAYS * 86_400_000
    }

    fun afterAlert(ctx: Context) {
        if (!BankSync.connected(ctx) || !active(ctx)) return
        val wm = WorkManager.getInstance(ctx)
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        for (m in OFFSETS) wm.enqueueUniqueWork("probe-$m", ExistingWorkPolicy.REPLACE,  // a newer alert restarts the schedule
            OneTimeWorkRequestBuilder<ProbeWorker>().setInitialDelay(m, TimeUnit.MINUTES).setConstraints(net)
                .setInputData(workDataOf("refresh" to true)).build())
        wm.enqueueUniquePeriodicWork("probe-postings", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ProbeWorker>(3, TimeUnit.HOURS).setConstraints(net).build())
    }

    private fun load(ctx: Context): MutableMap<Long, ProbeEntry> {
        val o = JSONObject(p(ctx).getString("entries", "{}")!!)
        return o.keys().asSequence().associate { k -> val e = o.getJSONObject(k)
            k.toLong() to ProbeEntry(e.getString("a"), e.getLong("t"), e.optLong("s").takeIf { e.has("s") },
                if (e.has("p")) e.getBoolean("p") else null, e.optLong("d").takeIf { e.has("d") })
        }.toMutableMap()
    }

    private fun save(ctx: Context, m: Map<Long, ProbeEntry>, now: Long) {
        val o = JSONObject()
        for ((id, e) in m) o.put(id.toString(), JSONObject().put("a", e.account).put("t", e.alertAt)
            .apply { e.seenAt?.let { put("s", it) }; e.seenPending?.let { put("p", it) }; e.postedAt?.let { put("d", it) } })
        val report = probeReport(m.values.toList(), now)
        p(ctx).edit().putString("entries", o.toString()).putString("report", report).apply()
        Log.i("BudgetMeProbe", report.replace("\n", " | "))
    }

    /** Sync, record what's visible now, then (in the hour after an alert) ask Plaid to check again for the next round. */
    suspend fun run(ctx: Context, refresh: Boolean) {
        if (!BankSync.connected(ctx)) return
        BankSync.sync(ctx)
        val start = p(ctx).getLong("start", 0); val now = System.currentTimeMillis()
        val dao = AppDb.get(ctx).dao()
        val alerts = dao.txnsSince(LocalDate.now().minusDays(5).toString()).filter { it.source == "alert" && it.createdAt >= start && it.account != null }
        if (alerts.isNotEmpty()) {
            val feed = runCatching {
                val ym = YearMonth.now()
                (BankSync.monthFeed(ctx, ym).first + if (LocalDate.now().dayOfMonth <= 5) BankSync.monthFeed(ctx, ym.minusMonths(1)).first else emptyList())
                    .associateBy { it.id }
            }.getOrDefault(emptyMap())
            val m = load(ctx)
            for (t in alerts) {
                val e = m[t.id] ?: ProbeEntry(t.account!!, t.createdAt)
                m[t.id] = observe(e, t.feedId != null, t.feedId?.let { feed[it]?.pending }, now)
            }
            save(ctx, m, now)
        }
        if (refresh && now - p(ctx).getLong("refresh_at", 0) >= 40_000) {  // Plaid allows 2 a minute per bank
            p(ctx).edit().putLong("refresh_at", now).apply()
            BankSync.refreshNow(ctx)
        }
    }
}

class ProbeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result { Probe.run(applicationContext, inputData.getBoolean("refresh", false)); return Result.success() }
}
