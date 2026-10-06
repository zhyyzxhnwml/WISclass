package com.shangkele.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.shangkele.core.database.entity.AssignmentEntity
import kotlinx.coroutines.flow.Flow

/**
 * 作业 / 待办的读写。
 *
 * `assignment` 表从 v2 起就在 schema 里，只是从来没有 DAO ——
 * 所以这里**不需要新的迁移**，加方法即可。
 */
@Dao
interface AssignmentDao {

    @Insert
    suspend fun insert(assignment: AssignmentEntity): Long

    /**
     * 同一条笔记里标题一样的，算同一条。
     *
     * 用于去重：一张照片可能被读两次（用户手动重读、或先失败后重试），
     * 不去重的话日程里会冒出两行一模一样的作业。
     */
    @Query("SELECT * FROM assignment WHERE noteId IS :noteId AND title = :title LIMIT 1")
    suspend fun findByNoteAndTitle(noteId: Long?, title: String): AssignmentEntity?

    /**
     * 按截止时间排的待办。
     *
     * `dueEpochDay IS NOT NULL` 先排前面：解析不出日期的那些（原话如「期末之前交」）
     * 放在最后，而不是混在时间轴里插队 —— 它们本来就没有位置。
     */
    @Query(
        """
        SELECT * FROM assignment
        ORDER BY (dueEpochDay IS NULL), dueEpochDay, id
        """,
    )
    fun observeAll(): Flow<List<AssignmentEntity>>

    @Query("SELECT * FROM assignment WHERE noteId = :noteId ORDER BY (dueEpochDay IS NULL), dueEpochDay, id")
    fun observeByNote(noteId: Long): Flow<List<AssignmentEntity>>

    @Query("SELECT * FROM assignment WHERE done = 0 AND dueEpochDay IS NOT NULL ORDER BY dueEpochDay, id")
    suspend fun getPending(): List<AssignmentEntity>

    @Query("UPDATE assignment SET done = :done WHERE id = :id")
    suspend fun setDone(id: Long, done: Boolean)

    @Query("UPDATE assignment SET remindAtMs = :remindAtMs WHERE id = :id")
    suspend fun setRemindAt(id: Long, remindAtMs: Long?)

    @Query("DELETE FROM assignment WHERE id = :id")
    suspend fun delete(id: Long)
}
