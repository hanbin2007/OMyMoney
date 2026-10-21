package app.omymoney

import android.content.Context
import android.os.Parcel
import androidx.core.os.bundleOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import app.omymoney.core.model.*
import app.omymoney.data.*
import app.omymoney.feature.bills.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class BatchStateTest {
    @Test fun maximumSelectionFitsSavedStateAndRetainsExactRevisions(): Unit = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = (context.applicationContext as OMyMoneyApplication).container.store
        val suffix = UUID.randomUUID().toString().take(6)
        val book = Book(UUID.randomUUID().toString(), "Selection state $suffix")
        val cash = AssetAccount(UUID.randomUUID().toString(), "Selection cash $suffix")
        store.catalog.saveBook(book); store.catalog.saveAccount(cash)
        val result = store.ledger.save(TransactionDraft(book.id, kind = BillKind.EXPENSE, amount = Money(1, CurrencySpec.CNY),
            fromAccountId = cash.id, occurredAt = Instant.now(), timeSource = TimeSource.USER), null) as SaveResult.Saved
        val bill = store.ledger.observeBill(result.billId).first()!!
        val saved = SavedStateHandle()
        val models = ViewModelStore()
        try {
            val model = withContext(Dispatchers.Main) { BatchViewModel(store, book, BillFilters(), saved).also { models.put("one", it) } }
            repeat(100) { group -> withContext(Dispatchers.Main) {
                repeat(100) { index -> model.toggle(bill.copy(id = UUID.randomUUID().toString(), revision = group * 100L + index + 1)) }
            } }
            assertEquals(10_000, model.selected.value.size())
            val bundle = bundleOf(*saved.keys().map { it to saved.get<Any>(it) }.toTypedArray())
            val parcel = Parcel.obtain()
            try {
                parcel.writeBundle(bundle)
                assertTrue("Selection alone uses ${parcel.dataSize()} bytes of shared Activity saved-state budget", parcel.dataSize() < 350_000)
            } finally { parcel.recycle() }
            val restored = withContext(Dispatchers.Main) {
                BatchViewModel(store, book, BillFilters(), SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })).also { models.put("two", it) }
            }
            assertEquals(model.selected.value.keySet(), restored.selected.value.keySet())
            model.selected.value.keySet().forEach { assertEquals(model.selected.value.getLong(it), restored.selected.value.getLong(it)) }
        } finally { withContext(Dispatchers.Main) { models.clear() } }
    }
}
