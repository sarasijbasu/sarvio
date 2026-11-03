package com.sarvio.app.player

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset

/** One chapter of a media file. [endMs] is -1 when it runs to the end of the file. */
data class Chapter(val startMs: Long, val endMs: Long, val title: String)

/**
 * Reads chapter markers embedded in a local media file. ExoPlayer does not expose them,
 * so the formats yt-dlp / FFmpeg write are parsed directly:
 *  - MP4 / M4A / MOV: the Nero `chpl` box inside moov/udta
 *  - MP3: ID3v2 `CHAP` frames
 *  - Ogg / Opus: `CHAPTERxxx` / `CHAPTERxxxNAME` comments
 * Unknown formats or files without chapters return an empty list.
 */
object ChapterReader {

    fun read(context: Context, uri: Uri): List<Chapter> = runCatching {
        open(context, uri)?.use { ch ->
            val head = ByteArray(12)
            val n = readAt(ch, 0, head)
            if (n < 8) return@use emptyList()
            val chapters = when {
                head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() -> readId3(ch)
                head[0] == 'O'.code.toByte() && head[1] == 'g'.code.toByte() && head[2] == 'g'.code.toByte() && head[3] == 'S'.code.toByte() -> readOgg(ch)
                String(head, 4, 4, Charsets.ISO_8859_1) in MP4_TOP -> readMp4(ch)
                else -> emptyList()
            }
            normalise(chapters)
        } ?: emptyList()
    }.getOrDefault(emptyList())

    private val MP4_TOP = setOf("ftyp", "moov", "mdat", "free", "wide", "skip")

    /** Wraps a channel with the stream that owns it, so both are closed together. */
    private class Source(val stream: FileInputStream, val channel: FileChannel, val pfd: android.os.ParcelFileDescriptor?) : java.io.Closeable {
        override fun close() {
            runCatching { channel.close() }
            runCatching { stream.close() }
            runCatching { pfd?.close() }
        }
    }

    private fun open(context: Context, uri: Uri): Source? = when (uri.scheme) {
        "file", null -> uri.path?.let { File(it) }?.takeIf { it.isFile }?.let {
            val s = FileInputStream(it); Source(s, s.channel, null)
        }
        "content" -> context.contentResolver.openFileDescriptor(uri, "r")?.let { pfd ->
            val s = FileInputStream(pfd.fileDescriptor); Source(s, s.channel, pfd)
        }
        else -> null // streams (http) are not scanned
    }

    private fun readAt(src: Source, pos: Long, dst: ByteArray): Int {
        val buf = ByteBuffer.wrap(dst)
        var total = 0
        while (buf.hasRemaining()) {
            val r = src.channel.read(buf, pos + total)
            if (r <= 0) break
            total += r
        }
        return total
    }

    private fun normalise(list: List<Chapter>): List<Chapter> {
        val sorted = list.filter { it.startMs >= 0 }.sortedBy { it.startMs }.distinctBy { it.startMs }
        // A single chapter covering the whole file is not useful.
        if (sorted.size < 2) return emptyList()
        return sorted.mapIndexed { i, c ->
            val end = sorted.getOrNull(i + 1)?.startMs ?: c.endMs.takeIf { it > c.startMs } ?: -1L
            c.copy(endMs = end, title = c.title.trim().ifBlank { "Chapter ${i + 1}" })
        }
    }

    // ---------- MP4 ----------

    private fun readMp4(src: Source): List<Chapter> {
        val size = src.channel.size()
        val moov = findBox(src, 0, size, "moov") ?: return emptyList()
        val udta = findBox(src, moov.first, moov.second, "udta") ?: return emptyList()
        val chpl = findBox(src, udta.first, udta.second, "chpl") ?: return emptyList()
        val len = (chpl.second - chpl.first).toInt()
        if (len < 9 || len > 1_000_000) return emptyList()
        val b = ByteArray(len)
        readAt(src, chpl.first, b)
        val bb = ByteBuffer.wrap(b)
        val version = bb.get().toInt() and 0xFF
        bb.position(4) // version + flags
        if (version == 1) bb.int // reserved
        val count = bb.get().toInt() and 0xFF
        val out = ArrayList<Chapter>(count)
        repeat(count) {
            if (bb.remaining() < 9) return out
            val start100ns = bb.long
            val tl = bb.get().toInt() and 0xFF
            if (bb.remaining() < tl) return out
            val t = ByteArray(tl); bb.get(t)
            out += Chapter(start100ns / 10_000, -1, String(t, Charsets.UTF_8))
        }
        return out
    }

    /** Returns the payload range (start, end) of the first child box named [type] in [from, to). */
    private fun findBox(src: Source, from: Long, to: Long, type: String): Pair<Long, Long>? {
        var pos = from
        val hdr = ByteArray(16)
        while (pos + 8 <= to) {
            if (readAt(src, pos, hdr) < 8) return null
            val bb = ByteBuffer.wrap(hdr)
            var boxSize = bb.int.toLong() and 0xFFFFFFFFL
            val name = String(hdr, 4, 4, Charsets.ISO_8859_1)
            var headerLen = 8L
            if (boxSize == 1L) { boxSize = ByteBuffer.wrap(hdr, 8, 8).long; headerLen = 16 }
            else if (boxSize == 0L) boxSize = to - pos
            if (boxSize < headerLen) return null
            if (name == type) return (pos + headerLen) to minOf(pos + boxSize, to)
            pos += boxSize
        }
        return null
    }

