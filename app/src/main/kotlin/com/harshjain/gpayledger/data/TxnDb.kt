package com.harshjain.gpayledger.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.harshjain.gpayledger.core.Direction
import com.harshjain.gpayledger.core.ParsedTxn
import com.harshjain.gpayledger.core.Txn

/**
 * Plain SQLite store. A GPay notification and the bank SMS for the same payment arrive
 * seconds apart; [record] merges them into one row instead of double-counting.
 */
class TxnDb private constructor(context: Context) : SQLiteOpenHelper(context, "ledger.db", null, 1) {

    companion object {
        /** Window within which same-amount, same-direction captures are treated as one transaction. */
        private const val MERGE_WINDOW_MS = 3 * 60 * 1000L

        @Volatile private var instance: TxnDb? = null

        fun get(context: Context): TxnDb =
            instance ?: synchronized(this) { instance ?: TxnDb(context.applicationContext).also { instance = it } }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE txns(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                amount_paise INTEGER NOT NULL,
                direction TEXT NOT NULL,
                counterparty TEXT,
                account TEXT,
                upi_ref TEXT,
                sources TEXT NOT NULL,
                raw TEXT NOT NULL,
                category TEXT,
                synced INTEGER NOT NULL DEFAULT 0)""",
        )
        db.execSQL("CREATE INDEX idx_txns_ref ON txns(upi_ref)")
        db.execSQL("CREATE INDEX idx_txns_ts ON txns(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Insert or merge. Returns the row id, or -1 if it was an exact duplicate (same source already seen). */
    @Synchronized
    fun record(p: ParsedTxn, ts: Long, raw: String): Long {
        val db = writableDatabase
        val existing = findMatch(db, p, ts)
        if (existing == null) {
            val cv = ContentValues().apply {
                put("ts", ts)
                put("amount_paise", p.amountPaise)
                put("direction", p.direction.name)
                put("counterparty", p.counterparty)
                put("account", p.account)
                put("upi_ref", p.upiRef)
                put("sources", p.source.name)
                put("raw", raw)
            }
            return db.insert("txns", null, cv)
        }
        val sources = existing.sources.split(",").toMutableSet()
        if (!sources.add(p.source.name)) return -1 // the same source told us twice, e.g. notification re-posted
        val cv = ContentValues().apply {
            put("sources", sources.sorted().joinToString(","))
            put("raw", existing.raw + "\n---\n" + raw)
            put("synced", 0)
            if (existing.counterparty.isNullOrBlank() && p.counterparty != null) put("counterparty", p.counterparty)
            if (existing.account.isNullOrBlank() && p.account != null) put("account", p.account)
            if (existing.upiRef.isNullOrBlank() && p.upiRef != null) put("upi_ref", p.upiRef)
        }
        db.update("txns", cv, "id=?", arrayOf(existing.id.toString()))
        return existing.id
    }

    private fun findMatch(db: SQLiteDatabase, p: ParsedTxn, ts: Long): Txn? {
        val ref = p.upiRef
        if (!ref.isNullOrBlank()) {
            query("upi_ref=?", arrayOf(ref), limit = 1).firstOrNull()?.let { return it }
        }
        return query(
            "amount_paise=? AND direction=? AND ts BETWEEN ? AND ?",
            arrayOf(p.amountPaise.toString(), p.direction.name, (ts - MERGE_WINDOW_MS).toString(), (ts + MERGE_WINDOW_MS).toString()),
            limit = 1,
        ).firstOrNull()
    }

    fun all(): List<Txn> = query(null, null, limit = 5000)

    fun unsynced(): List<Txn> = query("synced=0", null, limit = 500)

    fun markSynced(id: Long) {
        writableDatabase.update("txns", ContentValues().apply { put("synced", 1) }, "id=?", arrayOf(id.toString()))
    }

    fun updateLabels(id: Long, counterparty: String?, category: String?) {
        val cv = ContentValues().apply {
            put("counterparty", counterparty?.ifBlank { null })
            put("category", category?.ifBlank { null })
            put("synced", 0)
        }
        writableDatabase.update("txns", cv, "id=?", arrayOf(id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("txns", "id=?", arrayOf(id.toString()))
    }

    private fun query(where: String?, args: Array<String>?, limit: Int): List<Txn> {
        val c = readableDatabase.query("txns", null, where, args, null, null, "ts DESC, id DESC", limit.toString())
        c.use { return generateSequence { if (it.moveToNext()) it.toTxn() else null }.toList() }
    }

    private fun Cursor.toTxn() = Txn(
        id = getLong(getColumnIndexOrThrow("id")),
        ts = getLong(getColumnIndexOrThrow("ts")),
        amountPaise = getLong(getColumnIndexOrThrow("amount_paise")),
        direction = Direction.valueOf(getString(getColumnIndexOrThrow("direction"))),
        counterparty = getString(getColumnIndexOrThrow("counterparty")),
        account = getString(getColumnIndexOrThrow("account")),
        upiRef = getString(getColumnIndexOrThrow("upi_ref")),
        sources = getString(getColumnIndexOrThrow("sources")),
        raw = getString(getColumnIndexOrThrow("raw")),
        category = getString(getColumnIndexOrThrow("category")),
        synced = getInt(getColumnIndexOrThrow("synced")) == 1,
    )
}
