package app.omymoney.data

import app.omymoney.core.model.*
import androidx.room.withTransaction
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.combine

data class AssetHistoryPoint(val date: LocalDate?, val balance: Money, val change: BigInteger)

class AssetHistoryRepository(private val database: LedgerDatabase) {
    private val dao = database.ledgerDao()

    suspend fun snapshot(asOf: Instant? = null): List<AssetAccount> = database.withTransaction {
        val active = dao.allBills().filter { it.deletedAt == null &&
            (asOf == null || !Instant.ofEpochSecond(it.occurredAtSeconds, it.occurredAtNanos.toLong()).isAfter(asOf)) }.map { it.id }.toSet()
        val totals = dao.allPostings().filter { it.billId in active }.groupBy { it.accountId }
            .mapValues { (_, rows) -> rows.fold(BigInteger.ZERO) { sum, row -> sum + BigInteger.valueOf(row.minor) } }
        dao.accounts().map { row ->
            val total = BigInteger.valueOf(row.openingMinor) + (totals[row.id] ?: BigInteger.ZERO)
            row.copy(balanceMinor = BigDecimal(total).longValueExact()).model()
        }
    }

    fun observeHistory(accountId: String, zone: ZoneId) = combine(dao.observeAccounts(true),
        dao.observeBills(null, accountId, null, null, "")) { _, _ -> runCatching { history(accountId, zone) } }

    suspend fun history(accountId: String, zone: ZoneId): List<AssetHistoryPoint> = database.withTransaction {
        val account = requireNotNull(dao.account(accountId)).model()
        val dates = dao.allBills().filter { it.deletedAt == null }.associate { it.id to
            Instant.ofEpochSecond(it.occurredAtSeconds, it.occurredAtNanos.toLong()).atZone(zone).toLocalDate() }
        val changes = dao.allPostings().filter { it.accountId == accountId && it.billId in dates }
            .groupBy { dates.getValue(it.billId) }.toSortedMap()
        var balance = BigInteger.valueOf(account.openingBalance.minor)
        buildList {
            add(AssetHistoryPoint(null, account.openingBalance, BigInteger.ZERO))
            changes.forEach { (date, postings) ->
                val delta = postings.fold(BigInteger.ZERO) { sum, row -> sum + BigInteger.valueOf(row.minor) }
                balance += delta
                add(AssetHistoryPoint(date, Money(BigDecimal(balance).longValueExact(), account.currency), delta))
            }
        }
    }
}
