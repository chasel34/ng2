package com.chasel.ng2n.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chasel.ng2n.data.ai.RoomAiConversationStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiPersistenceTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  @get:Rule val helper = MigrationTestHelper(instrumentation, Ng2nDatabase::class.java)
  private fun conversation() = AiConversationEntity("conversation", "测试标题", "主题", 7, "主题 7", 1, 2, "生成中", "第 1 页", "{}", null)

  @Test fun migrationPreservesBookmarksAndNewTablesSurviveReopen() = runTest {
    val name = "ai-migration.db"
    helper.createDatabase(name, 2).apply {
      execSQL("INSERT INTO bookmark (tid,pid,lou,author,summary,note,subject,board_name,fav_code,created_at,updated_at) VALUES (7,0,0,'作者','摘要','备注','主题',NULL,NULL,1,2)")
      close()
    }
    helper.runMigrationsAndValidate(name, 3, true).close()
    Room.databaseBuilder(instrumentation.targetContext, Ng2nDatabase::class.java, name).build().let { db ->
      try {
        assertEquals("备注", db.bookmarkDao().byTid(7).single().note)
        val dao = db.aiConversationDao()
        dao.put(conversation())
        dao.messages(listOf(AiMessageEntity("conversation", 0, "部分回答")))
        dao.ranges(listOf(AiReadingRangeEntity("conversation", 0, "第 1 页", true)))
        dao.sources(listOf(AiSourceEntity("conversation", "s1", 7, 0, 0, 1, "作者", "昨天", 2, "hash")))
        dao.run(AiRunEntity("run", "conversation", "running", "读取第 2 页", 2))
        dao.work(AiWorkingStateEntity("conversation", "checkpoint", "run", "checkpoint"))
      } finally { db.close() }
    }
    Room.databaseBuilder(instrumentation.targetContext, Ng2nDatabase::class.java, name).build().let { db ->
      try {
        val store = RoomAiConversationStore(db)
        store.initialize()
        val saved = store.load("conversation")!!
        assertEquals("已中断", saved.conversation.status)
        assertEquals("interrupted", saved.run!!.status)
        assertEquals("读取第 2 页", saved.run.step)
        assertEquals("部分回答", saved.messages.single().payload)
        assertEquals("第 1 页", saved.ranges.single().label)
        assertEquals("hash", saved.sources.single().contentHash)
        assertEquals("checkpoint", store.work("conversation", "checkpoint", "run"))
        assertEquals("备注", db.bookmarkDao().byTid(7).single().note)
        assertEquals(0, db.aiConversationDao().usageCount())
      } finally { db.close() }
    }
  }

  @Test fun deleteUndoThenCascadeKeepsUsageAndPreventsLateResurrection() = runTest {
    val db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, Ng2nDatabase::class.java).build()
    try {
      val store = RoomAiConversationStore(db)
      store.initialize()
      store.save(conversation(), listOf(AiMessageEntity("conversation", 0, "回答")), emptyList(), emptyList(),
        AiRunEntity("run", "conversation", "completed", "已完成", 2))
      store.work("conversation", "checkpoint", "run", "checkpoint")
      store.work("conversation", "chat_memory", "current", "memory")
      store.requestStarted("conversation", "run")
      store.delete("conversation")
      assertTrue(store.conversations.first().isEmpty())
      store.undo("conversation")
      store.confirmDelete("conversation")
      assertEquals(1, store.conversations.first().size)
      assertEquals("回答", store.load("conversation")!!.messages.single().payload)
      assertEquals("checkpoint", store.work("conversation", "checkpoint", "run"))
      store.delete("conversation"); store.confirmDelete("conversation")
      assertNull(store.load("conversation"))
      assertNull(store.work("conversation", "checkpoint", "run"))
      assertNull(store.work("conversation", "chat_memory", "current"))
      assertTrue(db.aiConversationDao().messages("conversation").isEmpty())
      assertNull(db.aiConversationDao().latestRun("conversation"))
      assertEquals(1, db.aiConversationDao().usageCount())
      assertTrue(runCatching { store.save(conversation(), emptyList(), emptyList(), emptyList(), null) }.isFailure)
      assertTrue(store.conversations.first().isEmpty())
    } finally { db.close() }
  }

  @Test fun databaseWriteFailureIsReportedAndDoesNotCreateUsage() = runTest {
    val db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, Ng2nDatabase::class.java).build()
    try {
      val store = RoomAiConversationStore(db)
      store.initialize()
      db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_ai_insert BEFORE INSERT ON ai_conversation BEGIN SELECT RAISE(ABORT, 'injected storage failure'); END")
      assertTrue(runCatching { store.save(conversation(), emptyList(), emptyList(), emptyList(), null) }.exceptionOrNull() is com.chasel.ng2n.data.ai.AiStorageException)
      assertTrue(store.conversations.first().isEmpty())
      assertEquals(0, db.aiConversationDao().usageCount())
    } finally { db.close() }
  }
  @Test fun failedClearPreservesProgressAndAllowsSavingWithoutRevivingDeletedConversations() = runTest {
    val db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, Ng2nDatabase::class.java).build()
    try {
      val store = RoomAiConversationStore(db)
      store.initialize()
      val removed = conversation().copy(id = "removed")
      store.save(removed, emptyList(), emptyList(), emptyList(), null)
      store.delete(removed.id)
      store.confirmDelete(removed.id)
      store.save(conversation(), listOf(AiMessageEntity("conversation", 0, "草稿")), emptyList(), emptyList(),
        AiRunEntity("run", "conversation", "interrupted", "生成回答", 2))
      store.work("conversation", "checkpoint", "run", "checkpoint")
      store.work("conversation", "chat_memory", "current", "memory")
      store.requestStarted("conversation", "run")
      db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_ai_delete BEFORE DELETE ON ai_conversation BEGIN SELECT RAISE(ABORT, 'injected storage failure'); END")
      assertTrue(runCatching { store.clear() }.exceptionOrNull() is com.chasel.ng2n.data.ai.AiStorageException)
      db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_ai_delete")
      assertEquals(1, store.conversations.first().size)
      assertEquals("草稿", store.load("conversation")!!.messages.single().payload)
      assertEquals("checkpoint", store.work("conversation", "checkpoint", "run"))
      assertEquals("memory", store.work("conversation", "chat_memory", "current"))
      assertEquals(1, db.aiConversationDao().usageCount())
      store.save(conversation(), listOf(AiMessageEntity("conversation", 0, "恢复后的回答")), emptyList(), emptyList(), null)
      assertEquals("恢复后的回答", store.load("conversation")!!.messages.single().payload)
      assertTrue(runCatching { store.save(removed, emptyList(), emptyList(), emptyList(), null) }.isFailure)
      store.clear()
      assertTrue(store.conversations.first().isEmpty())
      assertNull(store.work("conversation", "checkpoint", "run"))
      assertEquals(1, db.aiConversationDao().usageCount())
      assertTrue(runCatching { store.save(conversation(), emptyList(), emptyList(), emptyList(), null) }.isFailure)
    } finally { db.close() }
  }

}
