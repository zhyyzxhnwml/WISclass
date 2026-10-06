package com.shangkele.core.update

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import android.widget.Toast

/**
 * 收安装结果的一层透明页面（没有界面，做完事立刻 finish）。
 *
 * 为什么不写成广播接收器：拿到 `STATUS_PENDING_USER_ACTION` 之后，要**由我们主动
 * 把系统的确认界面拉起来**；而广播接收器在 Android 10+ 起不能直接启动 Activity
 * （后台启动限制）。换成一个被系统拉起到前台的 Activity 就没这个限制。
 */
class InstallResultActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            // 系统在等用户点确认。这一步必须做，否则安装就永远停在「正在安装」
            PackageInstaller.STATUS_PENDING_USER_ACTION -> launchSystemConfirm()

            // 装成功时这个 App 已经被替换掉了，这里其实不会再执行
            PackageInstaller.STATUS_SUCCESS -> Unit

            // 失败原因必须说出来 —— 以前是「一直正在安装」然后不了了之，
            // 用户既不知道成没成，也不知道为什么，只能反复重试
            else -> toast("安装没成功：${statusDetail()}")
        }
        finish()
    }

    private fun launchSystemConfirm() {
        @Suppress("DEPRECATION")
        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        if (confirm == null) {
            toast("系统没有给出安装确认界面，安装没有继续")
            return
        }
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(confirm) }
            .onFailure { toast("打不开系统确认界面：${it.message ?: "未知原因"}") }
    }

    private fun statusDetail(): String =
        intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            ?.takeIf { it.isNotBlank() }
            ?: "系统没给原因"

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
