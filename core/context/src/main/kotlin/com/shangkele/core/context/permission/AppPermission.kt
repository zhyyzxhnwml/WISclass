package com.shangkele.core.context.permission

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本项目需要的「特殊权限」。
 *
 * 这些都不是普通运行时权限，只能检查 + 跳设置页让用户自己开，
 * 所以界面上必须做成**可检查的清单**，而且要能容忍用户不开
 * （每一项都要有降级路径，见 docs/07-荣耀200适配清单.md §二）。
 */
enum class AppPermission {
    /** 课程提醒、长任务进度通知 */
    Notifications,

    /** 上课自动静音 */
    DoNotDisturbAccess,

    /** 精准到分钟的上课/下课切换 */
    ExactAlarm,

    /** 电池优化白名单：不加，长任务和闹钟都可能被 MagicOS 掐掉 */
    BatteryOptimization,

    /** 自启动 / 后台活动：无法程序化检查，只能给操作说明 */
    AutoStart,
}

@Singleton
class PermissionInspector @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun isGranted(permission: AppPermission): Boolean = try {
        when (permission) {
            AppPermission.Notifications -> isNotificationGranted()

            AppPermission.DoNotDisturbAccess -> {
                val manager = context.getSystemService(android.app.NotificationManager::class.java)
                manager?.isNotificationPolicyAccessGranted == true
            }

            AppPermission.ExactAlarm -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    true
                } else {
                    context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
                }
            }

            AppPermission.BatteryOptimization -> {
                context.getSystemService(PowerManager::class.java)
                    ?.isIgnoringBatteryOptimizations(context.packageName) == true
            }

            // 没有公开 API 可查，永远按「未确认」显示，引导用户自己看一眼
            AppPermission.AutoStart -> false
        }
    } catch (e: Exception) {
        false
    }

    private fun isNotificationGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** 跳转到对应设置页。取不到具体页面时一律退到应用详情页。 */
    fun settingsIntent(permission: AppPermission): Intent {
        val packageUri = Uri.fromParts("package", context.packageName, null)
        val intent = when (permission) {
            AppPermission.Notifications -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

            AppPermission.DoNotDisturbAccess ->
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

            AppPermission.ExactAlarm ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(packageUri)
                } else {
                    appDetailsIntent()
                }

            AppPermission.BatteryOptimization ->
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(packageUri)

            // 荣耀的应用启动管理没有公开 Activity，只能跳到应用详情页配文字说明
            AppPermission.AutoStart -> appDetailsIntent()
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
}
