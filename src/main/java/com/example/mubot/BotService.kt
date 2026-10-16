package com.example.mubot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

/**
 * MU Immortal - Raklion kirmizi boss avi.
 *
 * Rota: Raklion 2 (kanal 1,2,3) -> Raklion 1 (kanal 1,2,3) -> basa don.
 *
 * Her adimda:
 *  1) (buff acik ise) ATK ve DEF okunur; ikisi de minimumun altindaysa buff noktasina gidilir.
 *  2) Harita acilir. Canli kirmizi boss ikonlari ve yesil oyuncu imleci bulunur
 *     (olu boss ikonu soluk oldugu icin gorunmez).
 *  3) Canli boss varsa en yakinina dokunulur, imlec ikonun ustune gelince harita kapanir ve
 *     oto saldiri acilir.
 *  4) Sag ustteki mini haritada boss ikonu soluklasana kadar savasilir.
 *  5) Kanalda canli boss kalmayinca siradaki kanala (veya haritaya) gecilir.
 */
class BotService : AccessibilityService() {

    private class StopEx : RuntimeException()
    private class Restart : RuntimeException()

    private class Scan(val bosses: List<PointF>, val player: PointF?)

    private class MarkView(
        ctx: Context,
        val bosses: List<PointF>,
        val player: PointF?,
        val dots: List<Triple<String, Float, Float>>,
        val rect: FloatArray?
    ) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 6f
            p.color = Color.RED
            for (s in bosses) c.drawCircle(s.x, s.y, 40f, p)
            if (player != null) {
                p.color = Color.GREEN
                c.drawCircle(player.x, player.y, 22f, p)
            }
            if (rect != null) {
                p.color = Color.CYAN
                c.drawRect(rect[0], rect[1], rect[2], rect[3], p)
            }
            p.style = Paint.Style.FILL
            p.textSize = 26f
            for (d in dots) {
                p.color = Color.MAGENTA
                c.drawCircle(d.second, d.third, 9f, p)
                p.color = Color.WHITE
                c.drawText(d.first, d.second + 12f, d.third - 8f, p)
            }
        }
    }

    companion object {
        val NAMES = listOf(
            "minimap", "closeMap", "autoAtk", "miniTL", "miniBR",
            "wire", "ch1", "ch2", "ch3", "switchLine", "closeChannel",
            "chat", "chatTab", "linkR1", "linkR2", "buffLink", "closeChat", "revive"
        )

        val LABELS = mapOf(
            "minimap" to "Sağ üstteki mini harita (haritayı açar)",
            "closeMap" to "Harita ekranı X (kapat) düğmesi",
            "autoAtk" to "Oto saldırı (Auto / Manual) düğmesi",
            "miniTL" to "Mini haritanın SOL ÜST köşesi",
            "miniBR" to "Mini haritanın SAĞ ALT köşesi",
            "wire" to "Wire / Switch düğmesi (kanal ekranını açar)",
            "ch1" to "Kanal ekranında 1. kanal",
            "ch2" to "Kanal ekranında 2. kanal",
            "ch3" to "Kanal ekranında 3. kanal",
            "switchLine" to "Kanal ekranındaki Switch Line düğmesi",
            "closeChannel" to "Kanal ekranı X (kapat) düğmesi",
            "chat" to "Chat'i açan düğme/alan",
            "chatTab" to "Chat'te koordinatların durduğu sekme",
            "linkR1" to "Chat'te Raklion 1 başlangıç noktasının bağlantısı",
            "linkR2" to "Chat'te Raklion 2 başlangıç noktasının bağlantısı",
            "buffLink" to "Chat'te buff noktasının bağlantısı",
            "closeChat" to "Chat'i kapatma (isteğe bağlı)",
            "revive" to "Ölüm ekranında Free Revival düğmesi (ölüm ekranı açıkken işaretle)"
        )

        // Ekran goruntulerinden (2419x1512) hesaplanan oranlar; eski muimm ayarlari korunur
        val DEFAULTS = mapOf(
            "minimap" to Pair(0.922f, 0.046f),
            "closeMap" to Pair(0.894f, 0.157f),
            "autoAtk" to Pair(0.972f, 0.473f),
            "miniTL" to Pair(0.865f, 0.012f),
            "miniBR" to Pair(0.985f, 0.190f),
            "wire" to Pair(0.992f, 0.050f),
            "ch1" to Pair(0.498f, 0.326f),
            "ch2" to Pair(0.498f, 0.456f),
            "ch3" to Pair(0.498f, 0.581f),
            "switchLine" to Pair(0.499f, 0.739f),
            "closeChannel" to Pair(0.637f, 0.202f)
        )

        // Rota: (harita, kanal)
        val ROUTE = listOf(
            Pair(2, 1), Pair(2, 2), Pair(2, 3),
            Pair(1, 1), Pair(1, 2), Pair(1, 3)
        )

        // Haritalardaki boss dogma noktalari (ekran orani): sayac anahtari icin kullanilir
        val SPAWNS = mapOf(
            1 to listOf(Pair(0.470f, 0.461f), Pair(0.506f, 0.600f)),
            2 to listOf(Pair(0.612f, 0.542f))
        )

        // harita cizim alani (ekran orani). Sol liste ve Random/yakinlastirma cubugu disarida kalir.
        const val MAP_X0 = 0.30f
        const val MAP_X1 = 0.84f
        const val MAP_Y0 = 0.18f
        const val MAP_Y1 = 0.84f
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var panel: LinearLayout? = null
    private var menu: LinearLayout? = null
    private var extra: View? = null
    private var timerPanel: LinearLayout? = null
    private val timerRows = ArrayList<TextView>()
    private var statusView: TextView? = null
    private var statsView: TextView? = null
    private var startBtn: Button? = null
    private var routeBtn: Button? = null
    private var dotView: View? = null

    private val ACCENT = 0xFFE5603D.toInt()
    private val PANEL_BG = 0xF2161920.toInt()
    private val CARD_BG = 0xFF272C3A.toInt()
    private val MISSING_BG = 0xFF4A2429.toInt()
    private val TEXT_DIM = 0xFF9AA3B5.toInt()
    private val OK = 0xFF3DDC84.toInt()
    private val BAD = 0xFFE5484D.toInt()

    @Volatile
    private var running = false
    private var worker: Thread? = null

    private var sent = 0
    private var cancelled = 0
    private var kills = 0
    private var killsR1 = 0
    private var killsR2 = 0
    private var deaths = 0
    private var buffs = 0
    private var laps = 0
    private var lastShot = 0L
    private var screenW = 1
    private var screenH = 1
    private var lastOcr = ""
    private var lastTitle = ""

    // bot hangi haritada / kanalda oldugunu tahmin eder, harita basligindan duzeltir
    private var curMap = 0
    private var curLine = 0
    private var step = 0

    private val killAt = HashMap<String, Long>()

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    // ------------------------------------------------------------------ yasam dongusu

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        refreshScreen()
        migratePrefs()
        loadTimers()
        buildPanel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        try {
            removeExtra()
            panel?.let { wm.removeView(it) }
            menu?.let { wm.removeView(it) }
            timerPanel?.let { wm.removeView(it) }
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ yardimcilar

    private fun ui(block: () -> Unit) {
        main.post { block() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun say(msg: String) {
        ui {
            statusView?.text = msg
            statsView?.text = "Boss " + kills + " (R2 " + killsR2 + " · R1 " + killsR1 + ")  ·  Ölüm " +
                deaths + "  ·  Buff " + buffs + "  ·  Tur " + laps
        }
    }

    private fun refreshScreen() {
        val b = wm.maximumWindowMetrics.bounds
        screenW = b.width()
        screenH = b.height()
    }

    private fun prefs() = getSharedPreferences("mubot", Context.MODE_PRIVATE)

    /** Eski surumdeki 'manual' noktasini yeni 'autoAtk' noktasina tasir. */
    private fun migratePrefs() {
        val p = prefs()
        if (!p.contains("autoAtk_x") && p.contains("manual_x")) {
            p.edit()
                .putFloat("autoAtk_x", p.getFloat("manual_x", 0.972f))
                .putFloat("autoAtk_y", p.getFloat("manual_y", 0.473f))
                .apply()
        }
    }

    private fun cfgF(key: String, def: Float): Float =
        prefs().getString("cfg_" + key, null)?.toFloatOrNull() ?: def

    private fun cfgI(key: String, def: Int): Int = cfgF(key, def.toFloat()).toInt()

    private fun cfgB(key: String, def: Boolean): Boolean = prefs().getBoolean("cfg_" + key, def)

    private fun hasPt(name: String): Boolean = prefs().contains(name + "_x") || DEFAULTS.containsKey(name)

    private fun pt(name: String): Pair<Float, Float> {
        val d = DEFAULTS[name] ?: Pair(0.5f, 0.5f)
        val p = prefs()
        return Pair(p.getFloat(name + "_x", d.first), p.getFloat(name + "_y", d.second))
    }

    private fun savePt(name: String, x: Float, y: Float) {
        prefs().edit().putFloat(name + "_x", x).putFloat(name + "_y", y).apply()
    }

    private fun pause(ms: Int) {
        var left = ms
        while (left > 0) {
            if (!running) throw StopEx()
            val s = if (left < 100) left else 100
            Thread.sleep(s.toLong())
            left -= s
        }
        if (!running) throw StopEx()
    }

    private fun fail(msg: String): Nothing {
        say(msg)
        throw StopEx()
    }

    private fun dist(a: PointF, b: PointF): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun routeName(i: Int): String {
        val r = ROUTE[i]
        return "Raklion " + r.first + " / kanal " + r.second
    }

    // ------------------------------------------------------------------ ekran okuma

    private fun grab(): Bitmap? {
        val gap = 1100L - (System.currentTimeMillis() - lastShot)
        if (gap > 0) Thread.sleep(gap)
        lastShot = System.currentTimeMillis()
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hb = screenshot.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                        if (hw != null) {
                            result = hw.copy(Bitmap.Config.ARGB_8888, false)
                            hw.recycle()
                        }
                        hb.close()
                    } catch (e: Exception) {
                    }
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    say("Ekran görüntüsü alınamadı (kod " + errorCode + ")")
                    latch.countDown()
                }
            })
        } catch (e: Exception) {
            say("Ekran görüntüsü hatası: " + e.message)
            return null
        }
        latch.await(3, TimeUnit.SECONDS)
        return result
    }

    /** Ekran goruntusu al ve olum ekranindaysa canlan. */
    private fun look(): Bitmap? {
        val b = grab() ?: return null
        if (isDeadScreen(b)) {
            // tek bir kareye güvenme: biraz bekleyip tekrar bak
            pause(1200)
            val b2 = grab()
            if (b2 != null && isDeadScreen(b2)) {
                deaths++
                say("Öldün, Free Revival'a basılıyor")
                tapPoint("revive")
                pause(4000)
                curMap = 0
                throw Restart()
            }
            return b2 ?: b
        }
        return b
    }

    /** Haritadaki canli boss ikonunun turuncu-kirmizi alev rengi. Olu ikon soluk oldugu icin buna girmez. */
    private fun isBossRed(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return r >= 165 && b <= 90 && g >= 35 && r - g >= 70 && r - b >= 110
    }

    private fun isGreen(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return r < 120 && g > 170 && b < 120 && g - r > 80
    }

    private fun isOrange(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return r > 200 && g in 110..210 && b < 90
    }

    private fun orangeAt(b: Bitmap, name: String): Boolean {
        val p = pt(name)
        val cx = p.first * b.width
        val cy = p.second * b.height
        val hw = 0.012f * b.width
        val hh = 0.020f * b.height
        val x0 = maxOf(0, (cx - hw).toInt())
        val x1 = minOf(b.width - 1, (cx + hw).toInt())
        val y0 = maxOf(0, (cy - hh).toInt())
        val y1 = minOf(b.height - 1, (cy + hh).toInt())
        var hit = 0
        var tot = 0
        for (y in y0..y1) {
            for (x in x0..x1) {
                tot++
                if (isOrange(b.getPixel(x, y))) hit++
            }
        }
        return tot > 0 && hit.toFloat() / tot >= 0.12f
    }

    /** Acik haritada canli kirmizi boss ikonlarini ve yesil oyuncu imlecini bulur. */
    private fun scanMap(b: Bitmap): Scan {
        val bw = b.width
        val bh = b.height
        val s = bw / 2560f
        val x0 = (MAP_X0 * bw).toInt()
        val x1 = (MAP_X1 * bw).toInt()
        val y0 = (MAP_Y0 * bh).toInt()
        val y1 = (MAP_Y1 * bh).toInt()
        val w = x1 - x0
        val h = y1 - y0
        val cell = maxOf(2, Math.round(3 * s))
        val cw = w / cell
        val ch = h / cell
        val cnt = IntArray(cw * ch)
        var gx = 0L
        var gy = 0L
        var gn = 0
        val row = IntArray(w)
        for (y in 0 until h) {
            b.getPixels(row, 0, w, x0, y0 + y, w, 1)
            val cy = y / cell
            for (x in 0 until w) {
                val c = row[x]
                if (isBossRed(c)) {
                    val cx = x / cell
                    if (cx < cw && cy < ch) cnt[cy * cw + cx]++
                } else if (isGreen(c)) {
                    gx += x
                    gy += y
                    gn++
                }
            }
        }
        val need = maxOf(2, cell * cell / 4)
        val hit = BooleanArray(cw * ch)
        for (i in hit.indices) hit[i] = cnt[i] >= need

        val seen = BooleanArray(cw * ch)
        val stack = IntArray(cw * ch)
        val bosses = ArrayList<PointF>()
        for (start in hit.indices) {
            if (!hit[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var minX = cw
            var maxX = -1
            var minY = ch
            var maxY = -1
            var n = 0
            while (sp > 0) {
                val cur = stack[--sp]
                val cx = cur % cw
                val cy = cur / cw
                n++
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = cx + dx
                        val ny = cy + dy
                        if (nx < 0 || ny < 0 || nx >= cw || ny >= ch) continue
                        val ni = ny * cw + nx
                        if (hit[ni] && !seen[ni]) {
                            seen[ni] = true
                            stack[sp++] = ni
                        }
                    }
                }
            }
            val pw = (maxX - minX + 1) * cell / s
            val ph = (maxY - minY + 1) * cell / s
            val area = n * cell * cell / (s * s)
            // boss ikonu ~48x60-72 piksel; yazi (yukseklik ~25) ve kucuk kirmizi noktalar elenir
            if (pw >= 34 && pw <= 100 && ph >= 44 && ph <= 115 && area >= 900) {
                bosses.add(
                    PointF(
                        x0 + (minX + maxX + 1) * cell / 2f,
                        y0 + (minY + maxY + 1) * cell / 2f
                    )
                )
            }
        }
        var player: PointF? = null
        if (gn >= 15 * s * s) player = PointF(x0 + gx.toFloat() / gn, y0 + gy.toFloat() / gn)
        return Scan(bosses, player)
    }

    /** Mini haritanin ortasinda canli boss ikonu var mi? Oran dondurur. */
    private fun miniFrac(b: Bitmap): Float {
        val a = pt("miniTL")
        val c = pt("miniBR")
        val cxm = (a.first + c.first) / 2f
        val cym = (a.second + c.second) / 2f
        val hw = (c.first - a.first) / 2f * 0.6f
        val hh = (c.second - a.second) / 2f * 0.6f
        val x0 = maxOf(0, ((cxm - hw) * b.width).toInt())
        val x1 = minOf(b.width - 1, ((cxm + hw) * b.width).toInt())
        val y0 = maxOf(0, ((cym - hh) * b.height).toInt())
        val y1 = minOf(b.height - 1, ((cym + hh) * b.height).toInt())
        if (x1 <= x0 || y1 <= y0) return 0f
        val w = x1 - x0 + 1
        val row = IntArray(w)
        var hit = 0
        for (y in y0..y1) {
            b.getPixels(row, 0, w, x0, y, w, 1)
            for (x in 0 until w) if (isBossRed(row[x])) hit++
        }
        return hit.toFloat() / (w * (y1 - y0 + 1))
    }

    private fun miniAlive(b: Bitmap): Boolean = miniFrac(b) >= 0.004f

    // ------------------------------------------------------------------ yazi okuma (ATK/DEF, harita basligi)

    private fun ocr(b: Bitmap, fx0: Float, fy0: Float, fx1: Float, fy1: Float): String? {
        return try {
            val x0 = (fx0 * b.width).toInt()
            val y0 = (fy0 * b.height).toInt()
            val w = ((fx1 - fx0) * b.width).toInt()
            val h = ((fy1 - fy0) * b.height).toInt()
            val crop = Bitmap.createBitmap(b, x0, y0, w, h)
            val big = Bitmap.createScaledBitmap(crop, w * 3, h * 3, true)
            val res = Tasks.await(recognizer.process(InputImage.fromBitmap(big, 0)), 10, TimeUnit.SECONDS)
            res.text.replace("\n", " ")
        } catch (e: Exception) {
            lastOcr = "hata: " + e.message
            null
        }
    }

    private fun readStats(b: Bitmap): Pair<Int, Int>? {
        val t = ocr(b, 0f, 0f, 0.27f, 0.07f) ?: return null
        lastOcr = t
        val s = t.replace(Regex("[\\s,.]"), "")
        val nums = Regex("\\d{3,6}").findAll(s).map { it.value.toInt() }.toList()
        return if (nums.size >= 2) Pair(nums[0], nums[1]) else null
    }

    /** Acik haritanin basligindan "Raklion 2 [Line 3]" okur. (harita, kanal); okunamayan null. */
    private fun readTitle(b: Bitmap): Pair<Int?, Int?> {
        val t = ocr(b, 0.30f, 0.14f, 0.62f, 0.22f) ?: return Pair(null, null)
        lastTitle = t
        val s = t.replace(Regex("\\s"), "")
        var m = Regex("Raklion(\\d)").find(s)?.groupValues?.get(1)?.toIntOrNull()
        var l = Regex("Line(\\d)").find(s)?.groupValues?.get(1)?.toIntOrNull()
        if (m != null && (m < 1 || m > 3)) m = null
        if (l != null && (l < 1 || l > 3)) l = null
        return Pair(m, l)
    }

    // ------------------------------------------------------------------ olum ekrani

    /** Free Revival duğmesi bölgesini 9x5 ızgaraya bölüp her hücrenin ortalama rengini (r,g,b) çıkarır. */
    private fun reviveGrid(b: Bitmap): IntArray {
        val cols = 9
        val rows = 5
        val p = pt("revive")
        val out = IntArray(cols * rows * 3)
        val hw = 0.06f * b.width
        val hh = 0.03f * b.height
        val px = p.first * b.width
        val py = p.second * b.height
        var k = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cx = (px - hw + (c + 0.5f) * (2f * hw / cols)).toInt()
                val cy = (py - hh + (r + 0.5f) * (2f * hh / rows)).toInt()
                var sr = 0
                var sg = 0
                var sb = 0
                var n = 0
                for (y in maxOf(0, cy - 2)..minOf(b.height - 1, cy + 2)) {
                    for (x in maxOf(0, cx - 2)..minOf(b.width - 1, cx + 2)) {
                        val col = b.getPixel(x, y)
                        sr += Color.red(col)
                        sg += Color.green(col)
                        sb += Color.blue(col)
                        n++
                    }
                }
                if (n == 0) n = 1
                out[k++] = sr / n
                out[k++] = sg / n
                out[k++] = sb / n
            }
        }
        return out
    }

    /** Kayıtlı ölüm ekranı referansına ortalama renk farkı (0 = aynı). Referans yoksa null. */
    private fun reviveDiff(b: Bitmap): Float? {
        val s = prefs().getString("reviveG", null) ?: return null
        val ref = s.split(",").mapNotNull { it.toIntOrNull() }
        val cur = reviveGrid(b)
        if (ref.size != cur.size) return null
        var sum = 0L
        for (i in cur.indices) sum += Math.abs(cur[i] - ref[i])
        return sum.toFloat() / cur.size
    }

    private fun isDeadScreen(b: Bitmap): Boolean {
        val d = reviveDiff(b) ?: return false
        return d < 14f
    }

    // ------------------------------------------------------------------ dokunma

    private fun tapPx(x: Float, y: Float): Boolean {
        if (shizukuReady() && shell(arrayOf("input", "tap", x.toInt().toString(), y.toInt().toString()))) {
            sent++
            return true
        }
        val p = Path()
        p.moveTo(x, y)
        return runGesture(p, 70L)
    }

    private fun shizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    private fun shizukuAsk() {
        try {
            if (!Shizuku.pingBinder()) {
                say("Shizuku çalışmıyor. Shizuku uygulamasını aç ve 'Başlat'a bas.")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                say("Shizuku izni tamam, dokunmalar Shizuku ile yapılacak")
                return
            }
            Shizuku.requestPermission(1001)
            say("Shizuku izin penceresinde 'İzin ver'e bas")
        } catch (e: Throwable) {
            say("Shizuku hatası: " + e.message)
        }
    }

    private fun shell(args: Array<String>): Boolean {
        return try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            val proc = m.invoke(null, args, null, null) as Process
            proc.waitFor() == 0
        } catch (e: Throwable) {
            false
        }
    }

    private fun runGesture(p: Path, dur: Long): Boolean {
        for (i in 0 until 3) {
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, dur))
                .build()
            val latch = CountDownLatch(1)
            var ok = false
            val accepted = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            }, null)
            if (accepted) latch.await(2, TimeUnit.SECONDS)
            if (ok) {
                sent++
                return true
            }
            cancelled++
            Thread.sleep(300)
        }
        return false
    }

    private fun tapPoint(name: String) {
        refreshScreen()
        val p = pt(name)
        tapPx(p.first * screenW, p.second * screenH)
    }

    // ------------------------------------------------------------------ sayaclar

    private fun spawnIndex(m: Int, target: PointF): Int {
        val list = SPAWNS[m] ?: return 0
        val tx = target.x / screenW
        val ty = target.y / screenH
        var best = 0
        var bd = Float.MAX_VALUE
        for ((i, sp) in list.withIndex()) {
            val dx = sp.first - tx
            val dy = sp.second - ty
            val d = dx * dx + dy * dy
            if (d < bd) {
                bd = d
                best = i
            }
        }
        return best
    }

    private fun recordKill(m: Int, line: Int, idx: Int) {
        if (line < 1) return
        killAt[m.toString() + "_" + line + "_" + idx] = System.currentTimeMillis()
        saveTimers()
    }

    private fun saveTimers() {
        val sb = StringBuilder()
        for ((k, v) in killAt) {
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(k).append('=').append(v)
        }
        prefs().edit().putString("timers", sb.toString()).apply()
    }

    private fun loadTimers() {
        killAt.clear()
        val s = prefs().getString("timers", "") ?: ""
        for (part in s.split(',')) {
            val kv = part.split('=')
            if (kv.size == 2) {
                val t = kv[1].toLongOrNull()
                if (t != null) killAt[kv[0]] = t
            }
        }
    }

    /** "mm:ss" kalan sure, "HAZIR" (dogdu) veya "--" (bilinmiyor). */
    private fun timerText(m: Int, line: Int, idx: Int): String {
        val t = killAt[m.toString() + "_" + line + "_" + idx] ?: return "--"
        val total = if (m == 1) cfgI("respR1", 2580) else cfgI("respR2", 2115)
        val left = total - ((System.currentTimeMillis() - t) / 1000L).toInt()
        if (left <= 0) return "HAZIR"
        return String.format("%02d:%02d", left / 60, left % 60)
    }

    // ------------------------------------------------------------------ bot adimlari

    /** Haritayi acar ve harita acikken alinmis ekran goruntusunu dondurur. */
    private fun ensureMap(): Bitmap {
        for (i in 0 until 4) {
            val b = look()
            if (b != null && orangeAt(b, "closeMap")) return b
            tapPoint("minimap")
            pause(1500)
        }
        fail("Harita açılamadı. 'Ayar' menüsünden minimap ve closeMap noktalarını kontrol et.")
    }

    private fun closeMapIfOpen() {
        val b = look()
        if (b != null && orangeAt(b, "closeMap")) {
            tapPoint("closeMap")
            pause(700)
        }
    }

    private fun switchLine(line: Int) {
        closeMapIfOpen()
        say("Kanal " + line + "'e geçiliyor")
        tapPoint("wire")
        pause(1500)
        tapPoint("ch" + line)
        pause(600)
        tapPoint("switchLine")
        pause(1500)
        val b = look()
        if (b != null && orangeAt(b, "closeChannel")) {
            tapPoint("closeChannel")
            pause(500)
        }
        pause(cfgI("loadWait", 5) * 1000)
        curLine = line
    }

    /** Chat'teki kayitli baglantiya dokunarak ilgili haritanin baslangicina isinlanir. */
    private fun teleport(m: Int) {
        closeMapIfOpen()
        say("Chat'ten Raklion " + m + " başlangıcına ışınlanılıyor")
        tapPoint("chat")
        pause(1000)
        tapPoint("chatTab")
        pause(700)
        tapPoint(if (m == 1) "linkR1" else "linkR2")
        pause(600)
        if (prefs().contains("closeChat_x")) {
            tapPoint("closeChat")
            pause(500)
        }
        pause(cfgI("tpWait", 6) * 1000)
        curMap = m
    }

    private fun gotoSpot(m: Int, line: Int) {
        if (curMap != m) teleport(m)
        if (curLine != line) switchLine(line)
    }

    private fun runLoop() {
        while (running) {
            try {
                routeStep()
            } catch (e: Restart) {
                say("Canlandın, rota kaldığı adımdan devam ediyor")
            }
        }
    }

    private fun routeStep() {
        val r = ROUTE[step]
        val m = r.first
        val line = r.second
        say(routeName(step) + " adımı")
        gotoSpot(m, line)
        if (cfgB("buffOn", true)) checkBuff()
        huntHere(m, line)
        step = (step + 1) % ROUTE.size
        if (step == 0) {
            laps++
            say("Tur tamamlandı, baştan başlanıyor")
        }
    }

    /** Bu kanalda canli boss kalmayana kadar av yapar. */
    private fun huntHere(m: Int, line: Int) {
        var fixes = 0
        var noMe = 0
        while (running) {
            if (curMap != m || curLine != line) gotoSpot(m, line)
            val b = ensureMap()

            // harita basligindan nerede oldugumuzu dogrula
            val t = readTitle(b)
            val pm = t.first
            val pl = t.second
            val mismatch = (pm != null && pm != m) || (pl != null && pl != line)
            if (mismatch) {
                if (fixes < 2) {
                    fixes++
                    say("Başlık okundu: Raklion " + pm + " / kanal " + pl + ", düzeltiliyor")
                    if (pm != null) curMap = pm
                    if (pl != null) curLine = pl
                    closeMapIfOpen()
                    continue
                }
                // iki denemede de uymadi: OCR yanlis okuyor olabilir, hedefteymisiz gibi devam et
                say("Başlık hâlâ uyuşmuyor, okuma hatası sayılıp devam ediliyor")
            } else {
                if (pm != null) curMap = pm
                if (pl != null) curLine = pl
            }

            val sc = scanMap(b)
            val me = sc.player
            if (me == null) {
                noMe++
                say("Oyuncu imleci bulunamadı (harita açık mı?)")
                pause(1500)
                if (noMe >= 4) {
                    closeMapIfOpen()
                    return
                }
                continue
            }
            noMe = 0
            if (sc.bosses.isEmpty()) {
                say("Raklion " + m + " kanal " + line + ": canlı boss yok")
                closeMapIfOpen()
                return
            }
            var best: PointF = sc.bosses[0]
            var bd = Float.MAX_VALUE
            for (s in sc.bosses) {
                val d = dist(me, s)
                if (d < bd) {
                    bd = d
                    best = s
                }
            }
            say("Raklion " + m + " kanal " + line + ": " + sc.bosses.size + " canlı boss")
            if (goAndFight(best, m, line)) {
                if (cfgB("buffOn", true)) checkBuff()
            }
        }
    }

    /** Boss'a yuruyup savasir. Boss oldurulduyse true. */
    private fun goAndFight(target: PointF, m: Int, line: Int): Boolean {
        say("Boss'a gidiliyor")
        tapPx(target.x, target.y)
        pause(800)
        val arrive = cfgI("arrive", 45).toFloat()
        val limit = cfgI("walkTimeout", 60) * 1000L
        var t0 = System.currentTimeMillis()
        var retapped = false
        var lastD = Float.MAX_VALUE
        while (true) {
            pause(300)
            if (System.currentTimeMillis() - t0 > limit) {
                if (!retapped) {
                    retapped = true
                    tapPx(target.x, target.y)
                    t0 = System.currentTimeMillis()
                } else {
                    say("Boss'a varılamadı, yeni tarama")
                    return false
                }
            }
            val b = look() ?: continue
            if (!orangeAt(b, "closeMap")) {
                tapPoint("minimap")
                pause(1200)
                continue
            }
            val sc = scanMap(b)
            val me = sc.player
            var tgt: PointF? = null
            var td = 40f
            for (s in sc.bosses) {
                val d = dist(s, target)
                if (d < td) {
                    td = d
                    tgt = s
                }
            }
            if (me != null) {
                if (tgt != null) {
                    lastD = dist(me, tgt)
                    if (lastD <= arrive) break
                } else {
                    // imlec ikonun ustunu kapatmis olabilir
                    if (lastD <= arrive * 2.5f) break
                    say("Hedef kayboldu, yeni tarama")
                    return false
                }
            }
        }
        say("Vardı, oto saldırı açılıyor")
        tapPoint("closeMap")
        pause(700)
        tapPoint("autoAtk")
        return fight(target, m, line)
    }

    private fun fight(target: PointF, m: Int, line: Int): Boolean {
        say("Savaş başladı")
        val t0 = System.currentTimeMillis()
        val limit = cfgI("fightTimeout", 300) * 1000L
        var seen = false
        var dead = 0
        var lastMap = t0
        var killed = true
        while (true) {
            pause(1500)
            val b = look() ?: continue
            if (orangeAt(b, "closeMap")) {
                tapPoint("closeMap")
                pause(600)
                continue
            }
            val now = System.currentTimeMillis()
            if (miniAlive(b)) {
                seen = true
                dead = 0
            } else if (seen) {
                dead++
                if (dead >= 3) break
            } else if (now - t0 > 8000 && now - lastMap > 10000) {
                // mini harita hic canli boss gostermedi: harita uzerinden kontrol et
                lastMap = now
                say("Mini harita boss göstermiyor, haritadan kontrol")
                val mb = ensureMap()
                val sc = scanMap(mb)
                var still = false
                for (s in sc.bosses) if (dist(s, target) < 40f) still = true
                val me = sc.player
                val onTop = me != null && dist(me, target) <= cfgI("arrive", 45) * 1.5f
                tapPoint("closeMap")
                pause(700)
                if (!still && !onTop) break
            }
            if (now - t0 > limit) {
                say("Savaş zaman aşımı, boss'tan vazgeçildi")
                killed = false
                break
            }
        }
        if (killed) {
            kills++
            if (m == 1) killsR1++ else killsR2++
            recordKill(m, line, spawnIndex(m, target))
            say("Boss öldü")
        }
        if (cfgB("stopAuto", true)) tapPoint("autoAtk")
        pause(1000)
        return killed
    }

    private fun checkBuff() {
        closeMapIfOpen()
        val b = look() ?: return
        val st = readStats(b)
        if (st == null) {
            say("ATK/DEF okunamadı: " + lastOcr)
            return
        }
        val minA = cfgI("minAtk", 0)
        val minD = cfgI("minDef", 0)
        if (st.first < minA && st.second < minD) {
            say("ATK " + st.first + " DEF " + st.second + " düşük, buff noktasına gidiliyor")
            doBuff(minA, minD)
        } else {
            say("ATK " + st.first + " DEF " + st.second + " tamam")
        }
    }

    private fun doBuff(minA: Int, minD: Int) {
        tapPoint("chat")
        pause(1000)
        tapPoint("chatTab")
        pause(700)
        tapPoint("buffLink")
        pause(600)
        if (prefs().contains("closeChat_x")) {
            tapPoint("closeChat")
            pause(500)
        }
        // buff noktasi Raklion 2 haritasindadir; rota sonraki adimda gerekirse geri isinlar
        curMap = 2
        pause(cfgI("buffTravel", 12) * 1000)
        val t0 = System.currentTimeMillis()
        val limit = cfgI("buffWait", 90) * 1000L
        while (System.currentTimeMillis() - t0 < limit) {
            val b = look()
            if (b != null) {
                val st = readStats(b)
                if (st != null && st.first >= minA && st.second >= minD) {
                    buffs++
                    say("Buff alındı: ATK " + st.first + " DEF " + st.second)
                    return
                }
            }
            pause(3000)
        }
        say("Buff süresi doldu, devam ediliyor")
    }

    private fun start() {
        if (running) return
        if (worker?.isAlive == true) {
            say("Önceki işlem bitiyor, biraz bekle")
            return
        }
        val need = ArrayList<String>()
        for (n in listOf("chat", "chatTab", "linkR1", "linkR2")) if (!hasPt(n)) need.add(n)
        if (cfgB("buffOn", true) && !hasPt("buffLink")) need.add("buffLink")
        if (need.isNotEmpty()) {
            say("Önce 'Ayar' menüsünden işaretle: " + need.joinToString(", "))
            return
        }
        step = prefs().getInt("startIdx", 0).coerceIn(0, ROUTE.size - 1)
        curMap = ROUTE[step].first
        curLine = ROUTE[step].second
        running = true
        updateUi()
        val t = Thread {
            try {
                runLoop()
            } catch (e: StopEx) {
            } catch (e: InterruptedException) {
            } catch (e: Exception) {
                say("Hata: " + e.message)
            }
            running = false
            ui { updateUi() }
        }
        worker = t
        t.start()
    }

    private fun stop() {
        running = false
        worker?.interrupt()
        say("Durduruldu")
        updateUi()
    }

    private fun toggle() {
        if (running) stop() else start()
    }

    private fun cycleStart() {
        val i = (prefs().getInt("startIdx", 0) + 1) % ROUTE.size
        prefs().edit().putInt("startIdx", i).apply()
        updateUi()
    }

    private fun updateUi() {
        val btn = startBtn
        if (btn != null) {
            btn.text = if (running) "■  Durdur" else "▶  Başlat"
            btn.background = rounded(if (running) BAD else ACCENT, 12)
            btn.setTextColor(Color.WHITE)
        }
        val si = prefs().getInt("startIdx", 0).coerceIn(0, ROUTE.size - 1)
        routeBtn?.text = "Başlangıç: R" + ROUTE[si].first + " · K" + ROUTE[si].second
        dotView?.background = oval(if (running) OK else ACCENT)
    }

    // ------------------------------------------------------------------ test

    private fun runTest() {
        say("Test: ekran okunuyor")
        Thread {
            try {
                val b = grab()
                if (b == null) {
                    say("Ekran görüntüsü alınamadı")
                    return@Thread
                }
                val open = orangeAt(b, "closeMap")
                val sc = scanMap(b)
                val mf = miniFrac(b)
                val st = readStats(b)
                val statOcr = lastOcr
                val tt = if (open) readTitle(b) else Pair(null, null)
                val rd = reviveDiff(b)
                val dead = if (rd == null) "referans yok" else String.format("fark %.1f (eşik 14, %s)", rd, if (rd < 14f) "ÖLÜ SANIYOR" else "normal")
                say(
                    "Harita açık: " + open + "  canlı boss: " + sc.bosses.size +
                        "  imleç: " + (sc.player != null) +
                        "\nBaşlık: Raklion " + tt.first + " / kanal " + tt.second + "  ('" + lastTitle + "')" +
                        "\nMini harita oranı: " + String.format("%.4f", mf) + " (eşik 0.0040, " +
                        (if (miniAlive(b)) "CANLI" else "yok") + ")" +
                        "\nATK/DEF: " + (if (st != null) st.first.toString() + "/" + st.second else "okunamadı") +
                        "  OCR: '" + statOcr + "'  ölüm ekranı: " + dead
                )
                ui { showMarkers(sc) }
            } catch (e: Exception) {
                say("Test hatası: " + e.message)
            }
        }.start()
    }

    // ------------------------------------------------------------------ panel ve menu

    private fun rounded(color: Int, r: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        return g
    }

    private fun oval(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(color)
        return g
    }

    private fun mkBtn(text: String, bg: Int = CARD_BG, fg: Int = Color.WHITE, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 12f
        b.isAllCaps = false
        b.setTextColor(fg)
        b.setPadding(dp(10), dp(4), dp(10), dp(4))
        b.minHeight = 0
        b.minimumHeight = 0
        b.minWidth = 0
        b.minimumWidth = 0
        b.background = rounded(bg, 10)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun addBtn(col: LinearLayout, btn: Button, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT) {
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
        lp.topMargin = dp(6)
        col.addView(btn, lp)
    }

    private fun overlayParams(w: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            w,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
    }

    private fun addRow(parent: LinearLayout, names: List<String>, acts: List<() -> Unit>): List<Button> {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val out = ArrayList<Button>()
        for (i in names.indices) {
            val bt = mkBtn(names[i]) { acts[i]() }
            val lp = LinearLayout.LayoutParams(0, dp(36), 1f)
            if (i > 0) lp.leftMargin = dp(6)
            row.addView(bt, lp)
            out.add(bt)
        }
        val rlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        rlp.topMargin = dp(8)
        parent.addView(row, rlp)
        return out
    }

    private fun buildPanel() {
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.background = rounded(PANEL_BG, 16)
        outer.setPadding(dp(12), dp(10), dp(12), dp(12))

        // ust satir: durum noktasi, baslik, gizle
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val dot = View(this)
        dotView = dot
        head.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)))
        val title = TextView(this)
        title.text = "MU Boss Bot"
        title.textSize = 15f
        title.setTextColor(Color.WHITE)
        title.typeface = android.graphics.Typeface.DEFAULT_BOLD
        title.setPadding(dp(8), 0, 0, 0)
        head.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val hide = TextView(this)
        hide.text = "▾"
        hide.textSize = 18f
        hide.setTextColor(TEXT_DIM)
        hide.setPadding(dp(10), 0, dp(4), 0)
        head.addView(hide)
        outer.addView(head)

        val inner = LinearLayout(this)
        inner.orientation = LinearLayout.VERTICAL

        val st = TextView(this)
        st.textSize = 12f
        st.setTextColor(Color.WHITE)
        st.maxLines = 8
        st.setPadding(0, dp(8), 0, dp(2))
        st.text = "Hazır"
        statusView = st
        inner.addView(st)

        val stats = TextView(this)
        stats.textSize = 11f
        stats.setTextColor(TEXT_DIM)
        stats.text = "Boss 0 (R2 0 · R1 0)  ·  Ölüm 0  ·  Buff 0  ·  Tur 0"
        statsView = stats
        inner.addView(stats)

        val sb = mkBtn("▶  Başlat") { toggle() }
        sb.textSize = 15f
        sb.typeface = android.graphics.Typeface.DEFAULT_BOLD
        startBtn = sb
        addBtn(inner, sb, dp(44))

        val rb = mkBtn("Başlangıç") { cycleStart() }
        routeBtn = rb
        addBtn(inner, rb, dp(36))

        addRow(inner, listOf("Shizuku", "Test", "Ayar"), listOf({ shizukuAsk() }, { runTest() }, { toggleMenu() }))
        addRow(inner, listOf("Sayaçlar"), listOf({ toggleTimers() }))
        outer.addView(inner)

        hide.setOnClickListener {
            val open = inner.visibility == View.VISIBLE
            inner.visibility = if (open) View.GONE else View.VISIBLE
            hide.text = if (open) "▸" else "▾"
        }

        val lp = overlayParams(dp(230))
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(6)
        lp.y = (screenH * 0.18f).toInt()
        wm.addView(outer, lp)
        panel = outer
        updateUi()
    }

    // ------------------------------------------------------------------ respawn sayac paneli

    private val tick = object : Runnable {
        override fun run() {
            if (timerPanel == null) return
            updateTimers()
            main.postDelayed(this, 1000)
        }
    }

    private fun updateTimers() {
        for ((i, r) in ROUTE.withIndex()) {
            if (i >= timerRows.size) break
            val m = r.first
            val line = r.second
            val parts = ArrayList<String>()
            var ready = false
            for (idx in 0 until (SPAWNS[m]?.size ?: 1)) {
                val tx = timerText(m, line, idx)
                if (tx == "HAZIR") ready = true
                parts.add(tx)
            }
            val row = timerRows[i]
            row.text = "R" + m + " · K" + line + "   " + parts.joinToString("  |  ")
            row.setTextColor(if (ready) OK else Color.WHITE)
        }
    }

    private fun toggleTimers() {
        val old = timerPanel
        if (old != null) {
            try {
                wm.removeView(old)
            } catch (e: Exception) {
            }
            timerPanel = null
            timerRows.clear()
            return
        }
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = rounded(PANEL_BG, 14)
        card.setPadding(dp(10), dp(8), dp(10), dp(10))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = "Respawn"
        title.textSize = 13f
        title.setTextColor(ACCENT)
        title.typeface = android.graphics.Typeface.DEFAULT_BOLD
        head.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val x = TextView(this)
        x.text = "✕"
        x.textSize = 16f
        x.setTextColor(TEXT_DIM)
        x.setPadding(dp(12), 0, dp(2), 0)
        x.setOnClickListener { toggleTimers() }
        head.addView(x)
        card.addView(head)

        timerRows.clear()
        for (i in ROUTE.indices) {
            val tv = TextView(this)
            tv.textSize = 12f
            tv.typeface = android.graphics.Typeface.MONOSPACE
            tv.setTextColor(Color.WHITE)
            tv.setPadding(0, dp(3), 0, 0)
            timerRows.add(tv)
            card.addView(tv)
        }
        val lp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.TOP or Gravity.END
        lp.x = dp(6)
        lp.y = (screenH * 0.25f).toInt()
        wm.addView(card, lp)
        timerPanel = card
        updateTimers()
        main.postDelayed(tick, 1000)
    }

    // ------------------------------------------------------------------ nokta menusu

    private fun toggleMenu() {
        val old = menu
        if (old != null) {
            try {
                wm.removeView(old)
            } catch (e: Exception) {
            }
            menu = null
            return
        }
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = rounded(PANEL_BG, 16)
        card.setPadding(dp(12), dp(10), dp(12), dp(12))

        val title = TextView(this)
        title.text = "Nokta işaretleme"
        title.textSize = 15f
        title.setTextColor(Color.WHITE)
        title.typeface = android.graphics.Typeface.DEFAULT_BOLD
        card.addView(title)
        val hint = TextView(this)
        hint.text = "Oyunda ilgili ekranı aç, aşağıdan bir satıra dokun, sonra oyun ekranında o noktaya dokun. Kırmızı satırlar henüz işaretlenmedi."
        hint.textSize = 11f
        hint.setTextColor(TEXT_DIM)
        hint.setPadding(0, dp(2), 0, dp(4))
        card.addView(hint)

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        for (n in NAMES) {
            val set = hasPt(n)
            val btn = mkBtn((if (set) "✓  " else "✗  ") + n + "\n" + (LABELS[n] ?: n), if (set) CARD_BG else MISSING_BG) {
                beginCapture(n)
            }
            btn.textSize = 11f
            btn.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            btn.setPadding(dp(12), dp(8), dp(12), dp(8))
            addBtn(list, btn)
        }
        val sv = android.widget.ScrollView(this)
        sv.addView(list)
        card.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (screenH * 0.55f).toInt()))

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        val b1 = mkBtn("Ölüm referansını sil", MISSING_BG) {
            prefs().edit().remove("reviveG").apply()
            say("Ölüm referansı silindi, ölüm tespiti kapalı")
        }
        val b2 = mkBtn("Kapat", ACCENT, Color.WHITE) { toggleMenu() }
        val l1 = LinearLayout.LayoutParams(0, dp(38), 1f)
        val l2 = LinearLayout.LayoutParams(0, dp(38), 1f)
        l2.leftMargin = dp(6)
        bottom.addView(b1, l1)
        bottom.addView(b2, l2)
        val blp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        blp.topMargin = dp(8)
        card.addView(bottom, blp)

        val lp = overlayParams(dp(330))
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(246)
        lp.y = dp(8)
        wm.addView(card, lp)
        menu = card
    }

    private fun removeExtra() {
        val v = extra
        if (v != null) {
            try {
                wm.removeView(v)
            } catch (e: Exception) {
            }
            extra = null
        }
    }

    /** Sonraki dokunusu kaydeden tam ekran saydam katman. Oyunda ilgili ekrani acik tutup kullan. */
    private fun beginCapture(name: String) {
        if (menu != null) toggleMenu()
        removeExtra()
        val fl = FrameLayout(this)
        fl.setBackgroundColor(0x33FFFFFF)
        val tv = TextView(this)
        tv.text = "'" + name + "' için dokun:  " + (LABELS[name] ?: name)
        tv.textSize = 18f
        tv.setTextColor(Color.BLACK)
        tv.setBackgroundColor(0xDDFFFF00.toInt())
        tv.setPadding(dp(10), dp(6), dp(10), dp(6))
        fl.addView(
            tv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
        )
        fl.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                refreshScreen()
                savePt(name, ev.rawX / screenW, ev.rawY / screenH)
                removeExtra()
                Toast.makeText(this, "Kaydedildi: " + name, Toast.LENGTH_SHORT).show()
                if (name == "revive") {
                    // ölüm ekranının o noktadaki rengini referans olarak sakla
                    Thread {
                        try {
                            Thread.sleep(700)
                            val b = grab()
                            if (b != null) {
                                prefs().edit().putString("reviveG", reviveGrid(b).joinToString(",")).apply()
                                say("Ölüm ekranı referansı kaydedildi")
                            }
                        } catch (e: Exception) {
                        }
                    }.start()
                }
            }
            true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(fl, lp)
        extra = fl
    }

    /** Bulunan boss'lari, oyuncuyu ve kayitli noktalari 6 saniye gosterir. */
    private fun showMarkers(sc: Scan) {
        removeExtra()
        refreshScreen()
        val dots = ArrayList<Triple<String, Float, Float>>()
        for (n in NAMES) {
            if (!hasPt(n)) continue
            val p = pt(n)
            dots.add(Triple(n, p.first * screenW, p.second * screenH))
        }
        val a = pt("miniTL")
        val c = pt("miniBR")
        val rect = floatArrayOf(a.first * screenW, a.second * screenH, c.first * screenW, c.second * screenH)
        val v = MarkView(this, sc.bosses, sc.player, dots, rect)
        val wlp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(v, wlp)
        extra = v
        main.postDelayed({
            if (extra === v) removeExtra()
        }, 6000)
    }
}
