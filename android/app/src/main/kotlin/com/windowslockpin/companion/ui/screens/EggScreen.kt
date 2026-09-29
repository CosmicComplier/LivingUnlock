package com.windowslockpin.companion.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.windowslockpin.companion.core.egg.EggCodec
import com.windowslockpin.companion.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

@Composable
fun EggScreen(raw: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var password by remember(raw) { mutableStateOf("") }
    var payload by remember(raw) { mutableStateOf<EggCodec.Payload?>(null) }
    var error by remember(raw) { mutableStateOf<String?>(null) }
    var busy by remember(raw) { mutableStateOf(false) }
    val envelope = remember(raw) { runCatching { EggCodec.parse(raw) } }
    val needsPassword = envelope.getOrNull()?.mode == "P9"

    fun decode() {
        if (busy) return
        busy = true
        error = null
        val chars = password.toCharArray()
        password = ""
        scope.launch {
            try {
                payload = withContext(Dispatchers.Default) { EggCodec.decryptPayload(raw, chars) }
            } catch (_: Exception) {
                error = "无法解读这份内容：口令不正确，或二维码已经损坏。"
            } finally {
                chars.fill('\u0000')
                busy = false
            }
        }
    }

    LaunchedEffect(raw) {
        if (envelope.isSuccess && !needsPassword) decode()
    }

    val context = LocalContext.current
    val backgroundRes = remember(raw) {
        listOf(R.drawable.bg_egg_deepsea, R.drawable.bg_egg_observatory, R.drawable.bg_egg_nebula).random()
    }
    val reduceMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val entry = remember(raw) { Animatable(if (reduceMotion || envelope.isFailure) 1f else 0f) }
    LaunchedEffect(raw) {
        if (reduceMotion || envelope.isFailure) entry.snapTo(1f)
        else entry.animateTo(1f, tween(500, easing = LinearEasing))
    }
    val video = payload as? EggCodec.Payload.MediaUrl
    Box(Modifier.fillMaxSize().background(Color(0xFF07111E))) {
        Image(
            painter = painterResource(backgroundRes),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = 0.64f
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color(0x66101F35), Color(0xB8101F35), Color(0xF0101F35))
                )
            )
        )
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
                    .transform(((entry.value - 0.1f) / 0.65f).coerceIn(0f, 1f))
            }
        ) {
            if (video?.kind == EggCodec.MediaKind.VIDEO) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    RemoteVideoCard(video.url, onBack, maxWidth, maxHeight)
                }
            } else {
                Column(
                    Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                        .verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    TextButton(onClick = onBack) { Text("← 返回", color = Color(0xFF78DFFF)) }
                    Text(contentTitle(payload), fontSize = 26.sp, color = Color.White)
                    Text("LivingUnlock · 秘密内容", color = Color(0xFF83B6D5))

                    if (envelope.isFailure) Text("无法识别此彩蛋。", color = Color(0xFFFF91AC))
                    if (needsPassword && payload == null) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { if (it.length <= 256) password = it },
                            label = { Text("彩蛋口令") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy
                        )
                        Button(onClick = { decode() }, enabled = !busy && password.isNotEmpty()) { Text("解读内容") }
                    }
                    if (busy) CircularProgressIndicator(color = Color(0xFF75D9FF))
                    error?.let { Text(it, color = Color(0xFFFF91AC)) }

                    when (val value = payload) {
                        is EggCodec.Payload.Text -> MessageCard(value.text)
                        is EggCodec.Payload.Image -> EncodedImageCard(value)
                        is EggCodec.Payload.MediaUrl -> if (value.kind == EggCodec.MediaKind.IMAGE) RemoteImageCard(value.url)
                        null -> Unit
                    }
                }
            }
        }
        if (!reduceMotion && entry.value < 1f) {
            EggPortalEntrance(progress = { entry.value })
        }
    }
}

