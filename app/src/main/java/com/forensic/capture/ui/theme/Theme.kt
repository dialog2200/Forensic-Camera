package com.forensic.capture.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.forensic.capture.R

/**
 * 字体：交付原型使用 Inter（界面）+ JetBrains Mono（技术数据）。
 * 两个可变字体文件随 APK 打包，离线可用；系统无该字体时无需回退逻辑。
 */
val InterFamily = FontFamily(
    Font(R.font.inter_var, FontWeight.Light),
    Font(R.font.inter_var, FontWeight.Normal),
    Font(R.font.inter_var, FontWeight.Medium),
    Font(R.font.inter_var, FontWeight.SemiBold),
    Font(R.font.inter_var, FontWeight.Bold)
)

val MonoFamily = FontFamily(
    Font(R.font.jetbrainsmono_var, FontWeight.Normal),
    Font(R.font.jetbrainsmono_var, FontWeight.Medium),
    Font(R.font.jetbrainsmono_var, FontWeight.Bold)
)

private val FCTypography = Typography(
    displayLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = FCType.display, lineHeight = 38.sp, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = FCType.title, lineHeight = 32.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = FCType.h2, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = FCType.h3, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = FCType.body, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = FCType.bodySm, lineHeight = 19.sp),
    labelSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = FCType.badge, lineHeight = 13.sp, letterSpacing = 0.8.sp)
)

private val FCDarkScheme = darkColorScheme(
    primary = FCColor.Brand,
    onPrimary = FCColor.TextHi,
    secondary = FCColor.Brand2,
    background = FCColor.BgApp,
    onBackground = FCColor.TextHi,
    surface = FCColor.BgApp,
    onSurface = FCColor.TextHi,
    surfaceVariant = FCColor.BgApp2,
    onSurfaceVariant = FCColor.TextMid,
    error = FCColor.Danger,
    outline = FCColor.Line
)

@Composable
fun ForensicTheme(content: @Composable () -> Unit) {
    isSystemInDarkTheme() // 应用固定暗色，不跟随系统
    MaterialTheme(
        colorScheme = FCDarkScheme,
        typography = FCTypography,
        content = content
    )
}
