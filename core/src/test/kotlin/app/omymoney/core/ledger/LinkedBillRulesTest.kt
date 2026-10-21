package app.omymoney.core.ledger

import app.omymoney.core.model.*
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class LinkedBillRulesTest {
    private val cny = CurrencySpec.CNY
    private fun bill(id: String, kind: BillKind, minor: Long, linked: String? = null,
                     time: String = "2026-01-31T10:00:00Z") = Bill(
        id, 1, Instant.parse(time), "submission-$id", "book", kind, Money(minor, cny),
        Instant.parse(time), TimeSource.USER, ZoneOffset.UTC,
        fromAccountId = if (kind in listOf(BillKind.EXPENSE, BillKind.REIMBURSEMENT)) "cash" else null,
        toAccountId = if (linked != null) "cash" else null, linkedBillId = linked,
    )

    @Test fun crossMonthRefundKeepsSourceAndReducesExpenseWithoutCountingOrdinaryIncome() {
        val source = bill("source", BillKind.EXPENSE, 10000)
        val refund = bill("refund", BillKind.REFUND, 3000, source.id, "2026-02-03T10:00:00Z")
        assertTrue(LinkedBillRules.validate(refund.toDraft(), listOf(source)).isEmpty())
        val effect = LinkedBillRules.refundEffects(source, listOf(refund)).getValue(refund.id)
        assertEquals(7000L, (source.amount - effect.expenseReduction).minor)
        assertEquals(0L, effect.income.minor)
        assertEquals(3000L, PostingCalculator.calculate(refund.toDraft()).single().amount.minor)
    }

    @Test fun excessRefundIsIncomeAndMultipleRefundsHaveStableChronologicalAllocation() {
        val source = bill("source", BillKind.EXPENSE, 2000)
        val first = bill("a", BillKind.REFUND, 1500, source.id)
        val second = bill("b", BillKind.REFUND, 1500, source.id)
        val effects = LinkedBillRules.refundEffects(source, listOf(second, first))
        assertEquals(RefundEffect(Money(1500, cny), Money(0, cny)), effects[first.id])
        assertEquals(RefundEffect(Money(500, cny), Money(1000, cny)), effects[second.id])
        assertEquals(1000L, LinkedBillRules.refundEffects(source, listOf(second.copy(amount = Money(3000, cny))))
            .getValue(second.id).income.minor)
    }

    @Test fun partialReimbursementIsNotAnAdditionalExpenseAndRejectsOverSettlement() {
        val source = bill("source", BillKind.REIMBURSEMENT, 10000)
        val first = bill("first", BillKind.REIMBURSED, 6000, source.id)
        assertEquals(4000L, LinkedBillRules.remainingReimbursement(source, listOf(first)).minor)
        assertEquals(-10000L, PostingCalculator.calculate(source.toDraft()).single().amount.minor)
        assertEquals(6000L, PostingCalculator.calculate(first.toDraft()).single().amount.minor)
        assertTrue(LinkedBillRules.validate(first.toDraft(), listOf(source, first)).isEmpty())
        val second = bill("second", BillKind.REIMBURSED, 5000, source.id)
        assertTrue(LinkedBillRules.validate(second.toDraft(), listOf(source, first)).any { it.code == "exceeds_remaining" })
        assertTrue(LinkedBillRules.validate(source.copy(amount = Money(5000, cny)).toDraft(), listOf(source, first))
            .any { it.code == "exceeds_remaining" })
    }

    @Test fun missingWrongBookWrongCurrencyAndCircularSourcesAreRejected() {
        val source = bill("source", BillKind.EXPENSE, 10000)
        val refund = bill("refund", BillKind.REFUND, 1000, source.id)
        listOf(refund.toDraft().copy(linkedBillId = null), refund.toDraft().copy(linkedBillId = refund.id),
            refund.toDraft().copy(bookId = "other"), refund.toDraft().copy(amount = Money(1000, CurrencySpec("USD", 2))),
            refund.toDraft().copy(kind = BillKind.REIMBURSED)).forEach {
            assertFalse(LinkedBillRules.validate(it, listOf(source, refund)).isEmpty())
        }
        assertFalse(LinkedBillRules.validate(refund.toDraft(), emptyList()).isEmpty())
        assertFalse(LinkedBillRules.validate(source.copy(kind = BillKind.INCOME).toDraft(), listOf(source, refund)).isEmpty())
    }
}
