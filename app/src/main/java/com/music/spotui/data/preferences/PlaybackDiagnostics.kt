package com.music.spotui.data.preferences

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Small local status report with song display metadata. No URLs, tokens, cookies or raw queries. */
object PlaybackDiagnostics {
    val revision=MutableStateFlow(0L)
    private fun prefs(c:Context)=c.getSharedPreferences("playback_diagnostics",0)
    private fun rows(c:Context)=runCatching{JSONArray(prefs(c).getString("events","[]"))}.getOrDefault(JSONArray())
    @Synchronized fun record(c:Context,event:String,detail:String="",song:String=com.music.spotui.audio.PlaybackDiagnosticSong.current()) {
        // Call sites pass fixed stage names and class names/status codes only.
        val safeEvent=event.replace(Regex("[^A-Za-z0-9 _.-]"),"").take(70)
        val safeDetail=detail.takeIf{it.matches(Regex("[A-Za-z0-9 _.,:()-]{0,100}"))}.orEmpty()
        val label=com.music.spotui.audio.DiagnosticSongLabel.sanitize(song)
        val old=rows(c);val next=JSONArray().put(JSONObject().put("time",System.currentTimeMillis()).put("event",safeEvent).put("detail",safeDetail).put("song",label))
        for(i in 0 until minOf(old.length(),49))next.put(old.getJSONObject(i))
        prefs(c).edit().putString("events",next.toString()).apply();revision.update{it+1}
    }
    fun report(c:Context):String {
        val list=rows(c)
        return "USpot playback diagnostics\nLocal events include song names; no credentials or stream URLs\n"+(0 until list.length()).joinToString("\n") {
            val row=list.getJSONObject(it)
            java.text.SimpleDateFormat("MM-dd HH:mm:ss",java.util.Locale.US).format(java.util.Date(row.optLong("time")))+"  "+row.optString("event")+"  "+row.optString("detail")+
                row.optString("song").takeIf { it.isNotBlank() }?.let { "  | $it" }.orEmpty()
        }
    }
    fun clear(c:Context){prefs(c).edit().clear().apply();revision.update{it+1}}
}
