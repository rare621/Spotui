package com.music.spotui.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.audio.PlaybackEqualizer
import com.music.spotui.data.api.Response
import com.music.spotui.ui.viewmodel.LibraryViewModel

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
internal fun SettingsProfileHeader() {
    val viewModel: LibraryViewModel = hiltViewModel()
    val response by viewModel.account.collectAsState()
    val profile = (response as? Response.Success)?.data
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        GlideImage(model = profile?.imageUrl.orEmpty(), contentDescription = "Spotify profile", contentScale = ContentScale.Crop,
            modifier = Modifier.size(60.dp).clip(CircleShape).background(Color.DarkGray))
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(profile?.name?.ifBlank { "Spotify account" } ?: "Spotify account", color = com.music.spotui.ui.theme.AppText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            com.music.spotui.ui.components.UspotPremiumBadge()
            profile?.email?.takeIf { it.isNotBlank() }?.let { Text(it, color = com.music.spotui.ui.theme.AppSecondary) }
            profile?.plan?.takeIf { it.isNotBlank() }?.let { Text("Spotify $it", color = Color(0xFF1DB954)) }
        }
    }
}

@Composable
internal fun EqualizerControls() {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Equalizer", color = com.music.spotui.ui.theme.AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Switch(checked = PlaybackEqualizer.enabled, onCheckedChange = { PlaybackEqualizer.configure(context, it) })
        }
        Text(PlaybackEqualizer.message, color = com.music.spotui.ui.theme.AppSecondary, fontSize = 12.sp)
        listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz").forEachIndexed { index, label ->
            Text("$label: ${PlaybackEqualizer.customGains[index] / 100f} dB", color = com.music.spotui.ui.theme.AppText)
            Slider(value = PlaybackEqualizer.customGains[index] / 100f, onValueChange = { PlaybackEqualizer.adjustBand(index, (it * 100).toInt()) },
                onValueChangeFinished = { PlaybackEqualizer.configure(context, PlaybackEqualizer.enabled, "Custom") }, valueRange = -12f..12f)
        }
        Box {
            Text("Preset: ${PlaybackEqualizer.preset}", color = Color(0xFF1DB954), modifier = Modifier.fillMaxWidth().clickable { expanded = true }.padding(vertical = 14.dp))
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                (PlaybackEqualizer.presets + "Custom").forEach { name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        PlaybackEqualizer.configure(context, PlaybackEqualizer.enabled, name); expanded = false
                    })
                }
            }
        }
    }
}
