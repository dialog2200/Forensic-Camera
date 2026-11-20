package com.forensic.capture.ui.theme

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

/** 等宽字体样式（哈希、坐标、时间、区块高度等技术数据统一使用） */
val MonoStyle = androidx.compose.ui.text.TextStyle(
    fontFamily = MonoFamily,
    fontWeight = FontWeight.Normal,
    fontSize = FCType.caption,
    lineHeight = 18.sp,
    letterSpacing = 0.2.sp
)

val MonoSmall = androidx.compose.ui.text.TextStyle(
    fontFamily = MonoFamily,
    fontWeight = FontWeight.Normal,
    fontSize = FCType.label,
    lineHeight = 16.sp,
    letterSpacing = 0.2.sp
)

/** 统一页面左右留白与顶部间距，避免各屏硬编码 */
val ScreenHPad = FCGen.screenPad
val ScreenTopGap = 8.dp

private object FCGen {
    val screenPad = 20.dp
}
