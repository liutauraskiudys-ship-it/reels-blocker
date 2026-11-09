package com.liutauras.reelsblocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
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
 * Watches Instagram only (events from every other app are dropped immediately).
 *
 * - Reels tab, or a full-screen reel opened from anywhere except a chat -> full-screen overlay.
 * - Full-screen reel opened from a chat -> allowed, one reel; swiping on -> full-screen overlay.
 * - Stories, posts and the home feed are never touched.
 *
 * Instagram does not publish how its screens are built, so detection works by looking at
 * view IDs, labels and sizes. They can change in Instagram updates; then this file needs a patch.
 */
class ReelsBlockerService : AccessibilityService() {

    private var dmContext = false          // last "main" screen we saw was a chat
    private var inAllowedReel = false      // watching a reel opened from a chat
    private var reelStartedAt = 0L
    private var reelAuthor: String? = null
    private var stickyMessage: String? = null // stays blocked until the reel screen is left

    private var lastScanAt = 0L
    private var lastStoryCheckAt = 0L
    private var lastStoryResult = false
    private var suppressUntil = 0L

    private var overlay: View? = null
    private var focusRequest: AudioFocusRequest? = null

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
        val minGap = if (isScroll && inAllowedReel) 0L else 120L
        if (now - lastScanAt < minGap) return
        lastScanAt = now

        val root = rootInActiveWindow ?: return

        // Targeted lookups (one call each) instead of walking the whole screen.
        val feedTabs = byId(root, "feed_tab")
        val clipsTabs = byId(root, "clips_tab")
        val feedSel = feedTabs.any { it.isSelected }
        val reelsSel = clipsTabs.any { it.isSelected }
        val tabBarVisible = feedTabs.isNotEmpty() || clipsTabs.isNotEmpty()
        val chatOpen = byId(root, "row_thread_composer_edittext").isNotEmpty()
        val author = byId(root, "clips_author_username").firstOrNull()?.text?.toString()
        // Only pagers that are really on screen count: Instagram keeps the Reels page alive,
        // hidden, behind the other tabs.
        val pagerBounds = byId(root, "clips_viewer_view_pager")
            .filter { it.isVisibleToUser }.map { boundsOf(it) }
        val otherTabSelected = listOf("search_tab", "profile_tab", "direct_tab", "creation_tab")
            .any { id -> byId(root, id).any { it.isSelected } }

        val screenH = resources.displayMetrics.heightPixels
        val fullH = (screenH * 85) / 100
        val topMax = screenH / 20

        // Full-screen reel viewer: a pager that fills the screen, not one that sits in the feed.
        var fullScreenViewer = !feedSel && !otherTabSelected && pagerBounds.any { it.height() >= fullH && it.top <= topMax }
        // If the Home tab is selected we are on the home feed, whatever the Reels tab claims.
        // The Reels tab keeps its "selected" flag after you leave it, so it only counts when no
        // other tab is selected and the reel pager is actually visible.
        var reelsTab = reelsSel && !feedSel && !otherTabSelected && pagerBounds.isNotEmpty()

        // Stories (Instagram calls them "reel" internally) must never be blocked. Checked only
        // when we are about to block something, and at most every 400 ms.
        if (reelsTab || fullScreenViewer) {
            if (now - lastStoryCheckAt > 400) {
                lastStoryCheckAt = now
                lastStoryResult = hasStoryIds(root, 0)
            }
            if (lastStoryResult) {
                reelsTab = false
                fullScreenViewer = false
            }
        }

        if (tabBarVisible) dmContext = false
        if (chatOpen) dmContext = true

        var message: String? = null

        if (reelsTab) {
            message = MSG_REELS
        } else if (fullScreenViewer) {
            val sticky = stickyMessage
            if (sticky != null) {
                message = sticky
            } else if (!inAllowedReel) {
                if (dmContext && !tabBarVisible) {
                    inAllowedReel = true
                    reelStartedAt = now
                    reelAuthor = author
                } else {
                    message = MSG_REELS
                    stickyMessage = message
                }
            } else if (now - reelStartedAt > 1200) {
                val authorChanged = reelAuthor != null && author != null && author != reelAuthor
                val pagerScrolled = isScroll && isReelPager(event)
                if (authorChanged || pagerScrolled) {
                    message = MSG_ONE_REEL
                    stickyMessage = message
                    inAllowedReel = false
                } else if (reelAuthor == null && author != null) {
                    reelAuthor = author
                }
            }
        } else {
            resetState()
        }

        if (message != null) showOverlay(message) else hideOverlay()
    }

    private fun isReelPager(event: AccessibilityEvent): Boolean {
        val cls = event.className?.toString() ?: ""
        val id = event.source?.viewIdResourceName ?: ""
        return cls.contains("ViewPager") || id.contains("clips")
    }

    private fun hasStoryIds(n: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (n == null || depth > 40) return false
        val id = n.viewIdResourceName
        if (id != null && id.substringAfter(":id/").startsWith("reel_viewer")) return true
        for (i in 0 until n.childCount) {
            if (hasStoryIds(n.getChild(i), depth + 1)) return true
        }
        return false
    }

    private fun byId(root: AccessibilityNodeInfo, id: String): List<AccessibilityNodeInfo> =
        root.findAccessibilityNodeInfosByViewId("$IG_PACKAGE:id/$id") ?: emptyList()

    private fun boundsOf(node: AccessibilityNodeInfo): Rect =
        Rect().also { node.getBoundsInScreen(it) }

    private fun resetState() {
        inAllowedReel = false
        reelAuthor = null
        stickyMessage = null
    }

    // ---- Overlay --------------------------------------------------------------------------

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
