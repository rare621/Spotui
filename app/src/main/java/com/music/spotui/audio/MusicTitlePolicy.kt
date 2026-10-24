package com.music.spotui.audio

/** Collaboration credits and upload labels aren't alternate recordings. Keep remix/live/etc. */
object MusicTitlePolicy {
    private val credits = Regex("""\s*[\(\[]\s*(?:feat\.?|ft\.?|with)\s+[^)\]]*[\)\]]""", RegexOption.IGNORE_CASE)
    private val labels = Regex("""\s*[\(\[]\s*(?:(?:official\s+)?(?:(?:music|lyric|lyrics)\s+)?(?:audio|video|visualizer|lyrics)|hd|hq|4k)\s*[\)\]]""", RegexOption.IGNORE_CASE)
    private val suffix = Regex("""\s+(?:official\s+(?:audio|music video|video|lyric video|visualizer)|lyric video|visualizer)\s*$""", RegexOption.IGNORE_CASE)
    fun clean(value: String): String = value.replace(credits, "").replace(labels, "").replace(suffix, "").trim().ifBlank { value.trim() }
}
