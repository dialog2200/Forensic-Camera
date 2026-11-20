package com.forensic.capture.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forensic.camera.anchor.Verifier
import com.forensic.camera.data.AnchorStatus
import com.forensic.camera.data.Evidence
import com.forensic.camera.data.EvidenceStore
import com.forensic.capture.state.AppState
import com.forensic.capture.state.Screen
import com.forensic.capture.ui.components.ButtonKind
import com.forensic.capture.ui.components.FCBar
import com.forensic.capture.ui.components.FCButton
import com.forensic.capture.ui.components.FCIcons
import com.forensic.capture.ui.components.FCKeyValue
import com.forensic.capture.ui.components.FCSheet
import com.forensic.capture.ui.components.GlassCard
import com.forensic.capture.ui.components.HashBlock
import com.forensic.capture.ui.components.IconSquareButton
import com.forensic.capture.ui.components.LineIcon
import com.forensic.capture.ui.components.SheetGrip
import com.forensic.capture.ui.components.ToastKind
import com.forensic.capture.ui.theme.FCColor
import com.forensic.capture.ui.theme.FCRadius
import com.forensic.capture.ui.theme.FCSpace
import com.forensic.capture.ui.theme.FCType
import com.forensic.capture.ui.theme.MonoFamily

@Composable
fun VerifyScreen(app: AppState) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var pickerOpen by remember { mutableStateOf(false) }
    val picked = app.findEvidence(app.pickedId) ?: app.evidences.firstOrNull()

    Box(modifier = Modifier.fillMaxSize()) {
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
                IconSquareButton(FCIcons.Back, { app.go(Screen.Home) })
                Spacer(Modifier.width(FCSpace.x3))
                Column(modifier = Modifier.weight(1f)) {
                    Text("完整性核验", color = FCColor.TextDim, fontSize = FCType.label)
                    Text("证据校验", color = FCColor.TextHi, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
                IconSquareButton(FCIcons.Retry, { app.verifyAll() }, tint = if (app.verifying) FCColor.TextDim else FCColor.TextMid)
            }

            if (picked == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FCRadius.md))
                        .background(FCColor.Surface1)
                        .border(1.dp, FCColor.Line, RoundedCornerShape(FCRadius.md))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("台账为空，请先采集证据", color = FCColor.TextLo, fontSize = FCType.bodySm)
                }
                Spacer(Modifier.height(12.dp))
                FCButton("去录制", onClick = { app.go(Screen.Record) })
            } else {
                // 文件选择
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FCRadius.md))
                        .background(FCColor.Surface1)
                        .border(1.dp, FCColor.Line, RoundedCornerShape(FCRadius.md))
                        .clickable { pickerOpen = true }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(FCRadius.xs))
                            .background(FCColor.InputBg)
                            .border(1.dp, FCColor.LineSoft, RoundedCornerShape(FCRadius.xs)),
                        contentAlignment = Alignment.Center
                    ) {
                        LineIcon(FCIcons.Folder, FCColor.TextMid, 15.dp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(picked.alias.ifEmpty { picked.name }, color = FCColor.TextHi, fontSize = 10.5.sp, fontFamily = MonoFamily, maxLines = 1)
                        Text(
                            "${"%.1f".format(EvidenceStore.bytesOf(ctx, picked) / 1048576.0)} MB · ${if (picked.status == AnchorStatus.ANCHORED) "台账内 · 已锚定" else "台账内 · 待锚定"}",
                            color = FCColor.TextLo,
                            fontSize = 8.5.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Text("更换", color = FCColor.Brand2, fontSize = 10.sp)
                }

                Spacer(Modifier.height(12.dp))

                GlassCard(padding = FCSpace.x4) {
                    HashBlock(
                        hash = picked.fileSha256,
                        label = "台账登记文件摘要",
                        chipText = "原始",
                        fontSize = 9,
                        onCopy = {
                            clipboard.setText(AnnotatedString(picked.fileSha256))
                            app.showToast("摘要已复制", ToastKind.Info)
                        }
                    )
                    Spacer(Modifier.height(14.dp))
                    HashBlock(
                        hash = picked.anchorHash,
                        label = "复合锚定摘要（上链值）",
                        chipText = "V1 规范",
                        fontSize = 9,
                        onCopy = {
                            clipboard.setText(AnnotatedString(picked.anchorHash))
                            app.showToast("锚定摘要已复制", ToastKind.Info)
                        }
                    )
                }

                Spacer(Modifier.height(12.dp))

                if (app.verifying) {
                    VerifyingCard()
                } else {
                    FCButton(
                        if (app.verifyResult == null) "三方核验（沙盒/相册/链上）" else "重新核验",
                        onClick = { app.verifyOne(picked) }
                    )
                }

                app.verifyResult?.let { r ->
                    if (app.pickedId == picked.id || app.pickedId == null) {
                        Spacer(Modifier.height(14.dp))
                        ResultCard(r, picked)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "沙盒与相册副本在本机复算 SHA-256 比对；链上核验直连 open.zxchain.qq.com 查询锚定摘要，全程不上传原始文件",
                color = FCColor.TextDim,
                fontSize = 8.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 18.dp)
            )
        }

        FCSheet(visible = pickerOpen, onDismiss = { pickerOpen = false }) {
            SheetGrip()
            Text("选择需要校验的证据", color = FCColor.TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("来自本机台账 · 共 ${app.evidences.size} 条", color = FCColor.TextDim, fontSize = 9.sp)
            Spacer(Modifier.height(14.dp))

            app.evidences.forEach { ev ->
                PickerRow(
                    ev = ev,
                    selected = ev.id == (picked?.id ?: ""),
                    fileMb = EvidenceStore.bytesOf(ctx, ev) / 1048576.0
                ) {
                    app.pickedId = ev.id
                    app.verifyResult = null
                    pickerOpen = false
                }
            }

            Spacer(Modifier.height(8.dp))
            FCButton("取消", kind = ButtonKind.Ghost, onClick = { pickerOpen = false })
        }
    }
}

