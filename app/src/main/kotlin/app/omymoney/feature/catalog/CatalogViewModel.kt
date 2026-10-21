package app.omymoney.feature.catalog

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omymoney.core.model.*
import app.omymoney.data.CatalogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CatalogState(
    val books: List<Book> = emptyList(),
    val accounts: List<AssetAccount> = emptyList(),
    val currentBookId: String? = null,
    val initialized: Boolean = false,
    val startupError: Boolean = false,
) {
    val currentBook: Book? get() = books.firstOrNull { it.id == currentBookId && !it.archived }
}

class CatalogViewModel(private val repository: CatalogRepository) : ViewModel() {
    private val initialized = MutableStateFlow(false)
    private val failed = MutableStateFlow(false)
    val state = combine(repository.observeBooks(true), repository.observeAccounts(true), repository.observeCurrentBookId(), initialized, failed) {
        books, accounts, selected, ready, error -> CatalogState(books, accounts, selected, ready, error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CatalogState())

    init { initialize() }

    fun initialize() {
        viewModelScope.launch {
            failed.value = false
            try {
                repository.ensureLocalDefaults()
                initialized.value = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e("OMyMoney", "Database initialization failed: ${error.javaClass.simpleName}")
                failed.value = true
            }
        }
    }

    fun observeCategories(bookId: String, kind: BillKind? = null, includeArchived: Boolean = false) =
        repository.observeCategories(bookId, kind, includeArchived)
    suspend fun initialDraft() = repository.initialDraft()
    suspend fun selectBook(id: String) = repository.selectBook(id)
    suspend fun saveBook(book: Book) = repository.saveBook(book)
    suspend fun saveAccount(account: AssetAccount) = repository.saveAccount(account)
    suspend fun saveCategory(category: Category) = repository.saveCategory(category)
    suspend fun archiveBook(id: String) = repository.archiveBook(id)
    suspend fun restoreBook(id: String) = repository.restoreBook(id)
    suspend fun archiveAccount(id: String) = repository.archiveAccount(id)
    suspend fun restoreAccount(id: String) = repository.restoreAccount(id)
    suspend fun archiveCategory(id: String) = repository.archiveCategory(id)
    suspend fun restoreCategory(id: String) = repository.restoreCategory(id)
    suspend fun reorderBooks(ids: List<String>) = repository.reorderBooks(ids)
    suspend fun reorderAccounts(ids: List<String>) = repository.reorderAccounts(ids)
    suspend fun reorderAccountGroups(groups: List<String?>) = repository.reorderAccountGroups(groups)
    suspend fun renameAccountGroup(previous: String, next: String) = repository.renameAccountGroup(previous, next)
    suspend fun reorderCategories(bookId: String, kind: BillKind, parentId: String?, ids: List<String>) =
        repository.reorderCategories(bookId, kind, parentId, ids)
}
