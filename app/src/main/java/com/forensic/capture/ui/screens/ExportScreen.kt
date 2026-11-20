package com.forensic.capture.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forensic.camera.data.AnchorStatus
import com.forensic.camera.data.EvidenceStore
import com.forensic.camera.data.GalleryExporter
import com.forensic.capture.state.AppState
import com.forensic.capture.state.ExportOutcome
import com.forensic.capture.state.Screen
import com.forensic.capture.ui.components.ButtonKind
import com.forensic.capture.ui.components.FCBar
import com.forensic.capture.ui.components.FCButton
import com.forensic.capture.ui.components.FCChip
import com.forensic.capture.ui.components.FCKeyValue
import com.forensic.capture.ui.components.FCLabel
import com.forensic.capture.ui.components.FCIcons
import com.forensic.capture.ui.components.FCSheet
import com.forensic.capture.ui.components.FCSwitch
import com.forensic.capture.ui.components.IconSquareButton
import com.forensic.capture.ui.components.LineIcon
import com.forensic.capture.ui.components.SheetGrip
import com.forensic.capture.ui.theme.FCColor
import com.forensic.capture.ui.theme.FCRadius
import com.forensic.capture.ui.theme.FCSpace
import com.forensic.capture.ui.theme.FCType
import com.forensic.capture.ui.theme.MonoFamily

@Composable
fun ExportScreen(app: AppState) {
    val ctx = LocalContext.current
    val items = app.evidences.sortedByDescending { it.createdAt }
    val selectedMb = items.filter { app.selectedIds.contains(it.id) }
        .sumOf { EvidenceStore.bytesOf(ctx, it) } / 1048576.0

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
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
                    Text("证据导出", color = FCColor.TextDim, fontSize = FCType.label)
                    Text("相册导出", color = FCColor.TextHi, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    if (app.selectedIds.size == items.size && items.isNotEmpty()) "取消全选" else "全选",
                    color = FCColor.Brand2,
                    fontSize = FCType.label,
                    modifier = Modifier.clickable {
                        if (app.selectedIds.size == items.size && items.isNotEmpty()) {
                            app.selectedIds.clear()
                        } else {
                            app.selectedIds.clear()
                            items.forEach { app.selectedIds.add(it.id) }
                        }
                        app.exportOutcome = null
                    }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FCRadius.sm))
                    .background(FCColor.Surface1)
                    .border(1.dp, FCColor.LineSoft, RoundedCornerShape(FCRadius.sm))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LineIcon(FCIcons.Folder, FCColor.TextLo, 13.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "DCIM/${GalleryExporter.ALBUM_DIR}",
                    color = FCColor.TextMid,
                    fontSize = 9.sp,
                    fontFamily = MonoFamily,
                    modifier = Modifier.weight(1f)
                )
                FCChip("系统相册", fontSize = 8)
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FCRadius.sm))
                    .background(FCColor.WarnSoft)
                    .border(1.dp, FCColor.Warn.copy(alpha = 0.22f), RoundedCornerShape(FCRadius.sm))
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                LineIcon(FCIcons.Warn, FCColor.Warn, 13.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "导出到相册的副本文件名带证据 UUID，便于三方核验比对；沙盒原始文件不受影响。",
                    color = FCColor.Warn,
                    fontSize = 8.5.sp,
                    lineHeight = 13.sp
                )
            }

            Spacer(Modifier.height(14.dp))
            FCLabel("本机证据（${items.size} 条）", Modifier.padding(bottom = 8.dp))

            if (items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FCRadius.sm))
                        .background(FCColor.Surface1)
                        .border(1.dp, FCColor.LineSoft, RoundedCornerShape(FCRadius.sm))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("台账为空，请先采集证据", color = FCColor.TextLo, fontSize = FCType.bodySm)
                }
            }

            items.forEach { ev ->
                val anchored = ev.status == AnchorStatus.ANCHORED
                ExportFileRow(
                    name = ev.alias.ifEmpty { ev.name },
                    meta = "${"%.1f".format(EvidenceStore.bytesOf(ctx, ev) / 1048576.0)} MB · " +
                            AppState.formatDuration((ev.durationMs / 1000).toInt()) +
                            if (ev.galleryUri.isNotEmpty()) " · 已导出过" else "",
                    chip = if (anchored) "已锚定" else "待锚定",
                    chipColor = if (anchored) FCColor.Ok else FCColor.Warn,
                    selected = app.selectedIds.contains(ev.id)
                ) { app.toggleExportSelection(ev.id) }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "导出使用 MediaStore 写入系统相册（UUID 稳定命名，重复导出覆盖同一副本）。",
                color = FCColor.TextDim,
                fontSize = 8.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(FCColor.BgApp.copy(alpha = 0.96f))
                .padding(horizontal = FCSpace.screen)
                .padding(top = 12.dp, bottom = 18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                Text(
                    "已选 ${app.selectedIds.size} 项",
                    color = FCColor.TextHi,
                    fontSize = 11.sp,
                    fontFamily = MonoFamily
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${"%.1f".format(selectedMb)} MB",
                    color = FCColor.TextLo,
                    fontSize = 10.sp,
                    fontFamily = MonoFamily
                )
            }
            FCButton(
                "导出并校验（${app.selectedIds.size}）",
                onClick = { app.openExportSheet() },
                enabled = app.selectedIds.isNotEmpty()
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        FCSheet(
            visible = app.exportSheet,
            onDismiss = {
                if (!app.exporting) {
                    app.exportSheet = false
                    app.exportOutcome = null
                }
            }
        ) {
            SheetGrip()
            Text("导出与校验", color = FCColor.TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(
                "${app.selectedIds.size} 项 · ${"%.1f".format(selectedMb)} MB · 写入 DCIM/${GalleryExporter.ALBUM_DIR}",
                color = FCColor.TextDim,
                fontSize = 9.sp,
                fontFamily = MonoFamily
            )
            Spacer(Modifier.height(14.dp))

            OptionRow("原始文件（只读校验）", "MP4 / M4A，保持原始字节")
            Spacer(Modifier.height(8.dp))
            OptionRow("导出后复核", "沙盒 / 相册副本 / 链上三方比对")
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FCRadius.sm))
                    .background(FCColor.Surface1)
                    .border(1.dp, FCColor.LineSoft, RoundedCornerShape(FCRadius.sm))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("导出后复核", color = FCColor.TextMid, fontSize = 10.sp)
                    Text("逐文件复算并与台账摘要、链上凭证比对", color = FCColor.TextDim, fontSize = 8.sp, modifier = Modifier.padding(top = 1.dp))
                }
                FCSwitch(checked = app.postCheck, onChange = { app.postCheck = it })
            }

            Spacer(Modifier.height(14.dp))

            val outcome = app.exportOutcome
            if (outcome == null) {
                if (app.exporting) {
                    ExportingBar()
                    Spacer(Modifier.height(14.dp))
                }
                FCButton(
                    "开始导出",
                    onClick = { app.exportSelected() },
                    enabled = !app.exporting && app.selectedIds.isNotEmpty()
                )
            } else {
                ExportResultCard(outcome) {
                    app.exportSheet = false
                    app.exportOutcome = null
                }
            }
        }
    }
}

