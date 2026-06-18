package com.agentos.shell.tools

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Native bridge exposed to every mini-app as `SlyOSNative` (wrapped by `window.SlyOS` in JS).
 * Lets generated apps persist data, read the user's memory, write back into the brain, and call AI.
 */
class AppBridge(
    private val ctx: Context,
    private val appId: Long,
    private val scope: CoroutineScope
) {
    @Volatile var web: WebView? = null

    @JavascriptInterface fun save(key: String, value: String) = AppStore.saveData(ctx, appId, key, value)
    @JavascriptInterface fun load(key: String): String = AppStore.loadData(ctx, appId, key)
    @JavascriptInterface fun memory(): String = MemoryStore.about(ctx)

    /** App writes a durable fact back into the brain (memory + graph). */
    @JavascriptInterface fun remember(fact: String) {
        val f = fact.trim()
        if (f.isBlank()) return
        val cur = MemoryStore.about(ctx)
        MemoryStore.setAbout(ctx, if (cur.isBlank()) f else "$cur\n$f")
        MemoryLog.add(ctx, "idea", f, f, "App")
    }

    /** Async AI call; result is delivered back to the JS Promise via __slyosResolve. */
    @JavascriptInterface fun ask(prompt: String, cbId: String) {
        scope.launch {
            val ans = withContext(Dispatchers.IO) { AgentClient.appAsk(prompt, MemoryStore.about(ctx)) }
            val w = web ?: return@launch
            w.post {
                w.evaluateJavascript(
                    "window.__slyosResolve(${JSONObject.quote(cbId)}, ${JSONObject.quote(ans)});", null
                )
            }
        }
    }

    companion object {
        /** JS shim + a safety-net stylesheet, injected ahead of the app so it has the API and stays in-frame. */
        fun wrap(html: String): String {
            val head = """
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<style>
  html,body{margin:0;max-width:100%;overflow-x:hidden;background:#F4EFE6;color:#1A1714;
    font-family:-apple-system,system-ui,sans-serif;}
  *{box-sizing:border-box;}
  img,video,canvas,table,pre{max-width:100%;}
  body{word-wrap:break-word;overflow-wrap:break-word;}
</style>
<script>
(function(){
  var N=window.SlyOSNative;
  window.SlyOS={
    save:function(k,v){ try{N.save(k,(typeof v==='string')?v:JSON.stringify(v));}catch(e){} },
    load:function(k){ try{var s=N.load(k); if(!s) return null; try{return JSON.parse(s);}catch(e){return s;}}catch(e){return null;} },
    memory:function(){ try{return N.memory();}catch(e){return '';} },
    remember:function(f){ try{N.remember(String(f));}catch(e){} },
    _cbs:{}, _n:0,
    ask:function(p){ var self=this; return new Promise(function(res){ var id='cb'+(self._n++); self._cbs[id]=res; try{N.ask(p,id);}catch(e){res('');} }); }
  };
  window.__slyosResolve=function(id,val){ var f=window.SlyOS._cbs[id]; if(f){ delete window.SlyOS._cbs[id]; f(val);} };
})();
</script>
"""
            // Put our head injection right after <head> if present, else at the very top.
            val idx = Regex("(?i)<head[^>]*>").find(html)?.range?.last
            return if (idx != null) html.substring(0, idx + 1) + head + html.substring(idx + 1)
                   else head + html
        }
    }
}
