package com.synclingo.app.data

import org.json.JSONObject
import java.io.File

data class Word(val text: String, val startMs: Long)

data class Cue(val startMs: Long, val endMs: Long, val text: String, val words: List<Word>)

data class Sentence(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<Word>,
    val translation: String? = null,
)

/** Parsed subtitle track. wordLevel = true when the file carries per-word timings (YouTube auto captions). */
data class Track(val cues: List<Cue>, val wordLevel: Boolean)

object Subtitles {

    private val TIME_TAG = Regex("""<(\d{1,2}:)?\d{2}:\d{2}[.,]\d{3}>""")
    private val ANY_TAG = Regex("""<[^>]+>""")
    private val SPACES = Regex("""\s+""")
    private val SENTENCE_END = Regex("""[.?!…]["'”’)\]]*$""")

    fun parse(file: File): Track? = try {
        val text = file.readText(Charsets.UTF_8).removePrefix("﻿")
        when {
            file.name.endsWith(".json3") || text.trimStart().startsWith("{") -> parseJson3(text)
            text.trimStart().startsWith("WEBVTT") || file.name.endsWith(".vtt") -> parseVtt(text)
            else -> parseSrt(text)
        }
    } catch (e: Exception) {
        null
    }

    // ---------------- json3 (YouTube timed text) ----------------
    private fun parseJson3(text: String): Track {
        val events = JSONObject(text).optJSONArray("events") ?: return Track(emptyList(), false)
        val cues = ArrayList<Cue>()
        var multiSegEvents = 0
        var segEvents = 0
        for (i in 0 until events.length()) {
            val ev = events.getJSONObject(i)
            val segs = ev.optJSONArray("segs") ?: continue
            val start = ev.optLong("tStartMs", 0)
            val dur = ev.optLong("dDurationMs", 0)
            val words = ArrayList<Word>()
            val sb = StringBuilder()
            var withOffset = 0
            for (j in 0 until segs.length()) {
                val seg = segs.getJSONObject(j)
                val t = seg.optString("utf8", "")
                sb.append(t)
                if (seg.has("tOffsetMs")) withOffset++
                val clean = t.replace('\n', ' ').trim()
                if (clean.isNotEmpty()) {
                    for (piece in clean.split(SPACES)) {
                        if (piece.isNotEmpty()) words.add(Word(piece, start + seg.optLong("tOffsetMs", 0)))
                    }
                }
            }
            val full = sb.toString().replace('\n', ' ').replace(SPACES, " ").trim()
            if (full.isEmpty()) continue
            segEvents++
            if (withOffset > 0) multiSegEvents++
            cues.add(Cue(start, start + dur, full, words))
        }
        val wordLevel = segEvents > 0 && multiSegEvents * 2 >= segEvents
        return Track(cues, wordLevel)
    }

    // ---------------- WebVTT ----------------
    private fun parseVttTime(s: String): Long {
        val p = s.trim().replace(',', '.').split(":")
        val (h, m, sec) = when (p.size) {
            3 -> Triple(p[0].toLong(), p[1].toLong(), p[2])
            2 -> Triple(0L, p[0].toLong(), p[1])
            else -> return 0
        }
        val sp = sec.split(".")
        val ms = sp.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0
        return ((h * 60 + m) * 60 + sp[0].toLong()) * 1000 + ms
    }

    private fun parseVtt(text: String): Track {
        val blocks = text.replace("\r", "").split(Regex("\n{2,}"))
        val hasWordTags = TIME_TAG.containsMatchIn(text)
        val cues = ArrayList<Cue>()
        for (b in blocks) {
            val lines = b.split("\n")
            val ti = lines.indexOfFirst { it.contains("-->") }
            if (ti < 0) continue
            val parts = lines[ti].split("-->")
            val start = parseVttTime(parts[0])
            val end = parseVttTime(parts[1].trim().split(" ")[0])
            val body = lines.drop(ti + 1).filter { it.isNotBlank() }
            if (body.isEmpty()) continue
            if (hasWordTags) {
                // YouTube auto-caption VTT: only lines carrying timing tags hold new words
                val words = ArrayList<Word>()
                for (line in body) {
                    if (!TIME_TAG.containsMatchIn(line)) continue
                    var cursor = start
                    var last = 0
                    val segments = ArrayList<Pair<Long, String>>()
                    for (m in TIME_TAG.findAll(line)) {
                        segments.add(cursor to line.substring(last, m.range.first))
                        cursor = parseVttTime(m.value.trim('<', '>'))
                        last = m.range.last + 1
                    }
                    segments.add(cursor to line.substring(last))
                    for ((t, raw) in segments) {
                        val clean = raw.replace(ANY_TAG, "").trim()
                        if (clean.isEmpty()) continue
                        for (piece in clean.split(SPACES)) if (piece.isNotEmpty()) words.add(Word(piece, t))
                    }
                }
                if (words.isNotEmpty()) cues.add(Cue(start, end, words.joinToString(" ") { it.text }, words))
            } else {
                val t = body.joinToString(" ").replace(ANY_TAG, "").replace(SPACES, " ").trim()
                    .let { decodeEntities(it) }
                if (t.isEmpty()) continue
                if (cues.isNotEmpty() && cues.last().text == t) continue
                cues.add(Cue(start, end, t, emptyList()))
            }
        }
        return Track(cues, hasWordTags)
    }

