package com.harshjain.gpayledger.core

enum class Direction { DEBIT, CREDIT }

enum class Source { GPAY, SMS }

/** What a single notification or SMS tells us about a transaction. */
data class ParsedTxn(
    val amountPaise: Long,
    val direction: Direction,
    val counterparty: String?,
    val account: String?,
    val upiRef: String?,
    val source: Source,
)

/** A stored ledger row. */
data class Txn(
    val id: Long,
    val ts: Long,
    val amountPaise: Long,
    val direction: Direction,
    val counterparty: String?,
    val account: String?,
    val upiRef: String?,
    /** Comma-joined set of sources that confirmed this row, e.g. "GPAY,SMS". */
    val sources: String,
    val raw: String,
    val category: String?,
    val synced: Boolean,
) {
    val amountRupees: String get() = formatPaise(amountPaise)

    companion object {
        fun formatPaise(paise: Long): String {
            val rupees = paise / 100
            val p = paise % 100
            return "%d.%02d".format(rupees, p)
        }
    }
}
