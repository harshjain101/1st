package com.harshjain.gpayledger.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Csv {
    private val IST: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")

    private fun fmt(pattern: String) = SimpleDateFormat(pattern, Locale.ENGLISH).apply { timeZone = IST }

    /** YNAB import format: Date,Payee,Memo,Outflow,Inflow (dd/mm/yyyy). */
    fun ynab(rows: List<Txn>): String {
        val d = fmt("dd/MM/yyyy")
        val sb = StringBuilder("Date,Payee,Memo,Outflow,Inflow\n")
        for (r in rows) {
            val memo = listOfNotNull(r.account, r.upiRef?.let { "UPI $it" }, r.category).joinToString(" | ")
            val out = if (r.direction == Direction.DEBIT) r.amountRupees else ""
            val inn = if (r.direction == Direction.CREDIT) r.amountRupees else ""
            sb.append(listOf(d.format(Date(r.ts)), r.counterparty ?: "", memo, out, inn).joinToString(",") { q(it) }).append('\n')
        }
        return sb.toString()
    }

    /** Everything we know, one row per transaction. */
    fun full(rows: List<Txn>): String {
        val d = fmt("yyyy-MM-dd HH:mm:ss")
        val sb = StringBuilder("Id,DateTime,Direction,Amount,Counterparty,Account,UpiRef,Sources,Category,Raw\n")
        for (r in rows) {
            sb.append(
                listOf(
                    r.id.toString(), d.format(Date(r.ts)), r.direction.name, r.amountRupees,
                    r.counterparty ?: "", r.account ?: "", r.upiRef ?: "", r.sources, r.category ?: "", r.raw,
                ).joinToString(",") { q(it) },
            ).append('\n')
        }
        return sb.toString()
    }

    private fun q(s: String): String {
        val needs = s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needs) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
