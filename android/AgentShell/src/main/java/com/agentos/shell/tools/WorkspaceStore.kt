package com.agentos.shell.tools

import android.content.Context
import java.io.File

/**
 * The local Cowork workspace: real files on the device the agent can list, read, create and edit —
 * the on-phone equivalent of a desktop project folder. Plain files in app storage, so the agent's
 * work persists and can be exported.
 */
object WorkspaceStore {
    private fun dir(ctx: Context): File = File(ctx.filesDir, "cowork").apply { mkdirs() }
    private fun safe(name: String): String =
        name.trim().replace(Regex("[\\\\/]+"), "_").replace("..", "_").take(120).ifBlank { "untitled.txt" }

    fun list(ctx: Context): List<String> =
        dir(ctx).listFiles()?.filter { it.isFile }?.map { it.name }?.sorted() ?: emptyList()

    fun read(ctx: Context, name: String): String =
        File(dir(ctx), safe(name)).let { if (it.exists()) it.readText() else "" }

    fun write(ctx: Context, name: String, content: String) {
        try { File(dir(ctx), safe(name)).writeText(content) } catch (e: Exception) {}
    }

    fun edit(ctx: Context, name: String, find: String, replace: String): Boolean {
        val f = File(dir(ctx), safe(name)); if (!f.exists()) return false
        val cur = f.readText(); if (!cur.contains(find)) return false
        f.writeText(cur.replaceFirst(find, replace)); return true
    }

    fun delete(ctx: Context, name: String) { try { File(dir(ctx), safe(name)).delete() } catch (e: Exception) {} }
    fun exists(ctx: Context, name: String): Boolean = File(dir(ctx), safe(name)).exists()
}
