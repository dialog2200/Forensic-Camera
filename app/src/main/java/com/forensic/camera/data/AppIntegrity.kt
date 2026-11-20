package com.forensic.camera.data

import android.content.Context
import com.forensic.camera.crypto.GmCrypto
import java.io.File

/** 当前安装 APK 的本体 SHA-256（运行时读取自身 base.apk 计算，进程内缓存）。 */
object AppIntegrity {

    @Volatile private var cached: String? = null

    fun apkSha256(ctx: Context): String {
        cached?.let { return it }
        val apk = File(ctx.applicationInfo.sourceDir)
        return runCatching { GmCrypto.sha256Hex(apk) }.getOrNull()?.also { cached = it } ?: ""
    }
}
