# GPay Ledger

Android app that records every Google Pay payment you make, automatically, the moment it happens.

Google Pay has no personal-transactions API, so the app listens on the phone itself:

| Capture | What it reads | What it gives |
|---|---|---|
| Notification listener | GPay's own "Paid ₹X to Y" / "Received ₹X from Y" notifications | amount, direction, payee, sometimes UPI ref |
| SMS receiver | Your bank's UPI debit/credit SMS (PNB, APB, HDFC, BOB, ICICI, SBI, …) | account last-4, UPI ref, VPA |

Both captures of the same payment are merged into one row (matched on UPI ref, or same amount within 3 minutes), so nothing is double-counted. OTPs, "will be debited" reminders, failed/pending payments, payment requests and non-UPI card swipes are ignored.

Everything is stored on-device (SQLite). From the app you can:

- see this month's in/out and the full list; tap a row to fix the payee, add a category, or delete it
- **Export YNAB CSV** (`Date,Payee,Memo,Outflow,Inflow`, dd/mm/yyyy, memo = account + UPI ref + category)
- **Export all** (every field incl. raw notification/SMS text)
- optionally push each row live to a Google Sheet via the bundled Apps Script webhook

## Install

1. Push to GitHub; the **Android** workflow runs the parser tests and builds `app-debug.apk`. Download it from the workflow run's Artifacts (or run `./gradlew :app:assembleDebug` locally with the Android SDK).
2. Install the APK on your phone (allow "install from unknown sources").
3. Open the app and tap **Allow notification access** → enable *GPay Ledger*. Tap **Allow SMS**.
4. Disable battery optimisation for the app (Settings → Apps → GPay Ledger → Battery → Unrestricted) so Android doesn't kill the listener.
5. Optional sheet sync: follow the comment at the top of `apps-script/Code.gs`, then paste the `/exec` URL under **Settings** in the app.

## Project layout

- `core/` pure Kotlin: `TxnParser` (notification + SMS regexes), `Csv` exporters, unit tests. Run `./gradlew :core:test`.
- `app/` Android (Compose UI, SQLite store, `GPayNotificationListener`, `SmsReceiver`, `WebhookSync`).
- `apps-script/Code.gs` Google Sheets receiver.
- `.github/workflows/android.yml` CI: tests + debug APK artifact.

## Adding a bank format

If a bank's SMS isn't picked up, add its text as a test in `core/src/test/.../TxnParserTest.kt` and extend the regexes in `TxnParser.kt`. The SMS must mention UPI/VPA/an `@handle` to be considered.
