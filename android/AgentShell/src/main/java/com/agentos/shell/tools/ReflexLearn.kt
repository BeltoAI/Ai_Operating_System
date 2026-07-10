package com.agentos.shell.tools

import android.content.Context
import com.agentos.shell.InteractionLogService
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * REFLEX LEARN — teach by demonstration.
 *
 * The user performs a task ONCE by hand; the accessibility service records each grounded action (the element
 * the user touched, captured by its resource-id + text + description + class, plus any text typed). SlyOS
 * saves it as a named SKILL and can replay it perfectly forever — re-finding each element by its recorded
 * signals on the live screen. No LLM in the loop at replay time, so it's deterministic and fast: the endgame
 * for reliable, repeatable missions.
 */
object ReflexLearn {
    private const val PREF = "slyos"
    private const val KEY = "reflex_skills"

    data class Step(val action: String, val id: String, val text: String, val desc: String, val cls: String, val typed: String)
    data class Skill(val name: String, val steps: List<Step>)

    @Volatile var recording = false; private set
    private var recName = ""
    private val recSteps = ArrayList<Step>()

    // ---- Recording (driven by the accessibility service) ------------------------------------------
    fun startRecording(name: String) { recName = name.trim(); recSteps.clear(); recording = true }
    fun cancel() { recording = false; recSteps.clear() }

    fun stopRecording(ctx: Context): Int {
        recording = false
        val steps = recSteps.toList()
        if (recName.isNotBlank() && steps.isNotEmpty()) saveSkill(ctx, Skill(recName, steps))
        recSteps.clear()
        return steps.size
    }

    /** A tap the user made (captured from TYPE_VIEW_CLICKED). */
    fun onClick(id: String, text: String, desc: String, cls: String) {
        if (!recording) return
        recSteps.add(Step("click", id.substringAfterLast('/'), text.take(60), desc.take(60), cls.substringAfterLast('.'), ""))
    }

    /** Text the user typed (captured from TYPE_VIEW_TEXT_CHANGED). Collapses keystrokes into the final value. */
    fun onType(id: String, desc: String, cls: String, typed: String) {
        if (!recording || typed.isBlank()) return
        val sid = id.substringAfterLast('/')
        val last = recSteps.lastOrNull()
        if (last != null && last.action == "type" && last.id == sid) recSteps[recSteps.size - 1] = last.copy(typed = typed)
        else recSteps.add(Step("type", sid, "", desc.take(60), cls.substringAfterLast('.'), typed))
    }

    // ---- Store ------------------------------------------------------------------------------------
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun skills(ctx: Context): List<Skill> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val st = o.optJSONArray("steps") ?: JSONArray()
            Skill(o.optString("name"), (0 until st.length()).mapNotNull { j ->
                val s = st.optJSONObject(j) ?: return@mapNotNull null
                Step(s.optString("action"), s.optString("id"), s.optString("text"), s.optString("desc"), s.optString("cls"), s.optString("typed"))
            })
        }
    } catch (e: Exception) { emptyList() }

    private fun saveSkill(ctx: Context, skill: Skill) {
        val all = skills(ctx).filter { !it.name.equals(skill.name, true) } + skill
        val arr = JSONArray()
        all.forEach { sk ->
            val steps = JSONArray()
            sk.steps.forEach { s -> steps.put(JSONObject().put("action", s.action).put("id", s.id).put("text", s.text).put("desc", s.desc).put("cls", s.cls).put("typed", s.typed)) }
            arr.put(JSONObject().put("name", sk.name).put("steps", steps))
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun delete(ctx: Context, name: String) {
        val arr = JSONArray()
        skills(ctx).filter { !it.name.equals(name, true) }.forEach { sk ->
            val steps = JSONArray()
            sk.steps.forEach { s -> steps.put(JSONObject().put("action", s.action).put("id", s.id).put("text", s.text).put("desc", s.desc).put("cls", s.cls).put("typed", s.typed)) }
            arr.put(JSONObject().put("name", sk.name).put("steps", steps))
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** A learned skill whose name clearly matches the goal (word overlap), or null. */
    fun match(ctx: Context, goal: String): Skill? {
        val g = goal.lowercase()
        return skills(ctx).firstOrNull { sk ->
            val words = sk.name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
            words.isNotEmpty() && words.count { g.contains(it) } >= (words.size + 1) / 2
        }
    }

    // ---- Replay -----------------------------------------------------------------------------------
    /** Re-find the recorded element on the current screen by its signals. */
    private fun findStep(nodes: List<InteractionLogService.ScreenNode>, step: Step): Int? {
        var best = -1; var bestScore = 0.0
        for (n in nodes) {
            val t = n.text.lowercase()
            var s = 0.0
            if (step.id.isNotBlank() && t.contains(step.id.lowercase())) s += 4.0
            if (step.text.isNotBlank() && n.text.equals(step.text, true)) s += 3.0
            else if (step.text.isNotBlank() && t.contains(step.text.lowercase())) s += 1.5
            if (step.desc.isNotBlank() && t.contains(step.desc.lowercase())) s += 1.0
            if (step.action == "type" && !n.editable) s -= 2.0
            if (step.action == "click" && n.clickable) s += 0.5
            if (s > bestScore) { bestScore = s; best = n.index }
        }
        return if (bestScore >= 2.0) best else null
    }

    /** Replay a learned skill on the live screen. Returns true if every step landed. */
    suspend fun replay(svc: InteractionLogService, skill: Skill): Boolean {
        for (step in skill.steps) {
            var nodes = svc.readScreen()
            var idx = findStep(nodes, step)
            if (idx == null) { svc.scroll(true); delay(700); nodes = svc.readScreen(); idx = findStep(nodes, step) }
            if (idx == null) return false
            if (step.action == "type") svc.setText(idx, step.typed) else svc.tapNode(idx)
            delay(900)
        }
        return true
    }
}
