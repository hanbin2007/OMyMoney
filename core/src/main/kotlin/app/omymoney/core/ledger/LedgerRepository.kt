package app.omymoney.core.ledger

import app.omymoney.core.model.*
import kotlinx.coroutines.flow.Flow

interface LedgerRepository {
    suspend fun save(draft: TransactionDraft, expectedRevision: Long?): SaveResult
    fun observeBills(query: BillQuery): Flow<List<Bill>>
    fun observeBill(id: String): Flow<Bill?>
    suspend fun delete(billId: String): UndoToken
    suspend fun undo(token: UndoToken): Boolean
}

class LedgerMutationException(val code: String, cause: Throwable? = null) : Exception(code, cause)
