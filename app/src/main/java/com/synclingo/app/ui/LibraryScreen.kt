package com.synclingo.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.synclingo.app.DownloadService
import com.synclingo.app.data.DownloadJob
import com.synclingo.app.data.Downloader
import com.synclingo.app.data.Engine
import com.synclingo.app.data.Library
import com.synclingo.app.data.LibraryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    sharedText: String?,
    onSharedConsumed: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val jobs by Downloader.jobs.collectAsState()
    val libVersion by Library.version.collectAsState()
    val engineState by Engine.state.collectAsState()
    val engineVersion by Engine.version.collectAsState()

    var url by rememberSaveable { mutableStateOf("") }
    var audioOnly by rememberSaveable { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<LibraryItem>>(emptyList()) }
    var menuOpen by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<LibraryItem?>(null) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var errorJob by remember { mutableStateOf<DownloadJob?>(null) }

    LaunchedEffect(libVersion) {
        items = withContext(Dispatchers.IO) { Library.list(ctx) }
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun startDownload(input: String) {
        val err = Downloader.enqueue(ctx, input, audioOnly)
        if (err != null) {
            Toast.makeText(ctx, err, Toast.LENGTH_SHORT).show()
        } else {
            url = ""
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            DownloadService.start(ctx)
        }
    }

    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            url = sharedText
            startDownload(sharedText)
            onSharedConsumed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SyncLingo", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showImport = true }) { Icon(Icons.Default.FileOpen, "내 파일 가져오기") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "메뉴") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("다운로드 엔진 업데이트") },
                                leadingIcon = { Icon(Icons.Default.SystemUpdate, null) },
                                onClick = {
                                    menuOpen = false
                                    busyMessage = "엔진 업데이트 중…"
                                    scope.launch {
                                        val r = Engine.update(ctx, nightly = false)
                                        busyMessage = null
                                        Toast.makeText(ctx, r, Toast.LENGTH_LONG).show()
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("엔진 최신 테스트판(nightly)") },
                                leadingIcon = { Icon(Icons.Default.Science, null) },
                                onClick = {
                                    menuOpen = false
                                    busyMessage = "nightly 엔진 받는 중…"
                                    scope.launch {
                                        val r = Engine.update(ctx, nightly = true)
                                        busyMessage = null
                                        Toast.makeText(ctx, r, Toast.LENGTH_LONG).show()
                                    }
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "엔진: " + when (engineState) {
                                            Engine.State.INITIALIZING -> "준비 중…"
                                            Engine.State.READY -> "yt-dlp ${engineVersion ?: "?"}"
                                            Engine.State.FAILED -> "오류"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                                onClick = { menuOpen = false },
                            )
                        }
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("유튜브 링크로 받기", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("https://youtu.be/…") },
                            trailingIcon = {
                                IconButton(onClick = { clipboard.getText()?.text?.let { url = it } }) {
                                    Icon(Icons.Default.ContentPaste, "붙여넣기")
                                }
                            },
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = !audioOnly, onClick = { audioOnly = false },
                                label = { Text("영상 720p") },
                                leadingIcon = { Icon(Icons.Default.Movie, null, Modifier.size(18.dp)) },
                            )
                            Spacer(Modifier.width(8.dp))
                            FilterChip(
                                selected = audioOnly, onClick = { audioOnly = true },
                                label = { Text("오디오만") },
                                leadingIcon = { Icon(Icons.Default.Headphones, null, Modifier.size(18.dp)) },
                            )
                            Spacer(Modifier.weight(1f))
                            Button(onClick = { startDownload(url) }, enabled = url.isNotBlank()) {
                                Icon(Icons.Default.Download, null)
                                Spacer(Modifier.width(4.dp))
                                Text("받기")
                            }
                        }
                        Text(
                            "팁: 유튜브 앱에서 공유 → SyncLingo 를 누르면 바로 받아집니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(jobs.filter { it.status != DownloadJob.Status.DONE }, key = { "job_" + it.id }) { job ->
                JobCard(job, onCancel = { Downloader.cancel(job.id) }, onDismiss = { Downloader.dismiss(job.id) }, onShowError = { errorJob = job })
            }

            if (items.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Default.School, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(8.dp))
                        Text("아직 받은 영상이 없습니다", color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else {
                item { Text("내 학습 영상 ${items.size}개", style = MaterialTheme.typography.titleSmall) }
            }

            items(items, key = { it.id }) { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .combinedClickable(onClick = { onOpen(item.id) }, onLongClick = { confirmDelete = item })
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (item.thumb != null) {
                            AsyncImage(model = item.thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxSize()) {}
                            Icon(if (item.isAudioOnly) Icons.Default.Headphones else Icons.Default.Movie, null)
                        }
                        if (item.durationSec > 0) {
                            Text(
                                formatTime(item.durationSec * 1000),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .padding(0.dp),
                                color = androidx.compose.ui.graphics.Color.White,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (item.channel.isNotBlank()) Text(item.channel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Tag(if (item.subtitle != null) "EN" else "자막없음", item.subtitle != null)
                            if (item.translation != null) Tag("KO", true)
                            if (item.isAudioOnly) Tag("오디오", true)
                        }
                    }
                }
            }
            item {
                Text(
                    "길게 누르면 삭제할 수 있습니다",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    busyMessage?.let {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(it) },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
        )
    }

    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("삭제할까요?") },
            text = { Text(item.title) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) { Library.delete(item) }
                    confirmDelete = null
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("취소") } },
        )
    }

    errorJob?.let { job ->
        AlertDialog(
            onDismissRequest = { errorJob = null },
            title = { Text("다운로드 실패") },
            text = { Text(job.error ?: "", style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { errorJob = null }) { Text("닫기") } },
            dismissButton = {
                TextButton(onClick = {
                    errorJob = null
                    Downloader.enqueue(ctx, job.url, job.audioOnly)
                    DownloadService.start(ctx)
                }) { Text("다시 시도") }
            },
        )
    }

    if (showImport) ImportDialog(onDismiss = { showImport = false })
}

