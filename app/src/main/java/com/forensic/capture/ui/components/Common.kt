package com.forensic.capture.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forensic.capture.ui.theme.FCColor
import com.forensic.capture.ui.theme.FCRadius
import com.forensic.capture.ui.theme.FCSpace
import com.forensic.capture.ui.theme.FCType
import com.forensic.capture.ui.theme.MonoFamily
import com.forensic.capture.ui.theme.MonoStyle

/* ==========================================================================
   基础容器
   ========================================================================== */

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    padding: Dp = FCSpace.x4,
    radius: Dp = FCRadius.md,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius))
            .background(FCColor.Surface1)
            .border(1.dp, FCColor.Line, RoundedCornerShape(radius))
            .padding(padding),
        content = content
    )
}

/** 10px 字重 600、字距 0.14em 的小标签，原型里用于卡片内分组标题 */
@Composable
fun FCLabel(text: String, modifier: Modifier = Modifier, color: Color = FCColor.TextDim) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = color,
        fontSize = FCType.badge,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.4.sp
    )
}

@Composable
fun FCMono(text: String, size: Int = 12, color: Color = FCColor.TextLo) {
    Text(text = text, color = color, fontSize = size.sp, style = MonoStyle.copy(fontSize = size.sp))
}

@Composable
fun FCEyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text = text, modifier = modifier, color = FCColor.TextDim, fontSize = FCType.label, lineHeight = 16.sp)
}

@Composable
fun FCPageHead(
    title: String,
    eyebrow: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconSquareButton(FCIcons.Back, onBack)
            Spacer(Modifier.width(FCSpace.x3))
        }
        Column(modifier = Modifier.weight(1f)) {
            if (eyebrow != null) FCEyebrow(eyebrow)
            Text(text = title, color = FCColor.TextHi, fontSize = FCType.title, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
        }
        if (trailing != null) trailing()
    }
}

/* ==========================================================================
   按钮 / 徽标 / 开关
   ========================================================================== */

@Composable
fun IconSquareButton(icon: IconSpec, onClick: () -> Unit, tint: Color = FCColor.TextMid) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(FCRadius.sm))
            .background(FCColor.Surface1)
            .border(1.dp, FCColor.Line, RoundedCornerShape(FCRadius.sm))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        LineIcon(icon, tint, 15.dp)
    }
}

@Composable
fun IconGhostButton(icon: IconSpec, onClick: () -> Unit, tint: Color = FCColor.TextMid) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(FCColor.Surface1)
            .border(1.dp, FCColor.Line, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        LineIcon(icon, tint, 15.dp)
    }
}

enum class ButtonKind { Primary, Ghost, Ok }

@Composable
fun FCButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    icon: IconSpec? = null,
    enabled: Boolean = true,
    height: Dp = 46.dp
) {
    val shape = RoundedCornerShape(FCRadius.sm)
    val bg = when (kind) {
        ButtonKind.Primary -> FCColor.Brand
        ButtonKind.Ghost -> FCColor.Surface1
        ButtonKind.Ok -> FCColor.OkSoft
    }
    val fg = when (kind) {
        ButtonKind.Primary -> Color.White
        ButtonKind.Ghost -> FCColor.TextMid
        ButtonKind.Ok -> FCColor.Ok
    }
    val border = when (kind) {
        ButtonKind.Primary -> Color.Transparent
        ButtonKind.Ghost -> FCColor.Line
        ButtonKind.Ok -> FCColor.Ok.copy(alpha = 0.28f)
    }
    Box(
        modifier = modifier
            .then(
                if (kind == ButtonKind.Primary && enabled)
                    Modifier.shadow(18.dp, shape, ambientColor = FCColor.Brand.copy(alpha = 0.45f), spotColor = FCColor.Brand.copy(alpha = 0.45f))
                else Modifier
            )
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(if (enabled) bg else bg.copy(alpha = 0.55f))
            .border(1.dp, border, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FCSpace.x2)) {
            if (icon != null) LineIcon(icon, fg, 15.dp)
            Text(text = text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun FCChip(
    text: String,
    color: Color = FCColor.TextLo,
    background: Color = Color.White.copy(alpha = 0.05f),
    borderColor: Color = FCColor.LineSoft,
    dotColor: Color? = null,
    fontSize: Int = 10
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (dotColor != null) Dot(dotColor, 4.dp)
        Text(text = text, color = color, fontSize = fontSize.sp, lineHeight = (fontSize + 1).sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun FCSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 24.dp)
            .clip(CircleShape)
            .background(if (checked) FCColor.Brand else Color.White.copy(alpha = 0.12f))
            .border(1.dp, if (checked) Color.Transparent else FCColor.Line, CircleShape)
            .clickable(enabled = enabled) { onChange(!checked) },
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 3.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(if (checked) Color.White else FCColor.TextLo)
        )
    }
}

/* ==========================================================================
   数据展示
   ========================================================================== */

@Composable
fun FCBar(progress: Int, color: Color = FCColor.Brand, height: Dp = 4.dp, danger: Boolean = false) {
    val p = progress.coerceIn(0, 100) / 100f
    val brush = when {
        danger -> Brush.horizontalGradient(listOf(FCColor.DangerDeep, FCColor.Danger))
        color == FCColor.Ok -> Brush.horizontalGradient(listOf(FCColor.OkDeep, FCColor.Ok))
        else -> Brush.horizontalGradient(listOf(FCColor.Brand, FCColor.Brand2))
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.09f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(p)
                .height(height)
                .clip(CircleShape)
                .background(brush)
        )
    }
}

