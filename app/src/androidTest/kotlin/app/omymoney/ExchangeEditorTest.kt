package app.omymoney

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.core.model.*
import app.omymoney.data.EditorDraft
import app.omymoney.feature.bills.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ExchangeEditorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container get() = (context.applicationContext as OMyMoneyApplication).container
    private val store get() = container.store
    private val usd = CurrencySpec("USD", 2)
    private val cny = CurrencySpec.CNY
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private suspend fun editor(book: String, bill: String? = null): BillEditorViewModel {
        lateinit var result: BillEditorViewModel
        onMain { result = BillEditorViewModel(store.drafts, store.catalog, store.attachments, container.applicationScope, book, bill) }
        withTimeout(5000) { result.state.first { it.record != null } }
        return result
    }
    private suspend fun fixture(kind: BillKind = BillKind.TRANSFER, source: BillSource = BillSource.MANUAL): Triple<String, String, String> {
        val book = UUID.randomUUID().toString(); val from = UUID.randomUUID().toString(); val to = UUID.randomUUID().toString()
        store.catalog.ensureLocalDefaults(); store.catalog.saveBook(Book(book, "FX ${book.take(6)}"))
        store.catalog.saveAccount(AssetAccount(from, "USD ${from.take(6)}", usd, Money(10000, usd)))
        store.catalog.saveAccount(AssetAccount(to, "CNY ${to.take(6)}"))
        val draft = TransactionDraft(book, kind = kind, fromAccountId = if (source == BillSource.OCR) to else from,
            toAccountId = to.takeIf { kind == BillKind.TRANSFER }, occurredAt = Instant.now(), timeSource = TimeSource.USER, source = source)
        store.drafts.write(EditorDraft("manual/$book", draft, if (source == BillSource.OCR) cny else usd, "10.00"))
        return Triple(book, from, to)
    }

    @Test fun explicitQuoteActualAndFeeSurviveSavingAndLaterRateChanges(): Unit = runBlocking {
        val (book, _, to) = fixture()
        store.catalog.removeExchangeRate(usd, cny)
        val vm = editor(book)
        onMain { vm.submit() }
        assertTrue(vm.state.value.errors.any { it.field == "exchangeRate" })
        store.catalog.saveExchangeRate(ManualExchangeRate(usd, cny, "7.2", LocalDate.of(2026, 10, 11)))
        onMain { vm.applyCurrentRate() }
        withTimeout(5000) { vm.state.first { it.record!!.exchangeAmountText == "72.00" } }
        onMain { vm.onAction(EditorAction.ExchangeAmount("71.99")); vm.onAction(EditorAction.Fee("0.10")); vm.submit() }
        val saved = withTimeout(5000) { vm.state.first { it.saved != null } }.saved!!
        val bill = store.ledger.observeBill(saved.billId).first()!!
        assertEquals(Money(7199, cny), bill.exchange!!.amount)
        assertEquals(Money(10, usd), bill.fee)
        assertEquals(7199L, store.catalog.observeAccounts().first().single { it.id == to }.balance.minor)
        store.catalog.saveExchangeRate(ManualExchangeRate(usd, cny, "8", LocalDate.of(2026, 10, 12)))
        val editing = editor(book, bill.id)
        assertEquals("7.2", editing.state.value.record!!.exchangeRateText)
        assertEquals("71.99", editing.state.value.record!!.exchangeAmountText)
        onMain { editing.onAction(EditorAction.Note("unchanged settlement")); editing.submit() }
        withTimeout(5000) { editing.state.first { it.saved != null } }
        assertEquals(bill.exchange, store.ledger.observeBill(bill.id).first()!!.exchange)
        assertEquals(7199L, store.catalog.observeAccounts().first().single { it.id == to }.balance.minor)
    }

    @Test fun selectingAForeignCashAccountDoesNotRelabelRecognizedNominalMoney(): Unit = runBlocking {
        val (book, from, _) = fixture(BillKind.EXPENSE, BillSource.OCR)
        val vm = editor(book)
        onMain { vm.onAction(EditorAction.FromAccount(from, usd)) }
        assertEquals(cny, vm.state.value.record!!.currency)
        onMain {
            vm.onAction(EditorAction.ExchangeRate("0.14")); vm.onAction(EditorAction.ExchangeDate("2026-10-11"))
            vm.recalculateExchange()
        }
        assertEquals("1.40", vm.state.value.record!!.exchangeAmountText)
        onMain { vm.submit() }
        val saved = withTimeout(5000) { vm.state.first { it.saved != null } }.saved!!
        val bill = store.ledger.observeBill(saved.billId).first()!!
        assertEquals(Money(1000, cny), bill.amount)
        assertEquals(Money(140, usd), bill.exchange!!.amount)
    }

    @Test fun malformedRateAndFeeRemainRawAndCannotSilentlySaveAsZero(): Unit = runBlocking {
        val (book, _, _) = fixture()
        val vm = editor(book)
        onMain {
            vm.onAction(EditorAction.ExchangeRate("1..2")); vm.onAction(EditorAction.ExchangeAmount("72.00"))
            vm.onAction(EditorAction.ExchangeDate("not-a-date")); vm.onAction(EditorAction.Fee("0..1")); vm.submit()
        }
        assertTrue(vm.state.value.errors.any { it.field == "exchangeRate" })
        assertTrue(vm.state.value.errors.any { it.field == "exchangeDate" })
        assertTrue(vm.state.value.errors.any { it.field == "fee" })
        assertTrue(store.ledger.observeBills(BillQuery(bookId = book)).first().isEmpty())
        onMain { vm.keepAndClose() }
        withTimeout(5000) { vm.state.first { it.closed } }
        val recovered = editor(book)
        assertEquals("1..2", recovered.state.value.record!!.exchangeRateText)
        assertEquals("0..1", recovered.state.value.record!!.feeText)
        onMain { recovered.discard() }
        withTimeout(5000) { recovered.state.first { it.closed } }
    }

    @Test fun recoveredForeignInputsCannotBeRelabeledAfterAccountCurrencyChanges(): Unit = runBlocking {
        val (book, _, to) = fixture()
        store.catalog.saveExchangeRate(ManualExchangeRate(usd, cny, "7.2", LocalDate.of(2026, 10, 11)))
        val vm = editor(book)
        onMain { vm.applyCurrentRate() }
        withTimeout(5000) { vm.state.first { it.record!!.exchangeAmountText == "72.00" } }
        onMain { vm.keepAndClose() }
        withTimeout(5000) { vm.state.first { it.closed } }
        val account = store.catalog.observeAccounts().first().single { it.id == to }
        val eur = CurrencySpec("EUR", 2)
        assertTrue(store.catalog.saveAccount(account.copy(currency = eur, openingBalance = Money(0, eur), balance = Money(0, eur))) is app.omymoney.data.CatalogResult.Saved)
        val recovered = editor(book)
        onMain { recovered.submit() }
        withTimeout(5000) { recovered.state.first { !it.saving } }
        assertTrue("A CNY quote must not become a EUR quote", recovered.state.value.errors.any { it.field == "exchangeRate" })
        assertTrue(recovered.state.value.errors.any { it.field == "exchangeAmount" })
        assertTrue(store.ledger.observeBills(BillQuery(bookId = book)).first().isEmpty())
        onMain { recovered.discard() }
        withTimeout(5000) { recovered.state.first { it.closed } }
    }

    @Test fun changedAccountReturningToNominalCurrencyRequiresExplicitReselection(): Unit = runBlocking {
        val (book, _, to) = fixture()
        store.catalog.saveExchangeRate(ManualExchangeRate(usd, cny, "7.2", LocalDate.of(2026, 10, 11)))
        val vm = editor(book)
        onMain { vm.applyCurrentRate() }
        withTimeout(5000) { vm.state.first { it.record!!.exchangeAmountText == "72.00" } }
        onMain { vm.keepAndClose() }
        withTimeout(5000) { vm.state.first { it.closed } }
        val account = store.catalog.observeAccounts().first().single { it.id == to }
        assertTrue(store.catalog.saveAccount(account.copy(currency = usd, openingBalance = Money(0, usd), balance = Money(0, usd))) is app.omymoney.data.CatalogResult.Saved)
        val recovered = editor(book)
        onMain { recovered.submit() }
        withTimeout(5000) { recovered.state.first { !it.saving } }
        assertTrue(recovered.state.value.errors.any { it.field == "toAccountId" })
        assertTrue(store.ledger.observeBills(BillQuery(bookId = book)).first().isEmpty())
        onMain { recovered.onAction(EditorAction.ToAccount(to, usd)); recovered.submit() }
        val saved = withTimeout(5000) { recovered.state.first { it.saved != null } }.saved!!
        assertNull(store.ledger.observeBill(saved.billId).first()!!.exchange)
    }

    @Test fun changingKindToTransferCannotRelabelThePreviousNominalAmount(): Unit = runBlocking {
        val (book, from, to) = fixture(BillKind.EXPENSE, BillSource.OCR)
        val vm = editor(book)
        onMain {
            vm.onAction(EditorAction.FromAccount(from, usd))
            vm.onAction(EditorAction.ExchangeRate("0.14")); vm.onAction(EditorAction.ExchangeAmount("1.40"))
            vm.onAction(EditorAction.Kind(BillKind.TRANSFER))
            vm.onAction(EditorAction.ToAccount(to, cny))
        }
        assertEquals(usd, vm.state.value.record!!.currency)
        assertEquals("The original CNY amount needs explicit reconfirmation in USD", "", vm.state.value.record!!.amountText)
        onMain { vm.submit() }
        assertTrue(vm.state.value.errors.any { it.field == "amount" })
        assertTrue(store.ledger.observeBills(BillQuery(bookId = book)).first().isEmpty())
    }

    @Test fun changingTransferPayingCurrencyRequiresANewNominalAmount(): Unit = runBlocking {
        val (book, _, to) = fixture()
        val vm = editor(book)
        onMain { vm.onAction(EditorAction.FromAccount(to, cny)) }
        assertEquals(cny, vm.state.value.record!!.currency)
        assertEquals("", vm.state.value.record!!.amountText)
    }

    @Test fun reselectingCurrencyPreservesConfirmedSettlement(): Unit = runBlocking {
        val (book, from, _) = fixture(BillKind.EXPENSE, BillSource.OCR)
        val vm = editor(book)
        onMain {
            vm.onAction(EditorAction.FromAccount(from, usd))
            vm.onAction(EditorAction.ExchangeRate("0.14")); vm.onAction(EditorAction.ExchangeAmount("1.39"))
            vm.onAction(EditorAction.ExchangeDate("2026-01-02"))
        }
        val original = vm.state.value.record!!
        onMain { vm.onAction(EditorAction.Currency(cny)) }
        assertEquals(original, vm.state.value.record)
    }

    @Test fun togglingReimbursementPreservesConfirmedSettlement(): Unit = runBlocking {
        val (book, from, _) = fixture(BillKind.EXPENSE, BillSource.OCR)
        val vm = editor(book)
        onMain {
            vm.onAction(EditorAction.FromAccount(from, usd))
            vm.onAction(EditorAction.ExchangeRate("0.14")); vm.onAction(EditorAction.ExchangeAmount("1.39"))
            vm.onAction(EditorAction.ExchangeDate("2026-01-02"))
        }
        onMain { vm.onAction(EditorAction.Kind(BillKind.REIMBURSEMENT)) }
        assertEquals("1.39", vm.state.value.record!!.exchangeAmountText)
        assertEquals("0.14", vm.state.value.record!!.exchangeRateText)
        assertEquals("2026-01-02", vm.state.value.record!!.exchangeDateText)
        onMain { vm.onAction(EditorAction.Kind(BillKind.EXPENSE)); vm.submit() }
        val saved = withTimeout(5000) { vm.state.first { it.saved != null } }.saved!!
        assertEquals(ExchangeSnapshot(from, Money(139, usd), "0.14", LocalDate.of(2026, 1, 2)), store.ledger.observeBill(saved.billId).first()!!.exchange)
    }
}
