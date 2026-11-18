package com.synclingo.app.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

object SubtitleTools {

    // ---------------- SRT writing ----------------

    private fun srtTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms / 60_000) % 60
        val s = (ms / 1000) % 60
        val milli = ms % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, milli)
    }

    enum class Kind { ENGLISH, KOREAN, BILINGUAL }

    fun toSrt(sentences: List<Sentence>, kind: Kind): String {
        val sb = StringBuilder()
        var n = 1
        for (s in sentences) {
            val text = when (kind) {
                Kind.ENGLISH -> s.text
                Kind.KOREAN -> s.translation ?: continue
                Kind.BILINGUAL -> s.text + (s.translation?.let { "\n$it" } ?: "")
            }
            sb.append(n++).append('\n')
                .append(srtTime(s.startMs)).append(" --> ").append(srtTime(s.endMs)).append('\n')
                .append(text).append("\n\n")
        }
        return sb.toString()
    }

    private fun safeName(title: String) =
        title.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), " ").trim().take(60).ifBlank { "subtitle" }

    // ---------------- 1) on-device machine translation (ML Kit) ----------------

    /** Translates every sentence EN→KO on the device and saves it as ko.mt.srt. */
    suspend fun machineTranslate(
        item: LibraryItem,
        sentences: List<Sentence>,
        onProgress: (done: Int, total: Int, stage: String) -> Unit,
    ): Int {
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.KOREAN)
                .build()
        )
        try {
            onProgress(0, sentences.size, "번역 모델 준비 중 (처음 한 번 약 30MB)")
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            val out = ArrayList<Sentence>(sentences.size)
            sentences.forEachIndexed { i, s ->
                val ko = try { translator.translate(s.text).await() } catch (e: Exception) { "" }
                out.add(s.copy(translation = ko.ifBlank { null }))
                if (i % 5 == 0 || i == sentences.size - 1) onProgress(i + 1, sentences.size, "번역 중")
            }
            withContext(Dispatchers.IO) {
                File(item.dir, "ko.mt.srt").writeText(toSrt(out, Kind.KOREAN))
            }
            Library.notifyChanged()
            return out.count { it.translation != null }
        } finally {
            translator.close()
        }
    }

    // ---------------- 2) AI translation round-trip via clipboard ----------------

    fun aiPrompt(sentences: List<Sentence>): String =
        "아래 SRT 영어 자막을 자연스러운 한국어로 번역해줘.\n" +
            "규칙: 번호와 시간 줄은 절대 바꾸지 말고, 영어 문장만 한국어로 바꿔서 SRT 형식 그대로 전체를 답해줘. " +
            "설명은 쓰지 마.\n\n" + toSrt(sentences, Kind.ENGLISH)

    /** Saves pasted SRT text (e.g. an AI chatbot reply) as the preferred Korean track. Returns cue count. */
    fun savePastedTranslation(item: LibraryItem, pasted: String): Int {
        val cleaned = pasted.replace("\r", "")
            .lines().filterNot { it.trim().startsWith("```") }.joinToString("\n").trim()
        val tmp = File(item.dir, "ko.ai.tmp.srt")
        tmp.writeText(cleaned)
        val track = Subtitles.parse(tmp)
        val count = track?.cues?.size ?: 0
        if (count == 0) {
            tmp.delete()
            return 0
        }
        val dest = File(item.dir, "ko.ai.srt")
        dest.delete()
        tmp.renameTo(dest)
        Library.notifyChanged()
        return count
    }

    // ---------------- 3) export / share ----------------

    private fun exportFiles(ctx: Context, item: LibraryItem, sentences: List<Sentence>): List<File> {
        val dir = File(ctx.cacheDir, "export").apply { deleteRecursively(); mkdirs() }
        val base = safeName(item.title)
        val files = ArrayList<File>()
        files.add(File(dir, "$base.en.srt").apply { writeText(toSrt(sentences, Kind.ENGLISH)) })
        if (sentences.any { it.translation != null }) {
            files.add(File(dir, "$base.ko.srt").apply { writeText(toSrt(sentences, Kind.KOREAN)) })
            files.add(File(dir, "$base.en-ko.srt").apply { writeText(toSrt(sentences, Kind.BILINGUAL)) })
        }
        return files
    }

    fun share(ctx: Context, item: LibraryItem, sentences: List<Sentence>) {
        val uris = ArrayList<Uri>(exportFiles(ctx, item, sentences).map {
            FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it)
        })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "자막 파일 보내기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Copies the subtitle files to Download/SyncLingo so the Files app can see them. */
    fun saveToDownloads(ctx: Context, item: LibraryItem, sentences: List<Sentence>): String {
        val files = exportFiles(ctx, item, sentences)
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = ctx.contentResolver
            for (f in files) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/x-subrip")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SyncLingo")
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: continue
                resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            }
            return "내 파일 → 다운로드 → SyncLingo 폴더에 ${files.size}개 저장했습니다"
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SyncLingo")
            dir.mkdirs()
            files.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
            return "다운로드/SyncLingo 폴더에 저장했습니다"
        }
    }
}
