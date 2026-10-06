package com.shangkele.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
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
     * 发起安装。
     *
     * 用 FileProvider 授一个只读 Uri，而不是传 `file://` —— 后者在 Android 7+
     * 直接抛 `FileUriExposedException`。授权也顺手给了 `FLAG_GRANT_READ_URI_PERMISSION`，
     * 否则系统安装器读不到文件。
     */
    fun install(apk: File): Boolean {
        if (!apk.isFile || apk.length() == 0L) return false
        return runCatching {
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
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
