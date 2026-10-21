package app.omymoney.data.exchange

import app.omymoney.core.model.*
import app.omymoney.data.*
import androidx.room.withTransaction
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

class BillExportService(private val database: LedgerDatabase) {
    suspend fun export(query: BillQuery, format: ExchangeFormat, output: OutputStream): Int = withContext(Dispatchers.IO) {
        val table = database.withTransaction {
            val dao = database.ledgerDao()
            val bills = RoomLedgerRepository(database).observeBills(query).first()
            if (bills.size > TableCodec.MAX_ROWS) throw ExchangeException("row_limit")
            val accounts = dao.accounts().associateBy { it.id }
            val categories = dao.allCategories().associateBy { it.id }
            fun account(id: String?) = id?.let { accounts[it]?.name ?: throw ExchangeException("missing_account") }.orEmpty()
            val rows = bills.mapIndexed { index, bill ->
                val category = bill.categoryId?.let { categories[it] ?: throw ExchangeException("missing_category") }
                val path = category?.let { listOfNotNull(it.parentId?.let { id -> categories[id]?.name }, it.name).joinToString(" / ") }.orEmpty()
                val values = mapOf(
                    ImportField.SOURCE_ID to bill.id, ImportField.TIME to bill.occurredAt.toString(), ImportField.ZONE to bill.zoneId.id,
                    ImportField.KIND to bill.kind.name, ImportField.AMOUNT to bill.amount.decimalText(),
                    ImportField.CURRENCY to bill.amount.currency.code, ImportField.SCALE to bill.amount.currency.scale.toString(),
                    ImportField.FROM to account(bill.fromAccountId), ImportField.TO to account(bill.toAccountId),
                    ImportField.CATEGORY to path, ImportField.NOTE to bill.note, ImportField.TAGS to LedgerJson.format.encodeToString(bill.tags),
                    ImportField.FEE to bill.fee?.decimalText().orEmpty(), ImportField.EXCLUDE_STATS to bill.excludeStats.toString(),
                    ImportField.EXCLUDE_BUDGET to bill.excludeBudget.toString(), ImportField.LINK to bill.linkedBillId.orEmpty(),
                    ImportField.FX_ACCOUNT to account(bill.exchange?.accountId), ImportField.FX_CURRENCY to bill.exchange?.amount?.currency?.code.orEmpty(),
                    ImportField.FX_SCALE to bill.exchange?.amount?.currency?.scale?.toString().orEmpty(),
                    ImportField.FX_AMOUNT to bill.exchange?.amount?.decimalText().orEmpty(), ImportField.FX_RATE to bill.exchange?.rate.orEmpty(),
                    ImportField.FX_DATE to bill.exchange?.asOf?.toString().orEmpty(),
                )
                TableRow(index + 2, listOf("OMyMoney-1") + ImportField.entries.map { values.getValue(it) })
            }
            ExchangeTable(listOf("format") + ImportField.entries.map { it.column }, rows)
        }
        if (table.rows.any { row -> row.cells.any { it.length > TableCodec.MAX_CELL } }) throw ExchangeException("cell_limit")
        TableCodec.write(table, format, output)
        table.rows.size
    }
}
