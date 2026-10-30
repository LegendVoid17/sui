package com.askall.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Drives ONE AI app like a person would: open it, type the prompt, press send, wait for the
 * answer to finish, read it off the screen. It polls on the main thread (no extra threads).
 *
 * If an app stops working, the numbers in [Companion] are the first thing to tune.
 */
class ChatDriver(
    private val svc: AiAccessibilityService,
    private val handler: Handler,
    private val app: AiApp,
    private val prompt: String,
    private val onFinished: (ok: Boolean, text: String, note: String) -> Unit,
) {
    companion object {
        const val POLL_MS = 700L
        const val SETTLE_MS = 2500L          // wait after the app appears before touching it
        const val LAUNCH_TIMEOUT_MS = 25_000L
        const val FIND_TIMEOUT_MS = 20_000L
        const val MIN_WAIT_MS = 4_000L       // never call an answer "done" sooner than this
        const val STABLE_MS = 5_000L         // text must stop changing for this long
        const val STALL_MS = 25_000L         // ...or 25 s if a Stop button seems stuck on screen
        const val MAX_WAIT_MS = 120_000L
        const val MAX_CHARS = 2_500

        private val CHROME = Regex(
            "^(copy|share|like|dislike|good response|bad response|read aloud|regenerate|retry|" +
                "try again|more|edit|sources?|listen|show more|copy code|report|thumbs up|thumbs down|" +
                "helpful|not helpful|stop|send|new chat)$",
            RegexOption.IGNORE_CASE,
        )
        private val TRANSIENT = Regex(
            "(thinking|searching|generating|typing|analy[sz]ing|reasoning|working on|loading|just a (sec|moment))",
            RegexOption.IGNORE_CASE,
        )
    }

    private enum class Step { LAUNCH, WAIT_WINDOW, TYPE, SEND, WAIT_REPLY, DONE }

    private var step = Step.LAUNCH
    private var stepStart = 0L
    private var cancelled = false
    private var typeAttempts = 0
    private var sendRetries = 0
    private var readyAt = 0L
    private var sentAt = 0L
    private var lastChange = 0L
    private var lastSeenAt = 0L
    private var lastSnapshot = ""
    private var best = ""
    private var baseline: Set<String> = emptySet()
    private val promptNorm = norm(prompt)
    private val tickRunnable = Runnable { tick() }

    fun start() {
        stepStart = now()
        handler.post(tickRunnable)
    }

    fun cancel() {
        cancelled = true
        handler.removeCallbacks(tickRunnable)
    }

    private fun now() = SystemClock.uptimeMillis()

    private fun norm(s: String): String =
        s.lowercase().replace(Regex("\\s+"), " ").trim().trimEnd('…', '.', ' ')

    private fun go(next: Step) {
        step = next
        stepStart = now()
    }

    private fun finish(ok: Boolean, text: String, note: String) {
        if (step == Step.DONE) return
        step = Step.DONE
        handler.removeCallbacks(tickRunnable)
        if (!cancelled) onFinished(ok, text, note)
    }

    private fun finishWithBest(note: String) {
        if (best.isNotEmpty()) finish(true, best.trim().take(MAX_CHARS), note) else finish(false, "", note)
    }

    /** The app's window, but only if it really is the app we are talking to. */
    private fun activeRoot(): AccessibilityNodeInfo? {
        val r = svc.rootInActiveWindow
        return if (r != null && r.packageName?.toString() == app.pkg) r else null
    }

    private fun tick() {
        if (cancelled) return
        try {
            when (step) {
                Step.LAUNCH -> doLaunch()
                Step.WAIT_WINDOW -> doWaitWindow()
                Step.TYPE -> doType()
                Step.SEND -> doSend()
                Step.WAIT_REPLY -> doWaitReply()
                Step.DONE -> return
            }
        } catch (e: Exception) {
            finish(false, "", "Unexpected error (" + e.javaClass.simpleName + ")")
            return
        }
        if (!cancelled && step != Step.DONE) handler.postDelayed(tickRunnable, POLL_MS)
    }

    private fun doLaunch() {
        val intent = svc.packageManager.getLaunchIntentForPackage(app.pkg)
        if (intent == null) {
            finish(false, "", app.label + " isn't installed")
            return
        }
        // CLEAR_TASK starts the app from its main screen, so we don't land inside some old settings page.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        svc.startActivity(intent)
        go(Step.WAIT_WINDOW)
    }

    private fun doWaitWindow() {
        if (activeRoot() != null) {
            if (readyAt == 0L) readyAt = now()
            if (now() - readyAt >= SETTLE_MS) go(Step.TYPE)
        } else if (now() - stepStart > LAUNCH_TIMEOUT_MS) {
            finish(false, "", app.label + " didn't open (Android may have blocked the launch)")
        }
    }

    private fun doType() {
        val root = activeRoot()
        val input = if (root != null) NodeUtils.findInput(root) else null
        if (input == null) {
            if (now() - stepStart > FIND_TIMEOUT_MS) {
                finish(
                    false, "",
                    "Couldn't find the message box in " + app.label +
                        ". Open it once, finish any sign-in or welcome screen, then try again.",
                )
            }
            return
        }
        typeAttempts++
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        input.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (typeAttempts >= 2) {
            // Second try: paste from the clipboard instead of setting the text directly.
            val cm = svc.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("askall", prompt))
            input.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } else {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt)
            input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        go(Step.SEND)
    }

    private fun typedOk(typed: String?): Boolean =
        !typed.isNullOrBlank() && norm(typed).contains(promptNorm.take(15))

    private fun doSend() {
        val root = activeRoot()
        val input = if (root != null) NodeUtils.findInput(root) else null
        if (root == null || input == null) {
            if (now() - stepStart > 15_000) finish(false, "", app.label + " left the screen")
            return
        }
        if (!typedOk(input.text?.toString())) {
            if (typeAttempts < 3) {
                go(Step.TYPE)
            } else {
                finish(false, "", "Couldn't type into " + app.label)
            }
            return
        }
        if (baseline.isEmpty()) baseline = NodeUtils.collectTexts(root).toSet()

        val button = NodeUtils.findSendButton(root, input)
        var clicked = false
        if (button != null) {
            clicked = NodeUtils.click(svc, button, forceGesture = sendRetries >= 1)
        } else if (Build.VERSION.SDK_INT >= 30) {
            clicked = input.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        if (!clicked) {
            if (now() - stepStart > 10_000) finish(false, "", "Couldn't find the send button in " + app.label)
            return
        }
        sentAt = now()
        lastChange = now()
        lastSeenAt = now()
        go(Step.WAIT_REPLY)
    }

    /** Is this line of on-screen text part of the new answer (and not the question or app chrome)? */
    private fun isReply(raw: String): Boolean {
        val t = raw.trim()
        if (t.length < 2) return false
        if (t in baseline) return false
        val n = norm(t)
        if (n.length >= 8 && promptNorm.contains(n)) return false   // the question echoed back
        if (CHROME.matches(t)) return false
        if (t.length <= 40 && TRANSIENT.containsMatchIn(t)) return false
        return true
    }

    private fun doWaitReply() {
        val root = activeRoot()
        if (root == null) {
            if (now() - lastSeenAt > 15_000) finishWithBest("The app left the screen")
            return
        }
        lastSeenAt = now()

        val snapshot = NodeUtils.collectTexts(root).filter { isReply(it) }.joinToString("\n")
        if (snapshot != lastSnapshot) {
            lastSnapshot = snapshot
            lastChange = now()
        }
        if (snapshot.length >= best.length) best = snapshot

        val elapsed = now() - sentAt
        val quiet = now() - lastChange
        val generating = NodeUtils.hasStopButton(root)

        // Nothing happened after pressing send and the question is still in the box: press send again.
        if (best.isEmpty() && elapsed > 7_000 && sendRetries < 2) {
            val input = NodeUtils.findInput(root)
            if (input != null && typedOk(input.text?.toString())) {
                sendRetries++
                go(Step.SEND)
                return
            }
        }

        val done = best.isNotEmpty() && elapsed >= MIN_WAIT_MS && quiet >= STABLE_MS &&
            (!generating || quiet >= STALL_MS)
        if (done) {
            finish(true, best.trim().take(MAX_CHARS), "")
            return
        }
        if (elapsed > MAX_WAIT_MS) {
            if (best.isNotEmpty()) {
                finish(true, best.trim().take(MAX_CHARS), "Timed out, the reply may be partial")
            } else {
                finish(false, "", "No reply detected")
            }
        }
    }
}