@Composable
private fun Tag(text: String, positive: Boolean) {
    Surface(
        color = if (positive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
    }
}

@Composable
private fun JobCard(job: DownloadJob, onCancel: () -> Unit, onDismiss: () -> Unit, onShowError: () -> Unit) {
    val failed = job.status == DownloadJob.Status.FAILED
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(job.title ?: job.id, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (failed) "실패 — 눌러서 자세히 보기" else "${job.stage} ${if (job.progress > 0) "${(job.progress * 100).toInt()}%" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (failed) {
                    TextButton(onClick = onShowError) { Text("자세히") }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "닫기") }
                } else {
                    IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "취소") }
                }
            }
            if (!failed) {
                Spacer(Modifier.height(8.dp))
                if (job.progress > 0f) LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var media by remember { mutableStateOf<Uri?>(null) }
    var sub by remember { mutableStateOf<Uri?>(null) }
    var ko by remember { mutableStateOf<Uri?>(null) }
    var title by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            media = uri
            if (title.isBlank()) title = Library.displayName(ctx, uri).substringBeforeLast('.')
        }
    }
    val pickSub = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { sub = it ?: sub }
    val pickKo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { ko = it ?: ko }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("내 파일 가져오기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "다른 앱(Seal, NewPipe 등)으로 받은 영상과 자막 파일(.srt/.vtt)을 불러와 학습할 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { pickMedia.launch(arrayOf("video/*", "audio/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text(media?.let { "영상: " + Library.displayName(ctx, it) } ?: "① 영상/오디오 파일 선택", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = { pickSub.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text(sub?.let { "자막: " + Library.displayName(ctx, it) } ?: "② 영어 자막 파일 (선택)", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = { pickKo.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text(ko?.let { "번역: " + Library.displayName(ctx, it) } ?: "③ 한국어 자막 파일 (선택)", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("제목") }, singleLine = true)
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = media != null && !working,
                onClick = {
                    working = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching { Library.import(ctx, media!!, sub, ko, title) }
                        }
                        working = false
                        r.onFailure { Toast.makeText(ctx, "가져오기 실패: ${it.message}", Toast.LENGTH_LONG).show() }
                        r.onSuccess { onDismiss() }
                    }
                },
            ) { Text("가져오기") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("취소") } },
    )
}
