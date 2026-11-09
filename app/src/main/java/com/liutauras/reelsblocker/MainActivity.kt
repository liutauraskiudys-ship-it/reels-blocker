package com.liutauras.reelsblocker

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private val bg = Color.parseColor("#0E0E0E")
    private val muted = Color.parseColor("#8A8A8A")
    private lateinit var status: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(32), dp(96), dp(32), dp(32))
        }

        status = TextView(this).apply {
            textSize = 13f
            setPadding(0, 0, 0, dp(16))
        }

        val title = TextView(this).apply {
            text = "Reels Blocker"
            setTextColor(Color.WHITE)
            textSize = 34f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }

        val body = TextView(this).apply {
            text = "Blocks the Instagram Reels tab and Explore reels.\n" +
                "Reels sent in chats open one at a time."
            setTextColor(muted)
            textSize = 15f
            setLineSpacing(0f, 1.3f)
            setPadding(0, dp(16), 0, dp(40))
        }

        val steps = TextView(this).apply {
            text = "1   Open accessibility settings\n" +
                "2   Find Reels Blocker\n" +
                "3   Turn it on"
            setTextColor(Color.parseColor("#C8C8C8"))
            textSize = 15f
            setLineSpacing(0f, 1.5f)
            setPadding(0, 0, 0, dp(40))
        }

        val tip = TextView(this).apply {
            text = "Tip: on Instagram's home screen, tap \"Instagram\" at the top and choose " +
                "Following to hide suggested posts and reels from your feed."
            setTextColor(muted)
            textSize = 13f
            setLineSpacing(0f, 1.3f)
            setPadding(0, dp(32), 0, 0)
        }

        val button = Button(this).apply {
            text = "Open accessibility settings"
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 15f
            minimumHeight = 0
            minimumWidth = 0
            stateListAnimator = null
            setPadding(dp(28), 0, dp(28), 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), Color.parseColor("#55FFFFFF"))
                setColor(Color.TRANSPARENT)
            }
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        root.addView(status)
        root.addView(title)
        root.addView(body)
        root.addView(steps)
        root.addView(button, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(52)
        ))
        root.addView(tip)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (isServiceEnabled()) {
            status.text = "●  On"
            status.setTextColor(Color.parseColor("#4ADE80"))
        } else {
            status.text = "○  Off"
            status.setTextColor(muted)
        }
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val full = "$packageName/${ReelsBlockerService::class.java.name}"
        val short = "$packageName/.ReelsBlockerService"
        return enabled.split(':').any { it.equals(full, true) || it.equals(short, true) }
    }
}
