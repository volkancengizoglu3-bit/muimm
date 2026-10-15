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
 *  - Chat koordinatiyla Raklion haritasina gidilir; harita acilinca liste kaydirilmaz.
 *  - Boss ismi kirmiziysa Random'a basilir, haritanin acik oldugu dogrulanir,
 *    sonra boss ismine tiklanip oyuncu imlecinin varisi beklenir.
 *  - Boss olunce mini haritadaki simge solmasi kontrol edilir.
 */
class BotService : AccessibilityService() {

    private class StopEx : RuntimeException()

    companion object {
        const val ICON_DY = -0.038f      // isim etiketinden ikon merkezine dikey fark
        const val RED_THR = 0.025f       // etiket bolgesinde kirmizi piksel orani esigi
        const val LOAD_SEC = 5           // kanal degisince yukleme bekleme
        const val RECHECK_SEC = 8        // savas sirasinda boss durumunu kontrol araligi
        const val FIGHT_TIMEOUT_SEC = 600

        val NAMES = listOf(
            "minimap", "wire", "ch1", "ch2", "ch3", "switchLine", "closeChannel", "closeMap",
            "boss1", "boss2", "boss3", "manual", "random", "miniTL", "miniBR", "chat", "buffLink", "closeChat", "chatMap1", "chatMap2"
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
            "boss1" to "Raklion 1 - birinci boss ismi",
            "boss2" to "Raklion 2 - tek boss ismi",
            "boss3" to "Raklion 1 - ikinci boss ismi",
            "manual" to "Manual (oto saldiri) dugmesi",
            "random" to "Harita sag alt Random dugmesi",
            "miniTL" to "Sag ustteki mini haritanin sol ust kosesi",
            "miniBR" to "Sag ustteki mini haritanin sag alt kosesi",
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
            "boss1" to Pair(0.469f, 0.494f),
            "boss2" to Pair(0.469f, 0.494f),
            "boss3" to Pair(0.469f, 0.494f),
            "manual" to Pair(0.972f, 0.473f),
            "random" to Pair(0.868f, 0.814f),
            // Screenshot: minimap is in the upper-right; boss marker sits near its left-center.
            "miniTL" to Pair(0.798f, 0.008f),
            "miniBR" to Pair(0.932f, 0.204f),
            "chat" to Pair(0.50f, 0.94f),
            "buffLink" to Pair(0.50f, 0.50f),
            "closeChat" to Pair(0.94f, 0.25f),
            "chatMap1" to Pair(0.50f, 0.50f),
            "chatMap2" to Pair(0.50f, 0.50f)
        )

    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var panel: LinearLayout? = null
    private var menu: LinearLayout? = null
    private var extra: View? = null
    private var statusView: TextView? = null
    private var startBtn: Button? = null
    private var lineBtn: Button? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var compact = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragOrigX = 0
    private var dragOrigY = 0
    private var menuDragStartX = 0f
    private var menuDragStartY = 0f
    private var menuDragOrigX = 0
    private var menuDragOrigY = 0
    private val panelButtons = ArrayList<View>()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var lastOcr = ""

    @Volatile
    private var running = false
    private var worker: Thread? = null

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

    private fun tapFrac(fx: Float, fy: Float): Boolean {
        refreshScreen()
        return tapPx(fx * screenW, fy * screenH)
    }

    private fun tapPoint(name: String): Boolean {
        val p = pt(name)
        return tapFrac(p.first, p.second)
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
    private fun scanBoss(line: Int, mapNo: Int, bossPoint: Int): Boolean {
        val bossLabel = if (mapNo == 2) "Raklion 2 tek boss" else if (bossPoint == 1) "Raklion 1 boss 1" else "Raklion 1 boss 2"
        say("Kanal " + line + " / " + bossLabel + " kontrol ediliyor")
        // Chat koordinatiyla zaten hedef Raklion haritasindayiz; harita listesinden satir
        // secmek/kaydirma hem gereksiz hem de Random dugmesinin yerini etkileyebilir.
        openMap()
        pause(350)
        val b = grab()
        val alive = b != null && bossAlive(b, bossPoint)
        if (!alive) closeMapIfOpen()
        return alive
    }

    private fun isGreen(c: Int): Boolean {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        return r < 120 && g > 170 && b < 120 && g - r > 80
    }

    private fun cursorAtBoss(b: Bitmap, m: Int): Boolean {
        val p = pt("boss" + m)
        val cx = p.first * b.width
        val cy = (p.second + ICON_DY) * b.height
        val x0 = maxOf(0, (cx - 0.015f * b.width).toInt())
        val x1 = minOf(b.width - 1, (cx + 0.015f * b.width).toInt())
        val y0 = maxOf(0, (cy - 0.020f * b.height).toInt())
        val y1 = minOf(b.height - 1, (cy + 0.020f * b.height).toInt())
        var green = 0
        var total = 0
        for (y in y0..y1 step 2) for (x in x0..x1 step 2) {
            total++
            if (isGreen(b.getPixel(x, y))) green++
        }
        return total > 0 && green.toFloat() / total >= 0.003f
    }

    private fun miniBossAlive(b: Bitmap): Boolean {
        // Boss simgesi mini haritanin sol-orta bolgesinde. Yesil oyuncu imleci
        // ve mini harita disindaki arayuz pikselleri olcum alanina dahil edilmez.
        val a = pt("miniTL"); val c = pt("miniBR")
        val x0 = maxOf(0, (a.first * b.width).toInt())
        val x1 = minOf(b.width - 1, (c.first * b.width).toInt())
        val y0 = maxOf(0, (a.second * b.height).toInt())
        val y1 = minOf(b.height - 1, (c.second * b.height).toInt())
        if (x1 <= x0 || y1 <= y0) return false
        var red = 0
        var grayOrBrown = 0
        var total = 0
        for (y in y0..y1 step 2) for (x in x0..x1 step 2) {
            val color = b.getPixel(x, y)
            val r = Color.red(color); val g = Color.green(color); val bl = Color.blue(color)
            total++
            if (isRed(color) || (r > 150 && g in 35..150 && bl < 100 && r - bl > 65)) red++
            if (r in 70..190 && g in 65..175 && bl in 45..135 && kotlin.math.abs(r - g) < 65) grayOrBrown++
        }
        // Live is only confirmed by red/orange boss pixels; absence of red is not proof of death.
        // Death verification itself compares multiple samples in fightBoss.
        return total > 0 && red.toFloat() / total >= 0.00035f
    }

    private fun fightBoss(bossPoint: Int, mapNo: Int) {
        // Harita açıkken Random ışınlan; boss simgesine tıkla ve imlecin simgeye gelmesini bekle.
        val bossLabel = if (mapNo == 2) "Raklion 2 tek boss" else if (bossPoint == 1) "Raklion 1 boss 1" else "Raklion 1 boss 2"
        say(bossLabel + ": Random düğmesine basılıyor")
        val sentBeforeRandom = sent
        tapPoint("random")
        pause(2500)

        // Goldingen'deki çalışan akış gibi Random'dan sonra haritanın durumunu yeniden
        // kontrol et. Harita kapanmışsa tekrar açmadan boss koordinatına dokunma.
        if (!mapOpen()) {
            say("Random sonrası harita kapandı; tekrar açılıyor")
            openMap()
            pause(700)
        }
        if (sent == sentBeforeRandom) {
            say("Random dokunuşu gönderilemedi; nokta ayarını kontrol et")
            closeMapIfOpen()
            return
        }
        val bp = pt("boss" + bossPoint)
        say("Random sonrası boss işaretine dokunuluyor")
        tapFrac(bp.first, bp.second + ICON_DY)
        pause(700)
        say("Boss simgesine doğru ilerleniyor")
        val walkStart = System.currentTimeMillis()
        var arrivedChecks = 0
        while (running && System.currentTimeMillis() - walkStart < 60000L) {
            pause(1200)
            val shot = grab()
            if (shot != null && cursorAtBoss(shot, bossPoint)) arrivedChecks++ else arrivedChecks = 0
            if (arrivedChecks >= 2) break
        }
        if (arrivedChecks < 2) {
            closeMapIfOpen()
            say("Boss konumuna varış doğrulanamadı; saldırı başlatılmadı")
            return
        }
        if (mapOpen()) { tapPoint("closeMap"); pause(700) }
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
        // Raklion gecisi yalnizca sohbet icindeki koordinat baglantisiyla yapilir.
        // Merkez varsayilanlari gercek koordinat degildir; nokta kullanici tarafindan kalibre edilmeli.
        if (!prefs().contains(point + "_x") || !prefs().contains(point + "_y")) {
            fail("Işınlanma noktası ayarlanmamış: " + point + ". Ayarlar > " + point + " ile sohbet içindeki koordinat bağlantısına dokun.")
        }
        if (!tapPoint("chat")) fail("Chat açılamadı: dokunuş gönderilemedi.")
        pause(900)
        val before = grab() ?: fail("Chat açıldı mı kontrol edilemiyor; ekran görüntüsü alınamadı.")
        if (!tapPoint(point)) fail("Chat koordinatına dokunuş gönderilemedi: " + point)
        pause(2500)
        val after = grab() ?: fail("Işınlanma kontrolü yapılamadı; ekran görüntüsü alınamadı.")
        // Hedefe tıklandıktan sonra chat kapatılır; harita/kanal kontrolleri bir sonraki adımda yapılır.
        tapPoint("closeChat")
        pause(700)
        say("Chat koordinatı seçildi (" + point + "); geçişin oyun içinde tamamlandığı ayrıca kontrol edilmeli.")
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

    private fun goBuffIfNeeded(returnMapNo: Int, returnLine: Int) {
        if (!prefs().getBoolean("cfg_buffOn", false)) { say("Buff kapali; boss dongusu devam ediyor"); return }
        val shot = grab() ?: run { say("Buff kontrolu: ekran goruntusu yok"); return }
        val values = readAtkDef(shot)
        if (values == null) { say("ATK/DEF okunamadi; buff karari verilmedi: " + lastOcr); return }
        val minAtk = prefs().getInt("cfg_minAtk", 0)
        val minDef = prefs().getInt("cfg_minDef", 0)
        val need = (minAtk > 0 && values.first < minAtk) || (minDef > 0 && values.second < minDef)
        say("ATK " + values.first + " / DEF " + values.second + if (need) " — buff gerekiyor" else " — buff yeterli")
        if (!need) return

        // Buff noktasi yalnizca 1. kanalda: once kanal 1, sonra chat koordinati.
        if (returnLine != 1) switchLine(1)
        say("Buff konumuna gidiliyor (Kanal 1)")
        goByChat("buffLink")
        pause(4000)
        val afterBuff = grab()?.let { readAtkDef(it) }
        if (afterBuff != null) say("Buff sonrasi ATK " + afterBuff.first + " / DEF " + afterBuff.second)
        else say("Buff sonrasi ATK/DEF okunamadi")

        // Buff bitince ayni harita/kanaldaki rotaya geri don.
        goByChat(if (returnMapNo == 2) "chatMap2" else "chatMap1")
        if (returnLine != 1) switchLine(returnLine)
    }

    private fun runLoop() {
        var line = 1
        var mapPos = 0 // Raklion 2 (tek boss), sonra Raklion 1 (iki boss)
        goByChat("chatMap2")
        switchLine(1)
        goBuffIfNeeded(returnMapNo = 2, returnLine = 1)
        while (running) {
            if (mapPos == 0) {
                // Raklion 2: her kanalda yalnizca bir boss
                if (scanBoss(line, 2, 2)) {
                    fightBoss(bossPoint = 2, mapNo = 2)
                    goBuffIfNeeded(returnMapNo = 2, returnLine = line)
                }
            } else {
                // Raklion 1: ayni kanalda iki ayri boss ismi/konumu kontrol edilir
                for (bossPoint in listOf(1, 3)) {
                    if (!running) break
                    if (scanBoss(line, 1, bossPoint)) {
                        fightBoss(bossPoint = bossPoint, mapNo = 1)
                        goBuffIfNeeded(returnMapNo = 1, returnLine = line)
                    }
                }
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
        startBtn?.text = if (running) "■  DÖNGÜYÜ DURDUR" else "▶  BOSS DÖNGÜSÜNÜ BAŞLAT"
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
        b.setPadding(dp(10), dp(3), dp(10), dp(3))
        b.minHeight = 0
        b.minimumHeight = 0
        b.minWidth = 0
        b.minimumWidth = 0
        val bg = GradientDrawable()
        bg.setColor(0xFF2A394F.toInt())
        bg.cornerRadius = dp(8).toFloat()
        bg.setStroke(dp(1), 0xFF43546C.toInt())
        b.background = bg
        b.setOnClickListener { onClick() }
        return b
    }

    private fun addBtn(col: LinearLayout, btn: Button) {
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38))
        lp.topMargin = dp(4)
        col.addView(btn, lp)
    }

    private fun buildPanel() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(10), dp(8), dp(10), dp(10))
        val shell = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xF2222D40.toInt(), 0xF2141B29.toInt()))
        shell.cornerRadius = dp(16).toFloat()
        shell.setStroke(dp(1), 0xFFE3AF43.toInt())
        col.background = shell
        col.elevation = dp(10).toFloat()
        col.minimumWidth = dp(158)

