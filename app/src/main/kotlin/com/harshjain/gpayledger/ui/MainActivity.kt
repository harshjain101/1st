package com.harshjain.gpayledger.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.harshjain.gpayledger.core.Csv
import com.harshjain.gpayledger.core.Direction
import com.harshjain.gpayledger.core.Txn
import com.harshjain.gpayledger.data.TxnDb
import com.harshjain.gpayledger.sync.WebhookSync
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : ComponentActivity() {
    private val refresh = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { LedgerScreen(refresh) } }
    }

    override fun onResume() {
        super.onResume()
        refresh.intValue++
        WebhookSync.pushPending(this)
    }
}

private val IST: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")
private val Debit = Color(0xFFB3261E)
private val Credit = Color(0xFF1B5E20)

@Composable
fun LedgerScreen(refresh: MutableIntState) {
    val context = LocalContext.current
    val db = remember { TxnDb.get(context) }
    var bump by remember { mutableIntStateOf(0) }
    val txns = remember(refresh.intValue, bump) { db.all() }

    val notifOn = remember(refresh.intValue) {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
    var smsOn by remember(refresh.intValue) {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED)
    }
    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { smsOn = it }

    var showSettings by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Txn?>(null) }

    val monthStart = remember(refresh.intValue) {
        Calendar.getInstance(IST).apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val monthOut = txns.filter { it.ts >= monthStart && it.direction == Direction.DEBIT }.sumOf { it.amountPaise }
    val monthIn = txns.filter { it.ts >= monthStart && it.direction == Direction.CREDIT }.sumOf { it.amountPaise }

    Scaffold { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("GPay Ledger", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { bump++ }) { Text("Refresh") }
                TextButton(onClick = { showSettings = true }) { Text("Settings") }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("This month", style = MaterialTheme.typography.labelLarge)
                    Row {
                        Text("Out ₹" + Txn.formatPaise(monthOut), color = Debit, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("In ₹" + Txn.formatPaise(monthIn), color = Credit, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        (if (notifOn) "GPay notifications: ON" else "GPay notifications: OFF") +
                            "   " + (if (smsOn) "Bank SMS: ON" else "Bank SMS: OFF"),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (notifOn && smsOn) Credit else Debit,
                    )
                }
            }

            if (!notifOn || !smsOn) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!notifOn) {
                        Button(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                            Text("Allow notification access")
                        }
                    }
                    if (!smsOn) {
                        OutlinedButton(onClick = { smsLauncher.launch(Manifest.permission.RECEIVE_SMS) }) { Text("Allow SMS") }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { share(context, "ynab-import.csv", Csv.ynab(txns)) }) { Text("Export YNAB CSV") }
                OutlinedButton(onClick = { share(context, "gpay-ledger.csv", Csv.full(txns)) }) { Text("Export all") }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()

            if (txns.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "Nothing recorded yet. Turn on both switches above, then make a GPay payment. " +
                        "Each one lands here within seconds.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(txns, key = { it.id }) { t ->
                    TxnRow(t) { editing = t }
                    HorizontalDivider()
                }
            }
        }
    }

    editing?.let { t ->
        EditDialog(
            t,
            onDismiss = { editing = null },
            onSave = { payee, cat -> db.updateLabels(t.id, payee, cat); editing = null; bump++ },
            onDelete = { db.delete(t.id); editing = null; bump++ },
        )
    }

    if (showSettings) {
        SettingsDialog(onDismiss = { showSettings = false })
    }
}

@Composable
private fun TxnRow(t: Txn, onClick: () -> Unit) {
    val df = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.ENGLISH).apply { timeZone = IST } }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(t.counterparty ?: "Unknown payee", fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(df.format(Date(t.ts)), t.account, t.category, t.sources.lowercase()).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.width(8.dp))
        val sign = if (t.direction == Direction.DEBIT) "−" else "+"
        Text(
            "$sign₹${t.amountRupees}",
            color = if (t.direction == Direction.DEBIT) Debit else Credit,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun EditDialog(t: Txn, onDismiss: () -> Unit, onSave: (String, String) -> Unit, onDelete: () -> Unit) {
    var payee by remember { mutableStateOf(t.counterparty ?: "") }
    var category by remember { mutableStateOf(t.category ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("₹${t.amountRupees} " + if (t.direction == Direction.DEBIT) "paid" else "received") },
        text = {
            Column {
                OutlinedTextField(payee, { payee = it }, label = { Text("Payee") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(category, { category = it }, label = { Text("Category") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Text(t.raw, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(payee, category) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete", color = Debit) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun SettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(WebhookSync.url(context)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Google Sheet webhook") },
        text = {
            Column {
                Text(
                    "Optional. Paste the Apps Script web-app URL from the README. Every new transaction is POSTed there " +
                        "and appended to your sheet; failed pushes retry automatically.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(url, { url = it }, label = { Text("https://script.google.com/macros/s/…/exec") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                WebhookSync.saveUrl(context, url)
                val app = context.applicationContext
                Thread {
                    val n = WebhookSync.pushPendingBlocking(app)
                    Handler(Looper.getMainLooper()).post { Toast.makeText(app, "Pushed $n row(s)", Toast.LENGTH_SHORT).show() }
                }.start()
                onDismiss()
            }) { Text("Save & sync") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun share(context: Context, name: String, content: String) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val f = File(dir, name).apply { writeText(content) }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, name))
}
