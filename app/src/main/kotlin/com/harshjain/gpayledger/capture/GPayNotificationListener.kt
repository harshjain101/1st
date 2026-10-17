package com.harshjain.gpayledger.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.harshjain.gpayledger.core.TxnParser
import com.harshjain.gpayledger.data.TxnDb
import com.harshjain.gpayledger.sync.WebhookSync

/** Watches Google Pay's own "Paid ₹X to Y" / "Received ₹X from Y" notifications. */
class GPayNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!TxnParser.isGPayPackage(sbn.packageName)) return
        val extras = sbn.notification.extras ?: return
        val text = listOf(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
        ).filterNotNull().joinToString(" ") { it.toString() }.trim()
        if (text.isEmpty()) return

        val parsed = TxnParser.parseGPay(text) ?: return
        val id = TxnDb.get(this).record(parsed, sbn.postTime, "[gpay] $text")
        Log.i(TAG, "recorded gpay txn id=$id amount=${parsed.amountPaise}")
        if (id >= 0) WebhookSync.pushPending(this)
    }

    private companion object { const val TAG = "GPayLedger" }
}
