package com.harshjain.gpayledger.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.harshjain.gpayledger.core.TxnParser
import com.harshjain.gpayledger.data.TxnDb
import com.harshjain.gpayledger.sync.WebhookSync

/** Catches the bank's UPI debit/credit SMS, which carries the account and UPI reference GPay's notification often lacks. */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (msgs.isEmpty()) return

        // Multipart SMS arrive as several PDUs of one message; join them.
        val sender = msgs[0].displayOriginatingAddress
        val body = msgs.joinToString("") { it.messageBody ?: "" }
        val ts = msgs[0].timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()

        val parsed = TxnParser.parseSms(sender, body) ?: return
        val pending = goAsync()
        Thread {
            try {
                val id = TxnDb.get(context).record(parsed, ts, "[sms $sender] $body")
                Log.i(TAG, "recorded sms txn id=$id amount=${parsed.amountPaise}")
                if (id >= 0) WebhookSync.pushPendingBlocking(context)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private companion object { const val TAG = "GPayLedger" }
}
