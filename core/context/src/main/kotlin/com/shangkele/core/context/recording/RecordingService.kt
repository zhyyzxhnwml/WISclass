package com.shangkele.core.context.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.shangkele.core.context.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 课堂录音前台服务。
 *
 * 职责只有两个：**保活**（录音期间不被 MagicOS 回收）和**渲染常驻通知**。
 * 真正的录音由 [RecordingController] 持有，服务只观察它的状态来更新通知
 * —— 这样不用把 MediaRecorder 塞进 Binder。
 *
 * 通知必须常驻且不可划掉，并直接提供暂停/继续与结束两个操作。
 */
@AndroidEntryPoint
class RecordingService : Service() {

    @Inject
    lateinit var controller: RecordingController

    @Inject
    lateinit var classNoteRecorder: ClassNoteRecorder

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null

    /**
     * 正在执行「结束并落库」。
     *
     * 必须挡住状态观察者：`controller.stop()` 会立刻把状态置为 Idle，
     * 观察者看到 Idle 就会 `stopSelf()`，把服务连同协程一起销毁，
     * **落库那一步就被取消了** —— 这正是之前通知栏结束会留下孤儿笔记的原因。
     */
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        observeJob = scope.launch {
            controller.state.collect { state ->
                when (state) {
                    is RecordingState.Active -> {
                        startForegroundCompat(buildNotification(state))
                    }

                    is RecordingState.Idle -> {
                        // 收尾中不要自杀，等落库完成
                        if (!stopping) {
                            stopForegroundCompat()
                            stopSelf()
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> controller.pause()
            ACTION_RESUME -> controller.resume()
            ACTION_STOP -> stopAndPersist()
        }
        // 第一次进来必须先 startForeground，否则 5 秒内会被系统判定为 ANR
        if (!controller.isActive && !stopping) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /**
     * 通知栏「结束」：**必须走 [ClassNoteRecorder.stop]**，不能直接调 `controller.stop()`。
     *
     * 直接调 controller 只会停掉 MediaRecorder，笔记永远停在 RECORDING 状态。
     */
    private fun stopAndPersist() {
        if (stopping) return
        stopping = true
        scope.launch {
            try {
                classNoteRecorder.stop()
            } finally {
                stopping = false
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        observeJob?.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // 权限被撤等情况：直接停掉，不要让录音处于「无通知的幽灵状态」
            controller.abandon()
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    private fun buildNotification(state: RecordingState.Active): android.app.Notification {
        val time = formatElapsed(state.elapsedMs)
        val title = if (state.paused) "已暂停 · $time" else "正在记笔记 · $time"
        val text = state.courseName?.let { "《$it》" } ?: "临时录音"

        val toggleIntent = if (state.paused) {
            actionIntent(ACTION_RESUME, REQUEST_TOGGLE)
        } else {
            actionIntent(ACTION_PAUSE, REQUEST_TOGGLE)
        }
        val toggleLabel = if (state.paused) "继续" else "暂停"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, toggleLabel, toggleIntent)
            .addAction(0, "结束", actionIntent(ACTION_STOP, REQUEST_STOP))
            .build()
    }

    private fun actionIntent(action: String, code: Int): PendingIntent {
        val intent = Intent(this, RecordingService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "课堂录音", NotificationManager.IMPORTANCE_LOW).apply {
                description = "录音期间的常驻通知"
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "class_recording"
        private const val NOTIFICATION_ID = 0x534B02
        private const val REQUEST_TOGGLE = 11
        private const val REQUEST_STOP = 12

        const val ACTION_PAUSE = "com.shangkele.action.PAUSE_RECORDING"
        const val ACTION_RESUME = "com.shangkele.action.RESUME_RECORDING"
        const val ACTION_STOP = "com.shangkele.action.STOP_RECORDING"

        fun intent(context: Context): Intent = Intent(context, RecordingService::class.java)

        fun formatElapsed(millis: Long): String {
            val totalSeconds = (millis / 1000).coerceAtLeast(0)
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%02d:%02d".format(minutes, seconds)
            }
        }
    }
}
