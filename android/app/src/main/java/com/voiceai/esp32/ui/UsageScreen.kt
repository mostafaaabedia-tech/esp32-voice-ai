package com.voiceai.esp32.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voiceai.esp32.MainViewModel
import java.util.Calendar

@Composable
fun UsageScreen(vm: MainViewModel, onBack: () -> Unit) {
    var confirmReset by remember { mutableStateOf(false) }
    val log = vm.usage.toList()
    val p = log.sumOf { it.prompt.toLong() }
    val c = log.sumOf { it.completion.toLong() }
    val t = p + c
    val n = log.size
    val avgTps = if (n > 0) log.sumOf { it.tokensPerSec } / n else 0.0
    val avgMs = if (n > 0) log.sumOf { it.totalMs } / n else 0L
    val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
    val todayTok = log.filter { it.ts >= today }.sumOf { (it.prompt + it.completion).toLong() }
    val inCol = MaterialTheme.colorScheme.secondary
    val outCol = MaterialTheme.colorScheme.primary

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SheetHeader("Usage", onBack) {
            TextButton(onClick = { confirmReset = true }) { Text("Reset") }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
            AppCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text("Total tokens", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(fmt(t), fontSize = 40.sp, fontWeight = FontWeight.Bold)
                        Text("${fmt(n)} answers · on this phone", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Text(
                        "Free · local", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))
                val frac = if (t > 0) p.toFloat() / t else 0.5f
                Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp))) {
                    if (frac > 0f) Box(Modifier.weight(frac.coerceAtLeast(0.001f)).fillMaxSize().background(inCol))
                    if (frac < 1f) Box(Modifier.weight((1f - frac).coerceAtLeast(0.001f)).fillMaxSize().background(outCol))
                }
                Spacer(Modifier.height(8.dp))
                Legend(listOf("Input ${fmt(p)}" to inCol, "Output ${fmt(c)}" to outCol))
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Today", fmt(todayTok), "tokens", Modifier.weight(1f))
                Stat("Requests", fmt(n), "answers", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Avg speed", "%.1f".format(avgTps), "tokens / second", Modifier.weight(1f))
                Stat("Avg response", "%.1fs".format(avgMs / 1000.0), "question to answer", Modifier.weight(1f))
            }

            Spacer(Modifier.height(10.dp))
            val recent = log.takeLast(24)
            AppCard {
                Text("Tokens per answer", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("Last ${recent.size} answers", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))
                if (recent.isEmpty()) EmptyChart() else {
                    val max = recent.maxOf { it.prompt + it.completion }.coerceAtLeast(1)
                    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                        val bw = size.width / recent.size
                        val w = (bw * 0.7f).coerceAtMost(28.dp.toPx())
                        recent.forEachIndexed { i, r ->
                            val hp = size.height * r.prompt / max
                            val hc = size.height * r.completion / max
                            val x = i * bw + (bw - w) / 2
                            drawRoundRect(outCol, Offset(x, size.height - hp - hc), Size(w, hc), CornerRadius(3f, 3f))
                            drawRoundRect(inCol, Offset(x, size.height - hp), Size(w, hp), CornerRadius(3f, 3f))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Legend(listOf("Input" to inCol, "Output" to outCol))
                }
            }

            Spacer(Modifier.height(10.dp))
            AppCard {
                Text("Generation speed", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("Tokens per second for each answer", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))
                if (recent.size < 2) EmptyChart("Needs two answers") else {
                    val vals = recent.map { it.tokensPerSec.toFloat() }
                    val max = (vals.max() * 1.15f).coerceAtLeast(1f)
                    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                        val pts = vals.mapIndexed { i, v ->
                            Offset(size.width * i / (vals.size - 1), size.height - size.height * v / max)
                        }
                        val line = Path().apply { pts.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
                        val area = Path().apply {
                            addPath(line); lineTo(pts.last().x, size.height); lineTo(pts.first().x, size.height); close()
                        }
                        drawPath(area, Brush.verticalGradient(listOf(outCol.copy(alpha = 0.25f), Color.Transparent)))
                        drawPath(line, outCol, style = Stroke(width = 2.dp.toPx()))
                        pts.forEach { drawCircle(outCol, 3.dp.toPx(), it) }
                    }
                    Text("max %.1f tok/s".format(vals.max()), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                }
            }

            Spacer(Modifier.height(10.dp))
            AppCard {
                val last = vm.messages.lastOrNull { it.usage != null }?.usage
                val ctx = vm.device?.numCtx?.takeIf { it > 0 } ?: 4096
                val used = last?.total ?: 0
                Text("Context window · current chat", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("How much of the model's memory the last answer used", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(
                        Modifier.fillMaxWidth((used.toFloat() / ctx).coerceIn(0f, 1f)).height(10.dp)
                            .background(Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.tertiary, Warn))),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text("${fmt(used)} of ${fmt(ctx)} tokens", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            vm.device?.takeIf { vm.deviceOnline }?.let { d ->
                SectionTitle("ESP32 since boot")
                ListCard(
                    listOf(
                        "Requests" to fmt(d.sessionRequests),
                        "Input tokens" to fmt(d.sessionPrompt),
                        "Output tokens" to fmt(d.sessionCompletion),
                    )
                )
            }

            val byChat = vm.chats.map { it.title to it.tokens }.filter { it.second > 0 }.sortedByDescending { it.second }.take(6)
            if (byChat.isNotEmpty()) {
                SectionTitle("Top chats")
                ListCard(byChat.map { it.first to fmt(it.second) })
            }
            val byModel = log.groupBy { it.model }.map { (m, l) -> m to l.sumOf { (it.prompt + it.completion).toLong() } }
            if (byModel.isNotEmpty()) {
                SectionTitle("By model")
                ListCard(byModel.map { it.first to fmt(it.second) })
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Counts come from Ollama (prompt_eval_count and eval_count). Running on your own PC costs nothing per token.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset usage?") },
            text = { Text("This clears the usage stats on this phone. Chats are kept.") },
            confirmButton = { TextButton(onClick = { vm.resetUsage(); confirmReset = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Stat(k: String, v: String, s: String, modifier: Modifier) {
    AppCard(modifier) {
        Text(k, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(s, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun Legend(items: List<Pair<String, Color>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
                Spacer(Modifier.width(6.dp))
                Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EmptyChart(text: String = "No answers yet") {
    Box(Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun ListCard(rows: List<Pair<String, String>>) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface),
    ) {
        rows.forEachIndexed { i, (k, v) ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(k, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                Text(v, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
