package com.andrej.glasstv.ui.glass

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import com.andrej.glasstv.screensaver.Aerial
import com.andrej.glasstv.screensaver.loadAerials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlin.math.min

/**
 * "Apple Aerial" wallpaper: a hand-picked frame from one of Apple's aerials, a different one each
 * day, like the Top Shelf on Apple TV. Only the index and one keyframe are downloaded (~3 MB) and
 * the result is kept on disk.
 */
class AerialStills(private val context: Context) {
    private val dir = File(context.filesDir, "stills").apply { mkdirs() }
    private val pool: List<Aerial> by lazy { loadAerials(context).filter { it.still != null } }

    /** Every shot already on disk (the screen saver galleries show these). */
    fun cachedFiles(): List<File> = pool.mapNotNull { cached(it) }

    /** Downloads the shots not on disk yet, one at a time, reporting each as it arrives. */
    suspend fun fetchMissing(onEach: (File) -> Unit) {
        for (a in pool.shuffled()) {
            if (cached(a) != null) continue
            fetch(a)?.let(onEach)
        }
    }

    /** The aerial for a given day (0 = today). */
    fun forDay(offset: Int = 0): Aerial {
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }
        val day = c.get(Calendar.YEAR) * 366 + c.get(Calendar.DAY_OF_YEAR)
        return pool[day % pool.size]
    }

    fun cached(a: Aerial): File? = file(a).takeIf { it.exists() && it.length() > 0 }

    suspend fun fetch(a: Aerial): File? = withContext(Dispatchers.IO) {
        cached(a)?.let { return@withContext it }
        val retriever = MediaMetadataRetriever()
        val source = HttpRangeSource(a.avc)
        runCatching {
            retriever.setDataSource(source)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            val us = (durationMs * 1000L * (a.still ?: 0.3f)).toLong()
            val frame = if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC, 1920, 1080)
            } else {
                retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)
            }!!
            val tmp = File(dir, "${a.id}.tmp")
            tmp.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            frame.recycle()
            tmp.renameTo(file(a))
            file(a)
        }.also {
            retriever.release()
            source.close()
        }.getOrNull()
    }

    private fun file(a: Aerial) = File(dir, "${a.id}.jpg")

}

/** Random access over HTTP range requests, in 512 KB blocks, so the retriever never downloads the whole video. */
private class HttpRangeSource(private val url: String) : MediaDataSource() {
    private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 24
    }
    private var length = -1L

    override fun getSize(): Long {
        if (length < 0) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "HEAD"
            length = c.contentLengthLong
            c.disconnect()
        }
        return length
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= getSize()) return -1
        var copied = 0
        while (copied < size && position + copied < length) {
            val p = position + copied
            val index = p / BLOCK
            val block = blocks.getOrPut(index) { fetch(index) }
            val inBlock = (p - index * BLOCK).toInt()
            val n = min(size - copied, block.size - inBlock)
            if (n <= 0) break
            System.arraycopy(block, inBlock, buffer, offset + copied, n)
            copied += n
        }
        return copied
    }

    private fun fetch(index: Long): ByteArray {
        val start = index * BLOCK
        val end = min(length, start + BLOCK) - 1
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Range", "bytes=$start-$end")
        return try {
            c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    override fun close() = blocks.clear()

    private companion object {
        const val BLOCK = 512L * 1024
    }
}
