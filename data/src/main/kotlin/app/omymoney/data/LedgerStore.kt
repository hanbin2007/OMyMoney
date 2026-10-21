package app.omymoney.data

import android.content.Context
import androidx.room.Room
import app.omymoney.core.ledger.LedgerRepository
import app.omymoney.data.backup.BackupArchive

class LedgerStore(context: Context, diagnostics: app.omymoney.core.diagnostics.DiagnosticSink = app.omymoney.core.diagnostics.DiagnosticSink.NONE) {
    private val database = Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, "omymoney.db").build()
    val catalog = CatalogRepository(database)
    val assetHistory = AssetHistoryRepository(database)
    val budgets = BudgetRepository(database)
    val schedules = ScheduledBillRepository(database)
    val savings = SavingsRepository(database)
    val ledger: LedgerRepository = RoomLedgerRepository(database)
    val batchActions = BatchActions(database)
    val billImport = app.omymoney.data.exchange.BillImportService(database, context)
    val billExport = app.omymoney.data.exchange.BillExportService(database)
    val drafts = DraftRepository(database)
    val recognitionQueue = RecognitionQueue(database, diagnostics = diagnostics)
    val automation = AutomationSettingsRepository(database)
    val attachments = AttachmentStore(context)
    val backup = BackupArchive(database, context)
}
