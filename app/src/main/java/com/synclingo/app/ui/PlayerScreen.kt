package com.synclingo.app.ui

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.synclingo.app.data.Library
import com.synclingo.app.data.LibraryItem
import com.synclingo.app.data.Sentence
import com.synclingo.app.data.Subtitles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

enum class PlayMode(val label: String) {
    CONTINUOUS("연속 재생"),
    REPEAT("한 문장 반복"),
    PAUSE("문장마다 멈춤"),
}

private val SPEEDS = listOf(0.5f, 0.6f, 0.75f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f)
private val FONT_SIZES = listOf(15, 18, 21, 24)

private fun loadSentences(item: LibraryItem): List<Sentence> {
    val track = item.subtitle?.let { Subtitles.parse(it) } ?: return emptyList()
    val sentences = Subtitles.buildSentences(track)
    val tr = item.translation?.let { Subtitles.parse(it) }
    return Subtitles.attachTranslation(sentences, tr)
}

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(itemId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val item = remember(itemId) { Library.get(ctx, itemId) }
    val media = item?.media
    if (item == null || media == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("파일을 찾을 수 없습니다")
                TextButton(onClick = onBack) { Text("돌아가기") }
            }
        }
        return
    }

    val player = remember(itemId) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(media)))
            prepare()
            seekTo(Library.lastPosition(item))
        }
    }
    DisposableEffect(player) {
        onDispose {
            Library.savePosition(item, player.currentPosition)
            player.release()
        }
    }

    var sentences by remember { mutableStateOf<List<Sentence>?>(null) }
    LaunchedEffect(itemId) { sentences = withContext(Dispatchers.IO) { loadSentences(item) } }
    val list = sentences ?: emptyList()
    val hasKo = list.any { it.translation != null }

    var bookmarks by remember { mutableStateOf(Library.bookmarks(item)) }
    var pos by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(PlayMode.CONTINUOUS) }
    var speed by rememberSaveable { mutableFloatStateOf(1.0f) }
    var showKo by rememberSaveable { mutableStateOf(false) }
    var blind by rememberSaveable { mutableStateOf(false) }
    var onlyStars by rememberSaveable { mutableStateOf(false) }
    var follow by remember { mutableStateOf(true) }
    var fontIdx by rememberSaveable { mutableIntStateOf(1) }
    var lockIdx by remember { mutableIntStateOf(-1) }
    val revealed = remember { mutableStateMapOf<Int, Boolean>() }
    var modeMenu by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }

    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }

    // Sync loop: position tracking + repeat / pause-per-sentence logic
    LaunchedEffect(player, list) {
        while (isActive) {
            val p = player.currentPosition
            pos = p
            playing = player.isPlaying
            if (list.isNotEmpty() && player.isPlaying && mode != PlayMode.CONTINUOUS) {
                var i = lockIdx
                if (i !in list.indices) { i = Subtitles.indexAt(list, p).coerceAtLeast(0); lockIdx = i }
                val s = list[i]
                val outside = p < s.startMs - 1000 || p > s.endMs + 700
                if (outside) {
                    // user seeked somewhere else: follow them
                    lockIdx = Subtitles.indexAt(list, p).coerceAtLeast(0)
                } else if (p >= s.endMs - 40) {
                    if (mode == PlayMode.REPEAT) {
                        player.seekTo(s.startMs)
                    } else {
                        player.pause()
                        if (i + 1 < list.size) { lockIdx = i + 1; player.seekTo(list[i + 1].startMs) }
                    }
                }
            }
            delay(40)
        }
    }

    val curIdx = Subtitles.indexAt(list, pos)

    fun playSentence(i: Int) {
        if (i !in list.indices) return
        lockIdx = i
        player.seekTo(list[i].startMs)
        player.play()
    }

    val listState = rememberLazyListState()
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) follow = false }
    val visible = if (onlyStars) list.filter { it.startMs in bookmarks } else list
    LaunchedEffect(curIdx, follow, onlyStars) {
        if (follow && !onlyStars && curIdx >= 0 && curIdx < visible.size) {
            listState.animateScrollToItem((curIdx - 1).coerceAtLeast(0))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
                title = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                actions = {
                    IconButton(onClick = { fontIdx = (fontIdx + 1) % FONT_SIZES.size }) { Icon(Icons.Default.FormatSize, "글자 크기") }
                },
            )
        },
        floatingActionButton = {
            if (!follow && !onlyStars && list.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { follow = true },
                    icon = { Icon(Icons.Default.MyLocation, null) },
                    text = { Text("현재 문장") },
                )
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // ---------- video ----------
            AndroidView(
                factory = { c ->
                    PlayerView(c).apply {
                        this.player = player
                        keepScreenOn = true
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        if (item.isAudioOnly) {
                            setControllerShowTimeoutMs(0)
                            setControllerHideOnTouch(false)
                            item.thumb?.let { f ->
                                BitmapFactory.decodeFile(f.absolutePath)?.let { bmp ->
                                    setDefaultArtwork(BitmapDrawable(c.resources, bmp))
                                }
                            }
                            setArtworkDisplayMode(PlayerView.ARTWORK_DISPLAY_MODE_FIT)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(androidx.compose.ui.graphics.Color.Black),
            )

            // ---------- learning controls ----------
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { playSentence((curIdx - 1).coerceAtLeast(0)) }) { Icon(Icons.Default.SkipPrevious, "이전 문장") }
                IconButton(onClick = { playSentence(curIdx.coerceAtLeast(0)) }) { Icon(Icons.Default.Replay, "현재 문장 다시") }
                FilledIconButton(onClick = {
                    if (player.isPlaying) player.pause() else {
                        lockIdx = curIdx
                        player.play()
                    }
                }, modifier = Modifier.size(52.dp)) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "재생", Modifier.size(30.dp))
                }
                IconButton(onClick = { playSentence(curIdx + 1) }) { Icon(Icons.Default.SkipNext, "다음 문장") }
                Text(
                    formatTime(pos),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box {
                    AssistChip(
                        onClick = { modeMenu = true },
                        label = { Text(mode.label) },
                        leadingIcon = {
                            Icon(
                                when (mode) {
                                    PlayMode.CONTINUOUS -> Icons.Default.PlaylistPlay
                                    PlayMode.REPEAT -> Icons.Default.RepeatOne
                                    PlayMode.PAUSE -> Icons.Default.PauseCircleOutline
                                }, null, Modifier.size(18.dp)
                            )
                        },
                    )
                    DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                        PlayMode.entries.forEach { m ->
                            DropdownMenuItem(text = { Text(m.label) }, onClick = {
                                mode = m; lockIdx = curIdx; modeMenu = false
                            })
                        }
                    }
                }
                Box {
                    AssistChip(
                        onClick = { speedMenu = true },
                        label = { Text("${"%.2f".format(speed).trimEnd('0').trimEnd('.')}x") },
                        leadingIcon = { Icon(Icons.Default.Speed, null, Modifier.size(18.dp)) },
                    )
                    DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                        SPEEDS.forEach { s ->
                            DropdownMenuItem(text = { Text("${s}x") }, onClick = { speed = s; speedMenu = false })
                        }
                    }
                }
                FilterChip(
                    selected = showKo, enabled = hasKo, onClick = { showKo = !showKo },
                    label = { Text("한국어") },
                    leadingIcon = { Icon(Icons.Default.Translate, null, Modifier.size(18.dp)) },
                )
                FilterChip(
                    selected = blind, onClick = { blind = !blind; revealed.clear() },
                    label = { Text("받아쓰기") },
                    leadingIcon = { Icon(Icons.Default.Hearing, null, Modifier.size(18.dp)) },
                )
                FilterChip(
                    selected = onlyStars, onClick = { onlyStars = !onlyStars },
                    label = { Text("★ ${bookmarks.size}") },
                )
            }

            // ---------- transcript ----------
            when {
                sentences == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f).padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("이 영상에는 영어 자막이 없습니다.\n'내 파일 가져오기'로 자막 파일을 직접 넣을 수 있습니다.", color = MaterialTheme.colorScheme.outline)
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(visible, key = { it.index }) { s ->
                        val isCur = s.index == curIdx
                        val starred = s.startMs in bookmarks
                        val primary = MaterialTheme.colorScheme.primary
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isCur) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surface)
                                .combinedClickable(
                                    onClick = { playSentence(s.index) },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        bookmarks = if (starred) bookmarks - s.startMs else bookmarks + s.startMs
                                        Library.saveBookmarks(item, bookmarks)
                                    },
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(formatTime(s.startMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                if (starred) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(Icons.Default.Star, null, Modifier.size(14.dp), tint = androidx.compose.ui.graphics.Color(0xFFFFC107))
                                }
                                Spacer(Modifier.weight(1f))
                                if (blind && revealed[s.index] != true) {
                                    Text(
                                        "정답 보기",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = primary,
                                        modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                            .combinedClickable(onClick = { revealed[s.index] = true })
                                            .padding(4.dp),
                                    )
                                }
                            }
                            val fs = FONT_SIZES[fontIdx].sp
                            val hidden = blind && revealed[s.index] != true
                            val text = when {
                                hidden -> buildAnnotatedString {
                                    append(s.words.joinToString(" ") { w -> w.text.map { if (it.isLetterOrDigit()) '_' else it }.joinToString("") })
                                }
                                isCur && s.words.isNotEmpty() -> buildAnnotatedString {
                                    s.words.forEachIndexed { i, w ->
                                        if (i > 0) append(" ")
                                        if (w.startMs <= pos) withStyle(SpanStyle(color = primary, fontWeight = FontWeight.SemiBold)) { append(w.text) }
                                        else append(w.text)
                                    }
                                }
                                else -> buildAnnotatedString { append(s.text) }
                            }
                            Text(
                                text,
                                fontSize = fs,
                                lineHeight = (FONT_SIZES[fontIdx] * 1.4f).sp,
                                fontWeight = if (isCur) FontWeight.Medium else FontWeight.Normal,
                                letterSpacing = if (hidden) 1.sp else 0.sp,
                            )
                            if (showKo && s.translation != null) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    s.translation,
                                    fontSize = (FONT_SIZES[fontIdx] - 3).sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
