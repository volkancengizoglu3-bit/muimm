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
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Ayar ekrani: sayisal degerler burada girilir, servis rotayi okurken bunlari kullanir. */
class MainActivity : Activity() {

    private class Field(val key: String, val label: String, val hint: String, val def: String, val decimal: Boolean = false)

    private val huntFields = listOf(
        Field("arrive", "Varış mesafesi", "İmleç boss ikonuna bu kadar yaklaşınca harita kapanır (piksel)", "45"),
        Field("walkTimeout", "Varış zaman aşımı", "Boss'a bu sürede varılamazsa yeni tarama (sn)", "60"),
        Field("fightTimeout", "Savaş zaman aşımı", "Bir boss için en fazla bekleme (sn)", "300"),
        Field("loadWait", "Kanal değişim beklemesi", "Kanal değişince yüklenmeyi bekle (sn)", "5"),
        Field("tpWait", "Işınlanma beklemesi", "Chat bağlantısına dokununca yüklenmeyi bekle (sn)", "6")
    )

    private val respawnFields = listOf(
        Field("respR2", "Raklion 2 yeniden doğuş", "Saniye (35:15 = 2115)", "2115"),
        Field("respR1", "Raklion 1 yeniden doğuş", "Saniye (43:00 = 2580)", "2580")
    )

    private val buffFields = listOf(
        Field("minAtk", "Minimum ATK", "ATK ve DEF ikisi de altındaysa buff'a gidilir", "0"),
        Field("minDef", "Minimum DEF", "", "0"),
        Field("buffTravel", "Buff yürüme süresi", "Buff noktasına varmak için bekleme (sn)", "12"),
        Field("buffWait", "Buff bekleme sınırı", "Buff'ın gelmesini en fazla bu kadar bekle (sn)", "90")
    )

    private val BG = 0xFF0F1115.toInt()
    private val CARD = 0xFF1B1F2A.toInt()
    private val INPUT = 0xFF262B3A.toInt()
    private val ACCENT = 0xFFE5603D.toInt()
    private val DIM = 0xFF9AA3B5.toInt()

