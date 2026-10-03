package com.example.airforce

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 游戏内自动更新。
 *
 * 更新源就是发布出来的那个静态站点（见 UPDATE_BASE_URL）：
 *   <base>/version.json   版本清单（versionCode / versionName / apkUrl / notes）
 *   <base>/app.apk        最新安装包
 *
 * 流程：拉清单 → 比对 versionCode → 有新版就下载 → 交给系统安装器。
 * 注意：Android 不允许 App 静默替换自己，最后一定会弹系统「安装」确认，
 * 玩家点一下即可 —— 但不用连电脑、不用敲命令了。
 */
/** 公网更新源：下载页所在地址。分享给别人的手机靠它更新。 */
private const val PUBLIC_UPDATE_URL = "https://11f45519734a4dcd8e70a1e081330ac2.sg2.agentos-app.run"

/**
 * 局域网更新源（见项目根目录的 启动更新服务.bat）。
 * 地址从 local.properties 的 lanUpdateUrl 注入 —— 那个文件不入库，
 * 所以仓库里不会出现任何人的内网 IP。没配就只走公网源。
 */
private val LAN_UPDATE_URL = BuildConfig.LAN_UPDATE_URL

/**
 * 更新源候选地址，按顺序尝试，第一个能通的生效。
 * 本机开发时局域网更快所以排在前面；手机在外网时自动回落到公网源。
 */
internal val UPDATE_SOURCES: List<String> = run {
    val list = ArrayList<String>(2)
    if (LAN_UPDATE_URL.isNotBlank()) list.add(LAN_UPDATE_URL)
    list.add(PUBLIC_UPDATE_URL)
    list
}

internal data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String
)

internal object UpdateManager {

    /** 依次尝试所有更新源，返回第一个能通的版本清单；全失败返回 null（静默，不打扰玩家）。 */
    fun fetchAny(): UpdateInfo? {
        for (base in UPDATE_SOURCES) {
            val info = fetchFrom(base)
            if (info != null) return info
        }
        return null
    }

    /** 拉取某个更新源的版本清单。任何失败都返回 null。 */
    fun fetchFrom(baseUrl: String): UpdateInfo? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$baseUrl/version.json").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("Cache-Control", "no-cache")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(body)
            val code = o.optInt("versionCode", 0)
            if (code <= 0) return null
            val apk = o.optString("apkUrl", "app.apk")
            UpdateInfo(
                versionCode = code,
                versionName = o.optString("versionName", code.toString()),
                apkUrl = if (apk.startsWith("http")) apk else "$baseUrl/$apk",
                notes = o.optString("notes", "")
            )
        } catch (t: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** 下载 APK 到 cacheDir/update.apk。失败返回 null。 */
    fun download(context: Context, info: UpdateInfo, onProgress: (Int, Int) -> Unit): File? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 30000
            }
            val total = conn.contentLength
            val out = File(context.cacheDir, "update.apk")
            conn.inputStream.use { input ->
                FileOutputStream(out).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            out
        } catch (t: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** 是否已获得「安装未知应用」权限 */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.packageManager.canRequestPackageInstalls()
        else true

    /** 通过 PackageInstaller 交给系统安装器（会弹系统确认框） */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                FileInputStream(apk).use { input -> input.copyTo(out) }
                out.flush()
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
                .setAction(InstallResultReceiver.ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pi.intentSender)
        }
    }
}

/** 安装结果回调（系统安装器装完后广播回来） */
internal class InstallResultReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "com.example.airforce.INSTALL_RESULT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, "更新完成，重新打开游戏即可", Toast.LENGTH_LONG).show()
        } else {
            val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
            Toast.makeText(context, "安装未完成：$msg", Toast.LENGTH_LONG).show()
        }
    }
}
