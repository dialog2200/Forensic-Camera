package com.forensic.capture.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.forensic.capture.state.AppState
import com.forensic.capture.state.CaptureMode
import com.forensic.capture.state.RecState
import com.forensic.capture.state.Screen
import com.forensic.capture.ui.components.FCChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.forensic.capture.ui.components.FCIcons
import com.forensic.capture.ui.components.IconSpec
import com.forensic.capture.ui.components.IconSquareButton
import com.forensic.capture.ui.components.LineIcon
import com.forensic.capture.ui.components.ToastKind
import com.forensic.capture.ui.theme.FCColor
import com.forensic.capture.ui.theme.FCMedia
import com.forensic.capture.ui.theme.FCRadius
import com.forensic.capture.ui.theme.FCSpace
import com.forensic.capture.ui.theme.FCType
import com.forensic.capture.ui.theme.MonoFamily
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sin

@Composable
fun RecordScreen(app: AppState) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    var gridOn by remember { mutableStateOf(true) }
    var phase by remember { mutableIntStateOf(0) }
    var mbWritten by remember { mutableStateOf(0.0) }
    var pendingStart by remember { mutableStateOf(false) }
    var previewRef by remember { mutableStateOf<PreviewView?>(null) }

    // 计时：录制中累加
    LaunchedEffect(app.recState) {
        while (app.recState == RecState.Recording) {
            delay(1000)
            app.elapsedSec += 1
        }
    }
    // 波形装饰动画相位：仅纯音频模式需要，视频录制时不再每 130ms 重组整屏
    LaunchedEffect(app.recState, app.mode) {
        while (app.recState != RecState.Idle && app.mode == CaptureMode.Audio) {
            delay(130)
            phase += 1
        }
    }
    // 真实写入体积：E1 统计密文目录，F0 统计明文文件；目录扫描移到 IO
    LaunchedEffect(app.recState) {
        while (app.recState == RecState.Recording) {
            val bytes = withContext(Dispatchers.IO) { app.currentRecordingBytes() }
            mbWritten = bytes / 1048576.0
            delay(1000)
        }
        if (app.recState == RecState.Idle) mbWritten = 0.0
    }
    // 视频模式绑定相机预览
    LaunchedEffect(previewRef, app.mode) {
        val pv = previewRef
        if (pv != null && activity != null && app.mode == CaptureMode.Video) {
            app.capture.bind(activity, pv, lifecycleOwner) { msg -> app.showToast(msg, ToastKind.Bad) }
        }
    }
    // 离开取景页解绑相机（录制中不解除），避免重进后预览黑屏
    DisposableEffect(Unit) {
        onDispose { app.capture.unbind() }
    }

    fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (!pendingStart) return@rememberLauncherForActivityResult
        pendingStart = false
        val camOk = res[Manifest.permission.CAMERA] ?: hasPerm(Manifest.permission.CAMERA)
        val micOk = res[Manifest.permission.RECORD_AUDIO] ?: hasPerm(Manifest.permission.RECORD_AUDIO)
        val ok = if (app.mode == CaptureMode.Video) camOk else micOk
        if (ok) {
            app.startRecording()
        } else {
            app.showToast("权限被拒绝，无法录制", ToastKind.Bad)
        }
    }

    fun onMainButton() {
        when (app.recState) {
            RecState.Idle -> {
                val need = if (app.mode == CaptureMode.Video)
                    Manifest.permission.CAMERA else Manifest.permission.RECORD_AUDIO
                if (hasPerm(need)) {
                    app.startRecording()
                } else {
                    pendingStart = true
                    permLauncher.launch(
                        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                    )
                }
            }
            RecState.Recording -> app.stopRecording()
            RecState.Sealing -> {}
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = FCSpace.screen)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconSquareButton(FCIcons.Back, { app.go(Screen.Home) })
            Spacer(Modifier.width(FCSpace.x3))
            Column(modifier = Modifier.weight(1f)) {
                Text("新建取证任务", color = FCColor.TextDim, fontSize = FCType.label)
                Text("取证录制", color = FCColor.TextHi, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            FCChip(
                text = if (app.configured) "至信链直连" else "未配置凭据",
                color = if (app.configured) FCColor.Ok else FCColor.Warn,
                background = if (app.configured) FCColor.OkSoft else FCColor.WarnSoft,
                borderColor = (if (app.configured) FCColor.Ok else FCColor.Warn).copy(alpha = 0.24f),
                dotColor = if (app.configured) FCColor.Ok else FCColor.Warn
            )
        }

        ModeSeg(
            mode = app.mode,
            enabled = app.recState == RecState.Idle,
            onChange = { app.toggleMode(it) }
        )

        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(FCRadius.lg))
                .background(FCColor.BgPage)
                .border(1.dp, FCColor.Line, RoundedCornerShape(FCRadius.lg))
                .clickable { gridOn = !gridOn }
        ) {
            if (app.mode == CaptureMode.Video) {
                if (app.settings.encRecord) {
                    // 加密取证：Camera2 直接把帧送到编码 Surface，明文不落盘
                    AndroidView(
                        factory = { c ->
                            android.view.SurfaceView(c).apply {
                                holder.addCallback(object : android.view.SurfaceHolder.Callback {
                                    override fun surfaceCreated(h: android.view.SurfaceHolder) {
                                        app.encPreview = h.surface
                                    }

                                    override fun surfaceChanged(
                                        h: android.view.SurfaceHolder, format: Int, width: Int, height: Int
                                    ) {
                                        app.encPreview = h.surface
                                    }

                                    override fun surfaceDestroyed(h: android.view.SurfaceHolder) {
                                        app.encPreview = null
                                    }
                                })
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AndroidView(
                        factory = { c ->
                            PreviewView(c).apply {
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                                setBackgroundColor(android.graphics.Color.BLACK)
                                previewRef = this
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                CameraHud(gridOn = gridOn, recording = app.recState == RecState.Recording)
            } else {
                AudioStage(phase = phase, recording = app.recState == RecState.Recording)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 34.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        if (app.mode != CaptureMode.Video) "AAC · m4a"
                        else if (app.settings.encRecord) "E1 · AES-256-GCM" else "HD · CameraX",
                        color = FCColor.HudText,
                        fontSize = 9.sp,
                        fontFamily = MonoFamily
                    )
                    Text(
                        if (app.mode != CaptureMode.Video) "单声道 · 沙盒直存"
                        else if (app.settings.encRecord) "H.264 · 密文分片落盘" else "H.264 · 同期声",
                        color = FCColor.TextDim,
                        fontSize = 8.sp,
                        fontFamily = MonoFamily,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${"%.1f".format(mbWritten)} MB",
                        color = FCColor.HudText,
                        fontSize = 9.sp,
                        fontFamily = MonoFamily
                    )
                    Text("已写入", color = FCColor.TextDim, fontSize = 8.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }

            RecPill(app, Modifier.align(Alignment.TopCenter))

            if (app.recState == RecState.Sealing) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(top = 60.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("封存中 · 计算 SHA-256 与复合锚定摘要", color = FCColor.HintText, fontSize = 10.sp)
                }
            } else if (app.mode == CaptureMode.Video && app.recState == RecState.Idle) {
                Text(
                    text = "轻触下方按钮开始录制 · 轻触取景可切换网格",
                    color = FCColor.HintText,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(top = 92.dp)
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                FCChip(
                    "沙盒私有目录",
                    color = FCColor.Ok,
                    background = FCColor.BgPage.copy(alpha = 0.6f),
                    borderColor = FCColor.Ok.copy(alpha = 0.3f),
                    dotColor = FCColor.Ok,
                    fontSize = 9
                )
                FCChip("结束即算摘要", background = FCColor.BgPage.copy(alpha = 0.6f), fontSize = 9)
                FCChip("出处证明随链", background = FCColor.BgPage.copy(alpha = 0.6f), fontSize = 9)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(FCColor.Surface1)
                        .border(1.dp, FCColor.Line, CircleShape)
                        .clickable { app.go(Screen.Ledger) },
                    contentAlignment = Alignment.Center
                ) {
                    LineIcon(FCIcons.Ledger, FCColor.TextMid, 18.dp)
                }
                Spacer(Modifier.height(5.dp))
                Text("台账", color = FCColor.TextDim, fontSize = 9.sp)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RecordButton(app) { onMainButton() }
                Spacer(Modifier.height(7.dp))
                Text(
                    text = when (app.recState) {
                        RecState.Idle -> "开始录制"
                        RecState.Recording -> "结束录制"
                        RecState.Sealing -> "封存中…"
                    },
                    color = FCColor.TextLo,
                    fontSize = 10.sp
                )
            }

            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.height(10.dp))
        Text(
            if (app.mode == CaptureMode.Video)
                "录制期间屏幕常亮；结束后立即计算文件 SHA-256、复合锚定摘要与出处证明"
            else
                "录音期间屏幕常亮；产物为沙盒私有目录中的 m4a，结束即算摘要",
            color = FCColor.TextDim,
            fontSize = 8.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ModeSeg(mode: CaptureMode, enabled: Boolean, onChange: (CaptureMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(FCColor.Surface1)
            .border(1.dp, FCColor.LineSoft, RoundedCornerShape(10.dp))
            .padding(3.dp)
    ) {
        SegItem("视频取证", FCIcons.VideoLine, mode == CaptureMode.Video, enabled, Modifier.weight(1f)) {
            onChange(CaptureMode.Video)
        }
        SegItem("纯音频录音", FCIcons.Mic, mode == CaptureMode.Audio, enabled, Modifier.weight(1f)) {
            onChange(CaptureMode.Audio)
        }
    }
}

@Composable
private fun SegItem(
    label: String,
    icon: IconSpec,
    active: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) FCColor.Brand else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LineIcon(icon, if (active) Color.White else FCColor.TextLo, 13.dp)
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            color = if (active) Color.White else FCColor.TextLo,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun RecPill(app: AppState, modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "rec")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "dot"
    )
    if (app.recState == RecState.Idle) return
    Row(
        modifier = modifier
            .padding(top = 104.dp)
            .clip(CircleShape)
            .background(FCColor.BgPage.copy(alpha = 0.66f))
            .border(1.dp, FCColor.Danger.copy(alpha = 0.32f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(modifier = Modifier.size(7.dp)) {
            drawCircle(color = FCColor.Danger.copy(alpha = if (app.recState == RecState.Recording) alpha else 1f))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            "REC ${AppState.formatDuration(app.elapsedSec)}",
            color = FCColor.TextHi,
            fontSize = 11.sp,
            fontFamily = MonoFamily,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun RecordButton(app: AppState, onMainButton: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "ring")
    val ringAlpha by pulse.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.06f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "ringAlpha"
    )
    Box(modifier = Modifier.size(76.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(76.dp)) {
            drawCircle(color = FCColor.RingSubtle, style = Stroke(1.5.dp.toPx()))
            if (app.recState == RecState.Recording) {
                drawCircle(color = FCColor.Danger.copy(alpha = ringAlpha), style = Stroke(4.dp.toPx()))
            }
        }
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(FCColor.Danger, FCColor.DangerDeep)))
                .clickable { onMainButton() },
            contentAlignment = Alignment.Center
        ) {
            when (app.recState) {
                RecState.Idle -> LineIcon(
                    if (app.mode == CaptureMode.Video) FCIcons.Video else FCIcons.MicRound,
                    Color.White,
                    22.dp
                )
                RecState.Recording -> Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White)
                )
                RecState.Sealing -> Text("…", color = Color.White, fontSize = 20.sp)
            }
        }
    }
}

/** 真实相机预览之上的取景 HUD：三分网格 + 四角取景框（保留原视觉语言）。 */
@Composable
private fun CameraHud(gridOn: Boolean, recording: Boolean) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (gridOn) {
            val lineColor = Color.White.copy(alpha = 0.06f)
            listOf(1f / 3f, 2f / 3f).forEach { f ->
                drawLine(lineColor, Offset(w * f, 0f), Offset(w * f, h), 1f)
                drawLine(lineColor, Offset(0f, h * f), Offset(w, h * f), 1f)
            }
        }
        val bracket = Color.White.copy(alpha = 0.22f)
        val len = 16.dp.toPx()
        val inset = 12.dp.toPx()
        val sw = 1.5.dp.toPx()
        listOf(
            Triple(Offset(inset, inset), 1f, 1f),
            Triple(Offset(w - inset, inset), -1f, 1f),
            Triple(Offset(inset, h - inset), 1f, -1f),
            Triple(Offset(w - inset, h - inset), -1f, -1f)
        ).forEach { (p, sx, sy) ->
            drawLine(bracket, p, Offset(p.x + len * sx, p.y), sw, StrokeCap.Round)
            drawLine(bracket, p, Offset(p.x, p.y + len * sy), sw, StrokeCap.Round)
        }
        if (recording) {
            drawCircle(
                color = FCColor.Danger.copy(alpha = 0.16f),
                radius = 26.dp.toPx(),
                center = Offset(w / 2, h / 2 + 26.dp.toPx())
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.28f),
                radius = 26.dp.toPx(),
                center = Offset(w / 2, h / 2 + 26.dp.toPx()),
                style = Stroke(1f)
            )
        }
    }
}

