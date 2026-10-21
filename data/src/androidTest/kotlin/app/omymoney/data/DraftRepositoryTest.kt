package app.omymoney.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.omymoney.core.model.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DraftRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "draft-${UUID.randomUUID()}.db"
    private lateinit var db: LedgerDatabase
    private lateinit var drafts: DraftRepository
    private lateinit var draft: EditorDraft
    @Before fun setup() = runBlocking {
        db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
        drafts = DraftRepository(db)
        val transaction = CatalogRepository(db).initialDraft()
        draft = EditorDraft("manual/${transaction.bookId}", transaction, amountText = "12.30", dirty = true)
    }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }

    @Test fun invalidRawInputAndMetadataSurviveFileReopen() = runBlocking {
        val raw = draft.copy(amountText = "1..2", tagsText = "  午饭,  工作 ", transaction = draft.transaction.copy(note = "未提交备注"))
        drafts.write(raw)
        db.close()
        db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
        drafts = DraftRepository(db)
        assertEquals(raw, drafts.read(raw.key))
        assertEquals(0, db.ledgerDao().billCount())
    }
    @Test fun duplicateSubmitRemovesDraftAndPostsExactlyOnce() = runBlocking {
        val valid = draft.copy(transaction = draft.transaction.copy(amount = Money(1230, CurrencySpec.CNY)))
        drafts.write(valid)
        val first = drafts.submit(valid)
        assertTrue(first is SaveResult.Saved)
        assertEquals(first, drafts.submit(valid))
        assertNull(drafts.read(valid.key))
        assertEquals(1, db.ledgerDao().billCount())
        assertEquals(-1230L, db.ledgerDao().account(valid.transaction.fromAccountId!!)!!.balanceMinor)
    }
    @Test fun unknownTimeAndInvalidAmountKeepDraftWithoutBalanceChange() = runBlocking {
        val unknown = draft.copy(transaction = draft.transaction.copy(amount = Money(100, CurrencySpec.CNY), occurredAt = null, timeSource = TimeSource.UNKNOWN))
        drafts.write(unknown)
        assertTrue(drafts.submit(unknown) is SaveResult.Invalid)
        assertEquals(unknown, drafts.read(unknown.key))
        assertEquals(0, db.ledgerDao().billCount())
        assertEquals(0L, db.ledgerDao().account(unknown.transaction.fromAccountId!!)!!.balanceMinor)
    }
    @Test fun draftRemovalFailureRollsBackTheBillAndBalance() = runBlocking {
        val valid = draft.copy(transaction = draft.transaction.copy(amount = Money(1230, CurrencySpec.CNY)))
        drafts.write(valid)
        assertNotNull(drafts.read(valid.key))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_draft_delete BEFORE DELETE ON drafts BEGIN SELECT RAISE(ABORT, 'test fault'); END")
        assertTrue(drafts.submit(valid) is SaveResult.Failure)
        assertNotNull(drafts.read(valid.key))
        assertEquals(0, db.ledgerDao().billCount())
        assertEquals(0L, db.ledgerDao().account(valid.transaction.fromAccountId!!)!!.balanceMinor)
    }
}
