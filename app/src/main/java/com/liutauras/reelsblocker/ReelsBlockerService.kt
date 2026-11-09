package com.liutauras.reelsblocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

private const val IG_PACKAGE = "com.instagram.android"
private const val MSG_REELS = "Open your feed instead."
private const val MSG_ONE_REEL = "One reel at a time."

/**
 * Watches Instagram only (everything else is ignored right away).
 *
 * Rules:
 *  1. Reels tab selected                       -> cover the screen with a "Reels blocked" overlay.
 *  2. Full-screen reel opened from a chat      -> allowed.
 *  3. Full-screen reel opened from anywhere else -> overlay.
 *  4. Swipe to another reel after a chat reel  -> overlay.
 *  The home feed (posts, stories, reels shown inside the feed) is never touched.
 *
 * Instagram does not publish how its screens are built, so detection works by looking at
 * view IDs and labels. They can change in Instagram updates; then this file needs a patch.
 */
class ReelsBlockerService : AccessibilityService() {

    private var dmContext = false          // last "main" screen we saw was a chat
    private var inAllowedReel = false      // watching a reel opened from a chat
    private var reelStartedAt = 0L
    private var reelAuthor: String? = null
    private var stickyMessage: String? = null // stays blocked until the reel screen is left

    private var lastScanAt = 0L
    private var suppressUntil = 0L

    private var overlay: View? = null
    private var focusRequest: AudioFocusRequest? = null

    private class Snapshot {
        val ids = HashSet<String>()
        var reelsTabSelected = false
        var feedTabSelected = false
        var author: String? = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        if (pkg != IG_PACKAGE) {
            // Left Instagram: never keep covering other apps.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                pkg != packageName && pkg != "com.android.systemui"
            ) {
                hideOverlay()
                resetState()
            }
            return
        }

        val now = System.currentTimeMillis()
        if (now < suppressUntil) return
        val isScroll = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        if (!isScroll && now - lastScanAt < 200) return
        lastScanAt = now

        val root = rootInActiveWindow ?: return
        val snap = Snapshot()
        scan(root, snap, 0)
        val ids = snap.ids

        val tabBarVisible = "feed_tab" in ids || "clips_tab" in ids
        val chatOpen = "row_thread_composer_edittext" in ids
        // Only the full-screen reel viewer. Reels shown inline in the feed are ignored.
        val reelViewerOpen = ids.any { it.startsWith("clips_viewer") }

        if (tabBarVisible) dmContext = false
        if (chatOpen) dmContext = true

        var message: String? = null

        if (snap.reelsTabSelected) {
            message = MSG_REELS
        } else if (reelViewerOpen && !snap.feedTabSelected) {
            val sticky = stickyMessage
            if (sticky != null) {
                message = sticky
            } else if (!inAllowedReel) {
                if (dmContext && !tabBarVisible) {
                    inAllowedReel = true
                    reelStartedAt = now
                    reelAuthor = snap.author
                } else {
                    message = MSG_REELS
                    stickyMessage = message
                }
            } else if (now - reelStartedAt > 1200) {
                val authorChanged =
                    reelAuthor != null && snap.author != null && snap.author != reelAuthor
                val pagerScrolled = isScroll && isReelPager(event)
                if (authorChanged || pagerScrolled) {
                    message = MSG_ONE_REEL
                    stickyMessage = message
                    inAllowedReel = false
                } else if (reelAuthor == null && snap.author != null) {
                    reelAuthor = snap.author
                }
            }
        } else if (!reelViewerOpen) {
            inAllowedReel = false
            reelAuthor = null
            stickyMessage = null
        }

        if (message != null) showOverlay(message) else hideOverlay()
    }

    private fun isReelPager(event: AccessibilityEvent): Boolean {
        val cls = event.className?.toString() ?: ""
        val id = event.source?.viewIdResourceName ?: ""
        return cls.contains("ViewPager") || id.contains("clips")
    }

    private fun scan(node: AccessibilityNodeInfo?, snap: Snapshot, depth: Int) {
        if (node == null || depth > 40) return

        val fullId = node.viewIdResourceName
        if (fullId != null) {
            val id = fullId.substringAfter(":id/")
            snap.ids.add(id)
            if (id == "clips_tab" && node.isSelected) snap.reelsTabSelected = true
            if (id == "feed_tab" && node.isSelected) snap.feedTabSelected = true
            if (id == "clips_author_username") snap.author = node.text?.toString()
        } else if (node.isSelected &&
            node.contentDescription?.toString()?.startsWith("Reels") == true
        ) {
            snap.reelsTabSelected = true
        }

        for (i in 0 until node.childCount) {
            scan(node.getChild(i), snap, depth + 1)
        }
    }

    private fun resetState() {
        inAllowedReel = false
        reelAuthor = null
        stickyMessage = null
    }

    // ---- Overlay ---------------------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showOverlay(message: String) {
        if (overlay != null) return
        val view = buildOverlay(message)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        )
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, params)
            overlay = view
            grabAudio()
        } catch (e: Exception) {
            overlay = null
        }
    }

    private fun hideOverlay() {
        val view = overlay ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (e: Exception) {
            // already gone
        }
        overlay = null
        releaseAudio()
    }

    private fun buildOverlay(message: String): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0E0E0E"))
            setPadding(dp(32), dp(32), dp(32), dp(32))
            isClickable = true // swallow touches so nothing behind is pressed
        }
        val title = TextView(this).apply {
            text = "Reels blocked"
            setTextColor(Color.WHITE)
            textSize = 26f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        val sub = TextView(this).apply {
            text = message
            setTextColor(Color.parseColor("#8A8A8A"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(32))
        }
        val button = Button(this).apply {
            text = "Go back"
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 15f
            minimumHeight = 0
            minimumWidth = 0
            stateListAnimator = null
            setPadding(dp(36), 0, dp(36), 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), Color.parseColor("#55FFFFFF"))
                setColor(Color.TRANSPARENT)
            }
            setOnClickListener { goBack() }
        }
        root.addView(title)
        root.addView(sub)
        root.addView(button, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(52)
        ))
        return root
    }

    private fun goBack() {
        val home = rootInActiveWindow
            ?.findAccessibilityNodeInfosByViewId("$IG_PACKAGE:id/feed_tab")
            ?.firstOrNull()
        hideOverlay()
        resetState()
        suppressUntil = System.currentTimeMillis() + 1200
        if (home == null || !home.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    // Best effort: asks other apps to pause so reel audio doesn't keep playing behind the overlay.
    private fun grabAudio() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .build()
            am.requestAudioFocus(request)
            focusRequest = request
        } catch (e: Exception) {
            focusRequest = null
        }
    }

    private fun releaseAudio() {
        val request = focusRequest ?: return
        try {
            (getSystemService(AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(request)
        } catch (e: Exception) {
            // ignore
        }
        focusRequest = null
    }

    override fun onInterrupt() {
        hideOverlay()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hideOverlay()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }
}
