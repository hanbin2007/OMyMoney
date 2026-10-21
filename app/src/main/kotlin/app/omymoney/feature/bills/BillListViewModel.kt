package app.omymoney.feature.bills

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omymoney.core.ledger.LedgerRepository
import app.omymoney.core.model.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

data class BillFilters(
    val search: String = "",
    val kind: BillKind? = null,
    val accountId: String? = null,
    val categoryId: String? = null,
    val tag: String = "",
    val from: String = "",
    val through: String = "",
) {
    val active get() = search.isNotBlank() || kind != null || accountId != null || categoryId != null || tag.isNotBlank() || from.isNotBlank() || through.isNotBlank()
    fun query(bookId: String, zone: ZoneId) = BillQuery(bookId, accountId, kind, categoryId,
        startInclusive = from.takeIf(String::isNotBlank)?.let { LocalDate.parse(it).atStartOfDay(zone).toInstant() },
        endExclusive = through.takeIf(String::isNotBlank)?.let { LocalDate.parse(it).plusDays(1).atStartOfDay(zone).toInstant() },
        search = search, tag = tag.trim().takeIf(String::isNotEmpty))
    fun bundle() = Bundle().apply {
        putString("search", search); putString("kind", kind?.name); putString("account", accountId)
        putString("category", categoryId); putString("tag", tag); putString("from", from); putString("through", through)
    }
    companion object {
        fun read(bundle: Bundle) = BillFilters(bundle.getString("search").orEmpty(),
            bundle.getString("kind")?.let { runCatching { BillKind.valueOf(it) }.getOrNull() },
            bundle.getString("account"), bundle.getString("category"), bundle.getString("tag").orEmpty(),
            bundle.getString("from").orEmpty(), bundle.getString("through").orEmpty())
    }
}

data class BillListState(val bills: List<Bill> = emptyList(), val loading: Boolean = true, val failed: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class)
class BillListViewModel(private val ledger: LedgerRepository, private val savedState: SavedStateHandle, book: Book) : ViewModel() {
    private val bookState = MutableStateFlow(book)
    private val refresh = MutableStateFlow(0)
    val filters = savedState.getStateFlow("filters", Bundle()).map(BillFilters::read)
        .stateIn(viewModelScope, SharingStarted.Eagerly, BillFilters.read(savedState["filters"] ?: Bundle()))
    val searchOpen = savedState.getStateFlow("searchOpen", false)
    val state = combine(filters, bookState, refresh) { filter, currentBook, _ -> filter to currentBook }
        .flatMapLatest { (filter, currentBook) ->
            flow {
                emit(BillListState())
                emitAll(ledger.observeBills(filter.query(currentBook.id, currentBook.zoneId)).map { BillListState(it, loading = false) })
            }.catch { emit(BillListState(loading = false, failed = true)) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BillListState())

    fun updateBook(book: Book) { bookState.value = book }
    fun updateFilters(filters: BillFilters) { savedState["filters"] = filters.bundle() }
    fun search(text: String) { updateFilters(BillFilters.read(savedState["filters"] ?: Bundle()).copy(search = text)) }
    fun clearFilters() { updateFilters(BillFilters()) }
    fun toggleSearch() {
        val open = !searchOpen.value
        savedState["searchOpen"] = open
        if (!open) search("")
    }
    fun retry() { refresh.value += 1 }
}
