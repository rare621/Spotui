package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.spotui.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UspotPremiumBadge() {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.heightIn(min = 48.dp).clickable { open = true }, contentAlignment = Alignment.CenterStart) {
        Surface(color = AppPalette.copy(alpha = .14f), shape = RoundedCornerShape(20.dp)) {
            Text("USpot Premium  ›", color = AppPalette, fontWeight = FontWeight.Bold,
                fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
        }
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }, containerColor = AppSurface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(AppPalette.copy(alpha = .22f), AppSurface)), RoundedCornerShape(24.dp)).padding(24.dp)) {
                Text("INCLUDED WITH USPOT", color = AppPalette, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text("USpot Premium", color = AppText, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("More music. More freedom.", color = AppSecondary, modifier = Modifier.padding(top = 8.dp))
            }
            listOf("No ads" to "Enjoy your music without ad breaks.",
                "Unlimited skips" to "Find the song that fits your moment.",
                "Offline downloads" to "Take your favorites with you.",
                "Radio & Radio Mix" to "Keep exploring from the songs you love.",
                "And more" to "Personal recommendations, listening stats, and your music on desktop.").forEach { (title, detail) ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("✓", color = AppPalette, fontSize = 22.sp)
                    Column { Text(title, color = AppText, fontWeight = FontWeight.Bold); Text(detail, color = AppSecondary, fontSize = 14.sp) }
                }
            }
            Text("These are USpot features. This does not change your Spotify subscription.", color = AppSecondary, fontSize = 12.sp)
            Button(onClick = { open = false }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AppPalette, contentColor = MaterialTheme.colorScheme.onPrimary)) {
                Text("Keep listening", modifier = Modifier.padding(8.dp), fontWeight = FontWeight.Bold)
            }
        }
    }
}
