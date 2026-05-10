package com.error698.swipetracker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.error698.swipetracker.data.SwipeApp
import com.error698.swipetracker.data.SwipeRepository

/**
 * SwipeAccessibilityService
 *
 * Detects right swipes (likes) in Bumble using multiple strategies:
 *
 * 1. BUTTON CLICK — TYPE_VIEW_CLICKED on nodes containing like/heart/yes keywords.
 * 2. ANNOUNCEMENT — TYPE_ANNOUNCEMENT events apps fire for screen readers
 *    (e.g. "Liked", "Like sent").
 * 3. CONTENT CHANGE — TYPE_WINDOW_CONTENT_CHANGED: when the profile card changes,
 *    scan the tree for right-swipe confirmation elements (Bumble's "liked" overlay).
 *    If a positive indicator is found, count it.
 * 4. VIEW SCROLLED — TYPE_VIEW_SCROLLED on the card stack can indicate a swipe gesture.
 *
 * Scoped to com.bumble.app only (via XML config).
 * 2000ms debounce prevents double-counting.
 */
class SwipeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SwipeTracker"

        private const val DEBOUNCE_MS = 500L
        private const val FALLBACK_RESET_MS = 5000L

        // Keywords indicating a "like" / right-swipe action (case-insensitive)
        private val LIKE_KEYWORDS = listOf(
            "like", "liked", "yes", "heart", "interested",
            "match", "superswipe", "super like",
            "sent", "vote yes", "vote_yes", "action_like",
            "like_button", "btn_like", "btn_yes", "btn_vote_yes"
        )

        // Keywords indicating a "pass/nope" action — EXCLUDE these
        private val PASS_KEYWORDS = listOf(
            "nope", "pass", "skip", "dismiss", "not interested",
            "remove", "block", "report", "unmatch", "vote no",
            "vote_no", "btn_nope", "btn_no", "btn_pass"
        )

        // Positive confirmation keywords that appear AFTER a right swipe
        // (in toast/overlay/animation elements)
        private val SWIPE_CONFIRM_KEYWORDS = listOf(
            "liked", "like sent", "match",
            "it's a match", "you liked", "sent a like",
            "super like sent", "superswipe sent"
        )

        var lastProfileHash: Int = 0
        var lastSwipeTime: Long = 0L
        // Track whether we recently saw a like-confirmation indicator
        private var lastConfirmationTime: Long = 0L
        // Prevent double-counting the SAME profile
        private var hasSwipedCurrentProfile: Boolean = false

        const val ACTION_SWIPE_DETECTED = "com.error698.swipetracker.SWIPE_DETECTED"
        const val EXTRA_APP_NAME = "app_name"
        const val EXTRA_NEW_TOTAL = "new_total"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_ANNOUNCEMENT
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        val app = SwipeApp.fromPackage(pkg) ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleClick(event, app)
            AccessibilityEvent.TYPE_ANNOUNCEMENT -> handleAnnouncement(event, app)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> handleContentChange(event, app)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> handleScroll(event, app)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleStateChange(event, app)
        }
    }

    // ─── Strategy 1: Direct click on Like/Heart button ───

    private fun handleClick(event: AccessibilityEvent, app: SwipeApp) {
        val source = event.source ?: return

        val desc = source.contentDescription?.toString()?.lowercase() ?: ""
        val text = source.text?.toString()?.lowercase() ?: ""
        val viewId = source.viewIdResourceName?.lowercase() ?: ""
        val className = source.className?.toString()?.lowercase() ?: ""
        val combined = "$desc $text $viewId $className"

        // Exclude pass actions
        if (PASS_KEYWORDS.any { combined.contains(it) }) {
            source.recycle()
            return
        }

        // Check for like action
        if (LIKE_KEYWORDS.any { combined.contains(it) }) {
            Log.d(TAG, "Like button clicked in ${app.displayName}: '$combined'")
            recordSwipeDebounced(app)
            source.recycle()
            return
        }

        // Also check parent nodes — the button itself may not have the keyword
        // but its parent container might
        val parent = source.parent
        if (parent != null) {
            val parentDesc = parent.contentDescription?.toString()?.lowercase() ?: ""
            val parentId = parent.viewIdResourceName?.lowercase() ?: ""
            val parentCombined = "$parentDesc $parentId"
            if (LIKE_KEYWORDS.any { parentCombined.contains(it) }) {
                Log.d(TAG, "Like parent clicked in ${app.displayName}: '$parentCombined'")
                recordSwipeDebounced(app)
                parent.recycle()
                source.recycle()
                return
            }
            parent.recycle()
        }

        source.recycle()
    }

    // ─── Strategy 2: Announcement events (screen reader) ───

    /**
     * Many apps fire TYPE_ANNOUNCEMENT for screen readers when an action
     * completes, e.g. "Liked", "Like sent", "Rose sent".
     */
    private fun handleAnnouncement(event: AccessibilityEvent, app: SwipeApp) {
        val text = event.text?.joinToString(" ")?.lowercase() ?: ""
        val desc = event.contentDescription?.toString()?.lowercase() ?: ""
        val combined = "$text $desc"

        if (combined.isBlank()) return

        // Check for positive like confirmations
        if (SWIPE_CONFIRM_KEYWORDS.any { combined.contains(it) }) {
            Log.d(TAG, "Like announcement in ${app.displayName}: '$combined'")
            recordSwipeDebounced(app)
            return
        }

        // Also check generic like keywords
        if (LIKE_KEYWORDS.any { combined.contains(it) }) {
            if (PASS_KEYWORDS.none { combined.contains(it) }) {
                Log.d(TAG, "Like-related announcement in ${app.displayName}: '$combined'")
                recordSwipeDebounced(app)
            }
        }
    }

    // ─── Strategy 3: Content change → scan for right-swipe indicators ───

    /**
     * When the user gesture-swipes right, Bumble/Hinge briefly show a
     * confirmation element (animation overlay, "liked" text, etc.) before
     * loading the next profile. We scan the tree for these indicators.
     *
     * Also detects profile card transitions: if the visible profile name/age
     * changes, we check if a like-confirmation element is present in the tree.
     */
    private fun handleContentChange(event: AccessibilityEvent, app: SwipeApp) {
        val root = rootInActiveWindow ?: return

        // First, scan the tree for right-swipe confirmation indicators
        val foundConfirmation = scanForLikeConfirmation(root)

        if (foundConfirmation) {
            val now = System.currentTimeMillis()
            // Only record if we haven't already recorded this swipe
            if (now - lastConfirmationTime > DEBOUNCE_MS) {
                lastConfirmationTime = now
                Log.d(TAG, "Like confirmation found in ${app.displayName} UI tree")
                recordSwipeDebounced(app)
            }
            root.recycle()
            return
        }

        // Secondary: track profile changes
        val profileText = extractProfileText(root)
        root.recycle()

        // If we can't find any text, we don't update the hash, 
        // but the fallback reset below will handle it.
        if (profileText.isBlank()) return

        val hash = profileText.hashCode()
        if (hash != lastProfileHash) {
            Log.d(TAG, "Profile changed - resetting swipe flag")
            lastProfileHash = hash
            hasSwipedCurrentProfile = false 
        }
    }

    /**
     * Scan the accessibility tree for elements that confirm a right swipe
     * just happened. These are transient elements like "Liked!" overlays,
     * match animations, sparkle effects with content descriptions, etc.
     */
    private fun scanForLikeConfirmation(root: AccessibilityNodeInfo): Boolean {
        return scanNodeForConfirmation(root, depth = 0, maxDepth = 12)
    }

    private fun scanNodeForConfirmation(
        node: AccessibilityNodeInfo,
        depth: Int,
        maxDepth: Int
    ): Boolean {
        if (depth > maxDepth) return false

        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val combined = "$text $desc $viewId"

        // Check for confirmation keywords
        if (combined.isNotBlank() && SWIPE_CONFIRM_KEYWORDS.any { combined.contains(it) }) {
            Log.d(TAG, "Confirmation element found: '$combined'")
            return true
        }

        // Check for specific Bumble confirmation view IDs
        if (viewId.contains("like_indicator") || viewId.contains("match_animation") ||
            viewId.contains("liked_overlay") || viewId.contains("action_feedback") ||
            viewId.contains("swipe_indicator")) {
            Log.d(TAG, "Confirmation view ID found: '$viewId'")
            return true
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (scanNodeForConfirmation(child, depth + 1, maxDepth)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    /**
     * Extract text from the view tree that identifies the current profile.
     */
    private fun extractProfileText(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        traverseForText(root, sb, depth = 0, maxDepth = 20) // Scans deeper
        return sb.toString()
    }

    private fun traverseForText(
        node: AccessibilityNodeInfo,
        sb: StringBuilder,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth > maxDepth) return
        node.text?.let { sb.append(it).append("|") }
        node.contentDescription?.let { sb.append(it).append("|") }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseForText(child, sb, depth + 1, maxDepth)
            child.recycle()
        }
    }

    // ─── Strategy 4: Scroll events on card stack ───

    /**
     * Gesture-based swipes on the card stack generate TYPE_VIEW_SCROLLED events.
     * We can check the scroll source for like-related identifiers.
     */
    private fun handleScroll(event: AccessibilityEvent, app: SwipeApp) {
        val source = event.source ?: return
        val viewId = source.viewIdResourceName?.lowercase() ?: ""
        val className = source.className?.toString()?.lowercase() ?: ""

        // Log scroll events for debugging — these help identify the
        // card stack container view IDs for future refinement
        Log.d(TAG, "Scroll in ${app.displayName}: viewId='$viewId' class='$className'")

        source.recycle()
    }

    // ─── Strategy 5: Window state change ───

    private fun handleStateChange(event: AccessibilityEvent, app: SwipeApp) {
        val className = event.className?.toString() ?: ""
        val text = event.text?.joinToString(" ")?.lowercase() ?: ""
        val desc = event.contentDescription?.toString()?.lowercase() ?: ""

        Log.d(TAG, "Window state changed in ${app.displayName}: $className text='$text' desc='$desc'")

        // Check if the state change itself indicates a like action
        val combined = "$text $desc"
        if (SWIPE_CONFIRM_KEYWORDS.any { combined.contains(it) }) {
            Log.d(TAG, "Like detected via window state in ${app.displayName}")
            recordSwipeDebounced(app)
        }

        // Reset profile hash on screen navigation
        lastProfileHash = 0
    }

    // ─── Recording with debounce ───

    private fun recordSwipeDebounced(app: SwipeApp) {
        val now = System.currentTimeMillis()
        
        // 0. Fallback reset: if it's been > 5s, we must have moved on
        if (hasSwipedCurrentProfile && (now - lastSwipeTime > FALLBACK_RESET_MS)) {
            Log.d(TAG, "Fallback: resetting swipe flag due to timeout")
            hasSwipedCurrentProfile = false
        }

        // 1. Time-based debounce (safety fallback)
        if (now - lastSwipeTime < DEBOUNCE_MS) {
            return
        }

        // 2. Profile-based debounce (prevents multiple counts for same person)
        if (hasSwipedCurrentProfile) {
            return
        }

        lastSwipeTime = now
        hasSwipedCurrentProfile = true // Mark this profile as counted

        val ctx = applicationContext
        val newTotal = SwipeRepository.recordSwipe(ctx, app)
        Log.d(TAG, "Right swipe recorded for ${app.displayName}! Total: $newTotal")

        val intent = Intent(ACTION_SWIPE_DETECTED).apply {
            putExtra(EXTRA_APP_NAME, app.name)
            putExtra(EXTRA_NEW_TOTAL, newTotal)
            setPackage(ctx.packageName)
        }
        sendBroadcast(intent)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Accessibility service destroyed")
    }
}