private fun contentTitle(payload: EggCodec.Payload?): String = when (payload) {
    is EggCodec.Payload.Image -> "藏在光里的图像"
    is EggCodec.Payload.MediaUrl -> if (payload.kind == EggCodec.MediaKind.VIDEO) "藏在光里的影像" else "藏在光里的图像"
    else -> "藏在光里的留言"
}

@Composable
private fun MessageCard(message: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E3652))) {
        SelectionContainer {
            Text(message, Modifier.padding(24.dp), color = Color(0xFFE1F8FF), fontSize = 19.sp, lineHeight = 31.sp)
        }
    }
}

@Composable
private fun EncodedImageCard(payload: EggCodec.Payload.Image) {
    val bitmap = remember(payload.bytes) { BitmapFactory.decodeByteArray(payload.bytes, 0, payload.bytes.size) }
    if (bitmap == null) {
        Text("图片数据无法显示。", color = Color(0xFFFF91AC))
    } else {
        BitmapCard(bitmap, "二维码中的 128×128 图片")
        SaveBytesButton(
            bytes = payload.bytes,
            mimeType = payload.mimeType,
            suggestedName = "livingunlock-image.${if (payload.mimeType == "image/png") "png" else "jpg"}",
            label = "保存图片"
        )
        DisposableEffect(bitmap) { onDispose { bitmap.recycle() } }
    }
}

private sealed interface RemoteImageState {
    data object Loading : RemoteImageState
    data class Ready(val media: DownloadedImage) : RemoteImageState
    data class Failed(val message: String) : RemoteImageState
}

private data class DownloadedImage(val bitmap: Bitmap, val bytes: ByteArray, val mimeType: String)

@Composable
private fun RemoteImageCard(url: String) {
    val state by produceState<RemoteImageState>(RemoteImageState.Loading, url) {
        value = try {
            RemoteImageState.Ready(withContext(Dispatchers.IO) { downloadImage(url) })
        } catch (e: Exception) {
            RemoteImageState.Failed(e.message ?: "图片加载失败")
        }
    }
    when (val current = state) {
        RemoteImageState.Loading -> CircularProgressIndicator(color = Color(0xFF75D9FF))
        is RemoteImageState.Failed -> Text("图片加载失败：${current.message}", color = Color(0xFFFF91AC))
        is RemoteImageState.Ready -> {
            BitmapCard(current.media.bitmap, "网址中的图片")
            SaveBytesButton(
                bytes = current.media.bytes,
                mimeType = current.media.mimeType,
                suggestedName = suggestedMediaName(url, current.media.mimeType, "image"),
                label = "保存图片"
            )
            DisposableEffect(current.media.bitmap) { onDispose { current.media.bitmap.recycle() } }
        }
    }
    SelectableLink(url)
}

@Composable
private fun BitmapCard(bitmap: Bitmap, description: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E3652))) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = description,
            modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 520.dp).padding(12.dp),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
private fun SelectableLink(url: String) {
    SelectionContainer {
        Text(url, color = Color(0xFF83B6D5), fontSize = 12.sp)
    }
}

@Composable
private fun SaveBytesButton(bytes: ByteArray, mimeType: String, suggestedName: String, label: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { uri ->
        if (uri != null) {
            saving = true
            resultText = null
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                            ?: error("无法打开保存位置")
                    }
                }
                saving = false
                resultText = if (result.isSuccess) "保存完成" else "保存失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
            }
        }
    }
    Button(
        onClick = { launcher.launch(suggestedName) },
        enabled = !saving,
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (saving) "正在保存…" else label) }
    resultText?.let { Text(it, color = if (it == "保存完成") Color(0xFF7FFFC1) else Color(0xFFFF91AC), fontSize = 12.sp) }
}

private fun suggestedMediaName(url: String, mimeType: String, fallbackStem: String): String {
    val fromUrl = runCatching { URL(url).path.substringAfterLast('/').substringBefore('?') }.getOrDefault("")
        .replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
    if (fromUrl.contains('.') && fromUrl.length > 2) return fromUrl
    val extension = when (mimeType.lowercase(Locale.ROOT).substringBefore(';')) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        else -> if (mimeType.startsWith("image/")) "jpg" else "mp4"
    }
    return "livingunlock-$fallbackStem.$extension"
}

