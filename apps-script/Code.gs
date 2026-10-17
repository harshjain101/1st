/**
 * Google Apps Script receiver for GPay Ledger.
 *
 * 1. Open a Google Sheet -> Extensions -> Apps Script, paste this file.
 * 2. Deploy -> New deployment -> Web app. Execute as: Me. Who has access: Anyone.
 * 3. Copy the /exec URL into the app: Settings -> Google Sheet webhook -> Save & sync.
 *
 * Each POST appends one row; the same app row id never appends twice.
 */
var HEADERS = ['Id', 'DateTime', 'Direction', 'Amount', 'Counterparty', 'Account', 'UpiRef', 'Sources', 'Category', 'Raw'];

function doPost(e) {
  var lock = LockService.getScriptLock();
  lock.waitLock(10000);
  try {
    var t = JSON.parse(e.postData.contents);
    var sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName('Ledger') ||
      SpreadsheetApp.getActiveSpreadsheet().insertSheet('Ledger');
    if (sheet.getLastRow() === 0) sheet.appendRow(HEADERS);

    var ids = sheet.getLastRow() > 1 ? sheet.getRange(2, 1, sheet.getLastRow() - 1, 1).getValues().map(function (r) { return String(r[0]); }) : [];
    var rowIndex = ids.indexOf(String(t.id));
    var when = Utilities.formatDate(new Date(Number(t.ts)), 'Asia/Kolkata', 'yyyy-MM-dd HH:mm:ss');
    var row = [t.id, when, t.direction, Number(t.amount), t.counterparty, t.account, t.upiRef, t.sources, t.category, t.raw];

    if (rowIndex >= 0) {
      sheet.getRange(rowIndex + 2, 1, 1, row.length).setValues([row]); // re-sent after an edit: update in place
    } else {
      sheet.appendRow(row);
    }
    return ContentService.createTextOutput(JSON.stringify({ ok: true })).setMimeType(ContentService.MimeType.JSON);
  } finally {
    lock.releaseLock();
  }
}

function doGet() {
  return ContentService.createTextOutput('GPay Ledger webhook is live. POST JSON here.');
}
