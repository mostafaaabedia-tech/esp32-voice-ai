package com.voiceai.esp32

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import com.voiceai.esp32.data.Chat
import com.voiceai.esp32.ui.BrandGradient
import com.voiceai.esp32.ui.shareText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.voiceai.esp32.ui.ChatScreen
import com.voiceai.esp32.ui.CompanionScreen
import androidx.compose.material.icons.outlined.Face
import com.voiceai.esp32.ui.BrandHeader
import com.voiceai.esp32.ui.SettingsScreen
import com.voiceai.esp32.ui.UsageScreen
import com.voiceai.esp32.ui.VoiceAITheme
import com.voiceai.esp32.ui.VoiceOverlay
import com.voiceai.esp32.ui.fmt
import com.voiceai.esp32.voice.VoiceInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.voiceai.esp32.data.Mode

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoiceAITheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().imePadding()) {
                    App(vm)
                }
            }
        }
    }
}

@Composable
private fun App(vm: MainViewModel) {
    val context = LocalContext.current
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // ---------------------------------------------------------------- toasts
    LaunchedEffect(vm.toast) {
        vm.toast?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.toast = null }
    }

    // ------------------------------------------------------------ voice input
    val voice = remember { VoiceInput(context) }
    var listening by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf("") }
    var level by remember { mutableFloatStateOf(0f) }
    var processing by remember { mutableStateOf(false) }
    var watchdog by remember { mutableStateOf<Job?>(null) }

    fun finish(send: Boolean, text: String) {
        if (!listening) return
        listening = false
        processing = false
        watchdog?.cancel()
        voice.cancel()
        val t = text.trim()
        if (vm.screen == Screen.COMPANION) {                             // talking to the companion
            if (send && t.isNotEmpty()) vm.companion.userSaid(t) else vm.face("idle")
            return
        }
        if (send && t.isNotEmpty()) {
            if (vm.settings.autoSend) {
                vm.send(t)
                if (vm.settings.mode == Mode.ESP32) vm.face("thinking", t)   // ESP32 mode: face switches right away
            } else {
                vm.draft = t
                vm.face("idle")
            }
        } else {
            vm.face("idle")                                              // never leave the OLED on "listening"
        }
    }

    /** If the speech service goes quiet, don't hang on "Listening…" forever. */
    fun armWatchdog(ms: Long) {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(ms)
            if (!listening) return@launch
            if (heard.isNotBlank()) finish(true, heard)
            else { finish(false, ""); vm.toast = "Didn't hear anything. Tap the mic and try again." }
        }
    }

    fun startListening() {
        if (!voice.available) { vm.toast = "No speech service. Install or update the Google app."; return }
        heard = ""; level = 0f; processing = false; listening = true
        vm.phoneVoice.stop()                                               // she stops talking when you start
        vm.face("listening")
        armWatchdog(9000)                                                  // nobody spoke
        voice.start(
            vm.settings.lang,
            onPartial = { heard = it; vm.faceHeard(it); if (!processing) armWatchdog(9000) },
            onLevel = { level = it },
            onFinal = { finish(true, it.ifBlank { heard }) },
            onFail = { msg -> if (heard.isNotBlank()) finish(true, heard) else { finish(false, ""); vm.toast = msg } },
            onSpeechStart = { armWatchdog(15000) },                        // talking: allow a longer sentence
            onSpeechEnd = { processing = true; armWatchdog(3500) },        // stopped talking: wait briefly for the result
        )
    }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startListening() else vm.toast = "Microphone permission is needed for voice"
    }
    val onMic = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening()
        else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    // the companion's face shows when you are talking
    LaunchedEffect(listening) { vm.companion.setListening(listening) }
    // remember which main screen to go back to
    LaunchedEffect(vm.screen) { if (vm.screen == Screen.CHAT || vm.screen == Screen.COMPANION) vm.home = vm.screen }

    // hands-free: listen again after the answer has been spoken
    LaunchedEffect(vm.listenRequest) {
        if (vm.listenRequest > 0 && !listening && (vm.settings.handsFree || vm.screen == Screen.COMPANION) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) startListening()
    }

    // ------------------------------------------------------------------ back
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = !drawer.isOpen && (vm.screen == Screen.USAGE || vm.screen == Screen.SETTINGS)) { vm.screen = vm.home }
    BackHandler(enabled = !drawer.isOpen && vm.screen == Screen.CHAT) { vm.screen = Screen.COMPANION }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
                DrawerContent(vm) { scope.launch { drawer.close() } }
            }
        },
    ) {
        when (vm.screen) {
            Screen.COMPANION -> CompanionScreen(
                vm, listening, heard, if (processing) 0f else level,
                onMenu = { scope.launch { drawer.open() } }, onMic = onMic,
                onStopMic = { if (heard.isNotBlank()) finish(true, heard) else finish(false, "") },
            )
            Screen.CHAT -> ChatScreen(vm, onMenu = { scope.launch { drawer.open() } }, onMic = onMic)
            Screen.USAGE -> UsageScreen(vm) { vm.screen = vm.home }
            Screen.SETTINGS -> SettingsScreen(vm) { vm.screen = vm.home }
        }
    }

    if (listening && vm.screen != Screen.COMPANION) {
        VoiceOverlay(
            text = heard.ifBlank { if (processing) "Processing…" else "" }, level = if (processing) 0f else level,
            onCancel = { finish(false, "") },
            onDone = { finish(true, heard) },
        )
    }
}

