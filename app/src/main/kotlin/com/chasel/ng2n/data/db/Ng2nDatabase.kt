package com.chasel.ng2n.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 唯一的 Room 库。RN 版分了 `ng2.db`(历史 + 帖子缓存)与 `notifications.db`(已读),
 * 这一版合成一个 —— 分库的唯一理由是 expo-sqlite 每个库一个连接,Room 没这个约束,
 * 而三张表本来就要在同一个事务里被清(设置页「清空缓存」)。
 *
 * ## 无迁移机制,照抄 RN 版策略
 *
 * `fallbackToDestructiveMigration` + 「换结构就换表名」(见 `Entities.kt` 的注释)。
 * 审计 P2-05 提过「SQLite 只有建表没有版本化迁移」,但 spec §一.5 列的**必修缺陷里没有它**
 * —— 这是本项目的明文选择而不是疏漏:三张表全是可重建的本机数据
 * (历史/缓存/已读),重建代价是「历史清了」,而写迁移的代价是每次改结构都要写测试。
 * 版本号在这里只有一个作用:改了 schema 忘记换表名时,让 Room 把库整个重建掉。
 *
 * `exportSchema = true`,schema JSON 落 `app/schemas/` **并进版本库**
 * (`app/build.gradle.kts` 的 `room { schemaDirectory(...) }` 是票 01 就配好的)。
 * 二选一里选进库这一档的理由:没有迁移测试,那份 JSON 就是「表结构曾经长什么样」的
 * 唯一书面记录,改结构时 diff 一眼能看出来动了哪列。
 */
@Database(
  entities = [
    BrowseHistoryEntity::class,
    TopicCacheEntity::class,
    NotificationReadEntity::class,
  ],
  version = 1,
  exportSchema = true,
)
abstract class Ng2nDatabase : RoomDatabase() {

  abstract fun browseHistoryDao(): BrowseHistoryDao

  abstract fun topicCacheDao(): TopicCacheDao

  abstract fun notificationReadDao(): NotificationReadDao


  companion object {
    /** 库文件名。换结构就换表名;真到了要换库的一天,这里也换。 */
    const val NAME = "ng2n.db"
  }
}
