package com.shangkele.core.context.photos

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
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

    companion object {
        /** 与 `res/xml/file_paths.xml` 里 `<external-files-path path="Pictures/">` 对齐。 */
        const val PHOTO_DIR = "photos"
    }
}
