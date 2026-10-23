package com.music.spotui.ui.screens
import androidx.compose.runtime.collectAsState

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.R
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.RadioSessions
import com.music.spotui.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun RadioScreen(navController: NavController, seedId: String, player: PlayerViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val seed = remember(seedId) { RadioSessions.get(context).firstOrNull { it.spotifyTrackId.ifBlank { it.id.toString() } == seedId } }
    val anchors = remember(seedId) { RadioSessions.anchors(context,seedId) }
    val mix = RadioSessions.isMix(seedId)
    var loadRevision by remember(seedId) { mutableStateOf(0) }
    var prepared by remember(seedId) { mutableStateOf<List<SongsModel>>(emptyList()) }
    var savedShuffle by remember(seedId){mutableStateOf(false)}
    var loading by remember(seedId) { mutableStateOf(true) }
    var showDetails by remember(seedId) { mutableStateOf(false) }
    var askDownload by remember { mutableStateOf(false) }
    val active = player.activeCollection.value?.key == "radio:$seedId"
    val shuffled = active && player.shuffleState.value
    val stationSongs = if (active) player.queue.value.drop(
        player.queue.value.indexOfFirst { it.id == player.currentSongId.value }.coerceAtLeast(0)).take(11) else prepared
    val downloaded = com.music.spotui.ui.components.rememberCollectionDownloaded(stationSongs, context)
    val refreshing by player.radioRefreshing.collectAsState()
    val refreshMessage by player.radioRefreshMessage.collectAsState()
    val tasteRevision by com.music.spotui.data.preferences.RadioTaste.revision.collectAsState()
    LaunchedEffect(seedId, tasteRevision, loadRevision) {
        loading=true
        val saved=withContext(Dispatchers.IO){com.music.spotui.data.preferences.RadioMemory.queue(context,seedId)}
        val sameMode=saved?.mode==com.music.spotui.data.preferences.RadioTaste.mode(context,seedId)
        val remaining=saved?.songs.orEmpty().filterIndexed { index,song -> index==0 || (sameMode && com.music.spotui.data.preferences.RadioTaste.allowed(context,song)) }
        if(remaining.isNotEmpty()){prepared=remaining;savedShuffle=saved?.shuffled==true;loading=false}
        if(remaining.size<11) {
            val recommendations = if (seed?.spotifyTrackId.isNullOrBlank()) emptyList() else withContext(Dispatchers.IO) { player.radioRecommendations(seed!!.spotifyTrackId) }
            prepared = if(remaining.isEmpty())com.music.spotui.data.recommendation.RadioQueuePolicy.resumed(anchors.firstOrNull()?.let { RadioSessions.resolvedAnchor(context,it) } ?: seed?.takeUnless { mix }, RadioSessions.lastTrack(context, seedId), recommendations)
                else (remaining+recommendations).distinctBy{it.spotifyTrackId.ifBlank{it.id.toString()}}.take(11)
        }
        loading = false
    }
    if (askDownload) com.music.spotui.ui.components.ConfirmDownloadAllDialog(stationSongs.size, { askDownload = false }) {
        com.music.spotui.di.Downloads.downloadAll(stationSongs, context)
    }
    Column(Modifier.fillMaxSize().background(com.music.spotui.ui.theme.AppBackground).statusBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("‹ Back", color = com.music.spotui.ui.theme.AppText, modifier = Modifier.clickable { navController.popBackStack() }.padding(vertical = 12.dp))
        Spacer(Modifier.height(8.dp))
        if(mix && anchors.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(22.dp))) {
                repeat(2) { row -> Row(Modifier.weight(1f)) {
                    repeat(2) { col -> GlideImage(model=anchors[(row*2+col)%anchors.size].coverUri,contentDescription="Mix artwork",contentScale=ContentScale.Crop,modifier=Modifier.weight(1f).fillMaxHeight()) }
                } }
            }
        } else com.music.spotui.ui.components.CollectionArtwork(seed?.coverUri.orEmpty(), "Radio artwork", Modifier)
        Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.Top) {
            Text(seed?.let { RadioSessions.stationName(it) } ?: "USpot Radio",
                color = com.music.spotui.ui.theme.AppText, fontSize = 27.sp, lineHeight = 33.sp,
                fontWeight = FontWeight.Bold, maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(top = 6.dp, end = 8.dp))
            com.music.spotui.ui.components.PinButton("radio:$seedId")
        }
        Text(if (mix) "${anchors.size} songs blended" else seed?.singer.orEmpty(),
            color = com.music.spotui.ui.theme.AppSecondary, fontSize = 14.sp,
            modifier = Modifier.padding(top = 6.dp))
        Text("USpot Radio · Continuous music", color = com.music.spotui.ui.theme.AppPalette,
            fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.music.spotui.ui.components.CollectionAction(painterResource(R.drawable.ic_player_shuffle), "Shuffle radio", shuffled, stationSongs.isNotEmpty()) {
                if (active) player.updateShuffleState(!shuffled)
                else seed?.let { player.resumeRadio(it, prepared, context, shuffled = true) }
            }
            com.music.spotui.ui.components.CollectionAction(painterResource(if (downloaded) R.drawable.added else R.drawable.ic_download), "Download current radio queue", downloaded, stationSongs.isNotEmpty()) {
                if (!downloaded) askDownload = true
            }
            Spacer(Modifier.weight(1f))
            com.music.spotui.ui.components.CollectionPlayButton(
                playing = active && player.currentSongPlayingState.value,
                enabled = stationSongs.isNotEmpty(),
                onClick = {
                    if (active) {
                        if (player.currentSongPlayingState.value) com.music.spotui.di.SongPlayer.pause() else com.music.spotui.di.SongPlayer.play()
                    } else seed?.let { player.resumeRadio(it, prepared, context, savedShuffle = savedShuffle) }
                })
        }
        if (loading) CircularProgressIndicator(Modifier.padding(top = 20.dp), color = Color(0xFF1ED760))
        com.music.spotui.ui.components.RadioTuningControls(seedId) { if (active) player.refreshRadioQueue() }
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
        ) { showDetails = !showDetails }, contentAlignment = Alignment.CenterStart) {
            Text(if (showDetails) "About this station  −" else "About this station  +",
                color = com.music.spotui.ui.theme.AppPalette, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        if (showDetails) {
            Text("Continuous suggestions shaped by your starting songs and Radio tuning. Your station keeps its name and remembers where you left off. Similarity is not a genre guarantee.",
                color = com.music.spotui.ui.theme.AppSecondary, fontSize = 13.sp, lineHeight = 20.sp)
            if (mix) {
                Text("IN THIS MIX", color = com.music.spotui.ui.theme.AppSecondary, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                anchors.forEach { Text("${it.title} · ${it.singer}", color = com.music.spotui.ui.theme.AppSecondary,
                    fontSize = 12.sp, modifier = Modifier.padding(bottom = 5.dp)) }
            }
        }
        if(active && refreshing)Text("Updating upcoming songs…",color=com.music.spotui.ui.theme.AppSecondary,modifier=Modifier.padding(top=8.dp))
        else if(active && refreshMessage.isNotBlank())Text(refreshMessage,color=com.music.spotui.ui.theme.AppSecondary,modifier=Modifier.padding(top=8.dp))
        if (!loading && stationSongs.isEmpty()) Text(if(com.music.spotui.data.preferences.RadioTaste.mode(context,seedId)=="close")"No direct Spotify suggestions returned. Try again or choose Balanced for broader suggestions." else "Recommendations are unavailable. Try again when connected.", color = com.music.spotui.ui.theme.AppSecondary, modifier = Modifier.padding(top = 20.dp))
        if(!loading && stationSongs.isEmpty())TextButton(onClick={loadRevision++}) { Text("Try again",color=com.music.spotui.ui.theme.AppPalette) }
        Spacer(Modifier.height(com.music.spotui.ui.navigation.LocalBottomOverlayInset.current + 24.dp))
    }
}
