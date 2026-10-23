package com.music.spotui.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.music.spotui.data.preferences.LibraryPins
import com.music.spotui.ui.theme.*

@Composable
fun PinButton(key:String) {
    val context=LocalContext.current
    val revision by LibraryPins.revision.collectAsState()
    val pinned=remember(key,revision){LibraryPins.contains(context,key)}
    IconButton(onClick={LibraryPins.toggle(context,key);ActionFeedback.show(if(pinned)"Unpinned"else"Pinned to your library")}) {
        Icon(if(pinned)Icons.Default.PushPin else Icons.Outlined.PushPin,modifier=Modifier.size(19.dp),contentDescription=if(pinned)"Unpin from library"else"Pin to library",tint=if(pinned)AppPalette else AppSecondary)
    }
}