@Composable
private fun RemoteVideoCard(url: String, onBack: () -> Unit, viewportWidth: androidx.compose.ui.unit.Dp, viewportHeight: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val retainedPlayer = remember(url) { TextureVideoView(context) }
    DisposableEffect(retainedPlayer) {
        onDispose { retainedPlayer.release() }
    }
    var failed by remember(url) { mutableStateOf(false) }
    var isPlaying by remember(url) { mutableStateOf(false) }
    var playerView by remember(url) { mutableStateOf<TextureVideoView?>(null) }
    var durationMs by remember(url) { mutableStateOf(0) }
    var positionMs by remember(url) { mutableStateOf(0) }
    var isSeeking by remember(url) { mutableStateOf(false) }
    var isFullscreen by remember(url) { mutableStateOf(false) }


    var videoAspect by remember(url) { mutableStateOf(0f) }
    var controlsVisible by remember(url) { mutableStateOf(true) }
    var controlsInteraction by remember(url) { mutableStateOf(0) }

    fun showControls() {
        controlsVisible = true
        controlsInteraction++
    }

    fun changeFullscreen(fullscreen: Boolean) {


        isFullscreen = fullscreen
        isSeeking = false
        showControls()
    }

    // Keep the original policy for the entire fullscreen session, including late video metadata.
    DisposableEffect(activity, isFullscreen) {
        val originalOrientation = activity?.requestedOrientation
        val restoreOnDispose = isFullscreen
        onDispose {
            if (restoreOnDispose && activity != null && originalOrientation != null) {
                activity.requestedOrientation = originalOrientation
            }
        }
    }
    LaunchedEffect(activity, isFullscreen, videoAspect) {
        if (isFullscreen && videoAspect > 0f) {
            activity?.requestedOrientation = if (videoAspect > 1f)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }
    LaunchedEffect(isFullscreen, controlsVisible, controlsInteraction, isPlaying, isSeeking, failed) {
        if (isFullscreen && controlsVisible && isPlaying && !isSeeking && !failed) {
            delay(3000)
            controlsVisible = false
        }
    }
    LaunchedEffect(isPlaying, failed) {
        if (!isPlaying || failed) showControls()
    }

    LaunchedEffect(playerView, url, isFullscreen) {
        while (true) {
            val view = playerView
            view?.videoAspect()?.takeIf { it > 0f }?.let { videoAspect = it }
            if (view != null && !isSeeking) {
                durationMs = view.durationMs()
                positionMs = view.positionMs().coerceIn(0, durationMs.coerceAtLeast(0))
            }
            delay(250)
        }
    }

    androidx.activity.compose.BackHandler(enabled = isFullscreen) { changeFullscreen(false) }
    if (isFullscreen) FullscreenVideoSystemUi()
    val scroll = rememberScrollState()
    LaunchedEffect(isFullscreen) { if (isFullscreen) scroll.scrollTo(0) }
    // One permanent layout and one permanent AndroidView: fullscreen changes dimensions only.
    Column(
        Modifier.fillMaxSize().background(if (isFullscreen) Color.Black else Color.Transparent)
            .then(if (isFullscreen) Modifier else Modifier.safeDrawingPadding())
            .verticalScroll(scroll, enabled = !isFullscreen)
            .padding(if (isFullscreen) 0.dp else 24.dp)
    ) {
        if (!isFullscreen) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text("藏在光里的影像", color = Color.White, fontSize = 26.sp)
            Spacer(Modifier.height(18.dp))
        }
        Box(
            Modifier.fillMaxWidth().height(if (isFullscreen) viewportHeight else (viewportWidth - 48.dp) * 9f / 16f)
                .background(Color.Black)
        ) {
            TextureVideoSurface(
                player = retainedPlayer,
                url = url,
                modifier = Modifier.fillMaxSize(),
                onReady = { playerView = it },
                onPlayingChanged = { view, playing -> if (playerView === view) isPlaying = playing },
                onFailure = { failed = true },
                onSurfaceTap = {
                    if (isFullscreen) {
                        if (controlsVisible) controlsVisible = false else showControls()
                    }
                },
                onSurfaceDoubleTap = { if (isFullscreen) showControls() },
                onGestureSeek = { target, finished ->
                    positionMs = target
                    isSeeking = true
                    if (isFullscreen) showControls()
                },
                onSeekCompleted = { actual ->
                    positionMs = actual
                    isSeeking = false
                    if (isFullscreen) showControls()
                }
            )
            if (isFullscreen && controlsVisible) {
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color(0xCC07111E)).safeDrawingPadding().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { retainedPlayer.togglePlayback(); showControls() }) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "暂停" else "播放", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        VideoTimeline(durationMs, positionMs, !failed,
                            { isSeeking = true; positionMs = it; showControls() },
                            { retainedPlayer.seekTo(positionMs); showControls() })
                    }
                    IconButton(onClick = { changeFullscreen(false) }) {
                        Icon(Icons.Default.FullscreenExit, contentDescription = "退出全屏", tint = Color.White)
                    }
                }
            }
        }
        if (!isFullscreen) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
                verticalAlignment = Alignment.Top
            ) {
                IconButton(
                    onClick = { retainedPlayer.togglePlayback() },
                    enabled = !failed,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停视频" else "播放视频",
                        tint = if (failed) Color.Gray else Color(0xFF78DFFF),
                        modifier = Modifier.size(26.dp)
                    )
                }
                Column(Modifier.weight(1f)) {
                    VideoTimeline(durationMs, positionMs, !failed,
                        { isSeeking = true; positionMs = it },
                        { retainedPlayer.seekTo(positionMs) })
                }
                IconButton(onClick = { changeFullscreen(true) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Fullscreen, contentDescription = "全屏",
                        tint = Color(0xFF78DFFF), modifier = Modifier.size(26.dp))
                }
            }
            if (failed) Text("视频无法播放，请检查网址或视频格式。", color = Color(0xFFFF91AC))
            SaveRemoteVideoButton(url)
            SelectableLink(url)
        }
    }
}

