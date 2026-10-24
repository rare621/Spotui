package com.music.spotui.ui.screens

import com.music.spotui.ui.components.motionClick
import androidx.compose.foundation.layout.*
import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.music.spotui.data.preferences.CROSSFADE_MAX_MS
import com.music.spotui.data.preferences.StreamQuality
import com.music.spotui.data.preferences.getCellularQuality
import com.music.spotui.data.preferences.getCrossfadeMs
import com.music.spotui.data.preferences.setCrossfadeMs
import com.music.spotui.data.preferences.getDownloadQuality
import com.music.spotui.data.preferences.getLyricTranslateLang
import com.music.spotui.data.preferences.isVideoFallbackEnabled
import com.music.spotui.data.preferences.getWifiQuality
import com.music.spotui.data.preferences.setCellularQuality
import com.music.spotui.data.preferences.setDownloadQuality
import com.music.spotui.data.preferences.setLyricTranslateLang
import com.music.spotui.data.preferences.setVideoFallbackEnabled
import com.music.spotui.data.preferences.setWifiQuality
import com.music.spotui.data.preferences.MusicSource
import com.music.spotui.data.preferences.getPrimaryMusicSource
import com.music.spotui.data.preferences.isYoutubeLoggedIn
import com.music.spotui.data.preferences.setPrimaryMusicSource
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import java.util.Locale
import kotlinx.coroutines.launch

