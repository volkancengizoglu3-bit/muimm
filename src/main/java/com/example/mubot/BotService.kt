package com.example.mubot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PixelFormat
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
import android.view.View
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

/**
 * MU Immortal - Raklion boss kontrol botu.
 *
 * Mantik:
 *  - Her kanalda (1,2,3) haritayi acar, sol listeden Raklion 1/2/3'u sirayla secer.
 *  - Ghost Aragog ismi KIRMIZI ise canli, GRI ise olu demektir.
 *  - Canliysa boss'a dokunur (oyun yuruturu), sonra Manual dugmesine basip oyunun
 *    kendi otomatik saldirisini acar. Boss olunce otomatigi kapatir, siradaki kanala gecer.
 */
class BotService : AccessibilityService() {

    private class StopEx : RuntimeException()

    companion object {
        const val ICON_DY = -0.038f      // isim etiketinden ikon merkezine dikey fark
        const val RED_THR = 0.025f       // etiket bolgesinde kirmizi piksel orani esigi
        const val LOAD_SEC = 5           // kanal degisince yukleme bekleme
        const val RECHECK_SEC = 8        // savas sirasinda boss durumunu kontrol araligi
        const val FIGHT_TIMEOUT_SEC = 600
        const val ROW_STEP = 0.0397f     // Raklion 1/2/3 satirlari arasi dikey mesafe
        const val SWAMP_GAP = 0.0476f    // Raklion 3 ile alttaki kirmizi Lv satiri arasi mesafe

        val NAMES = listOf(
            "minimap", "wire", "ch1", "ch2", "ch3", "switchLine", "closeChannel", "closeMap",
            "map1", "boss1", "boss2", "boss3", "manual", "random", "miniTL", "miniBR", "chat", "buffLink", "closeChat", "chatMap1", "chatMap2"
        )

        val LABELS = mapOf(
            "minimap" to "Sag ustteki mini harita (haritayi acar)",
            "wire" to "Wire butonu (kanal ekranini acar)",
            "ch1" to "Kanal ekraninda 1. kanal",
            "ch2" to "Kanal ekraninda 2. kanal",
            "ch3" to "Kanal ekraninda 3. kanal",
            "switchLine" to "Switch Line butonu",
            "closeChannel" to "Kanal ekrani X (kapat) dugmesi",
            "closeMap" to "Harita ekrani X (kapat) dugmesi",
            "map1" to "Harita listesinde Raklion satiri (sadece sag-sol konumu kullanilir)",
            "boss1" to "Raklion 1 haritasinda Ghost Aragog ISMI",
            "boss2" to "Raklion 2 haritasinda Ghost Aragog ISMI",
            "boss3" to "Raklion 3 haritasinda Ghost Aragog ISMI",
            "manual" to "Manual (oto saldiri) dugmesi",
            "random" to "Harita sag alt Random dugmesi",
            "miniTL" to "Mini harita sol ust kose",
            "miniBR" to "Mini harita sag alt kose",
            "chat" to "Chat acma noktasi",
            "buffLink" to "Chat icindeki buff koordinati",
            "closeChat" to "Chat kapatma noktasi",
            "chatMap1" to "Chat icinde Raklion 1 hedef koordinati",
            "chatMap2" to "Chat icinde Raklion 2 hedef koordinati"
        )

        // Ekran goruntulerinden (2419x1512) hesaplanan oranlar
        val DEFAULTS = mapOf(
            "minimap" to Pair(0.922f, 0.046f),
            "wire" to Pair(0.992f, 0.050f),
            "ch1" to Pair(0.498f, 0.326f),
            "ch2" to Pair(0.498f, 0.456f),
            "ch3" to Pair(0.498f, 0.581f),
            "switchLine" to Pair(0.499f, 0.739f),
            "closeChannel" to Pair(0.637f, 0.202f),
            "closeMap" to Pair(0.894f, 0.157f),
            "map1" to Pair(0.222f, 0.390f),
            "boss1" to Pair(0.469f, 0.494f),
            "boss2" to Pair(0.469f, 0.494f),
            "boss3" to Pair(0.469f, 0.494f),
            "manual" to Pair(0.972f, 0.473f),
            "random" to Pair(0.868f, 0.814f),
            "miniTL" to Pair(0.865f, 0.012f),
            "miniBR" to Pair(0.985f, 0.190f),
            "chat" to Pair(0.50f, 0.94f),
            "buffLink" to Pair(0.50f, 0.50f),
            "closeChat" to Pair(0.94f, 0.25f),
            "chatMap1" to Pair(0.50f, 0.50f),
            "chatMap2" to Pair(0.50f, 0.50f)
        )

        val WALK_OPTIONS = listOf(10, 15, 20, 30, 45)
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var panel: LinearLayout? = null
    private var menu: LinearLayout? = null
    private var extra: View? = null
    private var statusView: TextView? = null
    private var startBtn: Button? = null
    private var lineBtn: Button? = null
    private var walkBtn: Button? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var compact = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragOrigX = 0
    private var dragOrigY = 0
    private val panelButtons = ArrayList<View>()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var lastOcr = ""

    @Volatile
    private var running = false
    private var worker: Thread? = null

    private var startLine = 1
    private var walkIdx = 1
    private var sent = 0
    private var cancelled = 0
    private var lastShot = 0L
    private val listYs = FloatArray(4)
    private var screenW = 1
    private var screenH = 1

    // ------------------------------------------------------------------ yasam dongusu

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        refreshScreen()
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
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ kucuk yardimcilar

    private fun ui(block: () -> Unit) {
        main.post { block() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun say(msg: String) {
        ui { statusView?.text = msg + "  [tik ok " + sent + " / iptal " + cancelled + "]" }
    }

    private fun refreshScreen() {
        val b = wm.maximumWindowMetrics.bounds
        screenW = b.width()
        screenH = b.height()
    }

    private fun prefs() = getSharedPreferences("mubot", Context.MODE_PRIVATE)

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
                say("Ekran goruntusu alinamadi (kod " + errorCode + ")")
                latch.countDown()
            }
        })
        } catch (e: Exception) {
            say("Ekran goruntusu hatasi: " + e.message)
            return null
        }
        latch.await(3, TimeUnit.SECONDS)
        return result
    }

    private fun frac(b: Bitmap, name: String, hw: Float, hh: Float, pred: (Int) -> Boolean): Float {
        val p = pt(name)
        val cx = p.first * b.width
        val cy = p.second * b.height
        val x0 = maxOf(0, (cx - hw * b.width).toInt())
        val x1 = minOf(b.width - 1, (cx + hw * b.width).toInt())
        val y0 = maxOf(0, (cy - hh * b.height).toInt())
        val y1 = minOf(b.height - 1, (cy + hh * b.height).toInt())
        var hit = 0
        var tot = 0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                tot++
                if (pred(b.getPixel(x, y))) hit++
                x++
            }
            y++
        }
        return if (tot == 0) 0f else hit.toFloat() / tot
    }

    private fun isRed(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return r > 170 && g < 90 && b < 90 && (r - g) > 100
    }

    private fun isOrange(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return r > 200 && g in 110..210 && b < 90
    }

    private fun redFrac(b: Bitmap, m: Int): Float =
        frac(b, "boss" + m, 0.035f, 0.012f) { c -> isRed(c) }

    private fun bossAlive(b: Bitmap, m: Int): Boolean = redFrac(b, m) >= RED_THR

    private fun orangeAt(b: Bitmap, name: String): Boolean =
        frac(b, name, 0.012f, 0.020f) { c -> isOrange(c) } >= 0.12f

    /**
     * Sol haritalar listesinde Raklion'un hemen altindaki "Swamp of Peace" satirinin
     * KIRMIZI Lv yazisini bulur (oyuncunun seviyesi yetmedigi icin kirmizi). Satirin dikey
     * konumunu (ekran orani) dondurur, bulamazsa null.
     */
    private fun findSwampY(b: Bitmap): Float? {
        val x0 = (0.255f * b.width).toInt()
        val x1 = minOf(b.width - 1, (0.292f * b.width).toInt())
        val y0 = (0.20f * b.height).toInt()
        val y1 = minOf(b.height - 1, (0.70f * b.height).toInt())
        var runStart = -1
        var lastHit = -1
        var y = y0
        while (y <= y1) {
            var cnt = 0
            var x = x0
            while (x <= x1) {
                if (isRed(b.getPixel(x, y))) cnt++
                x++
            }
            if (cnt >= 3) {
                if (runStart < 0) runStart = y
                lastHit = y
            } else if (runStart >= 0 && y - lastHit > 6) {
                break
            }
            y++
        }
        if (runStart < 0 || lastHit - runStart < 8) return null
        return ((runStart + lastHit) / 2f) / b.height
    }

    /** Listeyi parmakla surukler. delta: icerigin ekran yuksekligine gore kayma miktari (eksi = yukari). */
    private fun dragList(delta: Float) {
        refreshScreen()
        val x = pt("map1").first * screenW
        val y0 = (0.45f - delta / 2f) * screenH
        val y1 = (0.45f + delta / 2f) * screenH
        val xi = x.toInt().toString()
        if (shizukuReady() &&
            shell(arrayOf("input", "swipe", xi, y0.toInt().toString(), xi, y1.toInt().toString(), "900"))
        ) {
            return
        }
        val p = Path()
        p.moveTo(x, y0)
        p.lineTo(x, y1)
        runGesture(p, 900L)
    }

    /** Haritada Raklion 1/2/3 satirlari gorunene kadar listeyi kaydirir ve satir konumlarini hesaplar. */
    private fun prepareList() {
        say("Harita listesi kaydiriliyor")
        for (i in 0 until 14) {
            val b = grab()
            if (b == null) continue
            val a = findSwampY(b)
            if (a == null) {
                dragList(if (i < 7) -0.22f else 0.22f)
                pause(1000)
                continue
            }
            if (a < 0.36f || a > 0.64f) {
                dragList((0.50f - a).coerceIn(-0.25f, 0.25f))
                pause(1000)
                continue
            }
            val r3 = a - SWAMP_GAP
            listYs[3] = r3
            listYs[2] = r3 - ROW_STEP
            listYs[1] = r3 - 2 * ROW_STEP
            return
        }
        fail("Harita listesinde Raklion bulunamadi (liste kaydirilamadi)")
    }

    private fun tapMapRow(m: Int) {
        tapFrac(pt("map1").first, listYs[m])
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

    // ------------------------------------------------------------------ Shizuku (yedek dokunma yontemi)

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
                say("Shizuku calismiyor. Shizuku uygulamasini ac ve 'Baslat'a bas.")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                say("Shizuku izni tamam, dokunmalar Shizuku ile yapilacak")
                return
            }
            Shizuku.requestPermission(1001)
            say("Shizuku izin penceresinde 'Izin ver'e bas")
        } catch (e: Throwable) {
            say("Shizuku hatasi: " + e.message)
        }
    }

    /** Shizuku uzerinden kabuk komutu calistirir (ornek: input tap x y). */
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

    private fun tapFrac(fx: Float, fy: Float) {
        refreshScreen()
        tapPx(fx * screenW, fy * screenH)
    }

    private fun tapPoint(name: String) {
        val p = pt(name)
        tapFrac(p.first, p.second)
    }

    // ------------------------------------------------------------------ bot adimlari

    private fun mapOpen(): Boolean {
        val b = grab() ?: return false
        return orangeAt(b, "closeMap")
    }

    private fun openMap() {
        var ok = false
        for (i in 0 until 3) {
            if (mapOpen()) {
                ok = true
                break
            }
            tapPoint("minimap")
            pause(1500)
            if (mapOpen()) {
                ok = true
                break
            }
        }
        if (!ok) fail("Harita acilamadi. 'Ayar' ile minimap ve closeMap noktalarini kontrol et.")
        prepareList()
    }

    private fun closeMapIfOpen() {
        val b = grab()
        if (b != null && orangeAt(b, "closeMap")) {
            tapPoint("closeMap")
            pause(700)
        }
    }

    private fun switchLine(line: Int) {
        closeMapIfOpen()
        say("Kanal " + line + "'e geciliyor")
        tapPoint("wire")
        pause(1500)
        tapPoint("ch" + line)
        pause(600)
        tapPoint("switchLine")
        pause(1500)
        val b = grab()
        if (b != null && orangeAt(b, "closeChannel")) {
            tapPoint("closeChannel")
            pause(500)
        }
        pause(LOAD_SEC * 1000)
    }

    /** Bu kanalda canli boss varsa harita numarasini (1..3) dondurur, yoksa 0. Canliysa harita acik kalir. */
    private fun scanLine(line: Int, mapNo: Int): Boolean {
        say("Kanal " + line + " / Raklion " + mapNo + " kontrol ediliyor")
        openMap()
        tapMapRow(mapNo)
        pause(1000)
        val b = grab()
        val alive = b != null && bossAlive(b, mapNo)
        if (!alive) closeMapIfOpen()
        return alive
    }

    private fun miniBossAlive(b: Bitmap): Boolean {
        val a = pt("miniTL"); val c = pt("miniBR")
        val x0 = maxOf(0, (a.first * b.width).toInt()); val x1 = minOf(b.width - 1, (c.first * b.width).toInt())
        val y0 = maxOf(0, (a.second * b.height).toInt()); val y1 = minOf(b.height - 1, (c.second * b.height).toInt())
        if (x1 <= x0 || y1 <= y0) return false
        var hit = 0; var total = 0
        for (y in y0..y1 step 2) for (x in x0..x1 step 2) {
            total++
            val color = b.getPixel(x, y)
            val r = Color.red(color); val g = Color.green(color); val bl = Color.blue(color)
            if (r > 150 && g > 120 && bl < 80 && r - bl > 90) hit++
        }
        return total > 0 && hit.toFloat() / total >= 0.0012f
    }

    private fun fightBoss(m: Int) {
        // Harita açıkken random ışınlan; ardından haritadaki boss işaretine tıkla.
        say("Raklion " + m + ": Random ışınlanma")
        tapPoint("random")
        pause(2200)
        val bp = pt("boss" + m)
        tapFrac(bp.first, bp.second + ICON_DY)
        pause(1000)
        if (mapOpen()) { tapPoint("closeMap"); pause(700) }
        say("Boss konumuna ilerleniyor")
        pause(WALK_OPTIONS[walkIdx] * 1000)
        tapPoint("manual")
        say("Oto saldırı açık; mini harita izleniyor")
        val t0 = System.currentTimeMillis()
        var faded = 0
        while (running) {
            pause(RECHECK_SEC * 1000)
            val b = grab()
            if (b != null) {
                if (miniBossAlive(b)) faded = 0 else faded++
                if (faded >= 2) { say("Mini harita boss simgesi soldu; boss öldü"); break }
            }
            if (System.currentTimeMillis() - t0 > FIGHT_TIMEOUT_SEC * 1000L) {
                say("Savaş zaman aşımı; sonraki kanala geçiliyor"); break
            }
        }
        tapPoint("manual")
        pause(600)
    }

    private fun goByChat(point: String) {
        tapPoint("chat")
        pause(600)
        tapPoint(point)
        pause(1800)
        tapPoint("closeChat")
        pause(400)
    }

    private fun readAtkDef(b: Bitmap): Pair<Int, Int>? {
        return try {
            val w = (0.27f * b.width).toInt()
            val h = (0.07f * b.height).toInt()
            val crop = Bitmap.createBitmap(b, 0, 0, w, h)
            val big = Bitmap.createScaledBitmap(crop, w * 3, h * 3, true)
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(big, 0)), 8, TimeUnit.SECONDS)
            lastOcr = result.text.replace("\\n", " ")
            val normalized = result.text.replace(Regex("[\\s,.]"), "")
            val values = Regex("\\d{3,6}").findAll(normalized).mapNotNull { it.value.toIntOrNull() }.toList()
            crop.recycle(); big.recycle()
            if (values.size >= 2) Pair(values[0], values[1]) else null
        } catch (e: Exception) { lastOcr = e.message ?: "OCR hatasi"; null }
    }

    private fun goBuffIfNeeded() {
        if (!prefs().getBoolean("cfg_buffOn", false)) { say("Buff kapali; boss dongusu devam ediyor"); return }
        val shot = grab() ?: run { say("Buff kontrolu: ekran goruntusu yok"); return }
        val values = readAtkDef(shot)
        if (values == null) { say("ATK/DEF okunamadi; buff karari verilmedi: " + lastOcr); return }
        val minAtk = prefs().getInt("cfg_minAtk", 0)
        val minDef = prefs().getInt("cfg_minDef", 0)
        val need = (minAtk > 0 && values.first < minAtk) || (minDef > 0 && values.second < minDef)
        say("ATK " + values.first + " / DEF " + values.second + if (need) " — buff gerekiyor" else " — buff yeterli")
        if (need) goByChat("buffLink")
    }

    private fun runLoop() {
        var line = 1
        var mapPos = 0 // Raklion 2, ardından Raklion 1
        goByChat("chatMap2")
        switchLine(1)
        goBuffIfNeeded()
        while (running) {
            val mapNo = if (mapPos == 0) 2 else 1
            if (scanLine(line, mapNo)) {
                fightBoss(mapNo)
                goBuffIfNeeded()
            }
            if (line < 3) {
                line++
                switchLine(line)
            } else {
                line = 1
                mapPos = (mapPos + 1) % 2
                goByChat(if (mapPos == 0) "chatMap2" else "chatMap1")
                switchLine(1)
            }
        }
    }

    private fun start() {
        if (running) return
        if (worker?.isAlive == true) {
            say("Onceki islem bitiyor, biraz bekle")
            return
        }
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

    private fun updateUi() {
        startBtn?.text = if (running) "■ Durdur" else "▶ Baslat"
        lineBtn?.text = "Baslangic kanali: " + startLine
        walkBtn?.text = "Yuruyus suresi: " + WALK_OPTIONS[walkIdx] + " sn"
    }

    // ------------------------------------------------------------------ test

    /** Sadece minimap noktasina bir kez dokunur ve oyunun dokunusu kabul edip etmedigini yazar. */
    private fun tapTest() {
        refreshScreen()
        val p = pt("minimap")
        say(
            "Tik testi: minimap (" + String.format("%.3f", p.first) + ", " +
                String.format("%.3f", p.second) + ") ekran " + screenW + "x" + screenH
        )
        Thread {
            val s0 = sent
            val c0 = cancelled
            tapPoint("minimap")
            say(
                "Tik sonucu: kabul " + (sent - s0) + " / iptal " + (cancelled - c0) +
                    "  mod: " + (if (shizukuReady()) "Shizuku" else "Erisilebilirlik")
            )
        }.start()
    }

    private fun runTest() {
        say("Test: ekran okunuyor")
        Thread {
            try {
                val b = grab()
                if (b == null) {
                    say("Ekran goruntusu alinamadi (Android 11+ gerekir)")
                } else {
                    val r1 = redFrac(b, 1)
                    val r2 = redFrac(b, 2)
                    val r3 = redFrac(b, 3)
                    val mo = orangeAt(b, "closeMap")
                    val co = orangeAt(b, "closeChannel")
                    say(
                        "Kirmizi oran b1=" + String.format("%.3f", r1) +
                            " b2=" + String.format("%.3f", r2) +
                            " b3=" + String.format("%.3f", r3) +
                            " (esik " + RED_THR + ")  harita X:" + mo + "  kanal X:" + co
                    )
                }
            } catch (e: Exception) {
                say("Test hatasi: " + e.message)
            }
            ui { showMarkers() }
        }.start()
    }

    // ------------------------------------------------------------------ panel ve menu

    private fun mkBtn(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 12f
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.setPadding(dp(8), 0, dp(8), 0)
        b.minHeight = 0
        b.minimumHeight = 0
        b.minWidth = 0
        b.minimumWidth = 0
        val bg = GradientDrawable()
        bg.setColor(0xCC222222.toInt())
        bg.cornerRadius = dp(6).toFloat()
        b.background = bg
        b.setOnClickListener { onClick() }
        return b
    }

    private fun addBtn(col: LinearLayout, btn: Button) {
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34))
        lp.topMargin = dp(3)
        col.addView(btn, lp)
    }

    private fun buildPanel() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val st = TextView(this)
        st.textSize = 11f
        st.setTextColor(Color.WHITE)
        st.setBackgroundColor(0xAA000000.toInt())
        st.setPadding(dp(4), dp(2), dp(4), dp(2))
        st.text = "Hazir"
        st.maxWidth = dp(260)
        statusView = st
        col.addView(st)

        val sb = mkBtn("▶ Baslat") { toggle() }
        startBtn = sb
        addBtn(col, sb)
        panelButtons.add(sb)

        val lb = mkBtn("Baslangic kanali: 1") {
            startLine = startLine % 3 + 1
            updateUi()
        }
        lineBtn = lb
        addBtn(col, lb)
        panelButtons.add(lb)

        val wb = mkBtn("Yuruyus suresi") {
            walkIdx = (walkIdx + 1) % WALK_OPTIONS.size
            updateUi()
        }
        walkBtn = wb
        addBtn(col, wb)
        panelButtons.add(wb)

        val sh = mkBtn("Shizuku izni") { shizukuAsk() }; addBtn(col, sh); panelButtons.add(sh)
        val tt = mkBtn("Tik testi") { tapTest() }; addBtn(col, tt); panelButtons.add(tt)
        val ts = mkBtn("Test") { runTest() }; addBtn(col, ts); panelButtons.add(ts)
        val ay = mkBtn("Ayar") { toggleMenu() }; addBtn(col, ay); panelButtons.add(ay)

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = "BOSSINGEN  ⋮  sürükle"
        title.textSize = 12f
        title.setTextColor(0xFFF5B83D.toInt())
        header.addView(title)
        val minimize = mkBtn("—") {
            compact = !compact
            for (v in panelButtons) v.visibility = if (compact) View.GONE else View.VISIBLE
            minimize.text = if (compact) "+" else "—"
        }
        header.addView(minimize)
        col.addView(header, 0)
        header.setOnTouchListener { _, ev ->
            val params = panelParams
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { dragStartX = ev.rawX; dragStartY = ev.rawY; dragOrigX = params?.x ?: 0; dragOrigY = params?.y ?: 0; true }
                MotionEvent.ACTION_MOVE -> { if (params != null) { params.x = dragOrigX + (ev.rawX - dragStartX).toInt(); params.y = dragOrigY + (ev.rawY - dragStartY).toInt(); try { wm.updateViewLayout(col, params) } catch (_: Exception) {} }; true }
                else -> true
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = (screenH * 0.35f).toInt()
        panelParams = lp
        wm.addView(col, lp)
        panel = col
        updateUi()
    }

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
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setBackgroundColor(0xAA000000.toInt())
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        for ((i, n) in NAMES.withIndex()) {
            val btn = mkBtn(n + ": " + (LABELS[n] ?: n)) {
                beginCapture(n)
            }
            btn.textSize = 11f
            addBtn(if (i < 8) left else right, btn)
        }
        addBtn(right, mkBtn("Menuyu kapat") { toggleMenu() })
        row.addView(left)
        row.addView(right)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(200)
        lp.y = dp(10)
        wm.addView(row, lp)
        menu = row
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
        tv.text = "'" + name + "' icin dokun:  " + (LABELS[name] ?: name)
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

    /** Kayitli tum noktalari 5 saniye boyunca ekranda gosterir. */
    private fun showMarkers() {
        removeExtra()
        refreshScreen()
        val fl = FrameLayout(this)
        for (n in NAMES) {
            val p = pt(n)
            val tv = TextView(this)
            tv.text = "● " + n
            tv.textSize = 10f
            tv.setTextColor(Color.WHITE)
            tv.setBackgroundColor(0xCCCC0000.toInt())
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            lp.leftMargin = (p.first * screenW).toInt() - dp(5)
            lp.topMargin = (p.second * screenH).toInt() - dp(8)
            fl.addView(tv, lp)
        }
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
        wm.addView(fl, wlp)
        extra = fl
        main.postDelayed({
            if (extra === fl) removeExtra()
        }, 5000)
    }
}
