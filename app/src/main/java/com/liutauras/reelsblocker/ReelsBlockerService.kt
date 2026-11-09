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
 * Watches Instagram only (everything else is ignored right away).
 *
 * - Reels tab, or a full-screen reel opened from anywhere except a chat -> full-screen overlay.
 * - Full-screen reel opened from a chat -> allowed, one reel; swiping on -> full-screen overlay.
 * - Reels shown inside the home feed -> only that reel is covered (the rest of the feed,
 *   posts and stories stay usable).
 * - Stories are never touched.
 *
 * Instagram does not publish how its screens are built, so detection works by looking at
 * view IDs, labels and sizes. Those can change in Instagram updates; then this file needs a patch.
 */
class ReelsBlockerService : AccessibilityService() {

    private var dmContext = false          // last "main" screen we saw was a chat
    private var inAllowedReel = false      // watching a reel opened from a chat
    private var reelStartedAt = 0L
    private var reelAuthor: String? = null
    private var stickyMessage: String? = null // stays blocked until the reel screen is left

    private var lastScanAt = 0L
    private var lastTrackAt = 0L
    private var suppressUntil = 0L

    // Full-screen overlay
    private var overlay: View? = null
    private var debugView: TextView? = null
    private var focusRequest: AudioFocusRequest? = null

    // Overlays that cover single reels inside the home feed
    private val regionViews = ArrayList<View>()
    private var regionNodes: List<AccessibilityNodeInfo> = emptyList()