private val translateLanguages by lazy {
    Locale.getISOLanguages()
        .map { it to Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()) }
        .filter { it.second.isNotBlank() }
        .sortedBy { it.second }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val backupRevision by com.music.spotui.ui.viewmodel.BackupSignals.revision.collectAsState()
    val context = LocalContext.current
    var showOpenSourceNotices by remember { mutableStateOf(false) }
    if (showOpenSourceNotices) {
        val notices = remember(context) {
            context.assets.open("licenses/NOTICE.txt").bufferedReader().use { it.readText() } + "\n\n" +
                context.assets.open("licenses/GPL-3.0.txt").bufferedReader().use { it.readText() } +
                "\n\n" + context.assets.open("licenses/PLAYBACK-BACKUP-NOTICES.txt").bufferedReader().use { it.readText() }
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showOpenSourceNotices = false },
            containerColor = com.music.spotui.ui.theme.AppSurface,
            title = { Text("Open-source license", color = com.music.spotui.ui.theme.AppText) },
            text = {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(notices, color = com.music.spotui.ui.theme.AppText, fontSize = 12.sp,
                        modifier = Modifier.height(360.dp).verticalScroll(rememberScrollState()))
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showOpenSourceNotices = false }) {
                    Text("Close", color = AppPalette)
                }
            }
        )
    }
    com.music.spotui.audio.PlaybackEqualizer.initialize(context)

    val homeRecents = com.music.spotui.data.preferences.HomeRecents
    val homePersonalization = com.music.spotui.data.preferences.HomePersonalization
    var personalizeHome by remember(backupRevision) { mutableStateOf(homePersonalization.enabled(context)) }
    var personalizeStrength by remember(backupRevision) { mutableStateOf(homePersonalization.strength(context).toFloat()) }
    var personalizeWindow by remember(backupRevision) { mutableStateOf(homePersonalization.windowDays(context)) }
    var showRecentPlaylists by remember(backupRevision) { mutableStateOf(homeRecents.showPlaylists(context)) }
    var showRecentRadios by remember(backupRevision) { mutableStateOf(homeRecents.showRadios(context)) }
    var recentCount by remember(backupRevision) { mutableStateOf(homeRecents.maxItems(context).toFloat()) }
    var clearRecentDialog by remember(backupRevision) { mutableStateOf(false) }
    var category by remember(backupRevision) { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(enabled = category != null) { category = null }
    var wifiQ by remember(backupRevision) { mutableStateOf(getWifiQuality(context)) }
    var cellQ by remember(backupRevision) { mutableStateOf(getCellularQuality(context)) }
    var dlQ by remember(backupRevision) { mutableStateOf(getDownloadQuality(context)) }
    var crossfadeMs by remember(backupRevision) { mutableStateOf(getCrossfadeMs(context).toFloat()) }
    var videoFallback by remember(backupRevision) { mutableStateOf(isVideoFallbackEnabled(context)) }
    var selectedLanguage by remember(backupRevision) { mutableStateOf(getLyricTranslateLang(context)) }
    var languageQuery by remember(backupRevision) { mutableStateOf("") }
    // Read fresh each composition so returning from the Deezer login reflects it.
    val deezerConnected = com.music.spotui.data.preferences.getDeezerArl(context) != null
    val deezerTier = com.music.spotui.data.preferences.getDeezerTier(context)
    val youtubeConnected = isYoutubeLoggedIn(context)
    var primarySource by remember(backupRevision) {
        mutableStateOf(getPrimaryMusicSource(context) ?: MusicSource.YOUTUBE_MUSIC)
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            CenterAlignedTopAppBar(
                title = { androidx.compose.animation.Crossfade(targetState = category ?: "Settings", animationSpec = androidx.compose.animation.core.tween(150), label = "Settings title") { title -> Text(title, color = com.music.spotui.ui.theme.AppText, fontWeight = FontWeight.Bold) } },
                navigationIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = com.music.spotui.ui.theme.AppText,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(48.dp)
                            .clickable { if (category != null) category = null else navController.popBackStack() }.padding(11.dp)
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = AppBackground)
            )
        }
    ) { padding ->
        com.music.spotui.ui.components.PageMotion(category, Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { shownCategory ->
        Column(
            modifier = Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (shownCategory == null) {
                SettingsProfileHeader()
                val summaries=mapOf("Advanced" to "Troubleshooting and Radio reset", "Updates" to "Check for the latest version", "Desktop & Browser" to "Listen on your computer", "Playback" to "Sound, transitions and controls", "Appearance" to if(com.music.spotui.ui.theme.AppearanceSettings.followDevice.value)"Follow device"else if(com.music.spotui.ui.theme.AppearanceSettings.effectiveDark)"Dark"else"Light", "Storage" to "Temporary cache and space use", "Smart Downloads" to "Optional offline favorites", "Data & backup" to "Export or restore your music data", "Music sources" to "Connections and audio quality", "Home" to "Personalization and history", "Account" to "Your connected services", "About" to "Credits and app information")
                listOf(listOf("Playback","Appearance","Home"),listOf("Storage","Smart Downloads","Data & backup","Desktop & Browser"),listOf("Music sources","Account","Advanced","Updates","About")).forEachIndexed { index, group ->
                    Text(listOf("Your listening", "Your library", "App & account")[index], color = com.music.spotui.ui.theme.AppSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp))
                    androidx.compose.material3.Surface(color=com.music.spotui.ui.theme.AppSurface,shape=androidx.compose.foundation.shape.RoundedCornerShape(16.dp),modifier=Modifier.padding(vertical=6.dp)) {
                        Column {
                            group.forEach { name ->
                                Row(Modifier.fillMaxWidth().motionClick {category=name}.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(name,color=com.music.spotui.ui.theme.AppText,fontSize=16.sp,fontWeight=FontWeight.SemiBold)
                                        Text(summaries[name].orEmpty(),color=com.music.spotui.ui.theme.AppSecondary,fontSize=12.sp)
                                    }
                                    Text("›",color=com.music.spotui.ui.theme.AppSecondary,fontSize=22.sp)
                                }
                            }
                        }
                    }
                }
            }
            if (shownCategory == "Updates") com.music.spotui.updates.UpdateSettings()
            if (shownCategory == "Desktop & Browser") com.music.spotui.webui.WebUiSettings()
            if (shownCategory == "Data & backup") BackupControls()
            if (shownCategory == "Smart Downloads") SmartDownloadSettings()
            if (shownCategory == "Storage") com.music.spotui.ui.components.StorageSettings()
            if (shownCategory == "Appearance") {
                SettingsSwitchRow("Follow device theme", "Use your device's light or dark appearance", com.music.spotui.ui.theme.AppearanceSettings.followDevice.value) {
                    com.music.spotui.ui.theme.AppearanceSettings.setFollowDevice(context, it)
                }
                SettingsSwitchRow("Dark mode", "Off uses light mode; disabled while following your device",
                    if (com.music.spotui.ui.theme.AppearanceSettings.followDevice.value) com.music.spotui.ui.theme.AppearanceSettings.effectiveDark else com.music.spotui.ui.theme.AppearanceSettings.darkMode.value,
                    enabled = !com.music.spotui.ui.theme.AppearanceSettings.followDevice.value) {
                    com.music.spotui.ui.theme.AppearanceSettings.setDarkMode(context, it)
                }
            }
            if(shownCategory=="Advanced") {
                com.music.spotui.ui.components.PlaybackDiagnosticsControls()
                var confirmReset by remember{mutableStateOf(false)}
                androidx.compose.material3.TextButton(onClick={confirmReset=true}) {Text("Reset Radio preferences",color=AppPalette)}
                if(confirmReset)androidx.compose.material3.AlertDialog(onDismissRequest={confirmReset=false},title={Text("Reset Radio preferences?")},text={Text("This clears your Radio tuning and excluded songs and artists. Your saved stations and listening history stay.")},confirmButton={androidx.compose.material3.TextButton(onClick={com.music.spotui.data.preferences.RadioTaste.reset(context);confirmReset=false;com.music.spotui.ui.components.ActionFeedback.show("Radio preferences reset")}){Text("Reset",color=AppPalette)}},dismissButton={androidx.compose.material3.TextButton(onClick={confirmReset=false}){Text("Cancel")}})
            }
            if (shownCategory == "Playback") {
            EqualizerControls()
            SectionTitle("Audio quality")
            QualityPicker(
                title = "Streaming over Wi-Fi",
                selected = wifiQ,
            ) { wifiQ = it; setWifiQuality(context, it) }

            QualityPicker(
                title = "Streaming over cellular",
                selected = cellQ,
            ) { cellQ = it; setCellularQuality(context, it) }

            QualityPicker(
                title = "Download quality",
                selected = dlQ,
            ) { dlQ = it; setDownloadQuality(context, it) }

            // Lossless FLAC comes from the Tidal community backend (no login) and, if
            // connected, Deezer HiFi. Falls back to best-quality YouTube on a miss.
            val losslessNote = if (deezerConnected) {
                "Lossless: Tidal (free) + Deezer${if (deezerTier.isNotBlank()) " $deezerTier" else ""} — real FLAC"
            } else {
                "Lossless: Tidal community FLAC (no login needed) — or add Deezer HiFi below"
            }
            Text(
                losslessNote,
                color = com.music.spotui.ui.theme.AppSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )

            Spacer(Modifier.height(12.dp))
            SectionTitle("Matching")
            SettingsSwitchRow(
                title = "Allow video fallback",
                subtitle = "Use regular YouTube videos only after Music song results fail",
                checked = videoFallback,
            ) {
                videoFallback = it
                setVideoFallbackEnabled(context, it)
            }

            Spacer(Modifier.height(12.dp))
            SectionTitle("Lyrics")
            val languageMatches = remember(languageQuery) {
                val query = languageQuery.trim()
                if (query.isBlank()) {
                    emptyList()
                } else {
                    translateLanguages.filter { (code, name) ->
                        code.contains(query, true) || name.contains(query, true)
                    }.sortedWith(
                        compareByDescending<Pair<String, String>> {
                            it.first.equals(query, true) || it.second.equals(query, true)
                        }.thenBy { it.second }
                    ).take(8)
                }
            }
            OutlinedTextField(
                value = languageQuery,
                onValueChange = {
                    languageQuery = it
                    if (it.isBlank()) {
                        selectedLanguage = ""
                        setLyricTranslateLang(context, "")
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                singleLine = true,
                label = { Text("Translate lyrics to", color = com.music.spotui.ui.theme.AppSecondary) },
                placeholder = { Text("Search languages", color = Color(0xFF72728A)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = com.music.spotui.ui.theme.AppText,
                    unfocusedTextColor = com.music.spotui.ui.theme.AppText,
                    cursorColor = AppPalette,
                    focusedBorderColor = AppPalette,
                    unfocusedBorderColor = Color(0xFF3A3A46),
                    focusedLabelColor = AppPalette,
                    unfocusedLabelColor = Color(0xFFB3B3B3),
                    focusedContainerColor = AppBackground,
                    unfocusedContainerColor = AppBackground,
                ),
            )
            languageMatches.forEach { (code, name) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(com.music.spotui.ui.theme.AppSurface)
                        .clickable {
                            selectedLanguage = code
                            setLyricTranslateLang(context, code)
                            languageQuery = ""
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, color = com.music.spotui.ui.theme.AppText, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(code, color = AppPalette, fontSize = 13.sp)
                }
            }
            Text(
                text = if (selectedLanguage.isBlank()) {
                    "Using phone language"
                } else {
                    "Selected: ${translateLanguages.firstOrNull { it.first == selectedLanguage }?.second ?: selectedLanguage} ($selectedLanguage)"
                },
                color = com.music.spotui.ui.theme.AppSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )

            Spacer(Modifier.height(12.dp))
            SectionTitle("Crossfade")
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Crossfade", color = com.music.spotui.ui.theme.AppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    if (crossfadeMs <= 0f) "Off" else "${(crossfadeMs / 1000f).let { String.format("%.0f", it) }}s",
                    color = if (crossfadeMs <= 0f) Color(0xFFB3B3B3) else AppPalette,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "Blend the end of a song into the start of the next",
                color = com.music.spotui.ui.theme.AppSecondary,
                fontSize = 13.sp,
            )
            Slider(
                value = crossfadeMs,
                onValueChange = { crossfadeMs = it },
                onValueChangeFinished = { setCrossfadeMs(context, crossfadeMs.toInt()) },
                valueRange = 0f..CROSSFADE_MAX_MS.toFloat(),
                steps = (CROSSFADE_MAX_MS / 1000) - 1, // 1-second stops
                colors = SliderDefaults.colors(
                    thumbColor = AppPalette,
                    activeTrackColor = AppPalette,
                    inactiveTrackColor = com.music.spotui.ui.theme.AppRaised,
                ),
            )
            Spacer(Modifier.height(12.dp))
            }
            if (shownCategory == "Music sources") {
            SectionTitle("Music source")
            SourceSettingRow(
                title = "YouTube Music",
                subtitle = if (youtubeConnected) "Connected — explicit and age-restricted playback enabled" else "Login required for explicit and age-restricted songs",
                selected = primarySource == MusicSource.YOUTUBE_MUSIC,
            ) {
                if (youtubeConnected) {
                    primarySource = MusicSource.YOUTUBE_MUSIC
                    setPrimaryMusicSource(context, primarySource)
                } else {
                    navController.navigate(com.music.spotui.ui.navigation.Routes.YouTubeLogin.route)
                }
            }
            SourceSettingRow(
                title = "Deezer",
                subtitle = if (deezerConnected) "Connected${if (deezerTier.isNotBlank()) " — $deezerTier" else ""}" else "Not connected",
                selected = primarySource == MusicSource.DEEZER,
            ) {
                if (deezerConnected) {
                    primarySource = MusicSource.DEEZER
                    setPrimaryMusicSource(context, primarySource)
                    com.music.spotui.data.preferences.setDeezerEnabled(context, true)
                } else {
                    navController.navigate(com.music.spotui.ui.navigation.Routes.DeezerLogin.route)
                }
            }
            Text(
                text = if (youtubeConnected) "Reconnect YouTube Music" else "Log in to YouTube Music",
                color = AppPalette,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { navController.navigate(com.music.spotui.ui.navigation.Routes.YouTubeLogin.route) }
                    .padding(vertical = 12.dp),
            )
            Text(
                text = if (deezerConnected) "Reconnect / switch account" else "Log in to Deezer",
                color = AppPalette,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        navController.navigate(com.music.spotui.ui.navigation.Routes.DeezerLogin.route)
                    }
                    .padding(vertical = 14.dp),
            )
            if (deezerConnected) {
                Text(
                    text = "Disconnect Deezer",
                    color = Color(0xFFE57373),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            com.music.spotui.data.preferences.clearDeezer(context)
                            navController.navigate(com.music.spotui.ui.navigation.Routes.Settings.route) {
                                popUpTo(com.music.spotui.ui.navigation.Routes.Settings.route) { inclusive = true }
                            }
                        }
                        .padding(vertical = 12.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            SectionTitle("SpotiFLAC (experimental)")
            val sfConnected = com.music.spotui.data.preferences.hasSpotiflacSession(context)
            Text(
                text = "Gives access to SpotiFLAC's own FLAC servers. You solve one quick check yourself — no auto-bypass. Experimental: may fail if their servers change.",
                color = com.music.spotui.ui.theme.AppSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 6.dp),
            )
            Text(
                text = if (sfConnected) "Re-verify SpotiFLAC" else "Set up SpotiFLAC verification",
                color = AppPalette,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        navController.navigate(com.music.spotui.ui.navigation.Routes.SpotiflacVerify.route)
                    }
                    .padding(vertical = 14.dp),
            )
            if (sfConnected) {
                Text(
                    text = "Session active ✓",
                    color = Color(0xFF00C7B7),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            SectionTitle("Soulseek (experimental)")
            var soulseekOn by remember(backupRevision) { mutableStateOf(com.music.spotui.data.preferences.isSoulseekEnabled(context)) }
            SettingsSwitchRow(
                title = "Soulseek lossless fallback",
                subtitle = "When the FLAC servers miss, fetch FLACs from Soulseek (peer-to-peer). Uses data and shows your IP to peers.",
                checked = soulseekOn,
            ) {
                soulseekOn = it
                com.music.spotui.data.preferences.setSoulseekEnabled(context, it)
                // Off: leave the network now and close the listening port.
                if (!it) Thread { com.music.spotui.soulseek.SoulseekClient.disconnect() }.start()
            }

            Spacer(Modifier.height(12.dp))
            }
            if (shownCategory == "Account") {
            SectionTitle("Account")
            Text(
                text = "Log out",
                color = Color(0xFFE57373),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        com.music.spotui.data.api.SpotifySession.setSpDc(context, "")
                        com.music.spotui.data.api.Api.HomeCache.clear()
                        navController.navigate(com.music.spotui.ui.navigation.Routes.Login.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                    .padding(vertical = 14.dp)
            )
            Spacer(Modifier.height(12.dp))
            }
            if (shownCategory == "Home") {
            SectionTitle("Home personalization")
            Text("Rank Spotify playlists, albums and artists using songs played in USpot. Spotify's existing recommendations remain the starting point.",
                color = com.music.spotui.ui.theme.AppSecondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            SettingsSwitchRow("Personalize Spotify Home rows", "Use local USpot listening history", personalizeHome) {
                personalizeHome = it; homePersonalization.setEnabled(context, it)
            }
            Text("Local personalization target: ${personalizeStrength.toInt()}%", color = com.music.spotui.ui.theme.AppText, fontSize = 14.sp)
            Slider(value = personalizeStrength, onValueChange = { personalizeStrength = it },
                onValueChangeFinished = { homePersonalization.setStrength(context, personalizeStrength.toInt()) },
                valueRange = 0f..100f, steps = 9, enabled = personalizeHome)
            Text("Gradually reaches your target as you listen. At 100%, all recommendation cards can come from USpot history. Spotify fills gaps when local recommendations are unavailable.",
                color = com.music.spotui.ui.theme.AppSecondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            androidx.compose.material3.TextButton(onClick = {
                homePersonalization.revision.value++
                android.widget.Toast.makeText(context, "Refreshing your USpot recommendations", android.widget.Toast.LENGTH_SHORT).show()
            }) { Text("Refresh recommendations", color = AppPalette) }
            val localListens = com.music.spotui.data.recommendation.ListeningTastePolicy.qualified(
                com.music.spotui.data.preferences.getListeningHistory(context), System.currentTimeMillis(), personalizeWindow).size
            Text("Learning from $localListens USpot listens. New recommendations appear as your listening grows.",
                color = com.music.spotui.ui.theme.AppSecondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
            Text("Listening history window", color = com.music.spotui.ui.theme.AppText, fontSize = 14.sp)
            listOf(30 to "30 days", 90 to "90 days", 0 to "All history").forEach { (days, label) ->
                Text((if (personalizeWindow == days) "● " else "○ ") + label,
                    color = if (personalizeWindow == days) AppPalette else com.music.spotui.ui.theme.AppText,
                    modifier = Modifier.fillMaxWidth().clickable {
                        personalizeWindow = days; homePersonalization.setWindowDays(context, days)
                    }.padding(vertical = 7.dp))
            }
            Spacer(Modifier.height(12.dp))
            var details by remember { mutableStateOf(false) }
            androidx.compose.material3.TextButton(onClick={details=!details}) {Text(if(details)"Hide personalization details"else"How personalization works",color=AppPalette)}
            if(details) {
            Text("Artist-based playlist rows use 30 days of qualified listening, weighted toward the last week, and rotate daily among your top three artists. At least three plays across two days are needed.", color = com.music.spotui.ui.theme.AppSecondary, fontSize = 13.sp)
            }
            SectionTitle("Home history")
            Text("The top eight cards show playlists, albums and USpot radios. Recently played below shows individual songs after 30 seconds of listening.",
                color = com.music.spotui.ui.theme.AppSecondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            SettingsSwitchRow("Show recently played playlists", "Open the playlist when tapped", showRecentPlaylists) {
                showRecentPlaylists = it; homeRecents.setPlaylists(context, it)
            }
            SettingsSwitchRow("Show recently started radio", "Restart radio when tapped", showRecentRadios) {
                showRecentRadios = it; homeRecents.setRadios(context, it)
            }
            Text("Maximum home history items: ${recentCount.toInt()}", color = com.music.spotui.ui.theme.AppText, fontSize = 14.sp)
            Slider(value = recentCount, onValueChange = { recentCount = it },
                onValueChangeFinished = { homeRecents.setMaxItems(context, recentCount.toInt()) },
                valueRange = 2f..20f, steps = 17)
            Text("Clear home history", color = AppPalette, fontSize = 15.sp,
                modifier = Modifier.fillMaxWidth().clickable { clearRecentDialog = true }.padding(vertical = 12.dp))
            if (clearRecentDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { clearRecentDialog = false },
                    title = { Text("Clear home history?") },
                    text = { Text("Remove saved playlist and radio shortcuts. Listening History will not be deleted.") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            homeRecents.clear(context); clearRecentDialog = false
                        }) { Text("Clear") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { clearRecentDialog = false }) { Text("Cancel") }
                    }
                )
            }
            }
            if (shownCategory == "About") {
            Spacer(Modifier.height(12.dp))
                        SectionTitle("About")
            Text("USpot ${com.music.spotui.BuildConfig.VERSION_NAME} (${com.music.spotui.BuildConfig.VERSION_CODE})", color = com.music.spotui.ui.theme.AppText, fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold)
            Text("Original app: Spotui · Credit to its creators and contributors", color = AppPalette, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().clickable {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://github.com/Spotui/Spotui")))
                }.padding(vertical = 10.dp))
            Text("Modified fork · Updated October 22, 2026", color = com.music.spotui.ui.theme.AppSecondary, fontSize = 13.sp)
            Text("Original Spotui work remains credited to its authors and contributors.",
                color = com.music.spotui.ui.theme.AppSecondary, fontSize = 13.sp)
            Text("GNU GPL version 3 · You may redistribute and modify this app under that license. Provided without warranty; see the license for details.",
                color = com.music.spotui.ui.theme.AppSecondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
            Text("View license and notices", color = AppPalette, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().clickable { showOpenSourceNotices = true }.padding(vertical = 10.dp))
            Text("The corresponding source code accompanies each published release.",
                color = com.music.spotui.ui.theme.AppSecondary, fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 10.dp))
            }
            Spacer(Modifier.height(com.music.spotui.ui.navigation.LocalBottomOverlayInset.current + 24.dp))
        }
        }
    }
}

@Composable
private fun SourceSettingRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(if (selected) com.music.spotui.ui.theme.AppSurface else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = com.music.spotui.ui.theme.AppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = com.music.spotui.ui.theme.AppSecondary, fontSize = 12.sp)
        }
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = "Selected", tint = AppPalette, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = AppPalette,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = com.music.spotui.ui.theme.AppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = com.music.spotui.ui.theme.AppSecondary, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AppPalette,
                uncheckedThumbColor = Color(0xFFB3B3B3),
                uncheckedTrackColor = com.music.spotui.ui.theme.AppRaised,
            ),
        )
    }
}

@Composable
private fun QualityPicker(
    title: String,
    selected: StreamQuality,
    onSelect: (StreamQuality) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(title, color = com.music.spotui.ui.theme.AppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        StreamQuality.entries.forEach { q ->
            val isSel = q == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelect(q) }
                    .background(if (isSel) com.music.spotui.ui.theme.AppSurface else Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(q.label, color = com.music.spotui.ui.theme.AppText, fontSize = 15.sp)
                    Text(q.detail, color = com.music.spotui.ui.theme.AppSecondary, fontSize = 12.sp)
                }
                if (isSel) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Selected",
                        tint = AppPalette,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}
