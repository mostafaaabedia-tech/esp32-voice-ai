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
import com.voiceai.esp32.ui.Logo
import com.voiceai.esp32.ui.SettingsScreen
import com.voiceai.esp32.ui.UsageScreen
import com.voiceai.esp32.ui.VoiceAITheme
import com.voiceai.esp32.ui.VoiceOverlay
import com.voiceai.esp32.ui.fmt
import com.voiceai.esp32.voice.VoiceInput
import kotlinx.coroutines.launch

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
    var draft by remember { mutableStateOf<String?>(null) }

    fun finish(send: Boolean, text: String) {
        if (!listening) return
        listening = false
        voice.cancel()
        val t = text.trim()
        if (send && t.isNotEmpty()) {
            if (vm.settings.autoSend) vm.send(t) else draft = t
        }
    }

    fun startListening() {
        if (!voice.available) { vm.toast = "No speech service. Install or update the Google app."; return }
        heard = ""; level = 0f; listening = true
        voice.start(
            vm.settings.lang,
            onPartial = { heard = it },
            onLevel = { level = it },
            onFinal = { finish(true, it.ifBlank { heard }) },
            onFail = { msg -> if (heard.isNotBlank()) finish(true, heard) else { finish(false, ""); vm.toast = msg } },
        )
    }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startListening() else vm.toast = "Microphone permission is needed for voice"
    }
    val onMic = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening()
        else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    // ------------------------------------------------------------------ back
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = !drawer.isOpen && vm.screen != Screen.CHAT) { vm.screen = Screen.CHAT }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
                DrawerContent(vm) { scope.launch { drawer.close() } }
            }
        },
    ) {
        when (vm.screen) {
            Screen.CHAT -> ChatScreen(vm, onMenu = { scope.launch { drawer.open() } }, onMic = onMic, draft = draft, onDraftUsed = { draft = null })
            Screen.USAGE -> UsageScreen(vm) { vm.screen = Screen.CHAT }
            Screen.SETTINGS -> SettingsScreen(vm) { vm.screen = Screen.CHAT }
        }
    }

    if (listening) {
        VoiceOverlay(
            text = heard, level = level,
            onCancel = { finish(false, "") },
            onDone = { finish(true, heard) },
        )
    }
}

@Composable
private fun DrawerContent(vm: MainViewModel, close: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo(24.dp)
            Spacer(Modifier.width(10.dp))
            Text("VoiceAI", fontWeight = FontWeight.Bold, fontSize = 19.sp)
        }
        Row(
            Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable { vm.newChat(); close() }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text("New chat", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "RECENT", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 6.dp),
        )
        LazyColumn(Modifier.weight(1f)) {
            if (vm.chats.isEmpty()) item { Text("No chats yet", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(10.dp)) }
            items(vm.chats, key = { it.id }) { c ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (c.id == vm.currentId) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                        .clickable { vm.openChat(c.id); close() }
                        .padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                        if (c.tokens > 0) Text("${fmt(c.tokens)} tokens", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).clickable { vm.deleteChat(c.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Delete, "Delete", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        DrawerLink(Icons.Outlined.BarChart, "Usage") { vm.screen = Screen.USAGE; close() }
        DrawerLink(Icons.Outlined.Settings, "Settings") { vm.screen = Screen.SETTINGS; close() }
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
