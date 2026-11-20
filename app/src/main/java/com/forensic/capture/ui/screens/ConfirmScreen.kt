package com.forensic.capture.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.forensic.camera.data.EvidenceStore
import com.forensic.capture.state.AppState
import com.forensic.capture.state.Screen
import com.forensic.capture.ui.components.ButtonKind
import com.forensic.capture.ui.components.FCButton
import com.forensic.capture.ui.components.FCIcons
import com.forensic.capture.ui.components.FCKeyValue
import com.forensic.capture.ui.components.FCSheet
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
fun ConfirmScreen(app: AppState) {
    val ctx = LocalContext.current
    val draft = app.sealed
    val clipboard = LocalClipboardManager.current

    if (draft == null) {
        // 已入队或无草稿：显示完成态
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = FCSpace.screen),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(80.dp))
            Text(
                if (app.queued) "证据已入台账，正在直连至信链上链" else "暂无待确认的证据",
                color = FCColor.TextLo,
                fontSize = FCType.bodySm
            )
            Spacer(Modifier.height(16.dp))
            FCButton(
                "查看台账",
                onClick = { app.go(Screen.Ledger) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        return
    }

    val sizeMb = EvidenceStore.bytesOf(ctx, draft) / 1048576.0
    val attText = when (draft.attLevel) {
        "H" -> "硬件级出处证明（KeyMint Attestation）"
        "S" -> "软件级出处证明（密钥库签名）"
        else -> "本机签名（无密钥库证明）"
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
                .padding(top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconSquareButton(FCIcons.Back, { app.go(Screen.Record) })
            Spacer(Modifier.width(FCSpace.x3))
            Column(modifier = Modifier.weight(1f)) {
                Text("证据确认", color = FCColor.TextDim, fontSize = FCType.label)
                Text("采集完成", color = FCColor.TextHi, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                text = when {
                    app.queued -> "已入队"
                    app.settings.autoAnchor -> "自动上链"
                    else -> "待入队"
                },
                color = if (app.queued || app.settings.autoAnchor) FCColor.Brand2 else FCColor.Warn,
                fontSize = 9.sp,
                fontFamily = MonoFamily
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.size(66.dp), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(66.dp)) {
                    drawCircle(color = FCColor.Ok.copy(alpha = 0.10f))
                    drawCircle(color = FCColor.Ok.copy(alpha = 0.32f), style = Stroke(1f))
                }
                LineIcon(FCIcons.CheckLine, FCColor.Ok, 26.dp)
            }
            Spacer(Modifier.height(12.dp))
            Text("证据已封存并计算摘要", color = FCColor.TextHi, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "${draft.name} · ${"%.1f".format(sizeMb)} MB · ${AppState.formatDuration((draft.durationMs / 1000).toInt())}",
                color = FCColor.TextLo,
                fontSize = 9.sp,
                fontFamily = MonoFamily
            )
        }

        GlassCard(padding = FCSpace.x4) {
            HashBlock(
                hash = draft.fileSha256,
                label = "文件 SHA-256",
                chipText = "已计算",
                fontSize = 9,
                onCopy = {
                    clipboard.setText(AnnotatedString(draft.fileSha256))
                    app.showToast("SHA-256 已复制", ToastKind.Ok)
                }
            )
            Spacer(Modifier.height(14.dp))
            HashBlock(
                hash = draft.anchorHash,
                label = "复合锚定摘要（上链值）",
                chipText = "V1 规范",
                fontSize = 9,
                onCopy = {
                    clipboard.setText(AnnotatedString(draft.anchorHash))
                    app.showToast("锚定摘要已复制", ToastKind.Ok)
                }
            )
            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FCRadius.sm))
                    .background(FCColor.OkSoft)
                    .border(1.dp, FCColor.Ok.copy(alpha = 0.22f), RoundedCornerShape(FCRadius.sm))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LineIcon(FCIcons.ShieldCheck, FCColor.Ok, 15.dp)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(attText, color = FCColor.Ok, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "att=${draft.attSha256.take(16)}… · v${draft.attVc}",
                        color = FCColor.TextLo,
                        fontSize = 8.5.sp,
                        fontFamily = MonoFamily,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Text("本机密钥", color = FCColor.TextDim, fontSize = 8.sp)
            }
        }

        Spacer(Modifier.height(12.dp))

        GlassCard(padding = FCSpace.x4) {
            FCLabel("采集元数据", Modifier.padding(bottom = 6.dp))
            FCKeyValue("采集时间", AppState.formatTime(draft.createdAt), mono = true)
            FCKeyValue("时长", AppState.formatDuration((draft.durationMs / 1000).toInt()), mono = true)
            FCKeyValue("文件大小", "${"%.1f".format(sizeMb)} MB", mono = true)
            FCKeyValue("类型", if (draft.type == "video") "MP4 · H.264 · 同期声" else "M4A · AAC", mono = true)
            FCKeyValue("设备", draft.device, mono = true, maxLines = 2)
            FCKeyValue("应用版本", draft.appVersion, mono = true)
            FCKeyValue("APK SHA-256", draft.apkSha256, mono = true)
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(FCRadius.md))
                .background(if (app.queued) FCColor.BrandSoft else FCColor.Surface1)
                .border(1.dp, if (app.queued) FCColor.Brand.copy(alpha = 0.28f) else FCColor.Line, RoundedCornerShape(FCRadius.md))
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LineIcon(FCIcons.Chain, if (app.queued) FCColor.Brand2 else FCColor.Warn, 15.dp)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (app.queued) "已入队 · 直连至信链上链中" else "待入队",
                    color = if (app.queued) FCColor.Brand2 else FCColor.Warn,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    if (app.queued) "AnchorManager 后台排队，成功后自动写入链上凭证"
                    else "点击下方按钮写入台账并排队上链；未配置凭据时仅入台账",
                    color = FCColor.TextLo,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        FCButton(
            text = if (app.queued) "已入队 · 查看台账" else "确认入队并直连上链",
            onClick = {
                if (app.queued) app.go(Screen.Ledger) else app.enqueueDraft()
            }
        )

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                FCButton(
                    text = if (app.queued) "返回取景" else "放弃并重录",
                    kind = ButtonKind.Ghost,
                    onClick = {
                        if (app.queued) app.clearDraft() else app.discardDraft()
                        app.go(Screen.Record)
                    }
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                FCButton(
                    "查看详情",
                    kind = ButtonKind.Ghost,
                    onClick = {
                        app.detailId = draft.id
                        app.go(Screen.Detail)
                    }
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "原始文件仅存于应用沙盒私有目录；入队后复合锚定摘要经 SM2 签名直连 open.zxchain.qq.com 存证",
            color = FCColor.TextDim,
            fontSize = 8.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 18.dp)
        )
    }
}
