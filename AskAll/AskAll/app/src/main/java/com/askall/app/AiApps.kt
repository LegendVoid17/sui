package com.askall.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

data class AiApp(val label: String, val pkg: String)

/** Known AI assistant apps (by Android package name) plus helpers to find what is installed. */
object AiApps {
    const val GEMINI_PKG = "com.google.android.apps.bard"

    val known = listOf(
        AiApp("ChatGPT", "com.openai.chatgpt"),
        AiApp("Gemini", GEMINI_PKG),
        AiApp("Claude", "com.anthropic.claude"),
        AiApp("Copilot", "com.microsoft.copilot"),
        AiApp("Perplexity", "ai.perplexity.app.android"),
        AiApp("Grok", "ai.x.grok"),
        AiApp("DeepSeek", "com.deepseek.chat"),
        AiApp("Le Chat", "ai.mistral.chat"),
        AiApp("Kimi", "com.moonshot.kimichat"),
        AiApp("Poe", "com.poe.android"),
        AiApp("Pi", "ai.inflection.pi"),
    )

    fun isInstalled(pm: PackageManager, pkg: String): Boolean =
        pm.getLaunchIntentForPackage(pkg) != null

    /** Every app with a launcher icon, so the user can add AI apps that are not in the known list. */
    @Suppress("DEPRECATION")
    fun launchable(c: Context): List<AiApp> {
        val pm = c.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { AiApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.pkg != c.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }
}

/** Small wrapper around SharedPreferences. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("askall", Context.MODE_PRIVATE)

    /** Package names the user ticked, or null if they have never changed the selection. */
    fun selected(c: Context): Set<String>? = sp(c).getStringSet("selected", null)?.toSet()

    fun saveSelected(c: Context, pkgs: Set<String>) {
        sp(c).edit().putStringSet("selected", HashSet(pkgs)).apply()
    }

    fun customApps(c: Context): List<AiApp> {
        val raw = sp(c).getStringSet("custom", emptySet()) ?: emptySet()
        return raw.mapNotNull {
            val i = it.indexOf('\t')
            if (i > 0) AiApp(it.substring(i + 1), it.substring(0, i)) else null
        }.sortedBy { it.label.lowercase() }
    }

    fun addCustom(c: Context, app: AiApp) {
        val s = HashSet(sp(c).getStringSet("custom", emptySet()) ?: emptySet())
        s.add(app.pkg + "\t" + app.label)
        sp(c).edit().putStringSet("custom", s).apply()
    }

    fun removeCustom(c: Context, pkg: String) {
        val s = HashSet(sp(c).getStringSet("custom", emptySet()) ?: emptySet())
        s.removeAll { it.startsWith(pkg + "\t") }
        sp(c).edit().putStringSet("custom", s).apply()
    }

    fun shortAnswers(c: Context): Boolean = sp(c).getBoolean("short", true)

    fun setShortAnswers(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("short", v).apply()
    }
}
