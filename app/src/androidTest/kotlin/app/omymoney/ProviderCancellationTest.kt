package app.omymoney

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.core.model.*
import app.omymoney.data.CatalogResult
import app.omymoney.feature.bills.BillFilters
import app.omymoney.feature.exchange.ExchangeViewModel
import app.omymoney.feature.settings.BackupViewModel
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProviderCancellationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val store get() = (context.applicationContext as OMyMoneyApplication).container.store
    private val blocked = Uri.parse("content://app.omymoney.android.test.ocr/blocked")
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 4000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(20)
        }
        fail("Cancelled provider operation did not return promptly")
    }
    private fun opened() = context.contentResolver.call(blocked, "opened", null, null)!!.getBoolean("opened")
    private fun closeSource() { context.contentResolver.call(blocked, "close", null, null) }

    @Test fun backupPreviewCancellationReturnsWithoutWaitingForTheExternalWriter() {
        runBlocking { store.catalog.ensureLocalDefaults() }
        val before = runBlocking { store.ledger.observeBills(BillQuery()).first() }
        lateinit var model: BackupViewModel
        main { model = BackupViewModel(store.backup, context.contentResolver, SavedStateHandle()) }
        try {
            await { !model.state.value.busy }
            main { model.inspect(blocked) }
            await(::opened)
            SystemClock.sleep(100)
            main(model::cancelOperation)
            await { !model.state.value.busy }
            assertEquals("cancelled", model.state.value.notice)
            assertNull(model.state.value.error)
            assertEquals(before, runBlocking { store.ledger.observeBills(BillQuery()).first() })
        } finally {
            closeSource()
            main { model.viewModelScope.cancel() }
        }
    }

    @Test fun billImportCancellationReturnsWithoutWaitingForTheExternalWriter() {
        val book = Book(UUID.randomUUID().toString(), "Pipe ${UUID.randomUUID().toString().take(6)}")
        runBlocking { store.catalog.ensureLocalDefaults(); assertTrue(store.catalog.saveBook(book) is CatalogResult.Saved) }
        lateinit var model: ExchangeViewModel
        main { model = ExchangeViewModel(store.billImport, store.billExport, store.ledger, context.contentResolver, book, BillFilters(), SavedStateHandle()) }
        try {
            await { !model.state.value.busy }
            main { model.import(blocked) }
            await(::opened)
            SystemClock.sleep(100)
            main(model::cancelOperation)
            await { !model.state.value.busy }
            assertEquals("cancelled", model.state.value.notice)
            assertNull(model.state.value.error)
            assertTrue(runBlocking { store.ledger.observeBills(BillQuery(bookId = book.id)).first().isEmpty() })
        } finally {
            closeSource()
            main { model.viewModelScope.cancel() }
        }
    }
}
