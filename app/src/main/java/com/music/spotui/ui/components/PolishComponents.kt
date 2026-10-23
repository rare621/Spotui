package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.music.spotui.ui.theme.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object ActionFeedback {
    data class Notice(val text: String, val undo: (() -> Unit)? = null)
    private val events = MutableSharedFlow<Notice>(extraBufferCapacity = 8)
    val messages = events.asSharedFlow()
    fun show(text: String, undo: (() -> Unit)? = null) { events.tryEmit(Notice(text, undo)) }
}

@Composable
fun LoadingRows(count: Int = 5) {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), color = AppPalette, strokeWidth = 2.dp)
        repeat(count) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(AppRaised))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.fillMaxWidth(.8f).height(13.dp).clip(RoundedCornerShape(4.dp)).background(AppRaised))
                    Box(Modifier.fillMaxWidth(.5f).height(10.dp).clip(RoundedCornerShape(4.dp)).background(AppRaised))
                }
            }
        }
    }
}

@Composable
fun RetryPanel(title: String, onRetry: () -> Unit) {
    EmptyState("Couldn’t load this", title, "Try again", onRetry)
}

@Composable
fun EmptyState(title: String, message: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = AppText, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = AppSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) TextButton(onClick = onAction) { Text(action, color = AppPalette) }
    }
}
