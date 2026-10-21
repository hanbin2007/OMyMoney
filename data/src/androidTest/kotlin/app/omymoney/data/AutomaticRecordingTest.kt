package app.omymoney.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.omymoney.core.ledger.EconomicFacts
import app.omymoney.core.model.*
import app.omymoney.core.recognition.*
import app.omymoney.core.recognition.rules.AlipayRules
import app.omymoney.data.backup.*
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class AutomaticRecordingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "automatic-${UUID.randomUUID()}.db"
    private lateinit var db: LedgerDatabase
    private lateinit var queue: RecognitionQueue
    private lateinit var book: String
    private val time = Instant.parse("2026-10-15T03:39:41Z")
    @Before fun setup() = runBlocking {
        db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
        queue = RecognitionQueue(db)
        book = CatalogRepository(db).recognitionContext().bookId
    }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }
    private fun result(at: Instant = time, amount: String = "0.01", extra: List<String> = emptyList()) =
        RuleEngine(AlipayRules.create(RecognitionContext(book))).recognize(UiSnapshot(AlipayRules.PACKAGE, 42, at,
            (listOf("支付成功$amount", "zjy", amount, "完成") + extra).map { UiNode(it) }))
    private suspend fun bills() = RoomLedgerRepository(db).observeBills(BillQuery(bookId = book)).first()

    @Test fun repaintAndRestartKeepOneSavedBillWithoutChangingCashBalance() = runBlocking {
        val first = queue.recordCapture(result(), "boot/42")
        assertEquals(QueueState.SAVED, first.state)
        val again = queue.recordCapture(result(time.plusMillis(744), extra = listOf("领取优惠")), "boot/42")
        assertEquals(first.id, again.id)
        db.close()
        db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
        queue = RecognitionQueue(db)
        assertEquals(first.id, queue.recordCapture(result(time.plusSeconds(41)), "boot/42").id)
        val bill = bills().single()
        assertEquals(Money(1, CurrencySpec.CNY), bill.amount)
        assertEquals(time, bill.occurredAt)
        assertEquals(TimeSource.CAPTURED, bill.timeSource)
        assertEquals("zjy", bill.payment?.counterparty)
        assertNull(bill.fromAccountId)
        assertTrue(db.ledgerDao().accounts().all { it.balanceMinor == it.openingMinor })
        assertEquals(1L, EconomicFacts.from(listOf(bill)).single().amount.minor)
    }

    @Test fun newPaymentBoundaryAndDifferentOrderNeverLoseEqualPayments() = runBlocking {
        queue.recordCapture(result(), "boot/42")
        queue.closeCaptureWindow(AlipayRules.PACKAGE, "boot/42")
        queue.recordCapture(result(time.plusSeconds(2)), "boot/42")
        queue.recordCapture(result(time.plusSeconds(3), extra = listOf("交易单号", "order-a")), "boot/43")
        queue.recordCapture(result(time.plusSeconds(4), extra = listOf("交易单号", "order-b")), "boot/43")
        assertEquals(4, bills().size)
    }

    @Test fun successToDetailAndBackAcrossWindowsEnrichesOneBillUntilTheNextPaymentBoundary() = runBlocking {
        val first = queue.recordCapture(result(), "boot/42")
        fun detail(at: Instant) = RuleEngine(AlipayRules.create(RecognitionContext(book))).recognize(UiSnapshot(AlipayRules.PACKAGE, 43, at,
            listOf("交易详情", "交易成功", "实付款", "0.01", "收款方", "zjy", "支付时间", "2026-10-15 03:39:41", "交易单号", "order-same").map { UiNode(it) }))
        assertEquals(first.id, queue.recordCapture(detail(time.plusSeconds(2)), "boot/43").id)
        assertEquals(first.id, queue.recordCapture(result(time.plusSeconds(3)), "boot/42").id)
        assertEquals(1, bills().size)
        assertEquals(TimeSource.PAGE, bills().single().timeSource)
        queue.closeCaptureWindow(AlipayRules.PACKAGE)
        queue.recordCapture(result(time.plusSeconds(4)), "boot/42")
        assertEquals(2, bills().size)
    }

    @Test fun anOlderEqualPaymentDetailCannotRewriteTheNewPaymentsEstimatedTime() = runBlocking {
        for (window in listOf("boot/42", "boot/43")) {
            val first = queue.recordCapture(result(extra = listOf("交易单号", "fresh-$window")), "boot/42")
            val detail = RuleEngine(AlipayRules.create(RecognitionContext(book))).recognize(UiSnapshot(AlipayRules.PACKAGE, 43, time.plusSeconds(2),
                listOf("交易详情", "交易成功", "实付款", "0.01", "收款方", "zjy", "支付时间", "2026-10-14 03:39:41").map { UiNode(it) }))
            val old = queue.recordCapture(detail, window)
            assertNotEquals(first.id, old.id)
            assertEquals(time, RoomLedgerRepository(db).observeBill(first.billId!!).first()!!.occurredAt)
            queue.closeCaptureWindow(AlipayRules.PACKAGE)
        }
        assertEquals(3, bills().size)
    }

    @Test fun existingAlipaySuccessRuleNamesWithoutSuffixStillContinueToTheirDetails() {
        val template = result() as RecognitionResult.NeedsReview
        for ((success, detail) in listOf("AlipayCharge" to "AlipayChargeDetail", "AlipayWithdraw" to "AlipayWithdrawDetail",
            "AlipayTransferIn" to "AlipayBillDetail", "AlipayTransferOut" to "AlipayBillDetail")) {
            val first = template.copy(partialDraft = template.partialDraft.copy(ruleId = success), ruleId = success)
            val next = template.copy(partialDraft = template.partialDraft.copy(ruleId = detail, recognizedAt = time.plusSeconds(1)), ruleId = detail)
            assertTrue(success, continuesToCaptureDetails(first, next))
        }
    }

    @Test fun twoEqualPaymentsThenOlderDetailsStayAmbiguousInsteadOfChangingEitherIdentity() = runBlocking {
        val a = queue.recordCapture(result(), "boot/42")
        queue.closeCaptureWindow(AlipayRules.PACKAGE)
        val b = queue.recordCapture(result(time.plusSeconds(30)), "boot/42")
        fun detail(seconds: String, order: String, observed: Long) = RuleEngine(AlipayRules.create(RecognitionContext(book)))
            .recognize(UiSnapshot(AlipayRules.PACKAGE, 43, time.plusSeconds(observed), listOf("交易详情", "交易成功", "实付款", "0.01",
                "收款方", "zjy", "支付时间", "2026-10-15 03:$seconds", "交易单号", order).map { UiNode(it) }))
        val detailA = queue.recordCapture(detail("39:41", "order-a", 40), "boot/43")
        assertEquals(QueueState.PENDING, detailA.state)
        assertEquals(detailA.id, queue.recordCapture(detail("39:41", "order-a", 41), "boot/43").id)
        assertEquals(QueueState.PENDING, queue.recordCapture(detail("40:11", "order-b", 42), "boot/44").state)
        assertEquals(b.id, queue.recordCapture(result(time.plusSeconds(45)), "boot/42").id)
        assertEquals(2, bills().size)
        assertEquals(time, bills().single { it.id == a.billId }.occurredAt)
        assertEquals(time.plusSeconds(30), bills().single { it.id == b.billId }.occurredAt)
    }

    @Test fun knownOrderIdentityWinsOverAnotherActiveEqualPaymentAndBackKeepsItsOwnWindow() = runBlocking {
        val a = queue.recordCapture(result(extra = listOf("交易单号", "order-a")), "boot/42")
        queue.closeCaptureWindow(AlipayRules.PACKAGE)
        val b = queue.recordCapture(result(time.plusSeconds(30)), "boot/42")
        val detail = RuleEngine(AlipayRules.create(RecognitionContext(book))).recognize(UiSnapshot(AlipayRules.PACKAGE, 43, time.plusSeconds(40),
            listOf("交易详情", "交易成功", "实付款", "0.01", "收款方", "zjy", "支付时间", "2026-10-15 03:39:41", "交易单号", "order-a").map { UiNode(it) }))
        assertEquals(a.id, queue.recordCapture(detail, "boot/43").id)
        assertEquals(b.id, queue.recordCapture(result(time.plusSeconds(45)), "boot/42").id)
        assertEquals(2, bills().size)
        assertEquals(time.plusSeconds(30), bills().single { it.id == b.billId }.occurredAt)
    }

    @Test fun conflictingAmountsForTheSameOrderNeverAutoSaveASecondBill() = runBlocking {
        queue.recordCapture(result(extra = listOf("交易单号", "same-order")), "boot/42")
        val conflict = queue.recordCapture(result(time.plusSeconds(1), amount = "0.02", extra = listOf("交易单号", "same-order")), "boot/42")
        assertEquals(QueueState.PENDING, conflict.state)
        assertEquals(1, bills().size)
        assertEquals(1L, bills().single().amount.minor)
    }

    @Test fun newCheckoutBoundaryAllowsASecondPaymentEvenWhenOnlyItsDetailIsCaptured() = runBlocking {
        val first = queue.recordCapture(result(), "boot/42")
        queue.closeCaptureWindow(AlipayRules.PACKAGE)
        val detail = RuleEngine(AlipayRules.create(RecognitionContext(book))).recognize(UiSnapshot(AlipayRules.PACKAGE, 43, time.plusSeconds(30),
            listOf("交易详情", "交易成功", "实付款", "0.01", "收款方", "zjy", "创建时间", "2026-10-15 03:40:00").map { UiNode(it) }))
        val next = queue.recordCapture(detail, "boot/43")
        assertEquals(QueueState.SAVED, next.state)
        assertNotEquals(first.id, next.id)
        assertEquals(2, bills().size)
    }

    @Test fun ambiguousDetailStaysPendingAfterItsCandidatesAgeOutAndTheProcessRestarts() = runBlocking {
        queue.recordCapture(result(), "boot/42")
        queue.closeCaptureWindow(AlipayRules.PACKAGE)
        queue.recordCapture(result(time.plusSeconds(30)), "boot/42")
        fun detail(at: Instant) = RuleEngine(AlipayRules.create(RecognitionContext(book))).recognize(UiSnapshot(AlipayRules.PACKAGE, 43, at,
            listOf("交易详情", "交易成功", "实付款", "0.01", "收款方", "zjy", "支付时间", "2026-10-15 03:39:41", "交易单号", "order-a").map { UiNode(it) }))
        val review = queue.recordCapture(detail(time.plusSeconds(40)), "boot/43")
        assertEquals(QueueState.PENDING, review.state)
        db.close()
        db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
        queue = RecognitionQueue(db)
        val repeated = queue.recordCapture(detail(time.plusSeconds(240)), "boot/43")
        assertEquals(review.id, repeated.id)
        assertEquals(QueueState.PENDING, repeated.state)
        assertEquals(2, bills().size)
    }

    @Test fun globalOrderConflictRemainsBlockedOutsideTheNearbyBillWindow() = runBlocking {
        queue.recordCapture(result(extra = listOf("交易单号", "same-order")), "boot/42")
        val conflict = result(time.plusSeconds(86400), "0.02", listOf("交易单号", "same-order", "支付时间", "2026-10-16 03:39:41"))
        assertEquals(QueueState.PENDING, queue.recordCapture(conflict, "boot/43").state)
        assertEquals(QueueState.PENDING, queue.recordCapture(conflict, "boot/43").state)
        assertEquals(1, bills().size)
    }

    @Test fun pendingOrConflictingPaymentNeverGeneratesSavedReceipt() = runBlocking {
        val pending = queue.recordCapture(result(extra = listOf("处理中")), "boot/42")
        assertEquals(QueueState.PENDING, pending.state)
        assertTrue(bills().isEmpty())
        val confirmed = queue.recordCapture(result(time.plusSeconds(2)), "boot/42")
        assertEquals(pending.id, confirmed.id)
        assertEquals(QueueState.SAVED, confirmed.state)
        val conflict = queue.recordCapture(result(extra = listOf("实付款", "0.02")), "boot/43")
        assertNotEquals(QueueState.SAVED, conflict.state)
        assertEquals(1, bills().size)
    }

    @Test fun storageFailureAndConcurrentRetriesHaveOneCommittedResult() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_auto_insert BEFORE INSERT ON bills BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertTrue(runCatching { queue.recordCapture(result(), "boot/42") }.isFailure)
        assertTrue(queue.entries().isEmpty())
        assertTrue(bills().isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_auto_insert")
        val entries = (1..8).map { async { queue.recordCapture(result(time.plusMillis(it.toLong())), "boot/42") } }.awaitAll()
        assertEquals(1, entries.map { it.id }.toSet().size)
        assertTrue(entries.all { it.state == QueueState.SAVED })
        assertEquals(1, bills().size)
    }

    @Test fun assigningFundingPostsOnceAndBackupRestoreRetainsMetadata() = runBlocking {
        queue.recordCapture(result(), "boot/42")
        val ledger = RoomLedgerRepository(db)
        val original = bills().single()
        val account = db.ledgerDao().accounts().single()
        assertTrue(ledger.save(original.toDraft().copy(fromAccountId = account.id), original.revision) is SaveResult.Saved)
        assertEquals(-1L, db.ledgerDao().account(account.id)!!.balanceMinor)
        val updated = bills().single()
        val token = ledger.delete(updated.id)
        assertEquals(0L, db.ledgerDao().account(account.id)!!.balanceMinor)
        assertTrue(ledger.undo(token))
        assertEquals(-1L, db.ledgerDao().account(account.id)!!.balanceMinor)
        val archive = BackupArchive(db, context)
        val bytes = ByteArrayOutputStream().also { archive.exportTo(it) }.toByteArray()
        val preview = archive.inspect(bytes.inputStream())
        archive.restore(preview.previewId, RestoreMode.REPLACE)
        assertEquals("zjy", bills().single().payment?.counterparty)
        assertEquals(TimeSource.CAPTURED, bills().single().timeSource)
    }

    @Test fun laterMetadataEnrichesButDoesNotOverwriteUserEditsOrResurrectDeletedBill() = runBlocking {
        val first = queue.recordCapture(result(), "boot/42")
        queue.recordCapture(result(time.plusSeconds(1), extra = listOf("付款方式", "测试银行(1234)")), "boot/42")
        assertEquals("测试银行(1234)", bills().single().payment?.fromAccountHint)
        val ledger = RoomLedgerRepository(db)
        val bill = bills().single()
        ledger.save(bill.toDraft().copy(note = "my correction"), bill.revision)
        queue.recordCapture(result(time.plusSeconds(2), extra = listOf("付款方式", "测试银行(1234)")), "boot/42")
        assertEquals("my correction", bills().single().note)
        ledger.delete(bill.id)
        assertEquals(first.id, queue.recordCapture(result(time.plusSeconds(3)), "boot/42").id)
        assertTrue(bills().isEmpty())
    }

    @Test fun lateFeeEnrichesOneBillAndIsPostedOnlyWhenFundingIsAssigned() = runBlocking {
        queue.recordCapture(result(amount = "1.00"), "boot/42")
        queue.recordCapture(result(time.plusSeconds(1), amount = "1.00", extra = listOf("手续费", "0.10")), "boot/42")
        val bill = bills().single()
        assertEquals(Money(10, CurrencySpec.CNY), bill.fee)
        val account = db.ledgerDao().accounts().single()
        assertEquals(0L, account.balanceMinor)
        val ledger = RoomLedgerRepository(db)
        assertTrue(ledger.save(bill.toDraft().copy(fromAccountId = account.id), bill.revision) is SaveResult.Saved)
        assertEquals(-110L, db.ledgerDao().account(account.id)!!.balanceMinor)
        val assigned = bills().single()
        val unassigned = assigned.toDraft().copy(fromAccountId = null)
        assertTrue(ledger.save(unassigned, assigned.revision) is SaveResult.Saved)
        assertTrue(ledger.save(unassigned, assigned.revision) is SaveResult.Saved)
        assertEquals(0L, db.ledgerDao().account(account.id)!!.balanceMinor)
    }
}
