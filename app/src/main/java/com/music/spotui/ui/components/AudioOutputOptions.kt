package com.music.spotui.ui.components

data class AudioOutput(val id: String, val name: String, val selected: Boolean, val bluetooth: Boolean)

/** Preserve Android's selected route while keeping its local output reachable. */
internal object AudioOutputOptions {
    const val PHONE_ID = "local:default"

    fun includePhone(routes: List<AudioOutput>, phone: AudioOutput): List<AudioOutput> {
        val unique = routes.distinctBy { it.id }
        if (unique.any { it.id == PHONE_ID }) return unique
        return listOf(phone.copy(selected = phone.selected && unique.none { it.selected })) + unique
    }
}
