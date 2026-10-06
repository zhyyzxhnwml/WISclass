package com.shangkele.core.database.repository

import com.shangkele.core.database.dao.ChangeLogDao
import com.shangkele.core.database.dao.CourseDao
import com.shangkele.core.database.dao.NoteDao
import com.shangkele.core.database.dao.SemesterDao
import com.shangkele.core.database.dao.TimeSlotDao
import com.shangkele.core.database.entity.ChangeLogEntity
import com.shangkele.core.database.entity.SemesterEntity
import com.shangkele.core.database.mapper.toDomain
import com.shangkele.core.database.mapper.toEntity
import com.shangkele.core.model.Course
import com.shangkele.core.model.Note
import com.shangkele.core.model.NotePhoto
import com.shangkele.core.model.NoteStatus
import com.shangkele.core.model.NoteSummary
import com.shangkele.core.model.TranscriptSegment
import com.shangkele.core.model.SchoolDefaults
import com.shangkele.core.model.SemesterEdit
import com.shangkele.core.model.Semester
import com.shangkele.core.model.TimeSlot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 课表仓库：UI 的唯一数据来源。
 *
 * W1 只做读 + 整学期覆盖写；W2 接入教务后会在这里加 diff（见 docs/03-教务系统对接.md §十）。
 */
