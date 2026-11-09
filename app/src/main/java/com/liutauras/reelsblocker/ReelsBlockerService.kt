package com.liutauras.reelsblocker

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Watches the Instagram app only (see accessibility_service_config.xml).
 *
 * Rules:
 *  1. Reels tab selected            -> go back to the home tab.
 *  2. A full-screen reel viewer opened from a chat (DM) -> allowed.
 *  3. A full-screen reel viewer opened from anywhere else (Explore, profile, ...) -> blocked.
 *  4. While watching a chat reel, if the reel changes (swipe) -> go back to the chat.
 *
 * Instagram does not publish how its screens are built, so this works by looking at
 * view IDs and labels. Those can change in Instagram updates, then this file needs a patch.
 */
class ReelsBlockerService : AccessibilityService() {

    private val igPackage = "com.instagram.android"

    private var dmContext = false          // last "main" screen we saw was a chat
    private var inAllowedReel = false      // currently watching a reel opened from a chat
    private var reelStartedAt = 0L
    private var reelAuthor: String? = null

    private var lastScanAt = 0L
    private var lastActionAt = 0L

    private class Snapshot {
        val ids = HashSet<String>()
        var reelsTabSelected = false
        var author: String? = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() != igPackage) return

        val now = System.currentTimeMillis()
        val isScroll = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        if (!isScroll && now - lastScanAt < 200) return
        lastScanAt = now

        val root = rootInActiveWindow ?: return
        val snap = Snapshot()
        scan(root, snap, 0)
        val ids = snap.ids

        val tabBarVisible = "feed_tab" in ids || "clips_tab" in ids
        val chatOpen = "row_thread_composer_edittext" in ids
        val reelViewerOpen = ids.any {
            it.startsWith("clips_viewer") ||
                it == "clips_video_container" ||
                it == "clips_author_username"
        }

        if (tabBarVisible) dmContext = false
        if (chatOpen) dmContext = true

        // Rule 1: Reels tab
        if (snap.reelsTabSelected) {
            block(root)
            return
        }

        if (reelViewerOpen) {
            if (!inAllowedReel) {
                if (dmContext && !tabBarVisible) {
                    // Rule 2: opened from a chat
                    inAllowedReel = true
                    reelStartedAt = now
                    reelAuthor = snap.author
                } else {
                    // Rule 3: opened from somewhere else
                    block(root)
                }
            } else if (now - reelStartedAt > 1200) {
                // Rule 4: swiped to another reel
                val authorChanged = reelAuthor != null && snap.author != null && snap.author != reelAuthor
                val pagerScrolled = isScroll && isReelPager(event)
                if (authorChanged || pagerScrolled) {
                    block(root)
                } else if (reelAuthor == null && snap.author != null) {
                    reelAuthor = snap.author
                }
            }
        } else {
            inAllowedReel = false
            reelAuthor = null
        }
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
            if (id == "clips_author_username") snap.author = node.text?.toString()
        } else if (node.isSelected && node.contentDescription?.toString()?.startsWith("Reels") == true) {
            snap.reelsTabSelected = true
        }

        for (i in 0 until node.childCount) {
            scan(node.getChild(i), snap, depth + 1)
        }
    }

    private fun block(root: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        if (now - lastActionAt < 800) return
        lastActionAt = now
        inAllowedReel = false
        reelAuthor = null

        val home = root.findAccessibilityNodeInfosByViewId("$igPackage:id/feed_tab").firstOrNull()
        if (home != null && home.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    override fun onInterrupt() {}
}
