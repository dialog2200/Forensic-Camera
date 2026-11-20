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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.widget.TextView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import com.forensic.camera.data.AnchorStatus
import com.forensic.camera.data.Evidence
import com.forensic.camera.data.EvidenceStore
import com.forensic.camera.data.TreeNode
import com.forensic.capture.state.AppState
import com.forensic.capture.state.Screen
import com.forensic.capture.state.TreeBuilder
import com.forensic.capture.state.TreeRow
import com.forensic.capture.ui.components.ButtonKind
import com.forensic.capture.ui.components.FCButton
import com.forensic.capture.ui.components.FCChip
import com.forensic.capture.ui.components.FCIcons
import com.forensic.capture.ui.components.FCKeyValue
import com.forensic.capture.ui.components.FCSheet
import com.forensic.capture.ui.components.GlassCard
import com.forensic.capture.ui.components.IconSquareButton
import com.forensic.capture.ui.components.LineIcon
import com.forensic.capture.ui.components.SheetGrip
import com.forensic.capture.ui.components.ToastKind
import com.forensic.capture.ui.theme.FCColor
import com.forensic.capture.ui.theme.FCRadius
import com.forensic.capture.ui.theme.FCSpace
import com.forensic.capture.ui.theme.FCType
import com.forensic.capture.ui.theme.MonoFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 台账弹层/对话框状态 */
private sealed class LedgerDialog {
    data class NewNode(val parentId: String) : LedgerDialog()
    data class RenameNode(val id: String, val name: String) : LedgerDialog()
    data class DeleteNode(val id: String, val name: String) : LedgerDialog()
    data class Alias(val ev: Evidence) : LedgerDialog()
    data class Move(val ev: Evidence) : LedgerDialog()
    data class Notes(val ev: Evidence) : LedgerDialog()
}