@Composable
private fun DrawerContent(vm: MainViewModel, close: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<Chat?>(null) }
    var deleting by remember { mutableStateOf<Chat?>(null) }
    val shown = vm.chats.filter {
        query.isBlank() || it.title.contains(query, true) || it.messages.any { m -> m.content.contains(query, true) }
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        BrandHeader(compact = true)
        Row(
            Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(BrandGradient))
                .clickable { vm.newChat(); vm.screen = Screen.CHAT; close() }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Add, null, tint = Color.White)
            Spacer(Modifier.width(10.dp))
            Text("New chat", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        DrawerLink(Icons.Outlined.Face, "Talk with ${vm.settings.companionName}") { vm.screen = Screen.COMPANION; close() }
        // search
        Row(
            Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search chats", color = MaterialTheme.colorScheme.outline, fontSize = 14.sp)
                    inner()
                },
            )
        }
        Text(
            if (query.isBlank()) "RECENT" else "RESULTS", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 6.dp),
        )
        LazyColumn(Modifier.weight(1f)) {
            if (shown.isEmpty()) item {
                Text(if (query.isBlank()) "No chats yet" else "Nothing found", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(10.dp))
            }
            items(shown, key = { it.id }) { c ->
                var menu by remember { mutableStateOf(false) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (c.id == vm.currentId) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                        .clickable { vm.openChat(c.id); vm.screen = Screen.CHAT; close() }
                        .padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                        Text(
                            "${c.messages.size} messages" + (if (c.tokens > 0) " · ${fmt(c.tokens)} tokens" else ""),
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Box {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).clickable { menu = true },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.MoreVert, "More", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                onClick = { renaming = c; menu = false })
                            DropdownMenuItem(text = { Text("Share / export") }, leadingIcon = { Icon(Icons.Outlined.Share, null) },
                                onClick = { shareText(context, vm.exportText(c)); menu = false })
                            DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                onClick = { deleting = c; menu = false })
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        DrawerLink(Icons.Outlined.BarChart, "Usage") { vm.screen = Screen.USAGE; close() }
        DrawerLink(Icons.Outlined.Settings, "Settings") { vm.screen = Screen.SETTINGS; close() }
    }

    renaming?.let { c ->
        var name by remember(c.id) { mutableStateOf(c.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename chat") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameChat(c.id, name); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete chat?") },
            text = { Text("\"${c.title}\" will be removed from this phone.") },
            confirmButton = { TextButton(onClick = { vm.deleteChat(c.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DrawerLink(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 15.sp)
    }
}
