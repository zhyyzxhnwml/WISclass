package com.shangkele.core.jwgl

import com.shangkele.core.database.entity.ChangeLogEntity
import com.shangkele.core.database.repository.ScheduleRepository
import com.shangkele.core.model.CourseSource
import com.shangkele.core.model.ScheduleChange
import com.shangkele.core.model.ScheduleDiffer
import com.shangkele.core.model.Semester
import javax.inject.Inject
import javax.inject.Singleton

/** 导入参数。 */
data class ImportRequest(
    val xnm: String,
    val xqm: String,
    val totalWeeks: Int,
    val startDateEpochDay: Long,
)

/** 导入结果。 */
sealed interface ImportResult {
    data class Success(
        val semester: Semester,
        val courseCount: Int,
        val changes: List<ScheduleChange>,
        val skipped: List<String>,
        val studentName: String?,
        /** 本次真正生效的课表接口地址，便于排查与后续静默刷新 */
        val sourceUrl: String? = null,
    ) : ImportResult

    /**
     * @param sessionExpired 会话失效导致的失败。界面据此把「用已保存的登录刷新」
     *                       收起来，别让用户对着一个必然失败的按钮反复点。
     */
    data class Failure(
        val message: String,
        val sessionExpired: Boolean = false,
    ) : ImportResult
}

/**
 * 教务课表导入编排。
 *
 * 两条取数通道，优先级从高到低：
 *
 * ```
 * ① WebView 页面内抓取（[importRawJson]）
 *      官方页面同源发请求，Cookie 由浏览器自己带，地址也从页面里刮 —— 最可靠
 * ② 原生 OkHttp 抓取（[importWithCookies] / [refreshWithSavedSession]）
 *      只用会话 Cookie，秒级、可静默刷新；依赖端点猜对
 * ```
 *
 * 两条都汇到 [applyRawJson]：解析 → 与库内旧课表 diff → 覆盖写入 → 记录变更。
 */
@Singleton
class ScheduleImporter @Inject constructor(
    private val api: JwglApiClient,
    private val sessionStore: JwglSessionStore,
    private val repository: ScheduleRepository,
) {

    /**
     * 处理 WebView 页面里抓到的课表响应。
     *
     * 这是**首选通道**：数据来自官方页面自己发的那次请求，端点不用猜。
     * 顺带把生效地址记下来，下次静默刷新直接命中。
     */
    suspend fun importRawJson(
        rawJson: String,
        request: ImportRequest,
        endpointUrl: String?,
    ): ImportResult {
        if (rawJson.isBlank()) return ImportResult.Failure("页面没有返回课表内容")

        // 页面里截到的是相对地址（/jwglxt/...），要补成绝对地址再存，
        // 否则之后拿它做静默刷新会让 OkHttp 报 "Expected URL scheme"
        val absolute = JwglEndpoints.resolveUrl(endpointUrl)
        absolute?.let { sessionStore.saveScheduleEndpoint(it) }

        return runCatching { applyRawJson(rawJson, request, CourseSource.WEB, absolute) }
            .getOrElse { toFailure(it) }
    }

    /** 用 WebView 拿到的会话，走原生 OkHttp 抓取。 */
    suspend fun importWithCookies(cookies: String, request: ImportRequest): ImportResult {
        if (cookies.isBlank()) return ImportResult.Failure("没有拿到登录会话，请先在页面里完成登录")
        sessionStore.saveSession(cookies)
        api.setCookies(cookies)
        return fetchAndApply(request, CourseSource.WEB)
    }

    /** 用已保存的会话静默刷新（日常刷新路径）。 */
    suspend fun refreshWithSavedSession(request: ImportRequest): ImportResult {
        val cookies = sessionStore.loadSession()
            ?: return ImportResult.Failure("还没有登录过教务系统")
        api.setCookies(cookies)
        return fetchAndApply(request, CourseSource.API)
    }

    fun forgetSession() {
        api.clearCookies()
        sessionStore.clearSession()
    }

    private suspend fun fetchAndApply(request: ImportRequest, source: CourseSource): ImportResult =
        runCatching {
            val preferred = sessionStore.scheduleEndpoint()
            val raw = api.fetchScheduleRaw(
                xnm = request.xnm,
                xqm = request.xqm,
                preferredEndpoint = preferred,
            )
            applyRawJson(raw, request, source, preferred)
        }.getOrElse { toFailure(it) }

    private suspend fun applyRawJson(
        rawJson: String,
        request: ImportRequest,
        source: CourseSource,
        endpointUrl: String?,
    ): ImportResult {
        val semester = repository.ensureActiveSemester(
            xnm = request.xnm,
            xqm = request.xqm,
            totalWeeks = request.totalWeeks,
            startDateEpochDay = request.startDateEpochDay,
        )
        repository.ensureDefaultTimeSlots(semester.id)

        val parsed = KbListParser.parse(
            raw = rawJson,
            semesterId = semester.id,
            totalWeeks = request.totalWeeks,
            source = source,
        )

        val previous = repository.getCourses(semester.id)
        val changes = if (previous.isEmpty()) {
            emptyList()
        } else {
            ScheduleDiffer.diff(previous, parsed.courses)
        }

        val now = System.currentTimeMillis()
        repository.replaceCourses(semester.id, parsed.courses, now)
        repository.appendChanges(changes.map { it.toEntity(semester.id, now) })

        return ImportResult.Success(
            semester = semester,
            courseCount = parsed.courses.size,
            changes = changes,
            skipped = parsed.skipped,
            studentName = parsed.studentName,
            sourceUrl = endpointUrl,
        )
    }

    private fun toFailure(error: Throwable): ImportResult.Failure = when (error) {
        is JwglSessionExpiredException -> {
            sessionStore.clearSession()
            api.clearCookies()
            ImportResult.Failure(
                message = "登录会话已过期。点上面的「登录教务系统并导入」重新登录一次即可。",
                sessionExpired = true,
            )
        }

        is JwglException -> ImportResult.Failure(error.message ?: "导入失败")
        else -> ImportResult.Failure("导入失败：${error.message ?: error.javaClass.simpleName}")
    }

    private fun ScheduleChange.toEntity(semesterId: Long, now: Long): ChangeLogEntity =
        ChangeLogEntity(
            semesterId = semesterId,
            courseStableKey = after?.stableKey() ?: before?.stableKey().orEmpty(),
            changeType = type.name,
            beforeJson = before?.let { "${it.name}|${it.teacher}|${it.roomRaw}" },
            afterJson = after?.let { "${it.name}|${it.teacher}|${it.roomRaw}" },
            createdAt = now,
            read = false,
        )
}
