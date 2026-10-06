package com.shangkele.feature.notes

import com.shangkele.core.ai.llm.HomeworkExtractor
import com.shangkele.core.ai.llm.LlmConfig
import com.shangkele.core.ai.llm.LlmConfigStore
import com.shangkele.core.ai.llm.LlmCredentialStore
import com.shangkele.core.context.photos.NotePhotoStore
import com.shangkele.core.database.repository.ScheduleRepository
import com.shangkele.core.model.Assignment
import com.shangkele.core.model.HomeworkDueParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把「拍下的板书」和「课堂转写」送去读一遍，把读到的作业写进库。
 *
 * 放在 feature 层而不是 core：它同时要用到 `core:context` 的照片存储和
 * `core:ai` 的模型调用，而这两个模块互不依赖（是平级的）。
 *
 * **两条触发路径，都保留**：
 *  - 后台自动：拍完立刻读，用户什么都不用做；
 *  - 手动重读：详情页里点一下。因为后台那条会丢（下面有说明），
 *    没有手动入口的话，用户遇到失败就再也没机会了。
 */
@Singleton
class HomeworkScanner @Inject constructor(
    private val repository: ScheduleRepository,
    private val extractor: HomeworkExtractor,
    private val photoStore: NotePhotoStore,
    private val configStore: LlmConfigStore,
    private val credentialStore: LlmCredentialStore,
) {

    /**
     * 应用级作用域，不用 viewModelScope。
     *
     * 从桌面组件拍完那一下，`QuickCaptureActivity` 马上就 finish 了，
     * 而一次视觉请求要几秒 —— 挂在 Activity 的生命周期上必然被取消。
     *
     * 代价是**进程被杀就丢**。所以详情页里留了手动重读的入口，
     * 并且 [lastResult] 会把失败原因带出来，不会静默消失。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _lastResult = MutableStateFlow<String?>(null)

    /**
     * 最近一次识别的结果，给界面提示用（null = 这次启动还没跑过）。
     *
     * 有了它，「后台跑失败了」才看得见 —— 否则用户只会觉得
     * 「AI 好像什么都没读出来」，而分不清是没内容还是出错。
     */
    val lastResult: StateFlow<String?> = _lastResult.asStateFlow()

    /** 拍完/导入后在后台读一张照片。结果只记在 [lastResult]，不打断任何流程。 */
    fun scanPhotoInBackground(noteId: Long, photoPath: String, todayEpochDay: Long) {
        scope.launch { scanPhoto(noteId, photoPath, todayEpochDay) }
    }

    /** 读一张照片，把里面的作业写进库。@return 新写入的条数 */
    suspend fun scanPhoto(noteId: Long, photoPath: String, todayEpochDay: Long): Result<Int> =
        runCatching {
            val (config, apiKey) = requireConfig()
            val base64 = photoStore.encodeForVision(File(photoPath))
                ?: error("这张照片读不出来，可能已经损坏")
            val drafts = extractor.fromImage(base64, config, apiKey).getOrThrow()
            finish(drafts, noteId, todayEpochDay)
        }.also { report(it) }

    /** 从一条笔记的转写里读。 */
    suspend fun scanTranscript(noteId: Long, todayEpochDay: Long): Result<Int> = runCatching {
        val note = repository.getNote(noteId) ?: error("这条笔记已经不在了")
        val text = note.transcriptText?.takeIf { it.isNotBlank() }
            ?: error("这条笔记还没有转写，先转写再读")
        val (config, apiKey) = requireConfig()
        val drafts = extractor.fromTranscript(text, config, apiKey).getOrThrow()
        finish(drafts, noteId, todayEpochDay)
    }.also { report(it) }

    // ---- 内部 ----

    private fun requireConfig(): Pair<LlmConfig, String> {
        val config = configStore.load()
        if (!config.isUsable) error("还没配置云端模型，去「设置 → 大模型」填一下地址和模型名")
        val key = credentialStore.apiKey()?.takeIf { it.isNotBlank() }
            ?: error("还没填 API Key，去「设置 → 大模型」填一下")
        return config to key
    }

    /**
     * 落库。
     *
     * 关键一步是 [HomeworkDueParser]：模型只给「下周三前」这句原话，
     * 日期由这个纯函数算 —— 它有单测，而模型每次的措辞都不一样。
     * 算不出来就存 null，界面只显示原话，绝不硬凑一个日期。
     */
    private suspend fun finish(
        drafts: List<HomeworkExtractor.Draft>,
        noteId: Long,
        todayEpochDay: Long,
    ): Int {
        val note = repository.getNote(noteId) ?: return 0
        var written = 0
        drafts.forEach { draft ->
            val id = repository.addAssignment(
                Assignment(
                    courseId = note.courseId,
                    noteId = note.id,
                    title = draft.title,
                    dueEpochDay = HomeworkDueParser.parse(draft.dueRawText, todayEpochDay),
                    dueRawText = draft.dueRawText,
                    confidence = draft.confidence,
                ),
            )
            // null = 判定为重复，不算新写入（见 ScheduleRepository.addAssignment）
            if (id != null) written++
        }
        return written
    }

    private fun report(result: Result<Int>) {
        _lastResult.value = result.fold(
            onSuccess = { count ->
                when {
                    count > 0 -> "从里面读到 $count 条待办"
                    else -> "没读到要做的事"
                }
            },
            onFailure = { "读取失败：${it.message ?: "未知原因"}" },
        )
    }
}
