package com.agentos.shell.tools

import android.content.Context

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

    /** Build + launch the Power locally in Termux, wire its 127.0.0.1 endpoint, and verify it answers. */
    fun build(ctx: Context, power: Power): Pair<Boolean, String> {
        if (!TermuxBridge.isInstalled(ctx))
            return false to "Termux isn't set up. Install Termux from F-Droid, run `pkg install python termux-api`, and set allow-external-apps=true in ~/.termux/termux.properties — then try again."
        val r = RECIPES[power.id]
            ?: return false to "No on-phone recipe for ${power.name} yet. Ask HomeAI to \"set up ${power.repo} in Termux\", or run it on a server and paste the URL."
        val log = TermuxBridge.run(ctx, r.setup, 300_000)          // pip installs can be slow
        val endpoint = "http://127.0.0.1:${r.port}"
        return if (PowerDispatch.ping(endpoint)) {
            PowerRegistry.install(ctx, power, endpoint)             // running locally → wire it into the brain
            true to "Running on your phone ✓  ($endpoint)\n\n${log.takeLast(500)}"
        } else {
            false to "Built, but the local server didn't answer on $endpoint.\n\n${log.takeLast(900)}"
        }
    }
}
