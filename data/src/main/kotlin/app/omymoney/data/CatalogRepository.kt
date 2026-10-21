package app.omymoney.data

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import app.omymoney.core.model.*
import app.omymoney.core.recognition.RecognitionContext
import java.time.Clock
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

sealed interface CatalogResult<out T> {
    data class Saved<T>(val value: T) : CatalogResult<T>
    data class Invalid(val fieldErrors: List<FieldError>) : CatalogResult<Nothing>
    data class Failure(val code: String) : CatalogResult<Nothing>
}

class CatalogRepository(private val database: LedgerDatabase, private val clock: Clock = Clock.systemUTC()) {
    private val dao = database.ledgerDao()

    fun observeExchangeRates(): Flow<List<ManualExchangeRate>> = dao.observeExchangeRates().map { rows -> rows.map(ExchangeRateEntity::model) }
    suspend fun saveExchangeRate(rate: ManualExchangeRate): CatalogResult<ManualExchangeRate> = mutate {
        if (rate.source.code == rate.target.code) invalid("currency", "same_currency")
        if (runCatching { app.omymoney.core.ledger.ExchangeRules.parseRate(rate.rate) }.isFailure) invalid("rate", "invalid_rate")
        dao.upsertExchangeRate(rate.entity())
        rate
    }
    suspend fun removeExchangeRate(source: CurrencySpec, target: CurrencySpec): CatalogResult<Unit> = mutate {
        dao.removeExchangeRate(source.code, source.scale, target.code, target.scale)
    }

    suspend fun ensureLocalDefaults(): String = database.withTransaction {
        var books = dao.books()
        if (books.isEmpty()) {
            val book = Book(UUID.randomUUID().toString(), "日常账本", zoneId = ZoneId.systemDefault())
            dao.upsertBook(book.entity())
            createDefaultCategories(book.id)
            books = dao.books()
        }
        val storedDefault = dao.preference(DEFAULT_BOOK)
        val defaultId = storedDefault?.takeIf { id -> books.any { it.id == id } } ?: books.first().id
        if (storedDefault != defaultId) dao.upsertPreference(PreferenceEntity(DEFAULT_BOOK, defaultId))
        if (dao.preference(INITIALIZED) == null) {
            if (dao.accounts().isEmpty()) dao.upsertAccount(AssetAccount(UUID.randomUUID().toString(), "现金").entity())
            dao.upsertPreference(PreferenceEntity(INITIALIZED, "true"))
        }
        val current = dao.preference(CURRENT_BOOK)
        if (books.none { it.id == current && !it.archived }) {
            val first = books.firstOrNull { !it.archived } ?: error("No active book in local database")
            dao.upsertPreference(PreferenceEntity(CURRENT_BOOK, first.id))
        }
        defaultId
    }

    fun observeBooks(includeArchived: Boolean = false): Flow<List<Book>> =
        dao.observeBooks(includeArchived).map { it.map(BookEntity::model) }

    fun observeAccounts(includeArchived: Boolean = false): Flow<List<AssetAccount>> =
        dao.observeAccounts(includeArchived).map { it.map(AccountEntity::model) }

    fun observeCategories(bookId: String, kind: BillKind? = null, includeArchived: Boolean = false): Flow<List<Category>> =
        dao.observeCategories(bookId, kind?.name, includeArchived).map { it.map(CategoryEntity::model) }

    fun observeCurrentBookId(): Flow<String?> = dao.observePreference(CURRENT_BOOK)

    suspend fun recognitionContext(): RecognitionContext {
        ensureLocalDefaults()
        return database.withTransaction {
            val book = requireNotNull(dao.preference(CURRENT_BOOK)?.let { dao.book(it) }).model()
            require(!book.archived)
            RecognitionContext(book.id, book.currency, book.zoneId)
        }
    }

