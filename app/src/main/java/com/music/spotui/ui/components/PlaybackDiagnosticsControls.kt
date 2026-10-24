package com.music.spotui.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.spotui.ui.theme.*
import com.music.spotui.data.preferences.PlaybackDiagnostics
import com.music.spotui.data.preferences.RadioSkipMemory

@Composable fun PlaybackDiagnosticsControls() {
    val context=LocalContext.current
    val backupRevision by com.music.spotui.ui.viewmodel.BackupSignals.revision.collectAsState()
    var enabled by remember(backupRevision){mutableStateOf(RadioSkipMemory.enabled(context))}
    val revision by PlaybackDiagnostics.revision.collectAsState()
    var expanded by remember{mutableStateOf(false)}
    Row(Modifier.fillMaxWidth().padding(vertical=12.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Avoid repeatedly skipped songs",color=AppText)
            Text("Three manual skips before 30 seconds in a week hides a song from Radio and Smart Shuffle for seven days.",color=AppSecondary,fontSize=12.sp)
        }
        Switch(checked=enabled,onCheckedChange={enabled=it;RadioSkipMemory.setEnabled(context,it)})
    }
    TextButton(onClick={RadioSkipMemory.clear(context)}){Text("Reset skip protection",color=AppPalette)}
    TextButton(onClick={expanded=!expanded}){Text("Playback diagnostics ${if(expanded)"▴" else "▾"}",color=AppPalette)}
    if(expanded) {
        val report=remember(revision){"USpot ${com.music.spotui.BuildConfig.VERSION_NAME} build ${com.music.spotui.BuildConfig.VERSION_CODE}\n"+PlaybackDiagnostics.report(context)}
        Text("Local status and song names. Copy this report when playback fails; it contains no sign-in cookies or stream URLs.",color=AppSecondary,fontSize=12.sp)
        Row {
            TextButton(onClick={
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("USpot playback diagnostics",report))
                android.widget.Toast.makeText(context,"Diagnostics copied",android.widget.Toast.LENGTH_SHORT).show()
            }){Text("Copy report",color=AppPalette)}
            TextButton(onClick={com.music.spotui.di.SongPlayer.retryCurrent(context)}){Text("Retry song",color=AppPalette)}
            TextButton(onClick={PlaybackDiagnostics.clear(context)}){Text("Clear",color=AppSecondary)}
        }
        Text(report,color=AppSecondary,fontSize=11.sp,modifier=Modifier.padding(bottom=16.dp))
    }
}
