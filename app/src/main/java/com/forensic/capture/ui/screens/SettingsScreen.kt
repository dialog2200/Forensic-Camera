package com.forensic.capture.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forensic.camera.data.SettingsStore
import com.forensic.capture.state.AppState
import com.forensic.capture.state.Screen
import com.forensic.capture.ui.components.ButtonKind
import com.forensic.capture.ui.components.FCButton
import com.forensic.capture.ui.components.FCChip
import com.forensic.capture.ui.components.FCIcons
import com.forensic.capture.ui.components.FCSwitch
import com.forensic.capture.ui.components.GlassCard
import com.forensic.capture.ui.components.IconSquareButton
import com.forensic.capture.ui.components.LineIcon
import com.forensic.capture.ui.components.ToastKind
import com.forensic.capture.ui.theme.FCColor
import com.forensic.capture.ui.theme.FCRadius
import com.forensic.capture.ui.theme.FCSpace
import com.forensic.capture.ui.theme.FCType
import com.forensic.capture.ui.theme.MonoFamily

@Composable
fun SettingsScreen(app: AppState) {
    val clipboard = LocalClipboardManager.current
    var secretId by remember { mutableStateOf(app.settings.secretId) }
    var secretKey by remember { mutableStateOf(app.settings.secretKey) }
    var privInput by remember { mutableStateOf("") }
    var keyError by remember { mutableStateOf<String?>(null) }
    var pendingPriv by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = FCSpace.screen)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconSquareButton(FCIcons.Back, { app.go(Screen.Home) })
            Spacer(Modifier.width(FCSpace.x3))
            Column(modifier = Modifier.weight(1f)) {
                Text("设备与存证", color = FCColor.TextDim, fontSize = FCType.label)
                Text("设置", color = FCColor.TextHi, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            FCChip(
                text = if (app.configured) "已配置" else "未配置",
                color = if (app.configured) FCColor.Ok else FCColor.Warn,
                background = if (app.configured) FCColor.OkSoft else FCColor.WarnSoft,
                borderColor = (if (app.configured) FCColor.Ok else FCColor.Warn).copy(alpha = 0.24f),
                dotColor = if (app.configured) FCColor.Ok else FCColor.Warn
            )
        }

        val loadDiag = SettingsStore.lastLoadDiag
        if (loadDiag.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            GlassCard(padding = FCSpace.x4) {
                Text(
                    "至信链配置读取失败",
                    color = FCColor.Danger,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "$loadDiag。请重新输入 SecretKey / SM2 私钥并保存。",
                    color = FCColor.TextLo,
                    fontSize = 9.sp,
                    lineHeight = 13.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // —— 上链方式 ——
        Text("上链方式", color = FCColor.TextDim, fontSize = 9.sp, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp))
        GlassCard(padding = FCSpace.x4) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (app.settings.autoAnchor) "自动上链" else "手动上链",
                        color = FCColor.TextHi,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (app.settings.autoAnchor)
                            "结束录制、算完摘要后立即自动入台账并排队上链，无需确认"
                        else
                            "结束录制后进入证据确认页，由你手动点击入队上链",
                        color = FCColor.TextLo,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(top = 3.dp),
                        lineHeight = 13.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                FCSwitch(
                    checked = app.settings.autoAnchor,
                    onChange = { app.saveSettings(app.settings.copy(autoAnchor = it)) }
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // —— 加密取证（默认关闭；旧证据 fmt=F0 仍走原路径）——
        GlassCard(padding = FCSpace.x4) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (app.settings.encRecord) "加密取证（E1）已开启" else "加密取证（E1）已关闭",
                        color = FCColor.TextHi,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (app.settings.encRecord)
                            "录制期即 AES-256-GCM 分片加密，明文不落盘；DEK 由本机 Keystore 包裹（StrongBox 优先、降级 TEE）"
                        else
                            "关闭时沿用旧路径：明文 mp4/m4a 存入沙盒；历史 F0 证据不受影响",
                        color = FCColor.TextLo,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(top = 3.dp),
                        lineHeight = 13.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                FCSwitch(
                    checked = app.settings.encRecord,
                    onChange = { app.saveSettings(app.settings.copy(encRecord = it)) }
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Text("至信链配置", color = FCColor.TextDim, fontSize = 9.sp, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp))
        GlassCard(padding = FCSpace.x4) {
            FCInputLabel("SecretId")
            FCInput(value = secretId, onValueChange = { secretId = it }, placeholder = "腾讯云 SecretId")
            Spacer(Modifier.height(10.dp))
            FCInputLabel("SecretKey")
            FCInput(value = secretKey, onValueChange = { secretKey = it }, placeholder = "腾讯云 SecretKey")
            Spacer(Modifier.height(12.dp))
            FCButton(
                "保存配置",
                height = 42.dp,
                onClick = { app.saveSettings(app.settings.copy(secretId = secretId.trim(), secretKey = secretKey.trim())) }
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FCRadius.xs))
                    .clickable {
                        if (!app.testingConn) app.testConnection(secretId, secretKey)
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LineIcon(FCIcons.Retry, FCColor.Brand2, 13.dp)
                Spacer(Modifier.width(7.dp))
                Text(
                    if (app.testingConn) "正在直连 open.zxchain.qq.com 测试…" else "测试连接（真实签名请求）",
                    color = FCColor.Brand2,
                    fontSize = 10.sp
                )
            }
            app.connResult?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    color = if (it.startsWith("失败")) FCColor.Danger else FCColor.Ok,
                    fontSize = 8.5.sp,
                    fontFamily = MonoFamily,
                    lineHeight = 13.sp
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("SM2 密钥与链账户", color = FCColor.TextDim, fontSize = 9.sp, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp))

        GlassCard(padding = FCSpace.x4) {
            val hasKey = app.settings.pubKeyPem.isNotEmpty()
            Row(verticalAlignment = Alignment.CenterVertically) {
                LineIcon(FCIcons.Key, FCColor.TextLo, 15.dp)
                Spacer(Modifier.width(9.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("链账户公钥", color = FCColor.TextMid, fontSize = 11.sp)
                    Text(
                        if (hasKey) "已导入 · SM2 · PKCS#8" else "未导入 · 存证需先配置密钥",
                        color = FCColor.TextDim,
                        fontSize = 8.sp,
                        fontFamily = MonoFamily,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                FCChip(
                    if (hasKey) "已就绪" else "缺失",
                    color = if (hasKey) FCColor.Ok else FCColor.Danger,
                    background = if (hasKey) FCColor.OkSoft else FCColor.DangerSoft,
                    borderColor = (if (hasKey) FCColor.Ok else FCColor.Danger).copy(alpha = 0.22f),
                    fontSize = 8
                )
            }
            if (hasKey) {
                val addr = runCatching { com.forensic.camera.crypto.GmCrypto.addressFromPubPem(app.settings.pubKeyPem) }.getOrDefault("?")
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FCRadius.xs))
                        .background(FCColor.CodeBg)
                        .border(1.dp, FCColor.LineSoft, RoundedCornerShape(FCRadius.xs))
                        .clickable {
                            clipboard.setText(AnnotatedString(addr))
                            app.showToast("链账户地址已复制", ToastKind.Ok)
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("链账户地址", color = FCColor.TextDim, fontSize = 8.sp)
                        Text(addr, color = FCColor.TextMid, fontSize = 9.5.sp, fontFamily = MonoFamily, modifier = Modifier.padding(top = 2.dp))
                    }
                    LineIcon(FCIcons.Copy, FCColor.TextDim, 12.dp)
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { app.keyExpanded = !app.keyExpanded }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (app.keyExpanded) "收起公钥 PEM" else "查看公钥 PEM", color = FCColor.Brand2, fontSize = 9.sp)
                    Spacer(Modifier.width(4.dp))
                    LineIcon(FCIcons.ChevronRight, FCColor.TextDim, 10.dp)
                }
                AnimatedVisibility(visible = app.keyExpanded) {
                    Column(modifier = Modifier.padding(top = 4.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(FCRadius.xs))
                                .background(FCColor.CodeBg)
                                .border(1.dp, FCColor.LineSoft, RoundedCornerShape(FCRadius.xs))
                                .padding(10.dp)
                        ) {
                            Text(
                                app.settings.pubKeyPem,
                                color = FCColor.TextLo,
                                fontSize = 7.5.sp,
                                lineHeight = 12.sp,
                                fontFamily = MonoFamily
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            DividerLine()
            Spacer(Modifier.height(10.dp))

            FCInputLabel("私钥 PEM 导入（PKCS#8，仅存本机）")
            FCInput(
                value = privInput,
                onValueChange = { privInput = it; keyError = null },
                placeholder = "-----BEGIN PRIVATE KEY-----\n…",
                singleLine = false,
                minHeight = 74
            )
            keyError?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = FCColor.Danger, fontSize = 8.5.sp)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    FCButton(
                        "导入私钥",
                        kind = ButtonKind.Ghost,
                        height = 40.dp,
                        onClick = {
                            val res = app.importPrivKeyPem(privInput)
                            if (res == null) {
                                keyError = "私钥解析失败：请粘贴完整 PKCS#8 PEM（BEGIN PRIVATE KEY）"
                            } else {
                                keyError = null
                                pendingPriv = privInput.trim()
                            }
                        }
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    FCButton(
                        "生成密钥对",
                        kind = ButtonKind.Ghost,
                        height = 40.dp,
                        onClick = {
                            val (pub, priv) = app.generateKeyPair()
                            privInput = priv
                            pendingPriv = priv
                            keyError = null
                            app.showToast("已生成 SM2 密钥对，请点击保存配置", ToastKind.Info)
                        }
                    )
                }
            }
            pendingPriv?.let { p ->
                Spacer(Modifier.height(10.dp))
                FCButton(
                    "确认并保存密钥",
                    kind = ButtonKind.Ok,
                    height = 42.dp,
                    onClick = {
                        val pub = app.importPrivKeyPem(p)?.first
                        if (pub != null) {
                            app.saveSettings(app.settings.copy(privKeyPem = p, pubKeyPem = pub))
                            pendingPriv = null
                            privInput = ""
                        } else {
                            keyError = "保存失败：私钥无法解析"
                        }
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "公钥需与至信链控制台注册的链账户公钥字符串完全一致，否则存证会被拒绝",
                color = FCColor.TextDim,
                fontSize = 8.sp,
                lineHeight = 12.sp
            )
        }

        Spacer(Modifier.height(14.dp))
        Text("台账维护", color = FCColor.TextDim, fontSize = 9.sp, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp))

        GlassCard(padding = FCSpace.x4) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FCRadius.xs))
                    .clickable { app.exportLedgerJson() }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LineIcon(FCIcons.Download, FCColor.TextLo, 14.dp)
                Spacer(Modifier.width(9.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("导出台账 JSON", color = FCColor.TextMid, fontSize = 11.sp)
                    Text("写入应用私有目录（release 版不可直接访问；请用下方分享）", color = FCColor.TextDim, fontSize = 8.sp, modifier = Modifier.padding(top = 2.dp))
                }
                LineIcon(FCIcons.ChevronRight, FCColor.TextDim, 12.dp)
            }
            DividerLine()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FCRadius.xs))
                    .clickable { app.shareLedgerJson() }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LineIcon(FCIcons.Share, FCColor.TextLo, 14.dp)
                Spacer(Modifier.width(9.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("分享台账 JSON", color = FCColor.TextMid, fontSize = 11.sp)
                    Text("通过系统分享发送到电脑/网盘/邮件（含链上凭证）", color = FCColor.TextDim, fontSize = 8.sp, modifier = Modifier.padding(top = 2.dp))
                }
                LineIcon(FCIcons.ChevronRight, FCColor.TextDim, 12.dp)
            }
            DividerLine()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("锚定重试", color = FCColor.TextMid, fontSize = 11.sp)
                    Text("恢复待处理/失败记录的直连上链队列", color = FCColor.TextDim, fontSize = 8.sp, modifier = Modifier.padding(top = 2.dp))
                }
                FCSwitch(checked = true, onChange = {
                    if (it) {
                        com.forensic.camera.anchor.AnchorManager.ensureRunning(app.ctx)
                        app.showToast("队列已恢复运行", ToastKind.Info)
                    }
                })
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("权限说明", color = FCColor.TextDim, fontSize = 9.sp, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp))

        GlassCard(padding = FCSpace.x4) {
            PermissionRow(FCIcons.VideoLine, "相机", "录制视频证据（首次录制时申请）")
            DividerLine()
            PermissionRow(FCIcons.MicRound, "麦克风", "录制现场音频与视频同期声（首次录制时申请）")
            DividerLine()
            PermissionRow(FCIcons.Chain, "网络", "直连至信链提交摘要与查询核验结果")
            DividerLine()
            PermissionRow(FCIcons.Folder, "存储", "Android 10+ 经 MediaStore 导出相册副本")
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "取证摄像头 v${app.appVersion} · 无后端直连至信链 open.zxchain.qq.com · 台账与凭据仅存本机",
            color = FCColor.TextDim,
            fontSize = 8.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp)
        )
    }
}