    suspend fun initialDraft(bookId: String? = null): TransactionDraft {
        ensureLocalDefaults()
        return database.withTransaction {
            val book = requireNotNull((bookId ?: dao.preference(CURRENT_BOOK))?.let { dao.book(it) }).model()
            require(!book.archived) { "Cannot create a draft in an archived book" }
            val accounts = dao.accounts().filterNot { it.archived || it.kind in listOf(AccountKind.LIABILITY.name, AccountKind.RECEIVABLE.name) }
            val account = accounts.firstOrNull { it.currencyCode == book.currency.code && it.currencyScale == book.currency.scale }
                ?: accounts.firstOrNull()
            val category = dao.categories(book.id).firstOrNull { it.kind == BillKind.EXPENSE.name && !it.archived && it.parentId == null }
            TransactionDraft(bookId = book.id, kind = BillKind.EXPENSE, fromAccountId = account?.id,
                categoryId = category?.id, occurredAt = clock.instant(), timeSource = TimeSource.USER, zoneId = book.zoneId)
        }
    }

    suspend fun saveBook(book: Book): CatalogResult<Book> = mutate {
        val name = checkedName(book.name)
        val all = dao.books()
        if (all.any { it.id != book.id && it.name.equals(name, ignoreCase = true) }) invalid("name", "duplicate_name")
        val previous = dao.book(book.id)
        if (previous != null && previous.model().currency != book.currency && dao.allBudgets().any { it.bookId == book.id && it.payloadJson != null }) {
            invalid("currency", "currency_locked")
        }
        val normalized = book.copy(name = name, sortOrder = previous?.sortOrder ?: nextOrder(all.map { it.sortOrder }),
            archived = previous?.archived ?: false)
        dao.upsertBook(normalized.entity())
        if (previous == null) {
            createDefaultCategories(book.id)
            dao.upsertPreference(PreferenceEntity(CURRENT_BOOK, book.id))
        }
        normalized
    }

    suspend fun saveAccount(account: AssetAccount): CatalogResult<AssetAccount> = mutate {
        val name = checkedName(account.name)
        val all = dao.accounts()
        if (all.any { it.id != account.id && it.name.equals(name, ignoreCase = true) }) invalid("name", "duplicate_name")
        if (account.openingBalance.currency != account.currency || account.balance.currency != account.currency) invalid("currency", "currency_mismatch")
        account.cardLastFour?.let { if (!it.matches(Regex("[0-9]{4}"))) invalid("cardLastFour", "invalid_last_four") }
        if (account.aliases.size > 20 || account.aliases.any { it.length > 80 }) invalid("aliases", "too_many_aliases")
        val group = account.group?.trim()?.takeIf(String::isNotBlank)
        if (group != null && (group.length > 80 || group.any(Char::isISOControl))) invalid("group", "invalid_name")
        account.creditTerms?.let {
            if (account.kind != AccountKind.CREDIT || !CreditCycles.validate(it, account.currency)) invalid("creditTerms", "invalid_credit_terms")
        }
        val previous = dao.account(account.id)
        val history = previous != null && dao.accountReferenceCount(account.id) > 0
        val currencyChanged = previous != null && (previous.currencyCode != account.currency.code || previous.currencyScale != account.currency.scale)
        if (history && currencyChanged) invalid("currency", "currency_locked")
        if (history && previous!!.openingMinor != account.openingBalance.minor) invalid("openingBalance", "opening_locked")
        if (history && previous!!.kind != account.kind.name) invalid("kind", "account_kind_locked")
        val balance = when {
            previous == null || currencyChanged -> account.openingBalance.minor
            previous.openingMinor == account.openingBalance.minor -> previous.balanceMinor
            else -> Math.addExact(Math.subtractExact(previous.balanceMinor, previous.openingMinor), account.openingBalance.minor)
        }
        val normalized = account.copy(name = name, group = group, balance = Money(balance, account.currency),
            sortOrder = previous?.sortOrder ?: nextOrder(all.map { it.sortOrder }), archived = previous?.archived ?: false,
            aliases = account.aliases.map(String::trim).filter(String::isNotBlank).distinct())
        dao.upsertAccount(normalized.entity())
        normalized
    }

