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

    @Query("SELECT * FROM note ORDER BY startedAtMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<NoteEntity>>

    @Query("SELECT * FROM note WHERE id = :noteId")
    suspend fun getById(noteId: Long): NoteEntity?

    /** 按状态查。用于启动时找回「卡在录音中」的孤儿笔记。 */
    @Query("SELECT * FROM note WHERE status = :status ORDER BY startedAtMs DESC")
    suspend fun getByStatus(status: String): List<NoteEntity>

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
