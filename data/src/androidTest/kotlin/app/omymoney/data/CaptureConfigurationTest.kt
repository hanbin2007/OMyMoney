package app.omymoney.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CaptureConfigurationTest {
    @Test fun recognitionContextReadsDoNotWriteOrInvalidateUnchangedPreferences() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val catalog = CatalogRepository(db)
            val original = catalog.recognitionContext()
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_pref_update BEFORE UPDATE ON preferences BEGIN SELECT RAISE(ABORT, 'unexpected preference write'); END")
            repeat(10) { assertEquals(original, catalog.recognitionContext()) }
        } finally { db.close() }
    }

    @Test fun unrelatedPreferenceWritesDoNotReemitIdenticalCaptureSettings() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val states = java.util.Collections.synchronizedList(mutableListOf<AutomationSettingsState>())
        val job = launch(Dispatchers.Default) { AutomationSettingsRepository(db).observe().collect { states += it } }
        try {
            withTimeout(3000) { while (states.isEmpty()) delay(10) }
            db.ledgerDao().upsertPreference(PreferenceEntity("unrelated", UUID.randomUUID().toString()))
            delay(250)
            assertEquals(1, states.size)
            AutomationSettingsRepository(db).setEnabled(true)
            withTimeout(3000) { while (states.size < 2) delay(10) }
            assertTrue(states.last().settings.enabled)
        } finally { job.cancelAndJoin(); db.close() }
    }
}
