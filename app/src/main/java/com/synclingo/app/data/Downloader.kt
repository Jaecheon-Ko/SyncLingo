package com.synclingo.app.data

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Wraps the bundled yt-dlp (python + ffmpeg + quickjs) engine. */
object Engine {
    enum class State { INITIALIZING, READY, FAILED }

    val state = MutableStateFlow(State.INITIALIZING)
    val version = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                version.value = YoutubeDL.getInstance().version(app)
                state.value = State.READY
                autoUpdate(app)
            } catch (e: Throwable) {
                error.value = e.message ?: e.toString()
                state.value = State.FAILED
            }
        }
    }

    /** Silently update yt-dlp at most once a day: YouTube changes often break old versions. */
    private fun autoUpdate(ctx: Context) {
        val prefs = ctx.getSharedPreferences("engine", Context.MODE_PRIVATE)
        val last = prefs.getLong("lastUpdate", 0)
        if (System.currentTimeMillis() - last < 24 * 3600_000L) return
        try {
            YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
            version.value = YoutubeDL.getInstance().version(ctx)
            prefs.edit().putLong("lastUpdate", System.currentTimeMillis()).apply()
        } catch (_: Throwable) {
        }
    }

    suspend fun update(ctx: Context, nightly: Boolean): String = withContext(Dispatchers.IO) {
        try {
            val channel: YoutubeDL.UpdateChannel =
                if (nightly) YoutubeDL.UpdateChannel.NIGHTLY else YoutubeDL.UpdateChannel.STABLE
            val status = YoutubeDL.getInstance().updateYoutubeDL(ctx, channel)
            version.value = YoutubeDL.getInstance().version(ctx)
            ctx.getSharedPreferences("engine", Context.MODE_PRIVATE).edit()
                .putLong("lastUpdate", System.currentTimeMillis()).apply()
            when (status) {
                YoutubeDL.UpdateStatus.DONE -> "업데이트 완료 (${version.value})"
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "이미 최신 버전입니다 (${version.value})"
                else -> "업데이트 결과를 확인할 수 없습니다"
            }
        } catch (e: Throwable) {
            "업데이트 실패: ${e.message?.take(200)}"
        }
    }
}

data class DownloadJob(
    val id: String,
    val url: String,
    val audioOnly: Boolean,
    val status: Status = Status.QUEUED,
    val progress: Float = 0f,
    val stage: String = "대기 중",
    val title: String? = null,
    val error: String? = null,
) {
    enum class Status { QUEUED, RUNNING, DONE, FAILED }
    val active get() = status == Status.QUEUED || status == Status.RUNNING
}

object Downloader {
    val jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    private val lock = Mutex()

    private val URL_RE = Regex("""https?://\S+""")
    private val ID_RE = Regex("""(?:v=|/shorts/|/live/|/embed/|youtu\.be/)([A-Za-z0-9_-]{11})""")

    fun extractUrl(text: String): String? = URL_RE.find(text)?.value ?: text.trim().takeIf { it.isNotEmpty() }

    fun extractVideoId(url: String): String? =
        ID_RE.find(url)?.groupValues?.get(1)
            ?: url.trim().takeIf { Regex("""^[A-Za-z0-9_-]{11}$""").matches(it) }

    private fun updateJob(id: String, f: (DownloadJob) -> DownloadJob) =
        jobs.update { list -> list.map { if (it.id == id) f(it) else it } }

    fun dismiss(id: String) = jobs.update { list -> list.filterNot { it.id == id && !it.active } }

    /** Returns an error message, or null when the job was queued. */
    fun enqueue(ctx: Context, input: String, audioOnly: Boolean): String? {
        val url = extractUrl(input) ?: return "링크를 입력하세요"
        val vid = extractVideoId(url) ?: return "유튜브 영상 링크가 아닙니다"
        if (jobs.value.any { it.id == vid && it.active }) return "이미 다운로드 중입니다"
        val app = ctx.applicationContext
        jobs.update { list -> list.filterNot { it.id == vid } + DownloadJob(vid, url, audioOnly) }
        Engine.scope.launch { lock.withLock { run(app, vid) } }
        return null
    }