@Composable
private fun FCInputLabel(text: String) {
    Text(text, color = FCColor.TextLo, fontSize = 9.sp, modifier = Modifier.padding(bottom = 5.dp))
}

@Composable
private fun FCInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = true,
    minHeight: Int = 42
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(minHeight.dp)
            .clip(RoundedCornerShape(FCRadius.sm))
            .background(FCColor.InputBg)
            .border(1.dp, FCColor.LineSoft, RoundedCornerShape(FCRadius.sm))
            .padding(horizontal = 11.dp, vertical = 9.dp)
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = FCColor.TextDim, fontSize = 9.5.sp, fontFamily = MonoFamily, lineHeight = 13.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(
                color = FCColor.TextHi,
                fontSize = 9.5.sp,
                fontFamily = MonoFamily,
                lineHeight = 13.sp
            ),
            cursorBrush = SolidColor(FCColor.Brand2),
            singleLine = singleLine,
            keyboardOptions = if (singleLine) KeyboardOptions(keyboardType = KeyboardType.Text) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PermissionRow(icon: com.forensic.capture.ui.components.IconSpec, title: String, desc: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(FCRadius.xs))
                .background(FCColor.InputBg),
            contentAlignment = Alignment.Center
        ) {
            LineIcon(icon, FCColor.TextLo, 13.dp)
        }
        Spacer(Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = FCColor.TextMid, fontSize = 11.sp)
            Text(desc, color = FCColor.TextDim, fontSize = 8.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun DividerLine() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(FCColor.LineSoft)
    )
}
