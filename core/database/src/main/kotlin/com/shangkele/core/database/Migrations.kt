package com.shangkele.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2：加照片表。
 *
 * 照片挂在笔记上，笔记删掉时照片行跟着 CASCADE 删（文件由仓库层清）。
 *
 * SQL 必须和 Room 从 [com.shangkele.core.database.entity.PhotoEntity]
 * 生成的定义**完全一致**：列类型、可空性、外键动作、索引名都会被逐项比对，
 * 差一点就会在打开数据库时报 `Migration didn't properly handle`。
 * 索引名是 Room 的命名规则 `index_<表>_<列1>_<列2>`。
 *
 * 注意：必须声明在 [ALL_MIGRATIONS] **之前**。Kotlin 顶层属性按声明顺序初始化，
 * 放后面的话 ALL_MIGRATIONS 会读到一个还没赋值的 null。
 */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `photo` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `noteId` INTEGER NOT NULL,
                `semesterId` INTEGER NOT NULL,
                `courseId` INTEGER,
                `offsetMs` INTEGER,
                `takenAtMs` INTEGER NOT NULL,
                `path` TEXT NOT NULL,
                `width` INTEGER NOT NULL,
                `height` INTEGER NOT NULL,
                `sizeBytes` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                FOREIGN KEY(`noteId`) REFERENCES `note`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_photo_noteId_offsetMs` " +
                "ON `photo` (`noteId`, `offsetMs`)",
        )
    }
}

/**
 * 迁移列表。
 *
 * 禁止使用 `fallbackToDestructiveMigration()`（见 docs/05-数据模型.md §七）——
 * 这条数据是用户一整个学期的课堂录音与笔记，丢了不可恢复。
 * 后续每次加字段都要在这里补一个 [Migration]，并把它加到下面数组里。
 */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_1_2,
)
