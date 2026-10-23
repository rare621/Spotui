package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.ui.theme.*

/** Keep the artwork slot intentional even when the catalog has no image or an image fails. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun LibraryCollectionArtwork(url: String, title: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(AppRaised), contentAlignment = Alignment.Center) {
        val icon = when {
            title.equals("Downloaded", true) -> Icons.Default.Download
            title.equals("Liked Songs", true) -> Icons.Default.Favorite
            else -> Icons.Default.MusicNote
        }
        Icon(icon, contentDescription = null, tint = AppPalette, modifier = Modifier.size(24.dp))
        if (url.isNotBlank()) GlideImage(model = url, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, contentDescription = null)
    }
}
