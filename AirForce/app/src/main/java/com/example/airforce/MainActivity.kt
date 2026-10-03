package com.example.airforce

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var game: GameView
    private lateinit var sound: SoundManager
    private lateinit var music: MusicManager
    private lateinit var root: FrameLayout
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 必须放在最前面：SharedPreferences 一旦被加载过，再改磁盘文件就没用了。
        // 这里会检查 files/import/ 下有没有外部推进来的存档。
        val importedSaves = SaveTransfer.importIfAny(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        sound = SoundManager(this)
        music = MusicManager(this)
        game = GameView(this, sound, music)

        // 一个完全透明的 EditText：只用来接管系统输入法，
        // 文字由 GameView 自己画在画布上（这样样式才统一）。
        input = EditText(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.TRANSPARENT)
            isCursorVisible = false
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(0, 0, 0, 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    game.onInputChanged(s?.toString() ?: "")
                }
            })
            // 实体键盘的 ENTER 会先 ACTION_DOWN 再 ACTION_UP 各触发一次，
            // 软键盘的「完成」按钮 event 为 null。只处理 DOWN，避免一次按键跳两格。
            setOnEditorActionListener { _, _, event ->
                if (event == null || event.action == KeyEvent.ACTION_DOWN) {
                    game.onInputDone()
                }
                true
            }
        }

        root = FrameLayout(this)
        root.addView(
            game,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            input,
            FrameLayout.LayoutParams(1, 1).apply { gravity = Gravity.TOP or Gravity.START }
        )
        setContentView(root)

        game.textInput = object : GameView.TextInputBridge {
            override fun open(initial: String, isPassword: Boolean) {
                input.inputType = if (isPassword)
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else
                    InputType.TYPE_CLASS_TEXT
                input.setText(initial)
                input.setSelection(input.text.length)
                input.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(input, 0)
            }

            override fun close() {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(input.windowToken, 0)
                input.clearFocus()
            }
        }

        // 监听软键盘高度，通知 GameView 把登录表单上移，避免被键盘挡住
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val visible = Rect()
            root.getWindowVisibleDisplayFrame(visible)
            val hidden = root.rootView.height - visible.bottom
            game.setKeyboardInset(if (hidden > 0) hidden.toFloat() else 0f)
        }

        hideSystemBars()

        if (importedSaves > 0) {
            Toast.makeText(
                this,
                "已导入 $importedSaves 个存档文件，进度已恢复",
                Toast.LENGTH_LONG
            ).show()
        }

        // 启动后稍等片刻再查更新，避免和开场画面抢注意力
        root.postDelayed({ checkForUpdate() }, 1500)
    }

    // ---------------- 游戏内自动更新 ----------------

    private var updatePrompted = false

    private fun currentVersionCode(): Int {
        val info = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            info.longVersionCode.toInt()
        else
            @Suppress("DEPRECATION") info.versionCode
    }

    /** 后台拉版本清单；有新版才弹窗，失败/已最新都静默 */
    private fun checkForUpdate() {
        Thread {
            val info = UpdateManager.fetchAny() ?: return@Thread
            if (info.versionCode <= currentVersionCode()) return@Thread
            runOnUiThread {
                if (!isFinishing && !updatePrompted) {
                    updatePrompted = true
                    showUpdateDialog(info)
                }
            }
        }.start()
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        val cur = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (t: Throwable) {
            "?"
        }
        val msg = buildString {
            append("发现新版本 v").append(info.versionName)
            append("（当前 v").append(cur).append("）\n\n")
            if (info.notes.isNotBlank()) append("更新内容：\n").append(info.notes)
        }
        AlertDialog.Builder(this)
            .setTitle("发现新版本")
            .setMessage(msg)
            .setPositiveButton("立即更新") { _, _ -> startUpdate(info) }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun startUpdate(info: UpdateInfo) {
        // 没授权「安装未知应用」时先引导去设置
        if (!UpdateManager.canInstall(this)) {
            AlertDialog.Builder(this)
                .setTitle("需要先授权")
                .setMessage("系统需要你允许「飞机大战」安装应用，之后更新就能一键完成。")
                .setPositiveButton("去设置") { _, _ ->
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (t: Throwable) {
                        Toast.makeText(this, "请手动到系统设置里授权", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val dlg = AlertDialog.Builder(this)
            .setTitle("正在下载更新")
            .setMessage("0%")
            .setCancelable(false)
            .create()
        dlg.show()

        Thread {
            val file = UpdateManager.download(this, info) { done, total ->
                val pct = if (total > 0) done * 100 / total else 0
                val text = if (total > 0)
                    "$pct%    ${done / 1048576}MB / ${total / 1048576}MB"
                else
                    "${done / 1048576}MB"
                runOnUiThread { if (!isFinishing) dlg.setMessage(text) }
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                dlg.dismiss()
                if (file == null) {
                    Toast.makeText(this, "下载失败，请检查网络后重试", Toast.LENGTH_LONG).show()
                } else {
                    try {
                        UpdateManager.install(this, file)
                    } catch (t: Throwable) {
                        Toast.makeText(this, "无法启动安装：${t.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        sound.resume()
        music.onResume()
        game.resume()
    }

    override fun onPause() {
        super.onPause()
        game.pause()
        // 顺手把存档导出一份到 files/export/，换机或重装时可以直接取走
        SaveTransfer.exportAll(this)
        music.onPause()
        sound.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        game.release()
        sound.release()
        music.release()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (!game.onBack()) super.onBackPressed()
    }
}
