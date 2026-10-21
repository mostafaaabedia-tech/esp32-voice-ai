package com.voiceai.esp32.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontStyle
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import com.voiceai.esp32.R
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The app's little mark: terracotta rounded square with a dark dot. */
@Composable
fun Logo(size: Dp = 22.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(Brush.linearGradient(BrandGradient)),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size * 0.36f).clip(CircleShape).background(MaterialTheme.colorScheme.background))
    }
}

@Composable
fun StatusDot(ok: Boolean, warn: Boolean = false) {
    val c = when {
        ok -> MaterialTheme.colorScheme.tertiary
        warn -> Warn
        else -> MaterialTheme.colorScheme.error
    }
    Box(Modifier.size(8.dp).clip(CircleShape).background(c))
}

@Composable
fun SectionTitle(text: String, note: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (note != null) Text(note, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
            .padding(14.dp),
        content = content,
    )
}

/** Simple segmented control: a row of options, one selected. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.background)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                    .clickable { onSelect(o) }
                    .padding(vertical = 9.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(o), fontSize = 13.sp, maxLines = 1,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// --------------------------------------------------------------- markdown --
private val inlineRegex = Regex("""\*\*(.+?)\*\*|`([^`]+)`|(?<![*\w])\*(?!\s)(.+?)\*(?!\w)""")

fun inlineMarkdown(text: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in inlineRegex.findAll(text)) {
        append(text.substring(last, m.range.first))
        val (bold, code, italic) = m.destructured
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(" $code ") }
            else -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(italic) }
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

private sealed class Block {
    data class Code(val lang: String, val code: String) : Block()
    data class Quote(val text: String) : Block()
    data class Heading(val text: String) : Block()
    data class Bullet(val text: String) : Block()
    data class Numbered(val n: String, val text: String) : Block()
    data class Para(val text: String) : Block()
}

private fun parseBlocks(src: String): List<Block> {
    val out = mutableListOf<Block>()
    val lines = src.replace("\r", "").split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        when {
            t.startsWith("```") -> {
                val lang = t.removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i]); i++
                }
                out += Block.Code(lang, code.toString())   // unfinished block while streaming is fine
            }
            t.startsWith(">") -> {
                val q = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    if (q.isNotEmpty()) q.append('\n')
                    q.append(lines[i].trim().removePrefix(">").trim()); i++
                }
                out += Block.Quote(q.toString()); continue
            }
            t.startsWith("#") -> out += Block.Heading(t.trimStart('#').trim())
            Regex("""^[-*•]\s+.*""").matches(t) -> out += Block.Bullet(t.drop(1).trim())
            Regex("""^\d+[.)]\s+.*""").matches(t) -> {
                val m = Regex("""^(\d+)[.)]\s+(.*)""").find(t)!!
                out += Block.Numbered(m.groupValues[1], m.groupValues[2])
            }
            t.isNotEmpty() -> out += Block.Para(t)
        }
        i++
    }
    return out
}

/**
 * Renders the AI's markdown: paragraphs, headings, lists, quotes and code blocks.
 * Code blocks and quotes get their own Copy button.
 */
@Composable
fun MarkdownText(
    text: String,
    color: Color = MaterialTheme.colorScheme.onBackground,
    scale: Float = 1f,
    onCopy: ((String) -> Unit)? = null,
) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val body = MaterialTheme.typography.bodyLarge.copy(color = color, fontSize = 16.sp * scale, lineHeight = 24.sp * scale)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parseBlocks(text).forEach { b ->
            when (b) {
                is Block.Code -> CodeBlock(b.lang, b.code, scale, onCopy)
                is Block.Quote -> QuoteBlock(b.text, body, codeBg, onCopy)
                is Block.Heading -> Text(
                    inlineMarkdown(b.text, codeBg), style = body.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp * scale),
                )
                is Block.Bullet -> Row {
                    Text("•", style = body.copy(color = MaterialTheme.colorScheme.primary), modifier = Modifier.width(18.dp))
                    Text(inlineMarkdown(b.text, codeBg), style = body)
                }
                is Block.Numbered -> Row {
                    Text(b.n + ".", style = body.copy(color = MaterialTheme.colorScheme.primary), modifier = Modifier.width(26.dp))
                    Text(inlineMarkdown(b.text, codeBg), style = body)
                }
                is Block.Para -> Text(inlineMarkdown(b.text, codeBg), style = body)
            }
        }
    }
}

@Composable
private fun CodeBlock(lang: String, code: String, scale: Float, onCopy: ((String) -> Unit)?) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0A1420))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF13233A)).padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(lang.ifBlank { "code" }, color = Color(0xFF9DB1C5), fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (onCopy != null) CopyChip { onCopy(code) }
        }
        Text(
            code,
            fontFamily = FontFamily.Monospace, fontSize = 13.sp * scale, lineHeight = 19.sp * scale,
            color = Color(0xFFD6E4F0),
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
        )
    }
}

@Composable
private fun QuoteBlock(text: String, body: TextStyle, codeBg: Color, onCopy: ((String) -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        Text(
            inlineMarkdown(text, codeBg), style = body.copy(fontStyle = FontStyle.Italic),
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp),
        )
        if (onCopy != null) Box(Modifier.padding(top = 4.dp, end = 4.dp)) { CopyChip(iconOnly = true) { onCopy(text) } }
    }
}

@Composable
fun CopyChip(iconOnly: Boolean = false, onClick: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick(); copied = true }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy, "Copy",
            tint = if (copied) MaterialTheme.colorScheme.tertiary else Color(0xFF9DB1C5),
            modifier = Modifier.size(15.dp),
        )
        if (!iconOnly) {
            Spacer(Modifier.width(5.dp))
            Text(if (copied) "Copied" else "Copy", fontSize = 12.sp, color = if (copied) MaterialTheme.colorScheme.tertiary else Color(0xFF9DB1C5))
        }
    }
}

fun fmt(n: Number): String = "%,d".format(n.toLong())
fun fmtSec(ms: Long): String = if (ms < 10_000) "%.1fs".format(ms / 1000.0) else "${ms / 1000}s"

// ------------------------------------------------------------------ brand --
const val CREDITS = "Made by Mostafa Mesbah Aabedi & Sebghatullah Haidari"
/** App name + credits, shown at the top of the app. */
@Composable
fun BrandHeader(modifier: Modifier = Modifier, compact: Boolean = false) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(Navy, Color(0xFF1C5C86), Color(0xFF1D8C9A)),
                )
            )
            .padding(horizontal = 16.dp, vertical = if (compact) 10.dp else 16.dp),
    ) {
        Text("VoiceAI", color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (compact) 17.sp else 22.sp)
        Text(CREDITS, color = Color.White.copy(alpha = 0.92f), fontSize = if (compact) 12.sp else 13.sp, lineHeight = 17.sp)
    }
}

/** Text painted with the teal -> blue logo gradient. */
@OptIn(ExperimentalTextApi::class)
@Composable
fun GradientText(text: String, fontSize: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        textAlign = TextAlign.Center,
        style = TextStyle(
            brush = Brush.linearGradient(listOf(Teal, Ocean, Color(0xFF8B5CF6))),
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            lineHeight = fontSize * 1.25f,
        ),
    )
}
