package com.voiceai.esp32.ui

import androidx.compose.foundation.Image
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

/** Renders paragraphs, bullet / numbered lists, code blocks, **bold** and `code`. */
@Composable
fun MarkdownText(text: String, color: Color = MaterialTheme.colorScheme.onBackground) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val body = MaterialTheme.typography.bodyLarge.copy(color = color, lineHeight = 24.sp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val parts = text.split("```")
        parts.forEachIndexed { i, part ->
            if (i % 2 == 1) {
                Text(
                    part.substringAfter('\n', part).trimEnd(),
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = color,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(codeBg)
                        .padding(12.dp),
                )
            } else {
                part.trim('\n').split('\n').filter { it.isNotBlank() }.forEach { line ->
                    val bullet = Regex("""^\s*[-*•]\s+(.*)""").find(line)
                    val number = Regex("""^\s*(\d+)[.)]\s+(.*)""").find(line)
                    when {
                        bullet != null -> Row {
                            Text("•", style = body, modifier = Modifier.width(18.dp))
                            Text(inlineMarkdown(bullet.groupValues[1], codeBg), style = body)
                        }
                        number != null -> Row {
                            Text(number.groupValues[1] + ".", style = body, modifier = Modifier.width(24.dp))
                            Text(inlineMarkdown(number.groupValues[2], codeBg), style = body)
                        }
                        else -> Text(inlineMarkdown(line.trim(), codeBg), style = body)
                    }
                }
            }
        }
    }
}

fun fmt(n: Number): String = "%,d".format(n.toLong())
fun fmtSec(ms: Long): String = if (ms < 10_000) "%.1fs".format(ms / 1000.0) else "${ms / 1000}s"

// ------------------------------------------------------------------ brand --
const val CREDITS = "Made by Mostafa Mesbah Aabedi & Sebghatullah Haidari"
const val SCHOOL = "Afghan-Turk Maarif Schools · Herat Boys HS"

@Composable
fun SchoolLogo(size: Dp) {
    Image(
        painter = painterResource(R.drawable.school_logo),
        contentDescription = "Afghan-Turk Maarif Schools Herat Boys HS",
        modifier = Modifier.size(size),
    )
}

/** Logo + app name + credits, shown at the top of the app. */
@Composable
fun BrandHeader(modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(Navy, Color(0xFF1C5C86), Color(0xFF1D8C9A)),
                )
            )
            .padding(horizontal = 14.dp, vertical = if (compact) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.clip(CircleShape).background(Color.White).padding(2.dp)) {
            SchoolLogo(if (compact) 40.dp else 54.dp)
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text("VoiceAI", color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (compact) 17.sp else 20.sp)
            Text(CREDITS, color = Color.White.copy(alpha = 0.92f), fontSize = 12.sp, lineHeight = 16.sp)
            if (!compact) Text(SCHOOL, color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
        }
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
