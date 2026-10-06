package com.shangkele.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.shangkele.core.database.entity.NoteEntity
import com.shangkele.core.database.entity.NoteJobEntity
import com.shangkele.core.database.entity.PhotoEntity
import com.shangkele.core.database.entity.SummaryEntity
import com.shangkele.core.database.entity.TranscriptSegmentEntity
import kotlinx.coroutines.flow.Flow

/**
 * 笔记相关 DAO。W1 只建立骨架，实际读写发生在 W4~W6。
 */
@Dao
interface NoteDao {

    @Query("SELECT * FROM note WHERE courseId = :courseId ORDER BY startedAtMs DESC")
    fun observeByCourse(courseId: Long): Flow<List<NoteEntity>>

    /**
     * 最近的**录音**笔记。
     *
     * 「随手拍」刻意排除在外：它是一条没有音频的照片集合，混在录音列表里
     * 既看不出来是照片（时长显示 `0:00`），又白占着「最近的录音」这个位置。
     * 界面上它单独一块，见 NotesScreen 的「随手拍」。
     *
     * `title IS NULL` 这一句不能省：SQL 里 `NULL NOT LIKE 'x'` 的结果是 NULL，
     * 也就是 false —— 漏掉它，所有**没有标题**的录音会整批从列表里消失。
     */
    @Query(
        """
        SELECT * FROM note
        WHERE title IS NULL OR title NOT LIKE '随手拍 · %'
        ORDER BY startedAtMs DESC LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<NoteEntity>>

    /** 最近的「随手拍」（一天一条）。 */
    @Query(
        """
        SELECT * FROM note WHERE title LIKE '随手拍 · %'
        ORDER BY dateEpochDay DESC LIMIT :limit
        """,
    )
    fun observeScratch(limit: Int): Flow<List<NoteEntity>>

    /** noteId → 照片张数。给「随手拍」列表显示「3 张照片」用。 */
    @Query("SELECT noteId, COUNT(*) AS count FROM photo GROUP BY noteId")
    fun observePhotoCounts(): Flow<List<NotePhotoCount>>

    @Query("SELECT * FROM note WHERE id = :noteId")
    suspend fun getById(noteId: Long): NoteEntity?

    /** 按状态查。用于启动时找回「卡在录音中」的孤儿笔记。 */
    @Query("SELECT * FROM note WHERE status = :status ORDER BY startedAtMs DESC")
    suspend fun getByStatus(status: String): List<NoteEntity>

    /**
     * 找当天那条「随手拍」笔记（桌面小组件拍照的落点）。没有则返回 null。
     *
     * 靠**标题**认，而不是「`audioPath` 为空」：录音中途失败时 `audioPath` 也是 null，
     * 那样照片会被塞进一条失败的录音笔记里，而真正的「随手拍」永远找不到。
     */
    @Query("SELECT * FROM note WHERE title LIKE '随手拍 · %' AND dateEpochDay = :day ORDER BY id DESC LIMIT 1")
    suspend fun getScratchNote(day: Long): NoteEntity?

    /**
     * 删除笔记。转写片段与摘要靠外键 CASCADE 一并删除
     * （见 `TranscriptSegmentEntity` / `SummaryEntity` 的 onDelete）。
     */
    @Query("DELETE FROM note WHERE id = :noteId")
    suspend fun delete(noteId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    @Query("UPDATE note SET status = :status WHERE id = :noteId")
    suspend fun updateStatus(noteId: Long, status: String)

    /** 录音收尾：一次性写入文件路径、时长、体积与状态。 */
    @Query(
        """
        UPDATE note SET audioPath = :audioPath, durationMs = :durationMs,
                        audioSizeBytes = :sizeBytes, status = :status
        WHERE id = :noteId
        """,
    )
    suspend fun updateRecordingResult(
        noteId: Long,
        audioPath: String?,
        durationMs: Long,
        sizeBytes: Long,
        status: String,
    )

    @Insert
    suspend fun insertSegments(segments: List<TranscriptSegmentEntity>)

    /** 重新转写前先清空旧结果，避免新旧片段混在一起。 */
    @Query("DELETE FROM transcript_segment WHERE noteId = :noteId")
    suspend fun deleteSegments(noteId: Long)

    @Query("SELECT * FROM transcript_segment WHERE noteId = :noteId ORDER BY startMs")
    suspend fun getSegments(noteId: Long): List<TranscriptSegmentEntity>

    @Query("UPDATE transcript_segment SET correctedText = :text, isCorrected = 1 WHERE id = :segmentId")
    suspend fun correctSegment(segmentId: Long, text: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSummary(summary: SummaryEntity)

    @Query("SELECT * FROM summary WHERE noteId = :noteId")
    suspend fun getSummary(noteId: Long): SummaryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJob(job: NoteJobEntity)

    @Query("SELECT * FROM note_job WHERE noteId = :noteId")
    suspend fun getJob(noteId: Long): NoteJobEntity?

    @Query("SELECT * FROM note_job WHERE state IN ('QUEUED','RUNNING','PAUSED')")
    suspend fun getResumableJobs(): List<NoteJobEntity>

    // ---- 照片 ----

    @Insert
    suspend fun insertPhoto(photo: PhotoEntity): Long

    /**
     * 按时间轴顺序读照片。
     *
     * 课后再补的照片 `offsetMs` 是 NULL，用 `COALESCE` 兜到一个极大值，
     * 让它们排在所有「跟着录音拍的」之后 —— 否则 SQLite 默认把 NULL 排最前，
     * 时间轴一开头就会冒出一堆课后补拍的图。
     */
    @Query(
        """
        SELECT * FROM photo WHERE noteId = :noteId
        ORDER BY COALESCE(offsetMs, 9223372036854775807), takenAtMs
        """,
    )
    fun observePhotos(noteId: Long): Flow<List<PhotoEntity>>

    @Query(
        """
        SELECT * FROM photo WHERE noteId = :noteId
        ORDER BY COALESCE(offsetMs, 9223372036854775807), takenAtMs
        """,
    )
    suspend fun getPhotos(noteId: Long): List<PhotoEntity>

    @Query("SELECT * FROM photo WHERE id = :photoId")
    suspend fun getPhotoById(photoId: Long): PhotoEntity?

    @Query("SELECT COUNT(*) FROM photo WHERE noteId = :noteId")
    suspend fun photoCount(noteId: Long): Int

    @Query("DELETE FROM photo WHERE id = :photoId")
    suspend fun deletePhoto(photoId: Long)
}

/** 一条笔记有几张照片。Room 用它接 [NoteDao.observePhotoCounts] 的投影结果。 */
data class NotePhotoCount(val noteId: Long, val count: Int)
