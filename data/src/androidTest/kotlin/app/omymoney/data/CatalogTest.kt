package app.omymoney.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.omymoney.core.model.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: LedgerDatabase
    private lateinit var catalog: CatalogRepository
    private val clock = Clock.fixed(Instant.parse("2026-10-11T10:00:00Z"), ZoneOffset.UTC)
    private var fileName: String? = null

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        catalog = CatalogRepository(db, clock)
    }
    @After fun cleanup() { db.close(); fileName?.let(context::deleteDatabase) }

    private fun <T> value(result: CatalogResult<T>): T {
        assertTrue("Expected Saved, got $result", result is CatalogResult.Saved)
        return (result as CatalogResult.Saved<T>).value
    }

    @Test fun repeatedInitializationCreatesOneUsableLocalBook() = runBlocking {
        val first = catalog.ensureLocalDefaults()
        assertTrue(first.isNotBlank())
        assertEquals(first, catalog.ensureLocalDefaults())
        assertEquals(1, catalog.observeBooks().first().size)
        assertEquals(1, catalog.observeAccounts().first().size)
        assertTrue(catalog.observeCategories(first, BillKind.EXPENSE).first().isNotEmpty())
        assertEquals(first, catalog.initialDraft().bookId)
    }

    @Test fun categoriesStayInTheirOwnBookAndCannotUseAForeignParent() = runBlocking {
        val original = catalog.ensureLocalDefaults()
        val trip = value(catalog.saveBook(Book(UUID.randomUUID().toString(), "旅行")))
        val custom = Category(UUID.randomUUID().toString(), trip.id, "登山", BillKind.EXPENSE)
        value(catalog.saveCategory(custom))
        assertTrue(catalog.observeCategories(original, BillKind.EXPENSE).first().none { it.id == custom.id })
        assertTrue(catalog.observeCategories(trip.id, BillKind.EXPENSE).first().any { it.id == custom.id })
        val foreignParent = catalog.observeCategories(original, BillKind.EXPENSE).first().first()
        assertTrue(catalog.saveCategory(custom.copy(parentId = foreignParent.id)) is CatalogResult.Invalid)
    }

    @Test fun categoriesPermitOnlyTwoLevelsWithoutCyclesOrKindMismatch() = runBlocking {
        val book = catalog.ensureLocalDefaults()
        val parent = value(catalog.saveCategory(Category(UUID.randomUUID().toString(), book, "通勤", BillKind.EXPENSE)))
        val child = value(catalog.saveCategory(Category(UUID.randomUUID().toString(), book, "公交", BillKind.EXPENSE, parent.id)))
        assertTrue(catalog.saveCategory(Category(UUID.randomUUID().toString(), book, "三级", BillKind.EXPENSE, child.id)) is CatalogResult.Invalid)
        assertTrue(catalog.saveCategory(parent.copy(parentId = child.id)) is CatalogResult.Invalid)
        assertTrue(catalog.saveCategory(child.copy(kind = BillKind.INCOME)) is CatalogResult.Invalid)
    }

    @Test fun selectedBookAndNewDraftShareOnePersistedSelection() = runBlocking {
        catalog.ensureLocalDefaults()
        val trip = value(catalog.saveBook(Book(UUID.randomUUID().toString(), "旅行")))
        value(catalog.selectBook(trip.id))
        assertEquals(trip.id, catalog.observeCurrentBookId().first())
        assertEquals(trip.id, catalog.initialDraft().bookId)
        assertEquals(trip.id, CatalogRepository(db, clock).initialDraft().bookId)
    }

    @Test fun archivingSelectedBookSelectsAnotherAndCannotArchiveTheLastBook() = runBlocking {
        val original = catalog.ensureLocalDefaults()
        val trip = value(catalog.saveBook(Book(UUID.randomUUID().toString(), "旅行")))
        value(catalog.selectBook(trip.id))
        value(catalog.archiveBook(trip.id))
        assertEquals(original, catalog.observeCurrentBookId().first())
        assertTrue(catalog.archiveBook(original) is CatalogResult.Invalid)
        assertEquals(1, catalog.observeBooks().first().size)
    }

    @Test fun archivingReferencedCatalogItemsPreservesHistoryAndBalances() = runBlocking {
        val book = catalog.ensureLocalDefaults()
        val account = catalog.observeAccounts().first().first()
        val category = catalog.observeCategories(book, BillKind.EXPENSE).first().first()
        val result = RoomLedgerRepository(db, clock).save(catalog.initialDraft().copy(
            amount = Money(1230, account.currency), fromAccountId = account.id, categoryId = category.id,
        ), null)
        assertTrue(result is SaveResult.Saved)
        value(catalog.archiveAccount(account.id))
        value(catalog.archiveCategory(category.id))
        assertTrue(catalog.observeAccounts(includeArchived = true).first().first { it.id == account.id }.archived)
        assertTrue(catalog.observeCategories(book, BillKind.EXPENSE, includeArchived = true).first().first { it.id == category.id }.archived)
        assertEquals(1, db.ledgerDao().billCount())
        assertEquals(-1230L, db.ledgerDao().account(account.id)!!.balanceMinor)
    }

    @Test fun metadataEditCannotOverwriteLiveBalanceOrHistoricalCurrency() = runBlocking {
        catalog.ensureLocalDefaults()
        val initial = AssetAccount(UUID.randomUUID().toString(), "银行卡", openingBalance = Money(10000, CurrencySpec.CNY))
        value(catalog.saveAccount(initial))
        assertTrue(RoomLedgerRepository(db, clock).save(catalog.initialDraft().copy(
            amount = Money(1230, CurrencySpec.CNY), fromAccountId = initial.id), null) is SaveResult.Saved)
        val renamed = value(catalog.saveAccount(initial.copy(name = "主账户")))
        assertEquals(8770L, renamed.balance.minor)
        assertTrue(catalog.saveAccount(renamed.copy(openingBalance = Money(20000, CurrencySpec.CNY))) is CatalogResult.Invalid)
        val usd = CurrencySpec("USD", 2)
        assertTrue(catalog.saveAccount(renamed.copy(currency = usd, openingBalance = Money(10000, usd), balance = Money(8770, usd))) is CatalogResult.Invalid)
        assertEquals(8770L, db.ledgerDao().account(initial.id)!!.balanceMinor)
    }

    @Test fun fileReopenKeepsSelectionAndAccountOrder() = runBlocking {
        db.close()
        fileName = "catalog-${UUID.randomUUID()}.db"
        db = Room.databaseBuilder(context, LedgerDatabase::class.java, fileName!!).build()
        catalog = CatalogRepository(db, clock)
        catalog.ensureLocalDefaults()
        val trip = value(catalog.saveBook(Book(UUID.randomUUID().toString(), "旅行")))
        value(catalog.selectBook(trip.id))
        value(catalog.saveAccount(AssetAccount(UUID.randomUUID().toString(), "账户 A")))
        value(catalog.saveAccount(AssetAccount(UUID.randomUUID().toString(), "账户 B")))
        val order = catalog.observeAccounts().first().map { it.id }.reversed()
        value(catalog.reorderAccounts(order))
        db.close()
        db = Room.databaseBuilder(context, LedgerDatabase::class.java, fileName!!).build()
        catalog = CatalogRepository(db, clock)
        assertEquals(trip.id, catalog.initialDraft().bookId)
        assertEquals(order, catalog.observeAccounts().first().map { it.id })
    }
}