@Composable
fun LedgerScreen(app: AppState) {
    val ctx = LocalContext.current
    var dialog by remember { mutableStateOf<LedgerDialog?>(null) }
    var menuOpenId by remember { mutableStateOf<String?>(null) }
    var manageEv by remember { mutableStateOf<Evidence?>(null) }

    val nodes = app.nodes.toList()
    val items = app.evidences.toList()
    val collapsed = app.collapsed.toSet()
    val rows = remember(nodes, items, collapsed, app.uncategorizedCollapsed) {
        TreeBuilder.build(nodes, items, collapsed, app.uncategorizedCollapsed)
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
                Text("本机台账", color = FCColor.TextDim, fontSize = FCType.label)
                Text("证据台账", color = FCColor.TextHi, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            IconSquareButton(FCIcons.Retry, { app.verifyAll() }, tint = if (app.verifying) FCColor.TextDim else FCColor.TextMid)
            Spacer(Modifier.width(8.dp))
            IconSquareButton(FCIcons.Folder, { dialog = LedgerDialog.NewNode("") })
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FCChip("共 ${items.size} 条", dotColor = FCColor.Brand)
            FCChip("已上链 ${app.anchoredCount}", color = FCColor.Ok, background = FCColor.OkSoft, borderColor = FCColor.Ok.copy(alpha = 0.24f))
            FCChip("待处理 ${app.pendingCount}", color = FCColor.Warn, background = FCColor.WarnSoft, borderColor = FCColor.Warn.copy(alpha = 0.24f))
            if (app.failedCount > 0) {
                FCChip("失败 ${app.failedCount}", color = FCColor.Danger, background = FCColor.DangerSoft, borderColor = FCColor.Danger.copy(alpha = 0.24f))
            }
        }

        if (rows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text("暂无证据与目录，去「录制」采集第一条证据", color = FCColor.TextDim, fontSize = FCType.bodySm)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)
            ) {
                items(rows, key = { row ->
                    when (row) {
                        is TreeRow.NodeRow -> "n:${row.key}"
                        is TreeRow.EvRow -> "e:${row.ev.id}"
                    }
                }) { row ->
                    when (row) {
                        is TreeRow.NodeRow -> NodeRowView(
                            row = row,
                            menuOpen = menuOpenId == row.key,
                            onMenu = { open -> menuOpenId = if (open) row.key else null },
                            onToggle = { app.toggleNode(row.key) },
                            onNewChild = { dialog = LedgerDialog.NewNode(row.key) },
                            onRename = { dialog = LedgerDialog.RenameNode(row.key, row.label) },
                            onDelete = { dialog = LedgerDialog.DeleteNode(row.key, row.label) }
                        )
                        is TreeRow.EvRow -> EvidenceRowView(
                            app = app,
                            ev = row.ev,
                            depth = row.depth,
                            menuOpen = menuOpenId == row.ev.id,
                            onMenu = { open -> menuOpenId = if (open) row.ev.id else null },
                            onClick = {
                                app.detailId = row.ev.id
                                app.go(Screen.Detail)
                            },
                            onManage = { manageEv = row.ev }
                        )
                    }
                }
            }
        }
    }

    // —— 证据管理面板 ——
    manageEv?.let { ev0 ->
        val ev = app.evidences.firstOrNull { it.id == ev0.id } ?: ev0
        FCSheet(visible = true, onDismiss = { manageEv = null }) {
            SheetGrip()
            Text(ev.alias.ifEmpty { ev.name }, color = FCColor.TextHi, fontSize = 14.sp, fontFamily = MonoFamily)
            Spacer(Modifier.height(4.dp))
            Text(
                "${AppState.formatTime(ev.createdAt)} · ${AppState.formatDuration((ev.durationMs / 1000).toInt())} · " +
                        "${"%.1f".format(EvidenceStore.bytesOf(ctx, ev) / 1048576.0)} MB",
                color = FCColor.TextDim,
                fontSize = 9.sp
            )
            Spacer(Modifier.height(12.dp))
            GlassCard(padding = FCSpace.x3) {
                FCKeyValue("上链状态", statusText(ev.status), valueColor = statusColor(ev.status))
                if (ev.evId.isNotEmpty()) FCKeyValue("存证编号", ev.evId, mono = true)
                FCKeyValue("目录", nodePath(nodes, ev.nodeId))
                FCKeyValue("文件 SHA-256", ev.fileSha256.take(20) + "…", mono = true)
                if (ev.notesHtml.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text("备注", color = FCColor.TextDim, fontSize = 8.sp, letterSpacing = 1.sp)
                    Spacer(Modifier.height(4.dp))
                    NotesPreview(html = ev.notesHtml)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    FCButton("备用名", kind = ButtonKind.Ghost, height = 40.dp, onClick = { dialog = LedgerDialog.Alias(ev); manageEv = null })
                }
                Box(modifier = Modifier.weight(1f)) {
                    FCButton("备注", kind = ButtonKind.Ghost, height = 40.dp, onClick = { dialog = LedgerDialog.Notes(ev); manageEv = null })
                }
                Box(modifier = Modifier.weight(1f)) {
                    FCButton("移动", kind = ButtonKind.Ghost, height = 40.dp, onClick = { dialog = LedgerDialog.Move(ev); manageEv = null })
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    FCButton("立即核验", kind = ButtonKind.Ghost, height = 40.dp, onClick = {
                        app.verifyOne(ev) { r ->
                            manageEv = null
                            app.showToast(r.detail.take(60), if (r.verdict == com.forensic.camera.anchor.Verifier.Verdict.INTACT) ToastKind.Ok else ToastKind.Warn)
                        }
                    })
                }
                Box(modifier = Modifier.weight(1f)) {
                    FCButton("导出相册", kind = ButtonKind.Ghost, height = 40.dp, onClick = {
                        app.exportOneToGallery(ev)
                        manageEv = null
                    })
                }
            }
            Spacer(Modifier.height(8.dp))
            FCButton("关闭", kind = ButtonKind.Primary, onClick = { manageEv = null })
        }
    }

    // —— 对话框 ——
    when (val d = dialog) {
        is LedgerDialog.NewNode -> TextInputDialog(
            title = if (d.parentId.isEmpty()) "新建目录" else "新建子目录",
            initial = "",
            confirmText = "创建",
            onConfirm = { app.addNode(it, d.parentId); dialog = null },
            onDismiss = { dialog = null }
        )
        is LedgerDialog.RenameNode -> TextInputDialog(
            title = "重命名目录",
            initial = d.name,
            confirmText = "保存",
            onConfirm = { app.renameNode(d.id, it); dialog = null },
            onDismiss = { dialog = null }
        )
        is LedgerDialog.DeleteNode -> ConfirmDialog(
            title = "删除目录",
            message = "删除「${d.name}」？其子目录将上移一层，目录内证据退回未分类。",
            confirmText = "删除",
            danger = true,
            onConfirm = { app.deleteNode(d.id); dialog = null },
            onDismiss = { dialog = null }
        )
        is LedgerDialog.Alias -> TextInputDialog(
            title = "设置备用名",
            initial = d.ev.alias,
            placeholder = "留空则显示原始文件名",
            confirmText = "保存",
            onConfirm = { app.setAlias(d.ev.id, it); dialog = null },
            onDismiss = { dialog = null }
        )
        is LedgerDialog.Move -> MoveDialog(
            nodes = nodes,
            currentId = d.ev.nodeId,
            onPick = { nodeId -> app.moveEvidence(d.ev.id, nodeId); dialog = null },
            onDismiss = { dialog = null }
        )
        is LedgerDialog.Notes -> NotesEditorDialog(
            initialHtml = d.ev.notesHtml,
            onSave = { html -> app.setNotes(d.ev.id, html); dialog = null },
            onDismiss = { dialog = null }
        )
        null -> {}
    }
}