    private fun run(ctx: Context, id: String) {
        val job = jobs.value.firstOrNull { it.id == id } ?: return
        updateJob(id) { it.copy(status = DownloadJob.Status.RUNNING, stage = "엔진 준비 중") }
        // wait for engine
        var waited = 0
        while (Engine.state.value == Engine.State.INITIALIZING && waited < 120_000) {
            Thread.sleep(250); waited += 250
        }
        if (Engine.state.value != Engine.State.READY) {
            updateJob(id) { it.copy(status = DownloadJob.Status.FAILED, error = "다운로드 엔진 초기화 실패: ${Engine.error.value}") }
            return
        }

        val dir = Library.dirFor(ctx, id)
        dir.deleteRecursively()
        dir.mkdirs()
        val marker = File(dir, ".incomplete").apply { createNewFile() }
        val watchUrl = "https://www.youtube.com/watch?v=$id"

        val format = if (job.audioOnly) "ba[ext=m4a]/ba/b"
        else "bv*[height<=720][ext=mp4][vcodec^=avc1]+ba[ext=m4a]/" +
            "bv*[height<=720][ext=mp4]+ba[ext=m4a]/" +
            "b[height<=720][ext=mp4]/bv*[height<=720]+ba/b"

        val req = YoutubeDLRequest(watchUrl).apply {
            addOption("-f", format)
            if (!job.audioOnly) addOption("--merge-output-format", "mp4")
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--no-part")
            addOption("-o", File(dir, "media.%(ext)s").absolutePath)
            addOption("--write-subs")
            addOption("--write-auto-subs")
            addOption("--sub-langs", "en,en-US,en-GB,en-orig")
            addOption("--sub-format", "json3/vtt/best")
            addOption("-o", "subtitle:" + File(dir, "sub.%(ext)s").absolutePath)
            addOption("--write-thumbnail")
            addOption("--convert-thumbnails", "jpg")
            addOption("-o", "thumbnail:" + File(dir, "thumb.%(ext)s").absolutePath)
            addOption("--write-info-json")
            addOption("-o", "infojson:" + File(dir, "info").absolutePath)
            addOption("--newline")
        }

        val lastLines = ArrayDeque<String>()
        var failure: Throwable? = null
        try {
            YoutubeDL.getInstance().execute(req, id, false) { progress, _, line ->
                if (line.isNotBlank()) {
                    lastLines.addLast(line); if (lastLines.size > 8) lastLines.removeFirst()
                }
                val stage = when {
                    line.contains("Writing video subtitles") || line.contains("subtitle") -> "자막 받는 중"
                    line.contains("[Merger]") -> "영상/음성 합치는 중"
                    line.contains("[download]") && progress > 0 -> "다운로드 중"
                    line.contains("thumbnail", ignoreCase = true) -> "썸네일 받는 중"
                    else -> null
                }
                updateJob(id) { j ->
                    j.copy(
                        progress = if (progress > 0) progress / 100f else j.progress,
                        stage = stage ?: j.stage,
                    )
                }
            }
        } catch (e: YoutubeDL.CanceledException) {
            dir.deleteRecursively()
            jobs.update { l -> l.filterNot { it.id == id } }
            return
        } catch (e: Throwable) {
            failure = e
        }

        // Read title etc. from info json
        var title = id
        try {
            val info = dir.listFiles()?.firstOrNull { it.name.endsWith(".info.json") }
            if (info != null) {
                val o = JSONObject(info.readText())
                title = o.optString("title", id)
                Library.writeMeta(
                    dir, title,
                    o.optString("channel").ifBlank { o.optString("uploader") },
                    o.optLong("duration", 0), watchUrl,
                )
                info.delete()
            } else Library.writeMeta(dir, id, "", 0, watchUrl)
        } catch (_: Exception) {
            Library.writeMeta(dir, id, "", 0, watchUrl)
        }

        val hasMedia = dir.listFiles()?.any { 
            it.name.startsWith("media.") && it.length() > 0 && !Regex("""^media\.f\d+""").containsMatchIn(it.name)
        } == true
        if (!hasMedia) {
            val msg = (failure?.message ?: lastLines.joinToString("\n"))
                .lines().filter { it.contains("ERROR") }.joinToString("\n").ifBlank { failure?.message ?: "알 수 없는 오류" }
            dir.deleteRecursively()
            updateJob(id) {
                it.copy(status = DownloadJob.Status.FAILED, error = friendlyError(msg), title = title)
            }
            return
        }

        // Korean (auto-translated) subtitles: separate, best-effort request
        val hasEnSub = dir.listFiles()?.any { it.name.startsWith("sub.") } == true
        if (hasEnSub) {
            updateJob(id) { it.copy(stage = "한국어 번역 자막 받는 중", title = title) }
            try {
                val ko = YoutubeDLRequest(watchUrl).apply {
                    addOption("--skip-download")
                    addOption("--write-subs")
                    addOption("--write-auto-subs")
                    addOption("--sub-langs", "ko")
                    addOption("--sub-format", "json3/vtt/best")
                    addOption("-o", "subtitle:" + File(dir, "ko.%(ext)s").absolutePath)
                    addOption("--no-playlist")
                }
                YoutubeDL.getInstance().execute(ko, "$id-ko", false, null)
            } catch (_: Throwable) {
            }
        }

        // cleanup leftovers
        dir.listFiles()?.filter { it.name.contains(".part") || it.name.endsWith(".ytdl") }?.forEach { it.delete() }
        marker.delete()
        updateJob(id) {
            it.copy(
                status = DownloadJob.Status.DONE, progress = 1f, title = title,
                stage = if (hasEnSub) "완료" else "완료 (영어 자막 없음)",
            )
        }
        Library.notifyChanged()
    }