@Composable
private fun VerifyingCard() {
    val pulse = rememberInfiniteTransition(label = "vf")
    val p by pulse.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "vp"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FCRadius.md))
            .background(FCColor.Surface1)
            .border(1.dp, FCColor.Line, RoundedCornerShape(FCRadius.md))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(modifier = Modifier.size(12.dp)) {
                drawCircle(color = FCColor.Brand.copy(alpha = 0.25f))
                drawCircle(color = FCColor.Brand, style = Stroke(1.5.dp.toPx()))
            }
            Spacer(Modifier.width(8.dp))
            Text("复算沙盒文件、比对相册副本、查询链上凭证…", color = FCColor.TextMid, fontSize = 10.sp, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        FCBar(progress = (p * 100).toInt())
    }
}

@Composable
private fun PickerRow(ev: Evidence, selected: Boolean, fileMb: Double, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(FCRadius.sm))
            .background(if (selected) FCColor.BrandSoft else FCColor.Surface1)
            .border(1.dp, if (selected) FCColor.Brand.copy(alpha = 0.32f) else FCColor.LineSoft, RoundedCornerShape(FCRadius.sm))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LineIcon(
            if (ev.type == "audio") FCIcons.Mic else FCIcons.VideoLine,
            if (selected) FCColor.Brand2 else FCColor.TextLo,
            15.dp
        )
        Spacer(Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                ev.alias.ifEmpty { ev.name },
                color = if (selected) FCColor.TextHi else FCColor.TextMid,
                fontSize = 10.sp,
                fontFamily = MonoFamily,
                maxLines = 1
            )
            Text(
                "${"%.1f".format(fileMb)} MB · ${if (ev.status == AnchorStatus.ANCHORED) "已锚定" else "待锚定"}",
                color = FCColor.TextDim,
                fontSize = 8.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (selected) {
            LineIcon(FCIcons.CheckLine, FCColor.Brand2, 13.dp)
        }
    }
}