private fun nodePath(nodes: List<TreeNode>, nodeId: String): String {
    if (nodeId.isEmpty()) return "未分类"
    val parts = mutableListOf<String>()
    var cur = nodes.firstOrNull { it.id == nodeId }
    var guard = 0
    while (cur != null && guard++ < 10) {
        parts.add(0, cur.name)
        cur = nodes.firstOrNull { it.id == cur!!.parentId }
    }
    return parts.joinToString(" / ")
}

private fun statusText(s: AnchorStatus) = when (s) {
    AnchorStatus.ANCHORED -> "已上链"
    AnchorStatus.PENDING -> "排队中"
    AnchorStatus.FAILED -> "上链失败"
}

private fun statusColor(s: AnchorStatus) = when (s) {
    AnchorStatus.ANCHORED -> FCColor.Ok
    AnchorStatus.PENDING -> FCColor.Warn
    AnchorStatus.FAILED -> FCColor.Danger
}

@Composable
private fun NodeRowView(
    row: TreeRow.NodeRow,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    onToggle: () -> Unit,
    onNewChild: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var anchor by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (row.depth * 14).dp)
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(FCRadius.sm))
            .background(FCColor.Surface1)
            .border(1.dp, FCColor.LineSoft, RoundedCornerShape(FCRadius.sm))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (row.expanded) "▾" else "▸",
            color = FCColor.TextDim,
            fontSize = 10.sp,
            modifier = Modifier.width(14.dp)
        )
        LineIcon(FCIcons.Folder, if (row.node == null) FCColor.TextDim else FCColor.Brand2, 14.dp)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(row.label, color = FCColor.TextHi, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(
                if (row.node == null) "${row.evCount} 条证据"
                else "${row.childCount} 子目录 · ${row.evCount} 证据",
                color = FCColor.TextDim,
                fontSize = 8.sp,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
        if (row.node != null) {
            Box {
                Text(
                    "⋯",
                    color = FCColor.TextLo,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onMenu(true) }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { onMenu(false) },
                    modifier = Modifier.background(FCColor.BgApp2)
                ) {
                    MenuLine("新建子目录") { onMenu(false); onNewChild() }
                    MenuLine("重命名") { onMenu(false); onRename() }
                    MenuLine("删除目录") { onMenu(false); onDelete() }
                }
            }
        }
    }
}

@Composable
private fun MenuLine(text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, color = FCColor.TextMid, fontSize = 11.sp) },
        onClick = onClick
    )
}

@Composable
private fun EvidenceRowView(
    app: AppState,
    ev: Evidence,
    depth: Int,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    onClick: () -> Unit,
    onManage: () -> Unit
) {
    val ctx = LocalContext.current
    var longPressHint by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 14).dp, top = 2.dp, bottom = 2.dp, end = 0.dp)
            .clip(RoundedCornerShape(FCRadius.sm))
            .background(if (longPressHint) FCColor.BrandSoft else FCColor.Surface1)
            .border(1.dp, FCColor.LineSoft, RoundedCornerShape(FCRadius.sm))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LineIcon(
            if (ev.type == "audio") FCIcons.MicRound else FCIcons.VideoLine,
            FCColor.TextLo,
            13.dp
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                ev.alias.ifEmpty { ev.name },
                color = FCColor.TextHi,
                fontSize = 10.sp,
                fontFamily = MonoFamily,
                maxLines = 1
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                StatusDot(ev.status)
                Spacer(Modifier.width(5.dp))
                Text(
                    "${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ev.createdAt))} · " +
                            AppState.formatDuration((ev.durationMs / 1000).toInt()) + " · " +
                            "${"%.1f".format(EvidenceStore.bytesOf(ctx, ev) / 1048576.0)} MB",
                    color = FCColor.TextDim,
                    fontSize = 8.sp
                )
            }
        }
        if (ev.notesHtml.isNotBlank()) {
            LineIcon(FCIcons.Doc, FCColor.TextDim, 11.dp)
            Spacer(Modifier.width(6.dp))
        }
        Box {
            Text(
                "⋯",
                color = FCColor.TextLo,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onMenu(true) }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { onMenu(false) },
                modifier = Modifier.background(FCColor.BgApp2)
            ) {
                MenuLine("查看详情") { onMenu(false); onClick() }
                MenuLine("立即核验") { onMenu(false); app.verifyOne(ev) }
                MenuLine("证据管理") { onMenu(false); onMenu(false); onManage() }
                MenuLine("导出到相册") { onMenu(false); app.exportOneToGallery(ev) }
            }
        }
    }
}

