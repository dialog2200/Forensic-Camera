package com.forensic.camera.enc

/**
 * 原生封装/加密/核验入口（libfcenc.so，C++ 实现）。
 *
 * 职责边界：
 *  - 原生侧负责 fMP4 分片组装、AES-256-GCM 加密、段链、落盘与核验；
 *  - Kotlin 侧只负责取码流（MediaCodec）、管密钥（Keystore）与呈现。
 * Kotlin 侧不再保留任何 fMP4 拼装或 GCM 实现，避免两套逻辑漂移。
 */
object NativeEnc {

    private var loadError: String? = null

    init {
        loadError = runCatching { System.loadLibrary("fcenc") }.exceptionOrNull()?.message
    }

    /** 原生库是否可用（不可用时上层退回明文 F0 路径并提示）。 */
    val available: Boolean get() = loadError == null

    val loadFailure: String? get() = loadError

    data class TrackSpec(
        val id: Int,
        val kind: String,              // video | audio
        val width: Int = 0,
        val height: Int = 0,
        val sampleRate: Int = 0,
        val channels: Int = 0,
        val csdBase64: String = ""     // video: Annex-B SPS+PPS；audio: AudioSpecificConfig
    )

    data class Summary(
        val segCount: Int,
        val segRoot: String,
        val cipherSha: String,
        val durationMs: Long,
        val error: String
    )

    data class Inspect(
        val keyAvailable: Boolean,
        val segCount: Int,
        val badSeqs: List<Int>,
        val missingSeqs: List<Int>,
        val chainOk: Boolean,
        val root: String,
        val plainSha: String,
        val interrupted: Boolean,
        val note: String
    ) {
        /** 结构完整：有密钥、无坏段、无缺段、段链自洽。 */
        val intact: Boolean
            get() = keyAvailable && segCount > 0 && badSeqs.isEmpty() &&
                    missingSeqs.isEmpty() && chainOk
    }

    // ---------------- JNI 声明 ----------------

    @JvmStatic private external fun nativeSelfTest(): String
    @JvmStatic private external fun nativeBuildInit(tracks: String): ByteArray
    @JvmStatic private external fun nativeAvcc(data: ByteArray): ByteArray
    @JvmStatic private external fun nativeCreate(
        dir: String, dek: ByteArray, tracks: String, segMs: Long): Long
    @JvmStatic private external fun nativeBegin(handle: Long): Boolean
    @JvmStatic private external fun nativePush(
        handle: Long, trackId: Int, data: ByteArray, ptsUs: Long, key: Boolean): Boolean
    @JvmStatic private external fun nativeFinish(handle: Long): String
    @JvmStatic private external fun nativeRelease(handle: Long)
    @JvmStatic private external fun nativeInspect(
        dir: String, dek: ByteArray, expectPlainSha: String, withPlain: Boolean): String
    @JvmStatic private external fun nativeRestore(dir: String, dek: ByteArray, dest: String): Long
    @JvmStatic private external fun nativePlainHash(dir: String, dek: ByteArray): String
    @JvmStatic private external fun nativeSha256File(path: String): String

    // ---------------- Kotlin 友好封装 ----------------

    fun selfTest(): String = runCatching { nativeSelfTest() }.getOrDefault("原生库不可用")

    fun sha256File(path: String): String = runCatching { nativeSha256File(path) }.getOrDefault("")

    fun avcc(data: ByteArray): ByteArray = runCatching { nativeAvcc(data) }.getOrDefault(data)

    fun buildInit(tracks: List<TrackSpec>): ByteArray =
        runCatching { nativeBuildInit(spec(tracks)) }.getOrDefault(ByteArray(0))

    fun plainHash(dir: String, dek: ByteArray): String =
        runCatching { nativePlainHash(dir, dek) }.getOrDefault("")

    fun inspect(dir: String, dek: ByteArray?, expectPlainSha: String = ""): Inspect =
        parseInspect(runCatching {
            nativeInspect(dir, dek ?: ByteArray(0), expectPlainSha, dek != null)
        }.getOrDefault(""))

    fun restore(dir: String, dek: ByteArray?, dest: String): Long =
        runCatching { nativeRestore(dir, dek ?: ByteArray(0), dest) }.getOrDefault(-1L)

    /** 段写盘器：create → begin → push… → finish。 */
    class Writer private constructor(private var handle: Long) {

        fun begin(): Boolean = handle != 0L && runCatching { nativeBegin(handle) }.getOrDefault(false)

        fun push(trackId: Int, data: ByteArray, ptsUs: Long, key: Boolean): Boolean =
            handle != 0L && runCatching { nativePush(handle, trackId, data, ptsUs, key) }
                .getOrDefault(false)

        fun finish(): Summary = parseSummary(
            if (handle == 0L) "segCount=0;segRoot=;cipherSha=;durationMs=0;error=未创建"
            else runCatching { nativeFinish(handle) }.getOrDefault("error=原生调用失败")
        ).also { handle = 0 }

        fun release() {
            if (handle != 0L) {
                runCatching { nativeRelease(handle) }
                handle = 0
            }
        }

        companion object {
            fun create(dir: String, dek: ByteArray, tracks: List<TrackSpec>, segMs: Long): Writer? {
                if (!available) return null
                val h = runCatching { nativeCreate(dir, dek, spec(tracks), segMs) }.getOrDefault(0L)
                return if (h == 0L) null else Writer(h)
            }
        }
    }

    private fun spec(tracks: List<TrackSpec>): String = tracks.joinToString(";") {
        "${it.id}|${it.kind}|${it.width}|${it.height}|${it.sampleRate}|${it.channels}|${it.csdBase64}"
    }

    private fun parseSummary(s: String): Summary {
        val m = kv(s)
        return Summary(
            segCount = m["segCount"]?.toIntOrNull() ?: 0,
            segRoot = m["segRoot"] ?: "",
            cipherSha = m["cipherSha"] ?: "",
            durationMs = m["durationMs"]?.toLongOrNull() ?: 0L,
            error = m["error"] ?: ""
        )
    }

    private fun parseInspect(s: String): Inspect {
        val m = kv(s)
        fun ints(v: String?) = v?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
        return Inspect(
            keyAvailable = m["key"] == "1",
            segCount = m["segs"]?.toIntOrNull() ?: 0,
            badSeqs = ints(m["bad"]),
            missingSeqs = ints(m["missing"]),
            chainOk = m["chain"] == "1",
            root = m["root"] ?: "",
            plainSha = m["plain"] ?: "",
            interrupted = m["interrupted"] == "1",
            note = m["note"] ?: ""
        )
    }

    private fun kv(s: String): Map<String, String> =
        s.split(';').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
        }.toMap()
}
