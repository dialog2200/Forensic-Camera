package com.forensic.camera.data

import android.content.Context
import android.os.Build

object DeviceMeta {

    fun describe(): String =
        "${Build.BRAND} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}"

    fun appVersion(ctx: Context): String = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "${pi.versionName}(${pi.longVersionCode})"
    }.getOrDefault("?")

    fun versionCode(ctx: Context): Int = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt()
        else @Suppress("DEPRECATION") pi.versionCode
    }.getOrDefault(0)
}
