package com.music.spotui.ui.components

import android.content.Context
import android.content.Intent
import android.media.MediaRouter
import android.media.MediaRouter2
import android.media.MediaRoute2Info
import android.media.RouteDiscoveryPreference
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.spotui.ui.theme.*
import kotlinx.coroutines.delay

/** Selected routes, not merely connected devices. No Bluetooth scan or microphone access. */
class AudioOutputs(context: Context) {
    private val context = context.applicationContext
    private val legacy = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter
    private val modern: MediaRouter2? = if(Build.VERSION.SDK_INT >= 30) MediaRouter2.getInstance(this.context) else null
    private val callback: MediaRouter2.RouteCallback? = if(Build.VERSION.SDK_INT >= 30) object: MediaRouter2.RouteCallback() {} else null
    fun start() { if(Build.VERSION.SDK_INT >= 30) runCatching {
        modern?.registerRouteCallback(context.mainExecutor, callback!!, RouteDiscoveryPreference.Builder(listOf(MediaRoute2Info.FEATURE_LIVE_AUDIO),false).build())
    } }
    fun close() { if(Build.VERSION.SDK_INT >= 30) runCatching { modern?.unregisterRouteCallback(callback!!) } }
    fun outputs(): List<AudioOutput> = runCatching {
        if(Build.VERSION.SDK_INT >= 30 && modern != null) {
            val selected = modern.systemController.selectedRoutes
            val selectedIds = selected.map { it.id }.toSet()
            val routes = (selected + modern.routes).filter { it.isSystemRoute }.distinctBy { it.id }.map { route ->
                AudioOutput(if (route.type == MediaRoute2Info.TYPE_BUILTIN_SPEAKER) AudioOutputOptions.PHONE_ID else route.id,
                    route.name.toString().ifBlank { "Phone speaker" }, route.id in selectedIds,
                    route.type in listOf(MediaRoute2Info.TYPE_BLUETOOTH_A2DP,MediaRoute2Info.TYPE_HEARING_AID,26,27))
            }
            if (routes.isEmpty()) legacyOutputs() else AudioOutputOptions.includePhone(routes, localOutput())
        } else legacyOutputs()
    }.getOrElse { runCatching { legacyOutputs() }.getOrDefault(emptyList()) }
    @Suppress("DEPRECATION") private fun localOutput(): AudioOutput {
        val local = legacy.defaultRoute
        return AudioOutput(AudioOutputOptions.PHONE_ID, local.name.toString().ifBlank { "Phone speaker" },
            legacy.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO) == local, false)
    }
    @Suppress("DEPRECATION") private fun legacyOutputs(): List<AudioOutput> {
        val selected = legacy.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
        val routes = (0 until legacy.routeCount).map { i -> legacy.getRouteAt(i) }
            .filter { it.supportedTypes and MediaRouter.ROUTE_TYPE_LIVE_AUDIO != 0 }
            .map { route -> AudioOutput(if (route == legacy.defaultRoute) AudioOutputOptions.PHONE_ID else "legacy:${route.hashCode()}",
                route.name.toString().ifBlank { "Phone speaker" }, route == selected, route.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH) }
        return AudioOutputOptions.includePhone(routes, localOutput())
    }
    fun select(id: String): Boolean = runCatching {
        if (id == AudioOutputOptions.PHONE_ID) {
            val deviceRouter = modern
            if (Build.VERSION.SDK_INT >= 30 && deviceRouter != null) {
                (deviceRouter.systemController.selectedRoutes + deviceRouter.routes)
                    .firstOrNull { it.isSystemRoute && it.type == MediaRoute2Info.TYPE_BUILTIN_SPEAKER }
                    ?.let { route -> if (runCatching { deviceRouter.transferTo(route) }.isSuccess) return true }
            }
            // The legacy local route remains selectable when a vendor's modern list omits it.
            legacy.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, legacy.defaultRoute)
            return true
        }
        if(Build.VERSION.SDK_INT >= 30 && modern != null && !id.startsWith("legacy:")) {
            modern.routes.firstOrNull { it.id == id }?.let { modern.transferTo(it); return true }
            if(Build.VERSION.SDK_INT >= 34 && modern.showSystemOutputSwitcher()) return true
            return false
        }
        for(i in 0 until legacy.routeCount) {
            val route = legacy.getRouteAt(i)
            if(id == "legacy:${route.hashCode()}") { legacy.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO,route); return true }
        }
        false
    }.getOrDefault(false)
    fun systemPicker(): Boolean = Build.VERSION.SDK_INT >= 34 && runCatching { modern?.showSystemOutputSwitcher() == true }.getOrDefault(false)
    fun moreDevices(): Boolean = runCatching {
        if(systemPicker()) return true
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}