    suspend fun saveCategory(category: Category): CatalogResult<Category> = mutate {
        val name = checkedName(category.name)
        val book = dao.book(category.bookId)
        if (book == null || book.archived) invalid("bookId", "unavailable_book")
        if (category.kind !in setOf(BillKind.EXPENSE, BillKind.INCOME)) invalid("kind", "category_kind")
        val all = dao.categories(category.bookId)
        val previous = dao.category(category.id)
        if (previous != null && (previous.kind != category.kind.name || previous.parentId != category.parentId) &&
            dao.allBudgets().filter { it.bookId == category.bookId }.any { row -> row.model()?.categories.orEmpty().any { it.categoryId == category.id } }) {
            invalid("kind", "budget_category_locked")
        }
        if (previous != null && previous.bookId != category.bookId) invalid("bookId", "category_book_locked")
        if (previous != null && previous.kind != category.kind.name && dao.categoryReferenceCount(category.id) > 0) invalid("kind", "category_kind_locked")
        if (all.any { it.id != category.id && it.kind == category.kind.name && it.parentId == category.parentId && it.name.equals(name, true) }) {
            invalid("name", "duplicate_name")
        }
        category.parentId?.let { parentId ->
            val parent = dao.category(parentId)
            if (parent == null || parent.id == category.id || parent.bookId != category.bookId || parent.kind != category.kind.name ||
                parent.parentId != null || parent.archived || dao.children(category.id).isNotEmpty()) invalid("parentId", "invalid_parent")
        }
        if (dao.children(category.id).any { it.kind != category.kind.name }) invalid("kind", "category_kind_locked")
        val siblings = all.filter { it.kind == category.kind.name && it.parentId == category.parentId }
        val normalized = category.copy(name = name, archived = previous?.archived ?: false,
            sortOrder = if (previous != null && previous.parentId == category.parentId && previous.kind == category.kind.name) previous.sortOrder
                else nextOrder(siblings.map { it.sortOrder }))
        dao.upsertCategory(normalized.entity())
        normalized
    }

    suspend fun selectBook(id: String): CatalogResult<Unit> = mutate {
        val book = dao.book(id)
        if (book == null || book.archived) invalid("bookId", "unavailable_book")
        dao.upsertPreference(PreferenceEntity(CURRENT_BOOK, id))
    }

    suspend fun archiveBook(id: String): CatalogResult<Unit> = mutate {
        val book = dao.book(id) ?: invalid("bookId", "not_found")
        val remaining = dao.books().filter { !it.archived && it.id != id }
        if (!book.archived && remaining.isEmpty()) invalid("bookId", "last_book")
        dao.upsertBook(book.copy(archived = true))
        if (dao.preference(CURRENT_BOOK) == id) dao.upsertPreference(PreferenceEntity(CURRENT_BOOK, remaining.first().id))
    }

    suspend fun restoreBook(id: String): CatalogResult<Unit> = mutate {
        dao.upsertBook((dao.book(id) ?: invalid("bookId", "not_found")).copy(archived = false))
    }

    suspend fun archiveAccount(id: String): CatalogResult<Unit> = mutate {
        dao.upsertAccount((dao.account(id) ?: invalid("accountId", "not_found")).copy(archived = true))
    }

    suspend fun restoreAccount(id: String): CatalogResult<Unit> = mutate {
        dao.upsertAccount((dao.account(id) ?: invalid("accountId", "not_found")).copy(archived = false))
    }

    suspend fun archiveCategory(id: String): CatalogResult<Unit> = mutate {
        val category = dao.category(id) ?: invalid("categoryId", "not_found")
        dao.upsertCategory(category.copy(archived = true))
        dao.children(id).forEach { dao.upsertCategory(it.copy(archived = true)) }
    }

    suspend fun restoreCategory(id: String): CatalogResult<Unit> = mutate {
        val category = dao.category(id) ?: invalid("categoryId", "not_found")
        if (category.parentId != null && dao.category(category.parentId)?.archived != false) invalid("parentId", "invalid_parent")
        dao.upsertCategory(category.copy(archived = false))
    }