@Composable
private fun SaveRemoteVideoButton(url: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember(url) { mutableStateOf(false) }
    var resultText by remember(url) { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/*")) { uri ->
        if (uri != null) {
            saving = true
            resultText = null
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { saveRemoteVideo(context, url, uri) } }
                saving = false
                resultText = if (result.isSuccess) "保存完成" else "保存失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
            }
        }
    }
    Button(
        onClick = { launcher.launch(suggestedMediaName(url, "video/mp4", "video")) },
        enabled = !saving,
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (saving) "正在保存视频…" else "保存视频") }
    resultText?.let { Text(it, color = if (it == "保存完成") Color(0xFF7FFFC1) else Color(0xFFFF91AC), fontSize = 12.sp) }
}

@Composable
private fun TextureVideoSurface(
    player: TextureVideoView,
    url: String,


    modifier: Modifier,
    onReady: (TextureVideoView) -> Unit,

    onPlayingChanged: (TextureVideoView, Boolean) -> Unit,
    onFailure: () -> Unit,
    onSurfaceTap: (() -> Unit)? = null,
    onSurfaceDoubleTap: (() -> Unit)? = null,
    onGestureSeek: ((Int, Boolean) -> Unit)? = null,
    onSeekCompleted: ((Int) -> Unit)? = null
) {
    AndroidView(
        factory = { context ->
            FrameLayout(context).apply {
                (player.parent as? ViewGroup)?.removeView(player)
                addView(player, FrameLayout.LayoutParams(-1, -1))
                onReady(player)
                player.onPlayingChanged = { onPlayingChanged(player, it) }
                player.onFailure = onFailure
                player.onSurfaceTap = onSurfaceTap
                player.onSurfaceDoubleTap = onSurfaceDoubleTap
                player.onGestureSeek = onGestureSeek
                player.onSeekCompleted = onSeekCompleted
                player.setSource(url)
            }
        },
        update = {
            val view = player
            view.onPlayingChanged = { onPlayingChanged(view, it) }
            view.onFailure = onFailure
            view.onSurfaceTap = onSurfaceTap
            view.onSurfaceDoubleTap = onSurfaceDoubleTap
            view.onGestureSeek = onGestureSeek
            view.onSeekCompleted = onSeekCompleted
            view.setSource(url)
            onReady(view)
        },
        onRelease = { container ->
            // The result screen owns the player. Fullscreen only changes its host.
            if (player.parent === container) container.removeView(player)
        },
        modifier = modifier
    )
}

@Composable
private fun VideoTimeline(
    durationMs: Int,
    positionMs: Int,
    enabled: Boolean,
    onSeeking: (Int) -> Unit,
    onSeekFinished: () -> Unit
) {
    Slider(
        value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
        onValueChange = { fraction -> onSeeking((fraction * durationMs).toInt().coerceIn(0, durationMs)) },
        onValueChangeFinished = onSeekFinished,
        enabled = enabled && durationMs > 0,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(formatMediaTime(positionMs), color = Color(0xFFB9D7E8), fontSize = 12.sp)
        Text(formatMediaTime(durationMs), color = Color(0xFFB9D7E8), fontSize = 12.sp)
    }
}

@Composable
private fun FullscreenVideoSystemUi() {
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    DisposableEffect(view, configuration.orientation) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }


        window?.let {
            WindowCompat.setDecorFitsSystemWindows(it, false)
            it.attributes = it.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

        }
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun formatMediaTime(milliseconds: Int): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0) / 1000)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

private class TextureVideoView(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener {
    private val textureView = TextureView(context)
    private var mediaPlayer: MediaPlayer? = null
    private var sourceUrl: String? = null
    private var prepared = false
    private var playWhenReady = false
    private var pendingSeekMs = 0
    private var videoWidth = 0
    private var videoHeight = 0

    var onPlayingChanged: (Boolean) -> Unit = {}
    var onFailure: () -> Unit = {}
    var onSurfaceTap: (() -> Unit)? = null
    var onSurfaceDoubleTap: (() -> Unit)? = null
    var onGestureSeek: ((Int, Boolean) -> Unit)? = null
    var onSeekCompleted: ((Int) -> Unit)? = null
    private var gestureSeeking = false
    private var gestureStartPositionMs = 0
    private var gestureTargetPositionMs = 0
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            onSurfaceTap?.invoke()
            return true
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            togglePlayback()
            onSurfaceDoubleTap?.invoke()
            return true
        }

        override fun onScroll(
            first: MotionEvent?,
            current: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            val start = first ?: return false
            val deltaX = current.x - start.x
            val deltaY = current.y - start.y
            if (!gestureSeeking && (kotlin.math.abs(deltaX) < 24f || kotlin.math.abs(deltaX) <= kotlin.math.abs(deltaY))) {
                return false
            }
            val duration = durationMs()
            if (duration <= 0 || textureView.width <= 0) return false
            if (!gestureSeeking) {
                gestureSeeking = true
                gestureStartPositionMs = positionMs()
            }
            gestureTargetPositionMs = (gestureStartPositionMs + deltaX / textureView.width * duration)
                .toInt().coerceIn(0, duration)
            onGestureSeek?.invoke(gestureTargetPositionMs, false)
            return true
        }
    })

    init {
        setBackgroundColor(AndroidColor.BLACK)
        textureView.surfaceTextureListener = this
        textureView.setOnTouchListener { _, event ->
            val handled = gestureDetector.onTouchEvent(event)
            if ((event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) && gestureSeeking) {
                seekTo(gestureTargetPositionMs)
                onGestureSeek?.invoke(gestureTargetPositionMs, true)
                gestureSeeking = false
                true
            } else handled || event.actionMasked == MotionEvent.ACTION_DOWN || gestureSeeking
        }
        addView(textureView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateTransform() }
        isClickable = true
    }

    fun setSource(url: String) {
        if (sourceUrl == url) return
        sourceUrl = url
        playWhenReady = false
        pendingSeekMs = 0
        onPlayingChanged(false)
        releasePlayer()
        if (textureView.isAvailable) prepare(textureView.surfaceTexture ?: return)
    }

    fun togglePlayback() {
        playWhenReady = !playWhenReady
        val player = mediaPlayer
        if (prepared && player != null) {
            if (playWhenReady) player.start() else player.pause()
        }
        onPlayingChanged(playWhenReady)
    }

    fun restorePlayback(positionMs: Int, playing: Boolean) {
        pendingSeekMs = positionMs.coerceAtLeast(0)
        playWhenReady = playing
        val player = mediaPlayer
        if (prepared && player != null) {
            seekExact(player, pendingSeekMs.coerceIn(0, durationMs()))
            if (playWhenReady) player.start() else player.pause()
        }
        onPlayingChanged(playWhenReady)
    }

    fun videoAspect(): Float = if (videoWidth > 0 && videoHeight > 0) videoWidth.toFloat() / videoHeight else 0f

    fun durationMs(): Int = if (prepared) runCatching { mediaPlayer?.duration ?: 0 }.getOrDefault(0).coerceAtLeast(0) else 0

    fun positionMs(): Int = if (prepared) runCatching { mediaPlayer?.currentPosition ?: pendingSeekMs }.getOrDefault(pendingSeekMs) else pendingSeekMs

    fun seekTo(positionMs: Int) {
        pendingSeekMs = positionMs.coerceAtLeast(0)
        if (prepared) runCatching {
            mediaPlayer?.let { seekExact(it, pendingSeekMs.coerceIn(0, durationMs())) }
        }
    }

    fun release() {
        playWhenReady = false
        pendingSeekMs = 0
        sourceUrl = null
        releasePlayer()
        onPlayingChanged(false)
    }

    private fun prepare(surfaceTexture: SurfaceTexture) {
        val url = sourceUrl ?: return
        // Reattach the existing decoder and network stream after fullscreen/rotation.
        mediaPlayer?.let { existing ->
            val surface = Surface(surfaceTexture)
            try { existing.setSurface(surface) } finally { surface.release() }
            updateTransform()
            return
        }
        val player = MediaPlayer()
        mediaPlayer = player
        try {
            val surface = Surface(surfaceTexture)
            try { player.setSurface(surface) } finally { surface.release() }
            player.setDataSource(context, Uri.parse(url))
            player.setScreenOnWhilePlaying(true)
            player.setOnVideoSizeChangedListener { _, width, height ->
                videoWidth = width
                videoHeight = height
                updateTransform()
            }
            player.setOnPreparedListener {
                prepared = true
                seekExact(it, pendingSeekMs.coerceIn(0, it.duration.coerceAtLeast(0)))
                if (playWhenReady) it.start()
                onPlayingChanged(playWhenReady)
            }
            player.setOnSeekCompleteListener {
                pendingSeekMs = it.currentPosition.coerceAtLeast(0)
                onSeekCompleted?.invoke(pendingSeekMs)
            }
            player.setOnCompletionListener {
                playWhenReady = false
                onPlayingChanged(false)
            }
            player.setOnErrorListener { _, _, _ ->
                prepared = false
                playWhenReady = false
                onPlayingChanged(false)
                onFailure()
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            releasePlayer()
            onFailure()
        }
    }

    private fun releasePlayer() {
        prepared = false
        mediaPlayer?.let { player ->
            runCatching { player.stop() }
            player.reset()
            player.release()
        }
        mediaPlayer = null
    }

    private fun seekExact(player: MediaPlayer, positionMs: Int) {
        player.seekTo(positionMs.toLong(), MediaPlayer.SEEK_CLOSEST)
    }

    private fun updateTransform() {
        if (videoWidth <= 0 || videoHeight <= 0 || textureView.width <= 0 || textureView.height <= 0) return
        val viewAspect = textureView.width.toFloat() / textureView.height
        val videoAspect = videoWidth.toFloat() / videoHeight
        val scaleX = if (videoAspect < viewAspect) videoAspect / viewAspect else 1f
        val scaleY = if (videoAspect > viewAspect) viewAspect / videoAspect else 1f
        textureView.setTransform(Matrix().apply {
            setScale(scaleX, scaleY, textureView.width / 2f, textureView.height / 2f)
        })
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = prepare(surface)
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = updateTransform()
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        mediaPlayer?.setSurface(null)
        return true
    }
}

private fun downloadImage(initialUrl: String): DownloadedImage {
    var current = URL(initialUrl)
    repeat(4) {
        require(current.protocol.equals("https", ignoreCase = true)) { "只允许 HTTPS 图片" }
        val connection = current.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 8_000
        connection.readTimeout = 12_000
        connection.setRequestProperty("Accept", "image/*")
        connection.setRequestProperty("User-Agent", "LivingUnlock/1")
        try {
            val status = connection.responseCode
            if (status in 300..399) {
                val location = connection.getHeaderField("Location") ?: error("图片重定向地址无效")
                current = URL(current, location)
                return@repeat
            }
            require(status in 200..299) { "服务器返回 $status" }
            val mimeType = connection.contentType?.substringBefore(';')?.lowercase(Locale.ROOT)
            require(mimeType?.startsWith("image/") == true) { "网址返回的不是图片" }
            val declaredLength = connection.contentLengthLong
            require(declaredLength <= MAX_REMOTE_IMAGE_BYTES || declaredLength < 0) { "远程图片超过 8 MB" }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_REMOTE_IMAGE_BYTES) { "远程图片超过 8 MB" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..16_384 && bounds.outHeight in 1..16_384) { "图片尺寸无效或过大" }
            var sample = 1
            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: error("无法解码图片")
            return DownloadedImage(bitmap, bytes, mimeType)
        } finally {
            connection.disconnect()
        }
    }
    error("图片重定向次数过多")
}

private fun saveRemoteVideo(context: Context, initialUrl: String, destination: Uri) {
    var current = URL(initialUrl)
    try {
        repeat(4) {
            require(current.protocol.equals("https", ignoreCase = true)) { "只允许 HTTPS 视频" }
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "video/*,application/octet-stream")
            connection.setRequestProperty("User-Agent", "LivingUnlock/1")
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location") ?: error("视频重定向地址无效")
                    current = URL(current, location)
                    return@repeat
                }
                require(status in 200..299) { "服务器返回 $status" }
                val mimeType = connection.contentType?.substringBefore(';')?.lowercase(Locale.ROOT).orEmpty()
                require(!mimeType.contains("mpegurl") && !current.path.lowercase(Locale.ROOT).endsWith(".m3u8")) {
                    "分段视频流暂不支持保存为单个文件"
                }
                require(mimeType.isEmpty() || mimeType.startsWith("video/") || mimeType == "application/octet-stream") {
                    "网址返回的不是可保存的视频文件"
                }
                val declaredLength = connection.contentLengthLong
                require(declaredLength <= MAX_REMOTE_VIDEO_BYTES || declaredLength < 0) { "视频超过 512 MB" }
                context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_REMOTE_VIDEO_BYTES) { "视频超过 512 MB" }
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: error("无法打开保存位置")
                return
            } finally {
                connection.disconnect()
            }
        }
        error("视频重定向次数过多")
    } catch (e: Exception) {
        runCatching { context.contentResolver.delete(destination, null, null) }
        throw e
    }
}

private const val MAX_REMOTE_IMAGE_BYTES = 8 * 1024 * 1024
private const val MAX_REMOTE_VIDEO_BYTES = 512L * 1024 * 1024
