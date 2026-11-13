package com.sarvio.app.engine

/** One selectable video quality. */
data class VideoOption(
    val height: Int,
    val label: String,
    val fps: Int,
    val estimatedBytes: Long,
    val formatSpec: String,
)

/** One selectable audio-only output. */
data class AudioOption(
    val label: String,
    val format: String,   // mp3, m4a, opus, best
    val bitrate: String,  // "320", "192", "128", "0" (best)
    val estimatedBytes: Long,
)

/** A flat entry inside a playlist / channel / search result. */
data class Entry(
    val id: String?,
    val url: String,
    val title: String,
    val durationSec: Long,
    val thumbnail: String?,
    val uploader: String?,
    val viewCount: Long = 0,
    val isLive: Boolean = false,
)

/** Direct stream links for playing a page in the built-in player without downloading it. */
data class StreamInfo(
    val pageUrl: String,
    val title: String,
    val uploader: String?,
    val thumbnail: String?,
    val durationSec: Long,
    /** Video (or combined) stream; for audio-only playback this is the audio stream. */
    val mediaUrl: String,
    /** Separate audio stream to play alongside [mediaUrl], when the site splits them. */
    val audioUrl: String?,
    val isHls: Boolean,
    val userAgent: String?,
    val audioOnly: Boolean,
)

data class MediaInfo(
    val id: String?,
    val url: String,
    val title: String,
    val uploader: String?,
    val durationSec: Long,
    val thumbnail: String?,
    val site: String?,
    val isPlaylist: Boolean,
    val entries: List<Entry>,
    val videoOptions: List<VideoOption>,
    val audioOptions: List<AudioOption>,
    val subtitleLangs: List<String>,
    /** Full yt-dlp JSON saved to cache (single videos only), reused by the download. */
    val infoJsonPath: String? = null,
)
