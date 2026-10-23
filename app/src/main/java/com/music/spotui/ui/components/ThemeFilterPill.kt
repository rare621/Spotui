package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.spotui.ui.theme.*

@Composable
fun ThemeFilterPill(label: String, selected: Boolean = false, onClick: () -> Unit) {
    val pillColor by androidx.compose.animation.animateColorAsState(if(selected)Color(0xFF1ED760)else AppRaised, androidx.compose.animation.core.tween(180), label="Selected tab")
    val textColor by androidx.compose.animation.animateColorAsState(if(selected)Color.Black else AppText, androidx.compose.animation.core.tween(180), label="Tab text")
    androidx.compose.foundation.layout.Box(
        modifier=Modifier.heightIn(min=48.dp).semantics {this.selected=selected}
            .clickable(indication=null,interactionSource=androidx.compose.runtime.remember{androidx.compose.foundation.interaction.MutableInteractionSource()},role=androidx.compose.ui.semantics.Role.Tab,onClick=onClick),
        contentAlignment=androidx.compose.ui.Alignment.Center
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.clip(RoundedCornerShape(22.dp)).background(pillColor).padding(horizontal=14.dp,vertical=8.dp),
            contentAlignment=androidx.compose.ui.Alignment.Center
        ) { Text(label,color=textColor,fontWeight=FontWeight.SemiBold,fontSize=14.sp) }
    }
}
