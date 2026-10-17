package com.harshjain.gpayledger.core

import java.math.BigDecimal

/**
 * Pure-Kotlin parsers for Google Pay notifications and Indian bank UPI SMS alerts.
 * No Android dependencies so it can be unit-tested on the JVM.
 */
object TxnParser {

    /** Google Pay (India) and the global Google Wallet/Pay package ids. */
    val GPAY_PACKAGES: Set<String> = setOf(
        "com.google.android.apps.nbu.paisa.user",
        "com.google.android.apps.walletnfcrel",
    )

    private val AMOUNT = Regex("""(?:₹|Rs\.?|INR)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
    private val UPI_REF = Regex("""\b(\d{12})\b""")
    private val LAST4 = Regex(
        """(?:a/c|acct|account|card|a/c no)\.?\s*(?:no\.?|number)?\s*(?:ending\s*(?:in|with)?\s*)?(?:[Xx*]+\s*)?(\d{4})\b""",
        RegexOption.IGNORE_CASE,
    )

    private val GPAY_SKIP = Regex(
        """\b(failed|failure|pending|processing|declined|request(?:ed|ing|s)?|remind(?:er)?|cashback|reward|scratch|offer|expired|unsuccessful|could not|couldn't)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val GPAY_DEBIT = Regex("""\b(paid|sent|payment of|you paid|debited)\b""", RegexOption.IGNORE_CASE)
    private val GPAY_CREDIT = Regex("""\b(received|credited|refund(?:ed)?|got)\b""", RegexOption.IGNORE_CASE)

    private val SMS_SKIP = Regex(
        """\b(OTP|one time password|will be debited|is due|due on|EMI due|failed|declined|unsuccessful|requested|request|has requested|login|mandate created|autopay set)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SMS_UPI = Regex("""\b(UPI|VPA|@[a-z]{2,})\b""", RegexOption.IGNORE_CASE)
    private val SMS_DEBIT = Regex("""\b(debited|sent|paid|withdrawn|spent|purchase|dr)\b""", RegexOption.IGNORE_CASE)
    private val SMS_CREDIT = Regex("""\b(credited|received|deposited|reversed|refund(?:ed)?|cr)\b""", RegexOption.IGNORE_CASE)

    private val COUNTERPARTY_STOPS = listOf(
        " using ", " via ", " on ", " with ", " for ", " is ", " has ", " was ", " successful",
        " upi", " ref", " rrn", " txn", " transaction", " info", " avl", " bal", " if ", " not you",
        " call ", " sms ", " dial ", " -", " |", "(", ".", ",", ";", "\n", "₹", " rs", " inr",
    )

    private val BANKS = listOf(
        "PNB" to "PNB", "PUNJAB" to "PNB",
        "HDFC" to "HDFC",
        "AIRBNK" to "APB", "AIRTEL" to "APB", "APBL" to "APB",
        "BOB" to "BOB", "BARODA" to "BOB",
        "ICICI" to "ICICI", "SBI" to "SBI", "AXIS" to "AXIS", "KOTAK" to "KOTAK",
        "IDFC" to "IDFC", "YES" to "YES", "INDUS" to "INDUSIND", "FEDERAL" to "FEDERAL",
        "CANARA" to "CANARA", "UNION" to "UNION", "PAYTM" to "PAYTM",
    )

    fun isGPayPackage(pkg: String?): Boolean = pkg != null && pkg in GPAY_PACKAGES

    /** Parse a GPay notification (title + text joined). Returns null for anything that is not a settled payment. */
    fun parseGPay(text: String): ParsedTxn? {
        val t = text.replace(' ', ' ').trim()
        if (t.isEmpty() || GPAY_SKIP.containsMatchIn(t)) return null
        val amount = parseAmount(t) ?: return null
        val direction = when {
            GPAY_CREDIT.containsMatchIn(t) && !GPAY_DEBIT.containsMatchIn(t) -> Direction.CREDIT
            GPAY_DEBIT.containsMatchIn(t) -> Direction.DEBIT
            GPAY_CREDIT.containsMatchIn(t) -> Direction.CREDIT
            else -> return null
        }
        val keyword = if (direction == Direction.DEBIT) " to " else " from "
        return ParsedTxn(
            amountPaise = amount,
            direction = direction,
            counterparty = after(t, keyword),
            account = null,
            upiRef = UPI_REF.find(t)?.groupValues?.get(1),
            source = Source.GPAY,
        )
    }

    /** Parse a bank SMS. Only UPI alerts are accepted; OTPs, reminders and non-UPI card swipes return null. */
    fun parseSms(sender: String?, body: String): ParsedTxn? {
        val t = body.replace(' ', ' ').trim()
        if (t.isEmpty() || SMS_SKIP.containsMatchIn(t) || !SMS_UPI.containsMatchIn(t)) return null
        val amount = parseAmount(t) ?: return null
        val direction = when {
            SMS_DEBIT.containsMatchIn(t) -> Direction.DEBIT
            SMS_CREDIT.containsMatchIn(t) -> Direction.CREDIT
            else -> return null
        }
        val bank = bankFromSender(sender)
        val last4 = LAST4.find(t)?.groupValues?.get(1)
        val account = listOfNotNull(bank, last4).joinToString(" ").ifBlank { null }
        return ParsedTxn(
            amountPaise = amount,
            direction = direction,
            counterparty = smsCounterparty(t, direction),
            account = account,
            upiRef = UPI_REF.find(t)?.groupValues?.get(1),
            source = Source.SMS,
        )
    }

    fun parseAmount(text: String): Long? {
        val m = AMOUNT.find(text) ?: return null
        val n = m.groupValues[1].replace(",", "")
        return runCatching { BigDecimal(n).movePointRight(2).toLong() }.getOrNull()?.takeIf { it > 0 }
    }

    fun bankFromSender(sender: String?): String? {
        val s = sender?.uppercase() ?: return null
        return BANKS.firstOrNull { s.contains(it.first) }?.second
    }

    private fun smsCounterparty(t: String, direction: Direction): String? {
        // PNB style: "UPI/P2A/428123456789/Rubina Shaikh"
        Regex("""UPI/[A-Z0-9]+/\d+/([^/.\n,]+)""", RegexOption.IGNORE_CASE).find(t)?.let {
            return clean(it.groupValues[1])
        }
        // "to VPA swiggy@ybl", "towards X", "from X"
        val keywords = if (direction == Direction.DEBIT) listOf(" to vpa ", " to ", " towards ", " at ") else listOf(" from vpa ", " from ", " by ")
        for (k in keywords) after(t, k)?.let { return it }
        return null
    }

    /** Text after the first occurrence of [keyword] up to the first stop token. */
    private fun after(t: String, keyword: String): String? {
        val lower = t.lowercase()
        val i = lower.indexOf(keyword)
        if (i < 0) return null
        val start = i + keyword.length
        var end = t.length
        for (s in COUNTERPARTY_STOPS) {
            val j = lower.indexOf(s, start)
            if (j in start until end) end = j
        }
        return clean(t.substring(start, end))
    }

    private fun clean(s: String): String? =
        s.trim().trimEnd('.', ',', ':', '-', '!', ' ').trim().ifBlank { null }
}
