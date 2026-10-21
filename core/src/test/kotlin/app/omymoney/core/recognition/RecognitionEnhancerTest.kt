package app.omymoney.core.recognition

import app.omymoney.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecognitionEnhancerTest {
    private val draft = TransactionDraft("book", kind = BillKind.EXPENSE, amount = Money(1230, CurrencySpec.CNY))
    private val initial = EnhancementState(draft, 7, setOf("cash", "bank"), setOf("food"))
    private val proof = listOf(FieldEvidence("amount", listOf(1), "12.30"),
        FieldEvidence("merchant", listOf(2), "Lunch cafe"), FieldEvidence("accountHint", listOf(3), "Cash"))
    private fun suggestion(field: EnhancementField, value: EnhancementValue, index: Int = 1, quote: String = "Lunch cafe") =
        FieldSuggestion(field, value, index, quote)
    private fun category(id: String = "food") = suggestion(EnhancementField.CATEGORY, EnhancementValue.CandidateId(id))
    private fun result(vararg fields: FieldSuggestion, version: Long = 7) = EnhancementResult.Suggestions(version, fields.toList())
    private fun apply(vararg fields: FieldSuggestion, state: EnhancementState = initial) =
        EnhancementValidator.apply(EnhancementRequest(initial, proof), state, result(*fields))

    @Test fun missingProviderPreservesEveryFieldAndNeverUsesNetwork() = runTest {
        val state = initial.copy(draft = draft.copy(note = "personal", tags = listOf("tag"), excludeStats = true))
        val request = EnhancementRequest(state, proof)
        assertEquals(EnhancementResult.Unavailable, NoOpRecognitionEnhancer().enhance(request))
        assertEquals(state.draft, EnhancementCoordinator().enhance(request) { state }.draft)
    }
    @Test fun groundedAllowedSuggestionsAreAppliedOnlyToReturnedCopy() {
        val applied = apply(category(), suggestion(EnhancementField.FROM_ACCOUNT, EnhancementValue.CandidateId("cash"), 2, "Cash"))
        assertEquals(EnhancementStatus.VALIDATED, applied.status)
        assertEquals("food", applied.draft.categoryId)
        assertEquals("cash", applied.draft.fromAccountId)
        assertNull(draft.categoryId)
        assertNull(draft.fromAccountId)
    }
    @Test fun invalidIdsTypesQuotesAndDuplicateFieldsAreRejected() {
        val cases = listOf(category("missing"), category().copy(evidenceIndex = -1), category().copy(quote = "invented"),
            category().copy(value = EnhancementValue.Text("food")),
            suggestion(EnhancementField.FROM_ACCOUNT, EnhancementValue.CandidateId("missing"), 2, "Cash"),
            suggestion(EnhancementField.TO_ACCOUNT, EnhancementValue.CandidateId("cash"), 2, "Cash"),
            suggestion(EnhancementField.NOTE, EnhancementValue.Text("invented")))
        cases.forEach { field ->
            val applied = apply(field)
            assertEquals(draft, applied.draft)
            assertEquals(setOf(field.field), applied.rejected)
        }
        assertEquals(setOf(EnhancementField.CATEGORY), apply(category(), category()).rejected)
        assertNull(apply(category(), state = initial.copy(allowedCategoryIds = emptySet())).draft.categoryId)
    }
    @Test fun unsupportedContradictoryAndAmbiguousAmountsStayUnchanged() {
        val valid = suggestion(EnhancementField.AMOUNT, EnhancementValue.Amount(Money(1230, CurrencySpec.CNY)), 0, "12.30")
        assertEquals(setOf(EnhancementField.AMOUNT), apply(valid).accepted)
        val contradictory = valid.copy(value = EnhancementValue.Amount(Money(9999, CurrencySpec.CNY)))
        assertEquals(setOf(EnhancementField.AMOUNT), apply(contradictory).rejected)
        val unknown = initial.copy(draft = draft.copy(amount = null))
        val request = EnhancementRequest(unknown, proof + FieldEvidence("amount", listOf(4), "99.99"))
        assertNull(EnhancementValidator.apply(request, unknown, result(valid)).draft.amount)
        assertEquals(setOf(EnhancementField.AMOUNT), apply(valid.copy(quote = "2.30")).rejected)
    }
    @Test fun staleVersionIdentityAndManualChangesAlwaysWin() {
        val request = EnhancementRequest(initial, proof)
        assertEquals(EnhancementStatus.STALE, EnhancementValidator.apply(request, initial.copy(version = 8), result(category())).status)
        assertEquals(EnhancementStatus.STALE, EnhancementValidator.apply(request, initial, result(category(), version = 6)).status)
        val other = initial.copy(draft = draft.copy(submissionId = "other"))
        assertEquals(other.draft, EnhancementValidator.apply(request, other, result(category())).draft)
        assertEquals(setOf(EnhancementField.CATEGORY), apply(category(), state = initial.copy(manuallyEdited = setOf(EnhancementField.CATEGORY))).rejected)
        val changed = initial.copy(draft = draft.copy(categoryId = "manual"))
        assertEquals("manual", apply(category(), state = changed).draft.categoryId)
    }
    @Test fun strictAdapterRejectsMalformedUnknownAndOversizedResponses() {
        val valid = Json.encodeToString(result(category()))
        assertEquals(result(category()), EnhancementValidator.decode(valid))
        listOf("not JSON", valid.replace("CandidateId", "Unknown"), valid.replace("\"food\"", "42"),
            valid.dropLast(1) + ",\"saveNow\":true}", " ".repeat(65_537)).forEach {
            assertEquals(EnhancementResult.Failed(EnhancementFailure.INVALID_RESPONSE), EnhancementValidator.decode(it))
        }
    }
    @Test fun timeoutFailureAndUnavailableReturnLatestDraftAndCancellationPropagates() = runTest {
        var current = initial
        val request = EnhancementRequest(initial, proof, timeoutMillis = 25)
        val slow = EnhancementCoordinator { delay(100); result(category()) }
        val pending = async { slow.enhance(request) { current } }
        runCurrent()
        current = initial.copy(draft = draft.copy(note = "newer user edit"), version = 8)
        advanceUntilIdle()
        assertEquals(EnhancementStatus.TIMED_OUT, pending.await().status)
        assertEquals(current.draft, pending.await().draft)
        val failed = EnhancementCoordinator { error("provider private details") }.enhance(request) { current }
        assertEquals(EnhancementStatus.FAILED, failed.status)
        assertEquals(current.draft, failed.draft)
        val cancelled = async { EnhancementCoordinator { throw CancellationException("stop") }.enhance(request) { current } }
        runCurrent()
        assertTrue(cancelled.isCancelled)
        val late = EnhancementCoordinator { delay(1); result(category()) }.enhance(request) { current }
        assertEquals(EnhancementStatus.STALE, late.status)
    }
    @Test fun requestIsBoundedRedactedMinimizedAndDetachedFromMutableInputs() {
        val nodes = mutableListOf(1)
        val evidence = mutableListOf(FieldEvidence("accountHint", nodes, "6222 0000 1234 5678"),
            FieldEvidence("orderId", listOf(2), "do not send order id"))
        val accounts = mutableSetOf("cash")
        val request = EnhancementRequest(initial.copy(allowedAccountIds = accounts), evidence)
        nodes += 2; accounts += "injected"; evidence.clear()
        assertEquals(listOf(1), request.evidence.single().nodeIndices)
        assertEquals("****5678", request.evidence.single().rawText)
        assertEquals(setOf("cash"), request.allowedAccountIds)
        assertThrows(IllegalArgumentException::class.java) { EnhancementRequest(initial, proof, 0) }
        assertThrows(IllegalArgumentException::class.java) { EnhancementRequest(initial, proof, 30_001) }
        assertThrows(IllegalArgumentException::class.java) { EnhancementRequest(initial, List(257) { proof[0] }) }
    }
}
