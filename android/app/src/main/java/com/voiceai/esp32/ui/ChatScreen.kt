package com.voiceai.esp32.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voiceai.esp32.MainViewModel
import com.voiceai.esp32.data.Message
import com.voiceai.esp32.data.Mode
import com.voiceai.esp32.data.Route
import kotlinx.coroutines.delay
import java.util.Calendar

@Composable
fun ChatScreen(vm: MainViewModel, onMenu: () -> Unit, onMic: () -> Unit, draft: String?, onDraftUsed: () -> Unit) {
    val msgs = vm.messages
    val list = rememberLazyListState()
    var input by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(draft) {
        if (draft != null) { input = draft; onDraftUsed() }
    }

    // keep the newest text in view while streaming
    val itemCount = msgs.size + (if (vm.busy || vm.error != null) 1 else 0)
    LaunchedEffect(itemCount, vm.liveText.length / 40) {
        if (itemCount > 0) list.animateScrollToItem(itemCount - 1, scrollOffset = 100_000)
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar(vm, onMenu)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (msgs.isEmpty() && !vm.busy) {
                EmptyState(vm, onPick = { vm.send(it) }, onMic = onMic)
            } else {
                LazyColumn(
                    state = list,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(msgs, key = { i, m -> "${m.ts}-$i" }) { i, m ->
                        if (m.role == "user") UserBubble(m.content)
                        else AiMessage(vm, m, isLast = i == msgs.lastIndex && !vm.busy)
                    }
                    if (vm.busy) item(key = "live") { LiveAnswer(vm) }
                    else if (vm.error != null) item(key = "error") { ErrorItem(vm.error!!) { vm.retry() } }
                }
            }
        }

        Composer(
            vm = vm,
            text = input,
            onText = { input = it },
            onSend = { vm.send(input); input = "" },
            onMic = onMic,
        )
    }
}

@Composable
private fun TopBar(vm: MainViewModel, onMenu: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, "Chats") }
        Row(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .clickable { vm.screen = com.voiceai.esp32.Screen.SETTINGS }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(ok = vm.aiReachable, warn = vm.deviceOnline || vm.pcOnline)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    vm.settings.model, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp),
                )
                Text(
                    (if (vm.aiReachable) "Connected · " else "Offline · ") + vm.settings.mode.label,
                    fontSize = 11.sp,
                    color = if (vm.aiReachable) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Outlined.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { vm.screen = com.voiceai.esp32.Screen.USAGE }) { Icon(Icons.Outlined.BarChart, "Usage") }
        IconButton(onClick = { vm.newChat() }) { Icon(Icons.Outlined.EditNote, "New chat") }
    }
}

private data class Idea(val icon: ImageVector, val title: String, val sub: String, val color: Color, val prompt: String)

private val IDEAS = listOf(
    Idea(Icons.Outlined.RocketLaunch, "Space fact", "Something amazing about the universe", Color(0xFF7FA8FF), "Tell me a fun fact about space."),
    Idea(Icons.Outlined.AutoStories, "Word of the day", "Learn a new English word", Color(0xFFB38CFF), "Give me an English word of the day with its meaning and an example."),
    Idea(Icons.Outlined.Eco, "Greenhouse", "How do greenhouses keep plants warm?", Color(0xFF6FD08C), "Explain how a greenhouse keeps plants warm."),
    Idea(Icons.Outlined.EmojiEmotions, "Make me laugh", "A short, clean joke", Color(0xFFFFC266), "Tell me a short joke."),
)

