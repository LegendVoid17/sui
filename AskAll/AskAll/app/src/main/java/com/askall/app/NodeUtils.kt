package com.askall.app

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Helpers for reading another app's screen through the accessibility tree.
 * Everything here is heuristic: AI apps don't publish stable ids, so we look for
 * "the editable box nearest the bottom", "a button labelled Send", and so on.
 */
object NodeUtils {
    private val SEND_LABEL = Regex(
        "^(send|submit|send message|send prompt|send now|submit prompt)\\b.*",
        RegexOption.IGNORE_CASE,
    )
    private val NOT_A_SEND_LABEL = Regex("feedback|invite|gift|report", RegexOption.IGNORE_CASE)
    private val NOT_SEND_GUESS = Regex(
        "voice|mic|dictat|speak|attach|camera|photo|image|upload|file|plus|add|more|menu|tools|" +
            "search|web|think|model|record|audio|talk|live|history|new chat|close|clear|feedback",
        RegexOption.IGNORE_CASE,
    )
    private val STOP_LABEL = Regex("^stop\\b.{0,20}$", RegexOption.IGNORE_CASE)

    fun rect(n: AccessibilityNodeInfo): Rect = Rect().also { n.getBoundsInScreen(it) }

    fun walk(node: AccessibilityNodeInfo?, depth: Int = 0, visit: (AccessibilityNodeInfo) -> Unit) {
        if (node == null || depth > 80) return
        visit(node)
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), depth + 1, visit)
        }
    }

    /** The message box: the lowest visible, enabled, editable field on screen. */
    fun findInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = ArrayList<AccessibilityNodeInfo>()
        walk(root) { n ->
            if (n.isEditable && n.isVisibleToUser && n.isEnabled && !n.isPassword) candidates.add(n)
        }
        return candidates.maxByOrNull { rect(it).bottom }
    }

    /** All visible text on screen in reading order, ignoring the message box itself. */
    fun collectTexts(root: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>()
        walk(root) { n ->
            if (n.isVisibleToUser && !n.isEditable && !n.isPassword) {
                val t = n.text?.toString()?.trim().orEmpty()
                if (t.isNotEmpty()) {
                    out.add(t)
                } else if (n.childCount == 0 && !n.isClickable) {
                    val d = n.contentDescription?.toString()?.trim().orEmpty()
                    if (d.isNotEmpty()) out.add(d)
                }
            }
        }
        return out
    }

    /** True while the app shows a "Stop" button, which means the answer is still being written. */
    fun hasStopButton(root: AccessibilityNodeInfo): Boolean {
        var found = false
        walk(root) { n ->
            if (!found && n.isVisibleToUser) {
                val label = (n.contentDescription ?: n.text)?.toString()?.trim().orEmpty()
                if (label.isNotEmpty() && STOP_LABEL.matches(label)) found = true
            }
        }
        return found
    }

    /** Looks for a button called "Send"; otherwise guesses the right-most button next to the message box. */
    fun findSendButton(root: AccessibilityNodeInfo, input: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var byLabel: AccessibilityNodeInfo? = null
        val guesses = ArrayList<AccessibilityNodeInfo>()
        val ir = rect(input)
        walk(root) { n ->
            if (n.isVisibleToUser && !n.isEditable) {
                val label = (n.contentDescription ?: n.text)?.toString()?.trim().orEmpty()
                if (byLabel == null && label.isNotEmpty() &&
                    SEND_LABEL.matches(label) && !NOT_A_SEND_LABEL.containsMatchIn(label)
                ) {
                    byLabel = n
                }
                if (n.isClickable && n.isEnabled) {
                    val r = rect(n)
                    val sameRow = r.centerY() in (ir.top - ir.height())..(ir.bottom + ir.height())
                    if (sameRow && r.centerX() >= ir.centerX() && !NOT_SEND_GUESS.containsMatchIn(label)) {
                        guesses.add(n)
                    }
                }
            }
        }
        return byLabel ?: guesses.maxByOrNull { rect(it).centerX() }
    }

    /** Clicks a node (or its nearest clickable parent); falls back to tapping its position. */
    fun click(svc: AiAccessibilityService, node: AccessibilityNodeInfo, forceGesture: Boolean): Boolean {
        if (!forceGesture) {
            var n: AccessibilityNodeInfo? = node
            var guard = 0
            while (n != null && !n.isClickable && guard < 6) {
                n = n.parent
                guard++
            }
            if (n != null && n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        val r = rect(node)
        return svc.tap(r.exactCenterX(), r.exactCenterY())
    }
}