@Composable
private fun ExportingBar() {
    val pulse = rememberInfiniteTransition(label = "ex")
    val p by pulse.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "ep"
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("导出中：写入相册并复核…", color = FCColor.TextMid, fontSize = 10.sp, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(9.dp))
    FCBar(progress = (p * 100).toInt())
}

@Composable
private fun ExportFileRow(
    name: String,
    meta: String,
    chip: String,
    chipColor: Color,
    selected: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FCRadius.sm))
            .background(FCColor.Surface1)
            .border(1.dp, if (selected) FCColor.Brand.copy(alpha = 0.26f) else FCColor.LineSoft, RoundedCornerShape(FCRadius.sm))
            .clickable(onClick = onToggle)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (selected) FCColor.Brand else FCColor.InputBg)
                .border(1.dp, if (selected) FCColor.Brand else FCColor.LineStrong, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (selected) LineIcon(FCIcons.CheckLine, Color.White, 11.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                color = if (selected) FCColor.TextHi else FCColor.TextMid,
                fontSize = 10.sp,
                fontFamily = MonoFamily,
                maxLines = 1
            )
            Text(meta, color = FCColor.TextDim, fontSize = 8.sp, modifier = Modifier.padding(top = 2.dp))
        }
        FCChip(
            chip,
            color = chipColor,
            background = chipColor.copy(alpha = 0.10f),
            borderColor = chipColor.copy(alpha = 0.22f),
            fontSize = 8
        )
    }
}

@Composable
private fun OptionRow(title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FCRadius.sm))
            .background(FCColor.InputBg)
            .border(1.dp, FCColor.LineSoft, RoundedCornerShape(FCRadius.sm))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(FCColor.Brand.copy(alpha = 0.18f))
                .border(1.dp, FCColor.Brand, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(FCColor.Brand)
            )
        }
        Spacer(Modifier.width(9.dp))
        Column {
            Text(title, color = FCColor.TextMid, fontSize = 10.sp)
            Text(subtitle, color = FCColor.TextDim, fontSize = 8.sp, modifier = Modifier.padding(top = 1.dp))
        }
    }
}

@Composable
private fun ExportResultCard(outcome: ExportOutcome, onDone: () -> Unit) {
    val allOk = outcome.exported == outcome.selected && outcome.notes.isEmpty()
    val color = if (allOk) FCColor.Ok else FCColor.Warn
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FCRadius.md))
            .background(if (allOk) FCColor.OkSoft else FCColor.WarnSoft)
            .border(1.dp, color.copy(alpha = 0.28f), RoundedCornerShape(FCRadius.md))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                LineIcon(if (allOk) FCIcons.CheckLine else FCIcons.Warn, color, 15.dp)
            }
            Spacer(Modifier.width(9.dp))
            Column {
                Text(
                    "导出完成 · ${outcome.consistent}/${outcome.exported} 复核一致",
                    color = color,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "已导出 ${outcome.exported}/${outcome.selected} 项，共 ${"%.1f".format(outcome.totalMb)} MB。",
                    color = FCColor.TextLo,
                    fontSize = 8.5.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        FCKeyValue("导出位置", "DCIM/${GalleryExporter.ALBUM_DIR}", mono = true)
        outcome.notes.forEach { note ->
            Spacer(Modifier.height(4.dp))
            Text(note, color = FCColor.Warn, fontSize = 8.5.sp)
        }
        Spacer(Modifier.height(12.dp))
        FCButton("完成", kind = ButtonKind.Ok, onClick = onDone)
    }
}
