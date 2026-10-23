package com.music.spotui.ui.components
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.spotui.data.preferences.RadioTaste
import com.music.spotui.ui.theme.*

@Composable
fun RadioTuningControls(station: String, onChanged: () -> Unit) {
    val context = LocalContext.current
    val revision by RadioTaste.revision.collectAsState()
    val selected = remember(revision, station) { RadioTaste.mode(context, station) }
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Tune your radio", color = AppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("close" to "Stay close", "balanced" to "Balanced", "explore" to "Explore more").forEach { (key, title) ->
                Column(Modifier.weight(1f).selectable(selected = selected == key, role = Role.Tab,
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClick = { if (selected != key) { RadioTaste.setMode(context, station, key); onChanged() } }),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                        Text(title, color = if (selected == key) AppPalette else AppSecondary,
                            fontWeight = if (selected == key) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected == key) AppPalette else AppRaised))
                }
            }
        }
        Text(when (selected) {
            "close" -> "Your starting songs and their direct Spotify suggestions."
            "explore" -> "Newer songs guide more of what comes next."
            else -> "A mix of familiar sounds and fresh discoveries."
        }, color = AppSecondary, fontSize = 13.sp, lineHeight = 19.sp)
    }
}
