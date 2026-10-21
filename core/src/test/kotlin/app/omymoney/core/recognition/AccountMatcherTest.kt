package app.omymoney.core.recognition

import app.omymoney.core.model.*
import org.junit.Assert.*
import org.junit.Test

class AccountMatcherTest {
    private val draft = TransactionDraft("book", amount = Money(1230, CurrencySpec.CNY), sourcePackage = "com.tencent.mm")
    private val bank = AssetAccount("bank", "招商银行", cardLastFour = "1234", kind = AccountKind.DEBIT, aliases = listOf("招行储蓄"))
    private fun hint(value: String) = listOf(FieldEvidence("accountHint", listOf(1), value))
    @Test fun principalAccountsAreNeverSuggestedAsPaymentMethods() {
        val liabilities = listOf(bank.copy(id = "liability", kind = AccountKind.LIABILITY), bank.copy(id = "receivable", kind = AccountKind.RECEIVABLE))
        assertEquals(listOf("bank"), AccountMatcher.match(draft, liabilities + bank, hint("招商银行1234")).options.map { it.accountId })
    }
    @Test fun namesAliasesAndTailsSuggestWithoutChangingTheDraft() {
        val before = draft.copy()
        val results = AccountMatcher.match(draft, listOf(bank), hint("招行储蓄 (1234)"))
        assertEquals("bank", results.options.single().accountId)
        assertTrue("card_tail" in results.options.single().reasons)
        assertTrue("name" in results.options.single().reasons)
        assertEquals(before, draft)
        assertNull(draft.fromAccountId)
    }
    @Test fun sharedTailsStayAmbiguousAndConflictingTailsAreExplained() {
        val other = bank.copy(id = "other", name = "另一账户")
        assertTrue(AccountMatcher.match(draft, listOf(bank, other), hint("尾号1234")).ambiguous)
        val conflict = AccountMatcher.match(draft, listOf(bank), hint("招商银行尾号9999"))
        assertTrue("card_tail" in conflict.options.single().differences)
    }
    @Test fun archivedOrWrongCurrencyAccountsAreNotSuggestedAndScenarioIsOnlyAWeakHint() {
        val wallet = AssetAccount("wallet", "微信零钱", kind = AccountKind.WALLET)
        val results = AccountMatcher.match(draft, listOf(wallet, bank.copy(archived = true), bank.copy(id = "usd", currency = CurrencySpec("USD", 2))))
        assertEquals(listOf("wallet"), results.options.map { it.accountId })
        assertTrue("scenario" in results.options.single().reasons)
        assertNull(draft.fromAccountId)
        assertTrue(AccountMatcher.match(draft.copy(sourcePackage = null), listOf(bank)).options.isEmpty())
    }
}
