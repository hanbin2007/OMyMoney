package app.omymoney.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.omymoney.core.model.*
import app.omymoney.core.recognition.*
import app.omymoney.data.backup.*
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import app.omymoney.core.diagnostics.DiagnosticSink
import kotlinx.serialization.json.*

@RunWith(AndroidJUnit4::class)
class RecognitionQueueTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "queue-${UUID.randomUUID()}.db"
    private val time = Instant.parse("2026-10-11T10:00:00Z")
    private val clock = Clock.fixed(time, ZoneOffset.UTC)
    private lateinit var db: LedgerDatabase
    private lateinit var queue: RecognitionQueue
    private lateinit var draft: TransactionDraft
    @Before fun setup() = runBlocking {
        db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
        queue = RecognitionQueue(db, clock)
        draft = CatalogRepository(db).initialDraft().copy(amount = Money(1230, CurrencySpec.CNY),
            occurredAt = time, recognizedAt = time, source = BillSource.ACCESSIBILITY, sourcePackage = "com.tencent.mm", ruleId = "fixture")
    }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }
    private fun result(draft: TransactionDraft = this.draft, order: String? = null) = RecognitionResult.Matched(draft,
        listOfNotNull(order?.let { FieldEvidence("orderId", listOf(1), it) }), "fixture")

    @Test fun diagnosticsDistinguishReturnedQueueStateFromRolledBackSave() = runBlocking {
        val events = mutableListOf<Pair<String, JsonObject>>()
        queue = RecognitionQueue(db, clock, diagnostics = DiagnosticSink { kind, fields -> events += kind to fields })
        val entry = queue.enqueue(result())
        events.clear()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_bill_insert BEFORE INSERT ON bills BEGIN SELECT RAISE(ABORT, 'diagnostic fault'); END")
        assertTrue(runCatching { queue.resolve(entry.id, QueueOutcome.Save(draft)) }.isFailure)
        assertTrue(events.any { it.first == "queue_error" })
        assertFalse(events.any { it.second["state"]?.jsonPrimitive?.content == "SAVED" })
        assertEquals(entry, queue.read(entry.id))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_bill_insert")
        events.clear()
        queue.resolve(entry.id, QueueOutcome.Save(draft))
        assertTrue(events.any { it.first == "queue_operation_returned" && it.second["state"]?.jsonPrimitive?.content == "SAVED" })
        queue = RecognitionQueue(db, clock, diagnostics = DiagnosticSink { _, _ -> error("broken sink") })
        assertEquals(QueueState.SAVED, queue.resolve(entry.id, QueueOutcome.Save(draft)).entry.state)
    }

    @Test fun exactOrderOnlyQueuesOnceWhileSimilarityNeverDiscards() = runBlocking {
        val first = queue.enqueue(result(order = "one"))
        assertEquals(first.id, queue.enqueue(result(draft.copy(submissionId = UUID.randomUUID().toString()), "one")).id)
        val second = queue.enqueue(result(draft.copy(submissionId = UUID.randomUUID().toString()), "two"))
        assertNotEquals(first.id, second.id)
        assertEquals(DedupKind.SIMILAR, second.decision.kind)
        assertEquals(2, queue.entries().size)
        assertEquals(0, db.ledgerDao().billCount())
    }
    @Test fun tenCandidatesAndEditingSurviveReopenAndCancelNeverMeansSaved() = runBlocking {
        val ids = (0..9).map { queue.enqueue(result(draft.copy(submissionId = UUID.randomUUID().toString(), note = "$it"))).id }
        queue.resolve(ids[0], QueueOutcome.Edit)
        db.close()
        db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
        queue = RecognitionQueue(db, clock)
        assertEquals(ids.toSet(), queue.entries().map { it.id }.toSet())
        assertEquals(QueueState.EDITING, queue.read(ids[0])!!.state)
        val cancelled = queue.resolve(ids[0], QueueOutcome.Cancel).entry
        assertEquals(QueueState.PENDING, cancelled.state)
        assertNull(cancelled.billId)
        assertEquals(0, db.ledgerDao().billCount())
        assertEquals(listOf(QueueState.RECOGNIZED, QueueState.PENDING, QueueState.EDITING, QueueState.PENDING), cancelled.events.map { it.state })
    }
    @Test fun savingIsAtomicIdempotentAndInvalidInputIsNotSaved() = runBlocking {
        val entry = queue.enqueue(result())
        val invalid = queue.resolve(entry.id, QueueOutcome.Save(draft.copy(occurredAt = null)))
        assertTrue(invalid.saveResult is SaveResult.Invalid)
        assertEquals(QueueState.FAILED, invalid.entry.state)
        assertEquals("The returned failure must also survive a fresh database read", invalid.entry, queue.read(entry.id))
        assertEquals(0, db.ledgerDao().billCount())
        val saved = queue.resolve(entry.id, QueueOutcome.Save(draft))
        assertTrue(saved.saveResult is SaveResult.Saved)
        assertEquals(QueueState.SAVED, saved.entry.state)
        assertEquals(saved, queue.resolve(entry.id, QueueOutcome.Save(draft)))
        assertEquals(1, db.ledgerDao().billCount())
        assertEquals(-1230L, db.ledgerDao().account(draft.fromAccountId!!)!!.balanceMinor)
    }

    @Test fun unavailableReferencesAndBalanceOverflowPersistTheirFailedReviewState() = runBlocking {
        val account = db.ledgerDao().account(draft.fromAccountId!!)!!.model()
        assertTrue(CatalogRepository(db).saveAccount(account.copy(openingBalance = Money(Long.MIN_VALUE, account.currency))) is CatalogResult.Saved)
        listOf(draft.copy(fromAccountId = UUID.randomUUID().toString()), draft).forEach { candidate ->
            val entry = queue.enqueue(result(candidate.copy(submissionId = UUID.randomUUID().toString())))
            val failed = queue.resolve(entry.id, QueueOutcome.Save(candidate))
            assertTrue(failed.saveResult is SaveResult.Invalid)
            assertEquals(QueueState.FAILED, queue.read(entry.id)!!.state)
            assertEquals(failed.entry.events, queue.read(entry.id)!!.events)
            assertEquals(0, db.ledgerDao().billCount())
            assertEquals(Long.MIN_VALUE, db.ledgerDao().account(account.id)!!.balanceMinor)
        }
    }

    @Test fun storageFailureRollsBackTheWholeCallerRatherThanReturningAnUnpersistedState() = runBlocking {
        val entry = queue.enqueue(result())
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_bill_insert BEFORE INSERT ON bills BEGIN SELECT RAISE(ABORT, 'test fault'); END")
        assertTrue(RoomLedgerRepository(db).save(draft, null) is SaveResult.Failure)
        val failure = runCatching { queue.resolve(entry.id, QueueOutcome.Save(draft)) }.exceptionOrNull()
        assertTrue("A failed nested write must abort its caller", failure is android.database.sqlite.SQLiteException)
        assertEquals(entry, queue.read(entry.id))
        assertEquals(0, db.ledgerDao().billCount())
        assertEquals(0L, db.ledgerDao().account(draft.fromAccountId!!)!!.balanceMinor)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_bill_insert")
        val saved = queue.resolve(entry.id, QueueOutcome.Save(draft))
        assertEquals(saved.entry, queue.read(entry.id))
        assertEquals(1, db.ledgerDao().billCount())
        assertEquals(-1230L, db.ledgerDao().account(draft.fromAccountId!!)!!.balanceMinor)
    }
    @Test fun capacityAndExpiryAreExplicitAndRetainEveryRecord() = runBlocking {
        val small = RecognitionQueue(db, clock, QueueLimits(active = 1, total = 3))
        val first = small.enqueue(result())
        assertEquals("active_limit", runCatching { small.enqueue(result(draft.copy(submissionId = UUID.randomUUID().toString()))) }.exceptionOrNull().let { (it as QueueException).code })
        val later = RecognitionQueue(db, Clock.fixed(time.plusSeconds(31 * 86400L), ZoneOffset.UTC))
        assertEquals(1, later.expire())
        assertEquals(QueueState.EXPIRED, later.read(first.id)!!.state)
        assertEquals(1, later.entries().size)
        assertEquals(QueueState.PENDING, later.resolve(first.id, QueueOutcome.Reopen).entry.state)
    }
    @Test fun queueMutationFailureRollsBackLedgerAndBackupIncludesQueue() = runBlocking {
        val entry = queue.enqueue(result())
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_queue_update BEFORE UPDATE ON recognition_queue BEGIN SELECT RAISE(ABORT, 'test fault'); END")
        assertTrue(runCatching { queue.resolve(entry.id, QueueOutcome.Save(draft)) }.isFailure)
        assertEquals(0, db.ledgerDao().billCount())
        assertEquals(0L, db.ledgerDao().account(draft.fromAccountId!!)!!.balanceMinor)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_queue_update")
        val archive = BackupArchive(db, context, clock = clock)
        val bytes = ByteArrayOutputStream().also { archive.exportTo(it) }.toByteArray()
        queue.resolve(entry.id, QueueOutcome.Ignore)
        val preview = archive.inspect(bytes.inputStream())
        assertTrue(preview.conflicts.any { it.kind.name == "QUEUE" })
        archive.restore(preview.previewId, RestoreMode.REPLACE)
        assertEquals(entry, queue.read(entry.id))
        assertEquals(0, db.ledgerDao().billCount())
    }

    @Test fun concurrentRepeatsAndSavesHaveOneDurableIdentityAndBalanceEffect() = runBlocking {
        val entries = (1..10).map { async { queue.enqueue(result(order = "same")) } }.awaitAll()
        assertEquals(1, entries.map { it.id }.distinct().size)
        val saved = (1..10).map { async { queue.resolve(entries[0].id, QueueOutcome.Save(draft)) } }.awaitAll()
        assertEquals(1, saved.map { it.entry.billId }.distinct().size)
        assertEquals(1, db.ledgerDao().billCount())
        val bytes = ByteArrayOutputStream().also { BackupArchive(db, context).exportTo(it) }.toByteArray()
        assertTrue(bytes.isNotEmpty())
        assertEquals(-1230L, db.ledgerDao().account(draft.fromAccountId!!)!!.balanceMinor)
    }

    @Test fun ignoredUnknownTimeCandidateIsNotAPermanentSuppressionKey() = runBlocking {
        val first = queue.enqueue(result(draft.copy(occurredAt = null)))
        queue.resolve(first.id, QueueOutcome.Ignore)
        val second = queue.enqueue(result(draft.copy(submissionId = UUID.randomUUID().toString(), occurredAt = null)))
        assertEquals(QueueState.PENDING, second.state)
        assertEquals(DedupKind.SIMILAR, second.decision.kind)
        assertTrue(runCatching { queue.resolve(first.id, QueueOutcome.Save(draft)) }.isFailure)
        assertEquals(QueueState.IGNORED, queue.read(first.id)!!.state)
        assertEquals(0, db.ledgerDao().billCount())
    }

    @Test fun evidenceIsBoundedAndRedactedWithoutStoringASnapshot() = runBlocking {
        val private = "银行卡 6222021234567890"
        val entry = queue.enqueue(RecognitionResult.NeedsReview(draft.copy(note = private), emptyList(),
            listOf(FieldEvidence("accountHint", listOf(1), private)), "fixture"))
        val raw = db.ledgerDao().queueEntry(entry.id)!!.payloadJson
        assertFalse(raw.contains("6222021234567890"))
        assertTrue(raw.contains("****7890"))
        assertTrue(runCatching { queue.enqueue(RecognitionResult.Unsupported("no_rule")) }.isFailure)
        assertTrue(runCatching { queue.enqueue(result().copy(evidence = listOf(FieldEvidence("amount", listOf(1), "x".repeat(5000))))) }.isFailure)
        assertEquals(1, queue.entries().size)
    }

    @Test fun similarityUsesEditedDraftAndCurrentSavedBillRatherThanOriginalCapture() = runBlocking {
        val entry = queue.enqueue(result())
        val edited = draft.copy(amount = Money(1350, CurrencySpec.CNY))
        queue.persistDraft(entry.id, EditorDraft("recognition/${entry.id}", edited, amountText = "13.50"))
        val pendingMatch = queue.enqueue(result(edited.copy(submissionId = UUID.randomUUID().toString(), occurredAt = time.plusSeconds(30))))
        assertEquals(DedupKind.SIMILAR, pendingMatch.decision.kind)
        queue.resolve(pendingMatch.id, QueueOutcome.Ignore)
        val saved = queue.resolve(entry.id, QueueOutcome.Save(edited)).entry
        val ledger = RoomLedgerRepository(db, clock)
        val bill = ledger.observeBill(saved.billId!!).first()!!
        assertTrue(ledger.save(bill.toDraft().copy(amount = Money(2450, CurrencySpec.CNY), occurredAt = time.plusSeconds(120)), bill.revision) is SaveResult.Saved)
        val latest = edited.copy(submissionId = UUID.randomUUID().toString(), amount = Money(2450, CurrencySpec.CNY), occurredAt = time.plusSeconds(150))
        val savedMatch = queue.enqueue(result(latest))
        assertEquals(DedupKind.SIMILAR, savedMatch.decision.kind)
        assertTrue(savedMatch.decision.matches.any { it.id == entry.id })
        assertEquals(1, db.ledgerDao().billCount())
    }
}
