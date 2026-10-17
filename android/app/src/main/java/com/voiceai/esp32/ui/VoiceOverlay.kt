package com.voiceai.esp32.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Full-screen "listening" view with an orb that reacts to your voice. */
@Composable
fun VoiceOverlay(text: String, level: Float, onCancel: () -> Unit, onDone: () -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val loud by animateFloatAsState(level, tween(120), label = "level")
        val pulse = rememberInfiniteTransition(label = "pulse")
        val breathe by pulse.animateFloat(1f, 1.1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "b")
        val accent = MaterialTheme.colorScheme.primary

        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.97f)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(140.dp).scale(1.5f * breathe + loud * 0.4f).alpha(0.12f).clip(CircleShape).background(accent))
                Box(Modifier.size(140.dp).scale(1.25f * breathe + loud * 0.25f).alpha(0.3f).clip(CircleShape).background(accent))
                Box(
                    Modifier.size(140.dp).scale(breathe * (1f + loud * 0.15f)).clip(CircleShape)
                        .background(Brush.radialGradient(listOf(Color(0xFF9EEBEE), accent, Ocean))),
                )
            }
            Spacer(Modifier.height(28.dp))
            Text(
                text.ifBlank { "Listening…" },
                fontSize = 22.sp, fontFamily = FontFamily.Serif, textAlign = TextAlign.Center, lineHeight = 30.sp,
                modifier = Modifier.heightIn(min = 70.dp),
            )
            Text(
                "Speak now. It sends when you stop talking.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
            )
            Spacer(Modifier.height(30.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onCancel,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) { Text("Cancel") }
                Button(onClick = onDone) { Text("Send now") }
            }
        }
    }
}
