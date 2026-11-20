package com.forensic.capture.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.Build
import android.os.StatFs
import com.forensic.camera.data.AnchorStatus
import com.forensic.camera.data.Evidence
import com.forensic.camera.data.EvidenceStore
import com.forensic.capture.state.AppState
import com.forensic.capture.state.CaptureMode
import com.forensic.capture.state.Screen
import com.forensic.capture.ui.components.FCChip
import com.forensic.capture.ui.components.FCIcons
import com.forensic.capture.ui.components.FCLabel
import com.forensic.capture.ui.components.IconSpec
import com.forensic.capture.ui.components.LineIcon
import com.forensic.capture.ui.components.SectionHead
import com.forensic.capture.ui.theme.FCColor
import com.forensic.capture.ui.theme.FCRadius
import com.forensic.capture.ui.theme.FCSpace
import com.forensic.capture.ui.theme.FCType
import com.forensic.capture.ui.theme.MonoFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(app: AppState) {
    val ctx = LocalContext.current
    val dateText = SimpleDateFormat("yyyy年M月d日 · EEEE", Locale.CHINA).format(Date())
    val freeGb = runCatching { StatFs(ctx.filesDir.absolutePath).availableBytes / 1073741824.0 }.getOrDefault(0.0)
    val model = Build.MODEL ?: "本机"
    val recent = app.evidences.sortedByDescending { it.createdAt }.take(2)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = FCSpace.screen)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(dateText, color = FCColor.TextDim, fontSize = FCType.label)
                Text(
                    "取证摄像头",
                    color = FCColor.TextHi,
                    fontSize = FCType.title,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.4).sp
                )
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(FCColor.BrandSoft)
                    .border(1.dp, FCColor.Brand.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                LineIcon(FCIcons.ShieldCheck, FCColor.Brand2, 20.dp)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 16.dp)) {
            if (app.configured) {
                FCChip(
                    "至信链已配置",
                    color = FCColor.Ok,
                    background = FCColor.OkSoft,
                    borderColor = FCColor.Ok.copy(alpha = 0.24f),
                    dotColor = FCColor.Ok
                )
            } else {
                FCChip(
                    "至信链未配置",
                    color = FCColor.Warn,
                    background = FCColor.WarnSoft,
                    borderColor = FCColor.Warn.copy(alpha = 0.24f),
                    dotColor = FCColor.Warn
                )
            }
            FCChip("存储 ${"%.1f".format(freeGb)} GB 可用")
            FCChip(model)
        }

        HeroCard(app)

        SectionHead("快捷开始", "取证记录 ›", onAction = {
            app.resetRecording()
            app.go(Screen.Record)
        })

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(FCRadius.md))
                .background(FCColor.Surface1)
                .border(1.dp, FCColor.Line, RoundedCornerShape(FCRadius.md))
                .clickable {
                    app.mode = CaptureMode.Video
                    app.go(Screen.Record)
                }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(FCColor.Brand, FCColor.Brand2))),
                contentAlignment = Alignment.Center
            ) {
                LineIcon(FCIcons.Video, FCColor.TextHi, 18.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("开始视频取证", color = FCColor.TextHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("CameraX 录制 · 同期声 · 结束即算 SHA-256", color = FCColor.TextLo, fontSize = 10.sp, fontFamily = MonoFamily)
            }
            LineIcon(FCIcons.ChevronRight, FCColor.TextDim, 16.dp)
        }

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickCard(
                icon = FCIcons.MicRound,
                title = "录音取证",
                meta = "AAC · m4a",
                modifier = Modifier.weight(1f)
            ) {
                app.mode = CaptureMode.Audio
                app.go(Screen.Record)
            }
            QuickCard(
                icon = FCIcons.ShieldCheck,
                title = "证据校验",
                meta = "SHA-256",
                modifier = Modifier.weight(1f)
            ) { app.go(Screen.Verify) }
        }

        SectionHead("最近证据", "查看台账 ›", onAction = { app.go(Screen.Ledger) })

        if (recent.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FCRadius.md))
                    .background(FCColor.Surface1)
                    .border(1.dp, FCColor.Line, RoundedCornerShape(FCRadius.md))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("暂无证据，点击上方开始取证", color = FCColor.TextDim, fontSize = FCType.bodySm)
            }
        } else {
            recent.forEachIndexed { i, ev ->
                if (i > 0) Spacer(Modifier.height(10.dp))
                EvidenceRow(ev, fileMb = EvidenceStore.bytesOf(ctx, ev) / 1048576.0) {
                    app.detailId = ev.id
                    app.go(Screen.Detail)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun HeroCard(app: AppState) {
    val ctx = LocalContext.current
    val total = app.evidences.size
    val anchored = app.anchoredCount
    val pending = app.pendingCount
    val percent = if (total == 0) 0 else anchored * 100 / total
    val localMb = app.evidences.sumOf { EvidenceStore.bytesOf(ctx, it) } / 1048576.0
    val lastAnchored = app.evidences.filter { it.status == AnchorStatus.ANCHORED && it.txTime > 0 }
        .maxByOrNull { it.txTime }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FCRadius.xl))
            .background(
                Brush.linearGradient(
                    listOf(
                        FCColor.Brand.copy(alpha = 0.16f),
                        FCColor.Brand.copy(alpha = 0.04f),
                        FCColor.Surface1
                    )
                )
            )
            .border(1.dp, FCColor.Line, RoundedCornerShape(FCRadius.xl))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                FCLabel("取证概览", Modifier.padding(bottom = 8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "$total",
                        color = FCColor.TextHi,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-1.4).sp,
                        lineHeight = 36.sp
                    )
                    Spacer(Modifier.width(7.dp))
                    Text("段证据已采集", color = FCColor.TextDim, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
                }
                Spacer(Modifier.height(14.dp))
                Row {
                    StatItem("$anchored", "已上链", FCColor.Ok, Modifier.weight(1f))
                    StatItem("$pending", "待处理", FCColor.Warn, Modifier.weight(1f))
                    StatItem(
                        if (localMb >= 1024) "%.1f".format(localMb / 1024.0) else "%.1f".format(localMb),
                        "本地体积", FCColor.Brand2, Modifier.weight(1f),
                        unit = if (localMb >= 1024) "GB" else "MB"
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Ring(percent = percent)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (lastAnchored != null) {
                val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastAnchored.txTime))
                "最近一次锚定 $t · 区块 #${lastAnchored.blockHeight} · ${if (lastAnchored.attLevel == "H") "硬件证明" else "软件证明"}"
            } else {
                if (app.configured) "尚无链上凭证，录制后自动排队直连上链" else "未配置至信链凭据，请到「我的」页配置"
            },
            color = FCColor.TextDim,
            fontSize = 9.sp,
            fontFamily = MonoFamily
        )
    }
}

