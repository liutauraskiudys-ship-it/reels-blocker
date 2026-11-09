package com.liutauras.reelsblocker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val text = TextView(this).apply {
            textSize = 16f
            text = "Reels Blocker\n\n" +
                "To turn it on:\n" +
                "1. Tap the button below.\n" +
                "2. Open \"Installed apps\" (or \"Downloaded apps\").\n" +
                "3. Tap Reels Blocker and switch it on.\n\n" +
                "Once on, Instagram will send you back if you open the Reels tab, " +
                "Explore reels, or swipe to another reel. Reels sent in chats " +
                "open normally, one at a time.\n\n" +
                "If Android says the setting is restricted: go to Settings > Apps > " +
                "Reels Blocker > three dots (top right) > Allow restricted settings, then try again."
        }

        val button = Button(this).apply {
            this.text = "Open Accessibility settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        layout.addView(text)
        layout.addView(button)
        setContentView(layout)
    }
}
