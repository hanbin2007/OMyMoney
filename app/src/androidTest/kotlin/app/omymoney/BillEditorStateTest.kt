package app.omymoney

import android.content.Context
import android.content.ContextWrapper
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.omymoney.core.model.*
import app.omymoney.data.EditorDraft
import app.omymoney.data.AttachmentStore
import app.omymoney.feature.bills.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import android.net.Uri

@RunWith(AndroidJUnit4::class)
class BillEditorStateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container get() = (context.applicationContext as OMyMoneyApplication).container
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test fun anUnclassifiedRecoveredDraftCanStillChooseABasicKind() = runBlocking {
        val book = Book(UUID.randomUUID().toString(), "Unknown kind ${UUID.randomUUID().toString().take(6)}")
        container.store.catalog.ensureLocalDefaults()
        container.store.catalog.saveBook(book)
        val draft = container.store.catalog.initialDraft(book.id).copy(kind = null, categoryId = null)
        container.store.drafts.write(EditorDraft("manual/${book.id}", draft, amountText = "12.00"))
        lateinit var editor: BillEditorViewModel
        onMain { editor = BillEditorViewModel(container.store.drafts, container.store.catalog, container.store.attachments, container.applicationScope, book.id) }
        withTimeout(5000) { editor.state.first { it.record != null } }
        onMain { editor.onAction(EditorAction.Kind(BillKind.EXPENSE)) }
        assertEquals(BillKind.EXPENSE, editor.state.value.draft!!.kind)
        onMain { editor.discard() }
        withTimeout(5000) { editor.state.first { it.closed } }
        Unit
    }

    @Test fun blockedImageImportCanBeCancelledKeptOrDiscarded(): Unit = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        container.store.catalog.ensureLocalDefaults()
        for (action in listOf("cancel", "keep", "discard")) {
            val book = Book(UUID.randomUUID().toString(), "取消图片 $action")
            container.store.catalog.saveBook(book)
            val pipe = ParcelFileDescriptor.createPipe()
            val provider = ImagePipeProvider(pipe[0]).apply {
                attachInfo(context, ProviderInfo().apply { authority = "test.image" })
            }
            val wrapped = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getContentResolver(): ContentResolver = ContentResolver.wrap(provider)
            }
            lateinit var editor: BillEditorViewModel
            try {
                onMain { editor = BillEditorViewModel(container.store.drafts, container.store.catalog,
                    AttachmentStore(wrapped), container.applicationScope, book.id) }
                withTimeout(5000) { editor.state.first { it.record != null } }
                onMain {
                    editor.onAction(EditorAction.Amount("18.25"))
                    editor.importAttachment(Uri.parse("content://test.image/blocked"))
                }
                delay(200)
                assertTrue(editor.state.value.importingAttachment)
                onMain { when (action) {
                    "cancel" -> editor.cancelAttachmentImport()
                    "keep" -> editor.keepAndClose()
                    else -> editor.discard()
                } }
                withTimeout(2000) { editor.state.first { !it.importingAttachment && (action == "cancel" || it.closed) } }
                assertTrue(editor.state.value.draft!!.attachments.isEmpty())
                if (action == "discard") assertNull(container.store.drafts.read("manual/${book.id}"))
                else {
                    withTimeout(5000) { editor.state.first { !it.persistencePending } }
                    assertEquals("18.25", container.store.drafts.read("manual/${book.id}")!!.amountText)
                    if (action == "cancel") {
                        onMain { editor.discard() }
                        withTimeout(5000) { editor.state.first { it.closed } }
                    }
                }
            } finally { pipe.forEach { runCatching { it.close() } } }
        }
    }

    private class ImagePipeProvider(private val pipe: ParcelFileDescriptor) : ContentProvider() {
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String) = pipe
        override fun getType(uri: Uri) = "image/png"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    @Test fun pickerResultArrivingDuringDraftLoadIsNotDropped() = runBlocking {
        val book = Book(UUID.randomUUID().toString(), "选择器恢复 ${UUID.randomUUID().toString().take(6)}")
        container.store.catalog.ensureLocalDefaults(); container.store.catalog.saveBook(book)
        lateinit var editor: BillEditorViewModel
        onMain {
            editor = BillEditorViewModel(container.store.drafts, container.store.catalog, container.store.attachments, container.applicationScope, book.id)
            editor.importAttachment(Uri.parse("content://nonexistent.omymoney.test/image"))
        }
        withTimeout(10_000) { editor.state.first { it.record != null } }
        withTimeout(2000) { editor.state.first { it.attachmentError != null } }
        assertEquals("unreadable", editor.state.value.attachmentError)
        onMain { editor.discard() }
        withTimeout(10_000) { editor.state.first { it.closed } }
        Unit
    }

    @Test fun attachmentFailurePreservesAllDraftInput() = runBlocking {
        val book = Book(UUID.randomUUID().toString(), "图片失败 ${UUID.randomUUID().toString().take(6)}")
        container.store.catalog.ensureLocalDefaults()
        container.store.catalog.saveBook(book)
        lateinit var editor: BillEditorViewModel
        onMain { editor = BillEditorViewModel(container.store.drafts, container.store.catalog, container.store.attachments, container.applicationScope, book.id) }
        withTimeout(10_000) { editor.state.first { it.record != null } }
        onMain {
            editor.onAction(EditorAction.Amount("18.25"))
            editor.onAction(EditorAction.Note("图片失败仍保留的备注"))
            editor.importAttachment(Uri.parse("content://nonexistent.omymoney.test/image"))
        }
        withTimeout(10_000) { editor.state.first { !it.importingAttachment && it.attachmentError != null } }
        assertEquals("unreadable", editor.state.value.attachmentError)
        assertEquals("18.25", editor.state.value.record!!.amountText)
        assertEquals("图片失败仍保留的备注", editor.state.value.draft!!.note)
        assertTrue(editor.state.value.draft!!.attachments.isEmpty())
        onMain { editor.discard() }
        withTimeout(10_000) { editor.state.first { it.closed } }
        Unit
    }

    @Test fun recoveredEditOnlyAllowsArchivedReferencesFromTheActualSavedBill() = runBlocking {
        val book = Book(UUID.randomUUID().toString(), "历史引用 ${UUID.randomUUID().toString().take(6)}")
        container.store.catalog.ensureLocalDefaults()
        container.store.catalog.saveBook(book)
        val first = AssetAccount(UUID.randomUUID().toString(), "原有账户 ${UUID.randomUUID().toString().take(6)}")
        val second = AssetAccount(UUID.randomUUID().toString(), "草稿账户 ${UUID.randomUUID().toString().take(6)}")
        container.store.catalog.saveAccount(first); container.store.catalog.saveAccount(second)
        val original = container.store.catalog.initialDraft(book.id).copy(fromAccountId = first.id, amount = Money(100, CurrencySpec.CNY))
        val saved = container.store.ledger.save(original, null) as SaveResult.Saved
        val edit = container.store.drafts.billForEditing(saved.billId)!!
        container.store.drafts.write(edit.copy(transaction = edit.transaction.copy(fromAccountId = second.id), dirty = true))
        container.store.catalog.archiveAccount(first.id); container.store.catalog.archiveAccount(second.id)
        lateinit var editor: BillEditorViewModel
        onMain { editor = BillEditorViewModel(container.store.drafts, container.store.catalog, container.store.attachments,
            container.applicationScope, book.id, saved.billId) }
        withTimeout(10_000) { editor.state.first { it.record != null } }
        assertEquals(setOf(first.id), editor.state.value.originalAccountIds)
        assertEquals(original.categoryId, editor.state.value.originalCategoryId)
        assertEquals(second.id, editor.state.value.draft!!.fromAccountId)
        onMain { editor.discard() }
        withTimeout(10_000) { editor.state.first { it.closed } }
        Unit
    }

    @Test fun rawInputsAreNeverSilentlyShortened() = runBlocking {
        val book = Book(UUID.randomUUID().toString(), "原始输入 ${UUID.randomUUID().toString().take(6)}")
        container.store.catalog.ensureLocalDefaults()
        container.store.catalog.saveBook(book)
        lateinit var editor: BillEditorViewModel
        onMain { editor = BillEditorViewModel(container.store.drafts, container.store.catalog, container.store.attachments, container.applicationScope, book.id) }
        withTimeout(10_000) { editor.state.first { it.record != null } }
        val raw = "0".repeat(63) + "12.34"
        val note = "完整备注".repeat(1100)
        onMain { editor.onAction(EditorAction.Amount(raw)); editor.onAction(EditorAction.Note(note)) }
        assertEquals(raw, editor.state.value.record!!.amountText)
        assertEquals(note, editor.state.value.draft!!.note)
        withTimeout(10_000) { editor.state.first { !it.persistencePending } }
        assertEquals(raw, container.store.drafts.read("manual/${book.id}")!!.amountText)
        onMain { editor.submit() }
        assertTrue(editor.state.value.errors.any { it.field == "amount" })
        assertTrue(editor.state.value.errors.any { it.field == "note" })
        assertTrue(container.store.ledger.observeBills(BillQuery(bookId = book.id)).first().isEmpty())
        onMain { editor.discard() }
        withTimeout(10_000) { editor.state.first { it.closed } }
        Unit
    }

    @Test fun changingEitherTransferAccountClearsTheCrossFieldError() = runBlocking {
        val book = Book(UUID.randomUUID().toString(), "转账修正 ${UUID.randomUUID().toString().take(6)}")
        container.store.catalog.ensureLocalDefaults()
        container.store.catalog.saveBook(book)
        val second = AssetAccount(UUID.randomUUID().toString(), "转账账户 ${UUID.randomUUID().toString().take(6)}")
        container.store.catalog.saveAccount(second)
        lateinit var editor: BillEditorViewModel
        onMain { editor = BillEditorViewModel(container.store.drafts, container.store.catalog, container.store.attachments, container.applicationScope, book.id) }
        withTimeout(10_000) { editor.state.first { it.record != null } }
        val firstId = editor.state.value.draft!!.fromAccountId!!
        onMain {
            editor.onAction(EditorAction.Amount("2.00"))
            editor.onAction(EditorAction.Kind(BillKind.TRANSFER))
            editor.onAction(EditorAction.ToAccount(firstId, CurrencySpec.CNY))
            editor.submit()
        }
        assertTrue(editor.state.value.errors.any { it.code == "same_account" })
        onMain { editor.onAction(EditorAction.FromAccount(second.id, second.currency)) }
        assertFalse(editor.state.value.errors.any { it.code == "same_account" })
        onMain { editor.discard() }
        withTimeout(10_000) { editor.state.first { it.closed } }
        Unit
    }

    @Test fun changingAnInvalidFieldRemovesItsStaleError() = runBlocking {
        val book = Book(UUID.randomUUID().toString(), "错误修正 ${UUID.randomUUID().toString().take(6)}")
        container.store.catalog.ensureLocalDefaults()
        container.store.catalog.saveBook(book)
        lateinit var editor: BillEditorViewModel
        onMain { editor = BillEditorViewModel(container.store.drafts, container.store.catalog, container.store.attachments, container.applicationScope, book.id) }
        withTimeout(10_000) { editor.state.first { it.record != null } }
        onMain { editor.submit() }
        assertTrue(editor.state.value.errors.any { it.field == "amount" })
        onMain { editor.onAction(EditorAction.Amount("6.78")) }
        assertFalse(editor.state.value.errors.any { it.field == "amount" })
        onMain { editor.discard() }
        withTimeout(10_000) { editor.state.first { it.closed } }
        Unit
    }

    @Test fun repeatedSubmitDuringOneFrameOnlyPostsOnce() = runBlocking {
        val book = Book(UUID.randomUUID().toString(), "连点 ${UUID.randomUUID().toString().take(6)}")
        container.store.catalog.ensureLocalDefaults()
        container.store.catalog.saveBook(book)
        lateinit var editor: BillEditorViewModel
        onMain { editor = BillEditorViewModel(container.store.drafts, container.store.catalog, container.store.attachments, container.applicationScope, book.id) }
        withTimeout(10_000) { editor.state.first { it.record != null } }
        onMain {
            editor.onAction(EditorAction.Amount("12.30"))
            repeat(20) { editor.submit() }
            editor.onAction(EditorAction.Amount("999.99"))
        }
        withTimeout(10_000) { editor.state.first { it.saved != null } }
        val bills = container.store.ledger.observeBills(BillQuery(bookId = book.id)).first()
        assertEquals(1, bills.size)
        assertEquals(1230L, bills.single().amount.minor)
        assertNull(container.store.drafts.read("manual/${book.id}"))
    }

    @Test fun restoredUnknownTimeIsNotReplacedByTheCurrentClock() = runBlocking {
        val book = Book(UUID.randomUUID().toString(), "待确认 ${UUID.randomUUID().toString().take(6)}")
        container.store.catalog.ensureLocalDefaults()
        container.store.catalog.saveBook(book)
        val original = container.store.catalog.initialDraft(book.id).copy(occurredAt = null, timeSource = TimeSource.UNKNOWN)
        container.store.drafts.write(EditorDraft("manual/${book.id}", original, amountText = "8.00", dirty = true))
        lateinit var editor: BillEditorViewModel
        onMain { editor = BillEditorViewModel(container.store.drafts, container.store.catalog, container.store.attachments, container.applicationScope, book.id) }
        withTimeout(10_000) { editor.state.first { it.record != null } }
        onMain { editor.submit() }
        assertNull(editor.state.value.draft!!.occurredAt)
        assertTrue(editor.state.value.errors.any { it.field == "occurredAt" })
        assertTrue(container.store.ledger.observeBills(BillQuery(bookId = book.id)).first().isEmpty())
        onMain { editor.discard() }
        withTimeout(10_000) { editor.state.first { it.closed } }
        Unit
    }

    @Test fun kindChangeKeepsSharedInputAndClearsIncompatibleFields() = runBlocking {
        val book = Book(UUID.randomUUID().toString(), "切换类型 ${UUID.randomUUID().toString().take(6)}")
        container.store.catalog.ensureLocalDefaults()
        container.store.catalog.saveBook(book)
        lateinit var editor: BillEditorViewModel
        onMain { editor = BillEditorViewModel(container.store.drafts, container.store.catalog, container.store.attachments, container.applicationScope, book.id) }
        withTimeout(10_000) { editor.state.first { it.record != null } }
        val original = editor.state.value.draft!!
        onMain {
            editor.onAction(EditorAction.Amount("21.50"))
            editor.onAction(EditorAction.Note("共用备注"))
            editor.onAction(EditorAction.Kind(BillKind.INCOME))
        }
        assertNull(editor.state.value.draft!!.fromAccountId)
        assertEquals(original.fromAccountId, editor.state.value.draft!!.toAccountId)
        assertNull(editor.state.value.draft!!.categoryId)
        assertEquals("21.50", editor.state.value.record!!.amountText)
        assertEquals("共用备注", editor.state.value.draft!!.note)
        onMain { editor.onAction(EditorAction.Kind(BillKind.TRANSFER)) }
        assertEquals(original.fromAccountId, editor.state.value.draft!!.fromAccountId)
        assertNull(editor.state.value.draft!!.toAccountId)
        onMain { editor.discard() }
        withTimeout(10_000) { editor.state.first { it.closed } }
        Unit
    }
}
