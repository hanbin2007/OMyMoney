package app.omymoney.core.ledger

import app.omymoney.core.model.*
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class DebtRulesTest {
    private val cny = CurrencySpec.CNY
    private fun bill(id: String, kind: BillKind, minor: Long, from: String?, to: String?, linked: String? = null) = Bill(
        id, 1, Instant.EPOCH, "submission-$id", "book", kind, Money(minor, cny), Instant.EPOCH, TimeSource.USER,
        ZoneOffset.UTC, fromAccountId = from, toAccountId = to, linkedBillId = linked,
    )

    @Test fun borrowingAndRepayingPrincipalWithSeparateInterestPreserveCashAndLiability() {
        val borrow = bill("borrow", BillKind.DEBT_BORROW, 10000, "debt", "cash")
        val repay = bill("repay", BillKind.DEBT_REPAY, 4000, "cash", "debt", borrow.id)
        val interest = bill("interest", BillKind.INTEREST_EXPENSE, 200, "cash", null, borrow.id)
        val bills = listOf(borrow, repay, interest)
        assertEquals(DebtPosition(6000, 200), DebtRules.position("debt", bills))
        val postings = bills.flatMap { PostingCalculator.calculate(it.toDraft()) }
        assertEquals(5800L, postings.filter { it.accountId == "cash" }.sumOf { it.amount.minor })
        assertEquals(-6000L, postings.filter { it.accountId == "debt" }.sumOf { it.amount.minor })
        assertTrue(bills.all { LinkedBillRules.validate(it.toDraft(), bills).isEmpty() && DebtRules.validate(it.toDraft(), bills).isEmpty() })
        assertEquals(BillKind.EXPENSE, PostingCalculator.categoryKind(interest.kind))
    }

    @Test fun lendingAndCollectingKeepReceivableAndInterestIncomeSeparate() {
        val lend = bill("lend", BillKind.DEBT_LEND, 10000, "cash", "receivable")
        val collect = bill("collect", BillKind.DEBT_COLLECT, 4000, "receivable", "cash", lend.id)
        val interest = bill("interest", BillKind.INTEREST_INCOME, 200, null, "cash", lend.id)
        val bills = listOf(lend, collect, interest)
        assertEquals(DebtPosition(6000, 200), DebtRules.position("receivable", bills))
        assertEquals(6000L, bills.flatMap { PostingCalculator.calculate(it.toDraft()) }.filter { it.accountId == "receivable" }.sumOf { it.amount.minor })
        assertEquals(BillKind.INCOME, PostingCalculator.categoryKind(interest.kind))
    }

    @Test fun settlementsCannotOverpayChangePrincipalAccountOrOutliveTheirSource() {
        val borrow = bill("borrow", BillKind.DEBT_BORROW, 10000, "debt", "cash")
        val repay = bill("repay", BillKind.DEBT_REPAY, 4000, "cash", "debt", borrow.id)
        val bills = listOf(borrow, repay)
        listOf(repay.toDraft().copy(id = null, amount = Money(7000, cny)),
            repay.toDraft().copy(toAccountId = "other"), repay.toDraft().copy(linkedBillId = null),
            repay.toDraft().copy(bookId = "other"), repay.toDraft().copy(kind = BillKind.DEBT_COLLECT),
            borrow.toDraft().copy(amount = Money(3000, cny)), borrow.toDraft().copy(fromAccountId = "other"),
            borrow.toDraft().copy(kind = BillKind.TRANSFER)).forEach {
            assertFalse("Must reject $it", DebtRules.validate(it, bills).isEmpty())
        }
        assertFalse(DebtRules.validate(repay.toDraft(), emptyList()).isEmpty())
        assertTrue(DebtRules.validate(repay.toDraft().copy(fromAccountId = "other-cash"), bills).isEmpty())
    }

    @Test fun creditPaymentHasOnlyTransferPostingsAndAdjustmentRequiresOneAccountAndReason() {
        val payment = bill("payment", BillKind.CREDIT_PAYMENT, 2000, "cash", "credit").toDraft()
        assertEquals(listOf(Posting("cash", Money(-2000, cny)), Posting("credit", Money(2000, cny))), PostingCalculator.calculate(payment))
        assertNull(PostingCalculator.categoryKind(BillKind.CREDIT_PAYMENT))
        val adjustment = payment.copy(kind = BillKind.ADJUSTMENT, toAccountId = null, note = "Cash count")
        assertEquals(-2000L, PostingCalculator.calculate(adjustment).single().amount.minor)
        assertEquals(2000L, PostingCalculator.calculate(adjustment.copy(fromAccountId = null, toAccountId = "cash")).single().amount.minor)
        listOf(adjustment.copy(note = ""), adjustment.copy(toAccountId = "credit"), adjustment.copy(fromAccountId = null)).forEach {
            assertThrows(DraftValidationException::class.java) { PostingCalculator.calculate(it) }
        }
    }
}
