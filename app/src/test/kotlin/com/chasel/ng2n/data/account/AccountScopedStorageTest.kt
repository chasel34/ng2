package com.chasel.ng2n.data.account

import com.chasel.ng2n.data.settings.FavoriteChange
import com.chasel.ng2n.data.settings.SettingsStore
import com.chasel.ng2n.data.settings.applyFavoriteChange
import com.chasel.ng2n.data.settings.foldersOfTopic
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 票 15 验收②:**切号之后按 uid 隔离的数据真的换了一份**(修 P1-02 的端到端那一半)。
 *
 * 存储侧票 14 已经按 uid 分了键(`topic-favor-index/v1/<uid>`);这里证明的是
 * 「读的时候用的是**当前** uid」—— RN 版栽的就是这一步:
 * `src/store/topic-favor.ts:40-43` 的 TanStack Query key 里没有 uid,
 * 于是切号后短暂或长期显示上一个账号的收藏(审计 P1-02)。
 *
 * 这条链在原生这边长这样:`AccountStore.currentUid` → 仓库层的键 →
 * `SettingsStore.topicFavorIndex(uid)`。票 16/17 的仓库照这个接法用。
 */
class AccountScopedStorageTest {

  @Test
  fun `切号之后收藏反向索引读到的是新 uid 那一份`() = runTest {
    val accounts = inMemoryAccountStore()
    val settings = SettingsStore(FakePreferencesDataStore())

    accounts.upsert(testAccount("1001"))
    accounts.upsert(testAccount("1002"))

    // 1002 是当前账号:给它记一条收藏
    settings.updateTopicFavorIndex(accounts.accounts.first().currentUid) { index ->
      applyFavoriteChange(index, FavoriteChange(tid = 45150945, folderId = 7, favored = true))
    }
    accounts.switchTo("1001")
    // 切到 1001 之后再记一条,夹 id 故意与 1002 撞车(审计 P1-02 点名的「相同 folder ID 碰撞」)
    settings.updateTopicFavorIndex(accounts.accounts.first().currentUid) { index ->
      applyFavoriteChange(index, FavoriteChange(tid = 12345678, folderId = 7, favored = true))
    }

    // 当前是 1001:只看得见 1001 自己的那条
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
