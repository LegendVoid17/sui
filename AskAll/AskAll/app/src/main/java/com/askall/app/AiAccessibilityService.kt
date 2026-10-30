package com.askall.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView

/**
 * The engine. While a run is active it opens each selected AI app in turn, asks the question,
 * collects the reply, then asks Gemini to pick the best one. It does nothing when no run is active.
 */
class AiAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AiAccessibilityService? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var driver: ChatDriver? = null
    private var job: JobState? = null
    private var pill: TextView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cancelJob()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // We poll the screen ourselves while a run is active, so events are not used.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    // ---- running a job -------------------------------------------------------------------

    fun runJob(state: JobState) {
        cancelJob()
        job = state
        showPill("Starting…")
        askNext(0)
    }

    private fun askNext(index: Int) {
        val st = job ?: return
        if (st.cancelled) return
        if (index >= st.results.size) {
            startJudging()
            return
        }
        val r = st.results[index]
        r.status = Status.RUNNING
        st.message = "Asking " + r.app.label + " (" + (index + 1) + " of " + st.results.size + ")"
        showPill(st.message)
        Orchestrator.notifyChange()

        val prompt = if (st.shortAnswers) {
            st.question + "\n\n(Please answer in under 150 words.)"
        } else {
            st.question
        }
        val d = ChatDriver(this, handler, r.app, prompt) { ok, text, note ->
            r.status = if (ok) Status.DONE else Status.FAILED
            r.text = text
            r.note = note
            Orchestrator.notifyChange()
            askNext(index + 1)
        }
        driver = d
        d.start()
    }

    private fun startJudging() {
        val st = job ?: return
        val good = st.results.withIndex().filter { it.value.status == Status.DONE && it.value.text.isNotBlank() }

        if (good.isEmpty()) {
            st.judgeNote = "No app gave an answer."
            finish()
            return
        }
        if (good.size == 1) {
            st.winnerIndex = good[0].index
            st.judgeNote = "Only one app answered, so there was nothing to compare."
            finish()
            return
        }
        if (!AiApps.isInstalled(packageManager, AiApps.GEMINI_PKG)) {
            st.judgeNote = "Gemini isn't installed, so it couldn't pick a winner."
            finish()
            return
        }

        st.phase = Phase.JUDGING
        st.message = "Asking Gemini to pick the best answer"
        st.judge.status = Status.RUNNING
        showPill(st.message)
        Orchestrator.notifyChange()

        val sb = StringBuilder()
        sb.append("Below are answers from different AI assistants to the same question. ")
        sb.append("Pick the single best one (most accurate, complete and clear).\n\n")
        sb.append("QUESTION: ").append(st.question).append("\n\n")
        good.forEachIndexed { n, iv ->
            sb.append("ANSWER ").append(n + 1).append(":\n")
            sb.append(iv.value.text.take(1200)).append("\n\n")
        }
        sb.append("Reply in exactly two lines and nothing else.\n")
        sb.append("Line 1: BEST: <answer number>\n")
        sb.append("Line 2: WHY: <one short sentence>")

        val d = ChatDriver(this, handler, st.judge.app, sb.toString()) { ok, text, note ->
            st.judge.status = if (ok) Status.DONE else Status.FAILED
            st.judge.text = text
            st.judge.note = note

            val n = Regex("BEST:?\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.toIntOrNull()
            if (n != null && n in 1..good.size) st.winnerIndex = good[n - 1].index
            st.winnerWhy = Regex("WHY:?\\s*(.+)", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.trim().orEmpty()
            if (st.winnerIndex < 0) st.judgeNote = "Gemini's verdict couldn't be read, so here are all the answers."
            finish()
        }
        driver = d
        d.start()
    }

    private fun finish() {
        val st = job ?: return
        st.phase = Phase.FINISHED
        st.message = "Done"
        driver = null
        bringAppToFront()
        handler.postDelayed({ hidePill() }, 1500)
        Orchestrator.notifyChange()
    }

    fun cancelJob() {
        driver?.cancel()
        driver = null
        val st = job
        if (st != null && st.phase != Phase.FINISHED) {
            st.cancelled = true
            st.phase = Phase.FINISHED
            st.message = "Stopped"
            st.results.filter { it.status == Status.RUNNING }.forEach {
                it.status = Status.FAILED
                it.note = "Stopped"
            }
            if (st.judge.status == Status.RUNNING) {
                st.judge.status = Status.FAILED
                st.judge.note = "Stopped"
            }
            Orchestrator.notifyChange()
        }
        hidePill()
    }

    private fun bringAppToFront() {
        val i = Intent(this, MainActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            startActivity(i)
        } catch (e: Exception) {
            // If Android refuses, the results are still waiting inside AskAll.
        }
    }

    // ---- gestures ------------------------------------------------------------------------

    fun tap(x: Float, y: Float): Boolean {
        val p = Path()
        p.moveTo(x, y)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60))
            .build()
        return dispatchGesture(g, null, null)
    }

    // ---- the small "Asking ChatGPT… tap to stop" pill ---------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun showPill(text: String) {
        val label = "$text  ·  tap to stop"
        val existing = pill
        if (existing != null) {
            existing.text = label
            return
        }
        val tv = TextView(this)
        tv.text = label
        tv.setTextColor(Color.WHITE)
        tv.textSize = 13f
        tv.setPadding(dp(14), dp(8), dp(14), dp(8))
        val bg = GradientDrawable()
        bg.setColor(0xE6222222.toInt())
        bg.cornerRadius = dp(20).toFloat()
        tv.background = bg
        tv.setOnClickListener {
            cancelJob()
            bringAppToFront()
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = dp(48)
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(tv, lp)
            pill = tv
        } catch (e: Exception) {
            pill = null
        }
    }

    private fun hidePill() {
        val tv = pill ?: return
        pill = null
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(tv)
        } catch (e: Exception) {
            // already gone
        }
    }
}
