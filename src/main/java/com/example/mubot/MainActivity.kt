package com.example.mubot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("mubot", Context.MODE_PRIVATE) }
    private lateinit var buffEnabled: CheckBox
    private lateinit var minAtk: EditText
    private lateinit var minDef: EditText

    private val bg = 0xFF0C1220.toInt()
    private val card = 0xFF172033.toInt()
    private val accent = 0xFF67B7FF.toInt()
    private val muted = 0xFFA3B1C7.toInt()
    private val white = 0xFFF1F6FF.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val scroll = ScrollView(this).apply { setBackgroundColor(bg); clipToPadding = false }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "MU IMMORTAL  /  AUTOMATION"
            textSize = 11f
            letterSpacing = .16f
            setTextColor(accent)
            setTypeface(null, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Bossingen"
            textSize = 32f
            setTextColor(white)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(2))
        })
        root.addView(body("Raklion 2 → 3 kanal → Raklion 1 → 3 kanal. Boss durumu mini haritadan takip edilir."))

        val access = makeCard()
        access.addView(section("01  /  BAŞLATMA"))
        access.addView(body("Oyuna gir ve erişilebilirlik servisini etkinleştir. Shizuku kullanıyorsan yüzen panelden iznini ver. Paneli başlığından sürükleyebilir, — düğmesiyle küçültebilirsin."))
        access.addView(actionButton("Erişilebilirlik ayarlarını aç") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(access, margin())

        val buff = makeCard()
        buff.addView(section("02  /  BUFF PROTOKOLÜ"))
        buff.addView(body("Buff açıkken başlangıçta ve her boss öldüğünde ATK/DEF okunur. ATK veya DEF kendi sınırının altındaysa chat üzerinden kayıtlı buff koordinatına gidilir. Değerler okunamazsa bot buff kararını güvenli biçimde atlar."))
        buffEnabled = CheckBox(this).apply {
            text = "Buff alma açık"
            textSize = 15f
            setTextColor(white)
            buttonTintList = android.content.res.ColorStateList.valueOf(accent)
            isChecked = prefs.getBoolean("cfg_buffOn", false)
        }
        buff.addView(buffEnabled)
        minAtk = numberField("Minimum ATK", prefs.getInt("cfg_minAtk", 0))
        minDef = numberField("Minimum DEF", prefs.getInt("cfg_minDef", 0))
        buff.addView(label("Minimum ATK")); buff.addView(minAtk)
        buff.addView(label("Minimum DEF")); buff.addView(minDef)
        buff.addView(actionButton("Buff ayarlarını kaydet") { saveBuff() })
        root.addView(buff, margin())

        val points = makeCard()
        points.addView(section("03  /  OYUN İÇİ NOKTALAR"))
        points.addView(body("Oyunda yüzen panelde Ayar düğmesine bas. Her nokta için ilgili satırı seçip oyunda hedefe dokun. Aşağıdaki noktaları mutlaka kaydet ve Tik testi ile doğrula."))
        val required = listOf(
            "random — haritanın sağ altındaki Random",
            "boss1 / boss2 — ilgili haritadaki boss simgesi",
            "miniTL / miniBR — mini haritanın sol üst ve sağ alt köşeleri",
            "chat — chat açma alanı",
            "chatMap1 / chatMap2 — chat içindeki Raklion 1 ve Raklion 2 koordinatları",
            "buffLink — chat içindeki buff konum koordinatı",
            "closeChat — chat kapatma düğmesi",
            "manual — otomatik saldırı düğmesi"
        )
        required.forEach { points.addView(body("•  $it")) }
        points.addView(body("Boss işaretleri ve chat hedefleri oyun arayüzüne göre değişebilir. Otomasyonu başlatmadan önce tüm noktaları doğru ekranda ayarla."))
        root.addView(points, margin())

        val safety = makeCard()
        safety.addView(section("04  /  ÇALIŞMA NOTLARI"))
        safety.addView(body("İlk çalıştırmada karakterin Raklion 2'ye geçişini, kanal değişimini ve mini harita simgesinin solma eşiğini gözlemle. OCR ile ATK/DEF okuma oyun yazı tipine göre cihazda doğrulanmalıdır."))
        root.addView(safety)
    }

    private fun saveBuff() {
        val atk = minAtk.text.toString().trim().toIntOrNull()
        val def = minDef.text.toString().trim().toIntOrNull()
        if (atk == null || def == null || atk < 0 || def < 0) {
            Toast.makeText(this, "ATK ve DEF için 0 veya daha büyük tam sayı gir.", Toast.LENGTH_LONG).show()
            return
        }
        prefs.edit()
            .putBoolean("cfg_buffOn", buffEnabled.isChecked)
            .putInt("cfg_minAtk", atk)
            .putInt("cfg_minDef", def)
            .apply()
        Toast.makeText(this, "Buff ayarları kaydedildi.", Toast.LENGTH_SHORT).show()
    }

    private fun makeCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(card, 18)
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        letterSpacing = .08f
        setTextColor(accent)
        setTypeface(null, Typeface.BOLD)
        setPadding(0, 0, 0, dp(8))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(muted)
        setPadding(0, 0, 0, dp(10))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(white)
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun numberField(value: String, initial: Int) = EditText(this).apply {
        hint = value
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine(true)
        textSize = 15f
        setTextColor(white)
        setHintTextColor(muted)
        setText(initial.toString())
        background = rounded(0xFF1F2B41.toInt(), 10)
        setPadding(dp(12), dp(9), dp(12), dp(9))
    }

    private fun actionButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        setTextColor(white)
        background = rounded(0xFF245C91.toInt(), 12)
        setOnClickListener { action() }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun margin() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(14) }
}