@Composable
fun FCKeyValue(
    key: String,
    value: String,
    mono: Boolean = false,
    valueColor: Color = FCColor.TextMid,
    maxLines: Int = 1
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(text = key, color = FCColor.TextLo, fontSize = FCType.bodySm, modifier = Modifier.width(96.dp))
        Text(
            text = value,
            color = valueColor,
            fontSize = if (mono) 11.sp else FCType.bodySm,
            fontFamily = if (mono) MonoFamily else null,
            textAlign = TextAlign.End,
            maxLines = maxLines,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 哈希块 + 复制：原型中本产品的识别符号 */
@Composable
fun HashBlock(
    hash: String,
    onCopy: () -> Unit,
    label: String? = null,
    chipText: String? = null,
    fontSize: Int = 10
) {
    if (label != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FCLabel(label, Modifier.weight(1f))
            if (chipText != null) {
                FCChip(chipText, color = FCColor.Ok, background = FCColor.OkSoft, borderColor = FCColor.Ok.copy(alpha = 0.24f), fontSize = 9)
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(FCRadius.sm))
                .background(Color.Black.copy(alpha = 0.34f))
                .border(1.dp, FCColor.LineSoft, RoundedCornerShape(FCRadius.sm))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = hash,
                color = FCColor.HashText,
                fontSize = fontSize.sp,
                lineHeight = (fontSize * 1.75f).sp,
                fontFamily = MonoFamily
            )
        }
        Spacer(Modifier.width(FCSpace.x2))
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(FCRadius.xs))
                .background(FCColor.Surface1)
                .border(1.dp, FCColor.LineSoft, RoundedCornerShape(FCRadius.xs))
                .clickable(onClick = onCopy),
            contentAlignment = Alignment.Center
        ) {
            LineIcon(FCIcons.Copy, FCColor.TextLo, 13.dp)
        }
    }
}

@Composable
fun SectionHead(title: String, action: String? = null, onAction: (() -> Unit)? = null, topGap: Dp = 22.dp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topGap, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title.uppercase(),
            color = FCColor.TextDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.4.sp,
            modifier = Modifier.weight(1f)
        )
        if (action != null) {
            Text(
                text = action,
                color = FCColor.Brand2,
                fontSize = FCType.label,
                modifier = Modifier.clickable { onAction?.invoke() }
            )
        }
    }
}

@Composable
fun FCNote(text: String, modifier: Modifier = Modifier, center: Boolean = false) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FCRadius.xs))
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, FCColor.LineSoft, RoundedCornerShape(FCRadius.xs))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            color = if (center) FCColor.TextDim else FCColor.TextLo,
            fontSize = 9.sp,
            lineHeight = 15.sp,
            textAlign = if (center) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/* ==========================================================================
   状态栏 / 底部导航 / 弹层
   ========================================================================== */

