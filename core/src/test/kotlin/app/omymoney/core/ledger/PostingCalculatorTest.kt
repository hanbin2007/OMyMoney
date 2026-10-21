package app.omymoney.core.ledger

import app.omymoney.core.model.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class PostingCalculatorTest {
    private val cny = CurrencySpec("CNY", 2)
    private val expense = TransactionDraft(
        bookId = "book", kind = BillKind.EXPENSE, amount = Money(1230, cny),
        fromAccountId = "cash", categoryId = "food",
        occurredAt = Instant.parse("2026-10-11T10:00:00Z"), timeSource = TimeSource.USER,
    )

    @Test fun expenseReducesOnlyTheSelectedAccount() {
        val posting = PostingCalculator.calculate(expense).single()
        assertEquals("cash", posting.accountId)
        assertEquals(-1230L, posting.amount.minor)
        assertEquals(8770L, (Money(10000, cny) + posting.amount).minor)
    }

    @Test fun incomeIncreasesReceivingAccount() {
        val result = PostingCalculator.calculate(expense.copy(
            kind = BillKind.INCOME, fromAccountId = null, toAccountId = "bank",
        ))
        assertEquals(listOf(Posting("bank", Money(1230, cny))), result)
    }

    @Test fun recognizedPaymentWithUnknownFundingDoesNotInventCashPostings() {
        val observed = expense.copy(fromAccountId = null, source = BillSource.ACCESSIBILITY,
            sourcePackage = "com.eg.android.AlipayGphone", recognizedAt = expense.occurredAt, ruleId = "AlipayPaySuccess")
        assertEquals(emptyList<Posting>(), PostingCalculator.calculate(observed))
        assertEquals(listOf(Posting("cash", Money(-1230, cny))), PostingCalculator.calculate(observed.copy(fromAccountId = "cash")))
        assertThrows(DraftValidationException::class.java) { PostingCalculator.calculate(observed.copy(source = BillSource.MANUAL)) }
    }

    @Test fun recognizedTransferOnlyPostsTheKnownSideAndCanBeFullyUnassigned() {
        val observed = expense.copy(kind = BillKind.TRANSFER, source = BillSource.ACCESSIBILITY,
            sourcePackage = "com.eg.android.AlipayGphone", recognizedAt = expense.occurredAt, ruleId = "AlipayTransferOut")
        assertEquals(listOf(Posting("cash", Money(-1230, cny))), PostingCalculator.calculate(observed))
        assertEquals(emptyList<Posting>(), PostingCalculator.calculate(observed.copy(fromAccountId = null)))
        assertThrows(DraftValidationException::class.java) { PostingCalculator.calculate(observed.copy(toAccountId = "cash")) }
    }

    @Test fun transferConservesMoneyAcrossTwoAccounts() {
        val result = PostingCalculator.calculate(expense.copy(
            kind = BillKind.TRANSFER, amount = Money(2000, cny), toAccountId = "bank", categoryId = null,
        ))
        assertEquals(listOf(Posting("cash", Money(-2000, cny)), Posting("bank", Money(2000, cny))), result)
        assertEquals(0L, result.sumOf { it.amount.minor })
    }

    @Test fun missingAmountTypeOrTimeCannotBecomeAZeroSuccessfulBill() {
        listOf(expense.copy(amount = null), expense.copy(amount = Money(0, cny)),
            expense.copy(amount = Money(-1, cny)), expense.copy(kind = null),
            expense.copy(occurredAt = null), expense.copy(timeSource = TimeSource.UNKNOWN),
        ).forEach { invalid -> assertThrows(DraftValidationException::class.java) { PostingCalculator.calculate(invalid) } }
    }

    @Test fun missingOrIdenticalAccountsCannotCreateTransfer() {
        listOf(expense.copy(fromAccountId = null), expense.copy(bookId = ""),
            expense.copy(kind = BillKind.TRANSFER, toAccountId = "cash"),
            expense.copy(kind = BillKind.TRANSFER, toAccountId = null),
        ).forEach { invalid -> assertThrows(DraftValidationException::class.java) { PostingCalculator.calculate(invalid) } }
    }

    @Test fun unsupportedKindsNeverFallThroughToOrdinaryExpenseAndFeesAreExplicit() {
        BillKind.entries.filterNot { it in PostingCalculator.supportedKinds }.forEach { kind ->
            val error = assertThrows(DraftValidationException::class.java) { PostingCalculator.calculate(expense.copy(kind = kind)) }
            assertTrue(error.errors.any { it.code == "unsupported_kind" })
        }
        assertEquals(Money(-1240, cny), PostingCalculator.calculate(expense.copy(fee = Money(10, cny))).single().amount)
    }

    @Test fun validationErrorsIdentifyFields() {
        val error = assertThrows(DraftValidationException::class.java) {
            PostingCalculator.calculate(expense.copy(amount = null, occurredAt = null))
        }
        assertTrue(error.errors.any { it.field == "amount" })
        assertTrue(error.errors.any { it.field == "occurredAt" })
    }
}
