package com.voiceai.esp32.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voiceai.esp32.MainViewModel
import com.voiceai.esp32.data.Mode
import com.voiceai.esp32.data.Persona
import com.voiceai.esp32.data.Route
import kotlinx.coroutines.launch

@Composable
fun SheetHeader(title: String, onBack: () -> Unit, action: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Row(Modifier.width(80.dp), horizontalArrangement = Arrangement.End, content = action)
    }
}

private val LANGS = listOf(
    "en-US" to "English (US)", "en-GB" to "English (UK)", "fa-IR" to "Persian / Dari", "ps-AF" to "Pashto",
    "tr-TR" to "Turkish", "az-AZ" to "Azerbaijani", "ru-RU" to "Russian", "ar-SA" to "Arabic",
)

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val s = vm.settings
    val scope = rememberCoroutineScope()
    var espHost by remember { mutableStateOf(s.espHost) }
    var pcHost by remember { mutableStateOf(s.pcHost) }
    var system by remember { mutableStateOf(s.systemPrompt) }
    var words by remember { mutableStateOf(s.maxWords.toString()) }
    var tokens by remember { mutableStateOf(s.maxTokens.toString()) }
    var memory by remember { mutableStateOf(s.history.toString()) }
    var volume by remember { mutableFloatStateOf(vm.device?.volume ?: 2f) }
    var checking by remember { mutableStateOf(false) }
    LaunchedEffect(vm.device?.volume) { vm.device?.volume?.let { volume = it } }

    fun saveLocal() = vm.updateSettings(
        vm.settings.copy(
            espHost = espHost.trim(), pcHost = pcHost.trim(), systemPrompt = system.trim().ifEmpty { s.systemPrompt },
            maxWords = words.toIntOrNull()?.coerceIn(5, 300) ?: s.maxWords,
            maxTokens = tokens.toIntOrNull()?.coerceIn(16, 1024) ?: s.maxTokens,
            history = memory.toIntOrNull()?.coerceIn(1, 30) ?: s.history,
        )
    )

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SheetHeader("Settings", { saveLocal(); onBack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {

            // -------------------------------------------------- connection
            SectionTitle("How to reach the AI")
            AppCard {
                Segmented(Mode.entries, s.mode, { it.label }) { vm.updateSettings(vm.settings.copy(mode = it)) }
                Spacer(Modifier.height(8.dp))
                Text(s.mode.hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp)
                Spacer(Modifier.height(12.dp))
                Field("PC address (Ollama)", pcHost, "192.168.80.189", KeyboardType.Uri) { pcHost = it }
                Field("ESP32 address (speaker)", espHost, "192.168.80.45", KeyboardType.Uri) { espHost = it }
                Button(
                    onClick = {
                        saveLocal(); checking = true
                        scope.launch {
                            vm.refreshStatus(); checking = false
                            vm.toast = buildString {
                                append(if (vm.pcOnline) "PC: online" else "PC: no answer")
                                append(" · ")
                                append(if (vm.deviceOnline) "ESP32: online" else "ESP32: no answer")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (checking) "Checking…" else "Save & test connection") }
                Spacer(Modifier.height(10.dp))
                StatusRows(vm)
            }

            // -------------------------------------------------- model
            SectionTitle("AI model", "also saved on the ESP32")
            AppCard {
                ModelPicker(vm.modelChoices, s.model) { vm.updateSettings(vm.settings.copy(model = it)) }
                Spacer(Modifier.height(12.dp))
                Field("Instructions (system prompt)", system, singleLine = false) { system = it }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { Field("Max words", words, keyboard = KeyboardType.Number) { words = it } }
                    Box(Modifier.weight(1f)) { Field("Max tokens", tokens, keyboard = KeyboardType.Number) { tokens = it } }
                }
                Field("Memory (last messages sent)", memory, keyboard = KeyboardType.Number) { memory = it }
                Label("Personality")
                Segmented(Persona.entries.take(3), s.persona.takeIf { it in Persona.entries.take(3) } ?: Persona.entries[0], { "${it.emoji} ${it.label}" }) {
                    vm.updateSettings(vm.settings.copy(persona = it))
                }
                Spacer(Modifier.height(6.dp))
                Segmented(Persona.entries.drop(3), s.persona.takeIf { it in Persona.entries.drop(3) } ?: Persona.ASSISTANT, { "${it.emoji} ${it.label}" }) {
                    vm.updateSettings(vm.settings.copy(persona = it))
                }
                Spacer(Modifier.height(14.dp))
                val t = s.temperature
                Label("Creativity  " + when {
                    t < 0.35f -> "· focused"
                    t < 0.9f -> "· balanced"
                    else -> "· creative"
                } + "  (%.1f)".format(t))
                Slider(value = t, onValueChange = { vm.updateSettings(vm.settings.copy(temperature = (it * 10).toInt() / 10f)) }, valueRange = 0f..1.5f)
                Spacer(Modifier.height(6.dp))
                Button(onClick = { saveLocal(); vm.pushToDevice() }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            }

            // -------------------------------------------------- voice
            SectionTitle("Voice")
            AppCard {
                Label("Play answers on")
                Segmented(Route.entries, s.route, { if (it == Route.BOTH) "Both" else it.label }) {
                    vm.updateSettings(vm.settings.copy(route = it))
                }
                Spacer(Modifier.height(14.dp))
                Label("ESP32 voice")
                Segmented(listOf(0, 1), vm.device?.voiceMode ?: 0, { if (it == 0) "Robot (offline)" else "PC voice" }) {
                    vm.setEspVoice(it)
                }
                Spacer(Modifier.height(14.dp))
                Label("Speaker volume  ${(volume / 4f * 100).toInt()}%")
                Slider(value = volume, onValueChange = { volume = it }, valueRange = 0f..4f, onValueChangeFinished = { vm.setVolume(volume) })
                Spacer(Modifier.height(6.dp))
                LangPicker(s.lang) { vm.updateSettings(vm.settings.copy(lang = it)) }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Hands-free conversation", fontSize = 15.sp)
                        Text("Mic turns on again after each answer is spoken", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(checked = s.handsFree, onCheckedChange = { vm.updateSettings(vm.settings.copy(handsFree = it)) })
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Send right after speaking", fontSize = 15.sp)
                        Text("Otherwise you can edit the text first", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(checked = s.autoSend, onCheckedChange = { vm.updateSettings(vm.settings.copy(autoSend = it)) })
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { saveLocal(); vm.speakOnEsp("Hello! This is your voice assistant speaker test.") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) { Text("Test speaker") }
            }

            SectionTitle("Display")
            AppCard {
                Label("Text size")
                Segmented(listOf(0.9f, 1f, 1.15f, 1.3f), s.textScale, {
                    when (it) { 0.9f -> "Small"; 1f -> "Normal"; 1.15f -> "Large"; else -> "Huge" }
                }) { vm.updateSettings(vm.settings.copy(textScale = it)) }
            }

            SectionTitle("About")
            BrandHeader()
            Spacer(Modifier.height(14.dp))
            Text(
                "VoiceAI 4.2 · your questions go to your own PC. Nothing leaves your network.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun StatusRows(vm: MainViewModel) {
    val d = vm.device
    val rows = buildList {
        add("PC / Ollama" to if (vm.pcOnline) "Online · ${vm.pcModels.size} models" else "Not reachable")
        add("ESP32" to if (vm.deviceOnline) "Online · ${d?.ip}" else "Not reachable")
        if (vm.deviceOnline && d != null) {
            add("ESP32 → Ollama" to if (d.ollamaOnline) "Online · ${d.ollamaHost}" else "Can't reach ${d.ollamaHost}")
            add("WiFi signal" to "${if (d.rssi > -60) "Strong" else if (d.rssi > -72) "Good" else "Weak"} (${d.rssi} dBm)")
            add("Free memory" to "${d.heap / 1024} KB")
            add("Uptime" to if (d.uptimeSec > 3600) "%.1f h".format(d.uptimeSec / 3600.0) else "${d.uptimeSec / 60} min")
            add("Firmware" to d.fw)
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.background).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        rows.forEach { (k, v) ->
            Row {
                Text(k, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                val bad = v.startsWith("Not") || v.startsWith("Can't")
                Text(
                    v, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = when {
                        bad -> MaterialTheme.colorScheme.error
                        v.startsWith("Online") -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun Field(
    label: String, value: String, placeholder: String = "", keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true, onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    )
}

@Composable
private fun ModelPicker(models: List<String>, selected: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Label("Model")
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.background)
                .clickable { open = true }
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(selected, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.KeyboardArrowDown, "Pick model")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (models.isEmpty()) DropdownMenuItem(text = { Text("Connect to the PC to list models") }, onClick = { open = false })
            models.forEach { m ->
                DropdownMenuItem(text = { Text(m) }, onClick = { onPick(m); open = false })
            }
        }
    }
}

@Composable
private fun LangPicker(selected: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Label("Speech language")
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.background)
                .clickable { open = true }
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(LANGS.firstOrNull { it.first == selected }?.second ?: selected, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.KeyboardArrowDown, "Pick language")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LANGS.forEach { (code, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onPick(code); open = false })
            }
        }
    }
}
