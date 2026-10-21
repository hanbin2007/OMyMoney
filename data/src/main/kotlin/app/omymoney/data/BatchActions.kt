@file:kotlinx.serialization.UseSerializers(app.omymoney.core.model.InstantSerializer::class)

package app.omymoney.data

import app.omymoney.core.model.*
import app.omymoney.core.ledger.LedgerMutationException
import androidx.room.withTransaction
import java.time.Clock
import java.time.Instant
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

data class BatchPatch(val note: String? = null, val tags: List<String>? = null,
    val changeCategory: Boolean = false, val categoryId: String? = null,
    val excludeStats: Boolean? = null, val excludeBudget: Boolean? = null)

@Serializable data class BatchAction(val id: String, val bookId: String, val expiresAt: Instant,
    val before: List<Bill>, val afterRevisions: Map<String, Long>, val deletions: List<UndoToken> = emptyList(),
    val used: Boolean = false)

class BatchActions(private val database: LedgerDatabase, private val clock: Clock = Clock.systemUTC()) {
    private val dao = database.ledgerDao()
    private val ledger = RoomLedgerRepository(database, clock)
    suspend fun edit(expected: Map<String, Long>, patch: BatchPatch): BatchAction = database.withTransaction {
        require(patch != BatchPatch())
        require(patch.note == null || patch.note.length <= 32_768)
        require(patch.tags == null || (patch.tags.size <= 100 && patch.tags.all { it.isNotBlank() && it.length <= 80 }))
        val before = selected(expected)
        val result = ledger.saveAll(before.map { bill -> bill.toDraft().copy(
            note = patch.note ?: bill.note, tags = patch.tags ?: bill.tags,
            categoryId = if (patch.changeCategory) patch.categoryId else bill.categoryId,
            excludeStats = patch.excludeStats ?: bill.excludeStats, excludeBudget = patch.excludeBudget ?: bill.excludeBudget,
        ) to bill.revision })
        persist(before, result.associate { it.billId to it.revision })
    }
    suspend fun delete(expected: Map<String, Long>): BatchAction = database.withTransaction {
        val before = selected(expected)
        val tokens = ledger.deleteAll(expected)
        persist(before, before.associate { it.id to Math.addExact(it.revision, 1) }, tokens)
    }
    suspend fun undo(id: String): Boolean = database.withTransaction {
        val action = dao.batchAction(id)?.model() ?: return@withTransaction false
        if (action.used || !clock.instant().isBefore(action.expiresAt)) return@withTransaction false
        if (action.afterRevisions.any { (billId, revision) -> dao.bill(billId)?.revision != revision }) return@withTransaction false
        if (action.deletions.isNotEmpty()) {
            if (!ledger.undoAll(action.deletions)) return@withTransaction false
        } else {
            if (action.before.any { dao.bill(it.id)?.deletedAt != null }) return@withTransaction false
            ledger.saveAll(action.before.map { it.toDraft() to action.afterRevisions.getValue(it.id) })
        }
        dao.upsertBatchAction(action.copy(used = true).entity())
        true
    }
    fun observeActions(bookId: String): Flow<List<BatchAction>> = dao.observeBatchActions(bookId).map { rows -> rows.map { it.model() } }
    private suspend fun selected(expected: Map<String, Long>): List<Bill> {
        require(expected.size in 1..10_000)
        val bills = expected.map { (id, revision) ->
            val entity = dao.bill(id) ?: throw LedgerMutationException("not_found")
            if (entity.deletedAt != null || entity.revision != revision) throw LedgerMutationException("conflict")
            LedgerJson.decodeBill(entity)
        }
        require(bills.map { it.bookId }.distinct().size == 1)
        return bills
    }
    private suspend fun persist(before: List<Bill>, revisions: Map<String, Long>, tokens: List<UndoToken> = emptyList()): BatchAction {
        val action = BatchAction(UUID.randomUUID().toString(), before.first().bookId, clock.instant().plus(Duration.ofDays(1)), before, revisions, tokens)
        dao.upsertBatchAction(action.entity())
        return action
    }
}

internal fun BatchAction.entity() = BatchActionEntity(id, bookId, expiresAt.toString(), LedgerJson.format.encodeToString(this))
internal fun BatchActionEntity.model() = LedgerJson.format.decodeFromString<BatchAction>(payloadJson)