@Composable
private fun AudioStage(phase: Int, recording: Boolean) {
    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(FCColor.Brand.copy(alpha = 0.20f), FCMedia.StageMid),
                    center = Offset(size.width / 2, size.height * 0.42f),
                    radius = size.width * 0.9f
                )
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 92.dp, bottom = 76.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "AUDIO CAPTURE",
                color = FCColor.Brand2.copy(alpha = 0.8f),
                fontSize = 9.sp,
                letterSpacing = 2.sp,
                fontFamily = MonoFamily
            )
            Spacer(Modifier.height(20.dp))

            Box(modifier = Modifier.size(92.dp), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(92.dp)) {
                    drawCircle(color = FCColor.Brand.copy(alpha = 0.10f))
                    drawCircle(color = FCColor.Brand.copy(alpha = 0.38f), style = Stroke(1f))
                    drawCircle(
                        color = FCColor.Brand2.copy(alpha = 0.18f),
                        radius = size.minDimension * 0.36f,
                        style = Stroke(1f)
                    )
                }
                LineIcon(FCIcons.MicRound, FCColor.TextHi, 30.dp)
            }

            Spacer(Modifier.height(22.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .padding(horizontal = 26.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(44) { i ->
                    val base = abs(sin((i + 1) * 0.7)).toFloat() * 0.55f + 0.15f
                    val jitter = if (recording) abs(sin((phase + i) * 0.9)).toFloat() * 0.42f else 0.04f
                    val hRatio = (base + jitter).coerceAtMost(1f)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height((54 * hRatio).dp)
                            .clip(CircleShape)
                            .background(FCColor.Brand2.copy(alpha = if (recording) 0.85f else 0.28f))
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Text(
                "AAC 编码 · 单声道 · 沙盒直存",
                color = FCColor.TextLo,
                fontSize = 9.sp,
                fontFamily = MonoFamily
            )

            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(12) { i ->
                    val lit = recording && i <= (phase * 3) % 12
                    Box(
                        modifier = Modifier
                            .width(14.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(
                                if (lit) {
                                    when {
                                        i > 9 -> FCColor.Danger
                                        i > 7 -> FCColor.Warn
                                        else -> FCColor.Ok
                                    }
                                } else FCColor.TrackEmpty
                            )
                    )
                }
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val bracket = Color.White.copy(alpha = 0.16f)
            val len = 14.dp.toPx()
            val inset = 12.dp.toPx()
            val sw = 1.5.dp.toPx()
            listOf(
                Triple(Offset(inset, inset), 1f, 1f),
                Triple(Offset(size.width - inset, inset), -1f, 1f),
                Triple(Offset(inset, size.height - inset), 1f, -1f),
                Triple(Offset(size.width - inset, size.height - inset), -1f, -1f)
            ).forEach { (p, sx, sy) ->
                drawLine(bracket, p, Offset(p.x + len * sx, p.y), sw, StrokeCap.Round)
                drawLine(bracket, p, Offset(p.x, p.y + len * sy), sw, StrokeCap.Round)
            }
        }
    }
}