private fun headlines(): List<String> {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val first = when {
        h < 5 -> "Night owl mode on 🦉"
        h < 12 -> "Fresh morning, fresh questions ☀️"
        h < 17 -> "Curious this afternoon? 🚀"
        h < 21 -> "Evening brain, let's go ✨"
        else -> "Late-night questions welcome 🌙"
    }
    return listOf(first, "Ask me anything, I'll say it out loud 🔊", "Your own AI, running on your PC 💡", "Tap the mic and just talk 🎙️")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(vm: MainViewModel, onPick: (String) -> Unit, onMic: () -> Unit) {
    val lines = remember { headlines() }
    var idx by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { delay(3500); idx = (idx + 1) % lines.size }
    }
    val s = vm.settings
    val needsSetup = s.pcHost.isBlank() || (s.route.onSpeaker && s.espHost.isBlank())

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandHeader()
        Spacer(Modifier.height(28.dp))
        Crossfade(targetState = idx, animationSpec = tween(600), label = "headline") { i ->
            GradientText(lines[i], 26.sp, Modifier.fillMaxWidth().heightIn(min = 70.dp))
        }
        Text(
            "Type a question or tap the mic. The answer shows here and plays on your ESP32 speaker.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, fontSize = 14.sp, lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(18.dp))

        if (needsSetup) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Warn.copy(alpha = 0.14f))
                    .clickable { vm.screen = com.voiceai.esp32.Screen.SETTINGS }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Settings, null, tint = Warn)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Finish setup", fontWeight = FontWeight.SemiBold)
                    Text("Add your PC and ESP32 addresses in Settings", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(14.dp))
        }

        // 2 x 2 idea cards
        IDEAS.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { idea ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                            .clickable { onPick(idea.prompt) }
                            .padding(14.dp),
                    ) {
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(idea.color.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(idea.icon, null, tint = idea.color, modifier = Modifier.size(20.dp)) }
                        Spacer(Modifier.height(10.dp))
                        Text(idea.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(idea.sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp, minLines = 2)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Brush.linearGradient(BrandGradient))
                .clickable(onClick = onMic)
                .padding(horizontal = 26.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Mic, null, tint = Color.White)
            Spacer(Modifier.width(10.dp))
            Text("Tap to talk", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text, fontSize = 16.sp, lineHeight = 22.sp, color = Color.White,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF1C5C86), Color(0xFF1D8C9A))))
                .padding(horizontal = 15.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun AiHeader(name: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Logo(20.dp)
        Spacer(Modifier.width(8.dp))
        Text(name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun AiMessage(vm: MainViewModel, m: Message, isLast: Boolean) {
    val clip = LocalClipboardManager.current
    var details by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        AiHeader(m.model ?: "AI")
        MarkdownText(m.content)
        Row(Modifier.padding(top = 4.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SmallAction(Icons.Outlined.ContentCopy, "Copy") { clip.setText(AnnotatedString(m.content)); vm.toast = "Copied" }
            SmallAction(Icons.Outlined.Speaker, "Play on speaker") { vm.speakOnEsp(m.content) }
            SmallAction(Icons.Outlined.PhoneAndroid, "Read on phone") { vm.speakOnPhone(m.content) }
            if (isLast) SmallAction(Icons.Outlined.Refresh, "Regenerate") { vm.regenerate() }
            m.usage?.let { u ->
                Row(
                    Modifier
                        .padding(start = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { details = !details }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    UsageBit(fmt(u.prompt), "in")
                    UsageBit(fmt(u.completion), "out")
                    Text("%.1f tok/s".format(u.tokensPerSec), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    Text(fmtSec(u.totalMs), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        if (details && m.usage != null) {
            val u = m.usage
            Text(
                "Input ${fmt(u.prompt)} · Output ${fmt(u.completion)} · Total ${fmt(u.total)} tokens\n" +
                    "Speed %.1f tok/s · Time %s".format(u.tokensPerSec, fmtSec(u.totalMs)) +
                    (if (u.loadMs > 300) " · Model load ${fmtSec(u.loadMs)}" else ""),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(10.dp),
            )
        }
    }
}

@Composable
private fun UsageBit(value: String, label: String) {
    Row {
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(" $label", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun SmallAction(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun LiveAnswer(vm: MainViewModel) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(100) }
    }
    val secs = (now - vm.startedAt) / 1000.0
    Column(Modifier.fillMaxWidth()) {
        AiHeader(vm.settings.model)
        if (vm.liveText.isNotEmpty()) {
            MarkdownText(vm.liveText + " ▍")
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ThinkingDots()
                Spacer(Modifier.width(10.dp))
                Text(
                    if (secs > 8) "Thinking · %.0fs (first answer loads the model)".format(secs) else "Thinking · %.1fs".format(secs),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun ThinkingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(
                initialValue = 0.25f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.size(7.dp).scale(0.7f + a * 0.3f).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = a)))
        }
    }
}

@Composable
private fun ErrorItem(text: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        AiHeader("Error")
        Text(text, color = MaterialTheme.colorScheme.error, fontSize = 15.sp)
        TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) { Text("Try again") }
    }
}

@Composable
private fun Composer(vm: MainViewModel, text: String, onText: (String) -> Unit, onSend: () -> Unit, onMic: () -> Unit) {
    val s = vm.settings
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
        // where answers play + device state
        Row(Modifier.padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = when (s.route) {
                Route.SPEAKER -> Icons.Outlined.Speaker
                Route.PHONE -> Icons.Outlined.PhoneAndroid
                Route.BOTH -> Icons.AutoMirrored.Outlined.VolumeUp
                Route.SILENT -> Icons.AutoMirrored.Outlined.VolumeOff
            }
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                    .clickable {
                        val next = Route.entries[(s.route.ordinal + 1) % Route.entries.size]
                        vm.updateSettings(s.copy(route = next))
                        vm.toast = "Answers: ${next.label}"
                    }
                    .padding(horizontal = 11.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(s.route.label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(10.dp))
            val state = when {
                vm.device?.state == "speaking" && vm.deviceOnline -> "Speaking on speaker"
                s.mode == Mode.DIRECT && !vm.pcOnline -> "PC / Ollama offline"
                s.mode == Mode.ESP32 && !vm.deviceOnline -> "ESP32 offline"
                s.mode == Mode.ESP32 && vm.device?.ollamaOnline == false -> "ESP32 can't reach Ollama"
                s.route.onSpeaker && s.espHost.isBlank() -> "Speaker not set up"
                else -> ""
            }
            Text(state, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state == "Speaking on speaker") {
                Text(
                    "Stop", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.stopAudio() }.padding(6.dp),
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(26.dp))
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            BasicTextField(
                value = text, onValueChange = onText,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 6,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp).heightIn(min = 22.dp),
                decorationBox = { inner ->
                    if (text.isEmpty()) Text("Message VoiceAI…", color = MaterialTheme.colorScheme.outline, fontSize = 16.sp)
                    inner()
                },
            )
            Box(
                Modifier.size(42.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(enabled = !vm.busy, onClick = onMic),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Mic, "Speak", tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.width(4.dp))
            val canSend = text.isNotBlank() && !vm.busy
            Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        if (vm.busy || canSend) Brush.linearGradient(BrandGradient)
                        else Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant))
                    )
                    .clickable(enabled = vm.busy || canSend) { if (vm.busy) vm.stop() else onSend() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (vm.busy) Icons.Filled.Stop else Icons.Filled.ArrowUpward,
                    if (vm.busy) "Stop" else "Send",
                    tint = if (vm.busy || canSend) Color.White else MaterialTheme.colorScheme.outline,
                )
            }
        }

        // context / token line
        val last = vm.messages.lastOrNull { it.usage != null }?.usage
        val ctx = vm.device?.numCtx?.takeIf { it > 0 } ?: 4096
        val used = last?.total ?: 0
        val total = vm.current?.tokens ?: 0
        Row(Modifier.padding(start = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(64.dp).height(4.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(
                    Modifier
                        .fillMaxWidth((used.toFloat() / ctx).coerceIn(0f, 1f))
                        .height(4.dp)
                        .background(if (used > ctx * 0.75) Warn else MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                if (total > 0) "${fmt(used)} / ${fmt(ctx)} context · ${fmt(total)} tokens in this chat"
                else "0 tokens in this chat",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
