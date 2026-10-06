package com.shangkele.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.shangkele.app.ui.AppRoot
import com.shangkele.app.ui.theme.ShangKeLeTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 唯一 Activity。
 *
 * `enableEdgeToEdge()` 是荣耀 200 四曲屏的前提：内容全屏铺开，
 * 再由各页面用 WindowInsets 留出安全边距（见 docs/07-荣耀200适配清单.md §七）。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // 必须在 Activity 进入 STARTED 之前注册，所以写成字段
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝则降级：提醒不响，其余功能照常 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        askNotificationPermissionIfNeeded()

        setContent {
            ShangKeLeTheme {
                AppRoot()
            }
        }
    }

    /**
     * Android 13+ 通知要运行时授权。
     *
     * 不做「先弹理由再申请」那一套 —— 首启直接申请，拒绝也能用，
     * 设置页里有常驻入口可以再打开。
     */
    private fun askNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