@Composable
private fun StatusDot(status: AnchorStatus) {
    val color = statusColor(status)
    Canvas(modifier = Modifier.size(7.dp)) {
        when (status) {
            AnchorStatus.ANCHORED -> drawCircle(color)
            AnchorStatus.FAILED -> drawCircle(color)
            AnchorStatus.PENDING -> drawCircle(color, style = androidx.compose.ui.graphics.drawscope.Stroke(1.4.dp.toPx()))
        }
    }
}

@Composable
private fun NotesPreview(html: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FCRadius.xs))
            .background(FCColor.CodeBg)
            .border(1.dp, FCColor.LineSoft, RoundedCornerShape(FCRadius.xs))
            .padding(10.dp)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { c ->
                TextView(c).apply {
                    setTextColor(FCColor.TextMid.toArgb())
                    textSize = 10f
                    setLineSpacing(4f, 1f)
                }
            },
            update = { it.text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY) }
        )
    }
}

/* —————— 对话框 —————— */

@Composable
private fun DialogScaffold(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FCColor.Scrim)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .clip(RoundedCornerShape(FCRadius.md))
                .background(FCColor.BgApp2)
                .border(1.dp, FCColor.Line, RoundedCornerShape(FCRadius.md))
                .clickable { }
                .padding(16.dp)
        ) {
            Text(title, color = FCColor.TextHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

private typealias ColumnScope = androidx.compose.foundation.layout.ColumnScope

@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    confirmText: String = "确定",
    placeholder: String = "",
    multiline: Boolean = false,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    DialogScaffold(title = title, onDismiss = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (multiline) 90.dp else 42.dp)
                .clip(RoundedCornerShape(FCRadius.sm))
                .background(FCColor.InputBg)
                .border(1.dp, FCColor.LineSoft, RoundedCornerShape(FCRadius.sm))
                .padding(horizontal = 11.dp, vertical = 9.dp)
        ) {
            if (text.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, color = FCColor.TextDim, fontSize = 9.5.sp)
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = FCColor.TextHi, fontSize = 10.5.sp, lineHeight = 15.sp),
                cursorBrush = SolidColor(FCColor.Brand2),
                singleLine = !multiline,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                FCButton("取消", kind = ButtonKind.Ghost, height = 40.dp, onClick = onDismiss)
            }
            Box(modifier = Modifier.weight(1f)) {
                FCButton(confirmText, height = 40.dp, enabled = text.isNotBlank(), onClick = { onConfirm(text) })
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    danger: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    DialogScaffold(title = title, onDismiss = onDismiss) {
        Text(message, color = FCColor.TextLo, fontSize = 10.sp, lineHeight = 15.sp)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                FCButton("取消", kind = ButtonKind.Ghost, height = 40.dp, onClick = onDismiss)
            }
            Box(modifier = Modifier.weight(1f)) {
                FCButton(
                    confirmText,
                    height = 40.dp,
                    kind = if (danger) ButtonKind.Ok else ButtonKind.Primary,
                    onClick = onConfirm
                )
            }
        }
    }
}

@Composable
private fun MoveDialog(
    nodes: List<TreeNode>,
    currentId: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    DialogScaffold(title = "移动到目录", onDismiss = onDismiss) {
        MoveOption("未分类", currentId.isEmpty()) { onPick("") }
        nodes.forEach { n ->
            MoveOption(n.name, n.id == currentId, indent = depthOf(nodes, n.id)) { onPick(n.id) }
        }
        Spacer(Modifier.height(6.dp))
        FCButton("取消", kind = ButtonKind.Ghost, height = 40.dp, onClick = onDismiss)
    }
}

private fun depthOf(nodes: List<TreeNode>, id: String): Int {
    var d = 0
    var cur = nodes.firstOrNull { it.id == id }
    while (cur != null && cur.parentId.isNotEmpty() && d < 10) {
        d++
        cur = nodes.firstOrNull { it.id == cur!!.parentId }
    }
    return d
}

