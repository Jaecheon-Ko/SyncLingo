package com.synclingo.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LibraryItem(
    val id: String,
    val dir: File,
    val title: String,
    val channel: String,
    val durationSec: Long,
    val url: String,
    val addedAt: Long,
    val media: File?,
    val thumb: File?,
    val subtitle: File?,
    val translation: File?,
) {
    val isAudioOnly: Boolean get() = (media?.extension?.lowercase() ?: "") in setOf("m4a", "mp3", "opus", "ogg", "aac", "wav", "flac")
}

object Library {
    /** Bumped whenever the library changes so the UI reloads. */
    val version = MutableStateFlow(0)

    private val MEDIA_EXT = setOf("mp4", "webm", "mkv", "m4a", "mp3", "opus", "ogg", "aac", "wav", "flac", "mov", "3gp")
    private val SUB_EXT = setOf("json3", "vtt", "srt")
    private val IMG_EXT = setOf("jpg", "jpeg", "png", "webp")

    fun root(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "library").apply { mkdirs() }

    fun dirFor(ctx: Context, id: String) = File(root(ctx), id)

    fun notifyChanged() { version.value++ }

    fun list(ctx: Context): List<LibraryItem> =
        root(ctx).listFiles()?.filter { it.isDirectory && !File(it, ".incomplete").exists() }
            ?.mapNotNull { load(it) }
            ?.sortedByDescending { it.addedAt }
            ?: emptyList()

    fun get(ctx: Context, id: String): LibraryItem? = load(dirFor(ctx, id))

    fun load(dir: File): LibraryItem? {
        if (!dir.isDirectory) return null
        val files = dir.listFiles()?.toList() ?: return null
        val media = files.firstOrNull { it.name.startsWith("media.") && it.extension.lowercase() in MEDIA_EXT && !it.name.contains(".part") }
            ?: files.firstOrNull { it.extension.lowercase() in MEDIA_EXT && !it.name.contains(".part") }
        val thumb = files.firstOrNull { it.extension.lowercase() in IMG_EXT }
        val subs = files.filter { it.extension.lowercase() in SUB_EXT }
        val enSubs = subs.filter { it.name.startsWith("sub.") }
        val subtitle = pickSubtitle(enSubs.ifEmpty { subs.filter { !it.name.startsWith("ko.") } })
        val translation = pickSubtitle(subs.filter { it.name.startsWith("ko.") })
        val meta = File(dir, "meta.json").takeIf { it.exists() }?.let {
            try { JSONObject(it.readText()) } catch (e: Exception) { null }
        } ?: JSONObject()
        if (media == null) return null
        return LibraryItem(
            id = dir.name,
            dir = dir,
            title = meta.optString("title").ifBlank { dir.name },
            channel = meta.optString("channel"),
            durationSec = meta.optLong("duration", 0),
            url = meta.optString("url"),
            addedAt = meta.optLong("addedAt", dir.lastModified()),
            media = media,
            thumb = thumb,
            subtitle = subtitle,
            translation = translation,
        )
    }

    private fun pickSubtitle(files: List<File>): File? {
        if (files.isEmpty()) return null
        fun langRank(f: File): Int {
            val parts = f.name.split(".")
            val lang = if (parts.size >= 3) parts[parts.size - 2] else ""
            return when (lang) {
                "en" -> 0; "en-US" -> 1; "en-GB" -> 2; "ko" -> 0; "en-orig" -> 4
                else -> if (lang.startsWith("en")) 3 else 5
            }
        }
        fun extRank(f: File) = when (f.extension.lowercase()) { "json3" -> 0; "vtt" -> 1; else -> 2 }
        return files.minWith(compareBy<File>({ langRank(it) }, { extRank(it) }))
    }

    fun writeMeta(dir: File, title: String, channel: String, duration: Long, url: String) {
        val o = JSONObject()
            .put("title", title).put("channel", channel).put("duration", duration)
            .put("url", url).put("addedAt", System.currentTimeMillis())
        File(dir, "meta.json").writeText(o.toString())
    }

    fun delete(item: LibraryItem) {
        item.dir.deleteRecursively()
        notifyChanged()
    }

    // ---------- per-item state: bookmarks & last position ----------

    fun bookmarks(item: LibraryItem): Set<Long> {
        val f = File(item.dir, "bookmarks.json")
        if (!f.exists()) return emptySet()
        return try {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { a.getLong(it) }.toSet()
        } catch (e: Exception) { emptySet() }
    }

    fun saveBookmarks(item: LibraryItem, set: Set<Long>) {
        File(item.dir, "bookmarks.json").writeText(JSONArray(set.sorted()).toString())
    }

    fun lastPosition(item: LibraryItem): Long =
        File(item.dir, "position.txt").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L

    fun savePosition(item: LibraryItem, ms: Long) {
        try { File(item.dir, "position.txt").writeText(ms.toString()) } catch (_: Exception) {}
    }

    // ---------- import from local files ----------

    fun displayName(ctx: Context, uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i)?.let { return it }
            }
        }
        return uri.lastPathSegment ?: "file"
    }

    /** Copies a local media file (+ optional subtitle files) into the library. */
    fun import(ctx: Context, media: Uri, subtitle: Uri?, translation: Uri?, title: String): String {
        val id = "local_" + System.currentTimeMillis()
        val dir = dirFor(ctx, id).apply { mkdirs() }
        val marker = File(dir, ".incomplete").apply { createNewFile() }
        try {
            val mName = displayName(ctx, media)
            val mExt = mName.substringAfterLast('.', "mp4").lowercase().let { if (it in MEDIA_EXT) it else "mp4" }
            copy(ctx, media, File(dir, "media.$mExt"))
            subtitle?.let {
                val ext = displayName(ctx, it).substringAfterLast('.', "srt").lowercase().let { e -> if (e in SUB_EXT) e else "srt" }
                copy(ctx, it, File(dir, "sub.en.$ext"))
            }
            translation?.let {
                val ext = displayName(ctx, it).substringAfterLast('.', "srt").lowercase().let { e -> if (e in SUB_EXT) e else "srt" }
                copy(ctx, it, File(dir, "ko.ko.$ext"))
            }
            writeMeta(dir, title.ifBlank { mName.substringBeforeLast('.') }, "내 파일", 0, "")
            marker.delete()
            notifyChanged()
            return id
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    private fun copy(ctx: Context, uri: Uri, dest: File) {
        ctx.contentResolver.openInputStream(uri)!!.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
    }
}
