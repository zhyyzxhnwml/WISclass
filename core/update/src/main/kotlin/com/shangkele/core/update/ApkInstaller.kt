package com.shangkele.core.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 触发系统安装器装我们刚下载的 APK。
 *
 * Android 8 起，应用要装别的应用得先拿到「安装未知应用」的授权，
 * 而那个开关**只能在系统设置里由用户打开**，代码无法代劳。
 * 所以这里做三件事：判断有没有权限、给一个直达那个开关页面的入口、
 * 以及在这种情况下把话说清楚 —— 否则用户只会看到「点了没反应」。
 */
@Singleton
class ApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 系统是否已允许本应用安装 APK。Android 8 以下没有这个限制。 */
    fun canInstall(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** 跳到「允许安装未知应用」的系统页面。 */
    fun openInstallPermissionSettings(): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

    /**
     * 发起安装。优先走 [installViaSession]，失败才退回老的 `ACTION_VIEW`。
     */
    suspend fun install(apk: File): Boolean = withContext(Dispatchers.IO) {
        if (!apk.isFile || apk.length() == 0L) return@withContext false
        installViaSession(apk) || installViaIntent(apk)
    }

    /**
     * 走 **PackageInstaller 会话** —— 这才是正路。
     *
     * 之前的做法是 FileProvider 授一个 `content://` 给系统安装器，让它自己来读。
     * 问题是那个 Uri 由**我们自己的进程**提供，而系统要装的恰好就是我们这个包：
     * 它得先杀掉我们才能替换，可一旦杀掉，Uri 就没人提供了。
     * 表现就是「一直卡在正在安装」，而退出、杀掉进程、重新打开再点一次反而能装上
     * （那次时序凑巧对上了）—— 用户报的就是这个。
     *
     * 会话 API 会把 APK **完整复制进系统自己的会话目录**，之后由系统独立完成安装：
     * 我们的进程死不死都不影响，也不存在「读到一半没了」。
     *
     * 整个过程异步：结果回到 [InstallResultActivity]。
     */
    private fun installViaSession(apk: File): Boolean = runCatching {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setSize(apk.length())
        }

        val sessionId = installer.createSession(params)
        var committed = false
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite(SESSION_NAME, 0, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                // commit 要的是 IntentSender，不是 PendingIntent
                session.commit(resultIntent().intentSender)
            }
            committed = true
            true
        } finally {
            // 没走到 commit 就把会话丢掉：否则系统里会留一个永远完不成的会话，
            // 下次安装还可能被它挡住
            if (!committed) runCatching { installer.abandonSession(sessionId) }
        }
    }.getOrDefault(false)

    /**
     * 退路：老的 FileProvider + `ACTION_VIEW`。
     *
     * 只在会话那条路直接抛异常时才会走到（例如某些 ROM 把会话功能限制掉了）。
     * 它带着上面那个「进程被杀就没人提供文件」的毛病，但总比点了完全没反应好。
     */
    private fun installViaIntent(apk: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        true
    }.getOrDefault(false)

    /**
     * 结果回给一个**透明的 Activity** 而不是广播接收器。
     *
     * 因为拿到 `STATUS_PENDING_USER_ACTION` 之后得由我们主动把系统的确认界面拉起来，
     * 而广播接收器在 Android 10+ 起不能直接启动 Activity（后台启动限制）。
     */
    private fun resultIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, InstallResultActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val SESSION_NAME = "shangkele.apk"
        const val REQUEST_CODE = 0x534B03
    }
}
