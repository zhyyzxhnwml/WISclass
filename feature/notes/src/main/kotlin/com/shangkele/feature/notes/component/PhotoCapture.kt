package com.shangkele.feature.notes.component

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 调系统相机拍一张照片，写进 [createTarget] 指定的文件。
 *
 * **用系统相机（`TakePicture`）而不是 CameraX**：我们要的就是「拍一张清晰的
 * 板书」，不需要实时预览、对焦框、滤镜那一整套。系统相机省掉一个几 MB 的依赖、
 * 一套相机生命周期，而且用户自己那个相机的画质和对焦习惯也更熟。
 *
 * 注意清单里**不能声明 CAMERA 权限**：一旦声明，`ACTION_IMAGE_CAPTURE`
 * 就会反过来要求本应用自己持有该权限；不声明则由相机应用负责。见 app 的 AndroidManifest。
 *
 * @param createTarget 每次按快门前调用一次，返回相机要写入的目标文件
 * @param onResult 拍完回调。`success=false` 表示用户取消了，此时目标文件要删掉
 */
@Composable
fun rememberPhotoCapture(
    createTarget: () -> File?,
    onResult: (file: File, success: Boolean) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<File?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val file = pending
        pending = null
        if (file != null) onResult(file, success)
    }

    return {
        val target = createTarget()
        if (target == null) {
            // 拿不到可写目录（极少数情况）。不弹相机，交给上层提示。
            pending = null
        } else {
            pending = target
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target,
            )
            launcher.launch(uri)
        }
    }
}

/**
 * 按需解码一张缩略图。
 *
 * 手写而不是引 Coil：整个项目只在这一处要显示图片，为它拉一个图片库不划算。
 * 关键在 `inSampleSize` —— 一张 1200 万像素的照片按原尺寸解出来是 48MB，
 * 滚动几下就 OOM；这里按 2 的幂次往下采样到目标尺寸附近。
 */
@Composable
fun rememberPhotoThumbnail(path: String, targetSize: Int = 640): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path, targetSize) {
        value = withContext(Dispatchers.IO) { decodeSampled(path, targetSize) }
    }
    return bitmap
}

private fun decodeSampled(path: String, target: Int): ImageBitmap? {
    val file = File(path)
    if (!file.isFile) return null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    // 一直翻倍，直到再翻一倍就比目标还小
    while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
        sample *= 2
    }

    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { BitmapFactory.decodeFile(path, options)?.asImageBitmap() }.getOrNull()
}