    suspend fun reorderAccounts(ids: List<String>): CatalogResult<Unit> = mutate {
        val all = dao.accounts().filterNot { it.archived }
        checkOrder(ids, all.map { it.id })
        val byId = all.associateBy { it.id }
        ids.forEachIndexed { order, id -> dao.upsertAccount(byId.getValue(id).copy(sortOrder = order)) }
    }

    suspend fun reorderAccountGroups(groups: List<String?>): CatalogResult<Unit> = mutate {
        val all = dao.accounts().filterNot { it.archived }
        val byGroup = all.groupBy { it.groupName }
        if (groups.size != byGroup.size || groups.toSet() != byGroup.keys) invalid("order", "invalid_order")
        groups.flatMap { byGroup.getValue(it) }.forEachIndexed { index, account -> dao.upsertAccount(account.copy(sortOrder = index)) }
    }

    suspend fun renameAccountGroup(previous: String, next: String): CatalogResult<Unit> = mutate {
        val name = checkedName(next)
        val all = dao.accounts()
        val members = all.filter { it.groupName == previous }
        if (members.isEmpty()) invalid("group", "not_found")
        if (name != previous && all.any { it.groupName == name }) invalid("group", "duplicate_name")
        members.forEach { dao.upsertAccount(it.copy(groupName = name)) }
    }

    suspend fun reorderBooks(ids: List<String>): CatalogResult<Unit> = mutate {
        val all = dao.books().filterNot { it.archived }
        checkOrder(ids, all.map { it.id })
        val byId = all.associateBy { it.id }
        ids.forEachIndexed { order, id -> dao.upsertBook(byId.getValue(id).copy(sortOrder = order)) }
    }

    suspend fun reorderCategories(bookId: String, kind: BillKind, parentId: String?, ids: List<String>): CatalogResult<Unit> = mutate {
        val all = dao.categories(bookId).filter { !it.archived && it.kind == kind.name && it.parentId == parentId }
        checkOrder(ids, all.map { it.id })
        val byId = all.associateBy { it.id }
        ids.forEachIndexed { order, id -> dao.upsertCategory(byId.getValue(id).copy(sortOrder = order)) }
    }

    private suspend fun createDefaultCategories(bookId: String) {
        val names = mapOf(
            BillKind.EXPENSE to listOf("餐饮", "交通", "购物", "住房", "日用品", "学习", "医疗", "娱乐", "旅行", "人情往来", "其他"),
            BillKind.INCOME to listOf("工资", "奖金", "兼职", "投资收益", "礼金", "其他"),
        )
        names.forEach { (kind, items) -> items.forEachIndexed { index, name ->
            dao.upsertCategory(CategoryEntity(UUID.randomUUID().toString(), bookId, name, kind.name, sortOrder = index))
        } }
    }

    private suspend fun <T> mutate(block: suspend () -> T): CatalogResult<T> = try {
        CatalogResult.Saved(database.withTransaction { block() })
    } catch (error: DraftValidationException) {
        CatalogResult.Invalid(error.errors)
    } catch (_: ArithmeticException) {
        CatalogResult.Invalid(listOf(FieldError("amount", "overflow", "Value exceeds supported range")))
    } catch (_: SQLiteException) {
        CatalogResult.Failure("storage")
    }

    private fun checkedName(name: String): String {
        val normalized = name.trim()
        if (normalized.isEmpty()) invalid("name", "name_required")
        if (normalized.length > 80 || normalized.any(Char::isISOControl)) invalid("name", "invalid_name")
        return normalized
    }

    private fun nextOrder(orders: List<Int>): Int = orders.maxOrNull()?.let { Math.addExact(it, 1) } ?: 0
    private fun checkOrder(ids: List<String>, current: List<String>) {
        if (ids.size != current.size || ids.toSet() != current.toSet()) invalid("order", "invalid_order")
    }
    private fun invalid(field: String, code: String): Nothing = throw DraftValidationException(listOf(FieldError(field, code, code)))

    companion object {
        const val CURRENT_BOOK = "current_book"
        private const val DEFAULT_BOOK = "default_book"
        private const val INITIALIZED = "catalog_initialized"
    }
}
