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
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import android.widget.Toast
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.synclingo.app.data.Downloader
import com.synclingo.app.data.SubtitleTools
import kotlinx.coroutines.launch
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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

enum class LangMode(val label: String) {
    EN("영어만"),
    BOTH("영어+한국어"),
    KO("한국어만"),
}

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
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(itemId, reload) {
        sentences = withContext(Dispatchers.IO) { loadSentences(Library.get(ctx, itemId) ?: item) }
    }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var subMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var busyProgress by remember { mutableFloatStateOf(-1f) }
    var showAi by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    val list = sentences ?: emptyList()
    val hasKo = list.any { it.translation != null }

    var bookmarks by remember { mutableStateOf(Library.bookmarks(item)) }
    var pos by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(PlayMode.CONTINUOUS) }
    var speed by rememberSaveable { mutableFloatStateOf(1.0f) }
    var lang by rememberSaveable { mutableStateOf(LangMode.EN) }
    var langMenu by remember { mutableStateOf(false) }
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

    var bigVideo by rememberSaveable { mutableStateOf(false) }

    // ---------------- building blocks ----------------

    val videoView: @Composable (Modifier) -> Unit = { m ->
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
            onRelease = { it.player = null },
            modifier = m.aspectRatio(16f / 9f).background(Color.Black),
        )
    }

    val playPause = {
        if (player.isPlaying) player.pause() else {
            lockIdx = curIdx
            player.play()
        }
    }

    val transport: @Composable (Modifier, Boolean) -> Unit = { m, grid ->
        if (grid) {
            // compact 2x2 grid beside a small video (folded phone)
            Column(m, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                FilledIconButton(onClick = playPause, modifier = Modifier.size(48.dp)) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "재생", Modifier.size(28.dp))
                }
                Row {
                    IconButton(onClick = { playSentence((curIdx - 1).coerceAtLeast(0)) }) { Icon(Icons.Default.SkipPrevious, "이전 문장") }
                    IconButton(onClick = { playSentence(curIdx.coerceAtLeast(0)) }) { Icon(Icons.Default.Replay, "다시") }
                    IconButton(onClick = { playSentence(curIdx + 1) }) { Icon(Icons.Default.SkipNext, "다음 문장") }
                }
                Text(formatTime(pos), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Row(
                m.padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { playSentence((curIdx - 1).coerceAtLeast(0)) }) { Icon(Icons.Default.SkipPrevious, "이전 문장") }
                IconButton(onClick = { playSentence(curIdx.coerceAtLeast(0)) }) { Icon(Icons.Default.Replay, "현재 문장 다시") }
                FilledIconButton(onClick = playPause, modifier = Modifier.size(52.dp)) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "재생", Modifier.size(30.dp))
                }
                IconButton(onClick = { playSentence(curIdx + 1) }) { Icon(Icons.Default.SkipNext, "다음 문장") }
                Text(formatTime(pos), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    val chips: @Composable (Boolean) -> Unit = { showSizeToggle ->
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (showSizeToggle) {
                FilterChip(
                    selected = bigVideo, onClick = { bigVideo = !bigVideo },
                    label = { Text(if (bigVideo) "영상 크게" else "영상 작게") },
                    leadingIcon = { Icon(if (bigVideo) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, null, Modifier.size(18.dp)) },
                )
            }
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
            Box {
                FilterChip(
                    selected = lang != LangMode.EN && hasKo,
                    onClick = { langMenu = true },
                    label = { Text(if (hasKo) lang.label else "영어만") },
                    leadingIcon = { Icon(Icons.Default.Translate, null, Modifier.size(18.dp)) },
                )
                DropdownMenu(expanded = langMenu, onDismissRequest = { langMenu = false }) {
                    LangMode.entries.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m.label) },
                            leadingIcon = { if (m == lang) Icon(Icons.Default.Check, null) },
                            onClick = {
                                langMenu = false
                                if (m != LangMode.EN && !hasKo) {
                                    toast("한국어 자막이 없습니다. 만드는 방법을 골라주세요")
                                    subMenu = true
                                } else lang = m
                            },
                        )
                    }
                }
            }
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
    }

    val transcript: @Composable (Modifier) -> Unit = { m ->
        Box(m) {
            when {
                sentences == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("이 영상에는 영어 자막이 없습니다.\n'내 파일 가져오기'로 자막 파일을 직접 넣을 수 있습니다.", color = MaterialTheme.colorScheme.outline)
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(visible, key = { it.index }) { s ->
                        SentenceRow(
                            s = s, isCur = s.index == curIdx, starred = s.startMs in bookmarks, pos = pos,
                            fontSp = FONT_SIZES[fontIdx], hidden = blind && revealed[s.index] != true,
                            lang = if (hasKo) lang else LangMode.EN,
                            onClick = { playSentence(s.index) },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                bookmarks = if (s.startMs in bookmarks) bookmarks - s.startMs else bookmarks + s.startMs
                                Library.saveBookmarks(item, bookmarks)
                            },
                            onReveal = { revealed[s.index] = true },
                        )
                    }
                }
            }
            if (!follow && !onlyStars && list.isNotEmpty()) {
                SmallFloatingActionButton(
                    onClick = { follow = true },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                ) { Icon(Icons.Default.MyLocation, "현재 문장으로") }
            }
        }
    }

    // ---------------- layout ----------------

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
                title = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                actions = {
                    IconButton(onClick = { fontIdx = (fontIdx + 1) % FONT_SIZES.size }) { Icon(Icons.Default.FormatSize, "글자 크기") }
                    Box {
                        IconButton(onClick = { subMenu = true }) { Icon(Icons.Default.Subtitles, "자막 메뉴") }
                        DropdownMenu(expanded = subMenu, onDismissRequest = { subMenu = false }) {
                            Text(
                                "한글 번역 만들기", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                            DropdownMenuItem(
                                text = { Text("폰에서 바로 번역 (무료·오프라인)") },
                                leadingIcon = { Icon(Icons.Default.Translate, null) },
                                enabled = list.isNotEmpty(),
                                onClick = {
                                    subMenu = false
                                    busy = "번역 준비 중"; busyProgress = -1f
                                    scope.launch {
                                        try {
                                            val cur = Library.get(ctx, itemId) ?: item
                                            val n = SubtitleTools.machineTranslate(cur, list) { done, total, stage ->
                                                busy = "$stage $done/$total"
                                                busyProgress = if (total > 0 && done > 0) done.toFloat() / total else -1f
                                            }
                                            toast("$n 문장을 번역했습니다")
                                            lang = LangMode.BOTH; reload++
                                        } catch (e: Exception) {
                                            toast("번역 실패: ${e.message}. 인터넷 연결을 확인하세요(처음 한 번 모델 다운로드).")
                                        } finally { busy = null }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("AI로 번역 (ChatGPT·Claude 등)") },
                                leadingIcon = { Icon(Icons.Default.AutoAwesome, null) },
                                enabled = list.isNotEmpty(),
                                onClick = { subMenu = false; showAi = true },
                            )
                            DropdownMenuItem(
                                text = { Text("유튜브 번역 자막 다시 받기") },
                                leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                onClick = {
                                    subMenu = false
                                    busy = "유튜브에서 한국어 자막 받는 중"; busyProgress = -1f
                                    scope.launch {
                                        val r = Downloader.fetchTranslation(ctx, Library.get(ctx, itemId) ?: item)
                                        busy = null
                                        toast(r); reload++
                                    }
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("자막 파일 내보내기") },
                                leadingIcon = { Icon(Icons.Default.IosShare, null) },
                                enabled = list.isNotEmpty(),
                                onClick = { subMenu = false; showExport = true },
                            )
                        }
                    }
                },
                windowInsets = TopAppBarDefaults.windowInsets,
                expandedHeight = 52.dp,
            )
        },
    ) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize()) {
            val wide = maxWidth >= 600.dp
            if (wide) {
                // Unfolded / landscape: video + controls on the left, transcript on the right
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.weight(0.55f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    ) {
                        videoView(Modifier.fillMaxWidth())
                        transport(Modifier.fillMaxWidth(), false)
                        chips(false)
                        val cur = list.getOrNull(curIdx)
                        if (cur != null) {
                            Card(Modifier.fillMaxWidth().padding(12.dp)) {
                                Column(Modifier.padding(14.dp)) {
                                    Text("지금 문장", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.height(4.dp))
                                    val curLang = if (hasKo) lang else LangMode.EN
                                    val koText = cur.translation
                                    if (curLang != LangMode.KO || koText == null) {
                                        Text(
                                            if (blind && revealed[cur.index] != true) maskText(cur) else cur.text,
                                            fontSize = (FONT_SIZES[fontIdx] + 2).sp,
                                            lineHeight = ((FONT_SIZES[fontIdx] + 2) * 1.4f).sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                    if (curLang != LangMode.EN && koText != null) {
                                        if (curLang == LangMode.BOTH) Spacer(Modifier.height(6.dp))
                                        Text(
                                            koText,
                                            color = if (curLang == LangMode.KO) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = (if (curLang == LangMode.KO) FONT_SIZES[fontIdx] + 2 else FONT_SIZES[fontIdx]).sp,
                                            lineHeight = ((FONT_SIZES[fontIdx] + 2) * 1.4f).sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    VerticalDivider()
                    transcript(Modifier.weight(0.45f).fillMaxHeight())
                }
            } else {
                // Folded / narrow: compact video, transcript gets most of the screen
                Column(Modifier.fillMaxSize()) {
                    if (bigVideo) {
                        videoView(Modifier.fillMaxWidth())
                        transport(Modifier.fillMaxWidth(), false)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            videoView(Modifier.weight(0.6f))
                            transport(Modifier.weight(0.4f), true)
                        }
                    }
                    chips(true)
                    HorizontalDivider(Modifier.padding(top = 4.dp))
                    transcript(Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
    busy?.let { msg ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(msg, style = MaterialTheme.typography.titleMedium) },
            text = {
                if (busyProgress >= 0f) LinearProgressIndicator(progress = { busyProgress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            },
        )
    }

    if (showAi) {
        AlertDialog(
            onDismissRequest = { showAi = false },
            title = { Text("AI로 자연스럽게 번역하기") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("① 아래 [복사]를 누르면 번역 요청문과 영어 자막이 함께 복사됩니다.", style = MaterialTheme.typography.bodyMedium)
                    Text("② ChatGPT·Claude·Gemini 앱에 붙여넣고 보냅니다.", style = MaterialTheme.typography.bodyMedium)
                    Text("③ 답변 전체를 복사한 뒤 돌아와서 [붙여넣기]를 누릅니다.", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "영상이 길면 AI가 중간에 끊을 수 있습니다. 그럴 땐 \"계속\"이라고 보낸 뒤 모든 답을 이어서 복사하세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            clipboard.setText(AnnotatedString(SubtitleTools.aiPrompt(list)))
                            toast("복사했습니다 (${list.size}문장). AI 앱에 붙여넣으세요")
                        }) { Text("복사") }
                        OutlinedButton(onClick = {
                            val pasted = clipboard.getText()?.text.orEmpty()
                            scope.launch {
                                val n = withContext(Dispatchers.IO) {
                                    SubtitleTools.savePastedTranslation(Library.get(ctx, itemId) ?: item, pasted)
                                }
                                if (n == 0) toast("붙여넣은 내용에서 SRT 자막을 찾지 못했습니다. AI 답변 전체를 복사했는지 확인하세요.")
                                else {
                                    toast(
                                        if (n < list.size * 0.9) "${list.size}문장 중 ${n}문장만 들어 있습니다. AI가 답을 중간에 끊었을 수 있어요. \"계속\"을 요청해 나머지도 이어서 붙여넣으세요."
                                        else "한국어 자막 ${n}문장을 적용했습니다"
                                    )
                                    showAi = false; lang = LangMode.BOTH; reload++
                                }
                            }
                        }) { Text("붙여넣기") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAi = false }) { Text("닫기") } },
        )
    }

    if (showExport) {
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text("자막 파일 내보내기") },
            text = {
                Text(
                    "영어" + (if (hasKo) ", 한국어, 영어+한국어(이중)" else "") + " 자막을 .srt 파일로 만듭니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showExport = false
                    try { SubtitleTools.share(ctx, Library.get(ctx, itemId) ?: item, list) }
                    catch (e: Exception) { toast("공유 실패: ${e.message}") }
                }) { Text("공유하기") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExport = false
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching { SubtitleTools.saveToDownloads(ctx, Library.get(ctx, itemId) ?: item, list) }
                        }
                        toast(r.getOrElse { "저장 실패: ${it.message}" })
                    }
                }) { Text("다운로드 폴더에 저장") }
            },
        )
    }
}

private fun maskText(s: Sentence) =
    s.words.joinToString(" ") { w -> w.text.map { if (it.isLetterOrDigit()) '_' else it }.joinToString("") }

@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SentenceRow(
    s: Sentence,
    isCur: Boolean,
    starred: Boolean,
    pos: Long,
    fontSp: Int,
    hidden: Boolean,
    lang: LangMode,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onReveal: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isCur) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(s.startMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            if (starred) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Default.Star, null, Modifier.size(14.dp), tint = Color(0xFFFFC107))
            }
            Spacer(Modifier.weight(1f))
            if (hidden && (lang != LangMode.KO || s.translation == null)) {
                Text(
                    "정답 보기",
                    style = MaterialTheme.typography.labelSmall,
                    color = primary,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).combinedClickable(onClick = onReveal).padding(4.dp),
                )
            }
        }
        val koText = s.translation
        val showEn = lang != LangMode.KO || koText == null
        if (showEn) {
            val text = when {
                hidden -> buildAnnotatedString { append(maskText(s)) }
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
                fontSize = fontSp.sp,
                lineHeight = (fontSp * 1.35f).sp,
                fontWeight = if (isCur) FontWeight.Medium else FontWeight.Normal,
                letterSpacing = if (hidden) 1.sp else 0.sp,
            )
        }
        if (lang != LangMode.EN && koText != null) {
            if (lang == LangMode.BOTH) {
                Spacer(Modifier.height(2.dp))
                Text(koText, fontSize = (fontSp - 3).sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    koText,
                    fontSize = fontSp.sp,
                    lineHeight = (fontSp * 1.4f).sp,
                    fontWeight = if (isCur) FontWeight.Medium else FontWeight.Normal,
                    color = if (isCur) primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