@Composable
fun rememberAudioOutputs(context: Context): Pair<AudioOutputs,List<AudioOutput>> {
    val router = remember(context.applicationContext) { AudioOutputs(context) }
    var outputs by remember { mutableStateOf(emptyList<AudioOutput>()) }
    DisposableEffect(router) { router.start(); onDispose { router.close() } }
    LaunchedEffect(router) { while(true) { outputs=router.outputs();delay(1000) } }
    return router to outputs
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioOutputSheet(
    router: AudioOutputs,
    outputs: List<AudioOutput>,
    trackTitle: String = "",
    trackArtist: String = "",
    onDismiss: () -> Unit,
) {
    val sheet = Color(0xFF1F1F1F)
    val card = Color(0xFF2A2A2A)
    val green = Color(0xFF1ED760)
    val secondary = Color(0xFFB3B3B3)
    var message by remember { mutableStateOf("") }
    val selected = outputs.firstOrNull { it.selected }
    val others = outputs.filterNot { it.selected }
    var pendingOutput by remember { mutableStateOf<String?>(null) }
    var needsControls by remember { mutableStateOf(false) }
    var transferAttempt by remember { mutableStateOf(0) }
    LaunchedEffect(pendingOutput, selected?.id, transferAttempt) {
        val pending = pendingOutput ?: return@LaunchedEffect
        if (selected?.id == pending) { pendingOutput = null; needsControls = false; message = "" }
        else {
            delay(5_000)
            message = "Your phone hasn't confirmed the switch. Try device controls."
            needsControls = true
        }
    }
    fun deviceIcon(output: AudioOutput?) = when {
        output?.bluetooth == true -> Icons.Outlined.Bluetooth
        output?.name?.contains("head", ignoreCase = true) == true -> Icons.Outlined.Headphones
        output?.id == AudioOutputOptions.PHONE_ID || output?.name?.contains("phone", ignoreCase = true) == true || output == null -> Icons.Outlined.Smartphone
        else -> Icons.Outlined.Speaker
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = sheet,
        contentColor = Color.White,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0xFF727272)) },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Connect", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
            }
            item {
                Surface(color = card, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(20.dp).heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(selected?.name ?: "This phone", color = Color.White, fontSize = 23.sp,
                                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val song = listOf(trackTitle, trackArtist).filter { it.isNotBlank() }.joinToString(" – ")
                            Text(song.ifBlank { if (selected != null) "Selected output" else "Checking audio output…" },
                                color = green, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(deviceIcon(selected), contentDescription = "Current audio output", tint = green, modifier = Modifier.size(42.dp))
                    }
                }
            }
            if (others.isNotEmpty()) item {
                Text("Available devices", color = secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp))
            }
            items(others, key = { it.id }) { output ->
                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                ) {
                    val requested = router.select(output.id)
                    transferAttempt++
                    pendingOutput = if (requested) output.id else null
                    needsControls = !requested
                    message = if (requested) "Switching to ${output.name}…" else "Use device controls to choose this output."
                }.padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Icon(deviceIcon(output), contentDescription = null, tint = secondary, modifier = Modifier.size(30.dp))
                    Text(output.name, color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (message.isNotBlank()) item { Text(message, color = secondary, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) }
            if (needsControls) item {
                TextButton(onClick = { if (!router.moreDevices()) message = "Device controls are unavailable on this phone." }) {
                    Text("Open device controls", color = green)
                }
            }
            item {
                Surface(color = card, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        message = if (router.moreDevices()) "Choose a device in your phone’s controls."
                            else "Device controls are unavailable on this phone."
                    }) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Bluetooth, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                        Text("Find Bluetooth devices", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