@Singleton
class ScheduleRepository @Inject constructor(
    private val semesterDao: SemesterDao,
    private val courseDao: CourseDao,
    private val timeSlotDao: TimeSlotDao,
    private val changeLogDao: ChangeLogDao,
    private val noteDao: NoteDao,
) {

    fun observeActiveSemester(): Flow<Semester?> =
        semesterDao.observeActive().map { list -> list.firstOrNull()?.toDomain() }

    fun observeSemesters(): Flow<List<Semester>> =
        semesterDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeCourses(semesterId: Long): Flow<List<Course>> =
        courseDao.observeBySemester(semesterId).map { list -> list.map { it.toDomain() } }

    fun observeTimeSlots(semesterId: Long): Flow<List<TimeSlot>> =
        timeSlotDao.observeBySemester(semesterId).map { list -> list.map { it.toDomain() } }

    fun observeUnreadChangeCount(): Flow<Int> = changeLogDao.observeUnreadCount()

    suspend fun getActiveSemester(): Semester? = semesterDao.getActive()?.toDomain()

    suspend fun getCourses(semesterId: Long): List<Course> =
        courseDao.getBySemester(semesterId).map { it.toDomain() }

    suspend fun courseCount(semesterId: Long): Int = courseDao.countBySemester(semesterId)

    suspend fun getTimeSlots(semesterId: Long): List<TimeSlot> =
        timeSlotDao.getBySemester(semesterId).map { it.toDomain() }

    /**
     * 取出（必要时创建）当前学期。
     *
     * 学年/学期变化时会新建一条并切换 `isActive`，旧学期的课表与笔记保留不删
     * ——用户需要能回看历史学期。
     *
     * **同一个学期时不能直接 return**：导入请求里的开学日期与总周数
     * 是用户在导入页明确选的，必须落库。这里以前直接返回，
     * 结果「用户改对了日期，课表却照旧按旧日期算周次，且不报错」。
     * 判断规则见 [SemesterEdit]，配了单测。
     */
    suspend fun ensureActiveSemester(
        xnm: String,
        xqm: String,
        totalWeeks: Int,
        startDateEpochDay: Long,
    ): Semester {
        semesterDao.getActive()?.let { current ->
            if (current.xnm == xnm && current.xqm == xqm) {
                val existing = current.toDomain()
                val merged = SemesterEdit.merge(existing, startDateEpochDay, totalWeeks)
                if (merged.startDateEpochDay != existing.startDateEpochDay) {
                    semesterDao.updateStartDate(current.id, merged.startDateEpochDay)
                }
                if (merged.totalWeeks != existing.totalWeeks) {
                    semesterDao.updateTotalWeeks(current.id, merged.totalWeeks)
                }
                return merged
            }
        }
        semesterDao.clearActive()
        val id = semesterDao.insert(
            SemesterEntity(
                xnm = xnm,
                xqm = xqm,
                name = Semester.buildName(xnm, xqm),
                startDateEpochDay = startDateEpochDay,
                totalWeeks = totalWeeks,
                isActive = true,
            ),
        )
        return Semester(
            id = id,
            xnm = xnm,
            xqm = xqm,
            name = Semester.buildName(xnm, xqm),
            startDateEpochDay = startDateEpochDay,
            totalWeeks = totalWeeks,
            isActive = true,
        )
    }

    /**
     * 补默认作息表。已有用户校准过的值就绝不动它。
     *
     * 唯一的例外是**自愈**：如果库里存的恰好还是早期那组写错的占位值
     * （上午 08:00 开始，见 [SchoolDefaults.LEGACY_PLACEHOLDER_TIME_SLOTS]），
     * 说明用户从没改过，那就换成修正后的默认值。
     * 否则只改常量不改库，已经装过的机器永远显示错误时间。
     */
    suspend fun ensureDefaultTimeSlots(semesterId: Long) {
        val existing = timeSlotDao.getBySemester(semesterId)
        if (existing.isEmpty()) {
            timeSlotDao.insertAll(SchoolDefaults.TIME_SLOTS.map { it.toEntity(semesterId) })
            return
        }
        if (existing.map { it.toDomain() } == SchoolDefaults.LEGACY_PLACEHOLDER_TIME_SLOTS) {
            timeSlotDao.deleteBySemester(semesterId)
            timeSlotDao.insertAll(SchoolDefaults.TIME_SLOTS.map { it.toEntity(semesterId) })
        }
    }

    /**
     * 校准当前学期的第一周周一。
     *
     * 校历错一周，整张课表的「本周有没有这门课」就全判错，而且不会有任何报错。
     * 所以能从教务页面自动读出来就不该让用户手填。
     */
    suspend fun updateActiveSemesterStartDate(startEpochDay: Long): Boolean {
        val semester = semesterDao.getActive() ?: return false
        semesterDao.updateStartDate(semester.id, startEpochDay)
        return true
    }

    /**
     * 把早期写死的**占位校历**换成真实开学日期。
     *
     * @param confirmed 用户是否亲自设置过开学日期。设过就一个字都不动。
     * @return true 表示确实改了
     */
    suspend fun healPlaceholderStartDate(confirmed: Boolean): Boolean {
        val semester = semesterDao.getActive() ?: return false
        val existing = semester.toDomain()
        val shouldHeal = SemesterEdit.shouldHealPlaceholder(
            existing = existing,
            confirmed = confirmed,
            placeholder = SchoolDefaults.LEGACY_PLACEHOLDER_START_DATE_EPOCH_DAY,
        )
        if (!shouldHeal) return false
        semesterDao.updateStartDate(semester.id, SchoolDefaults.DEFAULT_START_DATE_EPOCH_DAY)
        return true
    }

    // 刻意没有「自动推断/自愈校历」的方法。
    //
    // 第一周是哪天只有学校知道，任何默认值都是猜。猜错的代价是
    // 「本周有没有这门课」全判错，而且界面上除了日期区间没有别的线索。
    // 所以这里只提供「写入用户明确设置的值」这一个入口，
    // 是否已校准由 core:context 的 CalendarCalibrationStore 记录。

    /** 用户手动校准作息表：整表覆盖。 */
    suspend fun replaceTimeSlots(semesterId: Long, slots: List<TimeSlot>) {
        if (slots.isEmpty()) return
        timeSlotDao.deleteBySemester(semesterId)
        timeSlotDao.insertAll(slots.sortedBy { it.section }.map { it.toEntity(semesterId) })
    }

    /** 把作息表恢复成默认值。 */
    suspend fun restoreDefaultTimeSlots(semesterId: Long) {
        replaceTimeSlots(semesterId, SchoolDefaults.TIME_SLOTS)
    }

    /**
     * 写入新课表（覆盖语义）。
     *
     * **刻意不用「全删再全插」**：`note.courseId` 是 `ON DELETE SET NULL`，
     * 全删会把所有笔记的课程归属抹掉 —— 每次重新导入都丢一次，
     * 而且不可逆（笔记还在，但再也回不到那门课下面）。
     *
     * 所以改成两步：
     *  1. 只删**这次导入里已经不存在**的课（按 `stableKey` 比对）
     *  2. 仍然存在的课**复用原来的 id**，笔记的关联就保住了
     *
     * diff 由调用方（[com.shangkele.core.model.ScheduleDiffer]）事先算好。
     */
    suspend fun replaceCourses(semesterId: Long, courses: List<Course>, nowMs: Long) {
        val existing = courseDao.getBySemester(semesterId)
        val keepKeys = courses.map { it.stableKey() }

        if (keepKeys.isEmpty()) {
            courseDao.deleteBySemester(semesterId)
        } else {
            courseDao.deleteBySemesterExcept(semesterId, keepKeys)
        }

        // CourseEntity.stableKey 是字段，Course.stableKey() 是函数 —— 两边不一样，别混
        val idByStableKey = existing.associate { it.stableKey to it.id }
        courseDao.insertAll(
            courses.map { course ->
                course.toEntity(nowMs).copy(id = idByStableKey[course.stableKey()] ?: 0L)
            },
        )
    }

    // ---- 笔记（录音） ----

    fun observeRecentNotes(limit: Int = 30): Flow<List<Note>> =
        noteDao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    fun observeNotesByCourse(courseId: Long): Flow<List<Note>> =
        noteDao.observeByCourse(courseId).map { list -> list.map { it.toDomain() } }

    suspend fun createNote(note: Note): Long = noteDao.insert(note.toEntity())

    suspend fun finishNote(
        noteId: Long,
        audioPath: String?,
        durationMs: Long,
        sizeBytes: Long,
        status: NoteStatus,
    ) {
        noteDao.updateRecordingResult(
            noteId = noteId,
            audioPath = audioPath,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            status = status.name,
        )
    }

    suspend fun updateNoteStatus(noteId: Long, status: NoteStatus) {
        noteDao.updateStatus(noteId, status.name)
    }

    suspend fun getNote(noteId: Long): Note? = noteDao.getById(noteId)?.toDomain()

    /** 一条笔记的句子级转写，按时间顺序。 */
    suspend fun getNoteSegments(noteId: Long): List<TranscriptSegment> =
        noteDao.getSegments(noteId).map { it.toDomain() }

    /** 一条笔记的摘要；还没生成过则为 null。 */
    suspend fun getNoteSummary(noteId: Long): NoteSummary? = noteDao.getSummary(noteId)?.toDomain()

    suspend fun getNotesByStatus(status: NoteStatus): List<Note> =
        noteDao.getByStatus(status.name).map { it.toDomain() }

    // ---- 照片 ----

    fun observeNotePhotos(noteId: Long): Flow<List<NotePhoto>> =
        noteDao.observePhotos(noteId).map { list -> list.map { it.toDomain() } }

    suspend fun getNotePhotos(noteId: Long): List<NotePhoto> =
        noteDao.getPhotos(noteId).map { it.toDomain() }

    suspend fun addNotePhoto(photo: NotePhoto): Long = noteDao.insertPhoto(photo.toEntity())

    suspend fun notePhotoCount(noteId: Long): Int = noteDao.photoCount(noteId)

    /** 删一张照片：先删库再删文件，理由同 [deleteNote]。 */
    suspend fun deleteNotePhoto(photoId: Long): Boolean {
        val photo = noteDao.getPhotoById(photoId) ?: return false
        noteDao.deletePhoto(photoId)
        deleteFileAndEmptyParents(java.io.File(photo.path))
        return true
    }

    /**
     * 删除一条笔记：先删库，再删音频与照片文件。
     *
     * 顺序不能反 —— 先把文件删了但库删失败的话，会留下一条指向不存在文件的记录，
     * 点「转写」直接报「录音文件已丢失」，而且再也清不掉。
     * 反过来最坏情况只是留下一个没人引用的文件（可由「清缓存」回收）。
     *
     * **照片路径必须在删库之前取出来**：photo 行是靠外键 CASCADE 跟着笔记删的，
     * 删完就查不到了，那些 jpg 会永远留在磁盘上，用户既看不见也删不掉。
     */
    suspend fun deleteNote(noteId: Long): Boolean {
        val note = noteDao.getById(noteId) ?: return false
        val photoFiles = noteDao.getPhotos(noteId).map { java.io.File(it.path) }
        noteDao.delete(noteId)

        photoFiles.forEach { deleteFileAndEmptyParents(it) }

        val path = note.audioPath
        if (!path.isNullOrBlank()) {
            deleteFileAndEmptyParents(java.io.File(path))
        }
        return true
    }

    /**
     * 删文件并顺手回收变空的目录。
     *
     * 只往上一级就够了：`recordings/{学期}/{课程}/{id}.m4a` 与
     * `photos/{noteId}/{时间戳}.jpg` 这两种布局下，父目录空了就说明
     * 这门课 / 这条笔记确实没有别的文件了。
     */
    private fun deleteFileAndEmptyParents(file: java.io.File) {
        runCatching {
            file.delete()
            val parent = file.parentFile
            if (parent != null && parent.isDirectory && parent.list()?.isEmpty() == true) {
                parent.delete()
            }
        }
    }

    suspend fun markChangesRead() = changeLogDao.markAllRead()

    suspend fun appendChange(log: ChangeLogEntity) {
        changeLogDao.insert(log)
    }

    suspend fun appendChanges(logs: List<ChangeLogEntity>) {
        logs.forEach { changeLogDao.insert(it) }
    }
}
