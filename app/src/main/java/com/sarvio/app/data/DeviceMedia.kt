package com.sarvio.app.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DeviceItem(
    val uri: Uri,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val size: Long,
    val isVideo: Boolean,
    /** Name of the folder the file is in, e.g. "WhatsApp Video". */
    val folder: String?,
    val dateAdded: Long,
    /** Full folder path, used to tell apart folders that share a name. */
    val folderPath: String? = null,
    val album: String? = null,
)

/** A folder on the phone that holds media, with its files. */
data class DeviceFolder(
    val key: String,
    val name: String,
    val path: String?,
    val items: List<DeviceItem>,
) {
    val totalSize: Long get() = items.sumOf { it.size }
}

/** Reads videos and music already on the phone, so Sarvio works as the only player. */
object DeviceMedia {

    @Suppress("DEPRECATION")
    private val DATA = MediaStore.MediaColumns.DATA

    suspend fun videos(context: Context): List<DeviceItem> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.DATE_ADDED,
            DATA,
        )
        val out = mutableListOf<DeviceItem>()
        runCatching {
            context.contentResolver.query(collection, projection, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val name = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val size = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val bucket = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val added = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val data = c.getColumnIndex(DATA)
                while (c.moveToNext()) {
                    val path = parentOf(c, data)
                    out += DeviceItem(
                        uri = ContentUris.withAppendedId(collection, c.getLong(id)),
                        title = c.getString(name)?.substringBeforeLast('.') ?: "Video",
                        artist = null,
                        durationMs = c.getLong(dur),
                        size = c.getLong(size),
                        isVideo = true,
                        folder = c.getString(bucket) ?: path?.substringAfterLast('/'),
                        dateAdded = c.getLong(added),
                        folderPath = path,
                    )
                }
            }
        }
        out
    }

    suspend fun audio(context: Context): List<DeviceItem> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        // Audio got a bucket (folder) column only in Android 10; older phones use the file path.
        val bucketCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Audio.Media.BUCKET_DISPLAY_NAME else null
        val projection = listOfNotNull<String>(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DATE_ADDED, DATA, bucketCol,
        ).toTypedArray()
        val out = mutableListOf<DeviceItem>()
        runCatching {
            context.contentResolver.query(
                collection, projection, "${MediaStore.Audio.Media.DURATION} > 15000", null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val dur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val size = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val added = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val data = c.getColumnIndex(DATA)
                val bucket = bucketCol?.let { c.getColumnIndex(it) } ?: -1
                while (c.moveToNext()) {
                    val path = parentOf(c, data)
                    val bucketName = if (bucket >= 0) c.getString(bucket) else null
                    out += DeviceItem(
                        uri = ContentUris.withAppendedId(collection, c.getLong(id)),
                        title = c.getString(title) ?: "Track",
                        artist = c.getString(artist)?.takeUnless { it == "<unknown>" },
                        durationMs = c.getLong(dur),
                        size = c.getLong(size),
                        isVideo = false,
                        folder = bucketName ?: path?.substringAfterLast('/'),
                        dateAdded = c.getLong(added),
                        folderPath = path,
                        album = c.getString(album)?.takeUnless { it == "<unknown>" },
                    )
                }
            }
        }
        out
    }

    /** Groups files by the folder they are in, folders sorted by name. */
    fun folders(items: List<DeviceItem>): List<DeviceFolder> =
        items.groupBy { it.folderPath ?: it.folder ?: "" }
            .map { (key, list) ->
                DeviceFolder(
                    key = key,
                    name = list.first().folder?.ifBlank { null } ?: key.substringAfterLast('/').ifBlank { "Other" },
                    path = list.first().folderPath,
                    items = list,
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    private fun parentOf(c: Cursor, dataIdx: Int): String? {
        if (dataIdx < 0) return null
        val p = c.getString(dataIdx) ?: return null
        return p.substringBeforeLast('/', "").ifBlank { null }
    }
}
