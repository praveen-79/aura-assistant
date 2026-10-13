package com.aura.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Eyes and hands of the Aura assistant: reads the screen and performs UI actions on request.
 *
 * Requirements: minSdk 24 (canPerformGestures), kotlinx-coroutines-core.
 *
 * Threading
 * - [onAccessibilityEvent] runs on the main thread and only updates cheap state.
 * - Every helper that touches the node tree makes Binder calls into the target app and can block.
 *   Call them from a background dispatcher (e.g. Dispatchers.Default), never from the main thread.
 *
 * Node ownership
 * - Nodes returned by [findNodeByText] and [findSearchControl] belong to the caller, who should
 *   recycle them when done (a no-op on API 33+, required to avoid pool leaks before that).
 * - [performClick] and [performSetText] never recycle the node passed in. [performClick] recycles
 *   only the ancestors it fetches itself.
 *
 * Privacy: password fields are never read or typed into, and screen content is never logged.
 */
class AuraAccessibilityService : AccessibilityService() {

    /** Package of the last non-transient window that came to the foreground (IME and SystemUI excluded). */
    @Volatile
    var activePackageName: String? = null
        private set

    /** Class name reported by the last foreground window change (usually an Activity or Dialog). */
    @Volatile
    var activeClassName: String? = null
        private set

    private data class UiChange(val packageName: String?, val eventType: Int)

    private val uiChanges = MutableSharedFlow<UiChange>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val imePackages: Set<String> by lazy {
        runCatching {
            getSystemService(InputMethodManager::class.java)
                ?.enabledInputMethodList
                ?.map { it.packageName }
                ?.toSet()
        }.getOrNull().orEmpty()
    }

