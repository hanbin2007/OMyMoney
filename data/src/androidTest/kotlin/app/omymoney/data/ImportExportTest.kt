package app.omymoney.data

import app.omymoney.data.exchange.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.omymoney.core.model.*
import app.omymoney.data.backup.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.dhatim.fastexcel.Workbook
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import androidx.test.platform.app.InstrumentationRegistry

class ImportExportTest {
    private suspend fun withLedger(block: suspend (LedgerDatabase, Book, AssetAccount) -> Unit) {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val book = Book(UUID.randomUUID().toString(), "Exchange", zoneId = ZoneOffset.UTC)
            val cash = AssetAccount(UUID.randomUUID().toString(), "Cash")
            val catalog = CatalogRepository(db)
            catalog.saveBook(book); catalog.saveAccount(cash)
            block(db, book, cash)
        } finally { db.close() }
    }
    private fun service(db: LedgerDatabase) = BillImportService(db, ApplicationProvider.getApplicationContext<Context>())
    private val header = "occurred_at,kind,amount,currency,from_account,note\n"
    private val valid = "2026-10-11T12:00:00Z,EXPENSE,12.30,CNY,Cash,\"meal, tea\"\n"

    @Test fun previewReportsEveryInvalidRowWithoutWritingAnything() = runBlocking { withLedger { db, book, _ ->
        val csv = header + valid + "2026-10-11T12:00:00Z,EXPENSE,1,ZZZ,Cash,unknown\n" +
            "2026-02-30 10:00:00,EXPENSE,1,CNY,Cash,invalid date\n" +
            "2026-10-11T12:00:00Z,EXPENSE,0.001,CNY,Cash,precision\n"
        val preview = service(db).preview(csv.byteInputStream(), ExchangeFormat.CSV, book.id)
        assertEquals(4, preview.rows.size)
        assertTrue(preview.rows[0].selectable)
        assertTrue(preview.rows[1].issues.any { it.code == "unknown_currency" })
        assertTrue(preview.rows[2].issues.any { it.code == "invalid_date" })
        assertTrue(preview.rows[3].issues.any { it.field == "amount" })
        assertEquals(0, db.ledgerDao().billCount())
    } }

    @Test fun postingOverflowIsARowIssueAndDoesNotHideValidNeighboringRows() = runBlocking { withLedger { db, book, cash ->
        val table = ExchangeTable(listOf("occurred_at", "kind", "amount", "currency", "from_account", "fee"), listOf(
            TableRow(2, listOf("2026-10-11T12:00:00Z", "EXPENSE", "92233720368547758.07", "CNY", "Cash", "0.02")),
            TableRow(3, listOf("2026-10-11T12:00:00Z", "EXPENSE", "1.00", "CNY", "Cash", ""))))
        for (format in ExchangeFormat.entries) {
            val input = ByteArrayOutputStream().also { TableCodec.write(table, format, it) }.toByteArray()
            val importer = service(db)
            val preview = importer.preview(input.inputStream(), format, book.id)
            assertEquals(2, preview.rows.size)
            assertFalse(preview.rows[0].selectable)
            assertTrue(preview.rows[0].issues.any { it.field == "amount" && it.code == "overflow" })
            assertTrue(preview.rows[1].selectable)
            assertEquals(1, importer.commit(preview.previewId, ImportChoices(setOf(preview.rows[1].number))).imported)
        }
        assertEquals(3, db.ledgerDao().billCount())
        assertEquals(-300L, db.ledgerDao().account(cash.id)!!.balanceMinor)
    } }

    @Test fun selectedCommitIsAtomicAndRepeatFileNeverChargesAgainEvenAfterDeletion() = runBlocking { withLedger { db, book, cash ->
        val csv = header + valid + "invalid,EXPENSE,1,CNY,Cash,bad\n"
        val importer = service(db)
        val preview = importer.preview(csv.byteInputStream(), ExchangeFormat.CSV, book.id)
        assertEquals(2, preview.rows.size)
        assertTrue(runCatching { importer.commit(preview.previewId, ImportChoices(setOf(2, 3))) }.isFailure)
        assertEquals(0, db.ledgerDao().billCount())
        assertEquals(1, importer.commit(preview.previewId, ImportChoices(setOf(2))).imported)
        assertEquals(-1230L, db.ledgerDao().account(cash.id)!!.balanceMinor)
        val again = service(db).preview(csv.byteInputStream(), ExchangeFormat.CSV, book.id)
        assertTrue(again.rows.first().duplicate)
        assertEquals(0, importer.commit(again.previewId, ImportChoices(setOf(2))).imported)
        val bill = RoomLedgerRepository(db).observeBills(BillQuery()).first().single()
        RoomLedgerRepository(db).delete(bill.id)
        val afterDelete = importer.preview(csv.byteInputStream(), ExchangeFormat.CSV, book.id)
        assertTrue(afterDelete.rows.first().duplicate)
        assertEquals(0L, db.ledgerDao().account(cash.id)!!.balanceMinor)
    } }

    @Test fun mappingAndPreviewSurviveRestartButStaleAccountCannotPartiallyCommit() = runBlocking { withLedger { db, book, cash ->
        val importer = service(db)
        val preview = importer.preview((header + valid.replace("Cash", "Foreign") + valid).byteInputStream(), ExchangeFormat.CSV, book.id)
        assertEquals(2, preview.rows.size)
        assertTrue(preview.rows.first().issues.any { it.field == "fromAccountId" })
        val mapped = importer.remap(preview.previewId, preview.mapping.copy(accounts = mapOf("Foreign" to cash.id)))
        assertTrue(mapped.rows.all { it.selectable })
        assertEquals(mapped, service(db).preview(preview.previewId))
        db.ledgerDao().archiveAccount(cash.id)
        assertTrue(runCatching { importer.commit(preview.previewId, ImportChoices(setOf(2, 3))) }.isFailure)
        assertEquals(0, db.ledgerDao().billCount())
        assertEquals(0, db.ledgerDao().allImportReceipts().size)
    } }

    @Test fun filteredExportsReadBackExactAmountsCategoryAccountsAndFormulaText() = runBlocking { withLedger { db, book, cash ->
        val catalog = CatalogRepository(db)
        val category = Category(UUID.randomUUID().toString(), book.id, "Food", BillKind.EXPENSE)
        catalog.saveCategory(category)
        val ledger = RoomLedgerRepository(db)
        val draft = TransactionDraft(book.id, kind = BillKind.EXPENSE, amount = Money(1230, CurrencySpec.CNY),
            fromAccountId = cash.id, categoryId = category.id, occurredAt = Instant.parse("2026-10-11T12:00:00Z"),
            timeSource = TimeSource.USER, zoneId = ZoneOffset.UTC, note = "=SUM(1,2)\n中文", tags = listOf("comma,tag"),
            excludeBudget = true, fee = Money(1, CurrencySpec.CNY))
        assertTrue(ledger.save(draft, null) is SaveResult.Saved)
        assertTrue(ledger.save(draft.copy(submissionId = UUID.randomUUID().toString(), note = "not exported"), null) is SaveResult.Saved)
        ExchangeFormat.entries.forEach { format ->
            val output = ByteArrayOutputStream()
            assertEquals(1, BillExportService(db).export(BillQuery(bookId = book.id, search = "SUM"), format, output))
            val target = book.copy(id = UUID.randomUUID().toString(), name = "Target $format")
            catalog.saveBook(target)
            val targetCategory = category.copy(id = UUID.randomUUID().toString(), bookId = target.id)
            catalog.saveCategory(targetCategory)
            val importer = service(db)
            val preview = importer.preview(output.toByteArray().inputStream(), format, target.id)
            assertTrue(preview.toString(), preview.rows.single().selectable)
            importer.commit(preview.previewId, ImportChoices(setOf(2)))
            val actual = ledger.observeBills(BillQuery(bookId = target.id)).first().single()
            assertEquals(draft.amount, actual.amount)
            assertEquals(draft.note, actual.note)
            assertEquals(draft.tags, actual.tags)
            assertEquals(draft.fee, actual.fee)
            assertEquals(cash.id, actual.fromAccountId)
            assertEquals(targetCategory.id, actual.categoryId)
            assertTrue(actual.excludeBudget)
        }
    } }

    @Test fun rawImportReceiptsAndBatchUndoSurviveFullBackupReplacement() = runBlocking { withLedger { db, book, _ ->
        val importer = service(db)
        val preview = importer.preview((header + valid).byteInputStream(), ExchangeFormat.CSV, book.id)
        assertEquals(1, preview.rows.size)
        importer.commit(preview.previewId, ImportChoices(setOf(2)))
        val ledger = RoomLedgerRepository(db)
        val bill = ledger.observeBills(BillQuery()).first().single()
        val action = BatchActions(db).edit(mapOf(bill.id to bill.revision), BatchPatch(note = "Batch changed"))
        val archive = BackupArchive(db, ApplicationProvider.getApplicationContext())
        val bytes = ByteArrayOutputStream().also { archive.exportTo(it) }.toByteArray()
        assertTrue(BatchActions(db).undo(action.id))
        archive.restore(archive.inspect(bytes.inputStream()).previewId, RestoreMode.REPLACE)
        assertEquals(1, db.ledgerDao().allImportReceipts().size)
        assertEquals(1, db.ledgerDao().allBatchActions().size)
        val receipt = LedgerJson.format.decodeFromString<ImportReceipt>(db.ledgerDao().allImportReceipts().single().payloadJson)
        assertEquals("meal, tea", receipt.raw.last())
        assertTrue(BatchActions(db).undo(action.id))
        assertEquals("meal, tea", ledger.observeBill(bill.id).first()!!.note)
        assertTrue(importer.preview((header + valid).byteInputStream(), ExchangeFormat.CSV, book.id).rows.single().duplicate)
    } }

    @Test fun csvHandlesBomQuotedCommaNewlineQuotesAndEmptyFinalFields() {
        val input = "\uFEFFamount,note,account\r\n92233720368547758.07,\"餐饮,\"\"quoted\"\"\nnext line\",\r\n"
        val table = TableCodec.read(input.byteInputStream(), ExchangeFormat.CSV)
        assertEquals(listOf("amount", "note", "account"), table.columns)
        assertEquals(listOf("92233720368547758.07", "餐饮,\"quoted\"\nnext line", ""), table.rows.single().cells)
    }

    @Test fun allFormatsRoundTripLiteralFormulaTextAndExactMoneyOnAndroid() {
        val table = ExchangeTable(listOf("format", "amount", "note", "empty"), listOf(
            TableRow(2, listOf("OMyMoney-1", "92233720368547758.07", "=SUM(1,2)", "")),
            TableRow(3, listOf("OMyMoney-1", "0.01", "  @SUM(1)\n中文", "")),
            TableRow(4, listOf("OMyMoney-1", "1.00", "'literal", "")),
        ))
        ExchangeFormat.entries.forEach { format ->
            val bytes = ByteArrayOutputStream().also { TableCodec.write(table, format, it) }.toByteArray()
            assertEquals(format.name, table, TableCodec.read(bytes.inputStream(), format))
            if (format == ExchangeFormat.CSV) assertTrue(bytes.decodeToString().contains("'=SUM"))
        }
    }

    @Test fun oversizedRowsAreReportedWithoutLosingFollowingValidRows() {
        val input = "amount,note\n1,${"a".repeat(TableCodec.MAX_CELL + 1)}\n2,valid\n"
        val result = TableCodec.read(input.byteInputStream(), ExchangeFormat.CSV)
        assertEquals("cell_limit", result.rows.first().error)
        assertEquals(listOf("2", "valid"), result.rows.last().cells)
    }

    @Test fun corruptXlsxAndUnboundedCoordinatesAreRejectedBeforeAllocation() {
        fun reject(bytes: ByteArray) {
            val result = runCatching { TableCodec.read(bytes.inputStream(), ExchangeFormat.XLSX) }
            assertTrue(result.toString(), result.exceptionOrNull() is ExchangeException)
        }
        reject("not a workbook".toByteArray())
        val bytes = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write("<worksheet><sheetData><row r=\"1\"><c r=\"XFD2147483647\"/></row></sheetData></worksheet>".toByteArray())
            zip.closeEntry()
        } }.toByteArray()
        reject(bytes)
    }

    @Test fun missingSharedStringReferencesAreNotInventedAsEmptyCells() {
        val bytes = ByteArrayOutputStream().also { output -> Workbook(output, "Fixture", "1.0").use { workbook ->
            val sheet = workbook.newWorksheet("Data")
            sheet.value(0, 0, "note"); sheet.value(1, 0, "value")
        } }.toByteArray()
        val forged = rewriteXlsx(bytes) { name, text ->
            name to if (name.startsWith("xl/worksheets/")) text.replace("<v>1</v>", "<v>5000</v>") else text
        }
        val outcome = runCatching { TableCodec.read(forged.inputStream(), ExchangeFormat.XLSX) }
        assertTrue("A missing shared string must be rejected, not turned into blank text: $outcome", outcome.exceptionOrNull() is ExchangeException)
    }

    @Test fun jsonOversizedAndRaggedRowsHaveTheSamePerRowErrorsAsCsv() {
        val table = ExchangeTable(listOf("a", "b"), listOf(TableRow(2, listOf("a".repeat(TableCodec.MAX_CELL + 1), "b")),
            TableRow(3, listOf("a")), TableRow(4, listOf("ok", "ok"))))
        val result = TableCodec.read(LedgerJson.format.encodeToString(table).byteInputStream(), ExchangeFormat.JSON)
        assertEquals("cell_limit", result.rows[0].error)
        assertEquals("column_count", result.rows[1].error)
        assertNull(result.rows[2].error)
    }

    @Test fun modeledQianjiColumnsMapKnownBasicTypesWithoutGuessingUnsupportedFields() = runBlocking { withLedger { db, book, cash ->
        val catalog = CatalogRepository(db)
        catalog.saveCategory(Category(UUID.randomUUID().toString(), book.id, "Food", BillKind.EXPENSE))
        catalog.saveAccount(AssetAccount(UUID.randomUUID().toString(), "Bank"))
        catalog.saveAccount(AssetAccount(UUID.randomUUID().toString(), "Credit", kind = AccountKind.CREDIT))
        val input = InstrumentationRegistry.getInstrumentation().context.assets.open("exchange/qianji-modeled.csv").bufferedReader().use { it.readText() }
        val importer = service(db)
        val preview = importer.preview(input.byteInputStream(), ExchangeFormat.CSV, book.id)
        assertEquals("QIANJI_MODELED", preview.profile)
        assertEquals(4, preview.rows.size)
        assertTrue(preview.toString(), preview.rows.all { it.selectable })
        assertEquals(4, importer.commit(preview.previewId, ImportChoices(setOf(2, 3, 4, 5))).imported)
        assertEquals(3060L, db.ledgerDao().account(cash.id)!!.balanceMinor)
        val expense = RoomLedgerRepository(db).observeBills(BillQuery(kind = BillKind.EXPENSE)).first().single()
        assertEquals(listOf("日常", "茶"), expense.tags)
        assertTrue(expense.excludeBudget)
        val xlsx = ByteArrayOutputStream().also { TableCodec.write(TableCodec.read(input.byteInputStream(), ExchangeFormat.CSV), ExchangeFormat.XLSX, it) }.toByteArray()
        val xlsxPreview = importer.preview(xlsx.inputStream(), ExchangeFormat.XLSX, book.id)
        assertTrue(xlsxPreview.toString(), xlsxPreview.rows.all { it.selectable })
        assertEquals(listOf(BillKind.EXPENSE, BillKind.INCOME, BillKind.TRANSFER, BillKind.CREDIT_PAYMENT), xlsxPreview.rows.map { it.draft?.kind })
        val discounted = input.replace(",0.10,,User,", ",0.10,2.00,User,")
        assertTrue(importer.preview(discounted.byteInputStream(), ExchangeFormat.CSV, book.id).rows.first().issues.any { it.code == "unsupported_discount" })
    } }

    @Test fun receiptWriteFailureRollsBackBillsBalancesAndSubmissionsAndAllowsRetry() = runBlocking { withLedger { db, book, cash ->
        val importer = service(db)
        val preview = importer.preview((header + valid + valid.replace("12.30", "7.70")).byteInputStream(), ExchangeFormat.CSV, book.id)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_receipt BEFORE INSERT ON import_receipts BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertTrue(runCatching { importer.commit(preview.previewId, ImportChoices(setOf(2, 3))) }.isFailure)
        assertEquals(0, db.ledgerDao().billCount())
        assertEquals(0, db.ledgerDao().submissionCount())
        assertEquals(0, db.ledgerDao().allImportReceipts().size)
        assertEquals(0L, db.ledgerDao().account(cash.id)!!.balanceMinor)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_receipt")
        assertEquals(2, importer.commit(preview.previewId, ImportChoices(setOf(2, 3))).imported)
        assertEquals(-2000L, db.ledgerDao().account(cash.id)!!.balanceMinor)
    } }

    @Test fun allLocalKindsFxAndLinkedSourcesRoundTripWithoutOrderOrPrecisionLoss() = runBlocking { withLedger { db, book, cash ->
        val catalog = CatalogRepository(db); val ledger = RoomLedgerRepository(db)
        val bank = AssetAccount(UUID.randomUUID().toString(), "Bank")
        val debt = AssetAccount(UUID.randomUUID().toString(), "Debt", kind = AccountKind.LIABILITY)
        val credit = AssetAccount(UUID.randomUUID().toString(), "Credit", kind = AccountKind.CREDIT)
        val receivable = AssetAccount(UUID.randomUUID().toString(), "Receivable", kind = AccountKind.RECEIVABLE)
        listOf(bank, debt, credit, receivable).forEach { assertTrue(catalog.saveAccount(it) is CatalogResult.Saved) }
        suspend fun add(kind: BillKind, amount: Long, from: String? = cash.id, to: String? = null, link: String? = null,
            currency: CurrencySpec = CurrencySpec.CNY, exchange: ExchangeSnapshot? = null, fee: Money? = null): String {
            val saved = ledger.save(TransactionDraft(book.id, kind = kind, amount = Money(amount, currency), fromAccountId = from,
                toAccountId = to, linkedBillId = link, occurredAt = Instant.parse("2026-10-11T12:00:00Z"), timeSource = TimeSource.USER,
                zoneId = ZoneOffset.UTC, note = kind.name, exchange = exchange, fee = fee), null)
            assertTrue(saved.toString(), saved is SaveResult.Saved)
            return (saved as SaveResult.Saved).billId
        }
        val expense = add(BillKind.EXPENSE, 1000)
        add(BillKind.REFUND, 400, null, cash.id, expense)
        val reimb = add(BillKind.REIMBURSEMENT, 1200)
        add(BillKind.REIMBURSED, 500, null, cash.id, reimb)
        val borrowed = add(BillKind.DEBT_BORROW, 10000, debt.id, cash.id)
        add(BillKind.DEBT_REPAY, 2000, cash.id, debt.id, borrowed)
        add(BillKind.INTEREST_EXPENSE, 300, link = borrowed)
        add(BillKind.EXPENSE, 100, currency = CurrencySpec("USD", 2),
            exchange = ExchangeSnapshot(cash.id, Money(725, CurrencySpec.CNY), "7.25", LocalDate.parse("2026-10-11")), fee = Money(5, CurrencySpec.CNY))
        add(BillKind.TRANSFER, 600, cash.id, bank.id)
        add(BillKind.INCOME, 2500, null, cash.id)
        add(BillKind.CREDIT_PAYMENT, 800, cash.id, credit.id)
        val lent = add(BillKind.DEBT_LEND, 400, cash.id, receivable.id)
        add(BillKind.DEBT_COLLECT, 100, receivable.id, cash.id, lent)
        add(BillKind.INTEREST_INCOME, 50, null, cash.id, lent)
        add(BillKind.ADJUSTMENT, 60, null, cash.id)
        val expected = ledger.observeBills(BillQuery(bookId = book.id)).first()
        assertEquals(6580L, db.ledgerDao().account(cash.id)!!.balanceMinor)
        ExchangeFormat.entries.forEach { format ->
            val bytes = ByteArrayOutputStream().also { assertEquals(15, BillExportService(db).export(BillQuery(bookId = book.id), format, it)) }.toByteArray()
            val receiver = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LedgerDatabase::class.java).build()
            try {
                receiver.ledgerDao().upsertBook(book.entity())
                listOf(cash, bank, debt, credit, receivable).forEach { receiver.ledgerDao().upsertAccount(it.entity()) }
                val importer = service(receiver)
                val preview = importer.preview(bytes.inputStream(), format, book.id)
                assertTrue(preview.toString(), preview.rows.all { it.selectable })
                val childRow = preview.rows.first { it.draft?.kind == BillKind.REFUND }
                assertTrue(runCatching { importer.commit(preview.previewId, ImportChoices(setOf(childRow.number))) }.isFailure)
                assertEquals(0, receiver.ledgerDao().billCount())
                assertEquals(15, importer.commit(preview.previewId, ImportChoices(preview.rows.map { it.number }.toSet())).imported)
                val actual = RoomLedgerRepository(receiver).observeBills(BillQuery(bookId = book.id)).first()
                fun key(bill: Bill) = bill.kind to bill.amount.currency
                val beforeById = expected.associateBy { it.id }; val afterById = actual.associateBy { it.id }
                actual.forEach { bill ->
                    val original = expected.single { key(it) == key(bill) }
                    assertEquals(original.amount, bill.amount); assertEquals(original.fee, bill.fee); assertEquals(original.exchange, bill.exchange)
                    assertEquals(original.fromAccountId, bill.fromAccountId); assertEquals(original.toAccountId, bill.toAccountId)
                    assertEquals(original.linkedBillId?.let { beforeById[it]?.kind }, bill.linkedBillId?.let { afterById[it]?.kind })
                }
                assertEquals(6580L, receiver.ledgerDao().account(cash.id)!!.balanceMinor)
                assertEquals(-8000L, receiver.ledgerDao().account(debt.id)!!.balanceMinor)
                assertEquals(800L, receiver.ledgerDao().account(credit.id)!!.balanceMinor)
                assertEquals(600L, receiver.ledgerDao().account(bank.id)!!.balanceMinor)
                assertEquals(300L, receiver.ledgerDao().account(receivable.id)!!.balanceMinor)
            } finally { receiver.close() }
        }
    } }

    @Test fun cancelledAndExpiredPreviewsCannotCommitButDoNotTouchTheLedger() = runBlocking { withLedger { db, book, _ ->
        val context: Context = ApplicationProvider.getApplicationContext()
        val time = Instant.parse("2026-10-11T10:00:00Z")
        val importer = BillImportService(db, context, Clock.fixed(time, ZoneOffset.UTC))
        val preview = importer.preview((header + valid).byteInputStream(), ExchangeFormat.CSV, book.id)
        val expired = BillImportService(db, context, Clock.fixed(time.plus(Duration.ofDays(1)), ZoneOffset.UTC))
        assertEquals("expired_preview", (runCatching { expired.commit(preview.previewId, ImportChoices(setOf(2))) }.exceptionOrNull() as ExchangeException).code)
        importer.cancel(preview.previewId)
        assertEquals("invalid_preview", (runCatching { importer.preview(preview.previewId) }.exceptionOrNull() as ExchangeException).code)
        assertEquals(0, db.ledgerDao().billCount())
    } }

    @Test fun archiveRejectsForgedImportAndUndoReferencesAndCanMergeIntoEmptyLedger() = runBlocking { withLedger { db, book, _ ->
        val importer = service(db)
        val preview = importer.preview((header + valid).byteInputStream(), ExchangeFormat.CSV, book.id)
        importer.commit(preview.previewId, ImportChoices(setOf(2)))
        val bill = RoomLedgerRepository(db).observeBills(BillQuery()).first().single()
        val action = BatchActions(db).delete(mapOf(bill.id to bill.revision))
        val context: Context = ApplicationProvider.getApplicationContext()
        val bytes = ByteArrayOutputStream().also { BackupArchive(db, context).exportTo(it) }.toByteArray()
        val snapshot = java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            var result: LedgerSnapshot? = null
            while (true) { val entry = zip.nextEntry ?: break; if (entry.name == "data.json") result = LedgerJson.format.decodeFromString(zip.readBytes().decodeToString()) }
            requireNotNull(result)
        }
        val receipt = snapshot.importReceipts.single().model()
        val forged = listOf(
            snapshot.copy(importReceipts = listOf(receipt.copy(fileHash = "0".repeat(64)).entity())),
            snapshot.copy(importReceipts = listOf(receipt.copy(raw = emptyList()).entity())),
            snapshot.copy(importReceipts = listOf(receipt.copy(billId = UUID.randomUUID().toString()).entity())),
            snapshot.copy(batchActions = listOf(action.copy(afterRevisions = mapOf(bill.id to 99L)).entity())),
            snapshot.copy(batchActions = listOf(action.copy(before = listOf(bill.copy(amount = Money(2000, CurrencySpec.CNY)))).entity())),
            snapshot.copy(batchActions = listOf(action.copy(used = true).entity())),
            snapshot.copy(batchActions = listOf(action.copy(deletions = listOf(action.deletions.single().copy(id = UUID.randomUUID().toString()))).entity())),
        )
        assertEquals(emptyList<Int>(), forged.mapIndexedNotNull { index, value -> index.takeIf { runCatching { RestoreValidator.validate(value) }.isSuccess } })
        val receiver = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val archive = BackupArchive(receiver, context)
            archive.restore(archive.inspect(bytes.inputStream()).previewId, RestoreMode.MERGE)
            assertEquals(1, receiver.ledgerDao().allImportReceipts().size)
            assertTrue(BatchActions(receiver).undo(action.id))
            assertEquals("meal, tea", RoomLedgerRepository(receiver).observeBills(BillQuery()).first().single().note)
        } finally { receiver.close() }
    } }

    private fun rewriteXlsx(bytes: ByteArray, transform: (String, String) -> Pair<String, String>): ByteArray =
        ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { target ->
            ZipArchiveInputStream(bytes.inputStream()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val (name, text) = transform(entry.name, input.readBytes().decodeToString())
                    target.putNextEntry(ZipEntry(name)); target.write(text.toByteArray()); target.closeEntry()
                }
            }
        } }.toByteArray()
}
