package com.forensic.capture.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计 token —— 唯一来源。
 * 数值来自交付原型 1.html 的拆解结论（近黑底 / 玻璃层 / 单一蓝色主行动色 / 三色语义）。
 * 任何界面不得直接写十六进制颜色、字号、圆角、间距，必须引用这里的 token。
 */
object FCColor {
    // 底色
    val BgPage = Color(0xFF050505)
    val BgApp = Color(0xFF0F1115)
    val BgApp2 = Color(0xFF12151B)

    // 玻璃层与描边
    val Surface1 = Color.White.copy(alpha = 0.05f)
    val Surface2 = Color.White.copy(alpha = 0.08f)
    val Surface3 = Color.White.copy(alpha = 0.12f)
    val Line = Color.White.copy(alpha = 0.10f)
    val LineSoft = Color.White.copy(alpha = 0.06f)
    val LineStrong = Color.White.copy(alpha = 0.18f)
    /** 输入框 / 次级容器底色 */
    val InputBg = Color.White.copy(alpha = 0.04f)
    /** 代码块底色，用于哈希与证书文本 */
    val CodeBg = Color.Black.copy(alpha = 0.34f)

    /** 取景 / 媒体层专用：半透明白，避免各处直接写 alpha */
    val HudText = Color.White.copy(alpha = 0.72f)
    val HintText = Color.White.copy(alpha = 0.50f)
    val RingSubtle = Color.White.copy(alpha = 0.14f)
    val TrackEmpty = Color.White.copy(alpha = 0.09f)
    val ScrimDeep = Color(0xFF05070A)

    // 文本
    val TextHi = Color(0xFFFFFFFF)
    val TextMid = Color(0xFFC9CFD9)
    val TextLo = Color(0xFF8B93A1)
    val TextDim = Color(0xFF5C6472)
    /** 哈希等宽块正文 */
    val HashText = Color(0xFFA8B2C1)

    // 语义色（红 / 绿 / 黄严格限定语义，蓝色为唯一行动色）
    val Brand = Color(0xFF2D7DFF)
    val Brand2 = Color(0xFF5B9BFF)
    val BrandSoft = Color(0x242D7DFF)
    val Ok = Color(0xFF34C759)
    val OkSoft = Color(0x1F34C759)
    /** 成功态进度条深色端 */
    val OkDeep = Color(0xFF23A94A)
    val Warn = Color(0xFFFFCC00)
    val WarnSoft = Color(0x1AFFCC00)
    val Danger = Color(0xFFFF3B30)
    val DangerSoft = Color(0x1FFF3B30)
    /** 录制钮 / 进度条的深色端 */
    val DangerDeep = Color(0xFFD22A21)

    val Scrim = Color(0xB3000000)
    val SheetScrim = Color(0xCC000000)
    /** 轻提示条底色（近黑玻璃） */
    val BgToast = Color(0xF21A1D24)
}

object FCRadius {
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 20.dp
    val xl: Dp = 28.dp
    val xxl: Dp = 32.dp
    val phone: Dp = 40.dp
    val pill: Dp = 999.dp
}

object FCSpace {
    val x1: Dp = 4.dp
    val x2: Dp = 8.dp
    val x3: Dp = 12.dp
    val x4: Dp = 16.dp
    val x5: Dp = 20.dp
    val x6: Dp = 24.dp
    val x7: Dp = 28.dp
    val x8: Dp = 32.dp
    /** 屏内左右留白 */
    val screen: Dp = 20.dp
}

object FCType {
    val display = 34.sp
    val title = 28.sp
    val h2 = 20.sp
    val h3 = 17.sp
    val body = 15.sp
    val bodySm = 13.sp
    val caption = 12.sp
    val label = 11.sp
    val badge = 10.sp

    val mono = FontFamily.Monospace
    val sans = FontFamily.SansSerif
}

object FCShadow {
    const val BrandSoft = 0.28f
    const val Brand = 0.40f
    const val Danger = 0.35f
}

/** 取景画面色板：仅用于录制页的模拟取景层，与界面 token 分离管理 */
object FCMedia {
    val StageTop = Color(0xFF10161D)
    val StageMid = Color(0xFF0A0D12)
    val StageBottom = Color(0xFF05070A)
    val SceneFar = Color(0xFF18202A)
    val SceneNear = Color(0xFF0E141A)
    val Building = Color(0xFF1B242E)
    val Ground = Color(0xFF0B1116)
}