    private class Snapshot {
        val ids = HashSet<String>()
        var reelsTabSelected = false
        var feedTabSelected = false
        var author: String? = null
        val pagers = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        val medias = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        val reelDescs = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
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
                clearRegions()
                resetState()
            }
            return
        }

        val now = System.currentTimeMillis()
        val isScroll = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED

        // Fast path: keep the single-reel overlays glued to their reels while the feed scrolls.
        if (isScroll && regionNodes.isNotEmpty() && now - lastTrackAt >= 16) {
            lastTrackAt = now
            trackRegions()
        }

        if (now < suppressUntil) return
        val minGap = if (isScroll && inAllowedReel) 0L else 150L
        if (now - lastScanAt < minGap) return
        lastScanAt = now

        val root = rootInActiveWindow ?: return
        val snap = Snapshot()
        scan(root, snap, 0)
        val ids = snap.ids

        val screenH = resources.displayMetrics.heightPixels
        val fullH = (screenH * 85) / 100
        val topMax = screenH / 20

        val tabBarVisible = "feed_tab" in ids || "clips_tab" in ids
        val chatOpen = "row_thread_composer_edittext" in ids
        // Instagram calls stories "reel" internally.
        val storyOpen = ids.any { it.startsWith("reel_viewer") }
        val pagerH = snap.pagers.maxOfOrNull { it.second.height() } ?: 0
        // Full-screen reel viewer: a pager that fills the screen, not one that sits in the feed.
        val fullScreenViewer = !storyOpen && !snap.feedTabSelected &&
            snap.pagers.any { it.second.height() >= fullH && it.second.top <= topMax }
        // If the Home tab is selected we are on the home feed, whatever the Reels tab claims.
        val reelsTab = snap.reelsTabSelected && !snap.feedTabSelected && !storyOpen

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
        } else {
            inAllowedReel = false
            reelAuthor = null
            stickyMessage = null
        }

        if (message != null) {
            clearRegions()
            val clips = ids.filter { it.startsWith("clips") }.take(4).joinToString(",")
            val debug = "reelsTab=${snap.reelsTabSelected} feedTab=${snap.feedTabSelected} " +
                "fs=$fullScreenViewer story=$storyOpen bar=$tabBarVisible dm=$dmContext " +
                "pagerH=$pagerH/$screenH ids=[$clips]"
            showOverlay(message, debug)
        } else {
            hideOverlay()
            if (snap.feedTabSelected && tabBarVisible && !storyOpen) {
                setRegions(snap)
            } else {
                clearRegions()
            }
        }
    }

    private fun isReelPager(event: AccessibilityEvent): Boolean {
        val cls = event.className?.toString() ?: ""
        val id = event.source?.viewIdResourceName ?: ""
        return cls.contains("ViewPager") || id.contains("clips")
    }

    private fun boundsOf(node: AccessibilityNodeInfo): Rect =
        Rect().also { node.getBoundsInScreen(it) }

    private fun scan(node: AccessibilityNodeInfo?, snap: Snapshot, depth: Int) {
        if (node == null || depth > 40) return

        val fullId = node.viewIdResourceName
        if (fullId != null) {
            val id = fullId.substringAfter(":id/")
            snap.ids.add(id)
            when (id) {
                "clips_tab" -> if (node.isSelected) snap.reelsTabSelected = true
                "feed_tab" -> if (node.isSelected) snap.feedTabSelected = true
                "clips_author_username" -> snap.author = node.text?.toString()
                "clips_viewer_view_pager" -> snap.pagers.add(Pair(node, boundsOf(node)))
                "clips_media_component" -> snap.medias.add(Pair(node, boundsOf(node)))
            }
        }
        val desc = node.contentDescription?.toString()
        if (desc != null && desc.startsWith("Reel by")) {
            snap.reelDescs.add(Pair(node, boundsOf(node)))
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

    // ---- Single-reel overlays (home feed) -------------------------------------------------

    private fun setRegions(snap: Snapshot) {
        val primary = if (snap.pagers.isNotEmpty()) snap.pagers else snap.medias
        val candidates = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        for (p in primary + snap.reelDescs) {
            val r = p.second
            if (r.height() >= dp(120) && r.width() >= dp(120)) candidates.add(p)
        }
        candidates.sortByDescending { it.second.width() * it.second.height() }
        val kept = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        for (c in candidates) {
            if (kept.none { it.second.contains(c.second) }) kept.add(c)
        }
        regionNodes = kept.map { it.first }
        applyRegions(kept.mapNotNull { clamp(it.second) })
    }

    private fun trackRegions() {
        val alive = regionNodes.filter {
            try {
                it.refresh()
            } catch (e: Exception) {
                false
            }
        }
        regionNodes = alive
        applyRegions(alive.mapNotNull { clamp(boundsOf(it)) })
    }

    private fun clamp(src: Rect): Rect? {
        val dm = resources.displayMetrics
        val r = Rect(src)
        if (!r.intersect(0, 0, dm.widthPixels, dm.heightPixels)) return null
        return if (r.height() < dp(8) || r.width() < dp(8)) null else r
    }

    private fun regionParams(r: Rect): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            r.width(),
            r.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = r.left
            y = r.top
        }
    }

    private fun applyRegions(rects: List<Rect>) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        while (regionViews.size > rects.size) {
            val v = regionViews.removeAt(regionViews.size - 1)
            try {
                wm.removeView(v)
            } catch (e: Exception) {
                // already gone
            }
        }
        for (i in rects.indices) {
            val params = regionParams(rects[i])
            if (i < regionViews.size) {
                try {
                    wm.updateViewLayout(regionViews[i], params)
                } catch (e: Exception) {
                    // ignore
                }
            } else {
                val v = buildRegion()
                try {
                    wm.addView(v, params)
                    regionViews.add(v)
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }

    private fun clearRegions() {
        regionNodes = emptyList()
        if (regionViews.isNotEmpty()) applyRegions(emptyList())
    }

    private fun buildRegion(): View {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.gravity = Gravity.CENTER
        container.setBackgroundColor(Color.parseColor("#0E0E0E"))
        val label = TextView(this)
        label.text = "Reels blocked"
        label.setTextColor(Color.parseColor("#8A8A8A"))
        label.textSize = 14f
        label.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        label.gravity = Gravity.CENTER
        container.addView(label)
        return container
    }

    // ---- Full-screen overlay --------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showOverlay(message: String, debug: String) {
        if (overlay != null) {
            debugView?.text = debug
            return
        }
        val view = buildOverlay(message, debug)
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
            debugView = null
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
        debugView = null
        releaseAudio()
    }

    private fun buildOverlay(message: String, debug: String): View {
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
        // Temporary technical line, helps find why a screen was treated as Reels.
        val debugText = TextView(this).apply {
            text = debug
            setTextColor(Color.parseColor("#555555"))
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, dp(48), 0, 0)
        }
        debugView = debugText
        root.addView(debugText)
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
        clearRegions()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hideOverlay()
        clearRegions()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        hideOverlay()
        clearRegions()
        super.onDestroy()
    }
}
