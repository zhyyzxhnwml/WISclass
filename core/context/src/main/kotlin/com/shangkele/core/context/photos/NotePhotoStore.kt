package com.shangkele.core.context.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 课堂照片的落盘。
 *
 * 路径是**确定性**的：`Pictures/photos/{noteId}/{拍摄时间戳}.jpg`（外部私有目录）。
 *
 * 两个刻意的选择：
 *  - 用**拍摄时间戳**而不是自增序号做文件名：相机应用需要一个「已存在且可写」的
 *    目标文件，这个文件必须在插入数据库之前就定下来。万一进程在按快门那一刻被杀，
 *    我们还能靠目录名里的 noteId 把孤儿照片认出来。
 *  - 不写 MediaStore、不申请任何存储权限：照片在本应用私有目录里，
 *    卸载即清，相册里也不会突然多出一堆课堂板书。
 */
@Singleton
class NotePhotoStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 为某条笔记分配一张新照片的目标文件。 */
    fun newPhotoFile(noteId: Long, takenAtMs: Long): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
        val dir = File(File(root, PHOTO_DIR), noteId.toString())
        dir.mkdirs()
        return File(dir, "$takenAtMs.jpg")
    }

    /**
     * 读取图片尺寸。**只读文件头**（`inJustDecodeBounds`），不会把整张图解码进内存 ——
     * 一张 1200 万像素的照片按 ARGB_8888 解出来是 48MB，直接解会 OOM。
     */
    fun readSize(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return runCatching {
            BitmapFactory.decodeFile(file.absolutePath, options)
            options.outWidth to options.outHeight
        }.getOrDefault(0 to 0)
    }

    /** FileProvider 的 authority。**必须与 AndroidManifest 里声明的一致。** */
    fun authority(): String = "${context.packageName}.fileprovider"

    /**
     * 把照片压成能发给视觉模型的 base64。
     *
     * **必须压**：一张 1200 万像素的照片 base64 之后十几 MB，多数接口直接回 413；
     * 压到长边 [maxEdge] / JPEG [quality] 之后约 150~250KB，内联进请求体毫无问题。
     *
     * 解码同样走 `inSampleSize` 两步降采样（先按 2 的幂次粗降，再精确缩放），
     * 直接解全尺寸会先吃掉几十 MB 内存。
     *
     * @return base64 字符串（**不含** `data:` 前缀）；文件读不出来返回 null
     */
    fun encodeForVision(file: File, maxEdge: Int = 1024, quality: Int = 85): String? {
        if (!file.isFile) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // inSampleSize 只能取 2 的幂，先粗降到「再降一半就小于目标」为止
        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= maxEdge ||
            bounds.outHeight / (sample * 2) >= maxEdge
        ) {
            sample *= 2
        }

        val decoded = runCatching {
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull() ?: return null

        val scaled = shrink(decoded, maxEdge)

        val base64 = ByteArrayOutputStream().use { out ->
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                // 回收再返回：中间那两张图不小，别让它们等 GC
                if (scaled !== decoded) decoded.recycle()
                scaled.recycle()
                return null
            }
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }

        // 两张都回收；shrink 返回原图时二者是同一个对象，判一下再收（重复回收会抛）
        if (scaled !== decoded) decoded.recycle()
        scaled.recycle()
        return base64
    }

    /** 长边缩到 [maxEdge]。不需要缩就原样返回，**不回收入参**（由调用方统一回收）。 */
    private fun shrink(source: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxEdge) return source
        val ratio = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    companion object {
        /** 与 `res/xml/file_paths.xml` 里 `<external-files-path path="Pictures/">` 对齐。 */
        const val PHOTO_DIR = "photos"
    }
}
