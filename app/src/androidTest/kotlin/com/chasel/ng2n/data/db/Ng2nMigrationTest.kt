package com.chasel.ng2n.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chasel.ng2n.data.StorageBootstrap
import com.chasel.ng2n.data.cache.TopicCacheRepository
import com.chasel.ng2n.data.history.HistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Ng2nMigrationTest {

  private val instrumentation = InstrumentationRegistry.getInstrumentation()

  @get:Rule
  val helper = MigrationTestHelper(instrumentation, Ng2nDatabase::class.java)

  @Test
  fun 从版本_1_迁到_2_原有数据保留_书签表可读写() = runTest {
    val name = "migration-test.db"
    helper.createDatabase(name, 1).apply {
      execSQL(
        "INSERT INTO browse_history (tid, subject, author, board_name, fav_code, last_floor, max_floor, updated_at) " +
          "VALUES (7, '主题 7', '作者', '网事杂谈', NULL, 18, 40, 100)",
      )
      execSQL(
        "INSERT INTO topic_cache (tid, page, subject, board_name, fav_code, floors, total_pages, bytes, payload, used_at) " +
          "VALUES (7, 1, '主题 7', NULL, NULL, 20, 3, 2, '{}', 100)",
      )
      execSQL("INSERT INTO notification_read (uid, id, read_at) VALUES ('1', '1786100000-2-1-1', 1)")
      close()
    }

    helper.runMigrationsAndValidate(name, 2, true)

    val db = Room.databaseBuilder(instrumentation.targetContext, Ng2nDatabase::class.java, name)
      .allowMainThreadQueries()
      .build()
    try {
      val history = db.browseHistoryDao().find(7)
      assertNotNull(history)
      assertEquals(18, history!!.lastFloor)
      assertEquals("{}", db.topicCacheDao().readPayload(7, 1))
      assertEquals(listOf("1786100000-2-1-1"), db.notificationReadDao().readIds("1"))

      val dao = db.bookmarkDao()
      dao.upsert(bookmark(tid = 7, pid = 0, lou = 0))
      assertEquals(1, dao.byTid(7).size)
    } finally {
      db.close()
    }
  }

  @Test
  fun 数据库打不开时启动预热抛出异常而不是吞掉() {
    val context = instrumentation.targetContext
    val file = context.getDatabasePath("downgrade-test.db")
    file.parentFile?.mkdirs()
    file.delete()
    SQLiteDatabase.openOrCreateDatabase(file, null).use { it.version = 99 }

    val db = Room.databaseBuilder(context, Ng2nDatabase::class.java, file.name).build()
    val history = HistoryRepository(db.browseHistoryDao(), db.bookmarkDao(), CoroutineScope(Dispatchers.IO))
    val bootstrap = StorageBootstrap(db, history, TopicCacheRepository(db.topicCacheDao()), CoroutineScope(Dispatchers.IO))
    try {
      assertThrows(IllegalStateException::class.java) { bootstrap.open() }
    } finally {
      db.close()
      file.delete()
    }
  }
}

internal fun bookmark(tid: Long, pid: Long, lou: Long, note: String? = null, createdAt: Long = 100) = BookmarkEntity(
  tid = tid,
  pid = pid,
  lou = lou,
  author = "作者",
  summary = "第 $lou 楼",
  note = note,
  subject = "主题 $tid",
  boardName = null,
  favCode = null,
  createdAt = createdAt,
  updatedAt = createdAt,
)
