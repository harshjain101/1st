package com.harshjain.gpayledger.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TxnParserTest {

    @Test
    fun gpayPaid() {
        val p = assertNotNull(TxnParser.parseGPay("Paid ₹1,250.50 to Rubina Shaikh"))
        assertEquals(125050, p.amountPaise)
        assertEquals(Direction.DEBIT, p.direction)
        assertEquals("Rubina Shaikh", p.counterparty)
    }

    @Test
    fun gpayYouPaidWithBody() {
        val p = assertNotNull(TxnParser.parseGPay("Payment successful You paid ₹110 to Swagatam using PNB Bank 1234. UPI transaction ID 428123456789"))
        assertEquals(11000, p.amountPaise)
        assertEquals("Swagatam", p.counterparty)
        assertEquals("428123456789", p.upiRef)
    }

    @Test
    fun gpayReceived() {
        val p = assertNotNull(TxnParser.parseGPay("You received ₹200 from Akshat Jain"))
        assertEquals(Direction.CREDIT, p.direction)
        assertEquals("Akshat Jain", p.counterparty)
    }

    @Test
    fun gpayAmountFirst() {
        val p = assertNotNull(TxnParser.parseGPay("₹149 paid to uberindiasystems38"))
        assertEquals(14900, p.amountPaise)
        assertEquals("uberindiasystems38", p.counterparty)
    }

    @Test
    fun gpayIgnoresNoise() {
        assertNull(TxnParser.parseGPay("Payment of ₹500 to Swiggy is pending"))
        assertNull(TxnParser.parseGPay("Payment of ₹500 to Swiggy failed"))
        assertNull(TxnParser.parseGPay("Akshat Jain is requesting ₹500"))
        assertNull(TxnParser.parseGPay("You got a scratch card worth up to ₹1000"))
        assertNull(TxnParser.parseGPay("Pay bills on time with Google Pay"))
    }

    @Test
    fun smsPnb() {
        val p = assertNotNull(
            TxnParser.parseSms(
                "VM-PNBSMS",
                "A/c XX4521 debited for Rs.110.00 on 17-10-2026 to UPI/P2A/428123456789/Rubina Shaikh. Avl Bal Rs.5,000.00 -PNB",
            ),
        )
        assertEquals(11000, p.amountPaise)
        assertEquals(Direction.DEBIT, p.direction)
        assertEquals("PNB 4521", p.account)
        assertEquals("Rubina Shaikh", p.counterparty)
        assertEquals("428123456789", p.upiRef)
    }

    @Test
    fun smsHdfcRupayUpi() {
        val p = assertNotNull(
            TxnParser.parseSms(
                "AD-HDFCBK",
                "Rs.500.00 debited from HDFC Bank RuPay Credit Card XX4667 to VPA swiggy@ybl on 17-10-26. UPI Ref No 428987654321. Not you? Call 18002586161",
            ),
        )
        assertEquals(50000, p.amountPaise)
        assertEquals("HDFC 4667", p.account)
        assertEquals("swiggy@ybl", p.counterparty)
        assertEquals("428987654321", p.upiRef)
    }

    @Test
    fun smsAirtelCredit() {
        val p = assertNotNull(
            TxnParser.parseSms("AX-AIRBNK", "Rs 200.00 credited to your Airtel Payments Bank A/c XX7653 from akshat@okicici via UPI. Ref 428111222333"),
        )
        assertEquals(Direction.CREDIT, p.direction)
        assertEquals("APB 7653", p.account)
        assertEquals("akshat@okicici", p.counterparty)
    }

    @Test
    fun smsIgnoresNoise() {
        assertNull(TxnParser.parseSms("VM-PNBSMS", "123456 is your OTP for UPI txn of Rs.500. Do not share."))
        assertNull(TxnParser.parseSms("AD-HDFCBK", "Rs.1,350.00 will be debited from your A/c XX4667 on 20-10-26 for Google Play UPI autopay"))
        assertNull(TxnParser.parseSms("AD-HDFCBK", "Rs.2,000 spent on HDFC Card XX6155 at AMAZON on 17-10-26"))
    }

    @Test
    fun csvQuotesAndSplits() {
        val row = Txn(1, 1760700000000L, 12345, Direction.DEBIT, "Shri, SAS", "PNB 4521", "428123456789", "GPAY,SMS", "raw \"x\"", null, false)
        val y = Csv.ynab(listOf(row)).lines()
        assertEquals("Date,Payee,Memo,Outflow,Inflow", y[0])
        assertEquals("17/10/2025,\"Shri, SAS\",PNB 4521 | UPI 428123456789,123.45,", y[1])
        val f = Csv.full(listOf(row)).lines()[1]
        assertEquals(true, f.endsWith("\"raw \"\"x\"\"\""))
    }
}
