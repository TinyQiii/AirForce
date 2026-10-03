# Kotlin 学习指南 —— 用你的飞机大战当教材

> 每一课的代码都取自本项目真实源码，学完一个概念就能在项目里找到它。
> 建议：**左边开这份文档，右边开 Android Studio 或代码文件**，对照着看。

---

## 目录

1. [第 1 课：变量 val 和 var](#第-1-课变量-val-和-var)
2. [第 2 课：类型与类型推断](#第-2-课类型与类型推断)
3. [第 3 课：函数 fun](#第-3-课函数-fun)
4. [第 4 课：条件与 when](#第-4-课条件与-when)
5. [第 5 课：循环](#第-5-课循环)
6. [第 6 课：类与构造器](#第-6-课类与构造器)
7. [第 7 课：空安全 ?. 和 ?:](#第-7-课空安全--和-)
8. [第 8 课：Lambda 与函数作参数](#第-8-课lambda-与函数作参数)
9. [第 9 课：扩展函数](#第-9-课扩展函数)
10. [第 10 课：实战 —— 完整读懂一个函数](#第-10-课实战--完整读懂一个函数)

---

## 第 1 课：变量 val 和 var

Kotlin 声明变量只有两个关键字：

| 关键字 | 含义 | 能否重新赋值 |
|---|---|---|
| `val` | value，值 | ❌ 不能（像 Java 的 final） |
| `var` | variable，变量 | ✅ 能 |

**项目实例**（`GameView.kt`）：

```kotlin
// var —— 会变的值，游戏里一直在变
private var px = 0f              // 玩家 x 坐标，每帧都在改
private var lives = 3            // 生命数，吃到道具会增加
private var score = 0            // 分数，打中敌机就加

// val —— 定下来就不变的值
private val playerR = dp(16f)                        // 玩家半径，固定
private val bullets = ArrayList<Bullet>(256)         // 列表本身不变（但里面的内容会变）
private const val FIXED = 1f / 60f                   // 编译期常量
```

**关键点：`val` 修饰的是「引用」不是「内容」**

这行容易看错：

```kotlin
private val bullets = ArrayList<Bullet>(256)
bullets.add(b)          // ✅ 合法！往列表里加东西没问题
bullets = ArrayList()   // ❌ 编译报错，不能把 bullets 指向另一个列表
```

记住：`val` 意思是「这个**名字**只能绑定到一个对象」，不是说对象内部不能改。

**为什么原作者大量用 `private`？**

`private` 表示「只在当前类内部可见」。`GameView` 里的字段全是 `private`，外面碰不到——这是好习惯，改起来不怕牵连别处。

**练习 1**：看看 `Sprites.kt` 第 37~49 行，数一下有几个 `val`、几个 `var`，猜猜各自为什么这么选。

---

## 第 2 课：类型与类型推断

**Kotlin 把类型写在名字后面，用冒号隔开**：

```kotlin
var name: String = "yuqi"      // 类型: String
var hp: Int = 100              // 类型: Int
var speed: Float = 1.5f        // 类型: Float
var alive: Boolean = true      // 类型: Boolean
```

和 Java 对比（Java 是类型在前）：

```java
String name = "yuqi";
int hp = 100;
```

**类型推断：能猜出来就不用写**

```kotlin
var px = 0f            // 编译器知道 0f 是 Float，不用写 : Float
var lives = 3          // 3 是 Int，不用写 : Int
var hudScore = "0"     // "0" 是 String
```

项目里几乎所有字段都省略了类型——这不是偷懒，是 Kotlin 的常规写法。

**常用数字类型速查**

| 类型 | 说明 | 例子 |
|---|---|---|
| `Int` | 整数 | `3`、`600`（分数、血量） |
| `Float` | 单精度小数（**游戏坐标全靠它**） | `0f`、`1.5f`、`dp(16f)` |
| `Double` | 双精度小数 | `3.14159` |
| `Long` | 长整数 | `DateTime`、时间戳 |
| `Boolean` | 真假 | `true`、`false` |

⚠️ **`f` 后缀别漏**：`1.5` 是 `Double`，`1.5f` 才是 `Float`。游戏里传 `Double` 给 `Float` 参数会编译报错。这就是为什么项目里满屏都是 `dp(16f)`、`0.5f`、`2.2f`。

**十六进制颜色**

```kotlin
0xFFE3F2FD.toInt()   // 0x = 十六进制，FF=不透明，E3F2FD=淡蓝
```

`0xFFE3F2FD` 是 `Long`（因为溢出了 `Int` 范围），所以必须 `.toInt()` 转一下才能当颜色用。项目里所有颜色都是这个写法。

**字符串模板（超好用）**

```kotlin
val score = 1200
hudPauseInfo = "当前得分 $score · 最高 $highScore"   // $ 直接嵌入变量
```

对比 Java 得写 `"当前得分 " + score + " · 最高 " + highScore`。

要嵌表达式用花括号：

```kotlin
c.drawText("连击 x$combo", x, y, paint)              // 简单变量
pushText(x, y, "火力 Lv." + weaponLevel, ...)        // 拼接也行
```

**练习 2**：在第 2 课的基础上，去 `GameView.kt` 搜索 `0xFF`，随便挑 3 个颜色，说出它们分别是什么颜色（提示：RGB 三段）。

---

## 第 3 课：函数 fun

Kotlin 用 `fun` 定义函数：

```kotlin
// 带参数的函数
private fun dp(v: Float) = v * density

// 等价的完整写法
private fun dp(v: Float): Float {
    return v * density
}
```

**两种写法怎么选？**

- 函数体只有一行 → 用 `=` 简写，更清爽
- 有多行逻辑 → 用 `{}`

**项目实例对比**

```kotlin
// 简写：一行搞定
private fun fireInterval(): Float = max(0.062f, 0.125f - weaponLevel * 0.012f)

// 完整：多行逻辑
private fun shoot() {
    val spd = -dp(790f)
    val noseY = py - playerR * 1.5f
    when (weaponLevel) {
        1 -> fireBullet(px, noseY, 0f, spd, dp(5f), 1)
        // ...
    }
}
```

**返回类型可省略时**

```kotlin
private fun nextSpawnInterval() = max(0.24f, 0.92f - elapsed * 0.013f)   // 编译器推断返回 Float
```

**默认参数值（Java 做不到的）**

```kotlin
// GameView.kt 第 1438 行
private fun drawPlaneBmp(c: Canvas, bmp: Bitmap, x: Float, y: Float, r: Float, alpha: Int = 255)
```

`alpha: Int = 255` 表示调用时可以不传：

```kotlin
drawPlaneBmp(c, sp.player, px, py, playerR)              // alpha 自动 = 255
drawPlaneBmp(c, bmp, e.x, e.y, e.r, 128)                 // 显式传 128（半透明）
```

Java 要实现同样效果得写两个重载方法，Kotlin 一行就够了。

**单例/常量用 `companion object`**

```kotlin
private companion object {
    const val FIXED = 1f / 60f
    const val MAX_STEPS = 6
}
```

`companion object` 相当于 Java 的 `static`，`const val` 相当于 `static final`。

**练习 3**：在 `GameView.kt` 里找出所有「用 `=` 简写的单行函数」，看看一共几个。（提示：搜 `fun ` 然后看行尾有没有 `=`）

---

## 第 4 课：条件与 when

**if 表达式能直接赋值**（比 Java 强，不用写三元运算符）：

```kotlin
val a = if (bombs > 0) 0x44FFD54F else 0x22FFFFFF
```

**`when` 是 Kotlin 的 switch 加强版**——能返回值、能匹配区间、能匹配类型：

```kotlin
// 项目实例：按武器等级决定开火间隔
private fun fireCooldown(kind: Int): Float = when (kind) {
    K_TANK -> 1.6f
    K_ELITE -> 1.3f
    K_BOSS -> when (bossPhase) {
        1 -> 1.35f
        2 -> 1.0f
        else -> 0.72f
    }
    else -> 1.5f
}
```

注意三点：
1. **不用写 `break`**（Java 的 switch 最容易忘 break 导致穿透，Kotlin 没这问题）
2. **`when` 是个表达式**，可以直接 `return` 或赋值
3. **`else ->` 相当于 default**，是必须的（除非编译器能确认已穷尽所有情况）

**无参数的 when，相当于 if-else 链**

```kotlin
// spawnEnemy() 里按权重随机选敌人类型
when {
    r < wScout -> { /* 侦察机 */ }
    r < wScout + wZig -> { /* 蛇形机 */ }
    r < wScout + wZig + wTank -> { /* 坦克机 */ }
    else -> { /* 精英机 */ }
}
```

这种写法里，`when` 后面不写括号，每个分支是独立的布尔条件，从上往下第一个成立的执行——比一串 `else if` 清爽。

**`->` 后面只有一行可以省略花括号**

```kotlin
when (state) {
    State.READY -> startGame()
    State.PAUSED -> enterPause()
    else -> Unit      // Unit = 什么都不做（Kotlin 的 void）
}
```

**练习 4**：读 `updateEnemies()`（第 739~788 行）的 `when (e.kind)`，说出 6 种敌人各自是怎么移动的。

---

## 第 5 课：循环

| 需求 | Kotlin | Java |
|---|---|---|
| 0 到 9 | `for (i in 0 until 10)` | `for (int i=0; i<10; i++)` |
| 1 到 10 | `for (i in 1..10)` | `for (int i=1; i<=10; i++)` |
| 3 到 1 倒数 | `for (i in 3 downTo 1)` | `for (int i=3; i>=1; i--)` |
| 隔一个 | `for (i in 0 until 10 step 2)` | `for (int i=0; i<10; i+=2)` |
| 遍历列表 | `for (b in bullets)` | `for (Bullet b : bullets)` |

**项目实例 1：倒序遍历（超重要）**

```kotlin
for (i in bullets.indices.reversed()) {
    val b = bullets[i]
    // ... 里面可能 bullets.fastRemove(i)
}
```

`bullets.indices` = 所有合法下标（`0..size-1`），`.reversed()` 反过来。

**为什么要倒着走？** 因为循环里会删除元素。正着走的话，删掉第 2 个元素后，原来第 3 个变成了第 2 个，而 `i` 已经加到 3 了——那个元素就被跳过了。倒着删就不受影响。

**项目实例 2：区域遍历**

```kotlin
for (k in -2..2) {   // 依次取 -2, -1, 0, 1, 2 —— BOSS 的 5 发扇形弹幕
    val ang = k * 0.28f
    enemyBullet(e.x, e.y + e.r * 0.5f, sin(ang) * spd, cos(ang) * spd, dp(8f))
}
```

**项目实例 3：遍历并取下标**

```kotlin
for ((i, s) in stars.withIndex()) { ... }   // 同时拿到下标和元素
```

**`repeat` 重复 n 次**

```kotlin
repeat(3) { ... }    // 等价于 for (i in 0 until 3)，但不用关心 i
```

**练习 5**：在 `Sprites.kt` 的 `build()` 里，为什么猎空敌机是 `arrayOfNulls<Bitmap>(6)` 而不是普通数组？（提示：`arrayOfNulls` 创建的是「可空」数组，凑合理解第 7 课）

---

## 第 6 课：类与构造器

**最简写法：一行搞定一个类**

```kotlin
class Bullet {
    var x = 0f
    var y = 0f
    var r = 0f
    var friendly = true
    var power = 1
    var missile = false
    var homing = false
}
```

**对比 Java**——每个字段都要写 getter/setter（或用 Lombok），Kotlin 的 `var` 自动生成。访问就是 `b.x = 5f`。

**构造器直接写在类名后面**

```kotlin
// GameView.kt 第 151 行
class GameView(context: Context, private val sound: SoundManager) : View(context)
```

拆解：

| 片段 | 含义 |
|---|---|
| `class GameView` | 定义类 |
| `(context: Context, private val sound: SoundManager)` | **主构造器**：创建对象时传的参数 |
| `context` | 普通参数，只在构造时用，不存为字段 |
| `private val sound` | 加了 `val` → **自动变成类的属性**，后面 `sound.play()` 直接能用 |
| `: View(context)` | **继承** View（Java 的 `extends View`），并把 context 传给父类构造器 |

**继承要注意**：Kotlin 的类默认是 `final`（不能被继承），要能被继承得加 `open`。`View` 是 Android 框架的类，它已经是 `open` 的，所以能继承。

**`init` 块：对象创建时自动执行的代码**

```kotlin
init {
    highScore = prefs.getInt("high", 0)      // 从存档读最高分
    hudHigh = "最高 $highScore"
    sp.build()                                // 烘焙所有精灵图
    isFocusable = true
}
```

字段初始化和 `init` 块按书写顺序执行——所以 `sp.build()` 放在 `init` 里能保证「精灵先备好，再开始游戏」。

**`enum class`：一组固定的值**

```kotlin
private enum class State { READY, PLAYING, PAUSED, GAME_OVER }
```

用的时候 `State.PLAYING`。比用 `0/1/2/3` 这种魔法数字强太多——写错了编译期就报错。

**`object`：单例**

```kotlin
object MySingleton {          // 整个程序只有这一个实例
    fun doSomething() { }
}
```

**练习 6**：看 `Enemy` 类（第 79~103 行），它有一个 `reset()` 方法。想一想——为什么需要「重置」而不是「直接新建一个」？（提示：第 8 课会讲到对象池）

---

## 第 7 课：空安全 `?.` 和 `?:`

**这是 Kotlin 最值钱的特性，务必掌握。**

Android 上最常见的崩溃就是**空指针异常**（NullPointerException）。Java 里这样写：

```java
Vibrator v = getVibrator();
v.vibrate(100);        // 如果 v 是 null → 运行时崩溃！
```

Kotlin 从**类型层面**解决这个问题：默认类型不可为空，要能放 null 必须加 `?`：

```kotlin
var v: Vibrator = ...       // 这个变量永不为 null
var v: Vibrator? = ...      // 这个变量可能是 null
```

**对可空类型，你只有三种处理方式：**

### 方式一：`?.` —— 安全调用

```kotlin
v?.vibrate(100)    // v 不为 null 才执行；是 null 就整行跳过，不崩
```

### 方式二：`?:` —— 空则给备选值（Elvis 运算符）

```kotlin
val v = vibrator ?: return      // vibrator 是 null 就直接 return 结束函数
```

项目实例（第 1375~1378 行）：

```kotlin
private fun buzz(ms: Long) {
    val v = vibrator ?: return                    // 手机没振动器？不干活，不崩
    v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
}
```

### 方式三：`!!` —— 强行说「我保证不为空」

```kotlin
v!!.vibrate(100)   // 如果真是 null → 崩。**尽量别用**，等于放弃保护
```

**再看一个项目里的例子**（第 1472~1473 行）：

```kotlin
for (e in enemies) {
    val bmp = sp.enemies[e.kind] ?: continue   // 取不到图就跳过这架敌机
    // ... 下面可以放心用 bmp，因为到这说明它一定不是 null
}
```

**为什么 `arrayOfNulls` 需要判空？**

```kotlin
val enemies = arrayOfNulls<Bitmap>(6)   // 创建了一个「6 个格子，都装着 null」的数组
```

取出来的是 `Bitmap?`，所以必须用 `?:` 处理——**编译器会强制你面对这个问题**。这就是第 5 课练习 5 的答案。

**手动判空也行，但 Kotlin 写法更顺**

```kotlin
// 啰嗦版
if (bmp != null) { c.drawBitmap(bmp, null, dst, spritePaint) }

// Kotlin 风
bmp?.let { c.drawBitmap(it, null, dst, spritePaint) }   // it 就是 bmp（非空版）
```

**练习 7**：搜 `?:` 在 `GameView.kt` 里出现了几次，每次是不是都在防崩溃？（提示：搜 `bossRef`）

---

## 第 8 课：Lambda 与函数作参数

**Lambda = 一段可以当参数传的代码。**

先看最简单形式：

```kotlin
val double = { x: Int -> x * 2 }     // 一个函数，存进变量
double(5)                             // 结果 10
```

**项目里最漂亮的例子 —— 对象池 `Pool`**（第 45~53 行）：

```kotlin
private class Pool<T>(private val factory: () -> T, private val cap: Int) {
    private val free = ArrayList<T>()
    fun obtain(): T = if (free.isEmpty()) factory() else free.removeAt(free.size - 1)
    fun recycle(v: T) {
        if (free.size < cap) free.add(v)
    }
}
```

拆解类型标注 `factory: () -> T`：

| 部分 | 含义 |
|---|---|
| `()` | 这个函数**不要参数** |
| `->` | 返回 |
| `T` | 返回类型是 T（泛型，创建时决定） |

完整意思是「factory 是一个不需要参数、返回 T 的函数」。什么意思呢？**就是「造一个 T 的方法」**。传进来什么，`obtain()` 就能造什么：

```kotlin
private val bulletPool = Pool({ Bullet() }, 512)     // 传入「造子弹」的 lambda
private val enemyPool = Pool({ Enemy() }, 64)        // 传入「造敌机」的 lambda
private val particlePool = Pool({ Particle() }, 900) // 传入「造粒子」的 lambda
```

`{ Bullet() }` 就是一个 lambda：调用它 = `new Bullet()`。这样写一次 Pool，子弹/敌机/粒子全都能复用，不用写三份。

**参数只有一个时用 `it`**

```kotlin
pool.setOnLoadCompleteListener { _, sampleId, status ->
    if (status != 0) return@setOnLoadCompleteListener
    val idx = ids.indexOf(sampleId)
    if (idx >= 0) ready[idx] = true
}
```

`{ _, sampleId, status -> ... }` 三个参数用 `_` 忽略不用的那个。单个参数就可以省掉声明直接用 `it`：

```kotlin
bullets.forEach { it.y -= dt }        // it 就是每个 bullet
```

**`forEach` / `map` / `filter` 这类链式操作**

```kotlin
// 传统写法
for (i in 0 until owned.size) {
    if (!owned[i].isRecycled) owned[i].recycle()
}

// Kotlin 链式写法
owned.filter { !it.isRecycled }.forEach { it.recycle() }
```

**`inline fun` 是什么？**

```kotlin
private inline fun <T> ArrayList<T>.fastRemove(i: Int) {
    val last = size - 1
    if (i != last) this[i] = this[last]
    removeAt(last)
}
```

`inline` 让编译器把这个函数的代码**直接抄到调用处**，省掉函数调用的开销。游戏里 `fastRemove` 每帧要调用几百次，这个优化有意义。

**练习 8**：把 `Pool` 类翻译成你会说的自然语言，讲一遍「obtain 和 recycle 是怎么配合的」。

---

## 第 9 课：扩展函数

**这是 Kotlin 独有的能力：给一个已经存在的类「加方法」，而不用修改它的源码，也不用继承。**

项目实例（第 34~38 行）：

```kotlin
private inline fun <T> ArrayList<T>.fastRemove(i: Int) {
    val last = size - 1
    if (i != last) this[i] = this[last]
    removeAt(last)
}
```

拆解：

| 片段 | 含义 |
|---|---|
| `ArrayList<T>.` | **接收者类型**——这个函数是「挂在 ArrayList 上」的 |
| `fastRemove(i: Int)` | 函数名和参数 |
| `this` / `size` | 在函数内部，`this` 就是调用它的那个列表 |

于是就能这样用：

```kotlin
bullets.fastRemove(i)      // 感觉像是 ArrayList 自带的方法
```

**说白了，它等价于 Java 的静态工具方法**：

```java
// Java 只能这么写，调用时很别扭
public static <T> void fastRemove(ArrayList<T> list, int i) {
    int last = list.size() - 1;
    if (i != last) list.set(i, list.get(last));
    list.remove(last);
}
// 调用：ListUtil.fastRemove(bullets, i)
```

区别就是调用形式：`bullets.fastRemove(i)` vs `ListUtil.fastRemove(bullets, i)`。前者读起来像「列表自己的能力」。

**注意**：扩展函数不能访问类的 `private` 成员，也不能真正改变这个类——它只是「看起来像」。

**为什么原作者要写这个而不是用 `removeAt(i)`？**

因为 `ArrayList.removeAt(i)` 会把后面所有元素往前挪一格，代价是 O(n)；`fastRemove` 把队尾元素挪到空位再删队尾，代价是 O(1)。游戏每帧要删几百个元素，这个差别很大——**这就是「性能优化」在代码里的样子**。

**练习 9**：想一个你自己项目里能用的扩展函数（比如 `String.isNotBlank` 已经有了，那 `String.toScore()` 呢？）

---

## 第 10 课：实战 —— 完整读懂一个函数

我们来逐行读 `stepParticles()`（第 1205~1219 行），这是**粒子更新**，每帧都会跑。

```kotlin
private fun stepParticles(dt: Float) {
```
- `private fun` —— 私有函数
- `dt: Float` —— 参数，这一帧过去了多少秒（`dt` = delta time）

```kotlin
    for (i in particles.indices.reversed()) {
```
- `particles.indices` = 0 到 size-1 的所有下标
- `.reversed()` —— 倒序。**因为下面要删元素**（第 5 课讲过的坑）

```kotlin
        val p = particles[i]
```
- `val` —— 不变，就是取出当前这个粒子，起个短名字省打字

```kotlin
        p.x += p.vx * dt
        p.y += p.vy * dt
```
- `+=` 就是 `p.x = p.x + (...)`，简写
- 物理公式：**位移 = 速度 × 时间**。`vx` 是水平速度，`vy` 是垂直速度

```kotlin
        val k = 1f - dt * p.drag
        p.vx *= k
        p.vy *= k
```
- `drag` 是阻尼（阻力）。
- `k = 1 - dt*2.2`，每帧把速度乘上这个略小于 1 的数 → **速度逐渐衰减，粒子慢下来**。这就是「阻力」的实现方式。

```kotlin
        p.life -= dt
```
- 寿命倒计时，每帧减掉流逝的时间

```kotlin
        if (p.life <= 0f) {
            particles.fastRemove(i)
            particlePool.recycle(p)
        }
```
- 寿命耗尽 → 从活动列表移除（O(1)），**并且归还给对象池**，下次还能复用，不产生垃圾对象

```kotlin
    }
}
```

**完整逻辑用中文说一遍**：

> 遍历所有粒子（倒序，因为要删东西）：
> 1. 按速度移动位置
> 2. 施加阻力让速度衰减
> 3. 寿命减一帧
> 4. 寿命到了就移除，并把对象还回池子

**这就是游戏编程里 90% 的「更新」函数的通用结构**：`遍历 → 改状态 → 检查该不该消失 → 移除并回收`。

`updateBullets()`、`updatePowerups()`、`stepRings()`、`stepTexts()` 全是同一个套路——你看懂一个，其他四个自动就懂了。

**最终练习**：打开 `stepRings()`（第 1227 行），对照上面的结构，说出它和 `stepParticles()` 有哪几处不同、为什么不同。

---

## 学完之后

你现在能读懂项目里**超过一半的代码**了。剩下的部分主要是两类：

1. **Android 框架 API**（`Canvas`、`Paint`、`Bitmap`、`SoundPool`）——这不是 Kotlin 语言问题，是「Android 怎么画画/放声音」的 API 知识，用到哪个查哪个就行。
2. **游戏数学**（正弦波动、向量插值、碰撞检测）——属于游戏开发技巧，跟语言无关。

**建议的下一步练习**：

打开 `GameView.kt`，挑这些函数自己读一遍并说给我听，我帮你检查理解对不对：

| 函数 | 行号 | 你会学到 |
|---|---|---|
| `driftStars()` | 441 | 最简单的遍历更新 |
| `hurt()` | 1081 | 游戏失败逻辑 + 存档 |
| `applyPowerUp()` | 1001 | when 表达式实战 |
| `useBomb()` | 1121 | 全屏效果实现 |

有问题随时问——包括「这行我看不懂」，把行号发我就行。
