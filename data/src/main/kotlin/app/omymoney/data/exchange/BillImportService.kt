package app.omymoney.data.exchange

import android.content.Context
import androidx.room.withTransaction
import app.omymoney.data.*
import app.omymoney.core.model.*
import app.omymoney.core.ledger.PostingCalculator
import app.omymoney.core.ledger.LinkedBillRules
import app.omymoney.core.ledger.DebtRules
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Currency
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable private data class ImportStaging(val preview: ImportPreview, val table: ExchangeTable,
    val format: ExchangeFormat, val fileHash: String)
@Serializable private data class ImportEnvelope(val payload: String, val hash: String)

class BillImportService(private val database: LedgerDatabase, context: Context, private val clock: Clock = Clock.systemUTC()) {
    private val directory = File(context.applicationContext.noBackupFilesDir, "import-previews")
    private val dao = database.ledgerDao()
    private val ledger = RoomLedgerRepository(database, clock)

    suspend fun preview(input: InputStream, format: ExchangeFormat, bookId: String): ImportPreview = guarded {
        requireUuid(bookId)
        val bytes = TableCodec.boundedBytes(input, TableCodec.MAX_BYTES)
        var error: String? = null
        val table = try { TableCodec.read(bytes.inputStream(), format) }
            catch (failure: ExchangeException) { error = failure.code; ExchangeTable(emptyList(), emptyList()) }
        val isQianji = table.columns == QianjiMapping.columns
        val mapping = if (isQianji) QianjiMapping.mapping else ImportMapping(ImportField.entries.mapNotNull { field ->
            table.columns.indexOf(field.column).takeIf { it >= 0 }?.let { field to it }
        }.toMap())
        val base = ImportPreview(UUID.randomUUID().toString(), bookId, table.columns, mapping, emptyList(),
            clock.instant().plus(Duration.ofDays(1)), when { isQianji -> "QIANJI_MODELED"; table.columns.firstOrNull() == "format" -> "OMYMONEY"; else -> "CUSTOM" }, error)
        val staged = ImportStaging(base, table, format, sha256(bytes))
        val result = database.withTransaction { build(staged, mapping) }
        directory.mkdirs()
        directory.listFiles()?.filter { clock.millis() - it.lastModified() > Duration.ofDays(1).toMillis() }?.forEach { it.delete() }
        write(staged.copy(preview = result))
        result
    }
    suspend fun preview(previewId: String): ImportPreview = guarded {
        val stage = read(previewId)
        database.withTransaction { withDuplicates(stage) }
    }
    suspend fun remap(previewId: String, mapping: ImportMapping): ImportPreview = guarded {
        val stage = read(previewId)
        val result = database.withTransaction { build(stage, mapping) }
        write(stage.copy(preview = result))
        result
    }
    suspend fun commit(previewId: String, choices: ImportChoices): ImportResult = guarded {
        val stage = read(previewId)
        val result = database.withTransaction {
            if (stage.preview.fatalError != null || choices.rows.isEmpty()) throw ExchangeException("empty_selection")
            val preview = withDuplicates(stage)
            val selected = preview.rows.filter { it.number in choices.rows }
            if (selected.size != choices.rows.size || selected.any { !it.duplicate && !it.selectable }) throw ExchangeException("invalid_selection")
            val pending = selected.filterNot { it.duplicate }
            if (pending.isNotEmpty()) {
                ledger.saveAll(pending.map { requireNotNull(it.draft) to null })
                pending.forEach { row ->
                    val receipt = ImportReceipt(receiptId(preview.bookId, stage.fileHash, row.number), requireNotNull(row.draft?.id), preview.bookId,
                        stage.fileHash, stage.format, row.number, stage.table.columns, row.raw, clock.instant())
                    dao.insertImportReceipt(receipt.entity())
                }
            }
            ImportResult(pending.size, selected.size - pending.size)
        }
        // A repeated confirmation after process death is harmless: receipts remain authoritative.
        result
    }
    suspend fun cancel(previewId: String) = guarded { file(previewId).delete(); Unit }