private fun stateColor(label: String): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> =
    when {
        label.contains("一致") && !label.contains("不") -> FCColor.Ok to FCColor.OkSoft
        label.contains("已存证") -> FCColor.Ok to FCColor.OkSoft
        label.contains("缺失") || label.contains("不一致") || label.contains("失败") -> FCColor.Danger to FCColor.DangerSoft
        label.contains("未") -> FCColor.Warn to FCColor.WarnSoft
        else -> FCColor.TextLo to FCColor.Surface1
    }

@Composable
private fun ResultCard(r: Verifier.Result, picked: Evidence) {
    val pass = r.verdict == Verifier.Verdict.INTACT
    val color = when (r.verdict) {
        Verifier.Verdict.INTACT -> FCColor.Ok
        Verifier.Verdict.NOT_ANCHORED, Verifier.Verdict.INCOMPLETE,
        Verifier.Verdict.NO_CONFIG -> FCColor.Warn
        else -> FCColor.Danger
    }
    val soft = when (r.verdict) {
        Verifier.Verdict.INTACT -> FCColor.OkSoft
        Verifier.Verdict.NOT_ANCHORED, Verifier.Verdict.INCOMPLETE,
        Verifier.Verdict.NO_CONFIG -> FCColor.WarnSoft
        else -> FCColor.DangerSoft
    }
    val verdictText = when (r.verdict) {
        Verifier.Verdict.INTACT -> "校验通过 · 证据完整"
        Verifier.Verdict.TAMPERED -> "校验不一致 · 疑似篡改"
        Verifier.Verdict.MISSING -> "文件缺失 · 沙盒内未找到"
        Verifier.Verdict.INCOMPLETE -> "信息不完整 · 无法完整比对"
        Verifier.Verdict.NOT_ANCHORED -> "链上未存证 · 摘要未查到"
        Verifier.Verdict.NO_CONFIG -> "未配置至信链凭据"
        Verifier.Verdict.UNKNOWN -> "核验异常"
    }
    val verdictSub = when (r.verdict) {
        Verifier.Verdict.INTACT -> "沙盒、相册副本与链上凭证三方一致"
        Verifier.Verdict.TAMPERED -> "复算摘要与台账登记值不同，请勿作为证据使用"
        else -> r.detail.take(80)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FCRadius.md))
            .background(soft)
            .border(1.dp, color.copy(alpha = 0.28f), RoundedCornerShape(FCRadius.md))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                LineIcon(if (pass) FCIcons.CheckLine else FCIcons.Warn, color, 17.dp)
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(verdictText, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(verdictSub, color = FCColor.TextLo, fontSize = 8.5.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        FCKeyValue("比对文件", picked.alias.ifEmpty { picked.name }, mono = true)
        RowStateRow("沙盒原始文件", r.sandbox.label)
        RowStateRow("相册导出副本", r.gallery.label)
        RowStateRow("至信链存证", r.chain.label)
        if (r.proof.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(r.proof, color = FCColor.TextDim, fontSize = 8.5.sp, fontFamily = MonoFamily)
        }
        if (r.detail.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(r.detail, color = color.copy(alpha = 0.85f), fontSize = 8.5.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "核验时间 ${AppState.formatTime(picked.lastVerifyAt)}",
            color = FCColor.TextDim,
            fontSize = 8.sp,
            fontFamily = MonoFamily
        )
    }
}

@Composable
private fun RowStateRow(label: String, value: String) {
    val (c, bg) = stateColor(value)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(text = label, color = FCColor.TextLo, fontSize = FCType.bodySm, modifier = Modifier.width(96.dp))
        Text(
            text = value,
            color = c,
            fontSize = FCType.bodySm,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(bg)
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
        Spacer(Modifier.weight(1f))
    }
}
