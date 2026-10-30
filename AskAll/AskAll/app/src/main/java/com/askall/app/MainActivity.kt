package com.askall.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val BG = 0xFF0F1115.toInt()
    private val CARD = 0xFF1A1D24.toInt()
    private val CARD_GOOD = 0xFF16301F.toInt()
    private val TEXT = 0xFFEAECEF.toInt()
    private val MUTED = 0xFF9AA0AA.toInt()
    private val ACCENT = 0xFF7C9CFF.toInt()
    private val GREEN = 0xFF5FD38D.toInt()
    private val AMBER = 0xFFFFC24D.toInt()
    private val RED = 0xFFFF7A7A.toInt()

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var statusText: TextView
    private lateinit var helpText: TextView
    private lateinit var enableBtn: Button
    private lateinit var appInfoBtn: Button
    private lateinit var appsBox: LinearLayout
    private lateinit var input: EditText
    private lateinit var shortSwitch: Switch
    private lateinit var askBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var resultsBox: LinearLayout

    private val checks = LinkedHashMap<AiApp, CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(BG)
        scroll.isFillViewport = true
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0, insetTop(insets), 0, insetBottom(insets))
            insets
        }
        val root = vertical()
        root.setPadding(dp(16), dp(20), dp(16), dp(32))
        scroll.addView(root, ViewGroup.LayoutParams(MATCH, WRAP))
        setContentView(scroll)

        // Title
        root.addView(tv("AskAll", 28f, TEXT, true))
        root.addView(tv("One question, every AI app on your phone, Gemini picks the best answer.", 14f, MUTED))

        // Status card
        val statusCard = card()
        statusText = tv("", 15f, AMBER, true)
        statusCard.addView(statusText)
        helpText = tv(
            "Turn on “AskAll” under Accessibility → Installed apps. If Android says " +
                "“Restricted setting”, open App info, tap ⋮ and choose “Allow restricted settings”, then try again.",
            13f, MUTED,
        )
        helpText.setPadding(0, dp(6), 0, dp(6))
        statusCard.addView(helpText)
        val row = horizontal()
        enableBtn = button("Open Accessibility", true)
        enableBtn.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        appInfoBtn = button("App info", false)
        appInfoBtn.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
            )
        }
        row.addView(enableBtn, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(space(dp(8), 1))
        row.addView(appInfoBtn, LinearLayout.LayoutParams(0, WRAP, 1f))
        statusCard.addView(row)
        root.addView(statusCard)

        // Apps card
        val appsCard = card()
        appsCard.addView(tv("AI apps on this phone", 16f, TEXT, true))
        appsBox = vertical()
        appsCard.addView(appsBox)
        val addBtn = button("+ Add another app", false)
        addBtn.setOnClickListener { pickApp() }
        appsCard.addView(addBtn)
        root.addView(appsCard)

        // Question card
        val qCard = card()
        qCard.addView(tv("Your question", 16f, TEXT, true))
        input = EditText(this)
        input.hint = "Ask anything…"
        input.setHintTextColor(MUTED)
        input.setTextColor(TEXT)
        input.minLines = 3
        input.gravity = Gravity.TOP
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        qCard.addView(input, LinearLayout.LayoutParams(MATCH, WRAP))
        shortSwitch = Switch(this)
        shortSwitch.text = "Ask for short answers (recommended)"
        shortSwitch.setTextColor(MUTED)
        shortSwitch.textSize = 13f
        shortSwitch.isChecked = Prefs.shortAnswers(this)
        shortSwitch.setOnCheckedChangeListener { _, on -> Prefs.setShortAnswers(this, on) }
        shortSwitch.setPadding(0, dp(8), 0, dp(8))
        qCard.addView(shortSwitch)
        askBtn = button("Ask all", true)
        askBtn.setOnClickListener { ask() }
        qCard.addView(askBtn)
        stopBtn = button("Stop", false)
        stopBtn.visibility = View.GONE
        stopBtn.setOnClickListener { Orchestrator.cancel() }
        qCard.addView(stopBtn)
        root.addView(qCard)

        // Results
        resultsBox = vertical()
        root.addView(resultsBox)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        buildApps()
        Orchestrator.onChange = { renderResults() }
        renderResults()
    }

    override fun onPause() {
        Orchestrator.onChange = null
        super.onPause()
    }

    // ---- setup ---------------------------------------------------------------------------

    private fun refreshStatus() {
        val on = AiAccessibilityService.instance != null
        statusText.text = if (on) "● Automation is ON" else "○ Automation is OFF"
        statusText.setTextColor(if (on) GREEN else AMBER)
        val vis = if (on) View.GONE else View.VISIBLE
        helpText.visibility = vis
        enableBtn.visibility = vis
        appInfoBtn.visibility = vis
    }

    private fun candidates(): List<AiApp> {
        val known = AiApps.known.filter { AiApps.isInstalled(packageManager, it.pkg) }
        val custom = Prefs.customApps(this).filter { c ->
            AiApps.isInstalled(packageManager, c.pkg) && known.none { it.pkg == c.pkg }
        }
        return known + custom
    }

    private fun buildApps() {
        appsBox.removeAllViews()
        checks.clear()
        val saved = Prefs.selected(this)
        val list = candidates()
        val customPkgs = Prefs.customApps(this).map { it.pkg }.toSet()

        if (list.isEmpty()) {
            appsBox.addView(tv("No known AI apps found yet. Tap “Add another app”.", 13f, MUTED))
        }
        for (app in list) {
            val cb = CheckBox(this)
            cb.text = app.label
            cb.setTextColor(TEXT)
            cb.textSize = 15f
            cb.isChecked = saved?.contains(app.pkg) ?: true
            cb.setOnCheckedChangeListener { _, _ -> saveSelection() }
            if (app.pkg in customPkgs) {
                cb.setOnLongClickListener {
                    Prefs.removeCustom(this, app.pkg)
                    toast("Removed " + app.label)
                    buildApps()
                    true
                }
            }
            checks[app] = cb
            appsBox.addView(cb)
        }

        val missing = AiApps.known.filter { k -> list.none { it.pkg == k.pkg } }.map { it.label }
        if (missing.isNotEmpty()) {
            val t = tv("Not installed: " + missing.joinToString(", "), 12f, MUTED)
            t.setPadding(0, dp(6), 0, 0)
            appsBox.addView(t)
        }
        if (customPkgs.isNotEmpty()) {
            appsBox.addView(tv("Long-press an app you added to remove it.", 12f, MUTED))
        }
    }

    private fun saveSelection() {
        Prefs.saveSelected(this, checks.filter { it.value.isChecked }.keys.map { it.pkg }.toSet())
    }

    private fun pickApp() {
        val have = candidates().map { it.pkg }.toSet()
        val apps = AiApps.launchable(this).filter { it.pkg !in have }
        if (apps.isEmpty()) {
            toast("No other apps found")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Add an AI app")
            .setItems(apps.map { it.label }.toTypedArray()) { _, i ->
                val chosen = apps[i]
                val current = Prefs.selected(this) ?: candidates().map { it.pkg }.toSet()
                Prefs.addCustom(this, chosen)
                Prefs.saveSelected(this, current + chosen.pkg)
                buildApps()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- asking --------------------------------------------------------------------------

    private fun ask() {
        val q = input.text.toString().trim()
        if (q.isEmpty()) {
            toast("Type a question first")
            return
        }
        if (AiAccessibilityService.instance == null) {
            toast("Turn on AskAll in Accessibility first")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val targets = checks.filter { it.value.isChecked }.keys.toList()
        if (targets.isEmpty()) {
            toast("Tick at least one AI app")
            return
        }
        val state = JobState(q, targets, shortSwitch.isChecked)
        if (!Orchestrator.start(state)) {
            toast("Automation isn't running")
            return
        }
        renderResults()
    }

    // ---- results -------------------------------------------------------------------------

    private fun renderResults() {
        resultsBox.removeAllViews()
        val st = Orchestrator.current
        if (st == null) {
            stopBtn.visibility = View.GONE
            return
        }
        val running = st.phase != Phase.FINISHED
        stopBtn.visibility = if (running) View.VISIBLE else View.GONE
        askBtn.isEnabled = !running
        askBtn.alpha = if (running) 0.5f else 1f

        val head = tv(st.message, 13f, MUTED)
        head.setPadding(dp(4), dp(12), dp(4), 0)
        resultsBox.addView(head)

        if (!running && !st.cancelled) {
            val winner = st.results.getOrNull(st.winnerIndex)
            if (winner != null) {
                val c = card(CARD_GOOD)
                c.addView(tv("Best answer: " + winner.app.label, 16f, GREEN, true))
                if (st.winnerWhy.isNotEmpty()) c.addView(tv("Gemini: " + st.winnerWhy, 13f, MUTED))
                val body = tv(winner.text, 15f, TEXT)
                body.setTextIsSelectable(true)
                body.setPadding(0, dp(8), 0, dp(8))
                c.addView(body)
                val copy = button("Copy answer", false)
                copy.setOnClickListener {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("answer", winner.text))
                    toast("Copied")
                }
                c.addView(copy)
                resultsBox.addView(c)
            }
            if (st.judgeNote.isNotEmpty()) {
                val n = tv(st.judgeNote, 13f, AMBER)
                n.setPadding(dp(4), dp(10), dp(4), 0)
                resultsBox.addView(n)
            }
        }

        resultsBox.addView(sectionLabel("All replies"))
        for (r in st.results) {
            resultsBox.addView(resultCard(r))
        }
        if (st.judge.status != Status.PENDING) {
            resultsBox.addView(sectionLabel("Gemini's verdict"))
            resultsBox.addView(resultCard(st.judge))
        }
    }

    private fun resultCard(r: AppResult): LinearLayout {
        val c = card()
        val mark = when (r.status) {
            Status.PENDING -> "…"
            Status.RUNNING -> "⏳"
            Status.DONE -> "✓"
            Status.FAILED -> "✗"
        }
        val color = when (r.status) {
            Status.DONE -> GREEN
            Status.FAILED -> RED
            else -> MUTED
        }
        c.addView(tv(mark + "  " + r.app.label, 15f, color, true))
        val body = when {
            r.text.isNotEmpty() -> r.text
            r.note.isNotEmpty() -> r.note
            r.status == Status.RUNNING -> "Working…"
            else -> "Waiting"
        }
        val t = tv(body, 14f, if (r.text.isNotEmpty()) TEXT else MUTED)
        t.setTextIsSelectable(true)
        t.setPadding(0, dp(6), 0, 0)
        c.addView(t)
        if (r.text.isNotEmpty() && r.note.isNotEmpty()) {
            c.addView(tv(r.note, 12f, AMBER))
        }
        return c
    }

    // ---- tiny view helpers ---------------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    @Suppress("DEPRECATION")
    private fun insetTop(i: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= 30) i.getInsets(WindowInsets.Type.systemBars()).top else i.systemWindowInsetTop

    @Suppress("DEPRECATION")
    private fun insetBottom(i: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= 30) i.getInsets(WindowInsets.Type.systemBars()).bottom else i.systemWindowInsetBottom

    private fun vertical(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    private fun horizontal(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.HORIZONTAL
        return l
    }

    private fun space(w: Int, h: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(w, h)
        return v
    }

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    private fun sectionLabel(text: String): TextView {
        val t = tv(text.uppercase(), 11f, MUTED, true)
        t.setPadding(dp(4), dp(18), dp(4), 0)
        return t
    }

    private fun card(color: Int = CARD): LinearLayout {
        val l = vertical()
        val bg = GradientDrawable()
        bg.setColor(color)
        bg.cornerRadius = dp(14).toFloat()
        l.background = bg
        l.setPadding(dp(14), dp(12), dp(14), dp(12))
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(12)
        l.layoutParams = lp
        return l
    }

    private fun button(label: String, primary: Boolean): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(if (primary) Color.BLACK else TEXT)
        val bg = GradientDrawable()
        bg.setColor(if (primary) ACCENT else 0xFF2A2F3A.toInt())
        bg.cornerRadius = dp(12).toFloat()
        b.background = bg
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(8)
        b.layoutParams = lp
        return b
    }
}