@Composable
fun FCStatusBar(time: String = "21:55", tint: Color = FCColor.TextHi) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = FCSpace.x5),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = time, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Canvas(modifier = Modifier.size(15.dp, 11.dp)) {
                val w = size.width / 15f
                val h = size.height / 11f
                listOf(7f, 5f, 2.6f, 0f).forEachIndexed { i, top ->
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset((i * 4f) * w, top * h),
                        size = Size(2.4f * w, (11f - top) * h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.8f * w)
                    )
                }
            }
            Canvas(modifier = Modifier.size(14.dp, 11.dp)) {
                val sc = size.width / 24f
                drawArc(tint, 200f, 140f, false, topLeft = Offset(2 * sc, 2 * sc), size = Size(20 * sc, 20 * sc), style = Stroke(2 * sc, cap = StrokeCap.Round))
                drawArc(tint, 215f, 110f, false, topLeft = Offset(5.5f * sc, 6 * sc), size = Size(13 * sc, 13 * sc), style = Stroke(2 * sc, cap = StrokeCap.Round))
                drawCircle(tint, radius = 1.6f * sc, center = Offset(12 * sc, 9.4f * sc))
            }
            Canvas(modifier = Modifier.size(23.dp, 12.dp)) {
                val w = size.width / 25f
                val h = size.height / 12f
                drawRoundRect(color = tint.copy(alpha = 0.4f), topLeft = Offset(0.5f * w, 0.5f * h), size = Size(21 * w, 11 * h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.2f * w), style = Stroke(1f))
                drawRoundRect(color = tint, topLeft = Offset(2 * w, 2 * h), size = Size(16 * w, 8 * h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.8f * w))
                drawRoundRect(color = tint.copy(alpha = 0.5f), topLeft = Offset(23 * w, 4.2f * h), size = Size(1.6f * w, 3.6f * h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.8f * w))
            }
        }
    }
}

enum class Tab { Home, Ledger, Verify, Mine }

@Composable
fun FCTabBar(current: Tab, badge: Int = 0, onSelect: (Tab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(FCColor.BgApp.copy(alpha = 0.9f))
            .border(width = 0.dp, color = Color.Transparent)
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 22.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        val items = listOf(
            Triple(Tab.Home, FCIcons.Home, "首页"),
            Triple(Tab.Ledger, FCIcons.Ledger, "台账"),
            Triple(Tab.Verify, FCIcons.ShieldCheck, "校验"),
            Triple(Tab.Mine, FCIcons.Person, "我的")
        )
        items.forEach { (tab, icon, label) ->
            val active = tab == current
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box {
                    LineIcon(icon, if (active) FCColor.Brand2 else FCColor.TextDim, 17.dp)
                    if (tab == Tab.Ledger && badge > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(13.dp)
                                .clip(CircleShape)
                                .background(FCColor.Danger),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("$badge", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text(text = label, color = if (active) FCColor.Brand2 else FCColor.TextDim, fontSize = 10.sp)
            }
        }
    }
}

/** 底部弹层：scrim + 面板，面板内部可滚动由调用方决定 */
@Composable
fun FCSheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(FCColor.SheetScrim)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() }
            )
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(260)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(160))
        ) {
            Surface(
                color = FCColor.BgApp2,
                shape = RoundedCornerShape(topStart = FCRadius.xl, topEnd = FCRadius.xl),
                border = androidx.compose.foundation.BorderStroke(1.dp, FCColor.Line)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FCSpace.x5)
                        .padding(top = 10.dp, bottom = 22.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
fun SheetGrip() {
    Box(
        modifier = Modifier
            .padding(bottom = 14.dp)
            .width(38.dp)
            .height(4.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f))
    )
}

enum class ToastKind { Ok, Warn, Bad, Info }

data class ToastData(val message: String, val kind: ToastKind)

@Composable
fun FCToast(data: ToastData?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = data != null,
        modifier = modifier,
        enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { it / 2 },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 2 }
    ) {
        val kind = data?.kind ?: ToastKind.Info
        val color = when (kind) {
            ToastKind.Ok -> FCColor.Ok
            ToastKind.Warn -> FCColor.Warn
            ToastKind.Bad -> FCColor.Danger
            ToastKind.Info -> FCColor.Brand2
        }
        val icon = when (kind) {
            ToastKind.Ok -> FCIcons.CheckLine
            ToastKind.Warn -> FCIcons.Warn
            ToastKind.Bad -> FCIcons.Warn
            ToastKind.Info -> FCIcons.Shield
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(FCRadius.sm))
                .background(FCColor.BgToast)
                .border(1.dp, FCColor.Line, RoundedCornerShape(FCRadius.sm))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LineIcon(icon, color, 14.dp)
            Text(text = data?.message ?: "", color = FCColor.TextMid, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}
