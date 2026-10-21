package com.voiceai.esp32.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.voiceai.esp32.MainViewModel
import com.voiceai.esp32.Screen
import java.time.LocalTime

private val Pink = Color(0xFFFF6F9F)
private val Lavender = Color(0xFFB9A6FF)

/** Home screen: your AI companion, full size, talking with you. */
@Composable
fun CompanionScreen(
    vm: MainViewModel,
    listening: Boolean,
    heard: String,
    level: Float,
    onMenu: () -> Unit,
    onMic: () -> Unit,
    onStopMic: () -> Unit,
) {
    val c = vm.companion
    val s = vm.settings

    // run her brain only while this screen is visible and the app is open
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) c.setActive(true)
            if (e == Lifecycle.Event.ON_PAUSE) c.setActive(false)
        }
        owner.lifecycle.addObserver(obs)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) c.setActive(true)
        onDispose { owner.lifecycle.removeObserver(obs); c.setActive(false) }
    }

    val hour = LocalTime.now().hour
    val sky = when (hour) {
        in 6..16 -> listOf(Color(0xFF2B2F6B), Color(0xFF5B4BA0), Color(0xFFB07BC6))
        in 17..19 -> listOf(Color(0xFF241E4E), Color(0xFF6B3F86), Color(0xFFE08AA4))
        else -> listOf(Color(0xFF0A0F24), Color(0xFF1B2050), Color(0xFF3A2F6E))
    }
    var typing by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(sky))) {
        Column(Modifier.fillMaxSize()) {
            // ---------------------------------------------------- top bar
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, "Menu", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(s.companionName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    val dot = if (vm.pcOnline) Color(0xFF7CF0A6) else Color(0xFFFFB86B)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                c.thinking -> "thinking…"
                                c.speaking -> "talking"
                                listening -> "listening to you"
                                !vm.pcOnline -> "brain offline (PC)"
                                else -> "feeling ${c.emotion}"
                            },
                            color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp,
                        )
                    }
                }
                IconButton(onClick = { showLog = true }) { Icon(Icons.Outlined.History, "Recent talk", tint = Color.White) }
                IconButton(onClick = { vm.home = Screen.CHAT; vm.screen = Screen.CHAT }) {
                    Icon(Icons.AutoMirrored.Outlined.Chat, "Text chat", tint = Color.White)
                }
                IconButton(onClick = { vm.screen = Screen.SETTINGS }) { Icon(Icons.Outlined.Settings, "Settings", tint = Color.White) }
            }

            // ---------------------------------------------------- avatar
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AvatarView(vm, Modifier.fillMaxSize())

                // speech bubble
                val text = when {
                    listening -> heard
                    else -> c.bubble
                }
                Bubble(text, listening, s.textScale, Modifier.align(Alignment.TopCenter).padding(horizontal = 18.dp, vertical = 6.dp))

                // little chips: sensors and her decisions
                Column(
                    Modifier.align(Alignment.BottomStart).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (c.status.isNotBlank() && c.status != "listening…" && c.status != "thinking…") Chip(c.status)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        c.sensors?.let { se ->
                            if (se.ultrasonic) Chip(if (se.person && se.distanceCm > 0) "👤 ${se.distanceCm} cm" else "👤 nobody")
                            Chip("♥ ${se.love}%")
                            if (se.petting) Chip("being petted")
                        }
                    }
                }
            }

            // ---------------------------------------------------- controls
            if (typing) {
                var msg by remember { mutableStateOf("") }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = msg, onValueChange = { msg = it }, modifier = Modifier.weight(1f),
                        placeholder = { Text("Say something to ${s.companionName}…", color = Color.White.copy(alpha = 0.6f)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (msg.isNotBlank()) { c.userSaid(msg); msg = "" } }),
                        shape = RoundedCornerShape(24.dp),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = Lavender, unfocusedBorderColor = Color.White.copy(alpha = 0.4f),
                            cursorColor = Lavender,
                        ),
                    )
                    IconButton(onClick = { if (msg.isNotBlank()) { c.userSaid(msg); msg = "" } }) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.White)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                RoundButton(Icons.Outlined.Keyboard, "Type", typing) { typing = !typing }
                MicButton(listening, level, talking = c.speaking || c.thinking) {
                    when {
                        listening -> onStopMic()
                        c.speaking || c.thinking -> c.stopTalking()
                        else -> onMic()
                    }
                }
                RoundButton(Icons.Outlined.Loop, "Talk mode", s.talkMode) {
                    vm.updateSettings(s.copy(talkMode = !s.talkMode))
                    vm.toast = if (!s.talkMode) "Talk mode on: the mic opens again after she answers" else "Talk mode off"
                }
            }
            Text(
                when {
                    listening -> "Tap to stop"
                    c.speaking || c.thinking -> "Tap to interrupt"
                    else -> "Tap to talk · pat her head · poke her cheek"
                },
                color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            )
        }
    }

    if (showLog) {
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text("Talking with ${s.companionName}") },
            text = {
                val st = rememberLazyListState(initialFirstVisibleItemIndex = (c.talk.size - 1).coerceAtLeast(0))
                LazyColumn(state = st, modifier = Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (c.talk.isEmpty()) item { Text("Nothing yet. Say hi!") }
                    items(c.talk.toList()) { (role, text) ->
                        val me = role == "user"
                        val shown = if (me && text.startsWith("(event:")) "• " + text.removePrefix("(event:").removeSuffix(")").trim()
                        else com.voiceai.esp32.companion.Companion.visible(text)
                        Text(
                            (if (me && !text.startsWith("(event:")) "You: " else if (me) "" else "${s.companionName}: ") + shown,
                            fontSize = if (me && text.startsWith("(event:")) 11.sp else 14.sp,
                            color = if (me) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            maxLines = 6, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLog = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun Bubble(text: String, listening: Boolean, scale: Float, modifier: Modifier) {
    AnimatedVisibility(visible = text.isNotBlank(), enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Box(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(if (listening) Color(0xEE1D2A4A) else Color(0xF2FFFFFF))
                .border(1.5.dp, if (listening) Lavender else Pink.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .heightIn(max = 150.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                if (listening) "“$text”" else text,
                color = if (listening) Color.White else Color(0xFF2A1E3D),
                fontSize = (16 * scale).sp, lineHeight = (22 * scale).sp,
            )
        }
    }
}

@Composable
private fun Chip(text: String) {
    Text(
        text, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x66140F2E))
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(50.dp)
            .clip(CircleShape)
            .background(if (on) Lavender.copy(alpha = 0.45f) else Color(0x44FFFFFF))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White) }
}

@Composable
private fun MicButton(listening: Boolean, level: Float, talking: Boolean, onClick: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "mic").animateFloat(
        1f, 1.12f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "p",
    )
    Box(contentAlignment = Alignment.Center) {
        if (listening) Box(Modifier.size(84.dp).scale(1f + level * 0.35f).clip(CircleShape).background(Pink.copy(alpha = 0.3f)))
        Box(
            Modifier
                .size(72.dp)
                .scale(if (listening) pulse else 1f)
                .clip(CircleShape)
                .background(Brush.linearGradient(if (listening) listOf(Pink, Color(0xFFFF9A76)) else listOf(Color(0xFF9B7BFF), Pink)))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (listening || talking) Icons.Filled.Stop else Icons.Filled.Mic, "Talk", tint = Color.White, modifier = Modifier.size(34.dp))
        }
    }
}

/** The animated character (assets/avatar.html) in a WebView. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AvatarView(vm: MainViewModel, modifier: Modifier) {
    val c = vm.companion
    var web by remember { mutableStateOf<WebView?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                val main = Handler(Looper.getMainLooper())
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun touch(kind: String) { main.post { c.onTouch(kind) } }
                }, "AndroidBridge")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) { c.pushState() }
                }
                loadUrl("file:///android_asset/avatar.html")
                web = this
            }
        },
    )
    // forward commands from her brain to the drawing
    LaunchedEffect(web) {
        val w = web ?: return@LaunchedEffect
        c.js.collect { cmd -> w.evaluateJavascript(cmd, null) }
    }
    DisposableEffect(Unit) { onDispose { web?.destroy() } }
}