    // ---------- MP3 (ID3v2) ----------

    private fun readId3(src: Source): List<Chapter> {
        val h = ByteArray(10)
        readAt(src, 0, h)
        val major = h[3].toInt()
        if (major !in 3..4) return emptyList()
        val flags = h[5].toInt()
        val tagSize = syncsafe(h, 6)
        if (tagSize <= 0 || tagSize > 32_000_000) return emptyList()
        val tag = ByteArray(tagSize)
        readAt(src, 10, tag)
        var p = 0
        if (flags and 0x40 != 0) { // extended header
            p = if (major == 4) syncsafe(tag, 0) else (ByteBuffer.wrap(tag, 0, 4).int + 4)
        }
        val out = ArrayList<Chapter>()
        while (p + 10 <= tag.size) {
            val id = String(tag, p, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val size = if (major == 4) syncsafe(tag, p + 4) else ByteBuffer.wrap(tag, p + 4, 4).int
            val body = p + 10
            if (size <= 0 || body + size > tag.size) break
            if (id == "CHAP") parseChap(tag, body, size, major)?.let { out += it }
            p = body + size
        }
        return out
    }

    private fun parseChap(tag: ByteArray, start: Int, size: Int, major: Int): Chapter? {
        val end = start + size
        var p = start
        while (p < end && tag[p] != 0.toByte()) p++ // element id
        p++
        if (p + 16 > end) return null
        val bb = ByteBuffer.wrap(tag, p, 16)
        val startMs = bb.int.toLong() and 0xFFFFFFFFL
        val endMs = bb.int.toLong() and 0xFFFFFFFFL
        p += 16
        var title = ""
        while (p + 10 <= end) {
            val id = String(tag, p, 4, Charsets.ISO_8859_1)
            val sz = if (major == 4) syncsafe(tag, p + 4) else ByteBuffer.wrap(tag, p + 4, 4).int
            val body = p + 10
            if (sz <= 0 || body + sz > end) break
            if (id == "TIT2") title = decodeText(tag, body, sz)
            p = body + sz
        }
        return Chapter(startMs, endMs, title)
    }

    private fun decodeText(b: ByteArray, start: Int, len: Int): String {
        if (len < 1) return ""
        val cs: Charset = when (b[start].toInt()) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(b, start + 1, len - 1, cs).trimEnd('\u0000').substringBefore('\u0000')
    }

    private fun syncsafe(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    // ---------- Ogg (Opus / Vorbis comments) ----------

    private fun readOgg(src: Source): List<Chapter> {
        // Rebuild the first two packets of the first stream: id header, then comment header.
        val packets = ArrayList<ByteArray>()
        val current = java.io.ByteArrayOutputStream()
        var pos = 0L
        val hdr = ByteArray(27)
        var serial: Int? = null
        val limit = minOf(src.channel.size(), 4_000_000L)
        while (pos + 27 <= limit && packets.size < 2) {
            if (readAt(src, pos, hdr) < 27) break
            if (String(hdr, 0, 4, Charsets.ISO_8859_1) != "OggS") break
            val pageSerial = ByteBuffer.wrap(hdr, 14, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            val segs = hdr[26].toInt() and 0xFF
            val lace = ByteArray(segs)
            readAt(src, pos + 27, lace)
            val dataLen = lace.sumOf { it.toInt() and 0xFF }
            val data = ByteArray(dataLen)
            readAt(src, pos + 27 + segs, data)
            if (serial == null) serial = pageSerial
            if (pageSerial == serial) {
                var off = 0
                for (l in lace) {
                    val v = l.toInt() and 0xFF
                    current.write(data, off, v)
                    off += v
                    if (v < 255) { packets += current.toByteArray(); current.reset(); if (packets.size >= 2) break }
                }
            }
            pos += 27 + segs + dataLen
        }
        val comment = packets.getOrNull(1) ?: return emptyList()
        val skip = when {
            comment.size > 8 && String(comment, 0, 8, Charsets.ISO_8859_1) == "OpusTags" -> 8
            comment.size > 7 && comment[0] == 3.toByte() && String(comment, 1, 6, Charsets.ISO_8859_1) == "vorbis" -> 7
            else -> return emptyList()
        }
        val bb = ByteBuffer.wrap(comment).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.position(skip)
        val vendorLen = bb.int
        if (vendorLen < 0 || vendorLen > bb.remaining()) return emptyList()
        bb.position(bb.position() + vendorLen)
        val n = bb.int
        val times = HashMap<String, Long>()
        val names = HashMap<String, String>()
        repeat(n.coerceIn(0, 10_000)) {
            if (bb.remaining() < 4) return@repeat
            val l = bb.int
            if (l < 0 || l > bb.remaining()) return@repeat
            val s = ByteArray(l); bb.get(s)
            val kv = String(s, Charsets.UTF_8)
            val key = kv.substringBefore('=').uppercase()
            val value = kv.substringAfter('=', "")
            if (key.startsWith("CHAPTER")) {
                if (key.endsWith("NAME")) names[key.removeSuffix("NAME")] = value
                else parseClock(value)?.let { times[key] = it }
            }
        }
        return times.map { (k, t) -> Chapter(t, -1, names[k].orEmpty()) }
    }

    /** "HH:MM:SS.mmm" → milliseconds. */
    private fun parseClock(v: String): Long? {
        val parts = v.trim().split(':')
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val s = parts[2].toDoubleOrNull() ?: return null
        return h * 3_600_000 + m * 60_000 + (s * 1000).toLong()
    }
}
