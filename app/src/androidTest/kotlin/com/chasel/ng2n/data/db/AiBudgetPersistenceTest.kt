package com.chasel.ng2n.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.chasel.ng2n.core.ai.*
import com.chasel.ng2n.data.ai.*
import com.chasel.ng2n.data.ai.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AiBudgetPersistenceTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  @get:Rule val helper = MigrationTestHelper(instrumentation, Ng2nDatabase::class.java)
  @Test fun migrationAtomicReservationsReopenAndDeleteKeepUsage() = runTest {
    val name = "ai-budget-test.db"
    helper.createDatabase(name, 3).apply {
      execSQL("INSERT INTO ai_usage (requestId,conversationId,runId,startedAt,status) VALUES ('old','c','r',1,'pending_verification')")
      close()
    }
    helper.runMigrationsAndValidate(name, 4, true).close()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val prefsFile = File(instrumentation.targetContext.cacheDir, "budget-${System.nanoTime()}.preferences_pb")
    val settings = AiSettingsStore(PreferenceDataStoreFactory.create(scope = scope) { prefsFile })
    settings.setDailyLimit(32)
    var db = Room.databaseBuilder(instrumentation.targetContext, Ng2nDatabase::class.java, name).build()
    try {
      var store = AiBudgetStore(db, settings)
      val a = store.begin("one")
      val b = store.begin("two")
      val accepted = listOf(a, b).map { id -> async(Dispatchers.IO) { runCatching { store.reserve(id, 10_000, 0) }.isSuccess } }.awaitAll()
      assertEquals(1, accepted.count { it })
      val before = store.books.first().requests.single { !it.id.startsWith("legacy:") }
      db.close()
      db = Room.databaseBuilder(instrumentation.targetContext, Ng2nDatabase::class.java, name).build()
      store = AiBudgetStore(db, settings)
      store.initialize()
      db.aiConversationDao().clear()
      val after = store.books.first().requests.single { !it.id.startsWith("legacy:") }
      assertEquals(before.reserved, after.reserved)
      assertEquals("pending_verification", after.status)
      assertEquals(1, db.aiConversationDao().usageCount())
      store.change { it.settle(after.id, 100, 10, 50) }
      val settled = store.books.first()
      store.change { it.settle(after.id, 999, 999) }
      assertEquals(settled, store.books.first())
      val unsent = AiRunBudget(store, store.begin("unsent"))
      unsent.reserve(100, 0)
      val unsentId = unsent.requestId!!
      unsent.finish()
      db.close()
      db = Room.databaseBuilder(instrumentation.targetContext, Ng2nDatabase::class.java, name).build()
      store = AiBudgetStore(db, settings)
      store.initialize()
      val released = store.books.first().requests.single { it.id == unsentId }
      assertEquals("not_sent", released.status)
      assertEquals(0L, released.charged)
    } finally { db.close(); scope.cancel(); prefsFile.delete() }
  }
}
