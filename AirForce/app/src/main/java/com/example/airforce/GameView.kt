package com.example.airforce

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class GameView(
    context: Context,
    private val sound: SoundManager,
    private val music: MusicManager
) : View(context) {

    private enum class State {
        LOGIN, REGISTER,
        READY, LEVEL_INTRO, PLAYING, PAUSED, LEVEL_CLEAR,
        GAME_OVER, GAME_COMPLETE, SHOP, ACHIEVEMENTS, STATS, SETTINGS,
        SHIP_SELECT, GEAR_SHOP,
        REWARDS, BOX, STARS
    }

    /** 文本输入框桥：由 Activity 提供一个真实 EditText 来接管系统输入法。 */
    interface TextInputBridge {
        fun open(initial: String, isPassword: Boolean)
        fun close()
    }

    private companion object {
        const val FIXED = 1f / 60f
        const val MAX_STEPS = 6
        const val MENU_BUTTONS = 10     // 9 个原有入口 + 奖励中心
        const val SHOP_ROWS = 5
        const val ACH_PER_PAGE = 8
        const val SETTINGS_ROWS = 5
        const val SHIP_ROWS = 6
        const val GEAR_ROWS = 5
        const val STAR_MILE_ROWS = 6    // 星级里程碑条数
        const val BOX_ROWS = 2          // 宝箱数量

        // ---- 伪 3D ----
        const val FOG_LEVELS = 6        // 景深雾化分档数
        const val BOX_POPUP_TIME = 2.6f // 开箱结果弹层停留时长
        const val BOX_FLIP_TIME = 0.46f // 翻牌动画时长

        // 当前正在编辑的输入框
        const val F_NONE = -1
        const val F_LG_USER = 0
        const val F_LG_PASS = 1
        const val F_RG_USER = 2
        const val F_RG_PASS = 3
        const val F_RG_PASS2 = 4
    }

    private var vw = 0f
    private var vh = 0f
    private var topInset = 0f

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val playerR = dp(16f)

    private val sp = Sprites(density)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()
    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val bgDst = RectF()
    private val wingPath = android.graphics.Path()
    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    // ============================================================
    //  伪 3D 渲染参数
    //  纯 Canvas 2D 引擎，没有真正的深度缓冲，所有立体感都靠
    //  「透视投影 + 景深雾化 + 剪影投影 + 透视翻转」四招模拟。
    // ============================================================

    /** 透视焦距（dp）：越大视野越窄、纵深拉得越长 */
    private val focal = dp(150f)
    /** 深度范围：zNear 贴脸，zFar 最远 */
    private val zNear = 0.06f
    private val zFar = 1f
    /** 拖尾倍数：用「更远一点」的深度投出拖尾起点，形成拉丝 */
    private val trailK = 1.34f
    /** 星空向镜头推进的速度范围（z / 秒） */
    private val vzMin = 0.12f
    private val vzMax = 0.57f
    /** 景深雾化最浓时的混色比例（别太高，否则刚出场的敌机看不清） */
    private val fogMax = 0.50f
    /** 远处物体的视觉缩放下限（底部为 1.0） */
    private val depthScaleMin = 0.88f

    /** 星空拖尾画笔（每帧改 color / strokeWidth） */
    private val warpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** 纯黑剪影画笔：用 SRC_IN 把贴图压成黑色，拿来画投影和「厚度层」 */
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(0xFF000000.toInt(), PorterDuff.Mode.SRC_IN)
    }

    /** 景深雾化画笔：按档预生成 ColorMatrix，避免每帧新建对象 */
    private val fogPaints = Array(FOG_LEVELS) { Paint(Paint.FILTER_BITMAP_FLAG) }
    private var fogColor = 0

    /** 玩家横滚角（弧度）与其上一帧 x 坐标，用来算横向速度 */
    private var playerRoll = 0f
    private var prevPx = 0f

    private val choreographer by lazy { Choreographer.getInstance() }
    private val frameCb = Choreographer.FrameCallback { doFrame(it) }
    private var running = false
    private var lastNanos = 0L
    private var accumulator = 0f

    private var state = State.READY
    private var released = false

    // ---- 玩家 ----
    private var px = 0f
    private var py = 0f
    private var tx = 0f
    private var ty = 0f
    private var lives = 3
    private var weaponLevel = 1
    private var bombs = 2
    private var invincible = 0f
    private var shield = 0f
    private var fireTimer = 0f
    private var missileTimer = 0f
    private var trailTimer = 0f
    private var magnetTimer = 0f
    private var slowTimer = 0f
    private var reviveUsed = false

    private var laserTimer = 0f
    private var laserSndTimer = 0f

    // ---- 全局 ----
    private var score = 0
    private var highScore = 0
    private var kills = 0
    private var combo = 0
    private var comboTimer = 0f
    private var elapsed = 0f
    private var spawnTimer = 0.7f
    private var bossAlive = false
    private var bossPhase = 1
    private var bossSpin = 0f
    private var shake = 0f
    private var hitFlash = 0f
    private var hitStop = 0f
    private var overCooldown = 0f
    private var menuTime = 0f
    private var newRecord = false
    private var runCoins = 0
    private var tookDamageThisLevel = false
    private var introTimer = 0f
    private var clearTimer = 0f
    private var levelScore = 0
    private var levelStartScore = 0
    private var runStars = 0                 // 本次过关拿到的星数（0..3）
    private var starJustImproved = false     // 本次是否刷新了该关的星纪录

    // ---- 关卡 ----
    private var levelIndex = 0
    private var level: LevelConfig = LEVELS[0]
    private var swarmTimer = 0f
    private var swarmDone = false
    private var isDaily = false
    private var daily: DailyChallenge? = null

    // ---- 进度 ----
    private val upgrades = UpgradeState()
    private val ach = AchievementState()
    private val rewards = RewardState()
    private var dailyBest = 0
    private var dailyBestKey = 0

    // ---- 设置 ----
    private var soundOn = true
    private var vibrateOn = true
    private var musicOn = true
    private var musicVolume = 0.55f

    // ---- 战机 / 装备 / 模式 ----
    private val ships = ShipState()
    private val gear = GearState()
    private var mode = M_CAMPAIGN
    private var isEndless = false
    private var isBossRush = false
    private var runShip = SHIP_DAWN
    private var wingTimer = 0f
    private var regenTimer = 0f
    private var bossRushIndex = 0
    private var endlessNextBoss = 45f

    // ---- 账号 ----
    private val accounts = AccountStore(context)
    private var accountName: String? = null
    private var loginUser = ""
    private var loginPass = ""
    private var regUser = ""
    private var regPass = ""
    private var regPass2 = ""
    private var activeField = F_NONE
    private var loginMsg = ""
    private var loginMsgOk = false
    private var cursorT = 0f
    private var imeInset = 0f
    private var lastDoneAt = 0L
    var textInput: TextInputBridge? = null

    private val btnLgUser = RectF()
    private val btnLgPass = RectF()
    private val btnLgSubmit = RectF()
    private val btnLgToReg = RectF()
    private val btnRgUser = RectF()
    private val btnRgPass = RectF()
    private val btnRgPass2 = RectF()
    private val btnRgSubmit = RectF()
    private val btnRgToLogin = RectF()

    // ---- 背景图 ----
    private var bgBitmap: Bitmap? = null
    private var bgResId = 0

    // ---- HUD 缓存 ----
    private var hudScore = "0"
    private var hudCombo = ""
    private var hudHigh = "最高 0"
    private var hudHighBig = "历史最高分 0"
    private var hudBomb = "x2"
    private var hudCoins = "0"
    private var hudLevel = "第 1 关"
    private var hudPauseInfo = ""
    private var hudFinalScore = ""
    private var hudFinalStats = ""
    private var hudClearTitle = ""
    private var hudClearInfo = ""
    private var hudAchToast = ""
    private var hudAchToastSub = ""
    private var bossRef: Enemy? = null
    private val fm = Paint.FontMetrics()

    private var achToastTimer = 0f
    private var achToastColor = 0
    private val achQueue = ArrayList<Achievement>(4)

    private var dragging = false
    private var activePointer = -1

    // ---- 按钮 ----
    private val btnResume = RectF()
    private val btnRestart = RectF()
    private val btnQuit = RectF()
    private val btnAgain = RectF()
    private val btnBack = RectF()
    private val menuButtons = Array(MENU_BUTTONS) { RectF() }
    private val shopRows = Array(SHOP_ROWS) { RectF() }
    private val btnShopPrev = RectF()
    private val btnShopNext = RectF()
    private val weaponBtns = Array(W_COUNT) { RectF() }
    private val btnAchPrev = RectF()
    private val btnAchNext = RectF()
    private val btnClearNext = RectF()
    private val settingsRows = Array(SETTINGS_ROWS) { RectF() }
    private val shipRows = Array(SHIP_ROWS) { RectF() }
    private val gearRows = Array(GEAR_ROWS) { RectF() }
    private val btnVolDown = RectF()
    private val btnVolUp = RectF()
    private val btnGearPrev = RectF()
    private val btnGearNext = RectF()
    private val btnShopGear = RectF()

    // ---- 奖励中心 ----
    private val checkinCells = Array(CHECKIN_DAYS) { RectF() }
    private val btnCheckin = RectF()
    private val taskRows = Array(TASKS_PER_DAY) { RectF() }
    private val tierBtns = Array(ACTIVITY_TIERS.size) { RectF() }
    private val btnRewardsBox = RectF()
    private val btnRewardsStars = RectF()
    private val boxRows = Array(BOX_ROWS) { RectF() }
    private val starCells = Array(LEVELS.size) { RectF() }
    private val starRows = Array(STAR_MILESTONES.size) { RectF() }

    // 开箱结果弹层
    private var boxTimer = 0f
    private var boxTitle = ""
    private var boxSub = ""
    private var boxColor = 0xFFFFD54F.toInt()
    private var gearPage = 0
    private var pauseBtnX = 0f
    private var pauseBtnY = 0f
    private var bombBtnX = 0f
    private var bombBtnY = 0f
    private var shopPage = 0
    private var achPage = 0

    // ---- 容器 ----
    private val bullets = ArrayList<Bullet>(320)
    private val particles = ArrayList<Particle>(640)
    private val enemies = ArrayList<Enemy>(64)
    private val powerups = ArrayList<PowerUp>(24)
    private val rings = ArrayList<Ring>(40)
    private val texts = ArrayList<FloatText>(40)
    private val stars = ArrayList<Star>(160)
    private val nebs = ArrayList<Neb>(4)

    private val bulletPool = Pool({ Bullet() }, 640)
    private val particlePool = Pool({ Particle() }, 1100)
    private val ringPool = Pool({ Ring() }, 64)
    private val textPool = Pool({ FloatText() }, 64)
    private val enemyPool = Pool({ Enemy() }, 96)
    private val powerupPool = Pool({ PowerUp() }, 48)

    /** 全局设置（音效 / 震动 / 音乐，与账号无关） */
    private val globalPrefs: SharedPreferences by lazy {
        context.getSharedPreferences("airforce", Context.MODE_PRIVATE)
    }

    /** 当前账号的存档文件；未登录时指向全局文件（saveProgress 会提前返回） */
    private var prefs: SharedPreferences = globalPrefs

    private val vibrator: Vibrator? by lazy {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    init {
        // 音频设置是设备级的，不区分账号
        soundOn = globalPrefs.getBoolean("soundOn", true)
        vibrateOn = globalPrefs.getBoolean("vibrateOn", true)
        musicOn = globalPrefs.getBoolean("musicOn", true)
        musicVolume = globalPrefs.getFloat("musicVol", 0.55f)
        sound.enabled = soundOn
        music.enabled = musicOn
        music.volume = musicVolume
        sp.build()
        isFocusable = true

        // 内置一个默认测试账号：用户名 123 / 密码 123（已存在则不重复创建）。
        // 想删掉它，直接注释掉下面这一行即可。
        accounts.ensureAccount("123", "123")

        // 有上次登录的账号就直接进游戏，否则先登录
        val last = accounts.lastAccount()
        if (last != null) {
            enterAccount(last)
        } else {
            state = State.LOGIN
            loginUser = accounts.names().lastOrNull() ?: ""
            layoutLogin()
        }
    }

    // ---------------- 账号 ----------------

    /** 载入某个账号的存档并进入主菜单 */
    private fun enterAccount(name: String) {
        accountName = name
        accounts.setLast(name)
        prefs = context.getSharedPreferences(accounts.saveFile(name), Context.MODE_PRIVATE)
        loadProgress()
        activeField = F_NONE
        textInput?.close()
        state = State.READY
        refreshMenu()
    }

    private fun loadProgress() {
        highScore = prefs.getInt("high", 0)
        hudHigh = "最高 $highScore"
        hudHighBig = "历史最高分 $highScore"
        upgrades.load(prefs)
        ach.load(prefs)
        dailyBestKey = prefs.getInt("dailyKey", 0)
        dailyBest = prefs.getInt("dailyBest", 0)
        ships.load(prefs)
        gear.load(prefs)
        rewards.load(prefs)
        rewards.refreshDaily()
        runShip = ships.selected
        refreshCoins()
    }

    /** 退出登录：先存档，再回到登录页 */
    private fun logout() {
        saveProgress()
        accounts.setLast(null)
        accountName = null
        loginUser = accounts.names().lastOrNull() ?: ""
        loginPass = ""
        loginMsg = ""
        loginMsgOk = false
        activeField = F_NONE
        textInput?.close()
        state = State.LOGIN
        layoutLogin()
    }

    private fun doLogin() {
        activeField = F_NONE
        textInput?.close()
        val err = accounts.login(loginUser, loginPass)
        if (err != null) {
            loginMsg = err
            loginMsgOk = false
            sound.play(SoundManager.HURT, 0.5f, 1.4f)
            buzz(30)
            invalidate()
            return
        }
        sound.play(SoundManager.BUY, 0.9f)
        loginMsg = ""
        loginMsgOk = false
        loginPass = ""
        enterAccount(loginUser.trim())
    }

    private fun doRegister() {
        activeField = F_NONE
        textInput?.close()
        val err = if (regPass != regPass2) "两次输入的密码不一致"
        else accounts.register(regUser, regPass)
        if (err != null) {
            loginMsg = err
            loginMsgOk = false
            sound.play(SoundManager.HURT, 0.5f, 1.4f)
            buzz(30)
            invalidate()
            return
        }
        val name = regUser.trim()
        sound.play(SoundManager.BUY, 0.9f)
        loginMsg = ""
        loginMsgOk = false
        loginUser = name
        loginPass = ""
        regUser = ""
        regPass = ""
        regPass2 = ""
        enterAccount(name)
        // 自带 legacyMoved 守卫，只会在第一次真正迁移一次
        migrateLegacyProgress()
    }

    /**
     * 老版本把进度直接存在全局文件里。第一次注册账号时把它搬进新账号的存档，
     * 避免升级后最高分 / 金币 / 解锁内容凭空消失。
     */
    private fun migrateLegacyProgress() {
        if (globalPrefs.getBoolean("legacyMoved", false)) return
        val settingKeys = setOf("soundOn", "vibrateOn", "musicOn", "musicVol", "legacyMoved")
        val all = globalPrefs.all
        val hasProgress = all.keys.any { it !in settingKeys }
        if (!hasProgress) {
            globalPrefs.edit().putBoolean("legacyMoved", true).apply()
            return
        }
        val e = prefs.edit()
        for ((k, v) in all) {
            if (k in settingKeys) continue
            when (v) {
                is Int -> e.putInt(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Float -> e.putFloat(k, v)
                is Long -> e.putLong(k, v)
                is String -> e.putString(k, v)
            }
        }
        e.apply()
        globalPrefs.edit().putBoolean("legacyMoved", true).apply()
        loadProgress()
    }

    private fun openField(f: Int) {
        activeField = f
        cursorT = 0f
        val (text, pw) = when (f) {
            F_LG_USER -> loginUser to false
            F_LG_PASS -> loginPass to true
            F_RG_USER -> regUser to false
            F_RG_PASS -> regPass to true
            F_RG_PASS2 -> regPass2 to true
            else -> "" to false
        }
        textInput?.open(text, pw)
        invalidate()
    }

    /** 由 Activity 的 EditText 实时回传 */
    fun onInputChanged(text: String) {
        when (activeField) {
            F_LG_USER -> loginUser = text
            F_LG_PASS -> loginPass = text
            F_RG_USER -> regUser = text
            F_RG_PASS -> regPass = text
            F_RG_PASS2 -> regPass2 = text
        }
        invalidate()
    }

    /**
     * 输入法点击「完成」：自动跳到下一个输入框。
     * 注意：实体键盘的 ENTER、以及部分输入法，按下和抬起会各触发一次
     * onEditorAction，所以这里做 300ms 去抖，否则一次按键会跳两格。
     */
    fun onInputDone() {
        val now = System.currentTimeMillis()
        if (now - lastDoneAt < 300L) return
        lastDoneAt = now
        val f = activeField
        when (f) {
            F_LG_USER -> openField(F_LG_PASS)
            F_LG_PASS -> doLogin()          // 密码框按「完成」= 直接登录
            F_RG_USER -> openField(F_RG_PASS)
            F_RG_PASS -> openField(F_RG_PASS2)
            F_RG_PASS2 -> doRegister()      // 确认密码框按「完成」= 直接注册
            else -> {
                activeField = F_NONE
                textInput?.close()
            }
        }
        invalidate()
    }

    /** 软键盘高度变化：让登录表单自动上移，避免被键盘挡住 */
    fun setKeyboardInset(px: Float) {
        if (abs(px - imeInset) < 1f) return
        imeInset = px
        layoutLogin()
        invalidate()
    }

    // ---------------- 生命周期 ----------------

    fun resume() {
        if (running || released) return
        running = true
        lastNanos = 0L
        accumulator = 0f
        choreographer.postFrameCallback(frameCb)
    }

    fun pause() {
        if (!running) return
        running = false
        choreographer.removeFrameCallback(frameCb)
        if (state == State.PLAYING) {
            state = State.PAUSED
            hudPauseInfo = "当前得分 $score · 最高 $highScore"
        }
        saveProgress()
    }

    fun release() {
        pause()
        released = true
        sp.release()
    }

    fun onBack(): Boolean {
        return when (state) {
            State.PLAYING -> { enterPause(); true }
            // 暂停时再按返回 = 退出到主菜单（暂停界面也有「退出到主菜单」按钮）
            State.PAUSED -> { quitToMenu(); true }
            State.REGISTER -> {
                textInput?.close()
                activeField = F_NONE
                loginMsg = ""
                loginMsgOk = false
                state = State.LOGIN
                layoutLogin()
                true
            }
            // 开箱页先关掉结果弹层，再按才会回上一层
            State.BOX -> {
                if (boxTimer > 0f) {
                    boxTimer = 0f
                    true
                } else {
                    state = State.REWARDS; true
                }
            }
            // 星级页返回奖励中心
            State.STARS -> { state = State.REWARDS; true }
            State.SHOP, State.ACHIEVEMENTS, State.STATS, State.SETTINGS,
            State.SHIP_SELECT, State.GEAR_SHOP, State.REWARDS,
            State.LEVEL_INTRO, State.LEVEL_CLEAR, State.GAME_OVER, State.GAME_COMPLETE -> {
                state = State.READY; refreshMenu(); true
            }
            else -> false
        }
    }

    private fun enterPause() {
        state = State.PAUSED
        hudPauseInfo = "当前得分 $score · 最高 $highScore"
    }

    /** 中途退出当前对局：先存档，再回主菜单 */
    private fun quitToMenu() {
        saveProgress()
        state = State.READY
        refreshMenu()
    }

    private fun saveProgress() {
        // 音频设置始终全局保存
        globalPrefs.edit()
            .putBoolean("soundOn", soundOn)
            .putBoolean("vibrateOn", vibrateOn)
            .putBoolean("musicOn", musicOn)
            .putFloat("musicVol", musicVolume)
            .apply()
        // 未登录时不写账号存档
        if (accountName == null) return
        upgrades.save(prefs)
        ach.save(prefs)
        ships.save(prefs)
        gear.save(prefs)
        rewards.save(prefs)
        prefs.edit()
            .putInt("high", highScore)
            .putInt("dailyKey", dailyBestKey)
            .putInt("dailyBest", dailyBest)
            .apply()
    }

    private fun refreshCoins() {
        hudCoins = upgrades.coins.toString()
    }

    private fun refreshMenu() {
        achToastTimer = 0f
        boxTimer = 0f
        rewards.refreshDaily()
        refreshCoins()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        var top = insets.systemWindowInsetTop.toFloat()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val cut = insets.displayCutout
            if (cut != null) top = max(top, cut.safeInsetTop.toFloat())
        }
        topInset = max(top, dp(8f))
        return super.onApplyWindowInsets(insets)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        vw = w.toFloat()
        vh = h.toFloat()

        applyLevelBackground()

        if (stars.isEmpty()) initStars()
        if (nebs.isEmpty()) initNebula()

        if (px == 0f) {
            px = vw / 2f
            py = vh * 0.78f
            tx = px
            ty = py
        }

        pauseBtnX = vw - dp(34f)
        pauseBtnY = topInset + dp(30f)
        bombBtnX = dp(52f)
        bombBtnY = vh - dp(72f)

        layoutButtons()

        val bw = min(vw * 0.62f, dp(240f))
        val bh = dp(52f)
        val bx = (vw - bw) / 2f
        btnResume.set(bx, vh * 0.46f, bx + bw, vh * 0.46f + bh)
        btnRestart.set(bx, vh * 0.46f + bh + dp(16f), bx + bw, vh * 0.46f + bh * 2f + dp(16f))
        btnQuit.set(bx, vh * 0.46f + bh * 2f + dp(32f), bx + bw, vh * 0.46f + bh * 3f + dp(32f))
        btnAgain.set(bx, vh * 0.68f, bx + bw, vh * 0.68f + bh)
        btnClearNext.set(bx, vh * 0.68f, bx + bw, vh * 0.68f + bh)
        btnBack.set(dp(24f), vh - dp(76f), dp(24f) + dp(120f), vh - dp(76f) + dp(46f))
    }

    private fun layoutButtons() {
        // ---- 主菜单：2 列网格（9 个入口） ----
        val margin = dp(16f)
        val gap = dp(10f)
        val cols = 2
        val rows = (MENU_BUTTONS + cols - 1) / cols
        val top = vh * 0.285f
        val bottom = vh * 0.92f
        val cellW = (vw - margin * 2 - gap * (cols - 1)) / cols
        val cellH = (bottom - top - gap * (rows - 1)) / rows
        for (i in 0 until MENU_BUTTONS) {
            val r = i / cols
            val c = i % cols
            val x = margin + c * (cellW + gap)
            val y = top + r * (cellH + gap)
            menuButtons[i].set(x, y, x + cellW, y + cellH)
        }

        // ---- 设置页 ----
        val sTop = vh * 0.25f
        val sH = dp(56f)
        for (i in 0 until SETTINGS_ROWS) {
            settingsRows[i].set(
                dp(28f), sTop + i * (sH + dp(11f)),
                vw - dp(28f), sTop + i * (sH + dp(11f)) + sH
            )
        }
        val volY = settingsRows[3].centerY()
        btnVolDown.set(vw - dp(28f) - dp(16f) - dp(120f), volY - dp(19f), vw - dp(28f) - dp(16f) - dp(120f) + dp(52f), volY + dp(19f))
        btnVolUp.set(vw - dp(28f) - dp(16f) - dp(52f), volY - dp(19f), vw - dp(28f) - dp(16f), volY + dp(19f))

        // ---- 战机选择 ----
        val shTop = topInset + dp(80f)
        val shH = dp(62f)
        for (i in 0 until SHIP_ROWS) {
            shipRows[i].set(dp(18f), shTop + i * (shH + dp(8f)), vw - dp(18f), shTop + i * (shH + dp(8f)) + shH)
        }

        // ---- 装备商店 ----
        val gTop = topInset + dp(96f)
        val gH = dp(60f)
        for (i in 0 until GEAR_ROWS) {
            gearRows[i].set(dp(18f), gTop + i * (gH + dp(8f)), vw - dp(18f), gTop + i * (gH + dp(8f)) + gH)
        }
        val gy = gearRows[GEAR_ROWS - 1].bottom + dp(14f)
        btnGearPrev.set(dp(24f), gy, dp(24f) + dp(110f), gy + dp(42f))
        btnGearNext.set(vw - dp(24f) - dp(110f), gy, vw - dp(24f), gy + dp(42f))

        // ---- 商店（升级） ----
        val wGap = dp(8f)
        val wbw = (vw - dp(48f) - wGap * (W_COUNT - 1)) / W_COUNT
        val wy = topInset + dp(74f)
        for (i in 0 until W_COUNT) {
            val x = dp(24f) + i * (wbw + wGap)
            weaponBtns[i].set(x, wy, x + wbw, wy + dp(52f))
        }

        val rowY = wy + dp(70f)
        val rowH = dp(58f)
        for (i in 0 until SHOP_ROWS) {
            shopRows[i].set(dp(24f), rowY + i * (rowH + dp(8f)), vw - dp(24f), rowY + i * (rowH + dp(8f)) + rowH)
        }

        val py0 = shopRows[SHOP_ROWS - 1].bottom + dp(16f)
        btnShopPrev.set(dp(24f), py0, dp(24f) + dp(110f), py0 + dp(42f))
        btnShopNext.set(vw - dp(24f) - dp(110f), py0, vw - dp(24f), py0 + dp(42f))
        btnShopGear.set(vw / 2f - dp(80f), py0, vw / 2f + dp(80f), py0 + dp(42f))

        btnAchPrev.set(dp(24f), vh - dp(136f), dp(24f) + dp(110f), vh - dp(136f) + dp(46f))
        btnAchNext.set(vw - dp(24f) - dp(110f), vh - dp(136f), vw - dp(24f), vh - dp(136f) + dp(46f))

        // ---- 奖励中心 ----
        val rTop = topInset + dp(92f)
        val cGap = dp(5f)
        val rcw = (vw - dp(36f) - cGap * (CHECKIN_DAYS - 1)) / CHECKIN_DAYS
        for (i in 0 until CHECKIN_DAYS) {
            val x = dp(18f) + i * (rcw + cGap)
            checkinCells[i].set(x, rTop, x + rcw, rTop + dp(52f))
        }
        btnCheckin.set(dp(18f), rTop + dp(60f), vw - dp(18f), rTop + dp(60f) + dp(46f))

        val tTop = rTop + dp(118f)
        val tH = dp(54f)
        for (i in 0 until TASKS_PER_DAY) {
            taskRows[i].set(dp(18f), tTop + i * (tH + dp(6f)), vw - dp(18f), tTop + i * (tH + dp(6f)) + tH)
        }

        val aTop = tTop + TASKS_PER_DAY * (tH + dp(6f)) + dp(34f)
        val aH = dp(40f)
        val atw = (vw - dp(36f) - dp(6f) * (ACTIVITY_TIERS.size - 1)) / ACTIVITY_TIERS.size
        for (i in ACTIVITY_TIERS.indices) {
            val x = dp(18f) + i * (atw + dp(6f))
            tierBtns[i].set(x, aTop, x + atw, aTop + aH)
        }

        val bTop = aTop + aH + dp(14f)
        val bw2 = (vw - dp(36f) - dp(8f)) / 2f
        btnRewardsBox.set(dp(18f), bTop, dp(18f) + bw2, bTop + dp(48f))
        btnRewardsStars.set(dp(18f) + bw2 + dp(8f), bTop, vw - dp(18f), bTop + dp(48f))

        // ---- 开箱 ----
        val bxTop = topInset + dp(84f)
        val bxH = dp(112f)
        for (i in 0 until BOX_ROWS) {
            boxRows[i].set(dp(18f), bxTop + i * (bxH + dp(12f)), vw - dp(18f), bxTop + i * (bxH + dp(12f)) + bxH)
        }

        // ---- 星级 ----
        layoutStars()

        layoutLogin()
    }

    /** 星级页：上面是 12 关的星星格子，下面是 6 条星级里程碑 */
    private fun layoutStars() {
        if (vh == 0f) return
        val top = topInset + dp(96f)
        val bottom = vh - dp(92f)
        val cols = 4
        val rows = (LEVELS.size + cols - 1) / cols
        val gap = dp(6f)
        val ch = dp(50f)
        val cw = (vw - dp(36f) - gap * (cols - 1)) / cols
        for (i in 0 until LEVELS.size) {
            val r = i / cols
            val c = i % cols
            val x = dp(18f) + c * (cw + gap)
            val y = top + r * (ch + gap)
            starCells[i].set(x, y, x + cw, y + ch)
        }
        val msTop = top + rows * (ch + gap) + dp(34f)
        val n = STAR_MILESTONES.size
        val mgap = dp(4f)
        val avail = max(dp(200f), bottom - msTop)
        val mh = min(dp(52f), (avail - mgap * (n - 1)) / n).coerceAtLeast(dp(34f))
        for (i in 0 until n) {
            val y = msTop + i * (mh + mgap)
            starRows[i].set(dp(18f), y, vw - dp(18f), y + mh)
        }
    }

    /** 登录 / 注册表单布局（软键盘弹出时整体上移，保证不被挡住） */
    private fun layoutLogin() {
        if (vw == 0f) return
        val fw = min(vw - dp(44f), dp(330f))
        val fx = (vw - fw) / 2f
        val gap = dp(10f)
        val isReg = state == State.REGISTER
        val fields = if (isReg) 3 else 2

        val fixed = gap * (fields - 1) + dp(22f) + dp(18f) + dp(36f)
        val availTop = topInset + dp(92f)
        val availBottom = vh - imeInset - dp(16f)
        val avail = max(dp(180f), availBottom - availTop)

        var fh = dp(46f)
        if ((fields + 1) * fh + fixed > avail) {
            fh = max(dp(34f), (avail - fixed) / (fields + 1))
        }
        val formH = (fields + 1) * fh + fixed

        var top = max(availTop, (vh - formH) * 0.42f)
        if (top + formH > availBottom) top = max(availTop, availBottom - formH)

        var y = top
        if (isReg) {
            btnRgUser.set(fx, y, fx + fw, y + fh); y += fh + gap
            btnRgPass.set(fx, y, fx + fw, y + fh); y += fh + gap
            btnRgPass2.set(fx, y, fx + fw, y + fh); y += fh + dp(22f)
            btnRgSubmit.set(fx, y, fx + fw, y + fh); y += fh + dp(18f)
            btnRgToLogin.set(fx, y, fx + fw, y + dp(36f))
        } else {
            btnLgUser.set(fx, y, fx + fw, y + fh); y += fh + gap
            btnLgPass.set(fx, y, fx + fw, y + fh); y += fh + dp(22f)
            btnLgSubmit.set(fx, y, fx + fw, y + fh); y += fh + dp(18f)
            btnLgToReg.set(fx, y, fx + fw, y + dp(36f))
        }
    }

    private fun initStars() {
        stars.clear()
        for (i in 0 until 190) {
            val s = Star()
            respawnStar(s, scatter = true)
            stars.add(s)
        }
    }

    /**
     * 让一颗星回到最远处并换一个方向。
     * @param scatter 首次初始化时把 z 打散铺满整个纵深，避免所有星同时冲出来
     */
    private fun respawnStar(s: Star, scatter: Boolean = false) {
        // 极坐标均匀撒点，保证从消失点向外辐射的方向分布自然
        val ang = Random.nextFloat() * (2f * PI.toFloat())
        val rad = 0.16f + Random.nextFloat() * 0.84f
        s.dx = cos(ang) * rad
        s.dy = sin(ang) * rad * 1.15f          // 竖屏纵向撒得更开，铺满上下

        s.z = if (scatter) zNear + Random.nextFloat() * (zFar - zNear) else zFar

        // 分三层速度，近处（layer 大）更快，天然形成视差
        val layer = Random.nextFloat()
        s.vz = vzMin + layer * (vzMax - vzMin)
        s.size = dp(0.7f) + layer * dp(1.8f)
        s.alpha = (80 + layer * 165).toInt()
        s.phase = Random.nextFloat() * 6.28f
    }

    private fun initNebula() {
        nebs.clear()
        val n1 = Neb()
        n1.x = vw * 0.25f; n1.y = vh * 0.20f
        n1.speed = dp(9f); n1.scale = dp(360f); n1.alpha = 90; n1.variant = 0
        nebs.add(n1)

        val n2 = Neb()
        n2.x = vw * 0.82f; n2.y = vh * 0.62f
        n2.speed = dp(13f); n2.scale = dp(300f); n2.alpha = 70; n2.variant = 1
        nebs.add(n2)

        val n3 = Neb()
        n3.x = vw * 0.45f; n3.y = vh * 1.10f
        n3.speed = dp(7f); n3.scale = dp(420f); n3.alpha = 60; n3.variant = 2
        nebs.add(n3)
    }

    private fun applyLevelBackground() {
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, max(1f, vh),
            intArrayOf(level.bgTop, level.bgMid, level.bgBot),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        // 景深雾化的目标色跟着关卡背景走，远处物体才能"融进"背景里
        if (fogColor != level.bgMid) {
            fogColor = level.bgMid
            buildFogPaints()
        }
        if (level.bgRes != bgResId || bgBitmap == null) {
            bgBitmap?.recycle()
            bgBitmap = decodeBackground(level.bgRes)
            bgResId = level.bgRes
        }
    }

    /** 解码背景图，并按屏幕大小做降采样，避免占用过多内存。 */
    private fun decodeBackground(resId: Int): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inScaled = false }
        BitmapFactory.decodeResource(resources, resId, opts)
    } catch (e: Exception) {
        null
    }

    // ---------------- 主循环 ----------------

    private fun doFrame(frameTimeNanos: Long) {
        if (!running || released) return
        if (lastNanos == 0L) lastNanos = frameTimeNanos
        var dt = (frameTimeNanos - lastNanos) / 1_000_000_000f
        lastNanos = frameTimeNanos
        if (dt < 0f) dt = 0f
        if (dt > 0.10f) dt = 0.10f

        if (state == State.PLAYING) {
            if (hitStop > 0f) {
                hitStop -= dt
                if (hitStop < 0f) hitStop = 0f
                driftStars(dt, 1f)
            } else {
                val scaled = if (slowTimer > 0f) dt * 0.55f else dt
                accumulator += scaled
                var guard = 0
                while (accumulator >= FIXED && guard < MAX_STEPS) {
                    step(FIXED)
                    accumulator -= FIXED
                    guard++
                }
                if (accumulator > FIXED * MAX_STEPS) accumulator = 0f
            }
        } else {
            menuTime += dt
            cursorT += dt
            driftStars(dt, 0.42f)
            stepParticles(dt)
            stepRings(dt)
            stepTexts(dt)
            if (shake > 0f) shake = max(0f, shake - dt * 2.5f)
            if (hitFlash > 0f) hitFlash = max(0f, hitFlash - dt * 3f)
            if (overCooldown > 0f) overCooldown -= dt
            if (introTimer > 0f) introTimer -= dt
            if (clearTimer > 0f) clearTimer -= dt
            if (achToastTimer > 0f) {
                achToastTimer -= dt
                if (achToastTimer <= 0f) popAchToast()
            }
            if (boxTimer > 0f) boxTimer = max(0f, boxTimer - dt)
        }

        syncMusic()
        invalidate()
        choreographer.postFrameCallback(frameCb)
    }

    private fun driftStars(dt: Float, factor: Float) {
        for (s in stars) {
            s.z -= s.vz * dt * factor            // z 减小 = 朝镜头飞过来
            s.phase += dt * 2.4f
            if (s.z <= zNear) respawnStar(s)
        }
        for (n in nebs) {
            n.y += n.speed * dt * factor
            if (n.y - n.scale * 0.5f > vh) {
                n.y = -n.scale * 0.5f
                n.x = Random.nextFloat() * vw
            }
        }
    }

    private fun step(dt: Float) {
        elapsed += dt
        driftStars(dt, 1f)

        // 由横向速度推横滚角：往右压右坡度、往左压左坡度，松手自动回正
        val vxNow = if (dt > 0.0001f) (px - prevPx) / dt else 0f
        prevPx = px
        val rollTarget = (vxNow * 0.0022f).coerceIn(-0.5f, 0.5f)
        playerRoll += (rollTarget - playerRoll) * min(1f, dt * 9f)

        if (shake > 0f) shake = max(0f, shake - dt * 2.5f)
        if (hitFlash > 0f) hitFlash = max(0f, hitFlash - dt * 3f)
        if (slowTimer > 0f) slowTimer -= dt
        if (magnetTimer > 0f) magnetTimer -= dt

        // 装备：纳米再生 —— 每 25 秒回 1 血
        if (gear.hasEquipment(EQ_REGEN)) {
            regenTimer -= dt
            if (regenTimer <= 0f) {
                regenTimer = 25f
                if (lives < 6) {
                    lives++
                    pushText(px, py - dp(46f), "再生 +1", 0xFF66BB6A.toInt(), dp(15f), 0.9f)
                }
            }
        }
        if (comboTimer > 0f) {
            comboTimer -= dt
            if (comboTimer <= 0f) {
                combo = 0
                hudCombo = ""
            }
        }

        val moveK = shipSpec(runShip).speedMul * (1f + upgrades.levelOf(U_SPEED) * 0.08f)
        val lerp = min(1f, dt * 18f * moveK)
        px += (tx - px) * lerp
        py += (ty - py) * lerp
        val minY = topInset + dp(30f)
        val maxY = max(minY + 1f, vh - dp(24f))
        px = px.coerceIn(playerR, max(playerR + 1f, vw - playerR))
        py = py.coerceIn(minY, maxY)

        if (invincible > 0f) invincible -= dt
        if (shield > 0f) shield -= dt

        trailTimer -= dt
        if (trailTimer <= 0f) {
            trailTimer = 0.025f
            spawnTrail()
        }

        stepLevel(dt)

        fireTimer -= dt
        if (fireTimer <= 0f) {
            fireTimer = fireInterval()
            shoot()
        }
        if (weaponLevel >= 4 && upgrades.selectedWeapon == W_BASIC) {
            missileTimer -= dt
            if (missileTimer <= 0f) {
                missileTimer = 0.55f
                fireMissile(px - dp(18f), py + playerR * 0.6f)
                fireMissile(px + dp(18f), py + playerR * 0.6f)
            }
        }

        updateBullets(dt)
        updateEnemies(dt)
        updatePowerups(dt)
        updateWingmen(dt)
        collide()
        stepParticles(dt)
        stepRings(dt)
        stepTexts(dt)
    }

    // ---------------- 关卡流程 ----------------

    private fun stepLevel(dt: Float) {
        if (isDaily) {
            spawnTimer -= dt
            if (spawnTimer <= 0f) {
                spawnEnemy()
                spawnTimer = nextSpawnInterval()
            }
            return
        }

        if (isEndless) {
            // 无尽：持续刷怪，每 45 秒来一个 BOSS
            spawnTimer -= dt
            if (spawnTimer <= 0f) {
                spawnEnemy()
                spawnTimer = nextSpawnInterval()
            }
            if (!bossAlive && bossRef == null && elapsed >= endlessNextBoss) {
                endlessNextBoss += 45f
                spawnBoss()
            }
            return
        }

        if (isBossRush) {
            // BOSS 连战：只打 BOSS，推进由 killEnemy -> bossRushAdvance() 负责
            return
        }

        if (!swarmDone) {
            swarmTimer -= dt
            spawnTimer -= dt
            if (spawnTimer <= 0f) {
                spawnEnemy()
                spawnTimer = nextSpawnInterval()
            }
            if (swarmTimer <= 0f) {
                swarmDone = true
                for (i in enemies.indices.reversed()) {
                    val e = enemies[i]
                    explode(e.x, e.y, e.r, 12)
                    enemies.fastRemove(i)
                    enemyPool.recycle(e)
                }
                spawnBoss()
            }
        }
    }

    private fun startMode(m: Int) {
        mode = m
        isDaily = false
        daily = null
        isEndless = m == M_ENDLESS
        isBossRush = m == M_BOSSRUSH
        runShip = ships.selected
        when (m) {
            M_ENDLESS -> {
                level = LEVELS[3]
                applyLevelBackground()
                endlessNextBoss = 45f
                enterLevelIntro()
            }
            M_BOSSRUSH -> {
                bossRushIndex = 0
                level = LEVELS[6]
                applyLevelBackground()
                enterLevelIntro()
            }
            else -> {
                levelIndex = 0
                enterLevelIntro()
            }
        }
    }

    private fun startDaily() {
        isDaily = true
        val d = dailyChallenge()
        daily = d
        if (dailyBestKey != d.dateKey) {
            dailyBestKey = d.dateKey
            dailyBest = 0
        }
        levelIndex = 0
        level = LEVELS[0]
        applyLevelBackground()
        enterLevelIntro()
    }

    private fun enterLevelIntro() {
        if (!isDaily && !isEndless && !isBossRush) {
            level = levelAt(levelIndex)
            applyLevelBackground()
        }
        resetRunStats()
        state = State.LEVEL_INTRO
        introTimer = 2.2f
        sound.play(SoundManager.WARN, 0.5f, 1.3f)
    }

    /** 根据当前状态切换背景音乐（同曲不重播） */
    private fun syncMusic() {
        val track = when {
            state == State.GAME_OVER -> MusicManager.GAMEOVER
            state == State.GAME_COMPLETE || state == State.LEVEL_CLEAR -> MusicManager.VICTORY
            state == State.PLAYING || state == State.PAUSED ||
                    state == State.LEVEL_INTRO -> if (bossAlive) MusicManager.BOSS else MusicManager.BATTLE
            else -> MusicManager.MENU
        }
        music.play(track)
    }

    private fun resetRunStats() {
        val ship = shipSpec(runShip)
        score = 0
        runCoins = 0
        lives = max(1, 3 + upgrades.levelOf(U_ARMOR) + ship.hpBonus)
        if (isDaily) lives = daily?.startLives ?: 3
        if (isBossRush) lives = max(lives, 5)
        weaponLevel = min(5, 1 + upgrades.levelOf(U_FIREPOWER) + ship.startPower)
        bombs = 2 + upgrades.levelOf(U_AMMO)
        shield = max(upgrades.levelOf(U_SHIELD) * 2f, ship.startShield)
        invincible = 2.0f
        fireTimer = 0f
        missileTimer = 0.4f
        trailTimer = 0f
        magnetTimer = 0f
        slowTimer = 0f
        reviveUsed = false
        laserTimer = 0f
        laserSndTimer = 0f
        wingTimer = 0.5f
        regenTimer = 25f

        elapsed = 0f
        kills = 0
        combo = 0
        comboTimer = 0f
        spawnTimer = 0.7f
        swarmTimer = if (isDaily || isBossRush) 999999f else level.swarmSeconds
        swarmDone = isDaily || isBossRush
        bossAlive = false
        bossPhase = 1
        bossSpin = 0f
        shake = 0f
        hitFlash = 0f
        hitStop = 0f
        overCooldown = 0f
        newRecord = false
        tookDamageThisLevel = false
        levelStartScore = 0
        levelScore = 0
        runStars = 0
        starJustImproved = false
        hudScore = "0"
        hudCombo = ""
        hudBomb = "x$bombs"
        bossRef = null
        refreshCoins()
        updateHudLevel()

        clearAllEntities()

        px = vw / 2f
        py = vh * 0.78f
        tx = px
        ty = py
        prevPx = px
        playerRoll = 0f
        ach.add(S_GAMES, 1)
    }

    private fun clearAllEntities() {
        for (b in bullets) bulletPool.recycle(b)
        bullets.clear()
        for (p in particles) particlePool.recycle(p)
        particles.clear()
        for (r in rings) ringPool.recycle(r)
        rings.clear()
        for (t in texts) textPool.recycle(t)
        texts.clear()
        for (e in enemies) enemyPool.recycle(e)
        enemies.clear()
        for (p in powerups) powerupPool.recycle(p)
        powerups.clear()
    }

    private fun updateHudLevel() {
        hudLevel = when {
            isDaily -> "每日挑战 · ${daily?.title ?: ""}"
            isEndless -> "无尽模式"
            isBossRush -> "BOSS 连战 ${bossRushIndex + 1}/${BOSS_SPECS.size}"
            else -> level.name
        }
    }

    private fun beginPlay() {
        state = State.PLAYING
        sound.play(SoundManager.CLICK, 0.7f)
        if (isBossRush && bossRef == null) spawnBoss()
    }

    private fun levelClear() {
        bossAlive = false
        bossRef = null
        state = State.LEVEL_CLEAR
        clearTimer = 0.6f
        levelScore = score - levelStartScore

        val base = if (isDaily) 60 else 80 + levelIndex * 40
        val perfect = if (!tookDamageThisLevel) (base * 0.6f).toInt() else 0
        val total = ((base + perfect) * coinMul()).toInt()
        upgrades.coins += total
        runCoins += total
        refreshCoins()

        ach.set(S_LEVELS, max(ach.stats[S_LEVELS], levelIndex + 1))
        if (!tookDamageThisLevel) ach.add(S_NOHIT, 1)
        ach.max(S_BEST_TIME, elapsed.toInt())

        // ---- 关卡三星（只有战役模式评级） ----
        runStars = 0
        if (!isDaily && !isEndless && !isBossRush) {
            runStars = evaluateStars(!tookDamageThisLevel, levelScore, levelIndex)
            if (rewards.stars.record(levelIndex, runStars)) {
                starJustImproved = true
            }
        }

        // ---- 每日任务推进 ----
        rewards.push(T_LEVELS, 1)
        if (!tookDamageThisLevel) rewards.push(T_NOHIT, 1)
        rewards.pushBest(T_SCORE, levelScore)
        rewards.pushBest(T_COINS, runCoins)
        rewards.push(T_TIME, elapsed.toInt())

        if (isDaily && score > dailyBest) dailyBest = score
        if (score > highScore) {
            highScore = score
            newRecord = true
            hudHigh = "最高 $highScore"
            hudHighBig = "历史最高分 $highScore"
        }
        saveProgress()

        hudClearTitle = if (isDaily) "挑战结束" else "第 ${levelIndex + 1} 关 完成"
        hudClearInfo = "本关得分 $levelScore · 获得 $total 金币" +
                if (perfect > 0) " · 无伤 +$perfect" else ""
        pushAchievementToasts()
        sound.play(SoundManager.LEVEL_CLEAR, 0.9f)
        shake = 0.7f
    }

    private fun nextLevel() {
        if (isDaily) {
            state = State.GAME_COMPLETE
            endRunTasks()
            saveProgress()
            sound.play(SoundManager.GAME_OVER, 0.8f)
            return
        }
        levelIndex++
        if (levelIndex >= LEVELS.size) {
            state = State.GAME_COMPLETE
            endRunTasks()
            saveProgress()
            sound.play(SoundManager.LEVEL_CLEAR, 1f)
            shake = 1f
        } else {
            levelStartScore = score
            enterLevelIntro()
        }
    }

    /** 一局真正结束时（阵亡 / 全部通关）推进每日任务 */
    private fun endRunTasks() {
        rewards.push(T_GAMES, 1)
        rewards.pushBest(T_SCORE, score)
        rewards.pushBest(T_COINS, runCoins)
        rewards.push(T_TIME, elapsed.toInt())
    }

    private fun coinMul(): Float {
        var base = 1f + upgrades.levelOf(U_COIN) * 0.25f
        if (gear.hasEquipment(EQ_GREED)) base *= 1.5f
        return base * (daily?.coinMul ?: 1f)
    }

    private fun fireInterval(): Float {
        val base = when (upgrades.selectedWeapon) {
            W_LASER -> 0.10f
            W_SPREAD -> max(0.10f, 0.20f - weaponLevel * 0.018f)
            W_RING -> max(0.16f, 0.30f - weaponLevel * 0.024f)
            W_HOMING -> max(0.12f, 0.26f - weaponLevel * 0.020f)
            else -> max(0.062f, 0.125f - weaponLevel * 0.012f)
        }
        var k = shipSpec(runShip).fireMul
        if (gear.hasEquipment(EQ_OVERDRIVE) && combo >= 10) k *= 0.75f
        return base * k
    }

    private fun nextSpawnInterval(): Float {
        val base = max(0.20f, 0.92f - elapsed * 0.014f)
        val lv = if (isDaily) 1f else level.spawnScale
        val d = daily?.enemyRate ?: 1f
        return base * lv / d * (0.7f + Random.nextFloat() * 0.6f)
    }

    // ---------------- 子弹 ----------------

    private fun fireBullet(x: Float, y: Float, vx: Float, vy: Float, r: Float, power: Int, pierce: Boolean = false) {
        val b = bulletPool.obtain()
        b.x = x; b.y = y; b.vx = vx; b.vy = vy
        b.r = r; b.friendly = true; b.power = power
        b.missile = false; b.homing = false; b.pierce = pierce; b.color = 0
        bullets.add(b)
    }

    private fun fireMissile(x: Float, y: Float) {
        val b = bulletPool.obtain()
        b.x = x; b.y = y; b.vx = 0f; b.vy = -dp(340f)
        b.r = dp(7f); b.friendly = true; b.power = 4
        b.missile = true; b.homing = true; b.pierce = false; b.color = 0
        bullets.add(b)
        sound.play(SoundManager.SHOOT, 0.45f, 0.7f)
    }

    private fun shoot() {
        when (upgrades.selectedWeapon) {
            W_LASER -> shootLaser()
            W_SPREAD -> shootSpread()
            W_RING -> shootRing()
            W_HOMING -> shootHoming()
            else -> shootBasic()
        }
    }

    /** 追踪弹：向最近的敌机发射自动追踪弹 */
    private fun shootHoming() {
        val noseY = py - playerR * 1.4f
        val n = 1 + weaponLevel / 2
        for (k in 0 until n) {
            val off = (k - (n - 1) / 2f) * dp(12f)
            val b = bulletPool.obtain()
            b.x = px + off
            b.y = noseY
            b.vx = off * 2.2f
            b.vy = -dp(520f)
            b.r = dp(6f)
            b.friendly = true
            b.power = 1 + weaponLevel / 3
            b.missile = false
            b.homing = true
            b.pierce = false
            b.color = 0xFF7E57C2.toInt()
            bullets.add(b)
        }
        sound.play(SoundManager.SHOOT, 0.26f, 1.15f)
    }

    private fun shootBasic() {
        val spd = -dp(790f)
        val noseY = py - playerR * 1.5f
        when (weaponLevel) {
            1 -> fireBullet(px, noseY, 0f, spd, dp(5f), 1)
            2 -> {
                fireBullet(px - dp(9f), noseY + dp(4f), 0f, spd, dp(5f), 1)
                fireBullet(px + dp(9f), noseY + dp(4f), 0f, spd, dp(5f), 1)
            }
            3 -> {
                fireBullet(px, noseY - dp(4f), 0f, spd * 1.06f, dp(6f), 2)
                fireBullet(px - dp(12f), noseY + dp(6f), -dp(150f), spd, dp(5f), 1)
                fireBullet(px + dp(12f), noseY + dp(6f), dp(150f), spd, dp(5f), 1)
            }
            4 -> {
                fireBullet(px - dp(6f), noseY - dp(4f), 0f, spd * 1.06f, dp(6f), 2)
                fireBullet(px + dp(6f), noseY - dp(4f), 0f, spd * 1.06f, dp(6f), 2)
                fireBullet(px - dp(14f), noseY + dp(6f), -dp(185f), spd, dp(5f), 1)
                fireBullet(px + dp(14f), noseY + dp(6f), dp(185f), spd, dp(5f), 1)
            }
            else -> {
                fireBullet(px, noseY - dp(6f), 0f, spd * 1.08f, dp(7f), 3)
                fireBullet(px - dp(10f), noseY, -dp(120f), spd, dp(6f), 2)
                fireBullet(px + dp(10f), noseY, dp(120f), spd, dp(6f), 2)
            }
        }
        sound.play(SoundManager.SHOOT, 0.30f, 0.95f + Random.nextFloat() * 0.1f)
    }

    private fun shootLaser() {
        val half = dp(9f) + weaponLevel * dp(2.2f)
        val dmg = 1 + weaponLevel / 2
        laserTimer -= FIXED
        if (laserTimer <= 0f) {
            laserTimer = 0.07f
            for (i in enemies.indices.reversed()) {
                val e = enemies[i]
                if (abs(e.x - px) < half + e.r && e.y < py + e.r) {
                    damageEnemy(e, i, dmg, e.x, e.y)
                }
            }
        }
        laserSndTimer -= FIXED
        if (laserSndTimer <= 0f) {
            laserSndTimer = 0.22f
            sound.play(SoundManager.LASER, 0.22f, 1f)
        }
    }

    private fun shootSpread() {
        val n = 3 + weaponLevel
        val spd = dp(620f)
        val noseY = py - playerR * 1.2f
        val half = 0.62f
        for (i in 0 until n) {
            val t = if (n == 1) 0f else i.toFloat() / (n - 1) * 2f - 1f
            val ang = t * half
            fireBullet(
                px + sin(ang) * dp(10f), noseY,
                sin(ang) * spd, -cos(ang) * spd,
                dp(5f), 1 + weaponLevel / 3
            )
        }
        sound.play(SoundManager.SHOOT, 0.26f, 1.05f)
    }

    private fun shootRing() {
        val n = 6 + weaponLevel * 2
        val spd = dp(520f)
        for (i in 0 until n) {
            val ang = (i.toFloat() / n) * 6.2831855f
            fireBullet(
                px + cos(ang) * dp(8f), py + sin(ang) * dp(8f),
                cos(ang) * spd, sin(ang) * spd,
                dp(5f), 1 + weaponLevel / 3
            )
        }
        sound.play(SoundManager.SHOOT, 0.24f, 0.9f)
    }

    private fun updateBullets(dt: Float) {
        val missileSpeed = dp(560f)
        for (i in bullets.indices.reversed()) {
            val b = bullets[i]
            if (b.homing) {
                val t = nearestEnemy(b.x, b.y)
                if (t != null) {
                    val dx = t.x - b.x
                    val dy = t.y - b.y
                    val len = max(1f, hypot(dx, dy))
                    val turn = min(1f, dt * 5.5f)
                    b.vx += (dx / len * missileSpeed - b.vx) * turn
                    b.vy += (dy / len * missileSpeed - b.vy) * turn
                }
            }
            b.x += b.vx * dt
            b.y += b.vy * dt
            if (b.y < -dp(60f) || b.y > vh + dp(60f) || b.x < -dp(60f) || b.x > vw + dp(60f)) {
                bullets.fastRemove(i)
                bulletPool.recycle(b)
            }
        }
    }

    private fun nearestEnemy(x: Float, y: Float): Enemy? {
        var best: Enemy? = null
        var bestD = Float.MAX_VALUE
        for (e in enemies) {
            val d = (e.x - x) * (e.x - x) + (e.y - y) * (e.y - y)
            if (d < bestD) {
                bestD = d
                best = e
            }
        }
        return best
    }

    // ---------------- 敌机 ----------------

    private fun pickEnemyKind(): Int {
        val w = level.weights
        var total = 0
        for (v in w) total += v
        if (total <= 0) return K_SCOUT
        var r = Random.nextInt(total)
        for (i in w.indices) {
            r -= w[i]
            if (r < 0) return i
        }
        return K_SCOUT
    }

    private fun pickDailyKind(): Int {
        val pool = intArrayOf(
            K_SCOUT, K_ZIGZAG, K_TANK, K_DIVER, K_ELITE,
            K_ORBITER, K_SPLITTER, K_SHIELDED, K_SNIPER, K_KAMIKAZE,
            K_BOMBER, K_WARP
        )
        return pool[Random.nextInt(pool.size)]
    }

    private fun spawnEnemy() {
        val kind = if (isDaily) pickDailyKind() else pickEnemyKind()
        val spec = enemySpec(kind)
        val e = enemyPool.obtain()
        e.reset()
        e.kind = kind
        e.r = dp(spec.radiusDp)

        val hpMul = when {
            isDaily -> (1.0f + elapsed * 0.012f) * (daily?.enemyHp ?: 1f)
            isEndless -> level.hpScale * (1f + elapsed * 0.010f)
            isBossRush -> level.hpScale
            else -> level.hpScale
        }
        e.hp = max(1, (spec.hpMul * hpMul).toInt())
        e.score = spec.score
        e.coinDrop = spec.coin

        val margin = e.r + dp(8f)

        when (kind) {
            K_SCOUT -> {
                e.x = randRange(margin, max(margin + 1f, vw - margin))
                e.y = -dp(30f)
                e.vx = (Random.nextFloat() - 0.5f) * dp(70f)
                e.vy = dp(150f) + Random.nextFloat() * dp(70f)
            }
            K_ZIGZAG -> {
                e.baseX = randRange(dp(60f), max(dp(61f), vw - dp(60f)))
                e.x = e.baseX
                e.y = -dp(30f)
                e.amp = dp(40f) + Random.nextFloat() * dp(45f)
                e.vy = dp(95f) + Random.nextFloat() * dp(35f)
                e.phase = Random.nextFloat() * 6.28f
            }
            K_TANK -> {
                e.x = randRange(margin, max(margin + 1f, vw - margin))
                e.y = -dp(40f)
                e.vx = (Random.nextFloat() - 0.5f) * dp(45f)
                e.vy = dp(62f) + Random.nextFloat() * dp(26f)
                e.fireTimer = 0.8f
            }
            K_DIVER -> {
                e.x = randRange(margin, max(margin + 1f, vw - margin))
                e.y = -dp(30f)
                e.vx = (Random.nextFloat() - 0.5f) * dp(50f)
                e.vy = dp(120f)
            }
            K_ELITE -> {
                e.x = randRange(dp(60f), max(dp(61f), vw - dp(60f)))
                e.y = -dp(40f)
                e.vy = dp(90f)
                e.targetY = vh * (0.14f + Random.nextFloat() * 0.10f)
                e.fireTimer = 1.0f
            }
            K_ORBITER -> {
                val orbitR = dp(42f) + Random.nextFloat() * dp(40f)
                e.orbitR = orbitR
                e.orbitCx = randRange(orbitR + margin, max(orbitR + margin + 1f, vw - orbitR - margin))
                e.orbitCy = -dp(10f)
                e.orbitSpd = 2.0f + Random.nextFloat() * 1.4f
                e.vy = dp(40f)
                e.phase = Random.nextFloat() * 6.28f
                e.x = e.orbitCx
                e.y = e.orbitCy
            }
            K_SPLITTER -> {
                e.x = randRange(margin, max(margin + 1f, vw - margin))
                e.y = -dp(40f)
                e.vy = dp(95f)
                e.targetY = vh * (0.16f + Random.nextFloat() * 0.16f)
                e.vx = if (Random.nextBoolean()) dp(70f) else -dp(70f)
            }
            K_SHIELDED -> {
                e.x = randRange(margin, max(margin + 1f, vw - margin))
                e.y = -dp(40f)
                e.vy = dp(56f)
                e.shieldMax = e.hp * 2
                e.shieldHp = e.shieldMax
                e.fireTimer = 1.2f
            }
            K_SNIPER -> {
                e.x = randRange(dp(50f), max(dp(51f), vw - dp(50f)))
                e.y = -dp(40f)
                e.vy = dp(110f)
                e.targetY = vh * (0.12f + Random.nextFloat() * 0.12f)
                e.vx = if (Random.nextBoolean()) dp(55f) else -dp(55f)
                e.chargeTimer = 1.2f
            }
            K_KAMIKAZE -> {
                e.x = randRange(margin, max(margin + 1f, vw - margin))
                e.y = -dp(30f)
                e.vx = (Random.nextFloat() - 0.5f) * dp(40f)
                e.vy = dp(170f)
            }
            K_BOMBER -> {
                e.x = randRange(margin, max(margin + 1f, vw - margin))
                e.y = -dp(44f)
                e.vy = dp(52f)
                e.vx = (Random.nextFloat() - 0.5f) * dp(34f)
                e.fireTimer = 1.6f
            }
            K_WARP -> {
                e.x = randRange(margin, max(margin + 1f, vw - margin))
                e.y = -dp(36f)
                e.vy = dp(88f)
                e.warpTimer = 1.4f
                e.fireTimer = 1.2f
            }
        }
        e.maxHp = e.hp
        enemies.add(e)
    }

    private fun spawnBoss() {
        val hp: Int
        val bt: Int
        if (isDaily) {
            bt = Random.nextInt(BOSS_SPECS.size)
            hp = 220 + (elapsed / 20f).toInt() * 90
        } else if (isBossRush) {
            bt = bossRushIndex % BOSS_SPECS.size
            hp = 460 + bossRushIndex * 420
        } else if (isEndless) {
            bt = (elapsed / 45f).toInt() % BOSS_SPECS.size
            hp = 320 + (elapsed / 45f).toInt() * 380
        } else {
            bt = level.bossType
            hp = level.bossHp
        }
        val e = enemyPool.obtain()
        e.reset()
        e.kind = K_BOSS
        e.bossType = bt
        e.r = dp(52f)
        e.hp = hp
        e.maxHp = hp
        e.score = 600
        e.coinDrop = 60
        e.x = vw / 2f
        e.y = -dp(90f)
        e.vx = dp(120f)
        e.vy = dp(95f)
        e.targetY = vh * 0.20f
        e.fireTimer = 1.2f
        enemies.add(e)
        bossAlive = true
        bossRef = e
        bossPhase = 1
        bossSpin = 0f
        val bname = if (isDaily || isBossRush || isEndless) bossSpec(bt).name else level.bossName
        pushText(vw / 2f, vh * 0.30f, "警告 · $bname", 0xFFFF5252.toInt(), dp(24f), 1.9f)
        shake = 1f
        hitStop = 0.12f
        buzz(110)
        sound.play(SoundManager.WARN)
    }

    private fun fireCooldown(kind: Int): Float = when (kind) {
        K_TANK -> 1.6f
        K_ELITE -> 1.3f
        K_SHIELDED -> 1.5f
        K_SPLITTER -> 1.8f
        K_BOMBER -> 2.1f
        K_WARP -> 1.4f
        K_BOSS -> when (bossPhase) {
            1 -> 1.35f
            2 -> 1.0f
            else -> 0.72f
        }
        else -> 1.5f
    }

    private fun updateEnemies(dt: Float) {
        val slowK = if (slowTimer > 0f) 0.55f else 1f
        for (i in enemies.indices.reversed()) {
            val e = enemies[i]
            if (e.flash > 0f) e.flash = max(0f, e.flash - dt * 6f)

            when (e.kind) {
                K_SCOUT -> {
                    e.y += e.vy * dt * slowK
                    e.x += e.vx * dt * slowK
                }
                K_ZIGZAG -> {
                    e.phase += dt * 2.4f
                    e.y += e.vy * dt * slowK
                    e.x = e.baseX + sin(e.phase) * e.amp
                }
                K_TANK -> {
                    e.y += e.vy * dt * slowK
                    e.x += e.vx * dt * slowK
                }
                K_DIVER -> {
                    if (e.entering && e.y > vh * 0.18f) e.entering = false
                    if (!e.entering) {
                        val dx = px - e.x
                        val dy = py - e.y
                        val len = max(1f, hypot(dx, dy))
                        val spd = dp(430f)
                        val k = min(1f, dt * 1.7f)
                        e.vx += (dx / len * spd - e.vx) * k
                        e.vy += (dy / len * spd - e.vy) * k
                    }
                    e.x += e.vx * dt * slowK
                    e.y += e.vy * dt * slowK
                }
                K_ELITE -> {
                    if (e.entering) {
                        e.y += e.vy * dt * slowK
                        if (e.y >= e.targetY) {
                            e.entering = false
                            e.vy = 0f
                            e.vx = dp(105f)
                        }
                    } else {
                        e.x += e.vx * dt
                        if (e.x < e.r) {
                            e.x = e.r
                            e.vx = abs(e.vx)
                        }
                        if (e.x > vw - e.r) {
                            e.x = vw - e.r
                            e.vx = -abs(e.vx)
                        }
                    }
                }
                K_ORBITER -> {
                    e.phase += dt * e.orbitSpd * slowK
                    e.orbitCy += e.vy * dt * slowK
                    e.x = e.orbitCx + cos(e.phase) * e.orbitR
                    e.y = e.orbitCy + sin(e.phase) * e.orbitR
                }
                K_SPLITTER -> {
                    if (e.entering) {
                        e.y += e.vy * dt * slowK
                        if (e.y >= e.targetY) {
                            e.entering = false
                            e.vy = 0f
                        }
                    } else {
                        e.x += e.vx * dt * slowK
                        if (e.x < e.r) {
                            e.x = e.r
                            e.vx = abs(e.vx)
                        }
                        if (e.x > vw - e.r) {
                            e.x = vw - e.r
                            e.vx = -abs(e.vx)
                        }
                    }
                }
                K_SHIELDED -> {
                    e.y += e.vy * dt * slowK
                    e.x += e.vx * dt * slowK
                }
                K_SNIPER -> {
                    if (e.entering) {
                        e.y += e.vy * dt * slowK
                        if (e.y >= e.targetY) {
                            e.entering = false
                            e.vy = 0f
                        }
                    } else {
                        e.x += e.vx * dt
                        if (e.x < e.r) {
                            e.x = e.r
                            e.vx = abs(e.vx)
                        }
                        if (e.x > vw - e.r) {
                            e.x = vw - e.r
                            e.vx = -abs(e.vx)
                        }
                        e.chargeTimer -= dt * slowK
                        if (e.chargeTimer <= 0.5f) e.charging = true
                        if (e.chargeTimer <= 0f) {
                            e.chargeTimer = 2.6f
                            e.charging = false
                            sniperShot(e)
                        }
                    }
                }
                K_KAMIKAZE -> {
                    if (e.entering && e.y > vh * 0.15f) e.entering = false
                    if (!e.entering) {
                        val dx = px - e.x
                        val dy = py - e.y
                        val len = max(1f, hypot(dx, dy))
                        val spd = dp(520f)
                        val k = min(1f, dt * 2.6f)
                        e.vx += (dx / len * spd - e.vx) * k
                        e.vy += (dy / len * spd - e.vy) * k
                    }
                    e.x += e.vx * dt * slowK
                    e.y += e.vy * dt * slowK
                }
                K_BOMBER -> {
                    e.y += e.vy * dt * slowK
                    e.x += e.vx * dt * slowK
                }
                K_WARP -> {
                    e.y += e.vy * dt * slowK
                    e.warpTimer -= dt * slowK
                    if (e.warpTimer <= 0f) {
                        e.warpTimer = 1.5f + Random.nextFloat() * 1.0f
                        burst(e.x, e.y, 0xFF7E57C2.toInt())
                        e.x = randRange(e.r + dp(10f), max(e.r + dp(11f), vw - e.r - dp(10f)))
                        e.y = min(e.y + dp(36f), vh * 0.62f)
                        burst(e.x, e.y, 0xFFB39DDB.toInt())
                    }
                }
                K_BOSS -> updateBoss(e, dt)
            }

            if (bounces(e.kind)) {
                if (e.x < e.r) {
                    e.x = e.r
                    e.vx = -e.vx
                }
                if (e.x > vw - e.r) {
                    e.x = vw - e.r
                    e.vx = -e.vx
                }
            }

            if (enemySpec(e.kind).fires) {
                e.fireTimer -= dt
                if (e.fireTimer <= 0f && e.y > dp(8f) && e.y < vh * 0.82f) {
                    e.fireTimer = fireCooldown(e.kind)
                    enemyFire(e)
                }
            }

            if (e.kind != K_BOSS && e.y > vh + e.r * 2f) {
                enemies.fastRemove(i)
                enemyPool.recycle(e)
            } else if (e.kind == K_BOSS && e.y > vh + e.r * 3f) {
                bossAlive = false
                bossRef = null
                enemies.fastRemove(i)
                enemyPool.recycle(e)
            }
        }
    }

    private fun bounces(kind: Int): Boolean = when (kind) {
        K_ELITE, K_ORBITER, K_SNIPER, K_BOSS -> false
        else -> true
    }

    private fun updateBoss(e: Enemy, dt: Float) {
        if (e.entering) {
            e.y += e.vy * dt
            if (e.y >= e.targetY) {
                e.entering = false
                e.vy = 0f
                e.vx = dp(120f)
            }
        } else {
            if (e.bossType == BOSS_VOID) {
                e.phase += dt * 1.2f
                e.y = e.targetY + sin(e.phase) * dp(26f)
            }
            e.x += e.vx * dt
            val lo = e.r + dp(6f)
            val hi = vw - e.r - dp(6f)
            if (e.x < lo) {
                e.x = lo
                e.vx = abs(e.vx)
            }
            if (e.x > hi) {
                e.x = hi
                e.vx = -abs(e.vx)
            }
        }
        val frac = e.hp.toFloat() / e.maxHp
        bossPhase = if (frac > 0.66f) 1 else if (frac > 0.33f) 2 else 3
    }

    private fun sniperShot(e: Enemy) {
        val dx = px - e.x
        val dy = py - e.y
        val len = max(1f, hypot(dx, dy))
        val spd = dp(560f)
        enemyBullet(e.x, e.y + e.r * 0.4f, dx / len * spd, dy / len * spd, dp(6f))
        sound.play(SoundManager.SHOOT, 0.35f, 0.55f)
    }

    private fun enemyFire(e: Enemy) {
        when (e.kind) {
            K_TANK -> {
                val spd = dp(240f)
                for (k in -1..1) {
                    enemyBullet(e.x + k * dp(11f), e.y + e.r * 0.6f, k * dp(85f), spd, dp(7f))
                }
                sound.play(SoundManager.HIT, 0.22f, 0.8f)
            }
            K_ELITE -> {
                val dx = px - e.x
                val dy = py - e.y
                val len = max(1f, hypot(dx, dy))
                val spd = dp(300f)
                for (k in -1..1) {
                    val ang = k * 0.20f
                    val ca = cos(ang)
                    val sa = sin(ang)
                    val vx = (dx / len) * ca - (dy / len) * sa
                    val vy = (dx / len) * sa + (dy / len) * ca
                    enemyBullet(e.x, e.y + e.r * 0.5f, vx * spd, vy * spd, dp(6f))
                }
                sound.play(SoundManager.HIT, 0.22f, 1.0f)
            }
            K_SPLITTER -> {
                val spd = dp(230f)
                for (k in 0 until 6) {
                    val ang = (k.toFloat() / 6f) * 6.2831855f
                    enemyBullet(e.x, e.y, cos(ang) * spd, sin(ang) * spd, dp(6f))
                }
                sound.play(SoundManager.HIT, 0.24f, 0.75f)
            }
            K_SHIELDED -> {
                val dx = px - e.x
                val dy = py - e.y
                val len = max(1f, hypot(dx, dy))
                val spd = dp(270f)
                for (k in -1..1) {
                    val ang = k * 0.26f
                    val ca = cos(ang)
                    val sa = sin(ang)
                    val vx = (dx / len) * ca - (dy / len) * sa
                    val vy = (dx / len) * sa + (dy / len) * ca
                    enemyBullet(e.x, e.y + e.r * 0.5f, vx * spd, vy * spd, dp(7f))
                }
                sound.play(SoundManager.HIT, 0.22f, 0.85f)
            }
            K_BOMBER -> {
                val spd = dp(190f)
                for (k in -3..3) {
                    val ang = k * 0.16f
                    enemyBullet(e.x, e.y + e.r * 0.5f, sin(ang) * spd, cos(ang) * spd, dp(8f))
                }
                sound.play(SoundManager.HIT, 0.26f, 0.6f)
            }
            K_WARP -> {
                val dx = px - e.x
                val dy = py - e.y
                val len = max(1f, hypot(dx, dy))
                val spd = dp(340f)
                enemyBullet(e.x, e.y, dx / len * spd, dy / len * spd, dp(6f))
                sound.play(SoundManager.SHOOT, 0.25f, 0.9f)
            }
            K_BOSS -> bossFire(e)
        }
    }

    private fun bossFire(e: Enemy) {
        when (e.bossType) {
            BOSS_AZURE -> {
                val spd = dp(290f)
                val dx = px - e.x
                val dy = py - e.y
                val len = max(1f, hypot(dx, dy))
                val count = if (bossPhase == 1) 2 else 3
                for (k in 0 until count) {
                    val spread = (k - (count - 1) / 2f) * 0.18f
                    val ca = cos(spread)
                    val sa = sin(spread)
                    val vx = (dx / len) * ca - (dy / len) * sa
                    val vy = (dx / len) * sa + (dy / len) * ca
                    enemyBullet(e.x, e.y + e.r * 0.5f, vx * spd, vy * spd, dp(8f))
                }
                val wingSpd = dp(250f)
                enemyBullet(e.x - e.r * 1.1f, e.y, -dp(40f), wingSpd, dp(7f))
                enemyBullet(e.x + e.r * 1.1f, e.y, dp(40f), wingSpd, dp(7f))
                if (bossPhase >= 3) {
                    for (k in -2..2) {
                        val ang = k * 0.30f
                        enemyBullet(e.x, e.y + e.r * 0.5f, sin(ang) * wingSpd, cos(ang) * wingSpd, dp(7f))
                    }
                }
                sound.play(SoundManager.HIT, 0.3f, 0.7f)
            }
            BOSS_VOID -> {
                bossSpin += 0.36f
                val spd = dp(265f)
                val arms = if (bossPhase == 1) 6 else if (bossPhase == 2) 8 else 10
                for (k in 0 until arms) {
                    val ang = bossSpin + k * (6.2831855f / arms)
                    enemyBullet(e.x, e.y, cos(ang) * spd, sin(ang) * spd, dp(7f))
                }
                if (bossPhase >= 2 && Random.nextFloat() < 0.5f) summonMinion(e)
                sound.play(SoundManager.HIT, 0.3f, 0.6f)
            }
            BOSS_OMEGA -> {
                bossSpin += 0.22f
                val spd = dp(300f)
                // 激光扫射：密集子弹线随相位左右摆动
                val sweep = sin(bossSpin) * 0.7f
                for (k in -4..4) {
                    val ang = sweep + k * 0.09f
                    enemyBullet(e.x, e.y + e.r * 0.4f, sin(ang) * spd, cos(ang) * spd, dp(7f))
                }
                if (bossPhase >= 2) {
                    // 弹幕墙：横跨屏幕的一排子弹
                    val wallSpd = dp(215f)
                    val n = 9
                    for (k in 0 until n) {
                        val fx = vw * (k + 0.5f) / n
                        enemyBullet(fx, e.y + e.r, 0f, wallSpd, dp(8f))
                    }
                }
                if (bossPhase >= 3 && Random.nextFloat() < 0.4f) summonMinion(e)
                sound.play(SoundManager.HIT, 0.3f, 0.55f)
            }
            BOSS_NOVA -> {
                bossSpin += 0.5f
                val spd = dp(270f)
                val arms = if (bossPhase == 1) 10 else if (bossPhase == 2) 14 else 18
                for (k in 0 until arms) {
                    val ang = bossSpin + k * (6.2831855f / arms)
                    enemyBullet(e.x, e.y, cos(ang) * spd, sin(ang) * spd, dp(7f))
                }
                if (bossPhase >= 2) {
                    val dx = px - e.x
                    val dy = py - e.y
                    val len = max(1f, hypot(dx, dy))
                    for (k in -1..1) {
                        val ang = k * 0.22f
                        val ca = cos(ang)
                        val sa = sin(ang)
                        val vx = (dx / len) * ca - (dy / len) * sa
                        val vy = (dx / len) * sa + (dy / len) * ca
                        enemyBullet(e.x, e.y, vx * dp(330f), vy * dp(330f), dp(8f))
                    }
                }
                sound.play(SoundManager.HIT, 0.3f, 0.5f)
            }
            else -> {
                when (bossPhase) {
                    1 -> {
                        val spd = dp(250f)
                        for (k in -2..2) {
                            val ang = k * 0.28f
                            enemyBullet(e.x, e.y + e.r * 0.5f, sin(ang) * spd, cos(ang) * spd, dp(8f))
                        }
                    }
                    2 -> {
                        val spd = dp(300f)
                        for (k in -3..3) {
                            val ang = k * 0.22f
                            enemyBullet(e.x, e.y + e.r * 0.5f, sin(ang) * spd, cos(ang) * spd, dp(8f))
                        }
                        val dx = px - e.x
                        val dy = py - e.y
                        val len = max(1f, hypot(dx, dy))
                        enemyBullet(e.x, e.y + e.r * 0.5f, dx / len * spd, dy / len * spd, dp(9f))
                    }
                    else -> {
                        bossSpin += 0.42f
                        val spd = dp(280f)
                        for (k in 0 until 8) {
                            val ang = bossSpin + k * 0.7854f
                            enemyBullet(e.x, e.y, cos(ang) * spd, sin(ang) * spd, dp(7f))
                        }
                    }
                }
                sound.play(SoundManager.HIT, 0.3f, 0.65f)
            }
        }
    }

    private fun summonMinion(boss: Enemy) {
        val e = enemyPool.obtain()
        e.reset()
        e.kind = K_SCOUT
        e.r = dp(13f)
        e.hp = max(1, (2 * level.hpScale).toInt())
        e.maxHp = e.hp
        e.score = 10
        e.coinDrop = 1
        e.x = boss.x + (Random.nextFloat() - 0.5f) * boss.r * 2f
        e.y = boss.y
        e.vx = (Random.nextFloat() - 0.5f) * dp(80f)
        e.vy = dp(150f)
        enemies.add(e)
    }

    private fun enemyBullet(x: Float, y: Float, vx: Float, vy: Float, r: Float) {
        val b = bulletPool.obtain()
        b.x = x; b.y = y; b.vx = vx; b.vy = vy
        b.r = r; b.friendly = false; b.power = 1
        b.missile = false; b.homing = false; b.pierce = false; b.color = 0
        bullets.add(b)
    }

    private fun updatePowerups(dt: Float) {
        var magnetR = dp(120f) + upgrades.levelOf(U_MAGNET) * dp(40f)
        if (gear.hasEquipment(EQ_GRAVITY)) magnetR *= 1.8f
        val hasMagnet = magnetTimer > 0f || upgrades.levelOf(U_MAGNET) > 0 || gear.hasEquipment(EQ_GRAVITY)
        for (i in powerups.indices.reversed()) {
            val p = powerups[i]
            if (hasMagnet) {
                val dx = px - p.x
                val dy = py - p.y
                val d2 = dx * dx + dy * dy
                if (d2 < magnetR * magnetR) {
                    val d = max(1f, hypot(dx, dy))
                    val pull = dp(600f)
                    p.vx += (dx / d * pull - p.vx) * min(1f, dt * 4f)
                    p.vy += (dy / d * pull - p.vy) * min(1f, dt * 4f)
                    p.attracted = true
                }
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.phase += dt * 3f
            if (p.y > vh + dp(40f) || p.x < -dp(60f) || p.x > vw + dp(60f)) {
                powerups.fastRemove(i)
                powerupPool.recycle(p)
            }
        }
    }

    // ---------------- 僚机 ----------------

    /** 僚机在玩家附近的相对偏移（按数量左右分布） */
    private fun wingOffsetX(i: Int, count: Int): Float =
        if (count <= 1) 0f else (i - (count - 1) / 2f) * dp(46f)

    private fun updateWingmen(dt: Float) {
        val spec = wingmanSpec(gear.selectedWingman)
        if (spec.count <= 0) return
        wingTimer -= dt
        if (wingTimer > 0f) return
        wingTimer = spec.fireInterval / (1f + weaponLevel * 0.04f)
        for (i in 0 until spec.count) {
            val ox = wingOffsetX(i, spec.count)
            val wx = px + ox
            val wy = py + dp(34f)
            val b = bulletPool.obtain()
            b.x = wx
            b.y = wy
            b.r = if (spec.missile) dp(6f) else dp(4.5f)
            b.friendly = true
            b.pierce = false
            if (spec.missile) {
                b.vx = ox * 1.6f
                b.vy = -dp(300f)
                b.power = 2
                b.missile = true
                b.homing = true
                b.color = 0
            } else {
                b.vx = ox * 0.6f
                b.vy = -dp(720f)
                b.power = 1
                b.missile = false
                b.homing = false
                b.color = if (gear.selectedWingman == WG_LASER) 0xFF69F0AE.toInt() else 0
            }
            bullets.add(b)
        }
        sound.play(SoundManager.SHOOT, 0.10f, 1.35f)
    }

    // ---------------- 碰撞 ----------------

    private fun damageEnemy(e: Enemy, index: Int, dmg: Int, hitX: Float, hitY: Float) {
        var d = dmg
        val crit = gear.hasEquipment(EQ_CRIT) && Random.nextFloat() < 0.20f
        if (crit) d *= 2
        if (e.shielded) {
            e.shieldHp -= d
            e.flash = 1f
            burst(hitX, hitY, 0xFF80DEEA.toInt())
            if (e.shieldHp <= 0) {
                e.shieldHp = 0
                rings.add(obtainRing(e.x, e.y, e.r, e.r * 2.4f, 0.55f, 0xFF80DEEA.toInt(), dp(3.5f)))
                pushText(e.x, e.y - e.r, "护盾破碎", 0xFF80DEEA.toInt(), dp(14f), 0.9f)
                sound.play(SoundManager.EXPLODE, 0.4f, 1.5f)
            }
            return
        }
        e.hp -= d
        e.flash = 1f
        if (crit) {
            burst(hitX, hitY, 0xFFFFCA28.toInt())
            pushText(hitX, hitY - dp(6f), "暴击", 0xFFFFCA28.toInt(), dp(13f), 0.55f)
        } else {
            burst(hitX, hitY, 0xFFFFF59D.toInt())
        }
        if (e.hp <= 0) killEnemy(e, index)
    }

    private fun collide() {
        for (i in bullets.indices.reversed()) {
            val b = bullets[i]
            if (!b.friendly) continue
            for (j in enemies.indices.reversed()) {
                val e = enemies[j]
                val rr = b.r + e.r
                val dx = b.x - e.x
                val dy = b.y - e.y
                if (dx * dx + dy * dy < rr * rr) {
                    if (!b.pierce) {
                        bullets.fastRemove(i)
                        bulletPool.recycle(b)
                    }
                    sound.play(SoundManager.HIT, 0.20f, 1.2f)
                    damageEnemy(e, j, b.power, b.x, b.y)
                    break
                }
            }
        }

        for (i in enemies.indices.reversed()) {
            val e = enemies[i]
            val rr = e.r + playerR * 0.75f
            val dx = e.x - px
            val dy = e.y - py
            if (dx * dx + dy * dy < rr * rr) {
                val isBoss = e.kind == K_BOSS
                val ram = e.kind == K_KAMIKAZE
                if (shield > 0f) {
                    if (!isBoss) damageEnemy(e, i, if (ram) 12 else 6, e.x, e.y)
                } else if (invincible <= 0f) {
                    hurt(if (ram) 2 else 1)
                    if (!isBoss) damageEnemy(e, i, if (ram) 14 else 4, e.x, e.y)
                }
            }
        }

        for (i in bullets.indices.reversed()) {
            val b = bullets[i]
            if (b.friendly) continue
            val rr = b.r + playerR * 0.62f
            val dx = b.x - px
            val dy = b.y - py
            if (dx * dx + dy * dy < rr * rr) {
                if (shield > 0f) {
                    bullets.fastRemove(i)
                    bulletPool.recycle(b)
                    burst(b.x, b.y, 0xFF69F0AE.toInt())
                } else if (invincible <= 0f) {
                    bullets.fastRemove(i)
                    bulletPool.recycle(b)
                    hurt(1)
                }
            }
        }

        for (i in powerups.indices.reversed()) {
            val p = powerups[i]
            val rr = dp(30f) + playerR
            val dx = p.x - px
            val dy = p.y - py
            if (dx * dx + dy * dy < rr * rr) {
                powerups.fastRemove(i)
                applyPowerUp(p)
                powerupPool.recycle(p)
            }
        }
    }

    private fun applyPowerUp(p: PowerUp) {
        when (p.kind) {
            P_WEAPON -> {
                weaponLevel = min(5, weaponLevel + 1)
                ach.max(S_MAX_WEAPON, weaponLevel)
                pushText(px, py - dp(46f), "火力 Lv." + weaponLevel, 0xFF4FC3F7.toInt(), dp(17f), 1.0f)
            }
            P_BOMB -> {
                bombs = min(5, bombs + 1)
                hudBomb = "x$bombs"
                pushText(px, py - dp(46f), "炸弹 +1", 0xFFFFD54F.toInt(), dp(17f), 1.0f)
            }
            P_LIFE -> {
                lives = min(5, lives + 1)
                pushText(px, py - dp(46f), "生命 +1", 0xFFFF8A80.toInt(), dp(17f), 1.0f)
            }
            P_SHIELD -> {
                shield = 7f
                pushText(px, py - dp(46f), "护盾启动", 0xFF69F0AE.toInt(), dp(17f), 1.0f)
            }
            P_COIN -> {
                val v = (5 * coinMul()).toInt().coerceAtLeast(1)
                upgrades.coins += v
                runCoins += v
                ach.add(S_COINS, v)
                refreshCoins()
                pushText(px, py - dp(46f), "+$v 金币", 0xFFFFB300.toInt(), dp(16f), 0.9f)
                sound.play(SoundManager.COIN, 0.8f)
            }
            P_MAGNET -> {
                magnetTimer = 10f
                pushText(px, py - dp(46f), "磁力场 10s", 0xFFBA68C8.toInt(), dp(17f), 1.0f)
            }
            P_SLOW -> {
                slowTimer = 6f
                pushText(px, py - dp(46f), "时间减速 6s", 0xFF4DD0E1.toInt(), dp(17f), 1.0f)
            }
        }
        rings.add(obtainRing(px, py, dp(10f), dp(64f), 0.5f, 0xFFB3E5FC.toInt(), dp(3f)))
        buzz(22)
        sound.play(SoundManager.POWER, 0.85f)
    }

    private fun killEnemy(e: Enemy, index: Int) {
        if (index !in enemies.indices) return
        val kind = e.kind
        val ex = e.x
        val ey = e.y
        val er = e.r
        val escore = e.score
        val ecoin = e.coinDrop
        val egen = e.gen

        enemies.fastRemove(index)
        kills++
        combo++
        comboTimer = 1.6f
        val mult = 1f + min(2f, combo * 0.1f)
        val gained = (escore * mult).toInt()
        score += gained
        hudScore = score.toString()
        if (combo >= 3) hudCombo = "x" + combo

        ach.add(S_KILLS, 1)
        ach.max(S_BEST_COMBO, combo)
        // 每日任务：击落数（累计）与最高连击（取最大）
        rewards.push(T_KILLS, 1)
        rewards.pushBest(T_COMBO, combo)

        val big = kind == K_BOSS
        explode(ex, ey, er, if (big) 60 else if (er > dp(18f)) 30 else 16)
        rings.add(
            obtainRing(
                ex, ey, er * 0.4f,
                er * (if (big) 3.4f else 2.1f),
                if (big) 1.1f else 0.85f,
                if (big) 0xFFFF7043.toInt() else 0xFFFFC107.toInt(),
                dp(4f)
            )
        )
        pushText(ex, ey, "+" + gained, 0xFFFFD54F.toInt(), dp(16f), 0.9f)
        if (combo >= 3) pushText(ex, ey + dp(20f), "连击 x" + combo, 0xFF69F0AE.toInt(), dp(14f), 0.9f)

        if (ecoin > 0) {
            val v = (ecoin * coinMul()).toInt().coerceAtLeast(1)
            upgrades.coins += v
            runCoins += v
            ach.add(S_COINS, v)
            refreshCoins()
        }

        // 装备：吸血核心 —— 击落敌机有概率回血
        if (gear.hasEquipment(EQ_LIFESTEAL) && lives < 6 && Random.nextFloat() < 0.08f) {
            lives++
            pushText(px, py - dp(46f), "吸血 +1", 0xFFEF5350.toInt(), dp(15f), 0.9f)
        }

        if (big) {
            bossAlive = false
            bossRef = null
            shake = 1f
            hitStop = 0.22f
            bombs = min(5, bombs + 2)
            hudBomb = "x$bombs"
            buzz(160)
            sound.play(SoundManager.BIG_BOOM)
            ach.add(S_BOSSES, 1)
            rewards.push(T_BOSSES, 1)
        } else {
            shake = min(1f, shake + if (er > dp(18f)) 0.5f else 0.2f)
            sound.play(SoundManager.EXPLODE, 0.7f, 0.9f + Random.nextFloat() * 0.2f)
            dropPower(ex, ey)
        }

        if (kind == K_SPLITTER && egen == 0) {
            val n = 2 + Random.nextInt(2)
            for (k in 0 until n) {
                val c = enemyPool.obtain()
                c.reset()
                c.kind = K_SCOUT
                c.gen = 1
                c.r = dp(11f)
                c.hp = max(1, (1.5f * level.hpScale).toInt())
                c.maxHp = c.hp
                c.score = 10
                c.coinDrop = 1
                c.x = ex + (k - (n - 1) / 2f) * dp(22f)
                c.y = ey
                c.vx = (k - (n - 1) / 2f) * dp(60f)
                c.vy = dp(160f)
                enemies.add(c)
            }
            pushText(ex, ey - dp(24f), "分裂！", 0xFF9CCC65.toInt(), dp(14f), 0.9f)
        }

        enemyPool.recycle(e)

        if (big) {
            when {
                isBossRush -> bossRushAdvance()
                isEndless || isDaily -> { /* BOSS 只是强敌，不掉关 */ }
                else -> levelClear()
            }
        }
    }

    /** BOSS 连战：击破一个后进入下一个，全部击破则通关 */
    private fun bossRushAdvance() {
        bossRushIndex++
        if (bossRushIndex >= BOSS_SPECS.size) {
            state = State.GAME_COMPLETE
            sound.play(SoundManager.LEVEL_CLEAR, 1f)
            shake = 1f
            if (score > highScore) {
                highScore = score
                newRecord = true
                hudHigh = "最高 $highScore"
                hudHighBig = "历史最高分 $highScore"
            }
            saveProgress()
            return
        }
        // 每击破一个 BOSS 回一点血，为下一场做准备
        lives = min(6, lives + 1)
        hudBomb = "x$bombs"
        pushText(px, py - dp(46f), "生命 +1", 0xFF69F0AE.toInt(), dp(17f), 1.2f)
        updateHudLevel()
        spawnBoss()
    }

    private fun dropPower(x: Float, y: Float) {
        val roll = Random.nextFloat()
        val kind = when {
            roll < 0.055f -> P_WEAPON
            roll < 0.085f -> P_BOMB
            roll < 0.098f -> P_LIFE
            roll < 0.115f -> P_SHIELD
            roll < 0.170f -> P_COIN
            roll < 0.185f -> P_MAGNET
            roll < 0.200f -> P_SLOW
            else -> -1
        }
        if (kind < 0) return
        val p = powerupPool.obtain()
        p.x = x; p.y = y
        p.vx = 0f; p.vy = dp(95f)
        p.kind = kind; p.phase = 0f; p.attracted = false
        powerups.add(p)
    }

    private fun hurt(amount: Int) {
        if (invincible > 0f) return
        lives -= amount
        invincible = 2.2f
        hitFlash = 1f
        shake = 1f
        hitStop = 0.10f
        combo = 0
        hudCombo = ""
        tookDamageThisLevel = true
        buzz(70)
        sound.play(SoundManager.HURT, 0.9f)
        explode(px, py, playerR * 1.6f, 26)
        weaponLevel = max(1, weaponLevel - 1)
        if (lives <= 0) {
            val canRevive = !reviveUsed &&
                    (upgrades.levelOf(U_REVIVE) > 0 || shipSpec(runShip).autoRevive)
            if (canRevive) {
                reviveUsed = true
                lives = 1
                invincible = 3.5f
                shield = 4f
                px = vw / 2f
                py = vh * 0.78f
                tx = px
                ty = py
                rings.add(obtainRing(px, py, dp(10f), dp(140f), 0.9f, 0xFFFF5252.toInt(), dp(5f)))
                pushText(px, py - dp(56f), "复活！", 0xFFFF5252.toInt(), dp(20f), 1.4f)
                buzz(180)
                sound.play(SoundManager.REVIVE, 1f)
            } else {
                gameOver()
            }
        } else {
            px = vw / 2f
            py = vh * 0.78f
            tx = px
            ty = py
        }
    }

    private fun gameOver() {
        state = State.GAME_OVER
        overCooldown = 0.8f
        newRecord = score > highScore
        if (newRecord) {
            highScore = score
            hudHigh = "最高 $highScore"
            hudHighBig = "历史最高分 $highScore"
        }
        ach.max(S_BEST_TIME, elapsed.toInt())

        endRunTasks()

        hudFinalScore = "本局得分 $score"
        val modeTag = when {
            isEndless -> "无尽 · "
            isBossRush -> "连战 · "
            isDaily -> "每日 · "
            else -> ""
        }
        hudFinalStats = "${modeTag}击落 $kills 架 · 存活 ${elapsed.toInt()} 秒 · 金币 +$runCoins"
        explode(px, py, playerR * 2f, 44)
        shake = 1f
        buzz(220)
        sound.play(SoundManager.GAME_OVER)
        pushAchievementToasts()
        saveProgress()
    }

    private fun useBomb() {
        if (bombs <= 0) return
        bombs--
        hudBomb = "x$bombs"
        shake = 1f
        hitStop = 0.14f
        buzz(150)
        sound.play(SoundManager.BIG_BOOM, 0.9f, 1.15f)
        ach.add(S_BOMBS, 1)
        rewards.push(T_BOMBS, 1)
        rings.add(obtainRing(px, py, dp(10f), max(vw, vh) * 1.2f, 1.1f, 0xFFFFF176.toInt(), dp(8f)))
        for (i in enemies.indices.reversed()) {
            val e = enemies[i]
            if (e.kind == K_BOSS) {
                e.hp -= 35
                e.flash = 1f
                if (e.hp <= 0) killEnemy(e, i) else explode(e.x, e.y, e.r * 0.7f, 8)
            } else {
                damageEnemy(e, i, 35, e.x, e.y)
            }
        }
        for (i in bullets.indices.reversed()) {
            val b = bullets[i]
            if (!b.friendly) {
                bullets.fastRemove(i)
                bulletPool.recycle(b)
            }
        }
    }

    // ---------------- 粒子 ----------------

    private fun obtainParticle(): Particle = particlePool.obtain()

    private fun explode(x: Float, y: Float, r: Float, count: Int) {
        val scale = (r / dp(20f)).coerceIn(0.5f, 2.4f)
        for (i in 0 until count) {
            val a = Random.nextFloat() * 6.2831855f
            val spd = dp(50f) + Random.nextFloat() * dp(230f) * scale
            val life = 0.32f + Random.nextFloat() * 0.55f
            val roll = Random.nextFloat()
            val col = when {
                roll < 0.40f -> 0xFFFFF176.toInt()
                roll < 0.72f -> 0xFFFF7043.toInt()
                else -> 0xFFFFCC80.toInt()
            }
            val p = obtainParticle()
            p.x = x; p.y = y
            p.vx = cos(a) * spd; p.vy = sin(a) * spd
            p.life = life; p.maxLife = life
            p.color = col
            p.size = dp(1.8f) + Random.nextFloat() * dp(3.2f)
            p.drag = 2.2f
            p.additive = true
            particles.add(p)
        }
    }

    private fun burst(x: Float, y: Float, color: Int) {
        for (i in 0 until 4) {
            val a = Random.nextFloat() * 6.2831855f
            val spd = dp(30f) + Random.nextFloat() * dp(90f)
            val life = 0.15f + Random.nextFloat() * 0.2f
            val p = obtainParticle()
            p.x = x; p.y = y
            p.vx = cos(a) * spd; p.vy = sin(a) * spd
            p.life = life; p.maxLife = life
            p.color = color
            p.size = dp(1.5f) + Random.nextFloat() * dp(2f)
            p.drag = 2.2f
            p.additive = true
            particles.add(p)
        }
    }

    private fun spawnTrail() {
        val p = obtainParticle()
        p.x = px + (Random.nextFloat() - 0.5f) * playerR * 0.5f
        p.y = py + playerR * 1.15f
        p.vx = (Random.nextFloat() - 0.5f) * dp(24f)
        p.vy = dp(120f) + Random.nextFloat() * dp(60f)
        val life = 0.22f + Random.nextFloat() * 0.16f
        p.life = life; p.maxLife = life
        p.color = if (Random.nextFloat() < 0.5f) 0xFF4FC3F7.toInt() else 0xFFFFB300.toInt()
        p.size = dp(1.6f) + Random.nextFloat() * dp(1.8f)
        p.drag = 3.2f
        p.additive = true
        particles.add(p)
    }

    private fun stepParticles(dt: Float) {
        for (i in particles.indices.reversed()) {
            val p = particles[i]
            p.x += p.vx * dt
            p.y += p.vy * dt
            val k = 1f - dt * p.drag
            p.vx *= k
            p.vy *= k
            p.life -= dt
            if (p.life <= 0f) {
                particles.fastRemove(i)
                particlePool.recycle(p)
            }
        }
    }

    private fun obtainRing(x: Float, y: Float, r: Float, maxR: Float, life: Float, color: Int, width: Float): Ring {
        val g = ringPool.obtain()
        g.x = x; g.y = y; g.r = r; g.maxR = maxR; g.life = life; g.color = color; g.width = width
        return g
    }

    private fun stepRings(dt: Float) {
        for (i in rings.indices.reversed()) {
            val r = rings[i]
            r.r += (r.maxR - r.r) * min(1f, dt * 7f)
            r.life -= dt * 1.4f
            if (r.life <= 0f) {
                rings.fastRemove(i)
                ringPool.recycle(r)
            }
        }
    }

    private fun pushText(x: Float, y: Float, text: String, color: Int, size: Float, life: Float) {
        val t = textPool.obtain()
        t.x = x; t.y = y; t.text = text; t.color = color; t.size = size
        t.life = life; t.maxLife = life
        texts.add(t)
    }

    private fun stepTexts(dt: Float) {
        for (i in texts.indices.reversed()) {
            val t = texts[i]
            t.y -= dt * dp(42f)
            t.life -= dt
            if (t.life <= 0f) {
                texts.fastRemove(i)
                textPool.recycle(t)
            }
        }
    }

    // ---------------- 成就提示 ----------------

    private fun pushAchievementToasts() {
        for (a in ach.pending) achQueue.add(a)
        ach.pending.clear()
        if (achToastTimer <= 0f) popAchToast()
    }

    private fun popAchToast() {
        if (achQueue.isEmpty()) {
            achToastTimer = 0f
            return
        }
        val a = achQueue.removeAt(0)
        hudAchToast = "成就解锁 · ${a.name}"
        hudAchToastSub = a.desc
        achToastColor = a.color
        achToastTimer = 2.6f
        sound.play(SoundManager.ACHIEVE, 0.9f)
    }

    private fun buzz(ms: Long) {
        if (!vibrateOn) return
        val v = vibrator ?: return
        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /** 统一处理设置页的开关点击 */
    private fun handleSettingsDown(x: Float, y: Float) {
        if (btnBack.contains(x, y)) {
            sound.play(SoundManager.CLICK, 0.6f)
            state = State.READY
            refreshMenu()
            return
        }
        if (settingsRows[0].contains(x, y)) {
            if (soundOn) sound.play(SoundManager.CLICK, 0.7f)  // 关闭前先响一声
            soundOn = !soundOn
            sound.enabled = soundOn
            if (soundOn) sound.play(SoundManager.CLICK, 0.7f)  // 开启时立即反馈
            buzz(18)
            saveProgress()
        } else if (settingsRows[1].contains(x, y)) {
            vibrateOn = !vibrateOn
            if (vibrateOn) buzz(24)                            // 开启震动时给一次触感
            sound.play(SoundManager.CLICK, 0.6f)
            saveProgress()
        } else if (settingsRows[2].contains(x, y)) {
            musicOn = !musicOn
            music.enabled = musicOn
            sound.play(SoundManager.CLICK, 0.6f)
            buzz(18)
            saveProgress()
        } else if (btnVolDown.contains(x, y)) {
            musicVolume = (musicVolume - 0.1f).coerceIn(0f, 1f)
            music.volume = musicVolume
            sound.play(SoundManager.CLICK, 0.5f)
            saveProgress()
        } else if (btnVolUp.contains(x, y)) {
            musicVolume = (musicVolume + 0.1f).coerceIn(0f, 1f)
            music.volume = musicVolume
            sound.play(SoundManager.CLICK, 0.6f)
            saveProgress()
        } else if (settingsRows[4].contains(x, y)) {
            sound.play(SoundManager.CLICK, 0.6f)
            logout()
        }
    }

    // ---------------- 登录 / 注册 ----------------

    private fun handleLoginDown(x: Float, y: Float) {
        when {
            btnLgUser.contains(x, y) -> {
                sound.play(SoundManager.CLICK, 0.5f)
                openField(F_LG_USER)
            }
            btnLgPass.contains(x, y) -> {
                sound.play(SoundManager.CLICK, 0.5f)
                openField(F_LG_PASS)
            }
            btnLgSubmit.contains(x, y) -> {
                sound.play(SoundManager.CLICK, 0.7f)
                doLogin()
            }
            btnLgToReg.contains(x, y) -> {
                sound.play(SoundManager.CLICK, 0.6f)
                textInput?.close()
                activeField = F_NONE
                loginMsg = ""
                loginMsgOk = false
                regUser = ""
                regPass = ""
                regPass2 = ""
                state = State.REGISTER
                layoutLogin()
                invalidate()
            }
        }
    }

    private fun handleRegisterDown(x: Float, y: Float) {
        when {
            btnRgUser.contains(x, y) -> {
                sound.play(SoundManager.CLICK, 0.5f)
                openField(F_RG_USER)
            }
            btnRgPass.contains(x, y) -> {
                sound.play(SoundManager.CLICK, 0.5f)
                openField(F_RG_PASS)
            }
            btnRgPass2.contains(x, y) -> {
                sound.play(SoundManager.CLICK, 0.5f)
                openField(F_RG_PASS2)
            }
            btnRgSubmit.contains(x, y) -> {
                sound.play(SoundManager.CLICK, 0.7f)
                doRegister()
            }
            btnRgToLogin.contains(x, y) -> {
                sound.play(SoundManager.CLICK, 0.6f)
                textInput?.close()
                activeField = F_NONE
                loginMsg = ""
                loginMsgOk = false
                state = State.LOGIN
                layoutLogin()
                invalidate()
            }
        }
    }

    // ---------------- 战机选择 ----------------

    private fun handleShipSelectDown(x: Float, y: Float) {
        if (btnBack.contains(x, y)) {
            sound.play(SoundManager.CLICK, 0.6f)
            state = State.READY
            refreshMenu()
            return
        }
        for (i in 0 until SHIP_COUNT) {
            if (!shipRows[i].contains(x, y)) continue
            val spec = SHIPS[i]
            if (ships.isUnlocked(i)) {
                ships.selected = i
                runShip = i
                saveProgress()
                sound.play(SoundManager.CLICK, 0.75f)
            } else if (upgrades.coins >= spec.cost) {
                upgrades.coins -= spec.cost
                ships.unlock(i)
                ships.selected = i
                runShip = i
                saveProgress()
                refreshCoins()
                sound.play(SoundManager.BUY, 0.9f)
                buzz(30)
                pushText(vw / 2f, vh * 0.30f, "已解锁 · ${spec.name}", spec.color, dp(18f), 1.4f)
            } else {
                sound.play(SoundManager.HURT, 0.4f, 1.6f)
            }
            return
        }
    }

    // ---------------- 装备商店 ----------------

    private fun gearPageCount(): Int = (WG_COUNT + EQ_COUNT + GEAR_ROWS - 1) / GEAR_ROWS

    private fun handleGearShopDown(x: Float, y: Float) {
        if (btnBack.contains(x, y)) {
            sound.play(SoundManager.CLICK, 0.6f)
            state = State.READY
            refreshMenu()
            return
        }
        if (btnGearPrev.contains(x, y)) {
            if (gearPage > 0) gearPage--
            sound.play(SoundManager.CLICK, 0.5f)
            return
        }
        if (btnGearNext.contains(x, y)) {
            if (gearPage < gearPageCount() - 1) gearPage++
            sound.play(SoundManager.CLICK, 0.5f)
            return
        }
        for (i in 0 until GEAR_ROWS) {
            if (!gearRows[i].contains(x, y)) continue
            val idx = gearPage * GEAR_ROWS + i
            if (idx < WG_COUNT) buyOrSelectWingman(idx)
            else if (idx - WG_COUNT < EQ_COUNT) buyEquipment(idx - WG_COUNT)
            return
        }
    }

    // ---------------- 奖励中心 ----------------

    private fun handleRewardsDown(x: Float, y: Float) {
        if (btnBack.contains(x, y)) {
            sound.play(SoundManager.CLICK, 0.6f)
            state = State.READY
            refreshMenu()
            return
        }
        if (btnRewardsBox.contains(x, y)) {
            sound.play(SoundManager.CLICK, 0.6f)
            boxTimer = 0f
            state = State.BOX
            return
        }
        if (btnRewardsStars.contains(x, y)) {
            sound.play(SoundManager.CLICK, 0.6f)
            state = State.STARS
            return
        }
        if (btnCheckin.contains(x, y)) {
            doCheckIn()
            return
        }
        for (i in ACTIVITY_TIERS.indices) {
            if (tierBtns[i].contains(x, y)) {
                if (rewards.claimTier(i)) {
                    val t = ACTIVITY_TIERS[i]
                    upgrades.coins += t.coins
                    rewards.keys += t.keys
                    refreshCoins()
                    saveProgress()
                    sound.play(SoundManager.BUY, 0.9f)
                    buzz(40)
                    showBoxResult("活跃度奖励", t.short + if (t.keys > 0) " · 钥匙 +${t.keys}" else "", 0xFF69F0AE.toInt())
                } else {
                    sound.play(SoundManager.HURT, 0.4f, 1.6f)
                }
                return
            }
        }
    }

    private fun doCheckIn() {
        val today = todayKey()
        val idx = rewards.checkin.claim(today)
        if (idx < 0) {
            sound.play(SoundManager.HURT, 0.4f, 1.6f)
            return
        }
        val r = CHECKIN_REWARDS[idx]
        upgrades.coins += r.coins
        rewards.keys += r.keys
        refreshCoins()
        saveProgress()
        sound.play(SoundManager.BUY, 0.9f)
        buzz(40)
        val extra = if (r.keys > 0) " · 钥匙 +${r.keys}" else ""
        showBoxResult("签到成功 · 第 ${idx + 1} 天", "金币 +${r.coins}$extra", 0xFFFFB300.toInt())
    }

    private fun handleStarsDown(x: Float, y: Float) {
        if (btnBack.contains(x, y)) {
            sound.play(SoundManager.CLICK, 0.6f)
            state = State.REWARDS
            return
        }
        for (i in starRows.indices) {
            if (!starRows[i].contains(x, y)) continue
            if (rewards.claimMilestone(i)) {
                val m = STAR_MILESTONES[i]
                upgrades.coins += m.coins
                rewards.keys += m.keys
                if (m.shipId >= 0) ships.unlock(m.shipId)
                if (m.wingId >= 0) gear.unlockWingman(m.wingId)
                if (m.eqId >= 0) gear.unlockEquipment(m.eqId)
                refreshCoins()
                saveProgress()
                sound.play(SoundManager.BUY, 0.9f)
                buzz(50)
                showBoxResult("星级奖励达成", m.label, 0xFFFFD54F.toInt())
            } else {
                sound.play(SoundManager.HURT, 0.4f, 1.6f)
            }
            return
        }
    }

    private fun handleBoxDown(x: Float, y: Float) {
        // 结果弹层显示期间，点任意位置关闭
        if (boxTimer > 0f) {
            boxTimer = 0f
            return
        }
        if (btnBack.contains(x, y)) {
            sound.play(SoundManager.CLICK, 0.6f)
            state = State.REWARDS
            return
        }
        for (i in 0 until BOX_ROWS) {
            if (boxRows[i].contains(x, y)) {
                openBox(i)
                return
            }
        }
    }

    /** 开一次箱：扣费 → 抽奖 → 发放 → 弹结果 */
    private fun openBox(boxType: Int) {
        val spec = rewards.boxSpec(boxType) ?: return
        if (upgrades.coins < spec.costCoins || rewards.keys < spec.costKeys) {
            sound.play(SoundManager.HURT, 0.4f, 1.6f)
            buzz(25)
            return
        }
        upgrades.coins -= spec.costCoins
        rewards.keys -= spec.costKeys
        val entry = rewards.rollLoot(boxType)
        rewards.noteOpen(boxType, entry)
        val sub = grantLoot(entry)
        refreshCoins()
        saveProgress()
        sound.play(if (entry.big) SoundManager.BUY else SoundManager.CLICK, 0.95f)
        buzz(if (entry.big) 60 else 25)
        shake = if (entry.big) 0.8f else 0.25f
        showBoxResult(entry.label, sub, entry.color)
    }

    /** 把抽到的东西真正发到玩家身上，返回副标题（说明实际发了什么） */
    private fun grantLoot(e: LootEntry): String {
        when (e.kind) {
            L_COIN -> {
                upgrades.coins += e.amount
                return "金币 +${e.amount}"
            }
            L_KEY -> {
                rewards.keys += e.amount
                return "秘银钥匙 +${e.amount}"
            }
            L_WING -> {
                // WG_NONE 是「不装备僚机」，不算奖励，直接排除
                val id = randomUnowned(WG_COUNT) { it == WG_NONE || gear.wingmanUnlocked(it) }
                if (id >= 0) {
                    gear.unlockWingman(id)
                    return "解锁僚机 · ${WINGMEN[id].name}"
                }
                upgrades.coins += 800
                return "僚机已全部拥有 · 折算 800 金币"
            }
            L_EQUIP -> {
                val id = randomUnowned(EQ_COUNT) { gear.hasEquipment(it) }
                if (id >= 0) {
                    gear.unlockEquipment(id)
                    return "解锁装备 · ${EQUIPMENT[id].name}"
                }
                upgrades.coins += 1000
                return "装备已全部拥有 · 折算 1000 金币"
            }
            L_SHIP -> {
                val id = randomUnowned(SHIP_COUNT) { ships.isUnlocked(it) }
                if (id >= 0) {
                    ships.unlock(id)
                    return "解锁战机 · ${SHIPS[id].name}"
                }
                upgrades.coins += 2000
                return "战机已全部拥有 · 折算 2000 金币"
            }
        }
        return ""
    }

    /** 在 0..count-1 里随机挑一个「还没拥有」的；全都有则返回 -1 */
    private fun randomUnowned(count: Int, owned: (Int) -> Boolean): Int {
        val pool = ArrayList<Int>(count)
        for (i in 0 until count) if (!owned(i)) pool.add(i)
        if (pool.isEmpty()) return -1
        return pool[Random.nextInt(pool.size)]
    }

    private fun showBoxResult(title: String, sub: String, color: Int) {
        boxTitle = title
        boxSub = sub
        boxColor = color
        boxTimer = BOX_POPUP_TIME
    }

    private fun buyOrSelectWingman(id: Int) {
        val spec = WINGMEN[id]
        if (gear.wingmanUnlocked(id)) {
            gear.selectedWingman = id
            saveProgress()
            sound.play(SoundManager.CLICK, 0.75f)
        } else if (upgrades.coins >= spec.cost) {
            upgrades.coins -= spec.cost
            gear.unlockWingman(id)
            gear.selectedWingman = id
            saveProgress()
            refreshCoins()
            sound.play(SoundManager.BUY, 0.9f)
            buzz(30)
        } else {
            sound.play(SoundManager.HURT, 0.4f, 1.6f)
        }
    }

    private fun buyEquipment(id: Int) {
        val spec = EQUIPMENT[id]
        if (gear.hasEquipment(id)) {
            sound.play(SoundManager.CLICK, 0.5f)
        } else if (upgrades.coins >= spec.cost) {
            upgrades.coins -= spec.cost
            gear.unlockEquipment(id)
            saveProgress()
            refreshCoins()
            sound.play(SoundManager.BUY, 0.9f)
            buzz(30)
        } else {
            sound.play(SoundManager.HURT, 0.4f, 1.6f)
        }
    }

    // ---------------- 输入 ----------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (released) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointer = event.getPointerId(0)
                handleDown(event.x, event.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (state == State.PLAYING && dragging) {
                    val idx = event.actionIndex
                    activePointer = event.getPointerId(idx)
                    tx = event.getX(idx)
                    ty = event.getY(idx) - dp(62f)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (state == State.PLAYING && dragging) {
                    val idx = event.findPointerIndex(activePointer)
                    if (idx >= 0) {
                        tx = event.getX(idx)
                        ty = event.getY(idx) - dp(62f)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                activePointer = -1
            }
        }
        return true
    }

    private fun handleDown(x: Float, y: Float) {
        when (state) {
            State.LOGIN -> handleLoginDown(x, y)

            State.REGISTER -> handleRegisterDown(x, y)

            State.READY -> {
                when {
                    menuButtons[0].contains(x, y) -> { sound.play(SoundManager.CLICK, 0.6f); startMode(M_CAMPAIGN) }
                    menuButtons[1].contains(x, y) -> { sound.play(SoundManager.CLICK, 0.6f); startMode(M_ENDLESS) }
                    menuButtons[2].contains(x, y) -> { sound.play(SoundManager.CLICK, 0.6f); startMode(M_BOSSRUSH) }
                    menuButtons[3].contains(x, y) -> { sound.play(SoundManager.CLICK, 0.6f); startDaily() }
                    menuButtons[4].contains(x, y) -> { sound.play(SoundManager.CLICK, 0.6f); state = State.SHIP_SELECT }
                    menuButtons[5].contains(x, y) -> { sound.play(SoundManager.CLICK, 0.6f); shopPage = 0; state = State.SHOP }
                    menuButtons[6].contains(x, y) -> { sound.play(SoundManager.CLICK, 0.6f); achPage = 0; state = State.ACHIEVEMENTS }
                    menuButtons[7].contains(x, y) -> { sound.play(SoundManager.CLICK, 0.6f); state = State.STATS }
                    menuButtons[8].contains(x, y) -> { sound.play(SoundManager.CLICK, 0.6f); state = State.SETTINGS }
                    menuButtons[9].contains(x, y) -> {
                        sound.play(SoundManager.CLICK, 0.6f)
                        rewards.refreshDaily()
                        state = State.REWARDS
                    }
                }
            }

            State.REWARDS -> handleRewardsDown(x, y)

            State.BOX -> handleBoxDown(x, y)

            State.STARS -> handleStarsDown(x, y)

            State.SHIP_SELECT -> handleShipSelectDown(x, y)

            State.GEAR_SHOP -> handleGearShopDown(x, y)

            State.LEVEL_INTRO -> {
                if (introTimer <= 1.9f) {
                    sound.play(SoundManager.CLICK, 0.6f)
                    beginPlay()
                }
            }

            State.LEVEL_CLEAR -> {
                if (clearTimer <= 0f && btnClearNext.contains(x, y)) {
                    sound.play(SoundManager.CLICK, 0.6f)
                    nextLevel()
                }
            }

            State.GAME_OVER -> {
                if (overCooldown <= 0f && btnAgain.contains(x, y)) {
                    if (isDaily) startDaily() else startMode(mode)
                }
            }

            State.GAME_COMPLETE -> {
                if (btnAgain.contains(x, y)) {
                    sound.play(SoundManager.CLICK, 0.6f)
                    state = State.READY
                    refreshMenu()
                }
            }

            State.PAUSED -> {
                if (btnResume.contains(x, y)) {
                    state = State.PLAYING
                    sound.play(SoundManager.CLICK, 0.6f)
                } else if (btnRestart.contains(x, y)) {
                    sound.play(SoundManager.CLICK, 0.6f)
                    if (isDaily) startDaily() else startMode(mode)
                } else if (btnQuit.contains(x, y)) {
                    sound.play(SoundManager.CLICK, 0.6f)
                    quitToMenu()
                }
            }

            State.SHOP -> handleShopDown(x, y)

            State.ACHIEVEMENTS -> {
                if (btnBack.contains(x, y)) {
                    sound.play(SoundManager.CLICK, 0.6f)
                    state = State.READY
                    refreshMenu()
                } else if (btnAchPrev.contains(x, y)) {
                    if (achPage > 0) achPage--
                    sound.play(SoundManager.CLICK, 0.5f)
                } else if (btnAchNext.contains(x, y)) {
                    val pages = (ACHIEVEMENTS.size + ACH_PER_PAGE - 1) / ACH_PER_PAGE
                    if (achPage < pages - 1) achPage++
                    sound.play(SoundManager.CLICK, 0.5f)
                }
            }

            State.STATS -> {
                if (btnBack.contains(x, y)) {
                    sound.play(SoundManager.CLICK, 0.6f)
                    state = State.READY
                    refreshMenu()
                }
            }

            State.SETTINGS -> handleSettingsDown(x, y)

            State.PLAYING -> {
                if (hypot(x - pauseBtnX, y - pauseBtnY) < dp(30f)) {
                    enterPause()
                    sound.play(SoundManager.CLICK, 0.6f)
                    return
                }
                if (hypot(x - bombBtnX, y - bombBtnY) < dp(44f)) {
                    useBomb()
                    return
                }
                dragging = true
                tx = x
                ty = y - dp(62f)
            }
        }
    }

    private fun handleShopDown(x: Float, y: Float) {
        if (btnBack.contains(x, y)) {
            sound.play(SoundManager.CLICK, 0.6f)
            state = State.READY
            refreshMenu()
            return
        }
        if (btnShopGear.contains(x, y)) {
            sound.play(SoundManager.CLICK, 0.6f)
            gearPage = 0
            state = State.GEAR_SHOP
            return
        }
        if (btnShopPrev.contains(x, y)) {
            if (shopPage > 0) shopPage--
            sound.play(SoundManager.CLICK, 0.5f)
            return
        }
        if (btnShopNext.contains(x, y)) {
            val pages = (UPGRADES.size + SHOP_ROWS - 1) / SHOP_ROWS
            if (shopPage < pages - 1) shopPage++
            sound.play(SoundManager.CLICK, 0.5f)
            return
        }
        for (i in 0 until W_COUNT) {
            if (weaponBtns[i].contains(x, y)) {
                if (upgrades.weaponUnlocked(i)) {
                    upgrades.selectedWeapon = i
                    saveProgress()
                    sound.play(SoundManager.CLICK, 0.7f)
                } else {
                    sound.play(SoundManager.HURT, 0.4f, 1.6f)
                }
                return
            }
        }
        val base = shopPage * SHOP_ROWS
        for (i in 0 until SHOP_ROWS) {
            val id = base + i
            if (id >= UPGRADES.size) break
            if (shopRows[i].contains(x, y)) {
                when {
                    upgrades.isMaxed(id) -> sound.play(SoundManager.HURT, 0.4f, 1.6f)
                    upgrades.buy(id) -> {
                        refreshCoins()
                        saveProgress()
                        sound.play(SoundManager.BUY, 0.9f)
                        buzz(30)
                    }
                    else -> sound.play(SoundManager.HURT, 0.5f, 1.5f)
                }
                return
            }
        }
    }

    // ---------------- 渲染 ----------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (vw == 0f || released) return

        canvas.save()
        if (shake > 0.01f) {
            val m = dp(13f) * shake
            canvas.translate(
                (Random.nextFloat() - 0.5f) * 2f * m,
                (Random.nextFloat() - 0.5f) * 2f * m
            )
        }

        val bg = bgBitmap
        if (bg != null && !bg.isRecycled) {
            // 等比缩放铺满屏幕（居中裁剪）
            val scale = max(vw / bg.width, vh / bg.height)
            val w = bg.width * scale
            val h = bg.height * scale
            val left = (vw - w) * 0.5f
            val top = (vh - h) * 0.5f
            bgDst.set(left, top, left + w, top + h)
            canvas.drawBitmap(bg, null, bgDst, spritePaint)
        } else {
            canvas.drawRect(0f, 0f, vw, vh, bgPaint)
        }
        drawNebula(canvas)
        drawStars(canvas)

        val inGame = state == State.PLAYING || state == State.PAUSED ||
                state == State.LEVEL_INTRO || state == State.LEVEL_CLEAR
        if (inGame) {
            drawPowerUps(canvas)
            drawBullets(canvas)
            drawEnemies(canvas)
            drawLaser(canvas)
            if (state != State.GAME_OVER) drawPlayer(canvas)
            if (state != State.GAME_OVER) drawWingmen(canvas)
        }
        drawParticles(canvas)
        drawRings(canvas)
        drawFloatTexts(canvas)
        canvas.restore()

        if (hitFlash > 0f) {
            paint.style = Paint.Style.FILL
            paint.color = (hitFlash * 0.45f * 255f).toInt().coerceIn(0, 255).shl(24) or 0x00FF3D00
            canvas.drawRect(0f, 0f, vw, vh, paint)
        }

        if (inGame) drawHud(canvas)
        drawOverlay(canvas)
        drawAchToast(canvas)
    }

    // ---------------- 伪 3D 工具 ----------------

    /** 消失点：画面略偏上，营造「朝前下方飞」的视角 */
    private val vpX get() = vw * 0.5f
    private val vpY get() = vh * 0.42f

    /** 透视投影系数：z 越小越近，k 越大 */
    private fun projK(z: Float) = focal / max(z, zNear)

    /**
     * 景深因子：0 = 最远（屏幕顶部），1 = 最近（屏幕底部）。
     * 用于让远处物体缩小、变暗、雾化。
     */
    private fun depthAt(y: Float) = (y / max(1f, vh)).coerceIn(0f, 1f)

    /** 按深度取雾化档位；depth 越小雾越浓 */
    private fun fogIdxFor(depth: Float): Int {
        val t = (1f - depth).coerceIn(0f, 1f)
        return (t * (FOG_LEVELS - 1)).toInt().coerceIn(0, FOG_LEVELS - 1)
    }

    /** 按深度算视觉缩放：远处小、近处大 */
    private fun depthScaleAt(depth: Float) = depthScaleMin + (1f - depthScaleMin) * depth

    /** 按关卡背景色重建雾化画笔（颜色变了必须重建） */
    private fun buildFogPaints() {
        val fr = ((fogColor shr 16) and 0xFF) / 255f
        val fg = ((fogColor shr 8) and 0xFF) / 255f
        val fb = (fogColor and 0xFF) / 255f
        for (i in 0 until FOG_LEVELS) {
            val t = i / (FOG_LEVELS - 1f) * fogMax
            val inv = 1f - t
            // out = in * (1-t) + 雾色 * t
            val cm = ColorMatrix(
                floatArrayOf(
                    inv, 0f, 0f, 0f, fr * t * 255f,
                    0f, inv, 0f, 0f, fg * t * 255f,
                    0f, 0f, inv, 0f, fb * t * 255f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            fogPaints[i].colorFilter = ColorMatrixColorFilter(cm)
            fogPaints[i].alpha = 255
        }
    }

    /**
     * 伪 3D 绘制一艘飞船：
     *   ① 向下偏移的黑色剪影 = 投影（离地悬浮感）
     *   ② 略微下移的黑色剪影 = 挤出厚度（机身有立体边）
     *   ③ 主体贴图，带横滚与纵向压扁（压坡度）
     *
     * @param depth 视觉缩放（1 = 最近）
     * @param roll  横滚角（弧度）
     * @param shadow 投影强度 0..1
     * @param fogIdx 景深雾化档位；-1 表示不雾化
     */
    private fun drawShip3D(
        c: Canvas,
        bmp: Bitmap,
        x: Float,
        y: Float,
        r: Float,
        alpha: Int = 255,
        roll: Float = 0f,
        depth: Float = 1f,
        shadow: Float = 1f,
        fogIdx: Int = -1
    ) {
        val half = bmp.width * 0.5f * (r / sp.planeBase) * depth
        if (half < 0.6f) return

        // ① 投影：压扁 + 下移，纯黑剪影
        if (shadow > 0.02f) {
            val sy = y + dp(4.5f) * depth
            val squash = 0.52f
            shadowPaint.alpha = (alpha * 0.40f * shadow).toInt().coerceIn(0, 255)
            dst.set(x - half * 0.94f, sy - half * squash, x + half * 0.94f, sy + half * squash)
            c.drawBitmap(bmp, null, dst, shadowPaint)
        }

        // ② 厚度层：略微下移的剪影，露出底边形成挤出感
        val t = dp(1.7f) * depth
        shadowPaint.alpha = (alpha * 0.8f).toInt().coerceIn(0, 255)
        dst.set(x - half, y - half + t, x + half, y + half + t)
        c.drawBitmap(bmp, null, dst, shadowPaint)

        // ③ 主体
        val body = if (fogIdx >= 0) fogPaints[fogIdx] else spritePaint
        body.alpha = alpha
        if (abs(roll) > 0.002f) {
            c.save()
            c.rotate(roll * (180f / PI.toFloat()), x, y)
            c.scale(1f, 1f - abs(roll) * 0.24f, x, y)   // 压坡度时纵向收缩
            dst.set(x - half, y - half, x + half, y + half)
            c.drawBitmap(bmp, null, dst, body)
            c.restore()
        } else {
            dst.set(x - half, y - half, x + half, y + half)
            c.drawBitmap(bmp, null, dst, body)
        }
        body.alpha = 255
    }

    // ---------------- 背景层 ----------------

    private fun drawNebula(c: Canvas) {
        for (n in nebs) {
            // 星云越往下越靠近镜头 → 一边变大一边变亮，强化纵深
            val d = 0.86f + 0.26f * depthAt(n.y)
            spritePaint.alpha = (n.alpha * (0.7f + 0.3f * depthAt(n.y))).toInt().coerceIn(0, 255)
            val half = n.scale * 0.5f * d
            dst.set(n.x - half, n.y - half, n.x + half, n.y + half)
            val bmp = when (n.variant) {
                0 -> sp.nebulaA
                1 -> sp.nebulaB
                else -> sp.nebulaC
            }
            c.drawBitmap(bmp, null, dst, spritePaint)
        }
        spritePaint.alpha = 255
    }

    /**
     * 透视星空：每颗星按 k = 焦距 / z 投到屏幕上，
     * 再从「更远一点」的位置拉一条线到当前位置，形成朝镜头飞来的拉丝。
     */
    private fun drawStars(c: Canvas) {
        val cx = vpX
        val cy = vpY
        val margin = dp(24f)
        for (s in stars) {
            val zc = max(s.z, zNear)
            val k = projK(zc)
            val sx = cx + s.dx * k
            val sy = cy + s.dy * k
            if (sx < -margin || sx > vw + margin || sy < -margin || sy > vh + margin) continue

            // 拖尾起点
            val kt = projK(min(zFar * 1.2f, zc * trailK))
            val tx = cx + s.dx * kt
            val ty = cy + s.dy * kt

            val near = (1f - zc / zFar).coerceIn(0f, 1f)     // 0 远 → 1 近
            val twinkle = 0.62f + 0.38f * sin(s.phase)
            val a = (s.alpha * (0.30f + 0.70f * near) * twinkle).toInt().coerceIn(0, 255)
            if (a <= 3) continue

            warpPaint.color = a.shl(24) or 0x00BFE9FF
            warpPaint.strokeWidth = dp(0.9f) + near * dp(2.3f)
            c.drawLine(tx, ty, sx, sy, warpPaint)

            // 近处的星再叠一颗亮核
            if (near > 0.5f) {
                val half = s.size * (0.9f + near * 1.7f) * 1.8f
                spritePaint.alpha = a
                dst.set(sx - half, sy - half, sx + half, sy + half)
                c.drawBitmap(sp.starBmp, null, dst, spritePaint)
            }
        }
        spritePaint.alpha = 255
    }

    private fun drawPlaneBmp(c: Canvas, bmp: Bitmap, x: Float, y: Float, r: Float, alpha: Int = 255) {
        spritePaint.alpha = alpha
        val half = bmp.width * 0.5f * (r / sp.planeBase)
        dst.set(x - half, y - half, x + half, y + half)
        c.drawBitmap(bmp, null, dst, spritePaint)
        spritePaint.alpha = 255
    }

    private fun drawBulletBmp(c: Canvas, bmp: Bitmap, x: Float, y: Float, r: Float, alpha: Int = 255) {
        spritePaint.alpha = alpha
        val half = bmp.width * 0.5f * (r / sp.bulletBase)
        dst.set(x - half, y - half, x + half, y + half)
        c.drawBitmap(bmp, null, dst, spritePaint)
        spritePaint.alpha = 255
    }

    private fun drawPlayer(c: Canvas) {
        if (shield > 0f) {
            val pulse = 0.55f + 0.45f * sin(elapsed * 8f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(3f)
            paint.color = ((110 * pulse).toInt().coerceIn(0, 255)).shl(24) or 0x0069F0AE
            c.drawCircle(px, py, playerR * 2.2f, paint)
        }
        if (invincible > 0f && (invincible * 8f).toInt() % 2 == 0) return

        paint.style = Paint.Style.FILL
        paint.color = 0x3333E5FF
        c.drawCircle(px, py, playerR * 2.1f, paint)

        // 玩家永远在最前层：不做雾化、不缩小，只吃横滚和立体投影
        drawShip3D(c, sp.player, px, py, playerR, roll = playerRoll, depth = 1f, shadow = 1f)
    }

    /** 敌机横滚：横向速度压坡度 + 一点自然摆动，让机队"活"起来 */
    private fun enemyRoll(e: Enemy): Float {
        val fromVx = e.vx * 0.0018f
        val sway = sin(e.phase * 1.7f) * 0.06f
        val r = (fromVx + sway).coerceIn(-0.42f, 0.42f)
        return if (e.kind == K_BOSS) r * 0.5f else r
    }

    private fun drawWingmen(c: Canvas) {
        val spec = wingmanSpec(gear.selectedWingman)
        if (spec.count <= 0) return
        paint.style = Paint.Style.FILL
        val r = dp(9f)
        for (i in 0 until spec.count) {
            val wx = px + wingOffsetX(i, spec.count)
            val wy = py + dp(34f)
            wingPath.reset()
            wingPath.moveTo(wx, wy - r)
            wingPath.lineTo(wx - r * 0.9f, wy + r * 0.8f)
            wingPath.lineTo(wx, wy + r * 0.25f)
            wingPath.lineTo(wx + r * 0.9f, wy + r * 0.8f)
            wingPath.close()
            paint.color = spec.color
            c.drawPath(wingPath, paint)
            paint.color = 0x88FFFFFF.toInt()
            c.drawCircle(wx, wy + r * 0.75f, dp(2.4f), paint)
        }
    }

    private fun drawLaser(c: Canvas) {
        if (upgrades.selectedWeapon != W_LASER) return
        if (state != State.PLAYING) return
        val half = dp(9f) + weaponLevel * dp(2.2f)
        val noseY = py - playerR * 1.5f
        val pulse = 0.75f + 0.25f * sin(elapsed * 30f)

        paint.style = Paint.Style.FILL
        paint.color = ((70 * pulse).toInt().coerceIn(0, 255)).shl(24) or 0x00E040FB
        c.drawRect(px - half * 1.9f, 0f, px + half * 1.9f, noseY, paint)

        paint.color = ((160 * pulse).toInt().coerceIn(0, 255)).shl(24) or 0x00E040FB
        c.drawRect(px - half, 0f, px + half, noseY, paint)

        paint.color = 0xFFFFFFFF.toInt()
        c.drawRect(px - half * 0.34f, 0f, px + half * 0.34f, noseY, paint)
    }

    private fun drawEnemies(c: Canvas) {
        for (e in enemies) {
            val bmp = if (e.kind == K_BOSS) {
                sp.bossSprites[e.bossType] ?: sp.enemies[K_BOSS]
            } else {
                sp.enemies[e.kind]
            } ?: continue

            // 景深：越靠屏幕上方越远 → 越小、越暗、越雾
            val depth = depthAt(e.y)
            val dScale = depthScaleAt(depth)
            val fogIdx = fogIdxFor(depth)

            if (e.kind == K_BOSS || e.r > dp(18f)) {
                paint.style = Paint.Style.FILL
                paint.color = 0x33FF1744
                c.drawCircle(e.x, e.y, e.r * 1.7f * dScale, paint)
            }

            if (e.kind == K_SNIPER && e.charging) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1.5f)
                paint.color = 0x99FF5252.toInt()
                c.drawLine(e.x, e.y, px, py, paint)
            }

            drawShip3D(
                c, bmp, e.x, e.y, e.r,
                roll = enemyRoll(e),
                depth = dScale,
                shadow = 0.45f + 0.55f * depth,
                fogIdx = fogIdx
            )

            if (e.shielded) {
                val f = e.shieldHp.toFloat() / max(1, e.shieldMax)
                spritePaint.alpha = (90 + 100 * f).toInt().coerceIn(0, 255)
                val half = e.r * 2.3f * dScale
                dst.set(e.x - half, e.y - half, e.x + half, e.y + half)
                c.drawBitmap(sp.shieldRing, null, dst, spritePaint)
                spritePaint.alpha = 255
            }

            if (e.flash > 0f) {
                val a = (e.flash * 190f).toInt().coerceIn(0, 255)
                paint.style = Paint.Style.FILL
                paint.color = a.shl(24) or 0x00FFFFFF
                c.drawCircle(e.x, e.y, e.r * 0.9f * dScale, paint)
            }

            if (e.kind == K_TANK || e.kind == K_ELITE || e.kind == K_SHIELDED ||
                e.kind == K_SPLITTER || e.kind == K_SNIPER || e.kind == K_ORBITER
            ) {
                drawMiniBar(c, e)
            }
        }
    }

    private fun drawMiniBar(c: Canvas, e: Enemy) {
        val w = e.r * 2f
        val top = e.y - e.r * 1.9f
        paint.style = Paint.Style.FILL
        paint.color = 0x66000000
        c.drawRect(e.x - w / 2f, top, e.x + w / 2f, top + dp(5f), paint)
        paint.color = 0xFFFFB300.toInt()
        c.drawRect(e.x - w / 2f, top, e.x - w / 2f + w * (e.hp.toFloat() / e.maxHp), top + dp(5f), paint)
    }

    private fun drawBullets(c: Canvas) {
        for (b in bullets) {
            if (b.missile) drawBulletBmp(c, sp.bulletMissile, b.x, b.y, b.r)
            else if (b.friendly) drawBulletBmp(c, sp.bulletFriendly, b.x, b.y, b.r)
            else drawBulletBmp(c, sp.bulletEnemy, b.x, b.y, b.r)
        }
    }

    private fun drawPowerUps(c: Canvas) {
        for (p in powerups) {
            val bmp = sp.powerups[p.kind] ?: continue
            val depth = depthAt(p.y)
            val s = depthScaleAt(depth)
            val bob = sin(p.phase) * dp(3f)
            val half = bmp.width * 0.5f * s
            val fp = fogPaints[fogIdxFor(depth)]
            fp.alpha = 255
            dst.set(p.x - half, p.y + bob - half, p.x + half, p.y + bob + half)
            c.drawBitmap(bmp, null, dst, fp)
        }
    }

    private fun drawParticles(c: Canvas) {
        paint.style = Paint.Style.FILL
        for (p in particles) {
            val f = (p.life / p.maxLife).coerceIn(0f, 1f)
            val a = (f * 255f).toInt()
            paint.color = a.shl(24) or (p.color and 0x00FFFFFF)
            c.drawCircle(p.x, p.y, p.size * (0.4f + f * 0.6f), paint)
        }
    }

    private fun drawRings(c: Canvas) {
        paint.style = Paint.Style.STROKE
        for (r in rings) {
            val a = (r.life.coerceIn(0f, 1f) * 200f).toInt()
            paint.color = a.shl(24) or (r.color and 0x00FFFFFF)
            paint.strokeWidth = r.width * (0.5f + r.life.coerceIn(0f, 1f))
            c.drawCircle(r.x, r.y, r.r, paint)
        }
    }

    private fun drawFloatTexts(c: Canvas) {
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = bold
        for (t in texts) {
            val a = (t.life.coerceIn(0f, 1f) * 255f).toInt()
            paint.color = a.shl(24) or (t.color and 0x00FFFFFF)
            paint.textSize = t.size
            c.drawText(t.text, t.x, t.y, paint)
        }
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawHud(c: Canvas) {
        val top = topInset
        paint.style = Paint.Style.FILL
        paint.typeface = bold
        paint.textAlign = Paint.Align.LEFT

        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = dp(28f)
        c.drawText(hudScore, dp(18f), top + dp(40f), paint)

        paint.textSize = dp(13f)
        paint.color = 0xFF90CAF9.toInt()
        c.drawText(hudHigh, dp(18f), top + dp(60f), paint)

        if (hudCombo.isNotEmpty()) {
            paint.textSize = dp(15f)
            paint.color = 0xFF69F0AE.toInt()
            c.drawText(hudCombo, dp(18f), top + dp(80f), paint)
        }

        paint.textSize = dp(12f)
        paint.color = 0xFFB0BEC5.toInt()
        c.drawText(hudLevel, dp(18f), top + dp(100f), paint)

        paint.textAlign = Paint.Align.RIGHT
        paint.textSize = dp(16f)
        paint.color = 0xFFFFB300.toInt()
        c.drawText(hudCoins, vw - dp(74f), top + dp(40f), paint)
        paint.textSize = dp(12f)
        c.drawText("金币", vw - dp(74f), top + dp(58f), paint)

        paint.textSize = dp(11f)
        paint.color = weaponSpec(upgrades.selectedWeapon).color
        c.drawText(weaponSpec(upgrades.selectedWeapon).shortName, vw - dp(74f), top + dp(76f), paint)
        paint.textAlign = Paint.Align.LEFT

        val livesY = top + dp(122f)
        for (i in 0 until lives) {
            drawPlaneBmp(c, sp.player, dp(26f) + i * dp(24f), livesY, dp(9f))
        }

        if (!swarmDone && !isDaily) {
            val w = vw - dp(48f)
            val x0 = dp(24f)
            val y0 = top + dp(134f)
            val frac = (1f - swarmTimer / max(0.01f, level.swarmSeconds)).coerceIn(0f, 1f)
            paint.style = Paint.Style.FILL
            paint.color = 0x66000000
            c.drawRect(x0, y0, x0 + w, y0 + dp(6f), paint)
            paint.color = 0xFF4FC3F7.toInt()
            c.drawRect(x0, y0, x0 + w * frac, y0 + dp(6f), paint)
        }

        var tipY = top + dp(154f)
        if (magnetTimer > 0f) {
            paint.textSize = dp(11f)
            paint.color = 0xFFBA68C8.toInt()
            c.drawText("磁力 ${magnetTimer.toInt()}s", dp(18f), tipY, paint)
            tipY += dp(15f)
        }
        if (slowTimer > 0f) {
            paint.textSize = dp(11f)
            paint.color = 0xFF4DD0E1.toInt()
            c.drawText("减速 ${slowTimer.toInt()}s", dp(18f), tipY, paint)
        }

        paint.style = Paint.Style.FILL
        paint.color = 0x33FFFFFF
        c.drawCircle(pauseBtnX, pauseBtnY, dp(24f), paint)
        paint.color = 0xFFFFFFFF.toInt()
        c.drawRect(pauseBtnX - dp(7f), pauseBtnY - dp(8f), pauseBtnX - dp(2f), pauseBtnY + dp(8f), paint)
        c.drawRect(pauseBtnX + dp(2f), pauseBtnY - dp(8f), pauseBtnX + dp(7f), pauseBtnY + dp(8f), paint)

        paint.style = Paint.Style.FILL
        paint.color = if (bombs > 0) 0x44FFD54F else 0x22FFFFFF
        c.drawCircle(bombBtnX, bombBtnY, dp(30f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.5f)
        paint.color = if (bombs > 0) 0xFFFFD54F.toInt() else 0x66FFFFFF
        c.drawCircle(bombBtnX, bombBtnY, dp(30f), paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.color = if (bombs > 0) 0xFFFFF176.toInt() else 0x66FFFFFF
        paint.textSize = dp(20f)
        c.drawText("B", bombBtnX, bombBtnY - dp(2f), paint)
        paint.textSize = dp(12f)
        c.drawText(hudBomb, bombBtnX, bombBtnY + dp(16f), paint)
        paint.textAlign = Paint.Align.LEFT

        if (bossAlive) {
            val b = bossRef
            if (b != null && b.hp > 0) {
                val w = vw - dp(48f)
                val x0 = dp(24f)
                val y0 = top + dp(14f)
                paint.style = Paint.Style.FILL
                paint.color = 0x88000000.toInt()
                c.drawRect(x0, y0, x0 + w, y0 + dp(9f), paint)
                paint.color = if (bossPhase >= 3) 0xFFFF1744.toInt() else 0xFFFF5252.toInt()
                c.drawRect(x0, y0, x0 + w * (b.hp.toFloat() / b.maxHp), y0 + dp(9f), paint)
                paint.color = 0x66FFFFFF
                c.drawRect(x0, y0, x0 + w, y0 + dp(2f), paint)
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = dp(11f)
                paint.color = 0xFFFFCDD2.toInt()
                val bname = if (isDaily) bossSpec(b.bossType).name else level.bossName
                c.drawText(bname, vw / 2f, y0 + dp(23f), paint)
                paint.textAlign = Paint.Align.LEFT
            }
        }
    }

    private fun drawButton(c: Canvas, r: RectF, label: String, fill: Int, stroke: Int, textColor: Int, textSize: Float) {
        val rad = r.height() / 2f
        paint.style = Paint.Style.FILL
        paint.color = fill
        c.drawRoundRect(r, rad, rad, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.5f)
        paint.color = stroke
        c.drawRoundRect(r, rad, rad, paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = bold
        paint.color = textColor
        paint.textSize = textSize
        paint.getFontMetrics(fm)
        c.drawText(label, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawPanel(c: Canvas, x: Float, y: Float, w: Float, h: Float, fill: Int, stroke: Int) {
        val r = RectF(x, y, x + w, y + h)
        paint.style = Paint.Style.FILL
        paint.color = fill
        c.drawRoundRect(r, dp(12f), dp(12f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = stroke
        c.drawRoundRect(r, dp(12f), dp(12f), paint)
    }

    private fun drawAchToast(c: Canvas) {
        if (achToastTimer <= 0f || hudAchToast.isEmpty()) return
        val a = (min(1f, achToastTimer / 0.35f) * 255f).toInt().coerceIn(0, 255)
        val w = min(vw - dp(48f), dp(340f))
        val x = (vw - w) / 2f
        val y = topInset + dp(16f)
        val h = dp(56f)

        paint.style = Paint.Style.FILL
        paint.color = (a * 0.88f).toInt().coerceIn(0, 255).shl(24) or 0x00101020
        c.drawRoundRect(RectF(x, y, x + w, y + h), dp(12f), dp(12f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = a.shl(24) or (achToastColor and 0x00FFFFFF)
        c.drawRoundRect(RectF(x, y, x + w, y + h), dp(12f), dp(12f), paint)

        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = bold
        paint.color = a.shl(24) or (achToastColor and 0x00FFFFFF)
        paint.textSize = dp(15f)
        c.drawText(hudAchToast, x + dp(16f), y + dp(23f), paint)
        paint.color = a.shl(24) or 0x00CFD8DC
        paint.textSize = dp(12f)
        c.drawText(hudAchToastSub, x + dp(16f), y + dp(42f), paint)
    }

    private fun drawOverlay(c: Canvas) {
        if (state == State.PLAYING) return

        paint.style = Paint.Style.FILL
        paint.color = 0xCC04060F.toInt()
        c.drawRect(0f, 0f, vw, vh, paint)
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = bold

        when (state) {
            State.LOGIN -> drawLogin(c)
            State.REGISTER -> drawRegister(c)
            State.READY -> drawMenu(c)
            State.LEVEL_INTRO -> drawLevelIntro(c)
            State.LEVEL_CLEAR -> drawLevelClear(c)
            State.GAME_OVER -> drawGameOver(c)
            State.GAME_COMPLETE -> drawComplete(c)
            State.PAUSED -> drawPaused(c)
            State.SHOP -> drawShop(c)
            State.ACHIEVEMENTS -> drawAchievements(c)
            State.STATS -> drawStats(c)
            State.SETTINGS -> drawSettings(c)
            State.SHIP_SELECT -> drawShipSelect(c)
            State.GEAR_SHOP -> drawGearShop(c)
            State.REWARDS -> drawRewards(c)
            State.BOX -> drawBox(c)
            State.STARS -> drawStarLevels(c)
            else -> Unit
        }

        // 开箱 / 领奖的结果弹层（画在最上层）
        drawBoxResult(c)
        paint.textAlign = Paint.Align.LEFT
    }

    // ---------------- 登录 / 注册界面 ----------------

    private fun drawAuthField(
        c: Canvas, r: RectF, label: String, value: String,
        password: Boolean, active: Boolean, hint: String
    ) {
        val fill = if (active) 0x3329B6F6 else 0x1AFFFFFF
        val stroke = if (active) 0xFF29B6F6.toInt() else 0x44FFFFFF
        drawPanel(c, r.left, r.top, r.width(), r.height(), fill, stroke)

        paint.textAlign = Paint.Align.LEFT
        paint.color = 0xFF90CAF9.toInt()
        paint.textSize = dp(10f)
        c.drawText(label, r.left + dp(14f), r.top + dp(15f), paint)

        val shown = if (value.isEmpty()) hint else if (password) "•".repeat(value.length) else value
        paint.color = if (value.isEmpty()) 0x55FFFFFF else 0xFFFFFFFF.toInt()
        paint.textSize = dp(16f)
        val ty = r.top + dp(38f)
        c.drawText(shown, r.left + dp(14f), ty, paint)

        if (active && sin(cursorT * 6f) > 0f) {
            val w = paint.measureText(shown)
            paint.style = Paint.Style.FILL
            paint.color = 0xFF29B6F6.toInt()
            c.drawRect(r.left + dp(15f) + w, ty - dp(14f), r.left + dp(16.5f) + w, ty + dp(3f), paint)
        }
        paint.textAlign = Paint.Align.CENTER
    }

    private fun drawAuthHeader(c: Canvas, title: String, sub: String) {
        paint.textAlign = Paint.Align.CENTER
        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = dp(26f)
        c.drawText(title, vw / 2f, topInset + dp(46f), paint)
        paint.color = 0xFF90CAF9.toInt()
        paint.textSize = dp(12f)
        c.drawText(sub, vw / 2f, topInset + dp(68f), paint)
    }

    private fun drawAuthMessage(c: Canvas, bottomY: Float) {
        if (loginMsg.isEmpty()) return
        paint.textAlign = Paint.Align.CENTER
        paint.color = if (loginMsgOk) 0xFF69F0AE.toInt() else 0xFFFF6E6E.toInt()
        paint.textSize = dp(13f)
        c.drawText(loginMsg, vw / 2f, bottomY, paint)
    }

    private fun drawLogin(c: Canvas) {
        if (imeInset < dp(40f)) {
            drawPlaneBmp(c, sp.player, vw * 0.5f, topInset + dp(112f), dp(20f))
        }
        drawAuthHeader(c, "账号登录", "本地账号 · 存档保存在本机")

        drawAuthField(c, btnLgUser, "用户名", loginUser, false, activeField == F_LG_USER, "请输入用户名")
        drawAuthField(c, btnLgPass, "密码", loginPass, true, activeField == F_LG_PASS, "请输入密码")

        drawButton(c, btnLgSubmit, "登 录", 0x5529B6F6, 0xFF29B6F6.toInt(), 0xFFFFFFFF.toInt(), dp(17f))
        drawButton(c, btnLgToReg, "没有账号？点这里注册", 0x00FFFFFF, 0x44FFFFFF, 0xFF90CAF9.toInt(), dp(13f))

        drawAuthMessage(c, btnLgToReg.bottom + dp(26f))

        paint.textAlign = Paint.Align.CENTER
        paint.color = 0xFF78909C.toInt()
        paint.textSize = dp(11f)
        val n = accounts.names().size
        c.drawText(
            if (n == 0) "首次使用请先注册一个账号" else "本机已有 $n 个账号",
            vw / 2f, vh - dp(28f), paint
        )
    }

    private fun drawRegister(c: Canvas) {
        drawAuthHeader(c, "注册新账号", "用户名 2-16 位 · 密码至少 3 位")

        drawAuthField(c, btnRgUser, "用户名", regUser, false, activeField == F_RG_USER, "2-16 位，字母/数字/中文")
        drawAuthField(c, btnRgPass, "密码", regPass, true, activeField == F_RG_PASS, "至少 3 位")
        drawAuthField(c, btnRgPass2, "确认密码", regPass2, true, activeField == F_RG_PASS2, "再输入一次密码")

        drawButton(c, btnRgSubmit, "注 册", 0x5569F0AE, 0xFF69F0AE.toInt(), 0xFFFFFFFF.toInt(), dp(17f))
        drawButton(c, btnRgToLogin, "已有账号？返回登录", 0x00FFFFFF, 0x44FFFFFF, 0xFF90CAF9.toInt(), dp(13f))

        drawAuthMessage(c, btnRgToLogin.bottom + dp(26f))
    }

    private fun drawMenu(c: Canvas) {
        val bob = sin(menuTime * 1.6f) * dp(6f)
        // 主菜单的英雄机：慢慢左右摇摆，配合立体投影，像悬停在星空里
        drawShip3D(
            c, sp.player, vw * 0.5f, vh * 0.075f + bob, dp(21f),
            roll = sin(menuTime * 0.9f) * 0.20f, depth = 1f, shadow = 1f
        )

        paint.color = 0xFF4FC3F7.toInt()
        paint.textSize = dp(32f)
        c.drawText("飞 机 大 战", vw / 2f, vh * 0.145f, paint)

        paint.color = 0xFF90CAF9.toInt()
        paint.textSize = dp(11f)
        c.drawText("12 关战役 · 13 种敌机 · 5 种 BOSS · 5 种武器", vw / 2f, vh * 0.180f, paint)

        paint.color = 0xFF69F0AE.toInt()
        paint.textSize = dp(11f)
        c.drawText("当前账号：${accountName ?: "-"}", vw / 2f, vh * 0.206f, paint)

        val d = dailyChallenge()
        val checkinReady = !rewards.checkin.claimedToday(todayKey())
        val labels = arrayOf(
            "战役模式", "无尽模式", "BOSS 连战", "每日挑战", "战机选择",
            "商店", "成就", "统计", "设置", "奖励中心"
        )
        val subs = arrayOf(
            "12 关 · 虚空王座",
            "生存挑战",
            "连战 5 BOSS",
            d.title,
            shipSpec(ships.selected).name,
            "金币 ${upgrades.coins}",
            "${ach.unlockedCount()}/${ACHIEVEMENTS.size}",
            "查看战绩",
            "音效/震动/音乐",
            if (checkinReady) "签到 · 任务 · 开箱" else "★ ${rewards.stars.total()} · 钥匙 ${rewards.keys}"
        )
        val fills = intArrayOf(
            0x5529B6F6, 0x55E040FB, 0x55FF5252, 0x55FFB300, 0x5569F0AE,
            0x55FFAB40, 0x5569F0AE, 0x5580DEEA, 0x55B0BEC5, 0x55FF7043
        )
        val strokes = intArrayOf(
            0xFF29B6F6.toInt(), 0xFFE040FB.toInt(), 0xFFFF5252.toInt(),
            0xFFFFB300.toInt(), 0xFF69F0AE.toInt(), 0xFFFFAB40.toInt(),
            0xFF69F0AE.toInt(), 0xFF80DEEA.toInt(), 0xFFB0BEC5.toInt(),
            0xFFFF7043.toInt()
        )
        for (i in 0 until MENU_BUTTONS) {
            val r = menuButtons[i]
            drawButton(c, r, "", fills[i], strokes[i], 0xFFFFFFFF.toInt(), dp(15f))
            paint.color = 0xFFFFFFFF.toInt()
            paint.textSize = dp(15f)
            c.drawText(labels[i], r.centerX(), r.centerY() - dp(3f), paint)
            paint.color = 0xFF90CAF9.toInt()
            paint.textSize = dp(10f)
            c.drawText(subs[i], r.centerX(), r.centerY() + dp(14f), paint)
        }

        paint.color = 0xFFFFD54F.toInt()
        paint.textSize = dp(13f)
        c.drawText(hudHighBig, vw / 2f, vh * 0.965f, paint)
    }

    private fun drawLevelIntro(c: Canvas) {
        when {
            isDaily -> {
                val d = daily ?: return
                paint.color = d.color
                paint.textSize = dp(38f)
                c.drawText("每日挑战", vw / 2f, vh * 0.32f, paint)
                paint.color = 0xFFFFFFFF.toInt()
                paint.textSize = dp(26f)
                c.drawText(d.title, vw / 2f, vh * 0.40f, paint)
                paint.color = 0xFF90CAF9.toInt()
                paint.textSize = dp(15f)
                c.drawText(d.desc, vw / 2f, vh * 0.46f, paint)
                paint.color = 0xFFFFB300.toInt()
                paint.textSize = dp(14f)
                c.drawText("今日最佳 $dailyBest", vw / 2f, vh * 0.52f, paint)
            }
            isEndless -> {
                paint.color = 0xFFE040FB.toInt()
                paint.textSize = dp(38f)
                c.drawText("无尽模式", vw / 2f, vh * 0.32f, paint)
                paint.color = 0xFFFFFFFF.toInt()
                paint.textSize = dp(18f)
                c.drawText("难度持续上升 · 无终点", vw / 2f, vh * 0.40f, paint)
                paint.color = 0xFF90CAF9.toInt()
                paint.textSize = dp(14f)
                c.drawText("每 45 秒出现一个 BOSS", vw / 2f, vh * 0.46f, paint)
                paint.color = 0xFF69F0AE.toInt()
                paint.textSize = dp(14f)
                c.drawText("战机 · ${shipSpec(runShip).name}", vw / 2f, vh * 0.52f, paint)
            }
            isBossRush -> {
                paint.color = 0xFFFF5252.toInt()
                paint.textSize = dp(38f)
                c.drawText("BOSS 连战", vw / 2f, vh * 0.32f, paint)
                paint.color = 0xFFFFFFFF.toInt()
                paint.textSize = dp(18f)
                c.drawText("连续讨伐 ${BOSS_SPECS.size} 个 BOSS", vw / 2f, vh * 0.40f, paint)
                paint.color = 0xFF90CAF9.toInt()
                paint.textSize = dp(14f)
                c.drawText("每击破一个回复 1 点生命", vw / 2f, vh * 0.46f, paint)
                paint.color = 0xFF69F0AE.toInt()
                paint.textSize = dp(14f)
                c.drawText("战机 · ${shipSpec(runShip).name}", vw / 2f, vh * 0.52f, paint)
            }
            else -> {
                paint.color = 0xFF4FC3F7.toInt()
                paint.textSize = dp(32f)
                c.drawText(level.name, vw / 2f, vh * 0.32f, paint)
                paint.color = 0xFFFFFFFF.toInt()
                paint.textSize = dp(16f)
                c.drawText(level.subtitle, vw / 2f, vh * 0.39f, paint)
                paint.color = 0xFF90CAF9.toInt()
                paint.textSize = dp(14f)
                c.drawText("目标 · 击败 ${level.bossName}", vw / 2f, vh * 0.45f, paint)
                paint.color = 0xFF69F0AE.toInt()
                paint.textSize = dp(13f)
                c.drawText("进度 ${levelIndex + 1} / ${LEVELS.size} · 战机 ${shipSpec(runShip).name}", vw / 2f, vh * 0.51f, paint)
            }
        }

        val pulse = 0.5f + 0.5f * sin(menuTime * 5f)
        paint.color = ((180 + 75 * pulse).toInt().coerceIn(0, 255)).shl(24) or 0x00FFFFFF
        paint.textSize = dp(18f)
        c.drawText("触摸屏幕开始", vw / 2f, vh * 0.64f, paint)

        paint.color = 0xFF90CAF9.toInt()
        paint.textSize = dp(12f)
        c.drawText("拖动屏幕控制飞机 · 自动开火", vw / 2f, vh * 0.70f, paint)

        paint.color = 0xFF78909C.toInt()
        paint.textSize = dp(12f)
        c.drawText("按返回键可退出到主菜单", vw / 2f, vh * 0.76f, paint)
    }

    private fun drawLevelClear(c: Canvas) {
        paint.color = 0xFF69F0AE.toInt()
        paint.textSize = dp(36f)
        c.drawText(hudClearTitle, vw / 2f, vh * 0.24f, paint)

        // ---- 关卡三星 ----
        if (runStars > 0) {
            drawStarScore(c, vw / 2f, vh * 0.325f, runStars, 3, dp(16f), dp(7f))
            paint.color = if (starJustImproved) 0xFF69F0AE.toInt() else 0xFF90CAF9.toInt()
            paint.textSize = dp(11.5f)
            val tip = buildString {
                append("★ 通关")
                if (runStars >= 2) append("  ★ 无伤")
                if (runStars >= 3) append("  ★ 得分 ${starScoreTarget(levelIndex)}")
                if (starJustImproved) append("   · 星纪录刷新！")
            }
            c.drawText(tip.toString(), vw / 2f, vh * 0.355f, paint)
        }

        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = dp(17f)
        c.drawText(hudClearInfo, vw / 2f, vh * 0.41f, paint)

        paint.color = 0xFFFFD54F.toInt()
        paint.textSize = dp(17f)
        c.drawText("总分 $score", vw / 2f, vh * 0.46f, paint)

        paint.color = 0xFF90CAF9.toInt()
        paint.textSize = dp(14f)
        c.drawText("金币余额 ${upgrades.coins}", vw / 2f, vh * 0.51f, paint)

        if (newRecord) {
            val pulse = 0.6f + 0.4f * sin(menuTime * 6f)
            paint.color = ((255 * pulse).toInt().coerceIn(0, 255)).shl(24) or 0x00FFD54F
            paint.textSize = dp(16f)
            c.drawText("★ 新纪录 ★", vw / 2f, vh * 0.56f, paint)
        }

        if (clearTimer <= 0f) {
            val label = if (levelIndex + 1 >= LEVELS.size) "查看结果" else "进入下一关"
            drawButton(c, btnClearNext, label, 0x5529B6F6, 0xFF29B6F6.toInt(), 0xFFFFFFFF.toInt(), dp(20f))
        }
    }

    private fun drawGameOver(c: Canvas) {
        paint.color = 0xFFFF5252.toInt()
        paint.textSize = dp(42f)
        c.drawText("游戏结束", vw / 2f, vh * 0.26f, paint)

        if (newRecord) {
            val pulse = 0.6f + 0.4f * sin(menuTime * 6f)
            paint.color = ((255 * pulse).toInt().coerceIn(0, 255)).shl(24) or 0x00FFD54F
            paint.textSize = dp(18f)
            c.drawText("★ 新纪录 ★", vw / 2f, vh * 0.315f, paint)
        }

        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = dp(26f)
        c.drawText(hudFinalScore, vw / 2f, vh * 0.39f, paint)

        paint.color = 0xFF90CAF9.toInt()
        paint.textSize = dp(14f)
        c.drawText(hudFinalStats, vw / 2f, vh * 0.45f, paint)

        paint.color = 0xFFFFD54F.toInt()
        paint.textSize = dp(16f)
        c.drawText(hudHighBig, vw / 2f, vh * 0.51f, paint)

        paint.color = 0xFF69F0AE.toInt()
        paint.textSize = dp(14f)
        c.drawText("金币余额 ${upgrades.coins}", vw / 2f, vh * 0.56f, paint)

        if (overCooldown <= 0f) {
            drawButton(c, btnAgain, "再来一局", 0x5529B6F6, 0xFF29B6F6.toInt(), 0xFFFFFFFF.toInt(), dp(20f))
        }
    }

    private fun drawComplete(c: Canvas) {
        paint.color = 0xFFFFD54F.toInt()
        paint.textSize = dp(38f)
        c.drawText(if (isBossRush) "全部击破！" else "全部通关！", vw / 2f, vh * 0.24f, paint)

        paint.color = 0xFF69F0AE.toInt()
        paint.textSize = dp(17f)
        c.drawText(if (isBossRush) "你讨伐了所有 BOSS" else "你征服了虚空王座", vw / 2f, vh * 0.31f, paint)

        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = dp(24f)
        c.drawText("总分 $score", vw / 2f, vh * 0.39f, paint)

        paint.color = 0xFF90CAF9.toInt()
        paint.textSize = dp(14f)
        c.drawText(hudFinalStats, vw / 2f, vh * 0.45f, paint)

        paint.color = 0xFFFFD54F.toInt()
        paint.textSize = dp(16f)
        c.drawText("金币余额 ${upgrades.coins}", vw / 2f, vh * 0.51f, paint)

        paint.color = 0xFF90CAF9.toInt()
        paint.textSize = dp(13f)
        c.drawText("成就 ${ach.unlockedCount()} / ${ACHIEVEMENTS.size}", vw / 2f, vh * 0.56f, paint)

        drawButton(c, btnAgain, "返回主菜单", 0x5529B6F6, 0xFF29B6F6.toInt(), 0xFFFFFFFF.toInt(), dp(20f))
    }

    private fun drawPaused(c: Canvas) {
        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = dp(38f)
        c.drawText("已暂停", vw / 2f, vh * 0.36f, paint)

        drawButton(c, btnResume, "继续", 0x5529B6F6, 0xFF29B6F6.toInt(), 0xFFFFFFFF.toInt(), dp(20f))
        drawButton(c, btnRestart, "重新开始", 0x33FFFFFF, 0x88FFFFFF.toInt(), 0xFFE3F2FD.toInt(), dp(18f))
        drawButton(c, btnQuit, "退出到主菜单", 0x33FF5252, 0xFFFF5252.toInt(), 0xFFFFFFFF.toInt(), dp(18f))

        paint.color = 0xFF90CAF9.toInt()
        paint.textSize = dp(14f)
        c.drawText(hudPauseInfo, vw / 2f, vh * 0.46f + dp(222f), paint)
    }

    private fun drawShop(c: Canvas) {
        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = dp(26f)
        c.drawText("商店", vw / 2f, topInset + dp(44f), paint)

        paint.color = 0xFFFFB300.toInt()
        paint.textSize = dp(16f)
        c.drawText("金币 ${upgrades.coins}", vw / 2f, topInset + dp(66f), paint)

        for (i in 0 until W_COUNT) {
            val spec = WEAPONS[i]
            val unlocked = upgrades.weaponUnlocked(i)
            val selected = upgrades.selectedWeapon == i
            val r = weaponBtns[i]
            val fill = when {
                !unlocked -> 0x22FFFFFF
                selected -> (spec.color and 0x00FFFFFF) or 0x66000000
                else -> 0x33FFFFFF
            }
            val stroke = when {
                !unlocked -> 0x44FFFFFF
                selected -> spec.color
                else -> 0x88FFFFFF.toInt()
            }
            drawPanel(c, r.left, r.top, r.width(), r.height(), fill, stroke)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = dp(13f)
            paint.color = if (unlocked) 0xFFFFFFFF.toInt() else 0x66FFFFFF
            c.drawText(spec.shortName, r.centerX(), r.centerY() - dp(6f), paint)
            paint.textSize = dp(10f)
            paint.color = if (unlocked) 0xFFB0BEC5.toInt() else 0x55FF5252
            val sub = if (!unlocked) "未解锁" else if (selected) "使用中" else "点击选择"
            c.drawText(sub, r.centerX(), r.centerY() + dp(11f), paint)
            paint.textAlign = Paint.Align.LEFT
        }

        val base = shopPage * SHOP_ROWS
        for (i in 0 until SHOP_ROWS) {
            val id = base + i
            val r = shopRows[i]
            if (id >= UPGRADES.size) {
                drawPanel(c, r.left, r.top, r.width(), r.height(), 0x11FFFFFF, 0x22FFFFFF)
                continue
            }
            val u = UPGRADES[id]
            val lv = upgrades.levelOf(id)
            val maxed = upgrades.isMaxed(id)
            val can = !maxed && upgrades.coins >= upgrades.costOf(id)

            val fill = if (maxed) 0x2269F0AE else if (can) 0x33FFFFFF else 0x1AFFFFFF
            val stroke = if (maxed) 0xFF69F0AE.toInt() else if (can) u.color else 0x55FFFFFF
            drawPanel(c, r.left, r.top, r.width(), r.height(), fill, stroke)

            paint.textAlign = Paint.Align.LEFT
            paint.color = 0xFFFFFFFF.toInt()
            paint.textSize = dp(15f)
            c.drawText(u.name, r.left + dp(14f), r.top + dp(22f), paint)

            paint.textSize = dp(11f)
            paint.color = 0xFF90CAF9.toInt()
            c.drawText(u.desc, r.left + dp(14f), r.top + dp(40f), paint)

            for (k in 0 until u.maxLevel) {
                val dotX = r.right - dp(126f) + k * dp(14f)
                val dotY = r.top + dp(20f)
                paint.style = Paint.Style.FILL
                paint.color = if (k < lv) u.color else 0x44FFFFFF
                c.drawCircle(dotX, dotY, dp(4.5f), paint)
            }

            paint.textAlign = Paint.Align.RIGHT
            paint.textSize = dp(14f)
            paint.color = when {
                maxed -> 0xFF69F0AE.toInt()
                can -> 0xFFFFD54F.toInt()
                else -> 0x66FFFFFF
            }
            val label = if (maxed) "已满级" else "${upgrades.costOf(id)} 金币"
            c.drawText(label, r.right - dp(14f), r.top + dp(38f), paint)
            paint.textAlign = Paint.Align.LEFT
        }

        val pages = (UPGRADES.size + SHOP_ROWS - 1) / SHOP_ROWS
        drawButton(c, btnShopPrev, "上一页", 0x33FFFFFF, 0x88FFFFFF.toInt(), 0xFFE3F2FD.toInt(), dp(15f))
        drawButton(c, btnShopNext, "下一页", 0x33FFFFFF, 0x88FFFFFF.toInt(), 0xFFE3F2FD.toInt(), dp(15f))
        drawButton(c, btnShopGear, "僚机与装备", 0x5533D6FF.toInt(), 0xFF33D6FF.toInt(), 0xFFFFFFFF.toInt(), dp(14f))

        paint.textAlign = Paint.Align.CENTER
        paint.color = 0xFF90CAF9.toInt()
        paint.textSize = dp(13f)
        c.drawText("${shopPage + 1} / $pages", vw / 2f, btnShopPrev.centerY() - dp(28f), paint)
        paint.textAlign = Paint.Align.LEFT

        drawButton(c, btnBack, "返回", 0x5529B6F6, 0xFF29B6F6.toInt(), 0xFFFFFFFF.toInt(), dp(16f))
    }

    private fun drawAchievements(c: Canvas) {
        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = dp(26f)
        c.drawText("成就", vw / 2f, topInset + dp(44f), paint)

        paint.color = 0xFF69F0AE.toInt()
        paint.textSize = dp(14f)
        c.drawText("已解锁 ${ach.unlockedCount()} / ${ACHIEVEMENTS.size}", vw / 2f, topInset + dp(66f), paint)

        val base = achPage * ACH_PER_PAGE
        val rowH = dp(56f)
        val top0 = topInset + dp(84f)
        for (i in 0 until ACH_PER_PAGE) {
            val id = base + i
            val y = top0 + i * (rowH + dp(6f))
            val r = RectF(dp(24f), y, vw - dp(24f), y + rowH)
            if (id >= ACHIEVEMENTS.size) {
                drawPanel(c, r.left, r.top, r.width(), r.height(), 0x0AFFFFFF, 0x1AFFFFFF)
                continue
            }
            val a = ACHIEVEMENTS[id]
            val got = ach.unlocked[a.id]
            val fill = if (got) (a.color and 0x00FFFFFF) or 0x33000000 else 0x1AFFFFFF
            val stroke = if (got) a.color else 0x44FFFFFF
            drawPanel(c, r.left, r.top, r.width(), r.height(), fill, stroke)

            paint.textAlign = Paint.Align.LEFT
            paint.color = if (got) 0xFFFFFFFF.toInt() else 0x88FFFFFF.toInt()
            paint.textSize = dp(15f)
            c.drawText(a.name, r.left + dp(14f), r.top + dp(22f), paint)

            paint.textSize = dp(11f)
            paint.color = if (got) 0xFFB0BEC5.toInt() else 0x66FFFFFF
            c.drawText(a.desc, r.left + dp(14f), r.top + dp(40f), paint)

            paint.textAlign = Paint.Align.RIGHT
            if (got) {
                paint.textSize = dp(13f)
                paint.color = a.color
                c.drawText("已达成", r.right - dp(14f), r.top + dp(30f), paint)
            } else {
                val p = ach.progress(a)
                val v = ach.stats[a.stat]
                paint.textSize = dp(12f)
                paint.color = 0xFF90CAF9.toInt()
                c.drawText("${min(v, a.target)} / ${a.target}", r.right - dp(14f), r.top + dp(26f), paint)
                val bw = dp(80f)
                val bx = r.right - dp(14f) - bw
                val by = r.top + dp(36f)
                paint.style = Paint.Style.FILL
                paint.color = 0x55000000
                c.drawRect(bx, by, bx + bw, by + dp(4f), paint)
                paint.color = a.color
                c.drawRect(bx, by, bx + bw * p, by + dp(4f), paint)
            }
            paint.textAlign = Paint.Align.LEFT
        }

        val pages = (ACHIEVEMENTS.size + ACH_PER_PAGE - 1) / ACH_PER_PAGE
        drawButton(c, btnAchPrev, "上一页", 0x33FFFFFF, 0x88FFFFFF.toInt(), 0xFFE3F2FD.toInt(), dp(15f))
        drawButton(c, btnAchNext, "下一页", 0x33FFFFFF, 0x88FFFFFF.toInt(), 0xFFE3F2FD.toInt(), dp(15f))
        paint.textAlign = Paint.Align.CENTER
        paint.color = 0xFF90CAF9.toInt()
        paint.textSize = dp(13f)
        c.drawText("${achPage + 1} / $pages", vw / 2f, btnAchPrev.centerY(), paint)
        paint.textAlign = Paint.Align.LEFT

        drawButton(c, btnBack, "返回", 0x5529B6F6, 0xFF29B6F6.toInt(), 0xFFFFFFFF.toInt(), dp(16f))
    }

    private fun drawSettings(c: Canvas) {
        paint.textAlign = Paint.Align.CENTER
        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = dp(26f)
        c.drawText("设置", vw / 2f, topInset + dp(44f), paint)

        paint.color = 0xFF90CAF9.toInt()
        paint.textSize = dp(12f)
        c.drawText("当前账号：${accountName ?: "-"}", vw / 2f, topInset + dp(66f), paint)

        val titles = arrayOf("音效", "震动", "背景音乐", "音乐音量", "退出登录")
        val descs = arrayOf(
            "游戏音效与提示音",
            "击中、爆炸时的震动反馈",
            "菜单 / 战斗 / BOSS 背景音乐",
            "调整背景音乐大小",
            "保存进度并切换到其他账号"
        )
        val on = booleanArrayOf(soundOn, vibrateOn, musicOn, false, false)

        for (i in 0 until SETTINGS_ROWS) {
            val r = settingsRows[i]
            val isVol = i == 3
            val isLogout = i == 4
            val active = !isVol && !isLogout && on[i]
            val fill = when {
                isLogout -> 0x33FF5252
                active -> 0x3300E676
                else -> 0x1AFFFFFF
            }
            val stroke = when {
                isLogout -> 0xFFFF5252.toInt()
                active -> 0xFF00E676.toInt()
                else -> 0x44FFFFFF
            }
            drawPanel(c, r.left, r.top, r.width(), r.height(), fill, stroke)

            paint.textAlign = Paint.Align.LEFT
            paint.color = if (isLogout) 0xFFFF8A80.toInt() else 0xFFFFFFFF.toInt()
            paint.textSize = dp(17f)
            c.drawText(titles[i], r.left + dp(16f), r.top + dp(25f), paint)

            paint.color = 0xFF90CAF9.toInt()
            paint.textSize = dp(10f)
            c.drawText(descs[i], r.left + dp(16f), r.top + dp(43f), paint)

            if (isLogout) {
                paint.textAlign = Paint.Align.RIGHT
                paint.color = 0xFFFF8A80.toInt()
                paint.textSize = dp(18f)
                c.drawText("›", r.right - dp(18f), r.centerY() + dp(7f), paint)
                paint.textAlign = Paint.Align.LEFT
            } else if (isVol) {
                drawButton(c, btnVolDown, "-", 0x33FFFFFF, 0x66FFFFFF, 0xFFFFFFFF.toInt(), dp(20f))
                drawButton(c, btnVolUp, "+", 0x33FFFFFF, 0x66FFFFFF, 0xFFFFFFFF.toInt(), dp(20f))
                val bx = btnVolDown.right + dp(10f)
                val bw = max(dp(10f), btnVolUp.left - dp(10f) - bx)
                val by = r.centerY() - dp(5f)
                paint.style = Paint.Style.FILL
                paint.color = 0x55000000
                c.drawRect(bx, by, bx + bw, by + dp(10f), paint)
                paint.color = 0xFF4FC3F7.toInt()
                c.drawRect(bx, by, bx + bw * musicVolume, by + dp(10f), paint)
                paint.textAlign = Paint.Align.CENTER
                paint.color = 0xFFFFFFFF.toInt()
                paint.textSize = dp(11f)
                c.drawText("${(musicVolume * 100).toInt()}%", bx + bw / 2f, by - dp(6f), paint)
                paint.textAlign = Paint.Align.LEFT
            } else {
                // 开关：胶囊轨道 + 圆点
                val swW = dp(54f)
                val swH = dp(30f)
                val sx = r.right - dp(16f) - swW
                val sy = r.centerY() - swH / 2f
                paint.style = Paint.Style.FILL
                paint.color = if (active) 0xFF00E676.toInt() else 0x33FFFFFF
                c.drawRoundRect(sx, sy, sx + swW, sy + swH, swH / 2f, swH / 2f, paint)
                val knobR = swH / 2f - dp(3f)
                val knobX = if (active) sx + swW - swH / 2f else sx + swH / 2f
                paint.color = 0xFFFFFFFF.toInt()
                c.drawCircle(knobX, sy + swH / 2f, knobR, paint)
            }
        }

        paint.textAlign = Paint.Align.CENTER
        paint.color = 0xFF78909C.toInt()
        paint.textSize = dp(12f)
        c.drawText("设置会自动保存", vw / 2f, vh - dp(100f), paint)
        paint.textAlign = Paint.Align.LEFT

        drawButton(c, btnBack, "返回", 0x5529B6F6, 0xFF29B6F6.toInt(), 0xFFFFFFFF.toInt(), dp(16f))
    }

    private fun drawShipSelect(c: Canvas) {
        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = dp(26f)
        c.drawText("选择战机", vw / 2f, topInset + dp(44f), paint)

        paint.color = 0xFFFFB300.toInt()
        paint.textSize = dp(14f)
        c.drawText("金币 ${upgrades.coins}", vw / 2f, topInset + dp(66f), paint)

        for (i in 0 until SHIP_COUNT) {
            val r = shipRows[i]
            val spec = SHIPS[i]
            val unlocked = ships.isUnlocked(i)
            val selected = ships.selected == i
            val fill = when {
                !unlocked -> 0x14FFFFFF
                selected -> (spec.color and 0x00FFFFFF) or 0x55000000
                else -> 0x22FFFFFF
            }
            val stroke = when {
                !unlocked -> 0x33FFFFFF
                selected -> spec.color
                else -> 0x66FFFFFF
            }
            drawPanel(c, r.left, r.top, r.width(), r.height(), fill, stroke)

            // 战机图标
            drawPlaneBmp(c, sp.player, r.left + dp(34f), r.centerY(), dp(17f))

            paint.textAlign = Paint.Align.LEFT
            paint.color = if (unlocked) 0xFFFFFFFF.toInt() else 0x88FFFFFF.toInt()
            paint.textSize = dp(16f)
            c.drawText(spec.name, r.left + dp(62f), r.top + dp(24f), paint)

            paint.color = if (unlocked) 0xFFB0BEC5.toInt() else 0x66FFFFFF
            paint.textSize = dp(10.5f)
            c.drawText(spec.desc, r.left + dp(62f), r.top + dp(42f), paint)

            paint.textAlign = Paint.Align.RIGHT
            when {
                selected -> {
                    paint.color = spec.color
                    paint.textSize = dp(14f)
                    c.drawText("使用中", r.right - dp(16f), r.centerY() + dp(5f), paint)
                }
                unlocked -> {
                    paint.color = 0xFF90CAF9.toInt()
                    paint.textSize = dp(13f)
                    c.drawText("点击选择", r.right - dp(16f), r.centerY() + dp(5f), paint)
                }
                upgrades.coins >= spec.cost -> {
                    paint.color = 0xFFFFD54F.toInt()
                    paint.textSize = dp(14f)
                    c.drawText("${spec.cost} 金币", r.right - dp(16f), r.centerY() + dp(5f), paint)
                }
                else -> {
                    paint.color = 0xFFFF8A80.toInt()
                    paint.textSize = dp(13f)
                    c.drawText("${spec.cost} 金币", r.right - dp(16f), r.centerY() + dp(5f), paint)
                }
            }
            paint.textAlign = Paint.Align.LEFT
        }

        drawButton(c, btnBack, "返回", 0x5529B6F6, 0xFF29B6F6.toInt(), 0xFFFFFFFF.toInt(), dp(16f))
    }

    private fun drawGearShop(c: Canvas) {
        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = dp(26f)
        c.drawText("僚机与装备", vw / 2f, topInset + dp(44f), paint)

        paint.color = 0xFFFFB300.toInt()
        paint.textSize = dp(14f)
        c.drawText("金币 ${upgrades.coins}", vw / 2f, topInset + dp(66f), paint)

        val base = gearPage * GEAR_ROWS
        for (i in 0 until GEAR_ROWS) {
            val r = gearRows[i]
            val idx = base + i
            val isWing = idx < WG_COUNT
            val id = if (isWing) idx else idx - WG_COUNT
            val name: String
            val desc: String
            val color: Int
            val cost: Int
            val owned: Boolean
            val active: Boolean
            if (isWing) {
                val s = WINGMEN[id]
                name = "僚机 · ${s.name}"; desc = s.desc; color = s.color; cost = s.cost
                owned = gear.wingmanUnlocked(id)
                active = gear.selectedWingman == id
            } else {
                val s = EQUIPMENT[id]
                name = "装备 · ${s.name}"; desc = s.desc; color = s.color; cost = s.cost
                owned = gear.hasEquipment(id)
                active = false
            }
            val fill = if (owned) (color and 0x00FFFFFF) or 0x44000000 else 0x1AFFFFFF
            val stroke = if (owned) color else 0x44FFFFFF
            drawPanel(c, r.left, r.top, r.width(), r.height(), fill, stroke)

            paint.textAlign = Paint.Align.LEFT
            paint.color = if (owned) 0xFFFFFFFF.toInt() else 0xBBFFFFFF.toInt()
            paint.textSize = dp(15f)
            c.drawText(name, r.left + dp(16f), r.top + dp(24f), paint)

            paint.color = 0xFF90CAF9.toInt()
            paint.textSize = dp(10f)
            c.drawText(desc, r.left + dp(16f), r.top + dp(42f), paint)

            paint.textAlign = Paint.Align.RIGHT
            when {
                isWing && active -> {
                    paint.color = color; paint.textSize = dp(13f)
                    c.drawText("使用中", r.right - dp(16f), r.centerY() + dp(5f), paint)
                }
                isWing && owned -> {
                    paint.color = 0xFF90CAF9.toInt(); paint.textSize = dp(13f)
                    c.drawText("点击装备", r.right - dp(16f), r.centerY() + dp(5f), paint)
                }
                owned -> {
                    paint.color = color; paint.textSize = dp(13f)
                    c.drawText("已拥有", r.right - dp(16f), r.centerY() + dp(5f), paint)
                }
                else -> {
                    paint.color = if (upgrades.coins >= cost) 0xFFFFD54F.toInt() else 0xFFFF8A80.toInt()
                    paint.textSize = dp(14f)
                    c.drawText("$cost 金币", r.right - dp(16f), r.centerY() + dp(5f), paint)
                }
            }
            paint.textAlign = Paint.Align.LEFT
        }

        val pages = gearPageCount()
        drawButton(c, btnGearPrev, "上一页", 0x33FFFFFF, 0x88FFFFFF.toInt(), 0xFFE3F2FD.toInt(), dp(15f))
        drawButton(c, btnGearNext, "下一页", 0x33FFFFFF, 0x88FFFFFF.toInt(), 0xFFE3F2FD.toInt(), dp(15f))
        paint.textAlign = Paint.Align.CENTER
        paint.color = 0xFF90CAF9.toInt()
        paint.textSize = dp(13f)
        c.drawText("${gearPage + 1} / $pages", vw / 2f, btnGearPrev.centerY(), paint)
        paint.textAlign = Paint.Align.LEFT

        drawButton(c, btnBack, "返回", 0x5529B6F6, 0xFF29B6F6.toInt(), 0xFFFFFFFF.toInt(), dp(16f))
    }

    private fun drawStats(c: Canvas) {
        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = dp(26f)
        c.drawText("统计", vw / 2f, topInset + dp(44f), paint)

        val top0 = topInset + dp(76f)
        val rowH = dp(40f)
        for (i in 0 until S_COUNT) {
            val y = top0 + i * (rowH + dp(4f))
            val r = RectF(dp(24f), y, vw - dp(24f), y + rowH)
            drawPanel(c, r.left, r.top, r.width(), r.height(), 0x1AFFFFFF, 0x33FFFFFF)

            paint.textAlign = Paint.Align.LEFT
            paint.color = 0xFF90CAF9.toInt()
            paint.textSize = dp(14f)
            c.drawText(STAT_NAMES[i], r.left + dp(14f), r.centerY() + dp(5f), paint)

            paint.textAlign = Paint.Align.RIGHT
            paint.color = 0xFFFFFFFF.toInt()
            paint.textSize = dp(16f)
            val v = ach.stats[i]
            val suffix = if (i == S_BEST_TIME) " 秒" else ""
            c.drawText("$v$suffix", r.right - dp(14f), r.centerY() + dp(5f), paint)
            paint.textAlign = Paint.Align.LEFT
        }

        paint.textAlign = Paint.Align.CENTER
        paint.color = 0xFF90CAF9.toInt()
        paint.textSize = dp(13f)
        c.drawText("历史最高分 $highScore", vw / 2f, vh - dp(100f), paint)
        paint.textAlign = Paint.Align.LEFT

        drawButton(c, btnBack, "返回", 0x5529B6F6, 0xFF29B6F6.toInt(), 0xFFFFFFFF.toInt(), dp(16f))
    }

    // ---------------- 奖励系统界面 ----------------

    /** 单位五角星路径（半径 1，画的时候用 scale 放大） */
    private val starPath: android.graphics.Path by lazy {
        val p = android.graphics.Path()
        val n = 5
        for (i in 0 until n * 2) {
            val ang = -Math.PI / 2.0 + i * Math.PI / n
            val rr = if (i % 2 == 0) 1.0 else 0.45
            val x = (Math.cos(ang) * rr).toFloat()
            val y = (Math.sin(ang) * rr).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        p
    }

    /** 画一颗星；filled = false 时只画空心轮廓 */
    private fun drawStarIcon(c: Canvas, cx: Float, cy: Float, r: Float, filled: Boolean) {
        c.save()
        c.translate(cx, cy)
        c.scale(r, r)
        if (filled) {
            paint.style = Paint.Style.FILL
            paint.color = 0xFFFFD54F.toInt()
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.14f
            paint.color = 0x55FFFFFF
        }
        c.drawPath(starPath, paint)
        c.restore()
        paint.style = Paint.Style.FILL
    }

    /** 把 0..3 星画成「已得实心 + 未得空心」 */
    private fun drawStarScore(c: Canvas, cx: Float, cy: Float, got: Int, max: Int = 3, r: Float = dp(8f), gap: Float = dp(3f)) {
        val total = max * 2f * r + (max - 1) * gap
        var x = cx - total / 2f + r
        for (i in 0 until max) {
            drawStarIcon(c, x, cy, r, i < got)
            x += r * 2f + gap
        }
    }

    private fun drawRewards(c: Canvas) {
        paint.textAlign = Paint.Align.CENTER
        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = dp(26f)
        c.drawText("奖励中心", vw / 2f, topInset + dp(44f), paint)

        paint.color = 0xFFFFB300.toInt()
        paint.textSize = dp(13f)
        c.drawText(
            "金币 ${upgrades.coins} · 钥匙 ${rewards.keys} · 星星 ${rewards.stars.total()}/$MAX_STARS",
            vw / 2f, topInset + dp(66f), paint
        )

        val today = todayKey()

        // ---------- 签到 ----------
        val rTop = checkinCells[0].top
        paint.textAlign = Paint.Align.LEFT
        paint.color = 0xFF90CAF9.toInt()
        paint.textSize = dp(13f)
        c.drawText("7 天连续签到", dp(18f), rTop - dp(9f), paint)
        paint.textAlign = Paint.Align.RIGHT
        paint.color = 0xFFB0BEC5.toInt()
        paint.textSize = dp(11f)
        c.drawText("累计 ${rewards.checkin.totalDays} 天", vw - dp(18f), rTop - dp(9f), paint)

        val filled = rewards.checkin.filledCount(today)
        val pending = rewards.checkin.pendingIndex(today)
        for (i in 0 until CHECKIN_DAYS) {
            val cell = checkinCells[i]
            val got = i < filled
            val isNext = i == pending
            val pulse = if (isNext) 0.5f + 0.5f * sin(menuTime * 5f) else 1f
            val fill = when {
                got -> 0x55FFB300.toInt()
                isNext -> ((255 * pulse * 0.30f).toInt().coerceIn(0, 255)).shl(24) or 0x00FFD54F
                else -> 0x18FFFFFF
            }
            val stroke = when {
                got -> 0xFFFFD54F.toInt()
                isNext -> ((255 * pulse).toInt().coerceIn(0, 255)).shl(24) or 0x00FFD54F
                else -> 0x33FFFFFF
            }
            drawPanel(c, cell.left, cell.top, cell.width(), cell.height(), fill, stroke)
            paint.textAlign = Paint.Align.CENTER
            paint.color = if (got) 0xFFFFD54F.toInt() else if (isNext) 0xFFFFFFFF.toInt() else 0x77FFFFFF.toInt()
            paint.textSize = dp(12f)
            c.drawText("${i + 1}", cell.centerX(), cell.top + dp(19f), paint)
            paint.color = if (got) 0xFFB0BEC5.toInt() else 0x66FFFFFF.toInt()
            paint.textSize = dp(9f)
            c.drawText(CHECKIN_REWARDS[i].short, cell.centerX(), cell.top + dp(34f), paint)
            if (got) {
                paint.color = 0xFF69F0AE.toInt()
                paint.textSize = dp(11f)
                c.drawText("已领", cell.centerX(), cell.top + dp(47f), paint)
            }
        }

        val claimedToday = rewards.checkin.claimedToday(today)
        val broken = rewards.checkin.isBroken(today) && !claimedToday
        val rw = if (pending >= 0) CHECKIN_REWARDS[pending] else CHECKIN_REWARDS[0]
        val ciLabel = when {
            claimedToday -> "今日已签到 · 明天继续"
            broken -> "断签了 · 从第 1 天重新开始"
            else -> "领取第 ${pending + 1} 天 · 金币 ${rw.coins}" +
                    if (rw.keys > 0) " + 钥匙 ×${rw.keys}" else ""
        }
        drawButton(
            c, btnCheckin, ciLabel,
            if (claimedToday) 0x22FFFFFF else 0x55FFB300.toInt(),
            if (claimedToday) 0x55FFFFFF else 0xFFFFB300.toInt(),
            if (claimedToday) 0xFFB0BEC5.toInt() else 0xFFFFFFFF.toInt(),
            dp(14f)
        )

        // ---------- 每日任务 ----------
        val tTop = taskRows[0].top
        paint.textAlign = Paint.Align.LEFT
        paint.color = 0xFF90CAF9.toInt()
        paint.textSize = dp(13f)
        c.drawText("每日任务", dp(18f), tTop - dp(9f), paint)
        paint.textAlign = Paint.Align.RIGHT
        paint.color = 0xFF69F0AE.toInt()
        c.drawText("活跃度 ${rewards.activity}", vw - dp(18f), tTop - dp(9f), paint)

        for (i in 0 until TASKS_PER_DAY) {
            val r = taskRows[i]
            val sp = rewards.tasks.specAt(i)
            val prog = rewards.tasks.progress[i]
            val fin = rewards.tasks.done[i]
            val ratio = (prog.toFloat() / sp.target.toFloat()).coerceIn(0f, 1f)
            drawPanel(
                c, r.left, r.top, r.width(), r.height(),
                if (fin) 0x3369F0AE.toInt() else 0x1AFFFFFF,
                if (fin) 0xFF69F0AE.toInt() else 0x33FFFFFF
            )

            val barX = r.left + dp(14f)
            val barW = r.width() - dp(28f)
            val barY = r.bottom - dp(11f)
            paint.style = Paint.Style.FILL
            paint.color = 0x22FFFFFF
            c.drawRoundRect(RectF(barX, barY, barX + barW, barY + dp(4f)), dp(2f), dp(2f), paint)
            paint.color = if (fin) 0xFF69F0AE.toInt() else 0xFF29B6F6.toInt()
            c.drawRoundRect(RectF(barX, barY, barX + barW * ratio, barY + dp(4f)), dp(2f), dp(2f), paint)

            paint.textAlign = Paint.Align.LEFT
            paint.color = 0xFFFFFFFF.toInt()
            paint.textSize = dp(14f)
            c.drawText(sp.desc, r.left + dp(14f), r.top + dp(24f), paint)

            paint.color = 0xFF90CAF9.toInt()
            paint.textSize = dp(10.5f)
            c.drawText("${prog.coerceAtMost(sp.target)} / ${sp.target}", r.left + dp(14f), r.top + dp(42f), paint)

            paint.textAlign = Paint.Align.RIGHT
            if (fin) {
                paint.color = 0xFF69F0AE.toInt()
                paint.textSize = dp(13f)
                c.drawText("已完成 +${sp.act}", r.right - dp(14f), r.centerY() + dp(5f), paint)
            } else {
                paint.color = 0xFFB0BEC5.toInt()
                paint.textSize = dp(12f)
                c.drawText("活跃 +${sp.act}", r.right - dp(14f), r.centerY() + dp(5f), paint)
            }
            paint.textAlign = Paint.Align.LEFT
        }

        // ---------- 活跃度档位 ----------
        val aTop = tierBtns[0].top
        paint.textAlign = Paint.Align.LEFT
        paint.color = 0xFF90CAF9.toInt()
        paint.textSize = dp(13f)
        c.drawText("活跃度奖励", dp(18f), aTop - dp(22f), paint)

        val barX2 = dp(18f)
        val barW2 = vw - dp(36f)
        val barY2 = aTop - dp(15f)
        val aRatio = (rewards.activity.toFloat() / ACTIVITY_MAX.toFloat()).coerceIn(0f, 1f)
        paint.style = Paint.Style.FILL
        paint.color = 0x22FFFFFF
        c.drawRoundRect(RectF(barX2, barY2, barX2 + barW2, barY2 + dp(6f)), dp(3f), dp(3f), paint)
        paint.color = 0xFF69F0AE.toInt()
        c.drawRoundRect(RectF(barX2, barY2, barX2 + barW2 * aRatio, barY2 + dp(6f)), dp(3f), dp(3f), paint)

        for (i in ACTIVITY_TIERS.indices) {
            val r = tierBtns[i]
            val t = ACTIVITY_TIERS[i]
            val got = rewards.tierClaimed[i]
            val ready = rewards.tierReady(i)
            val pulse = if (ready) 0.45f + 0.4f * sin(menuTime * 5f) else 1f
            val fill = when {
                got -> 0x22FFFFFF
                ready -> ((255 * pulse * 0.32f).toInt().coerceIn(0, 255)).shl(24) or 0x0069F0AE
                else -> 0x14FFFFFF
            }
            val stroke = when {
                got -> 0x55FFFFFF
                ready -> ((255 * pulse).toInt().coerceIn(0, 255)).shl(24) or 0x0069F0AE
                else -> 0x33FFFFFF
            }
            val txt = when {
                got -> 0x88FFFFFF.toInt()
                ready -> 0xFFFFFFFF.toInt()
                else -> 0xAAFFFFFF.toInt()
            }
            drawButton(c, r, "", fill, stroke, txt, dp(13f))
            paint.textAlign = Paint.Align.CENTER
            paint.color = txt
            paint.textSize = dp(12.5f)
            c.drawText(t.short, r.centerX(), r.centerY() - dp(2f), paint)
            paint.color = if (got || ready) 0xFFB0BEC5.toInt() else 0x66FFFFFF
            paint.textSize = dp(10f)
            c.drawText("${t.need} 点", r.centerX(), r.centerY() + dp(13f), paint)
        }

        // ---------- 两个入口 ----------
        drawButton(c, btnRewardsBox, "开箱抽奖", 0x55FFB300.toInt(), 0xFFFFB300.toInt(), 0xFFFFFFFF.toInt(), dp(16f))
        drawButton(c, btnRewardsStars, "星级奖励", 0x55FFD54F.toInt(), 0xFFFFD54F.toInt(), 0xFFFFFFFF.toInt(), dp(16f))

        drawButton(c, btnBack, "返回", 0x5529B6F6, 0xFF29B6F6.toInt(), 0xFFFFFFFF.toInt(), dp(16f))
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawBox(c: Canvas) {
        paint.textAlign = Paint.Align.CENTER
        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = dp(26f)
        c.drawText("开箱抽奖", vw / 2f, topInset + dp(44f), paint)

        paint.color = 0xFFFFB300.toInt()
        paint.textSize = dp(13f)
        c.drawText("金币 ${upgrades.coins} · 钥匙 ${rewards.keys}", vw / 2f, topInset + dp(66f), paint)

        for (i in 0 until BOX_ROWS) {
            val spec = BOXES[i]
            val r = boxRows[i]
            val afford = upgrades.coins >= spec.costCoins && rewards.keys >= spec.costKeys
            val fill = (spec.color and 0x00FFFFFF) or (if (afford) 0x44000000.toInt() else 0x14000000)
            val stroke = if (afford) spec.color else 0x44FFFFFF
            drawPanel(c, r.left, r.top, r.width(), r.height(), fill, stroke)

            paint.textAlign = Paint.Align.LEFT
            paint.color = if (afford) spec.color else 0x88FFFFFF.toInt()
            paint.textSize = dp(19f)
            c.drawText(spec.name, r.left + dp(18f), r.top + dp(30f), paint)

            paint.color = if (afford) 0xFFFFFFFF.toInt() else 0x88FFFFFF.toInt()
            paint.textSize = dp(13f)
            val cost = if (spec.costKeys > 0) "花费 ${spec.costKeys} 把钥匙" else "花费 ${spec.costCoins} 金币"
            c.drawText(cost, r.left + dp(18f), r.top + dp(56f), paint)

            paint.color = 0xFF90CAF9.toInt()
            paint.textSize = dp(11f)
            c.drawText(spec.tip, r.left + dp(18f), r.top + dp(78f), paint)

            val left = max(0, PITY_LIMIT - rewards.pityOf(i))
            paint.color = if (left <= 1) 0xFFFF8A80.toInt() else 0xFFB0BEC5.toInt()
            paint.textSize = dp(11f)
            c.drawText("距保底大奖还有 $left 次", r.left + dp(18f), r.top + dp(100f), paint)

            // 右侧大宝箱图标
            paint.textAlign = Paint.Align.CENTER
            drawStarIcon(c, r.right - dp(46f), r.centerY(), dp(20f), true)
        }

        paint.textAlign = Paint.Align.CENTER
        paint.color = 0xFF90CAF9.toInt()
        paint.textSize = dp(11.5f)
        c.drawText("已开箱 ${rewards.boxOpened} 次 · 大奖必定是解锁券或钥匙", vw / 2f, boxRows[BOX_ROWS - 1].bottom + dp(34f), paint)

        drawButton(c, btnBack, "返回", 0x5529B6F6, 0xFF29B6F6.toInt(), 0xFFFFFFFF.toInt(), dp(16f))
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawStarLevels(c: Canvas) {
        val total = rewards.stars.total()
        paint.textAlign = Paint.Align.CENTER
        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = dp(26f)
        c.drawText("关卡星级", vw / 2f, topInset + dp(44f), paint)

        paint.color = 0xFFFFD54F.toInt()
        paint.textSize = dp(13f)
        c.drawText("总星 $total / $MAX_STARS", vw / 2f, topInset + dp(66f), paint)

        paint.color = 0xFF90CAF9.toInt()
        paint.textSize = dp(10.5f)
        c.drawText("★通关  ★无伤  ★得分达标", vw / 2f, topInset + dp(84f), paint)

        for (i in 0 until LEVELS.size) {
            val r = starCells[i]
            val got = rewards.stars.stars[i]
            drawPanel(
                c, r.left, r.top, r.width(), r.height(),
                if (got > 0) 0x33FFD54F.toInt() else 0x18FFFFFF,
                if (got > 0) 0xFFFFD54F.toInt() else 0x33FFFFFF
            )
            paint.textAlign = Paint.Align.CENTER
            paint.color = if (got > 0) 0xFFFFFFFF.toInt() else 0x88FFFFFF.toInt()
            paint.textSize = dp(11f)
            c.drawText("第 ${i + 1} 关", r.centerX(), r.top + dp(17f), paint)
            drawStarScore(c, r.centerX(), r.top + dp(35f), got)
        }

        // ---------- 星级里程碑 ----------
        val msTop = starRows[0].top
        paint.textAlign = Paint.Align.LEFT
        paint.color = 0xFF90CAF9.toInt()
        paint.textSize = dp(13f)
        c.drawText("星级奖励", dp(18f), msTop - dp(10f), paint)

        for (i in starRows.indices) {
            val r = starRows[i]
            val m = STAR_MILESTONES[i]
            val got = rewards.msClaimed[i]
            val ready = rewards.milestoneReady(i)
            val pulse = if (ready) 0.45f + 0.4f * sin(menuTime * 5f) else 1f
            val fill = when {
                got -> 0x22FFFFFF
                ready -> ((255 * pulse * 0.32f).toInt().coerceIn(0, 255)).shl(24) or 0x00FFD54F
                else -> 0x14FFFFFF
            }
            val stroke = when {
                got -> 0x55FFFFFF
                ready -> ((255 * pulse).toInt().coerceIn(0, 255)).shl(24) or 0x00FFD54F
                else -> 0x33FFFFFF
            }
            drawPanel(c, r.left, r.top, r.width(), r.height(), fill, stroke)

            paint.textAlign = Paint.Align.LEFT
            paint.color = if (got) 0x88FFFFFF.toInt() else if (ready) 0xFFFFFFFF.toInt() else 0xBBFFFFFF.toInt()
            paint.textSize = dp(14f)
            c.drawText(m.label, r.left + dp(16f), r.centerY() + dp(0f), paint)

            paint.color = 0xFFB0BEC5.toInt()
            paint.textSize = dp(10f)
            c.drawText("需要 ${m.need} 星", r.left + dp(16f), r.centerY() + dp(16f), paint)

            paint.textAlign = Paint.Align.RIGHT
            when {
                got -> {
                    paint.color = 0xFF69F0AE.toInt()
                    paint.textSize = dp(13f)
                    c.drawText("已领取", r.right - dp(16f), r.centerY() + dp(5f), paint)
                }
                ready -> {
                    paint.color = ((255 * pulse).toInt().coerceIn(0, 255)).shl(24) or 0x00FFD54F
                    paint.textSize = dp(14f)
                    c.drawText("点击领取", r.right - dp(16f), r.centerY() + dp(5f), paint)
                }
                else -> {
                    paint.color = 0x66FFFFFF
                    paint.textSize = dp(12f)
                    c.drawText("还差 ${m.need - total} 星", r.right - dp(16f), r.centerY() + dp(5f), paint)
                }
            }
            paint.textAlign = Paint.Align.LEFT
        }

        drawButton(c, btnBack, "返回", 0x5529B6F6, 0xFF29B6F6.toInt(), 0xFFFFFFFF.toInt(), dp(16f))
    }

    /** 开箱 / 领奖的结果弹层：卡片带透视翻牌 */
    private fun drawBoxResult(c: Canvas) {
        if (boxTimer <= 0f) return
        val a = (min(1f, boxTimer / 0.30f) * 255f).toInt().coerceIn(0, 255)

        paint.style = Paint.Style.FILL
        paint.color = ((a * 0.62f).toInt().coerceIn(0, 255)).shl(24)
        c.drawRect(0f, 0f, vw, vh, paint)

        val w = min(vw - dp(64f), dp(330f))
        val h = dp(132f)
        val x = (vw - w) / 2f
        val y = vh * 0.34f
        val box = RectF(x, y, x + w, y + h)
        val cx = vw / 2f
        val cy = y + h / 2f

        // ---- 透视翻牌：前半程看到牌背，后半程翻出结果 ----
        val elapsed = BOX_POPUP_TIME - boxTimer
        val flip = (elapsed / BOX_FLIP_TIME).coerceIn(0f, 1f)
        val sy = max(0.06f, abs(cos(flip * PI.toFloat())))   // 纵向压扁 = 卡片转过去
        val sx = 1f - 0.10f * (1f - sy)                      // 横向也收一点，像真的透视
        val showFront = flip >= 0.5f

        c.save()
        c.scale(sx, sy, cx, cy)

        // 卡片投影（随翻牌一起收窄）
        paint.style = Paint.Style.FILL
        paint.color = ((a * 0.45f).toInt().coerceIn(0, 255)).shl(24)
        val sh = RectF(box)
        sh.offset(0f, dp(7f))
        c.drawRoundRect(sh, dp(18f), dp(18f), paint)

        if (showFront) {
            paint.color = ((a * 0.96f).toInt().coerceIn(0, 255)).shl(24) or 0x00101820
            c.drawRoundRect(box, dp(18f), dp(18f), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2.5f)
            paint.color = a.shl(24) or (boxColor and 0x00FFFFFF)
            c.drawRoundRect(box, dp(18f), dp(18f), paint)

            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            drawStarIcon(c, cx, y + dp(20f), dp(11f), true)
            paint.color = a.shl(24) or (boxColor and 0x00FFFFFF)
            paint.textSize = dp(20f)
            c.drawText(boxTitle, cx, y + dp(62f), paint)
            paint.color = a.shl(24) or 0x00E0E0E0
            paint.textSize = dp(13f)
            c.drawText(boxSub, cx, y + dp(88f), paint)
            paint.color = a.shl(24) or 0x0080CBC4
            paint.textSize = dp(11f)
            c.drawText("点击任意位置关闭", cx, y + dp(114f), paint)
        } else {
            // 牌背：一枚空心星，暗示"还没翻开"
            paint.color = ((a * 0.96f).toInt().coerceIn(0, 255)).shl(24) or 0x001A2740
            c.drawRoundRect(box, dp(18f), dp(18f), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2.5f)
            paint.color = a.shl(24) or 0x66FFD54F
            c.drawRoundRect(box, dp(18f), dp(18f), paint)

            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            drawStarIcon(c, cx, cy - dp(12f), dp(22f), false)
            paint.color = a.shl(24) or 0xAAFFD54F.toInt()
            paint.textSize = dp(14f)
            c.drawText("开启中…", cx, cy + dp(36f), paint)
        }
        c.restore()

        paint.textAlign = Paint.Align.LEFT
    }
}

