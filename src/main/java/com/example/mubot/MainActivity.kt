package com.example.mubot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(pad, pad, pad, pad)

        val info = TextView(this)
        info.textSize = 16f
        info.text = "MU Immortal - Raklion Boss Botu\n\n" +
            "KURULUM\n" +
            "1) Asagidaki dugmeyle Erisilebilirlik ayarlarini ac, 'MU Boss Bot' servisini ac.\n" +
            "   Servis kapali gorunup acilmiyorsa: Ayarlar > Uygulamalar > MU Boss Bot > " +
            "sag ust uc nokta > 'Kisitli ayarlara izin ver', sonra tekrar dene.\n" +
            "2) Oyuna gir, Raklion'da bir haritada dur. Sol tarafta kucuk bir panel gorunecek.\n" +
            "3) 'Test' dugmesine bas: noktalar kirmizi isaretle gosterilir. Yanlis olanlari " +
            "'Ayar' menusunden sec, ilgili ekrani oyunda ac ve dogru yere dokun.\n" +
            "   Ozellikle: wire, minimap, boss1, boss2, boss3.\n" +
            "4) Raklion'da, baslangic kanalini panelde ayarla ve '▶ Baslat'a bas.\n\n" +
            "Not: Android 11 veya uzeri gerekir."
        col.addView(info)

        val btn = Button(this)
        btn.text = "Erisilebilirlik ayarlarini ac"
        btn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        col.addView(
            btn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val sv = ScrollView(this)
        sv.addView(col)
        setContentView(sv)
    }
}