    private suspend fun withDuplicates(stage: ImportStaging) = stage.preview.copy(rows = stage.preview.rows.map { row ->
        row.copy(duplicate = dao.importReceipt(receiptId(stage.preview.bookId, stage.fileHash, row.number)) != null ||
            row.draft?.id?.let { dao.bill(it) } != null)
    })

    private suspend fun build(stage: ImportStaging, mapping: ImportMapping): ImportPreview {
        val preview = stage.preview
        val book = dao.book(preview.bookId)?.model()?.takeUnless { it.archived } ?: throw ExchangeException("unavailable_book")
        if (mapping.columns.values.any { it !in stage.table.columns.indices }) throw ExchangeException("invalid_mapping")
        val accounts = dao.accounts().map(AccountEntity::model)
        val categories = dao.categories(book.id).map(CategoryEntity::model)
        val existing = dao.allBills()
        val existingIds = existing.associateBy { it.id }
        val receipts = dao.allImportReceipts().map { it.id }.toSet()
        fun value(row: TableRow, field: ImportField): String = if (preview.profile == "QIANJI_MODELED") QianjiMapping.value(field, row.cells, mapping)
            else mapping.columns[field]?.let { row.cells.getOrNull(it) }.orEmpty()
        fun ownId(source: String): String {
            requireUuid(source)
            return source.takeIf { existingIds[it]?.bookId == book.id }
                ?: UUID.nameUUIDFromBytes("OMyMoney-1|${book.id}|$source".toByteArray()).toString()
        }
        val sourceIds = stage.table.rows.map { value(it, ImportField.SOURCE_ID) }.filter { it.isNotEmpty() }
        val duplicateSources = sourceIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val ids = stage.table.rows.associate { row ->
            val source = value(row, ImportField.SOURCE_ID)
            row.number to if (preview.profile == "OMYMONEY" && source.isNotBlank() && canonicalUuid(source)) ownId(source)
                else receiptId(book.id, stage.fileHash, row.number)
        }
        val links = stage.table.rows.filter { value(it, ImportField.SOURCE_ID).isNotBlank() }
            .associate { value(it, ImportField.SOURCE_ID) to ids.getValue(it.number) }
        var rows = stage.table.rows.map { row ->
            val issues = mutableListOf<ImportIssue>()
            fun issue(field: String, code: String) { issues += ImportIssue(field, code) }
            fun <T> parse(field: String, code: String, block: () -> T): T? = try { block() } catch (_: IllegalArgumentException) { issue(field, code); null }
                catch (_: DateTimeException) { issue(field, code); null }
            fun get(field: ImportField) = value(row, field)
            fun currency(code: String, scale: String, field: String): CurrencySpec? {
                mapping.currencies[code]?.let { return it }
                return parse(field, "unknown_currency") {
                    require(Currency.getAvailableCurrencies().any { it.currencyCode == code })
                    val known = Currency.getInstance(code)
                    val digits = if (scale.isBlank()) known.defaultFractionDigits else scale.toInt()
                    require(known.defaultFractionDigits >= 0)
                    CurrencySpec(known.currencyCode, digits)
                }
            }
            fun account(name: String, field: String): String? {
                if (name.isEmpty()) return null
                val mapped = mapping.accounts[name]
                val matches = accounts.filter { !it.archived && if (mapped != null) it.id == mapped else it.name == name }
                return matches.singleOrNull()?.id ?: run { issue(field, "unavailable_account"); null }
            }
            row.error?.let { issue("row", it) }
            if (preview.profile == "QIANJI_MODELED") issues += QianjiMapping.issues(row.cells)
            if (row.cells.size != stage.table.columns.size) issue("row", "column_count")
            if (row.cells.any { it.length > TableCodec.MAX_CELL }) issue("row", "cell_limit")
            if (get(ImportField.SOURCE_ID) in duplicateSources) issue("sourceId", "duplicate_source")
            if (preview.profile == "OMYMONEY" && (row.cells.firstOrNull() != "OMyMoney-1" || !canonicalUuid(get(ImportField.SOURCE_ID)))) issue("row", "unsupported_format")
            val kind = parse("kind", "unsupported_kind") { BillKind.valueOf(get(ImportField.KIND)) }
            val unit = currency(get(ImportField.CURRENCY), get(ImportField.SCALE), "currency")
            val amount = unit?.let { parse("amount", "invalid_amount") { Money.parse(get(ImportField.AMOUNT), it) } }
            val zone = parse("zoneId", "invalid_zone") { get(ImportField.ZONE).takeIf { it.isNotBlank() }?.let(ZoneId::of) ?: book.zoneId }
            val time = zone?.let { parse("occurredAt", "invalid_date") { parseTime(get(ImportField.TIME), it) } }
            val from = account(get(ImportField.FROM), "fromAccountId")
            val to = account(get(ImportField.TO), "toAccountId")
            val categoryName = get(ImportField.CATEGORY)
            val categoryKind = PostingCalculator.categoryKind(kind)
            val category = if (categoryName.isEmpty()) null else {
                val mapped = mapping.categories["${categoryKind?.name}|$categoryName"]
                categories.filter { item -> !item.archived && item.kind == categoryKind &&
                    if (mapped != null) item.id == mapped else categoryPath(item, categories) == categoryName
                }.singleOrNull()?.id ?: run { issue("categoryId", "unavailable_category"); null }
            }
            val tags = if (get(ImportField.TAGS).isBlank()) emptyList() else parse("tags", "invalid_tags") {
                LedgerJson.format.decodeFromString<List<String>>(get(ImportField.TAGS)).also { tags ->
                    require(tags.size <= 100 && tags.all { it.isNotBlank() && it.length <= 80 })
                }
            }.orEmpty()
            fun flag(field: ImportField): Boolean = when (get(field)) { "", "false" -> false; "true" -> true; else -> { issue(field.column, "invalid_flag"); false } }
            var exchange: ExchangeSnapshot? = null
            val fxFields = listOf(ImportField.FX_ACCOUNT, ImportField.FX_CURRENCY, ImportField.FX_SCALE, ImportField.FX_AMOUNT, ImportField.FX_RATE, ImportField.FX_DATE)
            if (fxFields.any { get(it).isNotEmpty() }) {
                val fxAccount = account(get(ImportField.FX_ACCOUNT), "exchange")
                val fxUnit = currency(get(ImportField.FX_CURRENCY), get(ImportField.FX_SCALE), "exchange")
                val fxAmount = fxUnit?.let { parse("exchange", "invalid_amount") { Money.parse(get(ImportField.FX_AMOUNT), it) } }
                val fxDate = parse("exchange", "invalid_date") { LocalDate.parse(get(ImportField.FX_DATE)) }
                if (fxAccount != null && fxAmount != null && fxDate != null) exchange = ExchangeSnapshot(fxAccount, fxAmount, get(ImportField.FX_RATE), fxDate)
                else issue("exchange", "incomplete_exchange")
            }
            val link = get(ImportField.LINK).takeIf { it.isNotEmpty() }?.let { source -> links[source]
                ?: if (preview.profile == "OMYMONEY" && canonicalUuid(source)) ownId(source).takeIf { it in existingIds } else null
                ?: run { issue("linkedBillId", "invalid_link"); null } }
            var draft = if (kind != null && amount != null && time != null && zone != null) TransactionDraft(
                book.id, id = ids.getValue(row.number), submissionId = receiptId(book.id, stage.fileHash, row.number),
                kind = kind, amount = amount, fromAccountId = from, toAccountId = to, categoryId = category,
                occurredAt = time, zoneId = zone, timeSource = TimeSource.IMPORT, source = BillSource.IMPORT,
                note = get(ImportField.NOTE), tags = tags, exchange = exchange, linkedBillId = link,
                excludeStats = flag(ImportField.EXCLUDE_STATS), excludeBudget = flag(ImportField.EXCLUDE_BUDGET),
            ) else null
            if (draft != null && get(ImportField.FEE).isNotBlank()) {
                val feeAccount = app.omymoney.core.ledger.ExchangeRules.feeAccount(draft)
                val feeUnit = accounts.firstOrNull { it.id == feeAccount }?.currency
                val fee = feeUnit?.let { parse("fee", "invalid_amount") { Money.parse(get(ImportField.FEE), it) } }
                if (fee == null) issue("fee", "invalid_amount") else draft = draft.copy(fee = fee)
            }
            draft?.let { candidate ->
                try {
                    PostingCalculator.calculate(candidate).forEach { posting ->
                        if (accounts.firstOrNull { it.id == posting.accountId }?.currency != posting.amount.currency) issue("amount", "currency_mismatch")
                    }
                    DebtRules.validateAccounts(candidate, accounts).forEach { issue(it.field, it.code) }
                } catch (error: DraftValidationException) { error.errors.forEach { issue(it.field, it.code) } }
                catch (_: ArithmeticException) { issue("amount", "overflow") }
            }
            ImportRow(row.number, row.cells, draft, issues.distinct(), receiptId(book.id, stage.fileHash, row.number) in receipts || ids.getValue(row.number) in existingIds)
        }
        val proposed = existing.filter { it.deletedAt == null }.map(LedgerJson::decodeBill) +
            rows.filter { it.selectable }.map { requireNotNull(it.draft).recorded(requireNotNull(it.draft?.id), 1, clock.instant()) }
        rows = rows.map { row -> if (!row.selectable) row else row.copy(issues = LinkedBillRules.validate(requireNotNull(row.draft), proposed).map { ImportIssue(it.field, it.code) }) }
        return preview.copy(mapping = mapping, rows = rows)
    }

