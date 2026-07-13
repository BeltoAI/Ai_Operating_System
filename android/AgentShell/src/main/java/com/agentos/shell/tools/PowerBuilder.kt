package com.agentos.shell.tools

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * ON-PHONE POWER PROVISIONING. Instead of pointing at a server on your Mac, this builds and runs the repo
 * right on the phone inside Termux (a full Linux userland — python, pip, node, git), then wires the local
 * server's address (127.0.0.1:PORT) into the Power so the brain calls it on-device. Termux is the execution
 * engine; SlyOS drives it. Curated recipes below; arbitrary repos can be provisioned by the agent's
 * run_command tool (ask HomeAI to "set up <repo> in Termux").
 *
 * Honest limits: this only works for repos light enough to run on a phone (CLIs / small HTTP servers). Heavy
 * GPU stacks (ComfyUI, big models) won't. Some Python ML deps (e.g. onnxruntime) may not have an aarch64
 * wheel and can fail to install — the build log tells you.
 */
object PowerBuilder {
    data class Recipe(val setup: String, val port: Int)

    private val RECIPES: Map<String, Recipe> = mapOf(
        // rembg: install the CLI + runtime, (re)start a local server bound to loopback, health-check it.
        "rembg" to Recipe(
            setup = "pip install --quiet 'rembg[cli]' onnxruntime 2>&1 | tail -4; " +
                "pkill -f 'rembg s' 2>/dev/null; sleep 1; " +
                "nohup rembg s --host 127.0.0.1 --port 7000 > ~/rembg.log 2>&1 & sleep 4; " +
                "echo '--- health ---'; curl -s -o /dev/null -w 'http:%{http_code}' http://127.0.0.1:7000/api 2>/dev/null || echo 'http:000'",
            port = 7000
        )
    )

    fun hasRecipe(id: String): Boolean = RECIPES.containsKey(id)

    /**
     * Have the model write (or REPAIR) a Termux recipe. When [prevSetup]/[errorLog] are given it fixes the
     * failed attempt. Crucially it may also REPLICATE the repo — write a minimal, phone-friendly equivalent
     * server — when the real thing is too heavy to build on a phone.
     */
    private fun aiRecipe(power: Power, prevSetup: String?, errorLog: String?): Recipe? = try {
        val base = "You provision software on an Android phone via Termux (aarch64 Linux: pkg/pip/npm/git, no GPU, limited RAM). " +
            "Goal: a LOCAL HTTP server on 127.0.0.1 giving the capability of the GitHub repo '${power.repo}' (${power.description}). " +
            "You may EITHER install the repo, OR — if it's too heavy for a phone — WRITE A MINIMAL SELF-CONTAINED EQUIVALENT (e.g. a tiny Python http.server/Flask app using light, phone-friendly libraries, heredoc'd into a file) that exposes the same kind of endpoint. Prefer whatever will actually run on a phone. " +
            "Reply with ONLY minified JSON, no prose, no markdown fences: {\"setup\":\"<ONE bash line: install deps then start the server in the background with nohup ... >/dev/null 2>&1 & , then sleep 4, then curl the health path>\",\"port\":<int>}."
        val prompt = if (prevSetup == null) base
            else base + " The previous attempt FAILED. Previous setup: <<$prevSetup>>. Error: <<${errorLog?.takeLast(700)}>>. Fix it: install missing packages (pkg for system, pip/npm for libs) or switch to a lighter equivalent."
        val out = AgentClient.appAsk(prompt, "")
        val json = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(out)?.value ?: return null
        val o = JSONObject(json)
        val setup = o.optString("setup"); val port = o.optInt("port", 0)
        if (setup.isBlank() || port <= 0) null else Recipe(setup, port)
    } catch (e: Exception) { Log.w("SlyOS-Build", "aiRecipe: ${e.message}"); null }

    /** Pull the single most telling error line out of a shell log, for a clean one-line reason. */
    private fun errorEssence(log: String): String {
        val lines = log.lines().map { it.trim() }.filter { it.isNotBlank() }
        val hit = lines.lastOrNull {
            listOf("error", "not found", "no module", "cannot", "failed", "no such", "unable").any { k -> it.contains(k, true) }
        }
        return (hit ?: lines.lastOrNull() ?: "").take(150)
    }

    /** Build + launch the Power locally in Termux, wire its 127.0.0.1 endpoint, and verify it answers. */
    fun build(ctx: Context, power: Power): Pair<Boolean, String> {
        if (!TermuxBridge.isInstalled(ctx))
            return false to "Termux isn't set up. Install it from F-Droid, run `pkg install python termux-api`, set allow-external-apps=true — then try again."
        var recipe = RECIPES[power.id] ?: aiRecipe(power, null, null)
            ?: return false to "I couldn't work out a way to run ${power.name} on a phone — it likely needs a desktop or GPU. Easiest path: run it on a computer/server and use “Connect my own instance” below."
        var lastLog = ""
        // Diligent, self-healing loop: try, and if the server doesn't come up, feed the error back to the
        // AI to repair the recipe (or write a lighter equivalent) and try once more.
        for (attempt in 0..1) {
            val log = TermuxBridge.run(ctx, recipe!!.setup, 300_000)
            if (TermuxBridge.isSetupHint(log)) return false to log
            lastLog = log
            val endpoint = "http://127.0.0.1:${recipe!!.port}"
            if (PowerDispatch.ping(endpoint)) {
                PowerRegistry.install(ctx, power, endpoint)
                return true to "Running on your phone ✓  ($endpoint)"
            }
            if (attempt == 0) recipe = aiRecipe(power, recipe!!.setup, log) ?: break
        }
        val reason = errorEssence(lastLog)
        return false to ("Couldn't get ${power.name} running on the phone" +
            (if (reason.isBlank()) "." else " — $reason.") +
            "\n\nTwo ways forward:\n• Run it on a computer/server, then use “Connect my own instance” below.\n• Or pick a lighter power — some repos are just too heavy for a phone.")
    }
}
