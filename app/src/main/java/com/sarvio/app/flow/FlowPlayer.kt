package com.sarvio.app.flow

import android.content.Context
import com.sarvio.app.SarvioApp
import com.sarvio.app.engine.Engine
import com.sarvio.app.player.PlayItem
import com.sarvio.app.player.PlayerConnection
import com.sarvio.app.util.Cookies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Streams YouTube items from the Flow screen in the built-in player (no download needed). */
object FlowPlayer {
    private val _busy = MutableStateFlow<String?>(null)
    /** Page link currently being prepared, for a loading indicator. */
    val busy: StateFlow<String?> = _busy

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private var job: Job? = null

    /** Cookies for the signed-in microG account, when Flow sign-in is on. */
    suspend fun cookieFile(context: Context): String? {
        val s = SarvioApp.instance.settings.current()
        if (!s.flowLogin || s.flowAccount == null) return null
        return withContext(Dispatchers.Main) { Cookies.exportFor(context.applicationContext, MicroG.YOUTUBE) }
    }

    fun isYoutube(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("youtube.com/") || u.contains("youtu.be/") || u.endsWith("youtube.com")
    }

    fun isYoutubeVideo(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("youtu.be/") || u.contains("/watch") || u.contains("/shorts/") || u.contains("/live/")
    }

    /**
     * Resolves [url] and plays it. [audioOnly] = background play: sound only, the player screen
     * stays closed and playback continues with the screen off.
     */
    fun play(context: Context, url: String, audioOnly: Boolean = false) {
        val app = context.applicationContext
        job?.cancel()
        _busy.value = url
        job = SarvioApp.instance.appScope.launch {
            try {
                val s = SarvioApp.instance.settings.current()
                val height = if (s.videoQuality <= 0) 1080 else s.videoQuality.coerceAtMost(1080)
                val info = Engine.resolveStream(app, url, cookieFile(app), audioOnly, height)
                val item = PlayItem(
                    uri = info.mediaUrl,
                    title = info.title,
                    artist = info.uploader,
                    isVideo = !audioOnly,
                    artworkUri = info.thumbnail,
                    sourceUrl = info.pageUrl,
                    id = info.pageUrl,
                    audioUri = info.audioUrl,
                    isStream = true,
                    hls = info.isHls,
                    userAgent = info.userAgent,
                )
                PlayerConnection.play(app, listOf(item), 0, openScreen = !audioOnly)
                if (audioOnly) _messages.tryEmit("Playing in background")
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                val msg = t.message.orEmpty()
                _messages.tryEmit(
                    when {
                        msg.contains("Sign in", true) || msg.contains("age", true) ->
                            "This video needs a signed-in account. Sign in with microG in Browse."
                        msg.contains("Unable to download", true) || msg.contains("timed out", true) ->
                            "Network problem. Check your connection and try again."
                        else -> "Couldn't play this video" +
                            (msg.lineSequence().lastOrNull { it.contains("ERROR") }?.substringAfter("ERROR:")?.trim()?.take(140)?.let { ": $it" } ?: "")
                    }
                )
            } finally {
                if (_busy.value == url) _busy.value = null
            }
        }
    }
}