    // ---------------------------------------------------------------------------------------
    // Service lifecycle
    // ---------------------------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        rootInActiveWindow?.let { root ->
            root.packageName?.toString()
                ?.takeIf { !isTransientPackage(it) }
                ?.let { activePackageName = it }
            root.recycleCompat()
        }
        Log.i(TAG, "Aura accessibility service connected")
    }

    override fun onInterrupt() {
        Log.i(TAG, "Aura accessibility service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg != null && !isTransientPackage(pkg)) {
                    activePackageName = pkg
                    activeClassName = event.className?.toString()
                }
                uiChanges.tryEmit(UiChange(pkg, event.eventType))
            }

            AccessibilityEvent.TYPE_WINDOWS_CHANGED ->
                uiChanges.tryEmit(UiChange(pkg, event.eventType))

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Only structural changes count as a "layout change"; text/progress ticks are noise.
                val structural =
                    (event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE) != 0
                if (structural && (pkg == null || !isTransientPackage(pkg))) {
                    uiChanges.tryEmit(UiChange(pkg, event.eventType))
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Public helpers for Aura actions
    // ---------------------------------------------------------------------------------------

    /**
     * Finds a visible node whose text or contentDescription matches [text] (case-insensitive).
     *
     * @param exact `true` for whole-string equality, `false` for "contains".
     * @return a node the caller must recycle, or `null` if nothing matches.
     */
    fun findNodeByText(text: String, exact: Boolean = false): AccessibilityNodeInfo? {
        val query = text.trim()
        if (query.isEmpty()) return null
        val root = rootInActiveWindow ?: return null
        try {
            // Fast path: the target app searches its own tree in a single Binder call.
            // Some UI toolkits (notably Compose) don't implement it, hence the fallback below.
            pickBestTextMatch(root, query, exact)?.let { return it }

            var found: AccessibilityNodeInfo? = null
            walkTree(root) { node, _, state ->
                if (!node.isPassword && node.matchesText(query, exact)) {
                    found = node
                    state.stop()
                    true
                } else {
                    false
                }
            }
            return found
        } finally {
            root.recycleCompat()
        }
    }

    /**
     * Locates the most likely search control on screen, best candidate first:
     * 1. an editable field that also looks like search (hint/description/view id/text),
     * 2. a clickable search affordance (icon or bar),
     * 3. any editable field,
     * 4. any other node that looks like search.
     *
     * "Looks like search" means containing "search", "search query" or Telugu "వెతకండి".
     *
     * @return a node the caller must recycle, or `null` if there is none.
     */
    fun findSearchControl(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        try {
            walkTree(root) { node, _, state ->
                val score = node.searchScore()
                if (score > bestScore) {
                    best?.recycleCompat()
                    best = node
                    bestScore = score
                    if (score >= SCORE_EDITABLE_SEARCH) state.stop()
                    true
                } else {
                    false
                }
            }
        } finally {
            root.recycleCompat()
        }
        return best
    }

    /**
     * Clicks [node] with ACTION_CLICK, or its nearest clickable ancestor if the node itself
     * is not clickable. Does not recycle [node]; recycles the ancestors it fetched.
     *
     * @return `true` if a click action was accepted.
     */
    fun performClick(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        if (node.isClickable && node.isEnabled &&
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        ) {
            return true
        }

        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < MAX_PARENT_HOPS) {
            if (current.isClickable && current.isEnabled) {
                val accepted = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                current.recycleCompat()
                return accepted
            }
            val next = current.parent
            current.recycleCompat()
            current = next
            depth++
        }
        current?.recycleCompat()
        return false
    }

    /**
     * Focuses an editable [node] and replaces its content with [text] via ACTION_SET_TEXT.
     * Refuses password fields and non-editable nodes. Does not recycle [node].
     *
     * @return `true` if the set-text action was accepted.
     */
    fun performSetText(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null || node.isPassword || !node.isEditText()) return false

        if (!node.isFocused && !node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            // Some fields only take focus from a click.
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Returns a short spoken summary of what is on screen: the app name (when resolvable)
     * plus the first few visible labels. Empty and password nodes are ignored.
     */
    fun readScreenSummary(): String {
        val root = rootInActiveWindow ?: return SUMMARY_UNAVAILABLE
        val rootPackage = root.packageName?.toString()
        val labels = LinkedHashSet<String>()

        try {
            walkTree(root) { node, _, state ->
                node.visibleLabel()
                    ?.collapseWhitespace()
                    ?.take(MAX_LABEL_CHARS)
                    ?.takeIf { label -> label.length >= 2 && label.any { c -> c.isLetterOrDigit() } }
                    ?.let { labels.add(it) }
                if (labels.size >= MAX_COLLECTED_LABELS) state.stop()
                false
            }
        } finally {
            root.recycleCompat()
        }

        val appName = appLabelFor(activePackageName ?: rootPackage)
        val appSentence = appName?.let { "You're in $it. " }.orEmpty()

        if (labels.isEmpty()) return appSentence + SUMMARY_NO_TEXT

        val shown = labels.take(SUMMARY_LABEL_LIMIT)
        val more = if (labels.size > shown.size) ", and more" else ""
        return "${appSentence}I can see: ${shown.joinToString(", ")}$more."
    }

    /**
     * Title of the first clickable item in the main list on screen (RecyclerView, ListView,
     * lazy list, ...). The main list is the biggest collection on screen, which skips chip rows
     * and carousels. Returns `null` if no list or no readable title is found.
     * Plain web content (no list container) is not handled.
     */
    fun readFirstSearchResult(): String? {
        val root = rootInActiveWindow ?: return null
        try {
            val list = findLargestListContainer(root) ?: return null
            try {
                return firstResultTitle(list)
            } finally {
                list.recycleCompat()
            }
        } finally {
            root.recycleCompat()
        }
    }

    /**
     * Text of the most recent incoming message visible in a chat screen.
     *
     * Known message-text view ids (WhatsApp, Google Messages) are preferred; otherwise plain text
     * inside the main list is used. "Incoming" means the bubble sits closer to the left screen
     * edge, so this assumes a left-to-right chat layout. View ids and layouts change between app
     * versions; verify against the versions you target.
     */
    fun readLatestMessage(): String? {
        val root = rootInActiveWindow ?: return null
        val screen = Rect()
        root.getBoundsInScreen(screen)
        val messages = ArrayList<Pair<Rect, String>>()

        try {
            walkTree(root) { node, _, _ ->
                if (node.hasIdSuffix(MESSAGE_TEXT_ID_SUFFIXES)) collectMessage(node, messages)
                false
            }

            if (messages.isEmpty()) {
                val list = findLargestListContainer(root)
                if (list != null) {
                    try {
                        walkTree(list) { node, _, _ ->
                            if (node.isPlainMessageText()) collectMessage(node, messages)
                            false
                        }
                    } finally {
                        list.recycleCompat()
                    }
                }
            }
        } finally {
            root.recycleCompat()
        }

        return messages
            .filter { (bounds, _) -> bounds.isIncomingIn(screen) }
            .maxByOrNull { (bounds, _) -> bounds.bottom }
            ?.second
    }

    /**
     * Suspends until the UI changes, or [timeoutMs] elapses.
     *
     * - [targetPackage] != null: returns `true` as soon as that package is the foreground app
     *   (immediately if it already is) or one of its windows changes.
     * - [targetPackage] == null: returns `true` on the next window or structural layout change.
     *
     * Only events that arrive after the call starts are seen, so call it right after triggering
     * the action. Cancellation-safe.
     *
     * @return `true` if the change was observed, `false` on timeout.
     */
    suspend fun waitForWindowChange(targetPackage: String?, timeoutMs: Long = 4000L): Boolean {
        if (targetPackage != null && activePackageName == targetPackage) return true
        return withTimeoutOrNull(timeoutMs) {
            uiChanges.first { change -> targetPackage == null || change.packageName == targetPackage }
            true
        } ?: false
    }

    // ---------------------------------------------------------------------------------------
    // Internals: tree walking
    // ---------------------------------------------------------------------------------------

    private class TreeWalk(val maxNodes: Int) {
        var visited = 0
        var stopped = false
        fun stop() {
            stopped = true
        }
    }

    /**
     * Pre-order walk over the descendants of [root] (the root itself is not visited).
     *
     * [visitor] receives each visible node and returns `true` if it retained the node, in which
     * case the walker will not recycle it and the visitor's owner must. Invisible subtrees are
     * skipped. The walk is bounded by [maxNodes] and [MAX_DEPTH].
     */
    private fun walkTree(
        root: AccessibilityNodeInfo,
        maxNodes: Int = MAX_NODES,
        visitor: (node: AccessibilityNodeInfo, depth: Int, walk: TreeWalk) -> Boolean
    ) {
        walkChildren(root, 0, TreeWalk(maxNodes), visitor)
    }

    private fun walkChildren(
        parent: AccessibilityNodeInfo,
        depth: Int,
        state: TreeWalk,
        visitor: (AccessibilityNodeInfo, Int, TreeWalk) -> Boolean
    ) {
        if (depth >= MAX_DEPTH) return
        val count = parent.childCount
        for (i in 0 until count) {
            if (state.stopped || state.visited >= state.maxNodes) return
            val child = parent.getChild(i) ?: continue
            state.visited++
            if (!child.isVisibleToUser) {
                child.recycleCompat()
                continue
            }
            var retained = false
            try {
                retained = visitor(child, depth + 1, state)
                if (!state.stopped) walkChildren(child, depth + 1, state, visitor)
            } finally {
                if (!retained) child.recycleCompat()
            }
        }
    }

    private fun pickBestTextMatch(
        root: AccessibilityNodeInfo,
        query: String,
        exact: Boolean
    ): AccessibilityNodeInfo? {
        val candidates = root.findAccessibilityNodeInfosByText(query).orEmpty()
        var best: AccessibilityNodeInfo? = null
        for (candidate in candidates) {
            if (best == null &&
                candidate.isVisibleToUser &&
                !candidate.isPassword &&
                candidate.matchesText(query, exact)
            ) {
                best = candidate
            } else {
                candidate.recycleCompat()
            }
        }
        return best
    }

    /** Largest visible collection (or, failing that, scrollable) container. Caller recycles it. */
    private fun findLargestListContainer(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val all = ArrayList<AccessibilityNodeInfo>()
        walkTree(root) { node, _, _ ->
            if (node.isListContainer()) {
                all.add(node)
                true
            } else {
                false
            }
        }
        val preferred = all.filter { it.isCollection() }.ifEmpty { all }
        val best = preferred.maxByOrNull { it.screenArea() }
        all.forEach { if (it !== best) it.recycleCompat() }
        return best
    }

    private fun firstResultTitle(list: AccessibilityNodeInfo): String? {
        val count = minOf(list.childCount, MAX_RESULT_ITEMS_SCANNED)
        for (i in 0 until count) {
            val item = list.getChild(i) ?: continue
            try {
                if (!item.isVisibleToUser) continue
                if (!item.isClickable && !item.hasClickableDescendant()) continue
                extractTitle(item)?.let { return it }
            } finally {
                item.recycleCompat()
            }
        }
        return null
    }

    private fun extractTitle(item: AccessibilityNodeInfo): String? {
        var title: String? = null
        walkTree(item, maxNodes = TITLE_SCAN_NODES) { node, _, state ->
            val label = node.text?.toString()?.collapseWhitespace()
            if (!node.isPassword && label != null && label.isMeaningfulTitle()) {
                title = label
                state.stop()
            }
            false
        }
        if (title != null) return title
        // Some apps merge the whole card into one contentDescription and expose no child text.
        return item.visibleLabel()?.collapseWhitespace()?.takeIf { it.isMeaningfulTitle() }
    }

    private fun AccessibilityNodeInfo.hasClickableDescendant(): Boolean {
        var found = false
        walkTree(this, maxNodes = CLICKABLE_SCAN_NODES) { node, _, state ->
            if (node.isClickable) {
                found = true
                state.stop()
            }
            false
        }
        return found
    }

    private fun collectMessage(node: AccessibilityNodeInfo, into: MutableList<Pair<Rect, String>>) {
        val label = node.text?.toString()?.trim()
        if (label.isNullOrEmpty() || node.isPassword) return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        into.add(bounds to label)
    }

    // ---------------------------------------------------------------------------------------
    // Internals: node predicates
    // ---------------------------------------------------------------------------------------

    private fun AccessibilityNodeInfo.matchesText(query: String, exact: Boolean): Boolean {
        val candidates = sequenceOf(text?.toString(), contentDescription?.toString())
        return candidates.filterNotNull().any { value ->
            if (exact) value.trim().equals(query, ignoreCase = true)
            else value.contains(query, ignoreCase = true)
        }
    }

    private fun AccessibilityNodeInfo.visibleLabel(): String? {
        if (isPassword) return null
        text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun AccessibilityNodeInfo.isEditText(): Boolean =
        isEditable || className?.contains("EditText") == true

    private fun AccessibilityNodeInfo.hintTextCompat(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) hintText?.toString() else null

    private fun AccessibilityNodeInfo.looksLikeSearch(): Boolean {
        val signals = sequenceOf(
            hintTextCompat(),
            contentDescription?.toString(),
            // Strip the package: "com.google.android.googlequicksearchbox" contains "search".
            viewIdResourceName?.substringAfter(":id/"),
            if (isEditable || isClickable) text?.toString() else null
        )
        return signals.filterNotNull().any { signal ->
            SEARCH_KEYWORDS.any { keyword -> signal.contains(keyword, ignoreCase = true) }
        }
    }

    private fun AccessibilityNodeInfo.searchScore(): Int {
        if (isPassword) return 0
        val editable = isEditText()
        val searchy = looksLikeSearch()
        return when {
            editable && searchy -> SCORE_EDITABLE_SEARCH
            searchy && isClickable -> SCORE_CLICKABLE_SEARCH
            editable -> SCORE_PLAIN_EDITABLE
            searchy -> SCORE_OTHER_SEARCH
            else -> 0
        }
    }

    private fun AccessibilityNodeInfo.isCollection(): Boolean {
        val name = className?.toString().orEmpty()
        return collectionInfo != null ||
            name.endsWith("RecyclerView") ||
            name.endsWith("ListView") ||
            name.endsWith("GridView")
    }

    private fun AccessibilityNodeInfo.isListContainer(): Boolean = isCollection() || isScrollable

    private fun AccessibilityNodeInfo.screenArea(): Long {
        val bounds = Rect()
        getBoundsInScreen(bounds)
        return bounds.width().toLong() * bounds.height().toLong()
    }

    private fun AccessibilityNodeInfo.hasIdSuffix(suffixes: List<String>): Boolean {
        val id = viewIdResourceName ?: return false
        return suffixes.any { id.endsWith(it) }
    }

    private fun AccessibilityNodeInfo.isPlainMessageText(): Boolean {
        val label = text?.toString()?.trim()
        return !label.isNullOrEmpty() &&
            !isPassword &&
            !isEditable &&
            className?.contains("Button") != true &&
            !TIME_LIKE.matches(label)
    }

    private fun Rect.isIncomingIn(screen: Rect): Boolean {
        val leftGap = left - screen.left
        val rightGap = screen.right - right
        // Centered items (date separators, system notices) are neither incoming nor outgoing.
        return rightGap - leftGap > screen.width() * INCOMING_BIAS_RATIO
    }

    private fun String.collapseWhitespace(): String = replace(WHITESPACE, " ").trim()

    private fun String.isMeaningfulTitle(): Boolean {
        if (length < 3 || none { it.isLetter() }) return false
        return NON_TITLE_LABELS.none { equals(it, ignoreCase = true) }
    }

    // ---------------------------------------------------------------------------------------
    // Internals: misc
    // ---------------------------------------------------------------------------------------

    private fun isTransientPackage(pkg: String): Boolean =
        pkg == SYSTEM_UI_PACKAGE || pkg in imePackages

    private fun appLabelFor(pkg: String?): String? {
        if (pkg == null) return null
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(pkg, 0)
            }
            packageManager.getApplicationLabel(info).toString().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            // Typically NameNotFoundException: not visible without a <queries> entry on Android 11+.
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.recycleCompat() {
        // No-op on API 33+, required on older releases. Tolerate double recycling.
        try {
            recycle()
        } catch (e: IllegalStateException) {
            // Already recycled.
        }
    }

    companion object {
        private const val TAG = "AuraAccessibility"

        /** Live service instance, or `null` while the service is not connected. */
        @Volatile
        var instance: AuraAccessibilityService? = null
            private set

        fun isServiceActive(): Boolean = instance != null

        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        // Tree-walk bounds (every visited node costs a Binder call).
        private const val MAX_NODES = 1500
        private const val MAX_DEPTH = 60
        private const val MAX_PARENT_HOPS = 8
        private const val MAX_RESULT_ITEMS_SCANNED = 8
        private const val TITLE_SCAN_NODES = 40
        private const val CLICKABLE_SCAN_NODES = 60

        // Screen summary.
        private const val MAX_LABEL_CHARS = 80
        private const val MAX_COLLECTED_LABELS = 30
        private const val SUMMARY_LABEL_LIMIT = 5
        private const val SUMMARY_UNAVAILABLE = "I can't read the screen right now."
        private const val SUMMARY_NO_TEXT = "I couldn't find any readable text on this screen."

        // Search control ranking.
        private const val SCORE_EDITABLE_SEARCH = 100
        private const val SCORE_CLICKABLE_SEARCH = 60
        private const val SCORE_PLAIN_EDITABLE = 40
        private const val SCORE_OTHER_SEARCH = 20

        private val SEARCH_KEYWORDS = listOf("search query", "search", "వెతకండి")

        // Chat reading.
        private val MESSAGE_TEXT_ID_SUFFIXES = listOf(":id/message_text")
        private const val INCOMING_BIAS_RATIO = 0.05f
        private val TIME_LIKE = Regex("""^\d{1,2}[:.]\d{2}\s?([AaPp]\.?[Mm]\.?)?$""")

        private val NON_TITLE_LABELS = setOf("sponsored", "promoted", "advertisement")
        private val WHITESPACE = Regex("\\s+")
    }
}
