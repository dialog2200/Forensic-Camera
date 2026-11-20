package com.forensic.capture.ui.screens

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forensic.camera.data.AnchorStatus
import com.forensic.camera.data.EvidenceStore
import com.forensic.capture.state.AppState
import com.forensic.capture.state.Screen
import com.forensic.capture.ui.components.ButtonKind
import com.forensic.capture.ui.components.FCButton
import com.forensic.capture.ui.components.FCChip
import com.forensic.capture.ui.components.FCIcons
import com.forensic.capture.ui.components.FCKeyValue
import com.forensic.capture.ui.components.FCLabel
import com.forensic.capture.ui.components.GlassCard
import com.forensic.capture.ui.components.HashBlock
import com.forensic.capture.ui.components.IconSquareButton
import com.forensic.capture.ui.components.LineIcon
import com.forensic.capture.ui.components.ToastKind
import com.forensic.capture.ui.theme.FCColor
import com.forensic.capture.ui.theme.FCRadius
import com.forensic.capture.ui.theme.FCSpace
import com.forensic.capture.ui.theme.FCType
import com.forensic.capture.ui.theme.MonoFamily

@Composable
fun DetailScreen(app: AppState) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val ev = app.findEvidence(app.detailId) ?: app.evidences.firstOrNull()

    if (ev == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = FCSpace.screen),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("台账为空，请先采集证据", color = FCColor.TextLo, fontSize = FCType.bodySm)
            Spacer(Modifier.height(16.dp))
            FCButton("返回首页", onClick = { app.go(Screen.Home) }, modifier = Modifier.fillMaxWidth())
        }
        return
    }

    val sizeMb = EvidenceStore.bytesOf(ctx, ev) / 1048576.0
    val durSec = (ev.durationMs / 1000).toInt()
    val (statusText, statusColor, statusBg) = when (ev.status) {
        AnchorStatus.ANCHORED -> Triple("已上链", FCColor.Ok, FCColor.OkSoft)
        AnchorStatus.FAILED -> Triple("上链失败", FCColor.Danger, FCColor.DangerSoft)
        AnchorStatus.PENDING -> Triple("待上链", FCColor.Warn, FCColor.WarnSoft)
    }
    val attName = when (ev.attLevel) {
        "H" -> "硬件级（KeyMint Attestation）"
        "S" -> "软件级（密钥库签名）"
        else -> "无（本机计算）"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = FCSpace.screen)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconSquareButton(FCIcons.Back, { app.go(Screen.Ledger) })
            Spacer(Modifier.width(FCSpace.x3))
            Column(modifier = Modifier.weight(1f)) {
                Text("证据台账", color = FCColor.TextDim, fontSize = FCType.label)
                Text("证据详情", color = FCColor.TextHi, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            FCChip(statusText, color = statusColor, background = statusBg, borderColor = statusColor.copy(alpha = 0.24f), dotColor = statusColor)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(FCRadius.md))
                .background(FCColor.Surface1)
                .border(1.dp, FCColor.Line, RoundedCornerShape(FCRadius.md))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(FCRadius.sm))
                    .background(Brush.linearGradient(listOf(FCColor.BgApp2, FCColor.BgApp)))
                    .border(1.dp, FCColor.LineSoft, RoundedCornerShape(FCRadius.sm)),
                contentAlignment = Alignment.Center
            ) {
                LineIcon(
                    if (ev.type == "audio") FCIcons.MicRound else FCIcons.VideoLine,
                    FCColor.Brand2,
                    20.dp
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(ev.alias.ifEmpty { ev.name }, color = FCColor.TextHi, fontSize = 11.sp, fontFamily = MonoFamily)
                Text(
                    "${"%.1f".format(sizeMb)} MB · ${AppState.formatDuration(durSec)}",
                    color = FCColor.TextLo,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
                    FCChip(if (ev.type == "audio") "录音" else "视频", fontSize = 9)
                    FCChip("沙盒原始文件", fontSize = 9)
                    if (ev.galleryUri.isNotEmpty()) FCChip("已导出相册", fontSize = 9)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        GlassCard(padding = FCSpace.x4) {
            FCLabel("文件信息", Modifier.padding(bottom = 6.dp))
            FCKeyValue("采集时间", AppState.formatTime(ev.createdAt), mono = true)
            FCKeyValue("时长", AppState.formatDuration(durSec), mono = true)
            FCKeyValue("类型", if (ev.type == "audio") "M4A · AAC" else "MP4 · H.264 · 同期声", mono = true)
            FCKeyValue("设备", ev.device, mono = true, maxLines = 2)
            FCKeyValue("应用版本", ev.appVersion, mono = true)
            FCKeyValue("沙盒路径", "files/media/${ev.fileName}", mono = true)
            if (ev.notesHtml.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                FCLabel("备注", Modifier.padding(bottom = 4.dp))
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { c ->
                        android.widget.TextView(c).apply {
                            setTextColor(FCColor.TextMid.toArgb())
                            textSize = 11f
                        }
                    },
                    update = { it.text = androidx.core.text.HtmlCompat.fromHtml(ev.notesHtml, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        GlassCard(padding = FCSpace.x4) {
            HashBlock(
                hash = ev.fileSha256,
                label = "文件 SHA-256",
                chipText = "台账登记",
                fontSize = 9,
                onCopy = {
                    clipboard.setText(AnnotatedString(ev.fileSha256))
                    app.showToast("SHA-256 已复制", ToastKind.Ok)
                }
            )
            Spacer(Modifier.height(14.dp))
            HashBlock(
                hash = ev.anchorHash,
                label = "复合锚定摘要（上链值）",
                chipText = "V1 规范",
                fontSize = 9,
                onCopy = {
                    clipboard.setText(AnnotatedString(ev.anchorHash))
                    app.showToast("锚定摘要已复制", ToastKind.Ok)
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        GlassCard(padding = FCSpace.x4) {
            FCLabel("出处证明", Modifier.padding(bottom = 6.dp))
            FCKeyValue("证明级别", attName, mono = true)
            FCKeyValue("att SHA-256", if (ev.attSha256.isEmpty()) "—" else ev.attSha256, mono = true)
            FCKeyValue("采集时版本", "v${ev.attVc}", mono = true)
            FCKeyValue("当前 APK", ev.apkSha256, mono = true)
        }

        Spacer(Modifier.height(12.dp))

        GlassCard(padding = FCSpace.x4) {
            FCLabel("至信链锚定凭证", Modifier.padding(bottom = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(FCRadius.xs))
                        .background(FCColor.BrandSoft),
                    contentAlignment = Alignment.Center
                ) {
                    LineIcon(FCIcons.Chain, FCColor.Brand2, 14.dp)
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("至信链 · 司法存证", color = FCColor.TextHi, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Text("open.zxchain.qq.com", color = FCColor.TextDim, fontSize = 8.sp, fontFamily = MonoFamily)
                }
            }
            Spacer(Modifier.height(10.dp))
            FCKeyValue("状态", statusText, mono = true, valueColor = statusColor)
            FCKeyValue("存证编号 evId", ev.evId.ifEmpty { "—" }, mono = true)
            FCKeyValue("区块高度", if (ev.blockHeight > 0) "#${ev.blockHeight}" else "—", mono = true)
            FCKeyValue("锚定时间", if (ev.txTime > 0) AppState.formatTime(ev.txTime) else "—", mono = true)
            if (ev.txId.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("交易哈希 txId", color = FCColor.TextDim, fontSize = 8.sp, letterSpacing = 1.sp)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FCRadius.xs))
                        .background(FCColor.CodeBg)
                        .border(1.dp, FCColor.LineSoft, RoundedCornerShape(FCRadius.xs))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        ev.txId,
                        color = FCColor.TextMid,
                        fontSize = 9.sp,
                        fontFamily = MonoFamily,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(FCRadius.xs))
                            .background(FCColor.Surface1)
                            .clickable {
                                clipboard.setText(AnnotatedString(ev.txId))
                                app.showToast("txId 已复制", ToastKind.Ok)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        LineIcon(FCIcons.Copy, FCColor.TextDim, 11.dp)
                    }
                }
            }
            if (ev.status == AnchorStatus.FAILED && ev.anchorError.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("上链错误：${ev.anchorError}", color = FCColor.Danger, fontSize = 9.sp)
            }
        }

        Spacer(Modifier.height(14.dp))
        FCLabel("存证时间线", Modifier.padding(bottom = 10.dp))

        TimelineItem(
            time = AppState.formatClock(ev.createdAt),
            title = "证据采集",
            desc = if (ev.type == "audio") "录音开始 · m4a 落盘沙盒" else "录制开始 · mp4 落盘沙盒",
            active = true,
            last = false
        )
        TimelineItem(
            time = AppState.formatClock(ev.createdAt + ev.durationMs),
            title = "摘要与出处证明",
            desc = "SHA-256 + V1 复合锚定摘要 · att 级别 ${ev.attLevel}",
            active = true,
            last = false
        )
        TimelineItem(
            time = if (ev.txTime > 0) AppState.formatClock(ev.txTime) else "—",
            title = "上链锚定",
            desc = when (ev.status) {
                AnchorStatus.ANCHORED -> "区块 #${ev.blockHeight} · evId ${ev.evId.take(12)}…"
                AnchorStatus.FAILED -> "失败：${ev.anchorError.take(40)}"
                AnchorStatus.PENDING -> "排队中 / 未配置凭据"
            },
            active = ev.status == AnchorStatus.ANCHORED,
            last = ev.lastVerifyAt == 0L
        )
        if (ev.lastVerifyAt > 0) {
            TimelineItem(
                time = AppState.formatClock(ev.lastVerifyAt),
                title = "最近核验",
                desc = ev.lastVerify,
                active = true,
                last = true
            )
        }

        Spacer(Modifier.height(12.dp))

        FCButton("校验本文件", onClick = {
            app.pickedId = ev.id
            app.verifyResult = null
            app.go(Screen.Verify)
        })

        Spacer(Modifier.height(8.dp))

        if (ev.fmt == com.forensic.camera.enc.EncFormat.E1) {
            FCButton("预览（临时解密件）", kind = ButtonKind.Ghost, onClick = {
                app.previewEncrypted(ev)
            })
            Spacer(Modifier.height(8.dp))
        }

        FCButton("导出到相册", kind = ButtonKind.Ghost, onClick = {
            app.selectedIds.clear()
            app.selectedIds.add(ev.id)
            app.go(Screen.Export)
        })

        Spacer(Modifier.height(8.dp))

        FCButton("分享证据 JSON", kind = ButtonKind.Ghost, onClick = {
            app.shareEvidenceJson(ev)
        })

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun TimelineItem(time: String, title: String, desc: String, active: Boolean, last: Boolean) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (active) FCColor.Brand else FCColor.TextDim)
            )
            if (!last) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(38.dp)
                        .background(FCColor.LineSoft)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = FCColor.TextMid, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(6.dp))
                Text(time, color = FCColor.TextDim, fontSize = 8.5.sp, fontFamily = MonoFamily)
            }
            Text(desc, color = FCColor.TextDim, fontSize = 9.sp, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(if (last) 0.dp else 10.dp))
        }
    }
}