    private fun parseTime(value: String, zone: ZoneId): Instant {
        runCatching { return Instant.parse(value) }
        runCatching { return OffsetDateTime.parse(value).toInstant() }
        val local = LocalDateTime.parse(value, DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT))
        val offsets = zone.rules.getValidOffsets(local)
        require(offsets.size == 1) { "Ambiguous or missing local time" }
        return local.toInstant(offsets.single())
    }
    private fun read(id: String): ImportStaging {
        val file = file(id)
        if (!file.isFile || file.length() > 4L * TableCodec.MAX_BYTES) throw ExchangeException("invalid_preview")
        val envelope = LedgerJson.format.decodeFromString<ImportEnvelope>(file.readText())
        if (sha256(envelope.payload.toByteArray()) != envelope.hash) throw ExchangeException("preview_changed")
        val stage = LedgerJson.format.decodeFromString<ImportStaging>(envelope.payload)
        if (stage.preview.previewId != id || !clock.instant().isBefore(stage.preview.expiresAt)) throw ExchangeException("expired_preview")
        return stage
    }
    private fun write(stage: ImportStaging) {
        val payload = LedgerJson.format.encodeToString(stage)
        val bytes = LedgerJson.format.encodeToString(ImportEnvelope(payload, sha256(payload.toByteArray()))).toByteArray()
        if (bytes.size > 4 * TableCodec.MAX_BYTES) throw ExchangeException("file_limit")
        val target = file(stage.preview.previewId)
        val part = File(directory, "${stage.preview.previewId}.part")
        try {
            part.outputStream().use { it.write(bytes); it.fd.sync() }
            if (!part.renameTo(target)) throw ExchangeException("storage")
        } finally { part.delete() }
    }
    private fun file(id: String): File { requireUuid(id); return File(directory, "$id.json") }
    private suspend fun <T> guarded(block: suspend () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }
    companion object {
        private val mutex = Mutex()
        internal fun canonicalUuid(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
        private fun requireUuid(value: String) { if (!canonicalUuid(value)) throw ExchangeException("invalid_id") }
        internal fun receiptId(bookId: String, hash: String, row: Int) = UUID.nameUUIDFromBytes("$bookId|$hash|$row".toByteArray()).toString()
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun categoryPath(category: Category, categories: List<Category>) = listOfNotNull(category.parentId?.let { id -> categories.firstOrNull { it.id == id }?.name }, category.name).joinToString(" / ")
    }
}

internal fun ImportReceipt.entity() = ImportReceiptEntity(id, billId, LedgerJson.format.encodeToString(this))
internal fun ImportReceiptEntity.model() = LedgerJson.format.decodeFromString<ImportReceipt>(payloadJson)