    // ---------------- SRT ----------------
    private fun parseSrt(text: String): Track {
        val blocks = text.replace("\r", "").split(Regex("\n{2,}"))
        val cues = ArrayList<Cue>()
        for (b in blocks) {
            val lines = b.split("\n")
            val ti = lines.indexOfFirst { it.contains("-->") }
            if (ti < 0) continue
            val parts = lines[ti].split("-->")
            val start = parseVttTime(parts[0])
            val end = parseVttTime(parts[1].trim().split(" ")[0])
            val t = lines.drop(ti + 1).joinToString(" ").replace(ANY_TAG, "").replace(SPACES, " ").trim()
                .let { decodeEntities(it) }
            if (t.isNotEmpty()) cues.add(Cue(start, end, t, emptyList()))
        }
        return Track(cues, false)
    }

    private fun decodeEntities(s: String) = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")

    // ---------------- sentence building ----------------

    fun buildSentences(track: Track): List<Sentence> {
        val raw = if (track.wordLevel) fromWords(track) else fromCues(track)
        return raw.mapIndexed { i, s -> s.copy(index = i) }
    }

    private fun fromWords(track: Track): List<Sentence> {
        val words = track.cues.flatMap { it.words }.sortedBy { it.startMs }
        val out = ArrayList<Sentence>()
        var cur = ArrayList<Word>()
        fun flush(nextStart: Long?) {
            if (cur.isEmpty()) return
            val lastStart = cur.last().startMs
            val end = if (nextStart != null && nextStart - lastStart < 2500) nextStart else lastStart + 900
            out.add(Sentence(0, cur.first().startMs, end, cur.joinToString(" ") { it.text }, cur.toList()))
            cur = ArrayList()
        }
        for (i in words.indices) {
            val w = words[i]
            cur.add(w)
            val next = words.getOrNull(i + 1)
            val gap = if (next != null) next.startMs - w.startMs else Long.MAX_VALUE
            val endsSentence = SENTENCE_END.containsMatchIn(w.text)
            val breakHere = endsSentence ||
                (gap > 1500 && cur.size >= 3) ||
                (cur.size >= 14 && w.text.endsWith(",")) ||
                cur.size >= 22
            if (breakHere) flush(next?.startMs)
        }
        flush(null)
        return out
    }

    private fun fromCues(track: Track): List<Sentence> {
        val cues = track.cues
        if (cues.isEmpty()) return emptyList()
        val punctuated = cues.count { SENTENCE_END.containsMatchIn(it.text) }
        val merge = punctuated * 4 >= cues.size // only merge when the file actually uses punctuation
        val out = ArrayList<Sentence>()
        var group = ArrayList<Cue>()
        fun flush() {
            if (group.isEmpty()) return
            val start = group.first().startMs
            val end = group.last().endMs
            val text = group.joinToString(" ") { it.text }
            out.add(Sentence(0, start, end, text, interpolate(group)))
            group = ArrayList()
        }
        for (i in cues.indices) {
            val c = cues[i]
            group.add(c)
            val next = cues.getOrNull(i + 1)
            val len = group.sumOf { it.text.length }
            val breakHere = !merge || next == null ||
                SENTENCE_END.containsMatchIn(c.text) ||
                next.startMs - c.endMs > 2000 ||
                len > 200 ||
                next.text.startsWith("-") || next.text.startsWith("[") || next.text.startsWith("(")
            if (breakHere) flush()
        }
        flush()
        return out
    }

    /** Approximate per-word timing inside cues by character length. */
    private fun interpolate(cues: List<Cue>): List<Word> {
        val out = ArrayList<Word>()
        for (c in cues) {
            if (c.words.isNotEmpty()) { out.addAll(c.words); continue }
            val pieces = c.text.split(SPACES).filter { it.isNotEmpty() }
            val total = pieces.sumOf { it.length + 1 }.coerceAtLeast(1)
            val dur = (c.endMs - c.startMs).coerceAtLeast(1)
            var acc = 0
            for (p in pieces) {
                out.add(Word(p, c.startMs + dur * acc / total))
                acc += p.length + 1
            }
        }
        return out
    }

    /** Attach translated text (cue-level) to sentences by start time. */
    fun attachTranslation(sentences: List<Sentence>, translation: Track?): List<Sentence> {
        if (translation == null || sentences.isEmpty()) return sentences
        val buckets = Array(sentences.size) { StringBuilder() }
        val seen = HashSet<String>()
        for (c in translation.cues) {
            val t = c.text.trim()
            if (t.isEmpty()) continue
            val key = "${c.startMs}|$t"
            if (!seen.add(key)) continue
            val idx = indexAt(sentences, c.startMs + 200).coerceAtLeast(0)
            if (buckets[idx].isNotEmpty()) buckets[idx].append(' ')
            buckets[idx].append(t)
        }
        return sentences.mapIndexed { i, s ->
            val t = buckets[i].toString().trim()
            if (t.isEmpty()) s else s.copy(translation = t)
        }
    }

    /** Index of the last sentence whose start <= t, or -1. */
    fun indexAt(sentences: List<Sentence>, t: Long): Int {
        var lo = 0
        var hi = sentences.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (sentences[mid].startMs <= t) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}