@Composable
private fun MoveOption(label: String, selected: Boolean, indent: Int = 0, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (indent * 10).dp, bottom = 6.dp)
            .clip(RoundedCornerShape(FCRadius.sm))
            .background(if (selected) FCColor.BrandSoft else FCColor.Surface1)
            .border(1.dp, if (selected) FCColor.Brand.copy(alpha = 0.3f) else FCColor.LineSoft, RoundedCornerShape(FCRadius.sm))
            .clickable(enabled = !selected, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LineIcon(FCIcons.Folder, if (selected) FCColor.Brand2 else FCColor.TextLo, 13.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (selected) FCColor.TextHi else FCColor.TextMid, fontSize = 10.sp, modifier = Modifier.weight(1f))
        if (selected) Text("当前", color = FCColor.Brand2, fontSize = 8.sp)
    }
}

/** 富文本备注编辑器：工具栏插入 HTML 标签 + fromHtml 渲染预览（功能真实可用）。 */
@Composable
private fun NotesEditorDialog(
    initialHtml: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var tf by remember { mutableStateOf(TextFieldValue(initialHtml)) }
    val raw = tf.text

    fun wrap(prefix: String, suffix: String) {
        val v = tf.text
        val s = minOf(tf.selection.start, tf.selection.end).coerceIn(0, v.length)
        val e = maxOf(tf.selection.start, tf.selection.end).coerceIn(0, v.length)
        val newText = if (s < e) {
            v.substring(0, s) + prefix + v.substring(s, e) + suffix + v.substring(e)
        } else {
            v.substring(0, s) + prefix + suffix + v.substring(s)
        }
        val caret = if (s < e) e + prefix.length + suffix.length else s + prefix.length
        tf = TextFieldValue(newText, TextRange(caret))
    }

    fun linePrefix(p: String) {
        val v = tf.text
        val pos = tf.selection.start.coerceIn(0, v.length)
        val lineStart = if (pos == 0) 0 else {
            val idx = v.lastIndexOf('\n', pos - 1)
            if (idx < 0) 0 else idx + 1
        }
        tf = TextFieldValue(v.substring(0, lineStart) + p + v.substring(lineStart), TextRange(pos + p.length))
    }

    DialogScaffold(title = "富文本备注", onDismiss = onDismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            ToolBtn("B", FontWeight.Bold, FontStyle.Normal) { wrap("<b>", "</b>") }
            ToolBtn("I", FontWeight.Normal, FontStyle.Italic) { wrap("<i>", "</i>") }
            ToolBtn("U", FontWeight.Normal, FontStyle.Normal, underline = true) { wrap("<u>", "</u>") }
            ToolBtn("• 列表", FontWeight.Normal, FontStyle.Normal) { linePrefix("• ") }
            ToolBtn("换行", FontWeight.Normal, FontStyle.Normal) { wrap("<br>", "") }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp, max = 160.dp)
                .clip(RoundedCornerShape(FCRadius.sm))
                .background(FCColor.InputBg)
                .border(1.dp, FCColor.LineSoft, RoundedCornerShape(FCRadius.sm))
                .padding(horizontal = 11.dp, vertical = 9.dp)
        ) {
            if (raw.isEmpty()) {
                Text("输入备注内容（HTML 源码，工具栏插入标签）…", color = FCColor.TextDim, fontSize = 9.5.sp)
            }
            BasicTextField(
                value = tf,
                onValueChange = { tf = it },
                textStyle = TextStyle(color = FCColor.TextHi, fontSize = 9.5.sp, lineHeight = 14.sp, fontFamily = MonoFamily),
                cursorBrush = SolidColor(FCColor.Brand2),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(10.dp))
        Text("预览", color = FCColor.TextDim, fontSize = 8.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        NotesPreview(html = raw)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                FCButton("取消", kind = ButtonKind.Ghost, height = 40.dp, onClick = onDismiss)
            }
            Box(modifier = Modifier.weight(1f)) {
                FCButton("保存备注", height = 40.dp, onClick = { onSave(raw) })
            }
        }
    }
}

@Composable
private fun ToolBtn(text: String, weight: FontWeight, style: FontStyle, underline: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(FCRadius.xs))
            .background(FCColor.Surface1)
            .border(1.dp, FCColor.LineSoft, RoundedCornerShape(FCRadius.xs))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text,
            color = FCColor.TextMid,
            fontSize = 10.sp,
            fontWeight = weight,
            fontStyle = style,
            textDecoration = if (underline) TextDecoration.Underline else null
        )
    }
}