    private val inputs = HashMap<String, EditText>()
    private lateinit var buffOn: Switch
    private lateinit var stopAuto: Switch

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        return g
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(24), dp(16), dp(24))

        val title = TextView(this)
        title.text = "MU Boss Bot"
        title.textSize = 28f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(ACCENT)
        col.addView(title)
        val sub = TextView(this)
        sub.text = "MU Immortal · Raklion kırmızı boss avı"
        sub.textSize = 13f
        sub.setTextColor(DIM)
        sub.setPadding(0, 0, 0, dp(14))
        col.addView(sub)

        // kurulum karti
        val setup = card(col, "Kurulum")
        val steps = TextView(this)
        steps.textSize = 13f
        steps.setTextColor(Color.WHITE)
        steps.setLineSpacing(dp(3).toFloat(), 1f)
        steps.text = "1  Erişilebilirlik ayarlarından 'MU Boss Bot'u aç.\n" +
            "    Açılmıyorsa: Uygulama bilgisi > ⋮ > Kısıtlı ayarlara izin ver.\n" +
            "2  Shizuku'yu başlat, oyunda panelden 'Shizuku' ile izin ver.\n" +
            "3  Aşağıdaki değerleri kaydet.\n" +
            "4  Chat'te Raklion 1 ve Raklion 2 başlangıç koordinatlarını ve buff noktasını " +
            "aynı sekmede görünür tut.\n" +
            "5  Oyunda 'Ayar' ile kırmızı satırları işaretle (chat, chatTab, linkR1, linkR2, " +
            "buffLink, miniTL, miniBR, revive).\n" +
            "6  Haritayı aç, 'Test'e bas: canlı boss sayısı ve imleç görünmeli.\n" +
            "7  'Başlangıç' düğmesiyle şu an bulunduğun harita/kanalı seç, '▶ Başlat'.\n\n" +
            "Rota: Raklion 2 kanal 1-2-3, sonra Raklion 1 kanal 1-2-3, sonra baştan."
        setup.addView(steps)
        val acc = button("Erişilebilirlik ayarlarını aç", false)
        acc.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        setup.addView(acc, lp(dp(10)))

        // av karti
        val hunt = card(col, "Av")
        for (f in huntFields) addField(hunt, f)
        stopAuto = Switch(this)
        stopAuto.text = "Boss ölünce oto saldırıyı kapat"
        stopAuto.setTextColor(Color.WHITE)
        stopAuto.textSize = 13f
        stopAuto.isChecked = prefs().getBoolean("cfg_stopAuto", true)
        hunt.addView(stopAuto, lp(dp(10)))

        // respawn karti
        val resp = card(col, "Respawn sayaçları")
        for (f in respawnFields) addField(resp, f)

        // buff karti
        val buff = card(col, "Buff")
        buffOn = Switch(this)
        buffOn.text = "Buff alma açık"
        buffOn.setTextColor(Color.WHITE)
        buffOn.textSize = 14f
        buffOn.isChecked = prefs().getBoolean("cfg_buffOn", true)
        buff.addView(buffOn)
        for (f in buffFields) addField(buff, f)

        val save = button("Kaydet", true)
        save.setOnClickListener {
            val e = prefs().edit()
            for ((k, et) in inputs) {
                val f = (huntFields + respawnFields + buffFields).first { it.key == k }
                val raw = et.text.toString().trim()
                e.putString("cfg_" + k, if (raw.isEmpty()) f.def else raw)
            }
            e.putBoolean("cfg_buffOn", buffOn.isChecked)
            e.putBoolean("cfg_stopAuto", stopAuto.isChecked)
            e.apply()
            Toast.makeText(this, "Kaydedildi", Toast.LENGTH_SHORT).show()
        }
        col.addView(save, lp(dp(6)))

        val sv = ScrollView(this)
        sv.setBackgroundColor(BG)
        sv.addView(col)
        setContentView(sv)
    }

    private fun lp(top: Int): LinearLayout.LayoutParams {
        val l = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        l.topMargin = top
        return l
    }

    private fun card(parent: LinearLayout, heading: String): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = rounded(CARD, 16)
        c.setPadding(dp(14), dp(12), dp(14), dp(14))
        val h = TextView(this)
        h.text = heading
        h.textSize = 16f
        h.typeface = Typeface.DEFAULT_BOLD
        h.setTextColor(ACCENT)
        h.setPadding(0, 0, 0, dp(6))
        c.addView(h)
        parent.addView(c, lp(dp(12)))
        return c
    }

    private fun button(text: String, primary: Boolean): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = 14f
        b.typeface = Typeface.DEFAULT_BOLD
        b.setTextColor(Color.WHITE)
        b.background = rounded(if (primary) ACCENT else INPUT, 12)
        b.minHeight = dp(46)
        b.minimumHeight = dp(46)
        return b
    }

    private fun addField(parent: LinearLayout, f: Field) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        val l = TextView(this)
        l.text = f.label
        l.textSize = 14f
        l.setTextColor(Color.WHITE)
        left.addView(l)
        if (f.hint.isNotEmpty()) {
            val hnt = TextView(this)
            hnt.text = f.hint
            hnt.textSize = 11f
            hnt.setTextColor(DIM)
            left.addView(hnt)
        }
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val et = EditText(this)
        et.inputType = if (f.decimal) {
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        } else {
            InputType.TYPE_CLASS_NUMBER
        }
        et.setText(prefs().getString("cfg_" + f.key, f.def))
        et.setTextColor(Color.WHITE)
        et.textSize = 15f
        et.gravity = Gravity.CENTER
        et.background = rounded(INPUT, 10)
        et.setPadding(dp(8), dp(8), dp(8), dp(8))
        inputs[f.key] = et
        val elp = LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT)
        elp.leftMargin = dp(10)
        row.addView(et, elp)

        parent.addView(row, lp(dp(10)))
    }

    private fun prefs() = getSharedPreferences("mubot", Context.MODE_PRIVATE)
}
