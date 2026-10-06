package com.shangkele.app.capture

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.shangkele.core.context.photos.NotePhotoStore
import com.shangkele.core.database.repository.ScheduleRepository
import com.shangkele.core.model.NotePhoto
import com.shangkele.feature.notes.HomeworkScanner
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import javax.inject.Inject

/**
 * 「拍一下」中转页：从桌面小组件拉起来，调系统相机，拍完立刻退出。
 *
 * **为什么非要单独一个 Activity**：小组件是 Glance / RemoteViews，
 * 里面既跑不了 `registerForActivityResult`，也拿不到任何 Activity 的 Lifecycle，
 * 而调系统相机两样都必须有。所以只能由小组件用 PendingIntent 把本页拉起来，
 * 由它去拍 —— 用户看到的就是「在桌面上点一下 → 相机开 → 拍完回到桌面」。
 *
 * 它自己**没有界面**（透明主题、不进最近任务）。刻意不声明 `noHistory`：
 * 那会让本页在相机切到前台时被系统立刻 finish，拍照结果就再也回不来了 ——
 * 表现是「点了没反应」，而且不报错。退出全靠每条分支自己 `finish()`。
 */
@AndroidEntryPoint
class QuickCaptureActivity : ComponentActivity() {

    @Inject lateinit var repository: ScheduleRepository
    @Inject lateinit var photoStore: NotePhotoStore
    @Inject lateinit var homeworkScanner: HomeworkScanner

    /** 相机要写入的目标文件。 */
    private var target: File? = null

    /** 目标文件属于哪条笔记。 */
    private var noteId: Long = 0L

    /** 结果只处理一次：相机回调与失败分支都可能走到收尾。 */
    private var finishing = false

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            onCameraResult(success)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 相机在前台时，本页可能被系统回收；回来时会带着 savedInstanceState 重建。
        // 此时若再发一次相机，用户会看到相机连弹两次，只会以为它坏了。
        if (savedInstanceState != null) {
            finish()
            return
        }

        lifecycleScope.launch {
            val today = LocalDate.now().toEpochDay()
            val note = runCatching {
                repository.ensureScratchNote(today, System.currentTimeMillis())
            }.getOrNull()

            if (note == null) {
                // 没有学期就没地方挂照片。不明说的话，用户看到的就是「点了没反应」
                toast("先在「上课啦」里导入一次课表，照片才有地方存")
                finish()
                return@launch
            }

            val file = runCatching {
                photoStore.newPhotoFile(note.id, System.currentTimeMillis())
            }.getOrNull()

            if (file == null) {
                toast("找不到可写的目录，这张没拍成")
                finish()
                return@launch
            }

            noteId = note.id
            target = file
            val uri = FileProvider.getUriForFile(this@QuickCaptureActivity, photoStore.authority(), file)
            runCatching { takePicture.launch(uri) }.onFailure {
                // 设备没有可用的相机应用。空文件要删掉，否则「随手拍」里留一张 0 字节的图
                runCatching { file.delete() }
                toast("这台设备没有可用的相机")
                finish()
            }
        }
    }

    private fun onCameraResult(success: Boolean) {
        val file = target
        target = null

        if (file == null) {
            finish()
            return
        }

        if (!success) {
            // 用户退了回来。目标文件只是个空壳，必须删 —— 见上面「0 字节的图」
            runCatching { file.delete() }
            finish()
            return
        }

        lifecycleScope.launch {
            val saved = runCatching { save(file) }.getOrDefault(false)
            // 拍完本页就退，用户直接回到桌面；不提示的话他不知道到底成没成
            toast(if (saved) "拍下了，在「笔记」里能翻到" else "照片没保存成功，再试一次")
            finish()
        }
    }

    private suspend fun save(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false

        val (width, height) = withContext(Dispatchers.IO) { photoStore.readSize(file) }
        val note = repository.getNote(noteId) ?: return false

        // 取**文件的最后写入时间**而不是「点小组件的时刻」：相机是按下快门那一下才写盘，
        // 用户可能举着手机框了好几秒。
        val takenAtMs = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()

        // 「随手拍」笔记的起点是当天 0 点，所以这个偏移量**就是拍摄的钟点**（见 ScratchNote）。
        // 界面上会显示成 14:32:05，同一天内的先后也按它排。
        val offsetMs = (takenAtMs - note.startedAtMs).takeIf { it >= 0 }

        repository.addNotePhoto(
            NotePhoto(
                noteId = note.id,
                semesterId = note.semesterId,
                courseId = note.courseId,
                offsetMs = offsetMs,
                takenAtMs = takenAtMs,
                path = file.absolutePath,
                width = width,
                height = height,
                sizeBytes = file.length(),
                createdAt = System.currentTimeMillis(),
            ),
        )

        // 顺手读一遍里面有没有作业。这一步要联网、几秒才回，所以丢到后台 ——
        // 本页已经准备 finish 了，绝不能挡着「拍完就回桌面」。
        // 日期基准用笔记自己的那天，而不是「现在」：跨零点拍摄时两者会差一天。
        homeworkScanner.scanPhotoInBackground(note.id, file.absolutePath, note.dateEpochDay)
        return true
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
