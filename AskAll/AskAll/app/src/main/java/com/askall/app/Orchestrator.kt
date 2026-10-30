package com.askall.app

import android.os.Handler
import android.os.Looper

enum class Status { PENDING, RUNNING, DONE, FAILED }

enum class Phase { ASKING, JUDGING, FINISHED }

class AppResult(val app: AiApp) {
    var status = Status.PENDING
    var text = ""
    var note = ""
}

/** Everything about one "ask all" run. The service updates it, the screen draws it. */
class JobState(
    val question: String,
    targets: List<AiApp>,
    val shortAnswers: Boolean,
) {
    val results: List<AppResult> = targets.map { AppResult(it) }
    val judge = AppResult(AiApp("Gemini", AiApps.GEMINI_PKG))
    var phase = Phase.ASKING
    var message = "Starting…"
    var cancelled = false

    /** Index into [results] of the answer Gemini picked, or -1. */
    var winnerIndex = -1
    var winnerWhy = ""
    var judgeNote = ""
}

object Orchestrator {
    @Volatile
    var current: JobState? = null

    /** Set by the screen while it is visible. */
    @Volatile
    var onChange: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    fun notifyChange() {
        main.post { onChange?.invoke() }
    }

    /** Returns false if the accessibility service is not switched on yet. */
    fun start(state: JobState): Boolean {
        val svc = AiAccessibilityService.instance ?: return false
        current = state
        svc.runJob(state)
        return true
    }

    fun cancel() {
        AiAccessibilityService.instance?.cancelJob()
    }
}
