package com.forensic.camera.data

import org.json.JSONObject

enum class AnchorStatus { PENDING, ANCHORED, FAILED }

/**
 * 证据记录。
 * 上链锚定值 anchorHash = SHA-256(复合串)，复合串规范格式见 canonical()：
 *   V1|file=<视频文件SHA-256>|dev=<设备>|ts=<拍摄时间戳ms>|dur=<时长ms>|apk=<当前APK文件SHA-256>
 * 不含 GPS。APK 由已锚定源码构建，源码哈希经公证链第1/2步的链上 extendInfo 传递绑定。
 */
data class Evidence(
    val id: String,
    val name: String,
    val fileName: String,          // 相对 filesDir/media 的文件名
    val type: String,              // video | audio
    val fileSha256: String,        // 视频/音频文件本体 SHA-256
    val anchorHash: String,        // 复合 SHA-256（上链锚定值）
    val createdAt: Long,           // epoch millis
    val durationMs: Long,
    val device: String,            // 品牌/型号/Android 版本
    val appVersion: String,
    val apkSha256: String,         // 拍摄时安装的 APK 文件 SHA-256
    // ---- v1.3.0 T2 出处证明（Keystore attestation）----
    val attSha256: String = "",    // SHA-256(attBlob) hex；上链 extendInfo 前缀 att=<hex> 与之比对
    val attLevel: String = "N",    // H 硬件 | S 软件 | N 无
    val attVc: Int = 0,            // 拍摄时 versionCode
    val attBlob: String = "",      // base64(NO_WRAP)：[1B证书数][cert DER..][ECDSA签名 DER]
    // ---- v1.3.1 导出到本地相册 ----
    var galleryUri: String = "",   // 相册副本 uri（DCIM/ForensicCamera，文件名=UUID）；空=未导出。不参与 anchorHash/V1 复合串
    var galleryName: String = "",  // 相册副本文件名（首次导出后冻结，重复导出保持同名可追踪）。不参与 anchorHash/V1 复合串
    // ---- v1.3.5 三方核验结果（本地展示用）----
    var vSandbox: String = "",     // 沙盒原始文件核验：一致/不一致/缺失
    var vGallery: String = "",     // 相册导出副本核验：一致/不一致/缺失/未导出
    var vChain: String = "",       // 至信链存证核验：已存证一致/与链上不一致/链上未存证/未配置凭据/查询失败
    // ---- v1.3.4 树形证据目录（纯本地展示元数据，不参与 anchorHash/V1 复合串与链上 extendInfo）----
    var alias: String = "",        // 备用名：优先于原始文件名展示
    var notesHtml: String = "",    // 富文本备注（Html.toHtml 存储，fromHtml 渲染）
    var nodeId: String = "",       // 挂载的目录节点 id；空=未分类
    var status: AnchorStatus = AnchorStatus.PENDING,
    var evId: String = "",
    var txId: String = "",
    var blockHeight: Long = 0,
    var txTime: Long = 0,
    var anchorError: String = "",
    var lastVerify: String = "",   // 最近一次链上核验结论
    var lastVerifyAt: Long = 0,
    // ---- v3.4.0 录制期加密（fmt=E1）----
    // F0：旧证据，明文 mp4/m4a 原路径；E1：fileName 指向 filesDir/media 下的密文目录
    var fmt: String = "F0"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("fileName", fileName); put("type", type)
        put("fileSha256", fileSha256); put("anchorHash", anchorHash)
        put("createdAt", createdAt); put("durationMs", durationMs)
        put("device", device); put("appVersion", appVersion); put("apkSha256", apkSha256)
        put("attSha256", attSha256); put("attLevel", attLevel); put("attVc", attVc); put("attBlob", attBlob)
        put("galleryUri", galleryUri); put("galleryName", galleryName)
        put("vSandbox", vSandbox); put("vGallery", vGallery); put("vChain", vChain)
        put("alias", alias); put("notesHtml", notesHtml); put("nodeId", nodeId)
        put("status", status.name); put("evId", evId); put("txId", txId)
        put("blockHeight", blockHeight); put("txTime", txTime)
        put("anchorError", anchorError); put("lastVerify", lastVerify); put("lastVerifyAt", lastVerifyAt)
        put("fmt", fmt)
    }

    companion object {
        /** 复合串规范格式（核验与锚定必须逐字符一致）。 */
        fun canonical(fileSha256: String, device: String, createdAt: Long,
                      durationMs: Long, apkSha256: String): String =
            "V1|file=$fileSha256|dev=$device|ts=$createdAt|dur=$durationMs|apk=$apkSha256"

        fun fromJson(o: JSONObject): Evidence = Evidence(
            id = o.getString("id"),
            name = o.getString("name"),
            fileName = o.getString("fileName"),
            type = o.getString("type"),
            fileSha256 = o.getString("fileSha256"),
            anchorHash = o.getString("anchorHash"),
            createdAt = o.getLong("createdAt"),
            durationMs = o.optLong("durationMs"),
            device = o.optString("device"),
            appVersion = o.optString("appVersion"),
            apkSha256 = o.optString("apkSha256"),
            attSha256 = o.optString("attSha256"),
            attLevel = o.optString("attLevel", "N"),
            attVc = o.optInt("attVc"),
            attBlob = o.optString("attBlob"),
            galleryUri = o.optString("galleryUri"),
            galleryName = o.optString("galleryName"),
            vSandbox = o.optString("vSandbox"),
            vGallery = o.optString("vGallery"),
            vChain = o.optString("vChain"),
            alias = o.optString("alias"),
            notesHtml = o.optString("notesHtml"),
            nodeId = o.optString("nodeId"),
            status = runCatching { AnchorStatus.valueOf(o.optString("status", "PENDING")) }
                .getOrDefault(AnchorStatus.PENDING),
            evId = o.optString("evId"),
            txId = o.optString("txId"),
            blockHeight = o.optLong("blockHeight"),
            txTime = o.optLong("txTime"),
            anchorError = o.optString("anchorError"),
            lastVerify = o.optString("lastVerify"),
            lastVerifyAt = o.optLong("lastVerifyAt"),
            fmt = o.optString("fmt", "F0")
        )
    }

    /**
     * 上链 extendInfo（<=1024 字符，签名与请求体必须完全一致）。
     * v1.3.0：出具证明时前置紧凑绑定串 "att=<sha256hex>;lvl=<H|S|N>;vc=<versionCode>;"，
     * 前缀在最前，即使整体被截断到 1024 也保证 attestation 绑定不丢失；JSON 部分与 v1.2.0 相同。
     */
    fun buildExtendInfo(): String {
        val o = JSONObject()
        o.put("n", name)
        o.put("t", type)
        o.put("f", fileSha256)
        o.put("a", apkSha256)
        o.put("d", device)
        o.put("v", appVersion)
        o.put("ts", createdAt)
        o.put("dur", durationMs)
        var s = o.toString()
        if (attSha256.isNotEmpty()) {
            s = "att=$attSha256;lvl=$attLevel;vc=$attVc;" + s
        }
        if (s.length > 1024) s = s.substring(0, 1024)
        return s
    }
}
