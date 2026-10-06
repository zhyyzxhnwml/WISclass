package com.shangkele.app.update

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shangkele.core.update.ApkDownloader
import com.shangkele.core.update.ApkInstaller
import com.shangkele.core.update.UpdateChecker
import com.shangkele.core.update.UpdateManifest
import com.shangkele.core.update.UpdateManifestParser
import com.shangkele.core.update.UpdateSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** 更新检查的界面状态。 */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState

    data object Checking : UpdateUiState

    /** 已经是最新（手动检查时才提示，自动检查不打扰） */
    data class UpToDate(val version: String) : UpdateUiState

    data class Available(val manifest: UpdateManifest) : UpdateUiState

    data class Downloading(val downloaded: Long, val total: Long) : UpdateUiState {
        val percent: Int
            get() = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
    }

    /** 下好了，等用户点「安装」 */
    data class ReadyToInstall(val version: String, val apk: File) : UpdateUiState

    /** 需要用户先去系统里打开「安装未知应用」 */
    data class NeedInstallPermission(val apk: File) : UpdateUiState

    data class Failed(val message: String) : UpdateUiState
}

/**
 * 自动更新。
 *
 * 三个刻意的决定：
 *
 *  1. **下载和安装分开成两步**，不自动装。装 APK 是系统级动作，
 *     弹一个系统确认框把前因后果说清楚，比"砰"一下开始安装好。
 *  2. **同一个版本点了「以后再说」就不再自动弹** —— 否则每次开 App 都被拦一次。
 *  3. **检查失败要说清楚原因**。这条最容易被写成静默无反应，
 *     而「更新检查失败」和「已经是最新版」在用户眼里长得一模一样。
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val checker: UpdateChecker,
    private val downloader: ApkDownloader,
    private val installer: ApkInstaller,
    private val source: UpdateSource,
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /** 当前版本名，弹窗和设置页都要显示。 */
    val currentVersion: String = currentVersionName()

    /**
     * 开 App 时的静默检查。
     *
     * 有频率限制、也会跳过用户已忽略的版本；结果只在**真有新版**时才让界面弹东西。
     */
    fun autoCheck() {
        val now = System.currentTimeMillis()
        if (now - source.lastCheckMs < UpdateSource.AUTO_CHECK_INTERVAL_MS) return
        viewModelScope.launch {
            source.lastCheckMs = now
            when (val result = checker.check(currentVersion)) {
                is UpdateChecker.CheckResult.Available ->
                    if (result.manifest.version != source.skippedVersion) {
                        _state.value = UpdateUiState.Available(result.manifest)
                    }

                // 自动检查失败不打扰：用户没主动问，弹一个"检查失败"只是噪音
                else -> Unit
            }
        }
    }

    /** 用户在设置里主动点「检查更新」。这时失败原因必须说出来。 */
    fun checkNow() {
        if (_state.value is UpdateUiState.Checking) return
        viewModelScope.launch {
            _state.value = UpdateUiState.Checking
            _state.value = when (val result = checker.check(currentVersion)) {
                is UpdateChecker.CheckResult.Available -> UpdateUiState.Available(result.manifest)
                is UpdateChecker.CheckResult.UpToDate -> UpdateUiState.UpToDate(result.current)
                is UpdateChecker.CheckResult.Failed -> UpdateUiState.Failed(result.message)
            }
        }
    }

    fun download() {
        val manifest = (_state.value as? UpdateUiState.Available)?.manifest ?: return
        viewModelScope.launch {
            _state.value = UpdateUiState.Downloading(0L, manifest.sizeBytes)
            val result = downloader.download(manifest) { done, total ->
                _state.value = UpdateUiState.Downloading(done, total)
            }
            _state.value = result.fold(
                onSuccess = { apk ->
                    if (installer.canInstall()) {
                        UpdateUiState.ReadyToInstall(manifest.version, apk)
                    } else {
                        UpdateUiState.NeedInstallPermission(apk)
                    }
                },
                onFailure = { UpdateUiState.Failed(it.message ?: "下载失败") },
            )
        }
    }

    /** 点「安装」。系统没给权限时改为把用户带到那个开关页面。 */
    fun install(apk: File) {
        if (!installer.canInstall()) {
            _state.value = UpdateUiState.NeedInstallPermission(apk)
            return
        }
        if (!installer.install(apk)) {
            _state.value = UpdateUiState.Failed("调不起系统安装器，可能是文件已损坏")
        }
    }

    fun openInstallPermissionSettings() {
        if (!installer.openInstallPermissionSettings()) {
            _state.value = UpdateUiState.Failed(
                "打不开系统页面，请手动到「设置 → 应用 → 上课啦 → 安装未知应用」里打开",
            )
        }
    }

    /** 「以后再说」：记下这个版本，自动检查不再弹它（手动检查仍能看到）。 */
    fun skip(version: String) {
        source.skippedVersion = version
        _state.value = UpdateUiState.Idle
    }

    fun dismiss() {
        _state.value = UpdateUiState.Idle
    }

    fun consumeResult() {
        if (_state.value is UpdateUiState.UpToDate || _state.value is UpdateUiState.Failed) {
            _state.value = UpdateUiState.Idle
        }
    }

    fun notesPreview(manifest: UpdateManifest): String = UpdateManifestParser.trimNotes(manifest.notes)

    private fun currentVersionName(): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return info.versionName.orEmpty()
    }
}
