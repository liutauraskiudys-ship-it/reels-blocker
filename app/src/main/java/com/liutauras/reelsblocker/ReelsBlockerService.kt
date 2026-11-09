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
import android.os.Handler
import android.os.Looper
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
private const val REGION_GRACE_MS = 350L

/**
 * Watches Instagram only (everything else is ignored right away).
 *
 * - Reels tab, or a full-screen reel opened from anywhere except a chat -> full-screen overlay.
 * - Full-screen reel opened from a chat -> allowed, one reel; swiping on -> full-screen overlay.
 * - Reels shown inside the home feed -> only that reel is covered (the rest of the feed,
 *   posts and stories stay usable).
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
    private var lastStoryCheckAt = 0L
    private var lastStoryResult = false
    private var lastDebugAt = 0L
    private var suppressUntil = 0L
    private var debugIds = ""

    // Full-screen overlay
    private var overlay: View? = null
    private var debugView: TextView? = null
    private var focusRequest: AudioFocusRequest? = null

    // Overlays that cover single reels inside the home feed
    private class Region(var node: AccessibilityNodeInfo, var rect: Rect, var seenAt: Long)

    private val regions = ArrayList<Region>()
    private val regionViews = ArrayList<View>()
    private var lastApplied: List<Rect> = emptyList()

    // While a reel overlay exists, follow the reel ~60 times a second instead of waiting for
    // Instagram's (late, throttled) scroll events.
    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    private val tick = object : Runnable {
        override fun run() {
            if (regions.isEmpty()) {
                ticking = false
                return
            }
            trackRegions(System.currentTimeMillis())
            handler.postDelayed(this, 16)
        }
    }

    private fun ensureTicking() {
        if (!ticking && regions.isNotEmpty()) {
            ticking = true
            handler.post(tick)
        }
    }

    // Temporary technical strip at the top of the screen
    private var badge: TextView? = null

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
                hideBadge()
                resetState()
            }
            return
        }

        val now = System.currentTimeMillis()
        val isScroll = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED

        if (now < suppressUntil) return
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
        val pagers = byId(root, "clips_viewer_view_pager").map { Pair(it, boundsOf(it)) }
        val medias = byId(root, "clips_media_component").map { Pair(it, boundsOf(it)) }
        val videos = byId(root, "clips_video_container").map { Pair(it, boundsOf(it)) }
        val descs = (root.findAccessibilityNodeInfosByText("Reel by") ?: emptyList())
            .map { Pair(it, boundsOf(it)) }

        val screenH = resources.displayMetrics.heightPixels
        val fullH = (screenH * 85) / 100
        val topMax = screenH / 20

        val pagerH = pagers.maxOfOrNull { it.second.height() } ?: 0
        // Full-screen reel viewer: a pager that fills the screen, not one that sits in the feed.
        var fullScreenViewer = !feedSel &&
            pagers.any { it.second.height() >= fullH && it.second.top <= topMax }
        // If the Home tab is selected we are on the home feed, whatever the Reels tab claims.
        var reelsTab = reelsSel && !feedSel

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
            inAllowedReel = false
            reelAuthor = null
            stickyMessage = null
        }

        if (message != null) {
            clearRegions()
            hideBadge()
            val debug = "reelsSel=$reelsSel feedSel=$feedSel fs=$fullScreenViewer " +
                "bar=$tabBarVisible dm=$dmContext pagerH=$pagerH/$screenH"
            showOverlay(message, debug)
        } else {
            hideOverlay()
            if (feedSel && tabBarVisible) {
                updateRegions(pagers, medias, videos, descs, now)
            } else {
                clearRegions()
            }
            if (tabBarVisible) {
                if (now - lastDebugAt > 1500) {
                    lastDebugAt = now
                    debugIds = collectDebug(root)
                }
                val ms = System.currentTimeMillis() - now
                showBadge(
                    "feed=$feedSel reels=$reelsSel P=${pagers.size} M=${medias.size} " +
                        "V=${videos.size} D=${descs.size} R=${regions.size} ${ms}ms\n" +
                        "P:${fmt(pagers)} M:${fmt(medias)} V:${fmt(videos)} D:${fmt(descs)} " +
                        "H=${resources.displayMetrics.heightPixels}\n$debugIds"
                )
            } else {
                hideBadge()
            }
        }
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

    private fun fmt(list: List<Pair<AccessibilityNodeInfo, Rect>>): String =
        "[" + list.take(2).joinToString(",") { "${it.second.top}-${it.second.bottom}" } + "]"

    private fun byId(root: AccessibilityNodeInfo, id: String): List<AccessibilityNodeInfo> =
        root.findAccessibilityNodeInfosByViewId("$IG_PACKAGE:id/$id") ?: emptyList()

    private fun boundsOf(node: AccessibilityNodeInfo): Rect =
        Rect().also { node.getBoundsInScreen(it) }

    private fun resetState() {
        inAllowedReel = false
        reelAuthor = null
        stickyMessage = null
    }

    // ---- Single-reel overlays (home feed) -------------------------------------------------

    private fun updateRegions(
        pagers: List<Pair<AccessibilityNodeInfo, Rect>>,
        medias: List<Pair<AccessibilityNodeInfo, Rect>>,
        videos: List<Pair<AccessibilityNodeInfo, Rect>>,
        descs: List<Pair<AccessibilityNodeInfo, Rect>>,
        now: Long
    ) {
        // A reel that is playing in the feed shows up under different ids than one that is only
        // peeking in, so use every kind of element. When several belong to the same reel, the
        // biggest one wins (see the de-duplication below).
        val maxH = (resources.displayMetrics.heightPixels * 98) / 100
        val all = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        all.addAll(pagers)
        all.addAll(medias)
        all.addAll(videos)
        all.addAll(descs)
        val candidates = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        for (p in all) {
            val r = p.second
            if (r.height() >= dp(40) && r.height() < maxH && r.width() >= dp(120)) candidates.add(p)
        }
        candidates.sortByDescending { it.second.width() * it.second.height() }
        val kept = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        for (c in candidates) {
            if (kept.none { it.second.contains(c.second) }) kept.add(c)
        }

        if (kept.isNotEmpty()) {
            regions.clear()
            for (k in kept) regions.add(Region(k.first, k.second, now))
            applyRegions()
            ensureTicking()
        } else {
            // Nothing found this time: keep what we had for a moment instead of flickering.
            trackRegions(now)
        }
    }

    private fun trackRegions(now: Long) {
        for (r in regions) {
            val alive = try {
                r.node.refresh()
            } catch (e: Exception) {
                false
            }
            if (alive) {
                r.rect = boundsOf(r.node)
                r.seenAt = now
            }
        }
        regions.removeAll { now - it.seenAt > REGION_GRACE_MS }
        applyRegions()
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

    private fun applyRegions() {
        // A little extra above and below hides the small lag while the feed scrolls.
        val rects = regions.mapNotNull { r ->
            clamp(Rect(r.rect).also { it.inset(0, -dp(24)) })
        }
        if (rects == lastApplied) return
        lastApplied = rects

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
        regions.clear()
        if (regionViews.isNotEmpty() || lastApplied.isNotEmpty()) applyRegions()
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

    // ---- Temporary technical strip --------------------------------------------------------

    private fun showBadge(text: String) {
        val existing = badge
        if (existing != null) {
            existing.text = text
            return
        }
        val tv = TextView(this)
        tv.setBackgroundColor(Color.parseColor("#B3000000"))
        tv.setTextColor(Color.parseColor("#9AE6B4"))
        tv.textSize = 9f
        tv.setPadding(dp(6), dp(2), dp(6), dp(2))
        tv.text = text
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(tv, params)
            badge = tv
        } catch (e: Exception) {
            badge = null
        }
    }

    private fun hideBadge() {
        val tv = badge ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(tv)
        } catch (e: Exception) {
            // already gone
        }
        badge = null
    }

    private fun collectDebug(root: AccessibilityNodeInfo): String {
        val ids = LinkedHashSet<String>()
        val descs = ArrayList<String>()
        walkDebug(root, 0, ids, descs)
        return "ids=" + ids.take(14).joinToString(",") + "\nd=" + descs.joinToString(" | ")
    }

    private fun walkDebug(
        n: AccessibilityNodeInfo?,
        depth: Int,
        ids: MutableSet<String>,
        descs: MutableList<String>
    ) {
        if (n == null || depth > 40) return
        val id = n.viewIdResourceName?.substringAfter(":id/")
        if (id != null && (id.contains("clips") || id.contains("reel") || id.contains("video"))) {
            ids.add(id)
        }
        val desc = n.contentDescription?.toString()
        if (desc != null && descs.size < 3 && (desc.startsWith("Reel") || desc.startsWith("Video"))) {
            descs.add(desc.take(28))
        }
        for (i in 0 until n.childCount) {
            walkDebug(n.getChild(i), depth + 1, ids, descs)
        }
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

    private fun removeEverything() {
        handler.removeCallbacksAndMessages(null)
        ticking = false
        hideOverlay()
        clearRegions()
        hideBadge()
    }

    override fun onInterrupt() {
        removeEverything()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        removeEverything()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeEverything()
        super.onDestroy()
    }
}