        val st = TextView(this)
        st.textSize = 11f
        st.setTextColor(Color.WHITE)
        st.setTextColor(0xFFE8EDF5.toInt())
        val statusBg = GradientDrawable()
        statusBg.setColor(0xFF26364B.toInt())
        statusBg.cornerRadius = dp(8).toFloat()
        st.background = statusBg
        st.setPadding(dp(8), dp(7), dp(8), dp(7))
        st.text = "● Hazir"
        st.maxWidth = dp(260)
        statusView = st
        col.addView(st)

        val sb = mkBtn("▶  BOSS DÖNGÜSÜNÜ BAŞLAT") { toggle() }
        val startBg = GradientDrawable()
        startBg.setColor(0xFF176B59.toInt())
        startBg.cornerRadius = dp(9).toFloat()
        sb.background = startBg
        sb.setPadding(dp(10), 0, dp(10), 0)
        startBtn = sb
        addBtn(col, sb)
        panelButtons.add(sb)

        val sh = mkBtn("Shizuku izni") { shizukuAsk() }; addBtn(col, sh); panelButtons.add(sh)
        val tt = mkBtn("Tik testi") { tapTest() }; addBtn(col, tt); panelButtons.add(tt)
        val ts = mkBtn("Test") { runTest() }; addBtn(col, ts); panelButtons.add(ts)
        val ay = mkBtn("Ayar") { toggleMenu() }; addBtn(col, ay); panelButtons.add(ay)

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = "BOSSINGEN  •  ROTASYON"
        title.textSize = 12f
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        title.setTextColor(0xFFF5C45E.toInt())
        title.setPadding(dp(2), dp(4), dp(10), dp(6))
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        lateinit var minimize: Button
        minimize = mkBtn("—") {
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
            menuParams = menuParams
            return
        }
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(8), dp(6), dp(8), dp(8))
        val menuBg = GradientDrawable()
        menuBg.setColor(0xEE101722.toInt())
        menuBg.cornerRadius = dp(12).toFloat()
        menuBg.setStroke(dp(1), 0xFF53657C.toInt())
        root.background = menuBg

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = "AYAR NOKTALARI  •  SÜRÜKLE"
        title.textSize = 12f
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        title.setTextColor(0xFFF5C45E.toInt())
        title.setPadding(dp(6), dp(8), dp(12), dp(8))
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val close = mkBtn("✕") { toggleMenu() }
        header.addView(close)
        root.addView(header)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
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
        root.addView(row)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = menuParams?.x ?: dp(200)
        lp.y = menuParams?.y ?: dp(10)
        header.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    menuDragStartX = ev.rawX
                    menuDragStartY = ev.rawY
                    menuDragOrigX = lp.x
                    menuDragOrigY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = menuDragOrigX + (ev.rawX - menuDragStartX).toInt()
                    lp.y = menuDragOrigY + (ev.rawY - menuDragStartY).toInt()
                    try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
                    true
                }
                else -> true
            }
        }
        menuParams = lp
        wm.addView(root, lp)
        menu = root
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
