package com.music.spotui.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.music.spotui.cache.CacheBudget
import com.music.spotui.cache.CacheStorage
import com.music.spotui.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun StorageSettings() {
    val context = LocalContext.current
    var usage by remember { mutableStateOf<CacheStorage.Usage?>(null) }
    LaunchedEffect(Unit) { usage = withContext(Dispatchers.IO) { CacheStorage.usage(context) } }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("A little room for your music", color = AppText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Temporary music & artwork limit: ${CacheBudget.TOTAL_BYTES / CacheBudget.MIB} MB", color = AppPalette, fontWeight = FontWeight.SemiBold)
        Text("Older cached files are removed automatically. Keeping a small recent cache helps music load faster and avoids downloading the same audio again.", color = AppSecondary)
        usage?.let { u ->
            listOf("Streamed audio" to (u.audio to CacheBudget.AUDIO_BYTES),
                "Artwork" to (u.artwork to CacheBudget.ARTWORK_BYTES),
                "Lossless files" to (u.lossless to CacheBudget.LOSSLESS_BYTES)).forEach { (name, sizes) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(name, color = AppText, modifier = Modifier.weight(1f))
                    Text("${sizes.first / CacheBudget.MIB} / ${sizes.second / CacheBudget.MIB} MB", color = AppSecondary)
                }
            }
        }
        Text("Your listening history, all-time and monthly stats, Radio progress, Home personalization, sign-ins and downloaded music are kept separately. Cache cleanup does not erase them.", color = AppSecondary)
        Text("Files in use are protected. Android, web sign-in and update downloads can temporarily use additional cache space.", color = AppSecondary, fontSize = 12.sp)
    }
}
