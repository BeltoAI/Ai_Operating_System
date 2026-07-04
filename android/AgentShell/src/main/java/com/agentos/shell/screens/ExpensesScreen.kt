package com.agentos.shell.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.AgentLoop
import com.agentos.shell.tools.BrainContext
import com.agentos.shell.tools.ExpenseStore
import com.agentos.shell.tools.ImageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** An editable receipt awaiting save (from a photo). */
private data class Pending(val merchant: String, val total: String, val dateIso: String, val category: String,
                           val currency: String, val tax: Double, val itemsJson: String, val imagePath: String, val confidence: Double)

@Composable
fun ExpensesScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(ExpenseStore.all(ctx)) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var editId by remember { mutableStateOf<Long?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf("") }

    fun refresh() { list = ExpenseStore.all(ctx) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        if (bmp == null) return@rememberLauncherForActivityResult
        busy = true; status = "reading receipt…"; review = ""
        scope.launch {
            val (b64, path) = withContext(Dispatchers.IO) {
                val dir = File(ctx.filesDir, "receipts").apply { mkdirs() }
                val f = File(dir, "r_${System.currentTimeMillis()}.jpg")
                try { FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) } } catch (e: Exception) {}
                (ImageUtil.encodeBitmap(bmp) ?: "") to f.absolutePath
            }
            val r = if (b64.isNotBlank()) withContext(Dispatchers.IO) { AgentClient.extractReceipt(b64) } else null
            busy = false
            if (r == null) { status = "That didn't look like a receipt — try again." }
            else { status = ""; pending = Pending(r.merchant, "%.2f".format(r.total), r.dateIso.ifBlank { today() }, r.category, r.currency, r.tax, r.itemsJson, path, r.confidence) }
        }
    }

    Column(modifier) {
        ScreenHeader("Expenses", onBack)
        Spacer(Modifier.height(6.dp))

        // This-month totals header
        val (from, to) = remember(list) { ExpenseStore.rangeFor("this month") }
        val totals = remember(list) { ExpenseStore.totalsByCategory(ctx, from, to) }
        val monthTotal = totals.values.sum()
        Text("This month", fontSize = T.caption, color = T.inkFaint)
        Text("$" + "%.2f".format(monthTotal), fontSize = 34.sp, color = T.accent)
        if (totals.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(totals.entries.joinToString("  ·  ") { "${it.key} $${"%.2f".format(it.value)}" }, fontSize = T.caption, color = T.inkFaint)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("＋ Snap receipt", fontSize = T.small, color = Color.White,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent).clickable { camera.launch(null) }.padding(horizontal = 16.dp, vertical = 9.dp))
            Spacer(Modifier.width(12.dp))
            Text(if (busy) "…" else "Review this month", fontSize = T.small, color = T.accent,
                modifier = Modifier.clickable(enabled = !busy) {
                    busy = true; review = ""
                    scope.launch {
                        val ctxStr = withContext(Dispatchers.IO) { BrainContext.profileBlock(ctx) }
                        val out = withContext(Dispatchers.IO) { AgentLoop.run(ctx, "Give me this month's spending review from my expenses, then offer to make a sheet.", ctxStr, emptyList(), userInitiated = true) }
                        review = out.answer; busy = false
                    }
                }.padding(6.dp))
        }
        if (status.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(status, fontSize = T.caption, color = T.accent) }
        if (review.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(T.bgElevated).padding(14.dp)) {
                Text(review, fontSize = T.small, color = T.ink)
            }
        }

        // Editable confirm card for a freshly-snapped receipt
        pending?.let { p ->
            Spacer(Modifier.height(12.dp))
            EditCard("Confirm receipt", p.merchant, p.total, p.dateIso, p.category,
                onSave = { m, t, d, c ->
                    val id = ExpenseStore.record(ctx, m, d, t.toDoubleOrNull() ?: 0.0, p.currency, p.tax, c, p.itemsJson, "camera", p.imagePath, "", p.confidence)
                    status = if (id > 0) "Saved ✓" else "Already logged (duplicate)."
                    pending = null; refresh()
                }, onCancel = { pending = null })
        }

        Spacer(Modifier.height(14.dp))
        if (list.isEmpty()) {
            Text("No expenses yet. Snap a receipt, or connect Gmail to pull email receipts automatically.", fontSize = T.small, color = T.inkSoft)
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { e ->
                if (editId == e.id) {
                    Spacer(Modifier.height(8.dp))
                    EditCard("Edit expense", e.merchant, "%.2f".format(e.total), monthDay(e.ts, true), e.category,
                        onSave = { m, t, d, c -> ExpenseStore.update(ctx, e.id, m, t.toDoubleOrNull() ?: e.total, isoToTs(d, e.ts), c); editId = null; refresh() },
                        onDelete = { ExpenseStore.delete(ctx, e.id); editId = null; refresh() },
                        onCancel = { editId = null })
                } else {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.bgElevated).clickable { editId = e.id }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.merchant, fontSize = T.body, color = T.ink, maxLines = 1)
                            Text("${e.category} · ${monthDay(e.ts, false)} · ${e.source}", fontSize = T.caption, color = T.inkFaint)
                        }
                        Text("$${"%.2f".format(e.total)}", fontSize = T.body, color = T.ink)
                    }
                }
            }
        }
    }
}