    /** Retry fetching YouTube's Korean auto-translated subtitles for an existing item. */
    suspend fun fetchTranslation(ctx: Context, item: LibraryItem): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (item.id.startsWith("local_")) return@withContext "내 파일로 가져온 영상은 유튜브 번역을 받을 수 없습니다"
        if (Engine.state.value != Engine.State.READY) return@withContext "다운로드 엔진이 아직 준비되지 않았습니다"
        val before = item.dir.listFiles()?.count { it.name.startsWith("ko.ko.") } ?: 0
        val url = item.url.ifBlank { "https://www.youtube.com/watch?v=${item.id}" }
        val msg = try {
            val req = YoutubeDLRequest(url).apply {
                addOption("--skip-download")
                addOption("--write-subs")
                addOption("--write-auto-subs")
                addOption("--sub-langs", "ko")
                addOption("--sub-format", "json3/vtt/best")
                addOption("-o", "subtitle:" + File(item.dir, "ko.%(ext)s").absolutePath)
                addOption("--no-playlist")
            }
            YoutubeDL.getInstance().execute(req, item.id + "-ko-retry", false, null)
            null
        } catch (e: Throwable) {
            e.message ?: e.toString()
        }
        val after = item.dir.listFiles()?.count { it.name.startsWith("ko.ko.") } ?: 0
        when {
            after > before || after > 0 -> { Library.notifyChanged(); "한국어 번역 자막을 받았습니다" }
            msg != null && msg.contains("429") -> "유튜브가 요청을 막았습니다(429). 몇 시간 뒤 다시 시도하거나 '폰에서 번역'을 쓰세요."
            msg != null -> "받지 못했습니다: " + msg.lines().firstOrNull { it.contains("ERROR") }.orEmpty().take(200)
            else -> "이 영상은 유튜브 한국어 번역이 제공되지 않습니다. '폰에서 번역'을 쓰세요."
        }
    }

    fun cancel(id: String) {
        YoutubeDL.getInstance().destroyProcessById(id)
    }

    private fun friendlyError(raw: String): String {
        val hint = when {
            raw.contains("Sign in to confirm", true) || raw.contains("bot", true) ->
                "유튜브가 봇 확인을 요구했습니다. 잠시 후 다시 시도하거나, 와이파이/데이터를 바꿔보세요."
            raw.contains("403") -> "유튜브가 다운로드를 막았습니다. 메뉴 → '다운로드 엔진 업데이트'를 눌러보세요."
            raw.contains("429") -> "요청이 너무 많습니다. 잠시 후 다시 시도하세요."
            raw.contains("Private video", true) || raw.contains("unavailable", true) -> "비공개이거나 볼 수 없는 영상입니다."
            raw.contains("Unable to resolve", true) || raw.contains("network", true) -> "인터넷 연결을 확인하세요."
            else -> "메뉴 → '다운로드 엔진 업데이트' 후 다시 시도해 보세요."
        }
        return hint + "\n\n" + raw.take(400)
    }
}
