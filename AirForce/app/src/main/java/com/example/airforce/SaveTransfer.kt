package com.example.airforce

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 存档导入 / 导出（换手机、重装时搬进度用）。
 *
 * 游戏的所有存档都是 SharedPreferences 的 XML 文件，躺在应用私有的
 * `shared_prefs/` 里。普通用户碰不到，而一旦卸载（或者换了签名要重装），
 * 这些文件就跟着没了。
 *
 * 这个类用「应用外部文件目录」当中转站把它打通：
 *
 * ```
 * Android/data/com.example.airforce/files/import/   ← 放这里的 *.xml 下次启动时自动导入
 * Android/data/com.example.airforce/files/export/   ← 退到后台时自动把当前存档导出到这里
 * ```
 *
 * 选外部文件目录而不是公共目录，是因为它：
 *   1. 不需要任何存储权限（Android 11+ 也一样）；
 *   2. `adb push` / `adb pull` 可以直接读写，不需要 root，也不需要 run-as。
 *
 * 所以「卸载旧版 → 装新版 → 把 XML 推进 import/ → 重启」就能把进度搬回来，
 * 哪怕新旧包签名不同也照样管用。
 */
internal object SaveTransfer {

    private const val TAG = "SaveTransfer"
    private const val DIR_IMPORT = "import"
    private const val DIR_EXPORT = "export"
    private const val DIR_DONE = "_done"

    /** 存档文件所在目录：`<dataDir>/shared_prefs` */
    private fun prefsDir(context: Context): File =
        File(context.filesDir.parentFile, "shared_prefs")

    /**
     * 启动时调用：把 `import/` 里的存档覆盖进 `shared_prefs/`。
     *
     * **必须在任何 `getSharedPreferences()` 之前调用** —— SharedPreferences 一旦
     * 加载过某个文件，就会以内存里的副本为准，之后覆盖磁盘文件是没用的。
     *
     * @return 实际导入的文件数
     */
    fun importIfAny(context: Context): Int {
        val ext = context.getExternalFilesDir(null) ?: return 0
        val srcDir = File(ext, DIR_IMPORT)

        // 关键：目录必须由**应用自己**创建。
        // 如果用 adb 在外面建，属主会是 shell，而应用并不在 ext_data_rw 组里，
        // 目录权限 drwxrws--- 对应用来说就是 ---，连目录都进不去。
        // 应用自己建则是 u0_aXX:ext_data_rw，属主权限生效。
        // 外面推上来的文件是 -rw-rw-rw-（全局可读），应用读没问题；
        // 因为目录归应用所有，改名/删除也都做得到。
        if (!srcDir.exists() && !srcDir.mkdirs()) {
            Log.w(TAG, "无法创建 import 目录: $srcDir")
            return 0
        }

        val files = srcDir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }
        if (files == null) {
            Log.w(TAG, "无法读取 import 目录: $srcDir")
            return 0
        }
        if (files.isEmpty()) return 0

        val dstDir = prefsDir(context)
        if (!dstDir.exists()) dstDir.mkdirs()

        var n = 0
        for (f in files) {
            try {
                f.copyTo(File(dstDir, f.name), overwrite = true)
                n++
            } catch (t: Throwable) {
                // 单个文件失败不影响其它的
                Log.w(TAG, "导入失败: ${f.name}", t)
            }
        }

        // 已导入的挪进 _done/，避免每次启动重复导入
        if (n > 0) {
            val done = File(srcDir, DIR_DONE)
            if (!done.exists()) done.mkdirs()
            for (f in files) {
                try {
                    f.renameTo(File(done, f.name))
                } catch (t: Throwable) {
                    Log.w(TAG, "归档失败: ${f.name}", t)
                }
            }
        }
        Log.i(TAG, "导入完成：发现 ${files.size} 个文件，成功 $n 个")
        return n
    }

    /**
     * 退到后台时调用：把当前存档导出到 `export/`。
     *
     * SharedPreferences 落盘用的是 AtomicFile（先写 `.new` 再改名），
     * 所以主文件任何时刻都是完整的，中途复制不会拿到半截 XML。
     */
    fun exportAll(context: Context) {
        val srcDir = prefsDir(context)
        val files = srcDir.listFiles { f -> f.isFile && f.name.endsWith(".xml") } ?: return
        if (files.isEmpty()) return

        val ext = context.getExternalFilesDir(null) ?: return
        val dstDir = File(ext, DIR_EXPORT)
        if (!dstDir.exists() && !dstDir.mkdirs()) return

        var n = 0
        for (f in files) {
            try {
                f.copyTo(File(dstDir, f.name), overwrite = true)
                n++
            } catch (_: Throwable) {
            }
        }
        Log.i(TAG, "导出完成：$n 个文件 -> $dstDir")
    }
}