@Composable
private fun EditCard(title: String, merchant0: String, total0: String, date0: String, cat0: String,
                     onSave: (String, String, String, String) -> Unit, onDelete: (() -> Unit)? = null, onCancel: () -> Unit) {
    var m by remember { mutableStateOf(merchant0) }
    var t by remember { mutableStateOf(total0) }
    var d by remember { mutableStateOf(date0) }
    var c by remember { mutableStateOf(ExpenseStore.normalizeCategory(cat0)) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(T.bgElevated).padding(14.dp)) {
        Text(title, fontSize = T.caption, color = T.inkFaint)
        Spacer(Modifier.height(6.dp))
        field(m, "Merchant") { m = it }
        Spacer(Modifier.height(6.dp))
        Row {
            Box(Modifier.weight(1f)) { field(t, "Total") { t = it } }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) { field(d, "Date (YYYY-MM-DD)") { d = it } }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            ExpenseStore.CATEGORIES.forEach { cat ->
                Text(cat, fontSize = T.caption, color = if (c == cat) Color.White else T.inkSoft,
                    modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(999.dp))
                        .background(if (c == cat) T.accent else T.hairline).clickable { c = cat }.padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Save", fontSize = T.small, color = Color.White,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent).clickable { onSave(m, t, d, c) }.padding(horizontal = 18.dp, vertical = 8.dp))
            Spacer(Modifier.width(12.dp))
            if (onDelete != null) { Text("Delete", fontSize = T.small, color = T.danger, modifier = Modifier.clickable { onDelete() }.padding(6.dp)); Spacer(Modifier.width(8.dp)) }
            Text("Cancel", fontSize = T.small, color = T.inkSoft, modifier = Modifier.clickable { onCancel() }.padding(6.dp))
        }
    }
}

@Composable
private fun field(value: String, hint: String, onChange: (String) -> Unit) {
    BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(color = T.ink, fontSize = T.small),
        cursorBrush = SolidColor(T.accent),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(T.bg).padding(10.dp),
        decorationBox = { inner -> if (value.isEmpty()) Text(hint, fontSize = T.small, color = T.inkFaint); inner() })
}

private fun today() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
private fun monthDay(ts: Long, iso: Boolean) = java.text.SimpleDateFormat(if (iso) "yyyy-MM-dd" else "MMM d", java.util.Locale.getDefault()).format(java.util.Date(ts))
private fun isoToTs(iso: String, fallback: Long): Long =
    try { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { isLenient = true }.parse(iso)?.time ?: fallback } catch (e: Exception) { fallback }
