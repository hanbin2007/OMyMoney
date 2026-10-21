package app.omymoney.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.omymoney.core.budget.*
import app.omymoney.core.model.*
import app.omymoney.data.backup.*
import java.io.ByteArrayOutputStream
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

class BudgetCopyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: LedgerDatabase
    private lateinit var repository: BudgetRepository
    private lateinit var catalog: CatalogRepository
    private val book = UUID.randomUUID().toString()
    private val now = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC)
    private val current = BudgetPeriod(2026, 1)
    private val previous = BudgetPeriod(2025, 12)
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        repository = BudgetRepository(db, now); catalog = CatalogRepository(db)
        assertTrue(catalog.saveBook(Book(book, "Budget", zoneId = ZoneOffset.UTC)) is CatalogResult.Saved)
    }
    @After fun cleanup() { db.close() }
    private fun plan(period: BudgetPeriod = previous) = BudgetPlan(book, period, Money(100000, CurrencySpec.CNY))

    @Test fun copiedBudgetAndDeletionRemainStableAcrossRepositoryRecreationAndReopening() = runBlocking {
        assertTrue(repository.save(plan()) is CatalogResult.Saved)
        assertTrue(repository.copyPreviousIfEligible(book, current) is CatalogResult.Saved)
        val copied = repository.observe(book, current).first()!!
        assertEquals(plan(current).copy(copiedFrom = previous), copied)
        assertTrue(repository.save(plan().copy(total = Money(200000, CurrencySpec.CNY))) is CatalogResult.Saved)
        repository.copyPreviousIfEligible(book, current)
        assertEquals(100000L, repository.observe(book, current).first()!!.total.minor)
        assertTrue(repository.delete(book, current) is CatalogResult.Saved)
        repository = BudgetRepository(db, now)
        repeat(3) { repository.copyPreviousIfEligible(book, current) }
        assertNull(repository.observe(book, current).first())
        assertTrue(repository.copyPrevious(book, current) is CatalogResult.Saved)
        assertEquals(200000L, repository.observe(book, current).first()!!.total.minor)
    }

    @Test fun budgetValidationAndStorageFailureDoNotPartiallyReplaceLimits() = runBlocking {
        val category = catalog.observeCategories(book, BillKind.EXPENSE).first().first()
        val saved = plan().copy(categories = listOf(BudgetLimit(category.id, Money(60000, CurrencySpec.CNY))))
        assertTrue(repository.save(saved) is CatalogResult.Saved)
        assertTrue(repository.save(saved.copy(total = Money(50000, CurrencySpec.CNY))) is CatalogResult.Invalid)
        assertEquals(saved, repository.observe(book, previous).first())
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_budget BEFORE INSERT ON budgets BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertTrue(repository.save(saved.copy(total = Money(200000, CurrencySpec.CNY))) is CatalogResult.Failure)
        assertEquals(saved, repository.observe(book, previous).first())
    }

    @Test fun backupPreservesBudgetTombstonesAndConflictDoesNotPreventReplacement() = runBlocking {
        repository.save(plan())
        repository.copyPreviousIfEligible(book, current)
        repository.delete(book, current)
        val archive = BackupArchive(db, context)
        val bytes = ByteArrayOutputStream().also { archive.exportTo(it) }.toByteArray()
        repository.save(plan(current).copy(total = Money(200000, CurrencySpec.CNY)))
        val preview = archive.inspect(bytes.inputStream())
        assertTrue(preview.conflicts.any { it.kind.name == "BUDGET" })
        assertTrue(runCatching { archive.restore(preview.previewId, RestoreMode.MERGE) }.isFailure)
        archive.restore(preview.previewId, RestoreMode.REPLACE)
        repository.copyPreviousIfEligible(book, current)
        assertNull(repository.observe(book, current).first())
        assertEquals(plan(), repository.observe(book, previous).first())
    }

    @Test fun autoCopyOnlyUsesTheImmediatePredecessorForTheCurrentMonthOrYear() = runBlocking {
        repository.save(plan(BudgetPeriod(2025)))
        repository.save(plan())
        repository.copyPreviousIfEligible(book, BudgetPeriod(2026))
        assertEquals(BudgetPeriod(2025), repository.observe(book, BudgetPeriod(2026)).first()!!.copiedFrom)
        repository.copyPreviousIfEligible(book, BudgetPeriod(2026, 2))
        assertNull(repository.observe(book, BudgetPeriod(2026, 2)).first())
        assertTrue(repository.copyPrevious(book, BudgetPeriod(2026, 3)) is CatalogResult.Invalid)
        repository.delete(book, BudgetPeriod(2026))
        assertEquals(plan(), repository.observe(book, previous).first())
    }

    @Test fun budgetCurrencyAndReferencedCategoryMeaningCannotBeSilentlyChanged() = runBlocking {
        val category = catalog.observeCategories(book, BillKind.EXPENSE).first().first()
        repository.save(plan().copy(categories = listOf(BudgetLimit(category.id, Money(60000, CurrencySpec.CNY)))))
        val original = catalog.observeBooks().first().single()
        assertTrue(catalog.saveBook(original.copy(currency = CurrencySpec("USD", 2))) is CatalogResult.Invalid)
        assertTrue(catalog.saveCategory(category.copy(kind = BillKind.INCOME)) is CatalogResult.Invalid)
        assertTrue(catalog.saveCategory(category.copy(name = "Renamed")) is CatalogResult.Saved)
    }

    @Test fun firstYearMonthlyCopyOnlyRejectsJanuaryWhichHasNoSupportedPredecessor() = runBlocking {
        val january = BudgetPeriod(1, 1)
        assertTrue(repository.save(plan(january)) is CatalogResult.Saved)
        assertTrue(repository.copyPrevious(book, BudgetPeriod(1, 2)) is CatalogResult.Saved)
        assertEquals(january, repository.observe(book, BudgetPeriod(1, 2)).first()!!.copiedFrom)
        assertTrue(repository.copyPrevious(book, january) is CatalogResult.Invalid)
        assertTrue(repository.copyPrevious(book, BudgetPeriod(1)) is CatalogResult.Invalid)
    }
}
