package com.example.airforce

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 本地账号系统（离线）。
 *
 * - 账号注册表存在独立的 SharedPreferences 文件里，和游戏进度分开。
 * - 密码不保存明文：每个账号一份随机盐，做 1000 轮 SHA-256 散列后保存。
 *   （这是本地游戏，够用；它不是银行级安全，但也绝不裸存密码。）
 * - 每个账号有独立的存档文件（save_<id>），所以不同玩家进度互不干扰。
 */
internal class AccountStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("airforce_accounts", Context.MODE_PRIVATE)

    /** 所有已注册的用户名（按注册顺序） */
    fun names(): List<String> =
        prefs.getString("names", "")!!.split('|').filter { it.isNotEmpty() }

    fun exists(name: String): Boolean = names().contains(name)

    /** 上次登录且仍然存在的账号；没有则返回 null */
    fun lastAccount(): String? {
        val n = prefs.getString("last", "")!!
        return if (n.isNotEmpty() && exists(n)) n else null
    }

    fun setLast(name: String?) {
        prefs.edit().putString("last", name ?: "").apply()
    }

    /** 每个账号独立的存档文件名 */
    fun saveFile(name: String): String = "save_" + prefs.getInt("id_$name", 0)

    /**
     * 注册。成功返回 null；失败返回给玩家看的错误文案。
     * 用户名只允许字母 / 数字 / 下划线 / 非 ASCII（中文等），且不能含分隔符 '|'。
     */
    fun register(name: String, password: String): String? {
        val n = name.trim()
        if (n.length < 2) return "用户名至少 2 个字符"
        if (n.length > 16) return "用户名最多 16 个字符"
        if (!n.all { it.isLetterOrDigit() || it == '_' || it.code > 127 })
            return "用户名只能用字母、数字、下划线或中文"
        if (password.length < 3) return "密码至少 3 位"
        if (password.length > 32) return "密码最多 32 位"
        if (exists(n)) return "该用户名已被注册"

        val salt = randomSalt()
        val id = prefs.getInt("seq", 0) + 1
        val list = names() + n
        prefs.edit()
            .putInt("seq", id)
            .putString("names", list.joinToString("|"))
            .putString("salt_$n", salt)
            .putString("hash_$n", hash(password, salt))
            .putInt("id_$n", id)
            .putLong("ctime_$n", System.currentTimeMillis())
            .putString("last", n)
            .apply()
        return null
    }

    /**
     * 确保某个账号存在（不存在才创建）。用于内置的默认账号。
     * 与 register 的区别：不修改「上次登录」、不做长度/字符校验。
     * 返回 true 表示本次新建了。
     */
    fun ensureAccount(name: String, password: String): Boolean {
        if (exists(name)) return false
        val salt = randomSalt()
        val id = prefs.getInt("seq", 0) + 1
        val list = names() + name
        prefs.edit()
            .putInt("seq", id)
            .putString("names", list.joinToString("|"))
            .putString("salt_$name", salt)
            .putString("hash_$name", hash(password, salt))
            .putInt("id_$name", id)
            .putLong("ctime_$name", System.currentTimeMillis())
            .apply()
        return true
    }

    /** 登录。成功返回 null；失败返回错误文案。 */
    fun login(name: String, password: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return "请输入用户名"
        if (!exists(n)) return "账号不存在，请先注册"
        val salt = prefs.getString("salt_$n", "")!!
        val saved = prefs.getString("hash_$n", "")!!
        if (hash(password, salt) != saved) return "密码错误，请重试"
        setLast(n)
        return null
    }

    private fun randomSalt(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return b.toHex()
    }

    private fun hash(password: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        var data = (salt + ":" + password).toByteArray(Charsets.UTF_8)
        repeat(1000) {
            md.reset()
            data = md.digest(data)
        }
        return data.toHex()
    }
}

private const val HEX_DIGITS = "0123456789abcdef"

private fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX_DIGITS[v ushr 4])
        sb.append(HEX_DIGITS[v and 0x0F])
    }
    return sb.toString()
}
