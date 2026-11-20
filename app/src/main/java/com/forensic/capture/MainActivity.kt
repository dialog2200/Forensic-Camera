package com.forensic.capture

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.forensic.capture.state.AppState
import com.forensic.capture.state.RecState
import com.forensic.capture.state.Screen
import com.forensic.capture.ui.components.FCStatusBar
import com.forensic.capture.ui.components.FCTabBar
import com.forensic.capture.ui.components.FCToast
import com.forensic.capture.ui.components.Tab
import com.forensic.capture.ui.screens.ConfirmScreen
import com.forensic.capture.ui.screens.DetailScreen
import com.forensic.capture.ui.screens.ExportScreen
import com.forensic.capture.ui.screens.HomeScreen
import com.forensic.capture.ui.screens.LedgerScreen
import com.forensic.capture.ui.screens.RecordScreen
import com.forensic.capture.ui.screens.SettingsScreen
import com.forensic.capture.ui.screens.VerifyScreen
import com.forensic.capture.ui.theme.FCColor
import com.forensic.capture.ui.theme.ForensicTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            ForensicTheme {
                ForensicApp()
            }
        }
    }

    override fun onDestroy() {
        (currentAppState)?.let { it.capture.release() }
        super.onDestroy()
    }

    companion object {
        @Volatile
        var currentAppState: AppState? = null
    }
}

/** 底部导航对应的屏；null 表示该屏不显示 tabbar */
private fun tabOf(screen: Screen): Tab? = when (screen) {
    Screen.Home -> Tab.Home
    Screen.Ledger -> Tab.Ledger
    Screen.Verify -> Tab.Verify
    Screen.Settings -> Tab.Mine
    else -> null
}

private fun screenOf(tab: Tab): Screen = when (tab) {
    Tab.Home -> Screen.Home
    Tab.Ledger -> Screen.Ledger
    Tab.Verify -> Screen.Verify
    Tab.Mine -> Screen.Settings
}

@Composable
private fun ForensicApp() {
    val ctx = LocalContext.current
    val app = remember {
        AppState(ctx).also { MainActivity.currentAppState = it }
    }
    val clock = rememberClock()

    // 录制期间防息屏，停止后释放
    LaunchedEffect(app.recState) {
        val window = (ctx as? android.app.Activity)?.window
        if (app.recState == RecState.Recording) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 返回键：二级页面回到首页；录制中返回 = 停止录制（产物进入封存确认）
    val backTarget: Screen? = when (app.screen) {
        Screen.Record, Screen.Confirm, Screen.Detail, Screen.Export, Screen.Ledger ->
            if (app.screen == Screen.Detail || app.screen == Screen.Export) Screen.Ledger else Screen.Home
        else -> null
    }
    BackHandler(enabled = backTarget != null) {
        when (app.screen) {
            Screen.Record -> {
                if (app.recState == RecState.Recording) {
                    app.stopRecording()
                } else {
                    app.go(Screen.Home)
                }
            }
            Screen.Confirm -> {
                // 草稿未入队时保留，可从台账/首页再次进入确认
                app.go(Screen.Home)
            }
            else -> backTarget?.let { app.go(it) }
        }
    }

    // 轻提示 2.4s 自动消失
    LaunchedEffect(app.toast) {
        if (app.toast != null) {
            delay(2400)
            app.toast = null
        }
    }

    val tab = tabOf(app.screen)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FCColor.BgApp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            FCStatusBar(time = clock)
            Crossfade(
                targetState = app.screen,
                animationSpec = tween(180),
                modifier = Modifier.weight(1f)
            ) { screen ->
                when (screen) {
                    Screen.Home -> HomeScreen(app)
                    Screen.Record -> RecordScreen(app)
                    Screen.Confirm -> ConfirmScreen(app)
                    Screen.Ledger -> LedgerScreen(app)
                    Screen.Detail -> DetailScreen(app)
                    Screen.Verify -> VerifyScreen(app)
                    Screen.Export -> ExportScreen(app)
                    Screen.Settings -> SettingsScreen(app)
                }
            }
            if (tab != null) {
                FCTabBar(
                    current = tab,
                    badge = app.failedCount,
                    onSelect = { app.go(screenOf(it)) }
                )
            }
        }

        FCToast(
            data = app.toast,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (tab != null) 96.dp else 28.dp)
        )
    }
}

/** 自绘状态栏时钟（20s 刷新一次） */
@Composable
private fun rememberClock(): String {
    var time by remember { mutableStateOf(nowText()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            time = nowText()
        }
    }
    return time
}

private fun nowText(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
