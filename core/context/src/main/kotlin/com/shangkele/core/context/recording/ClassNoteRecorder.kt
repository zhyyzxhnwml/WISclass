package com.shangkele.core.context.recording

import android.content.Context
import android.os.Environment
import androidx.core.content.ContextCompat
import com.shangkele.core.context.now.NowClassResolver
import com.shangkele.core.database.repository.ScheduleRepository
import com.shangkele.core.model.Note
import com.shangkele.core.model.NoteStatus
import com.shangkele.core.model.WeekCalculator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把「录音」和「笔记」接起来。
 *
 * 关键设计：**先建笔记记录再开录**。这样录音中途进程被杀，用户也能在列表里
 * 看到一条残缺记录（状态停在 RECORDING），而不是什么都没有。
 *
 * 录音会自动关联到当前正在上的课（用 [NowClassResolver] 判定），
 * 不在上课时间就录成「临时录音」，课后可以手动归到某门课。
 */
@Singleton
class ClassNoteRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ScheduleRepository,
    private val controller: RecordingController,
) {

    sealed interface StartOutcome {
        data class Started(val noteId: Long, val courseName: String?) : StartOutcome

        data object NoSemester : StartOutcome

        data class Failed(val message: String) : StartOutcome
    }

    suspend fun start(nowMillis: Long = System.currentTimeMillis()): StartOutcome {
        if (controller.isActive) return StartOutcome.Failed("已经在录音了")

        val semester = repository.getActiveSemester() ?: return StartOutcome.NoSemester
        val courses = repository.getCourses(semester.id)
        val slots = repository.getTimeSlots(semester.id).associateBy { it.section }

        val zoned = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
        val epochDay = zoned.toLocalDate().toEpochDay()
        val week = WeekCalculator.currentWeek(semester.startDateEpochDay, epochDay, semester.totalWeeks)
        val weekday = WeekCalculator.weekdayOf(epochDay)
        val nowMinutes = zoned.hour * 60 + zoned.minute

        val current = NowClassResolver.current(courses, slots, weekday, week, nowMinutes)
        val courseId = current?.course?.id
        val courseName = current?.course?.name

        val noteId = repository.createNote(
            Note(
                courseId = courseId,
                semesterId = semester.id,
                weekIndex = week,
                dateEpochDay = epochDay,
                startedAtMs = nowMillis,
                durationMs = 0L,
                audioPath = null,
                audioSizeBytes = 0L,
                status = NoteStatus.RECORDING,
                title = courseName?.let { "$it · 第 $week 周" },
                createdAt = nowMillis,
            ),
        )

        val file = audioFile(semester.id, courseId, noteId)
        if (!controller.start(noteId, file, courseId, courseName)) {
            repository.updateNoteStatus(noteId, NoteStatus.FAILED)
            return StartOutcome.Failed("录音启动失败，请检查麦克风权限是否已授予")
        }

        ContextCompat.startForegroundService(context, RecordingService.intent(context))
        return StartOutcome.Started(noteId, courseName)
    }

    /**
     * 结束录音并落库。
     *
     * **所有结束路径都必须经过这里**，包括通知栏的「结束」。
     * 之前通知栏直接调 [RecordingController.stop]，跳过了落库这一步，
     * 结果是：录音停了、控制器回到 Idle，但数据库里那条笔记永远停在
     * `RECORDING` —— 界面上一直显示「录音中」，点按钮又开始新录音，
     * 音频文件成了没人引用的孤儿。
     */
    suspend fun stop(): RecordingResult? {
        val result = controller.stop() ?: return null
        repository.finishNote(
            noteId = result.noteId,
            audioPath = result.filePath,
            durationMs = result.durationMs,
            sizeBytes = result.sizeBytes,
            status = if (result.sizeBytes > 0) NoteStatus.RECORDED else NoteStatus.FAILED,
        )
        context.stopService(RecordingService.intent(context))
        return result
    }

    /**
     * 启动时自愈：把「卡在 RECORDING 但实际已经没有在录」的笔记收尾。
     *
     * 要么是历史上走了错误的结束路径，要么是录音期间进程被 MagicOS 杀掉。
     * 不修的话这些记录会永远显示「录音中」且删不掉。
     *
     * @return 修复的条数
     */
    suspend fun recoverOrphanedNotes(nowMs: Long = System.currentTimeMillis()): Int {
        if (controller.isActive) return 0

        // 只收尾「开始超过两分钟」的记录。
        // [start] 里是先建笔记再启动录音，两步之间有个时间窗；
        // 如果自愈正好挤进去，就会把一条正在启动的录音判成孤儿并置为 FAILED，
        // 而录音其实正常开始了 —— 这条记录就永久坏了。宁可漏收，不可误伤。
        val orphans = repository.getNotesByStatus(NoteStatus.RECORDING)
            .filter { nowMs - it.startedAtMs > SAFE_RECOVERY_WINDOW_MS }
        if (orphans.isEmpty()) return 0

        orphans.forEach { note ->
            val file = audioFileFor(note.semesterId, note.courseId, note.id)
            val size = if (file.isFile) file.length() else 0L
            if (size > 0) {
                repository.finishNote(
                    noteId = note.id,
                    audioPath = file.absolutePath,
                    // 正常路径的时长是录的时候累加的，孤儿笔记没有这个值，
                    // 只能从文件里读；读不到就按编码码率估一个
                    durationMs = readDurationMs(file).takeIf { it > 0 } ?: estimateDurationMs(size),
                    sizeBytes = size,
                    status = NoteStatus.RECORDED,
                )
            } else {
                // 文件是空的（刚开始录就被杀），没什么可救的
                repository.updateNoteStatus(note.id, NoteStatus.FAILED)
            }
        }
        return orphans.size
    }

    private fun readDurationMs(file: File): Long {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** AAC 按 48kbps 估算：字节数 × 8 / 码率。仅用于读不出时长时的兜底。 */
    private fun estimateDurationMs(sizeBytes: Long): Long =
        sizeBytes * 8 * 1000L / 48_000L

    private companion object {
        /** 见 [recoverOrphanedNotes]：小于这个时长的记录不碰，避免误伤刚启动的录音。 */
        const val SAFE_RECOVERY_WINDOW_MS = 2 * 60 * 1000L
    }

    fun pause() = controller.pause()

    fun resume() = controller.resume()

    private fun audioFile(semesterId: Long, courseId: Long?, noteId: Long): File {
        val file = audioFileFor(semesterId, courseId, noteId)
        file.parentFile?.mkdirs()
        return file
    }

    /**
     * 录音文件放外部私有目录：一学期量级到 GB，放 filesDir 不好管理也不好备份。
     *
     * 路径是**确定性**的（学期 + 课程 + 笔记 id），这一点很重要：
     * 孤儿笔记的 `audioPath` 是空的，只能靠同样的算法反推出文件在哪，
     * 否则自愈就无从下手。所以这里不要加随机盐或时间戳。
     */
    fun audioFileFor(semesterId: Long, courseId: Long?, noteId: Long): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        val dir = File(File(root, "recordings/$semesterId"), (courseId ?: 0L).toString())
        return File(dir, "$noteId.m4a")
    }
}
