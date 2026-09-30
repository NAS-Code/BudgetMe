package com.budgetme

import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.roundToLong

/** Minimal RFC 4180 reader: quoted fields, doubled quotes, commas and newlines inside quotes. */
fun parseCsv(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val f = StringBuilder()
    var q = false; var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            q && c == '"' && text.getOrNull(i + 1) == '"' -> { f.append('"'); i++ }
            c == '"' -> q = !q
            !q && c == ',' -> { row.add(f.toString()); f.clear() }
            !q && (c == '\n' || c == '\r') -> {
                if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                row.add(f.toString()); f.clear(); rows.add(row); row = mutableListOf()
            }
            else -> f.append(c)
        }
        i++
    }
    if (f.isNotEmpty() || row.isNotEmpty()) { row.add(f.toString()); rows.add(row) }
    return rows
}

/** CSV with a header row -> list of maps. */
fun csvRecords(text: String): List<Map<String, String>> {
    val rows = parseCsv(text.removePrefix("﻿"))
    val h = rows.firstOrNull() ?: return emptyList()
    return rows.drop(1).filter { it.size == h.size }.map { h.zip(it).toMap() }
}

fun dollarsToCents(s: String): Long = (s.toDouble() * 100).roundToLong()

/** config/categories.json -> Category rows. Non-default extras (Fitness, Shopping...) start archived. */
fun categoriesFromJson(json: String): List<Category> {
    val arr = JSONObject(json).getJSONArray("categories")
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val budget = o.optJSONObject("budget")
        val hints = o.optJSONObject("hints") ?: JSONObject()
        val kw = hints.optJSONArray("keywords")
        Category(
            id = o.getString("id"), name = o.getString("name"), description = o.getString("description"),
            budgetCents = budget?.let { (it.getDouble("amount") * 100).roundToLong() },
            period = budget?.optString("period", "month") ?: "month",
            predict = o.getString("predict"),
            keywords = kw?.let { a -> (0 until a.length()).joinToString("|") { a.getString(it) } } ?: "",
            requireKeyword = hints.optBoolean("require_keyword"),
            archived = o.optBoolean("archived") || !o.optBoolean("is_default"),
            sort = i,
        )
    }
}

/**
 * data/labeled_transactions.csv -> history rows (kind = variable, placeholders dropped), in the same order and
 * with the same effective dates as engine/backtest.py: an entry without a date inside its sheet's month is
 * dated the 15th.
 */
fun historyFromCsv(text: String, cats: List<Category>): List<Txn> {
    val byName = cats.associate { it.name to it.id }
    return csvRecords(text)
        .filter { it["kind"] == "variable" && "placeholder" !in it["flags"].orEmpty() && it["category"] in byName }
        .map { r ->
            val month = r["month"]!!
            val date = r["date"].orEmpty().takeIf { it.startsWith(month) } ?: "$month-15"
            r to date
        }
        .sortedWith(compareBy({ it.second }, { it.first["source"] }))
        .map { (r, date) ->
            Txn(
                source = "history", account = r["account"], rawDescriptor = r["description"].orEmpty(),
                merchant = r["merchant"]!!, amountCents = dollarsToCents(r["amount"]!!), date = date,
                budgetMonth = r["month"]!!, categoryId = byName[r["category"]], funWeek = r["fun_week"]?.toDoubleOrNull()?.toInt(),
                status = "confirmed", note = r["note"]?.ifBlank { null },
            )
        }
}

fun LocalDate.month() = toString().substring(0, 7)
