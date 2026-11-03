package com.voiceai.esp32.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voiceai.esp32.MainViewModel
import com.voiceai.esp32.net.NetScan
import com.voiceai.esp32.net.WifiNet

/**
 * Network page:
 *  1. find the ESP32 and the PC on the phone's WiFi (no typing IP addresses)
 *  2. places (Home, School…): switch all addresses with one tap
 *  3. change the ESP32's WiFi from the phone (scan, password, it restarts and joins)
 *  4. setup mode: when the ESP32 knows no WiFi it makes its own "VoiceAI-Setup"
 */
@Composable
fun NetworkScreen(vm: MainViewModel, onBack: () -> Unit) {
    val s = vm.settings
    var joinNet by remember { mutableStateOf<WifiNet?>(null) }
    var otherNet by remember { mutableStateOf(false) }
    var newProfile by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadEspWifi(quiet = true) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SheetHeader("Network", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {

            // ------------------------------------------------ status
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(ok = vm.deviceOnline)
                    Spacer(Modifier.width(8.dp))
                    Text("ESP32  ", fontWeight = FontWeight.SemiBold)
                    Text(if (s.espHost.isBlank()) "not set" else s.espHost + if (vm.deviceOnline) "" else "  (no answer)", fontSize = 14.sp)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(ok = vm.pcOnline)
                    Spacer(Modifier.width(8.dp))
                    Text("PC (Ollama)  ", fontWeight = FontWeight.SemiBold)
                    Text(if (s.pcHost.isBlank()) "not set" else s.pcHost + if (vm.pcOnline) "" else "  (no answer)", fontSize = 14.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "This phone: " + NetScan.phoneIps().joinToString(", ").ifBlank { "not on WiFi" },
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.outline,
                )
            }

            // ------------------------------------------------ find devices
            SectionTitle("Find automatically", "same WiFi as the phone")
            AppCard {
                Text(
                    "Changed WiFi or the addresses don't work? Find the ESP32 and the PC on this network, no typing needed.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp,
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = { vm.findDevices() }, enabled = !vm.scanning, modifier = Modifier.fillMaxWidth()) {
                    Text(if (vm.scanning) "Searching… ${vm.scanProgress}%" else "🔍 Find ESP32 and PC")
                }
                if (vm.scanning) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { vm.scanProgress / 100f }, modifier = Modifier.fillMaxWidth())
                }
                if (vm.scannedOnce && !vm.scanning) {
                    Spacer(Modifier.height(10.dp))
                    if (vm.foundEsps.isEmpty() && vm.foundPcs.isEmpty()) Text("Nothing found on this WiFi.", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    vm.foundEsps.forEach { e ->
                        FoundRow("🤖 ESP32 ${e.ip}", "firmware ${e.info.fw}" + if (e.info.ollamaOnline) " · AI online" else "", e.ip == s.espHost) { vm.useEsp(e.ip) }
                    }
                    vm.foundPcs.forEach { p ->
                        FoundRow("💻 PC ${p.ip}", "Ollama · ${p.models} model" + if (p.models == 1) "" else "s", p.ip == s.pcHost.substringBefore(':')) { vm.usePc(p.ip) }
                    }
                }
            }

            // ------------------------------------------------ places
            SectionTitle("Places", "Home, School…")
            AppCard {
                if (vm.profiles.isEmpty()) Text(
                    "Save the current addresses as a place. Next time you are there, one tap switches everything.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp,
                )
                vm.profiles.toList().forEach { p ->
                    val current = p.pcHost == s.pcHost && p.espHost == s.espHost
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background)
                            .clickable { vm.applyProfile(p) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text((if (current) "✓ " else "") + p.name, fontWeight = FontWeight.SemiBold)
                            Text("PC ${p.pcHost.ifBlank { "-" }} · ESP32 ${p.espHost.ifBlank { "-" }}", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                        }
                        Text("✕", modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.deleteProfile(p) }.padding(8.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                }
                TextButton(onClick = { newProfile = true }) { Text("＋ Save current as a place") }
            }

            // ------------------------------------------------ ESP32 WiFi
            SectionTitle("ESP32 WiFi", "change it from here")
            AppCard {
                val w = vm.espWifi
                when {
                    w == null -> Text(
                        if (s.espHost.isBlank()) "Find or add the ESP32 first." else "Can't read the ESP32's WiFi (needs firmware 4.1+ and the ESP32 online).",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    w.setupMode -> Text("The ESP32 is in setup mode (its own WiFi \"VoiceAI-Setup\"). Pick a network below.", fontSize = 13.sp, color = Warn)
                    else -> {
                        Text("Connected to  \"${w.ssid}\"", fontWeight = FontWeight.SemiBold)
                        Text("${w.ip} · signal ${signalWord(w.rssi)} (${w.rssi} dBm)", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }
                if (w != null && (w.saved.isNotEmpty() || w.default.isNotBlank())) {
                    Spacer(Modifier.height(10.dp))
                    Text("Saved on the ESP32 (it joins the strongest one):", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    w.saved.forEach { ss ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("📶 $ss", fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text("forget", fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.forgetWifi(ss) }.padding(8.dp))
                        }
                    }
                    if (w.default.isNotBlank() && w.default !in w.saved) Text("📶 ${w.default}  (from config.h)", fontSize = 14.sp)
                }
                Spacer(Modifier.height(10.dp))
                vm.wifiBusy?.let { msg ->
                    Text(msg, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                }
                Button(
                    onClick = { vm.scanEspWifi() }, enabled = vm.espWifi != null && !vm.wifiScanning && vm.wifiBusy == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (vm.wifiScanning) "The ESP32 is looking…" else "📡 Show WiFi networks near the ESP32") }
                vm.wifiNets?.let { nets ->
                    Spacer(Modifier.height(6.dp))
                    if (nets.isEmpty()) Text("No networks found.", fontSize = 13.sp)
                    nets.forEach { n ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { joinNet = n }.padding(horizontal = 6.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(bars(n.rssi), fontSize = 14.sp, modifier = Modifier.width(44.dp))
                            Text(n.ssid, modifier = Modifier.weight(1f), fontWeight = if (n.ssid == vm.espWifi?.ssid) FontWeight.Bold else FontWeight.Normal)
                            Text(if (n.secure) "🔒" else "open", fontSize = 12.sp)
                        }
                    }
                }
                TextButton(onClick = { otherNet = true }, enabled = vm.espWifi != null) { Text("＋ Hidden / other network") }
                Text(
                    "The ESP32 saves the network, restarts and joins it (about 15 seconds). Then the app finds it again. " +
                        "If the phone is on another WiFi, switch the phone to the same network and tap Find.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, lineHeight = 16.sp,
                )
            }

            // ------------------------------------------------ setup mode
            SectionTitle("Setup mode", "when the ESP32 knows no WiFi")
            AppCard {
                Text(
                    "If the ESP32 can't join any saved WiFi, it makes its own WiFi:\n" +
                        "   name:  VoiceAI-Setup\n   password:  voiceai123\n" +
                        "1. Connect this phone to \"VoiceAI-Setup\" (Android may say \"no internet\": choose stay connected).\n" +
                        "2. Tap the button below, then pick your WiFi in \"ESP32 WiFi\" above.",
                    fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                if (!vm.setupMode) Button(onClick = { vm.enterSetup() }, modifier = Modifier.fillMaxWidth()) { Text("I'm connected to VoiceAI-Setup") }
                else Button(
                    onClick = { vm.exitSetup() }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                ) { Text("Leave setup mode") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    joinNet?.let { n -> JoinDialog(n.ssid, n.secure, { joinNet = null }) { pw -> joinNet = null; vm.joinWifi(n.ssid, pw) } }
    if (newProfile) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newProfile = false },
            title = { Text("Save this place") },
            text = {
                Column {
                    Text("PC ${s.pcHost.ifBlank { "-" }} · ESP32 ${s.espHost.ifBlank { "-" }}", fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it.take(20) }, singleLine = true, placeholder = { Text("Home, School…") })
                }
            },
            confirmButton = { TextButton(onClick = { vm.saveProfile(name); newProfile = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { newProfile = false }) { Text("Cancel") } },
        )
    }
    if (otherNet) {
        // hidden network: name + password
        var ssid by remember { mutableStateOf("") }
        var pw by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { otherNet = false },
            title = { Text("Other network") },
            text = {
                Column {
                    OutlinedTextField(value = ssid, onValueChange = { ssid = it.take(32) }, singleLine = true, label = { Text("WiFi name") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pw, onValueChange = { pw = it.take(63) }, singleLine = true, label = { Text("Password (empty if open)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { if (ssid.isNotBlank()) { otherNet = false; vm.joinWifi(ssid.trim(), pw) } }) { Text("Connect ESP32") }
            },
            dismissButton = { TextButton(onClick = { otherNet = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FoundRow(title: String, sub: String, current: Boolean, onUse: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.background).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        }
        if (current) Text("in use ✓", color = MaterialTheme.colorScheme.tertiary, fontSize = 13.sp)
        else TextButton(onClick = onUse) { Text("Use") }
    }
}

/** Password for a network the ESP32 should join. */
@Composable
private fun JoinDialog(ssid: String?, secure: Boolean, onDismiss: () -> Unit, onJoin: (String) -> Unit) {
    if (ssid == null) return
    var pw by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val ok = !secure || pw.length in 8..63
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect the ESP32 to \"$ssid\"") },
        text = {
            Column {
                if (secure) {
                    OutlinedTextField(
                        value = pw, onValueChange = { pw = it.take(63) }, singleLine = true, label = { Text("WiFi password") },
                        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    TextButton(onClick = { show = !show }) { Text(if (show) "Hide password" else "Show password") }
                    if (pw.isNotEmpty() && pw.length < 8) Text("WiFi passwords have at least 8 characters.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                } else Text("This network has no password.")
            }
        },
        confirmButton = { TextButton(onClick = { onJoin(pw) }, enabled = ok) { Text("Connect ESP32") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun bars(rssi: Int) = when {
    rssi > -55 -> "▂▄▆█"
    rssi > -65 -> "▂▄▆_"
    rssi > -75 -> "▂▄__"
    else -> "▂___"
}

private fun signalWord(rssi: Int) = when {
    rssi > -60 -> "strong"
    rssi > -72 -> "good"
    else -> "weak"
}
