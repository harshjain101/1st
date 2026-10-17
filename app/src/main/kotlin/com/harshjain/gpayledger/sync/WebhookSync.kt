package com.harshjain.gpayledger.sync

import android.content.Context
import android.util.Log
import com.harshjain.gpayledger.core.Txn
import com.harshjain.gpayledger.data.TxnDb
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Optional: POST each new row as JSON to a webhook (the bundled Google Apps Script appends it to a Sheet).
 * Rows stay flagged unsynced until the server answers 2xx, so a failed push is retried on the next capture or app open.
 */
object WebhookSync {
    private const val PREFS = "gpay_ledger"
    private const val KEY_URL = "webhook_url"
    private const val TAG = "GPayLedger"
    private val executor = Executors.newSingleThreadExecutor()

    fun url(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URL, "") ?: ""

    fun saveUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_URL, url.trim()).apply()
    }

    fun pushPending(context: Context) {
        val app = context.applicationContext
        executor.execute { pushPendingBlocking(app) }
    }

    /** Returns the number of rows successfully pushed. */
    fun pushPendingBlocking(context: Context): Int {
        val url = url(context)
        if (url.isBlank()) return 0
        val db = TxnDb.get(context)
        var ok = 0
        for (t in db.unsynced()) {
            if (post(url, t)) {
                db.markSynced(t.id)
                ok++
            } else {
                break // keep order; try again later
            }
        }
        return ok
    }

    private fun post(url: String, t: Txn): Boolean = try {
        val body = JSONObject().apply {
            put("id", t.id)
            put("ts", t.ts)
            put("amount", t.amountRupees)
            put("direction", t.direction.name)
            put("counterparty", t.counterparty ?: "")
            put("account", t.account ?: "")
            put("upiRef", t.upiRef ?: "")
            put("sources", t.sources)
            put("category", t.category ?: "")
            put("raw", t.raw)
        }.toString()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            instanceFollowRedirects = true // Apps Script answers with a 302 to the result page
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        conn.disconnect()
        code in 200..399
    } catch (e: Exception) {
        Log.w(TAG, "webhook push failed: ${e.message}")
        false
    }
}
