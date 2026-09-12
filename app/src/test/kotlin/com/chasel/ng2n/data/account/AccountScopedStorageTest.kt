package com.chasel.ng2n.data.account

import com.chasel.ng2n.data.settings.FavoriteChange
import com.chasel.ng2n.data.settings.SettingsStore
import com.chasel.ng2n.data.settings.applyFavoriteChange
import com.chasel.ng2n.data.settings.foldersOfTopic
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountScopedStorageTest {

  @Test
  fun `切号之后收藏反向索引读到的是新 uid 那一份`() = runTest {
    val accounts = inMemoryAccountStore()
    val settings = SettingsStore(FakePreferencesDataStore())

    accounts.upsert(testAccount("1001"))
    accounts.upsert(testAccount("1002"))

    settings.updateTopicFavorIndex(accounts.accounts.first().currentUid) { index ->
      applyFavoriteChange(index, FavoriteChange(tid = 45150945, folderId = 7, favored = true))
    }
    accounts.switchTo("1001")
    settings.updateTopicFavorIndex(accounts.accounts.first().currentUid) { index ->
      applyFavoriteChange(index, FavoriteChange(tid = 12345678, folderId = 7, favored = true))
    }

    val asAccountA = settings.topicFavorIndex(accounts.currentUid.first()).first()
    assertEquals(listOf(7), foldersOfTopic(asAccountA, 12345678))
    assertEquals(emptyList(), foldersOfTopic(asAccountA, 45150945))

    accounts.switchTo("1002")

    val asAccountB = settings.topicFavorIndex(accounts.currentUid.first()).first()
    assertEquals(listOf(7), foldersOfTopic(asAccountB, 45150945))
    assertEquals(emptyList(), foldersOfTopic(asAccountB, 12345678), "不该串到 1001 那份")
  }

  @Test
  fun `全退光之后读到的是空索引 —— 游客态没有收藏夹`() = runTest {
    val accounts = inMemoryAccountStore()
    val settings = SettingsStore(FakePreferencesDataStore())

    accounts.upsert(testAccount("1001"))
    settings.updateTopicFavorIndex("1001") { index ->
      applyFavoriteChange(index, FavoriteChange(tid = 1, folderId = 3, favored = true))
    }
    accounts.remove("1001")

    val guest = settings.topicFavorIndex(accounts.currentUid.first()).first()
    assertEquals(emptyMap(), guest)
  }
}
