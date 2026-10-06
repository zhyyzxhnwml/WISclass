package com.shangkele.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shangkele.core.database.entity.ChangeLogEntity
import com.shangkele.core.database.entity.CourseEntity
import com.shangkele.core.database.entity.SemesterEntity
import com.shangkele.core.database.entity.TimeSlotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SemesterDao {

    /**
     * 用 List 返回而不是 Flow<T?>，避免不同 Room 版本对可空单值 Flow 的差异。
     *
     * `ORDER BY id DESC` 不是可有可无的：万一出现两条 `isActive = 1`
     * （历史版本留下、或事务被中断），不加排序时「写入时选中的行」和
     * 「界面上读到的行」可能不是同一条 —— 表现就是「改了没反应」。
     * 两边都取最新那条，至少保证一致。
     */
    @Query("SELECT * FROM semester WHERE isActive = 1 ORDER BY id DESC LIMIT 1")
    fun observeActive(): Flow<List<SemesterEntity>>

    @Query("SELECT * FROM semester WHERE isActive = 1 ORDER BY id DESC LIMIT 1")
    suspend fun getActive(): SemesterEntity?

    @Query("SELECT * FROM semester ORDER BY startDateEpochDay DESC")
    fun observeAll(): Flow<List<SemesterEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(semester: SemesterEntity): Long

    @Query("UPDATE semester SET isActive = 0")
    suspend fun clearActive()

    @Query("UPDATE semester SET isActive = 1 WHERE id = :id")
    suspend fun setActive(id: Long)

    /** 设置开学日期。 */
    @Query("UPDATE semester SET startDateEpochDay = :startEpochDay WHERE id = :id")
    suspend fun updateStartDate(id: Long, startEpochDay: Long)

    /** 设置总周数（导入页可改）。 */
    @Query("UPDATE semester SET totalWeeks = :totalWeeks WHERE id = :id")
    suspend fun updateTotalWeeks(id: Long, totalWeeks: Int)

    @Query("SELECT COUNT(*) FROM semester")
    suspend fun count(): Int
}

@Dao
interface CourseDao {

    @Query("SELECT * FROM course WHERE semesterId = :semesterId ORDER BY weekday, startSection")
    fun observeBySemester(semesterId: Long): Flow<List<CourseEntity>>

    @Query("SELECT * FROM course WHERE semesterId = :semesterId ORDER BY weekday, startSection")
    suspend fun getBySemester(semesterId: Long): List<CourseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(courses: List<CourseEntity>)

    @Query("DELETE FROM course WHERE semesterId = :semesterId")
    suspend fun deleteBySemester(semesterId: Long)

    /**
     * 只删「这次导入里已经不存在」的课。
     *
     * 重新导入时不能用 [deleteBySemester] 全删重插：`note.courseId` 是
     * `ON DELETE SET NULL`，全删会把所有笔记的课程归属抹掉，且不可逆。
     * 保留仍然存在的课程行，笔记的关联就不会断。
     *
     * ⚠️ `keepKeys` 不能为空 —— `NOT IN ()` 是非法 SQL，Room 不会替你兜。
     * 调用方需在空集合时走 [deleteBySemester]。
     */
    @Query("DELETE FROM course WHERE semesterId = :semesterId AND stableKey NOT IN (:keepKeys)")
    suspend fun deleteBySemesterExcept(semesterId: Long, keepKeys: List<String>)

    @Query("SELECT COUNT(*) FROM course WHERE semesterId = :semesterId")
    suspend fun countBySemester(semesterId: Long): Int
}

@Dao
interface TimeSlotDao {

    @Query("SELECT * FROM time_slot WHERE semesterId = :semesterId ORDER BY section")
    fun observeBySemester(semesterId: Long): Flow<List<TimeSlotEntity>>

    @Query("SELECT * FROM time_slot WHERE semesterId = :semesterId ORDER BY section")
    suspend fun getBySemester(semesterId: Long): List<TimeSlotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(slots: List<TimeSlotEntity>)

    @Query("DELETE FROM time_slot WHERE semesterId = :semesterId")
    suspend fun deleteBySemester(semesterId: Long)

    @Query("SELECT COUNT(*) FROM time_slot WHERE semesterId = :semesterId")
    suspend fun countBySemester(semesterId: Long): Int
}

@Dao
interface ChangeLogDao {

    @Query("SELECT * FROM change_log ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<ChangeLogEntity>>

    @Query("SELECT COUNT(*) FROM change_log WHERE read = 0")
    fun observeUnreadCount(): Flow<Int>

    @Insert
    suspend fun insert(log: ChangeLogEntity): Long

    @Query("UPDATE change_log SET read = 1")
    suspend fun markAllRead()
}
