package com.agentos.shell.tools

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.util.UUID

/**
 * YOUR STAFF — persistent AI employees you hire. Each has a name, a role, a standing goal, the tools it may
 * use, and its own running log of what it's done. Runs are logged to the brain too, so the whole team's work
 * is tracked and searchable. Backed by SQLite (grows, and rides along in brain backups).
 */
object EmployeeStore {
    private const val TAG = "SlyOS-Staff"

    data class Employee(
        val id: String,
        val name: String,
        val role: String,
        val goal: String,
        val tools: String,        // comma-separated capabilities the employee may use
        val intervalMin: Int,     // how often it runs on its own (0 = only when you tap Run)
        val autonomous: Boolean,  // false = it proposes and you approve; true = it acts on its own
        val createdAt: Long,
        val lastRun: Long,
        val status: String        // idle | working | needs_you
    )

    data class LogLine(val id: Long, val empId: String, val ts: Long, val line: String, val needsInput: Boolean)

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "slyos_staff.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS employees(id TEXT PRIMARY KEY, name TEXT, role TEXT, goal TEXT, " +
                "tools TEXT, interval_min INTEGER, autonomous INTEGER, created_at INTEGER, last_run INTEGER, status TEXT)")
            db.execSQL("CREATE TABLE IF NOT EXISTS employee_log(id INTEGER PRIMARY KEY AUTOINCREMENT, emp_id TEXT, " +
                "ts INTEGER, line TEXT, needs_input INTEGER)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_log_emp ON employee_log(emp_id, ts)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
    }

    @Volatile private var helper: Helper? = null
    private fun db(ctx: Context): SQLiteDatabase {
        val h = helper ?: synchronized(this) { helper ?: Helper(ctx).also { helper = it } }
        return h.writableDatabase
    }

    fun hire(ctx: Context, name: String, role: String, goal: String, tools: String, intervalMin: Int, autonomous: Boolean): String {
        val id = UUID.randomUUID().toString()
        try {
            db(ctx).insert("employees", null, ContentValues().apply {
                put("id", id); put("name", name); put("role", role); put("goal", goal); put("tools", tools)
                put("interval_min", intervalMin); put("autonomous", if (autonomous) 1 else 0)
                put("created_at", System.currentTimeMillis()); put("last_run", 0L); put("status", "idle")
            })
            log(ctx, id, "Hired as ${role.ifBlank { "an assistant" }}.", false)
        } catch (e: Exception) { Log.w(TAG, "hire: ${e.message}") }
        return id
    }

    fun fire(ctx: Context, id: String) = try {
        db(ctx).delete("employees", "id=?", arrayOf(id)); db(ctx).delete("employee_log", "emp_id=?", arrayOf(id)); Unit
    } catch (e: Exception) { Unit }

    fun all(ctx: Context): List<Employee> = try {
        val out = ArrayList<Employee>()
        db(ctx).rawQuery("SELECT id,name,role,goal,tools,interval_min,autonomous,created_at,last_run,status FROM employees ORDER BY created_at DESC", null).use { c ->
            while (c.moveToNext()) out.add(Employee(
                c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4) ?: "",
                c.getInt(5), c.getInt(6) == 1, c.getLong(7), c.getLong(8), c.getString(9) ?: "idle"))
        }
        out
    } catch (e: Exception) { emptyList() }

    fun get(ctx: Context, id: String): Employee? = all(ctx).firstOrNull { it.id == id }
    fun count(ctx: Context): Int = try {
        db(ctx).rawQuery("SELECT count(*) FROM employees", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    } catch (e: Exception) { 0 }

    fun setStatus(ctx: Context, id: String, status: String, touchRun: Boolean = false) = try {
        db(ctx).update("employees", ContentValues().apply {
            put("status", status); if (touchRun) put("last_run", System.currentTimeMillis())
        }, "id=?", arrayOf(id)); Unit
    } catch (e: Exception) { Unit }

    /** Edit an agent's persona/instructions, and optionally its display name + role, after hiring. */
    fun edit(ctx: Context, id: String, goal: String, name: String = "", role: String = "") = try {
        db(ctx).update("employees", ContentValues().apply {
            put("goal", goal.trim())
            if (name.isNotBlank()) put("name", name.trim())
            if (role.isNotBlank()) put("role", role.trim())
        }, "id=?", arrayOf(id)); Unit
    } catch (e: Exception) { Unit }

    fun log(ctx: Context, empId: String, line: String, needsInput: Boolean) = try {
        db(ctx).insert("employee_log", null, ContentValues().apply {
            put("emp_id", empId); put("ts", System.currentTimeMillis()); put("line", line); put("needs_input", if (needsInput) 1 else 0)
        }); Unit
    } catch (e: Exception) { Unit }

    fun logFor(ctx: Context, empId: String, limit: Int = 30): List<LogLine> = try {
        val out = ArrayList<LogLine>()
        db(ctx).rawQuery("SELECT id,emp_id,ts,line,needs_input FROM employee_log WHERE emp_id=? ORDER BY ts DESC LIMIT ?",
            arrayOf(empId, limit.toString())).use { c ->
            while (c.moveToNext()) out.add(LogLine(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3) ?: "", c.getInt(4) == 1))
        }
        out
    } catch (e: Exception) { emptyList() }

    /** The whole team's most recent activity (for the office feed / speech bubbles). */
    fun recentActivity(ctx: Context, limit: Int = 20): List<LogLine> = try {
        val out = ArrayList<LogLine>()
        db(ctx).rawQuery("SELECT id,emp_id,ts,line,needs_input FROM employee_log ORDER BY ts DESC LIMIT ?",
            arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) out.add(LogLine(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3) ?: "", c.getInt(4) == 1))
        }
        out
    } catch (e: Exception) { emptyList() }
}