@Composable
private fun StatItem(value: String, label: String, color: Color, modifier: Modifier = Modifier, unit: String? = null) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = MonoFamily)
            if (unit != null) {
                Text(unit, color = color.copy(alpha = 0.7f), fontSize = 9.sp, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
        Text(label, color = FCColor.TextDim, fontSize = 9.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun Ring(percent: Int) {
    Box(modifier = Modifier.size(74.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2
            drawArc(
                color = FCColor.TextHi.copy(alpha = 0.08f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke)
            )
            drawArc(
                brush = Brush.sweepGradient(listOf(FCColor.Brand2, FCColor.Brand, FCColor.Brand2)),
                startAngle = -90f,
                sweepAngle = 360f * percent / 100f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$percent%", color = FCColor.TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = MonoFamily)
            Text("上链率", color = FCColor.TextDim, fontSize = 8.sp)
        }
    }
}

@Composable
private fun QuickCard(icon: IconSpec, title: String, meta: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .height(58.dp)
            .clip(RoundedCornerShape(FCRadius.md))
            .background(FCColor.Surface1)
            .border(1.dp, FCColor.Line, RoundedCornerShape(FCRadius.md))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LineIcon(icon, FCColor.Brand2, 16.dp)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, color = FCColor.TextMid, fontSize = 12.sp)
            Text(meta, color = FCColor.TextDim, fontSize = 8.sp, fontFamily = MonoFamily)
        }
    }
}

@Composable
private fun EvidenceRow(ev: Evidence, fileMb: Double, onClick: () -> Unit) {
    val audio = ev.type.equals("audio", ignoreCase = true)
    val dur = AppState.formatDuration(((ev.durationMs / 1000).toInt()))
    val timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ev.createdAt))
    val (tagText, tagColor, tagBg) = when (ev.status) {
        AnchorStatus.ANCHORED -> Triple("链上一致", FCColor.Ok, FCColor.OkSoft)
        AnchorStatus.FAILED -> Triple("上链失败", FCColor.Danger, FCColor.DangerSoft)
        AnchorStatus.PENDING -> Triple("待上链", FCColor.Warn, FCColor.WarnSoft)
    }
    val secondTag = if (ev.galleryUri.isNotEmpty()) "已导出相册" else "本机沙盒"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FCRadius.md))
            .background(FCColor.Surface1)
            .border(1.dp, FCColor.Line, RoundedCornerShape(FCRadius.md))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(FCRadius.sm))
                .background(Brush.linearGradient(listOf(FCColor.BgApp2, FCColor.BgApp)))
                .border(1.dp, FCColor.LineSoft, RoundedCornerShape(FCRadius.sm)),
            contentAlignment = Alignment.Center
        ) {
            if (audio) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(0.3f, 0.6f, 0.9f, 0.45f, 0.75f, 0.35f).forEach { h ->
                        Box(
                            Modifier
                                .width(2.dp)
                                .height((28 * h).dp)
                                .clip(CircleShape)
                                .background(FCColor.Brand2.copy(alpha = 0.8f))
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(FCColor.BgPage.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    LineIcon(FCIcons.Play, FCColor.TextHi, 9.dp)
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(FCColor.BgPage.copy(alpha = 0.7f))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(dur, color = FCColor.TextMid, fontSize = 8.sp, fontFamily = MonoFamily)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                ev.alias.ifEmpty { ev.name },
                color = FCColor.TextHi,
                fontSize = 11.sp,
                fontFamily = MonoFamily,
                maxLines = 1
            )
            Text("$timeText 采集 · ${"%.1f".format(fileMb)} MB", color = FCColor.TextLo, fontSize = 9.sp, modifier = Modifier.padding(top = 3.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
                FCChip(tagText, color = tagColor, background = tagBg, borderColor = tagColor.copy(alpha = 0.24f), fontSize = 9)
                FCChip(secondTag, fontSize = 9)
            }
        }
        LineIcon(FCIcons.ChevronRight, FCColor.TextDim, 14.dp)
    }
}
