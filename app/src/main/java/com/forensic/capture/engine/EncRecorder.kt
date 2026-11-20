package com.forensic.capture.engine

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Range
import android.util.Size
import android.view.Surface
import com.forensic.camera.enc.EncFormat
import com.forensic.camera.enc.EncKeyVault
import com.forensic.camera.enc.NativeEnc
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 录制期加密引擎（fmt=E1）：Camera2 + MediaCodec 双路编码，样本直接进原生封装/加密，
 * 明文 YUV/PCM 与明文 mp4 均不落盘。
 *
 * 视频：相机 → MediaCodec(avc) 输入 Surface → 原生分片封装 → AES-256-GCM → seg_*.enc
 * 音频：AudioRecord → MediaCodec(aac) → 同一段写盘器（按时间戳切段）
 *
 * 已知边界（真机需确认，PC 侧无法覆盖）：
 *  - 未写入旋转矩阵（tkhd matrix），竖屏取景下播放方向可能不理想；
 *  - 各厂商编码器输出的 csd 形式（Annex-B / 长度前缀）已做归一化，但仍需真机回归；
 *  - 段内以首个视频关键帧为界，EOS 时刻的尾帧归属需真机核对音画同步。
 */
class EncRecorder(private val ctx: Context) {

    companion object {
        const val VIDEO_ID = 1
        const val AUDIO_ID = 2
        private const val SEG_MS = 2000L
    }

    var lastError: String = ""
        private set

    private val running = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val lock = Any()

    @Volatile private var dir: File? = null
    private var dek: ByteArray? = null
    private var sm2PubPem: String = ""
    private var withAudio = true
    private var sampleRate = 44100
    private var channels = 1

    private var cameraManager: CameraManager? = null
    private var cameraId: String = ""
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    private var encoderSurface: Surface? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null

    private var videoW = 0
    private var videoH = 0
    private var videoCsd: ByteArray? = null
    private var audioCsd: ByteArray? = null
    private var videoPtsBase = -1L

    private var writer: NativeEnc.Writer? = null
    private var tracks: List<NativeEnc.TrackSpec> = emptyList()
    private var startTs = 0L

    private data class Pending(val trackId: Int, val data: ByteArray, val ptsUs: Long, val key: Boolean)
    private val buffered = ArrayList<Pending>()

    private val cameraThread = HandlerThread("fc-cam").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val videoThread = HandlerThread("fc-venc").apply { start() }
    private val videoHandler = Handler(videoThread.looper)
    private val audioThread = HandlerThread("fc-aenc").apply { start() }
    private val audioHandler = Handler(audioThread.looper)

    val isRecording: Boolean get() = running.get()

    /** 已写入沙盒的密文字节数（含 init/manifest/meta）。调用方应在 IO 线程执行。 */
    fun bytesWritten(): Long = dir?.listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * 开始加密录制。[withCamera] 为 false 时只录音频（纯音轨，按时间戳切段）。
     * 失败返回 false，原因见 [lastError]。
     */
    fun start(
        dir: File,
        dek: ByteArray,
        sm2PubPem: String,
        withCamera: Boolean,
        withAudio: Boolean,
        preview: Surface?
    ): Boolean {
        lastError = ""
        if (!NativeEnc.available) {
            lastError = "原生库未加载：${NativeEnc.loadFailure}"
            return false
        }
        if (dek.size != 32) {
            lastError = "DEK 长度非法"
            return false
        }
        this.dir = dir
        this.dek = dek.copyOf()
        this.sm2PubPem = sm2PubPem
        this.withAudio = withAudio
        startTs = System.currentTimeMillis()
        if (!dir.exists() && !dir.mkdirs()) {
            lastError = "无法创建密文目录"
            return false
        }
        running.set(true)

        if (withCamera && !setupVideo()) {
            release()
            return false
        }
        if (withAudio && !setupAudio()) {
            release()
            return false
        }
        if (withCamera) {
            cameraHandler.post { openCamera(preview) }
        } else if (!withAudio) {
            lastError = "至少需要一路音视频轨"
            release()
            return false
        }
        return true
    }

    /** 停止并收尾；成功返回待入库条目，失败返回 null（原因见 [lastError]）。 */
    fun stop(): PendingCapture? {
        if (!running.getAndSet(false)) return null
        val wasVideo = videoCodec != null

        if (wasVideo) runCatching { videoCodec?.signalEndOfInputStream() }
        runCatching { videoThread.join(4000) }
        runCatching { audioThread.join(4000) }

        closeCamera()
        closeAudio()

        val w = writer
        if (w == null) {
            if (lastError.isEmpty()) lastError = "未产生任何可封装样本"
            release()
            return null
        }
        val sum = w.finish()
        writer = null
        release()

        if (sum.error.isNotEmpty()) {
            lastError = sum.error
            return null
        }
        val d = dir ?: return null
        val plainSha = NativeEnc.plainHash(d.absolutePath, dek ?: ByteArray(0))
        if (plainSha.isEmpty()) {
            lastError = "还原比对摘要计算失败"
            return null
        }
        // DEK 必须成功包裹才能落清单：否则这条证据永远无法还原，宁可当场失败也不静默入库。
        val wrapped = EncKeyVault.wrapDek(ctx, dek ?: ByteArray(0))
        if (wrapped == null) {
            lastError = "DEK Keystore 包裹失败（${EncKeyVault.lastDiag.ifEmpty { "原因未知" }}）：" +
                "密文将无法在本机还原，本次录制作废，请重试"
            return null
        }
        // #region debug-point keystore-key-lost：包裹成功后立即自检回环（区分"从未持久化"与"事后丢失"）
        val curDek = dek
        val chk = EncKeyVault.unwrapDek(wrapped.b64)
        val roundtrip = curDek != null && chk != null && chk.contentEquals(curDek)
        android.util.Log.d("FCDBG", "stop:roundtrip=$roundtrip level=${wrapped.level} diag=${EncKeyVault.lastDiag}")
        chk?.fill(0)
        if (!roundtrip) {
            lastError = "DEK 包裹回环校验失败（${EncKeyVault.lastDiag.ifEmpty { "解密结果不符" }}）：" +
                "本次录制作废，请重试"
            return null
        }
        // #endregion
        writeMeta(d, sum, plainSha, wrapped)
        d.resolve("open.lock").delete()

        val result = PendingCapture(
            name = d.name,
            file = d,
            type = if (wasVideo) "video" else "audio",
            startTs = startTs,
            durationMs = sum.durationMs,
            fmt = EncFormat.E1,
            plainSha256 = plainSha
        )
        dek?.fill(0)
        return result
    }

    // ---------------- 视频 ----------------

    private fun setupVideo(): Boolean {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (mgr == null) {
            lastError = "相机服务不可用"
            return false
        }
        cameraManager = mgr
        val id = mgr.cameraIdList.firstOrNull { cid ->
            runCatching {
                mgr.getCameraCharacteristics(cid).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }.getOrDefault(false)
        } ?: mgr.cameraIdList.firstOrNull()
        if (id == null) {
            lastError = "未找到可用相机"
            return false
        }
        cameraId = id
        val ch = runCatching { mgr.getCameraCharacteristics(id) }.getOrNull()
            ?: run { lastError = "读取相机参数失败"; return false }
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: run { lastError = "相机缺少流配置"; return false }
        val sizes = map.getOutputSizes(MediaCodec::class.java)
            ?: run { lastError = "相机不支持编码 Surface"; return false }
        val size = pickSize(sizes)
        videoW = size.width
        videoH = size.height

        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoW, videoH).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = runCatching { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }
            .getOrElse { lastError = "无法创建 H.264 编码器"; return false }
        runCatching {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = codec.createInputSurface()
            codec.start()
        }.onFailure {
            runCatching { codec.release() }
            lastError = "H.264 编码器配置失败：${it.message}"
            return false
        }
        videoCodec = codec
        videoHandler.post { drainVideo(codec) }
        return true
    }

    private fun pickSize(sizes: Array<Size>): Size {
        val ladder = listOf(1280 to 720, 1920 to 1080, 960 to 540, 640 to 360)
        for ((w, h) in ladder) {
            sizes.firstOrNull { it.width == w && it.height == h }?.let { return it }
        }
        return sizes.filter { it.width * it.height <= 1920 * 1080 }.maxByOrNull {
            it.width * it.height
        } ?: sizes.maxByOrNull { it.width * it.height } ?: Size(640, 480)
    }

    private fun openCamera(preview: Surface?) {
        val mgr = cameraManager ?: return
        try {
            mgr.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (!running.get()) {
                        device.close()
                        return
                    }
                    camera = device
                    createSession(device, preview)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    fail("相机连接被中断")
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    fail("相机错误码 $error")
                }
            }, cameraHandler)
        } catch (e: SecurityException) {
            fail("缺少相机权限")
        } catch (e: Exception) {
            fail("打开相机失败：${e.message}")
        }
    }

    private fun createSession(device: CameraDevice, preview: Surface?) {
        val es = encoderSurface ?: run { fail("编码器 Surface 缺失"); return }
        val outputs = ArrayList<Surface>(2)
        outputs.add(es)
        if (preview != null && preview.isValid) outputs.add(preview)
        runCatching {
            device.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    runCatching {
                        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            outputs.forEach { addTarget(it) }
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                            )
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                        }.build()
                        s.setRepeatingRequest(req, null, cameraHandler)
                    }.onFailure { fail("启动预览失败：${it.message}") }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    fail("相机会话配置失败（可能不支持双路输出）")
                }
            }, cameraHandler)
        }.onFailure { fail("创建相机会话失败：${it.message}") }
    }

    private fun drainVideo(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = try {
                codec.dequeueOutputBuffer(info, 10_000)
            } catch (e: Exception) {
                break
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!running.get()) break
                continue
            }
            if (idx < 0) continue
            var eos = false
            try {
                val isCsd = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size > 0) {
                    val buf = codec.getOutputBuffer(idx)
                    if (buf != null) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        buf.get(data)
                        if (isCsd) {
                            videoCsd = toAnnexB(data)
                            maybeCreateWriter()
                        } else if (!eos) {
                            if (videoPtsBase < 0) videoPtsBase = info.presentationTimeUs
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            feed(VIDEO_ID, data, info.presentationTimeUs - videoPtsBase, key)
                        }
                    }
                }
            } catch (e: Exception) {
                eos = true
            } finally {
                runCatching { codec.releaseOutputBuffer(idx, false) }
            }
            if (eos) break
        }
    }

    // ---------------- 音频 ----------------

    private fun setupAudio(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            lastError = "麦克风参数不受支持"
            return false
        }
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, 16384)
            )
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec?.release() }
            lastError = "麦克风不可用"
            return false
        }
        val fmt = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = runCatching { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC) }
            .getOrElse { rec.release(); lastError = "无法创建 AAC 编码器"; return false }
        runCatching {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            rec.startRecording()
        }.onFailure {
            runCatching { codec.release() }
            rec.release()
            lastError = "AAC 编码器启动失败：${it.message}"
            return false
        }
        audioRecord = rec
        audioCodec = codec
        audioHandler.post { drainAudio(codec, rec) }
        return true
    }

    private fun drainAudio(codec: MediaCodec, rec: AudioRecord) {
        val info = MediaCodec.BufferInfo()
        val pcm = ByteArray(4096)
        var pts = 0L
        var eosQueued = false
        while (true) {
            val alive = running.get()
            if (alive) {
                val inIdx = runCatching { codec.dequeueInputBuffer(5_000) }.getOrDefault(-1)
                if (inIdx >= 0) {
                    val ib = codec.getInputBuffer(inIdx)
                    val n = if (ib != null) {
                        ib.clear()
                        rec.read(pcm, 0, minOf(pcm.size, ib.capacity()))
                    } else 0
                    if (n > 0 && ib != null) {
                        ib.put(pcm, 0, n)
                        codec.queueInputBuffer(inIdx, 0, n, pts, 0)
                        pts += (n / (2 * channels)).toLong() * 1_000_000L / sampleRate
                    } else {
                        codec.queueInputBuffer(inIdx, 0, 0, pts, 0)
                    }
                }
            } else if (!eosQueued) {
                val inIdx = runCatching { codec.dequeueInputBuffer(10_000) }.getOrDefault(-1)
                if (inIdx >= 0) {
                    codec.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eosQueued = true
                }
            }
            while (true) {
                val outIdx = runCatching { codec.dequeueOutputBuffer(info, 0) }.getOrDefault(-1)
                if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER || outIdx < 0) break
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                val isCsd = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size > 0) {
                    val buf = codec.getOutputBuffer(outIdx)
                    if (buf != null) {
                        val d = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(d)
                        if (isCsd) {
                            audioCsd = d
                            maybeCreateWriter()
                        } else if (!eos) {
                            feed(AUDIO_ID, d, info.presentationTimeUs, true)
                        }
                    }
                }
                runCatching { codec.releaseOutputBuffer(outIdx, false) }
                if (eos) return
            }
            if (!alive && eosQueued) Thread.sleep(5)
        }
    }

    // ---------------- 段写盘 ----------------

    private fun feed(trackId: Int, data: ByteArray, ptsUs: Long, key: Boolean) {
        synchronized(lock) {
            val w = writer
            if (w == null) {
                if (running.get() && buffered.size < 1500) buffered.add(Pending(trackId, data, ptsUs, key))
                return
            }
            w.push(trackId, data, ptsUs, key)
        }
    }

    /** 两路 csd 齐备后才建写盘器（init.mp4 需要完整轨信息），此前样本暂存内存。 */
    private fun maybeCreateWriter() {
        synchronized(lock) {
            if (writer != null || failed.get()) return
            val vcsd = videoCsd
            val acsd = if (withAudio) audioCsd else null
            if (vcsd == null) return
            if (withAudio && acsd == null) return
            val d = dir ?: return
            val k = dek ?: return

            val list = ArrayList<NativeEnc.TrackSpec>(2)
            if (vcsd != null) {
                list.add(NativeEnc.TrackSpec(VIDEO_ID, "video", videoW, videoH, 0, 0, b64(vcsd)))
            }
            if (acsd != null) {
                list.add(NativeEnc.TrackSpec(AUDIO_ID, "audio", 0, 0, sampleRate, channels, b64(acsd)))
            }
            val w = NativeEnc.Writer.create(d.absolutePath, k, list, SEG_MS)
            if (w == null || !w.begin()) {
                fail("原生段写盘器初始化失败")
                return
            }
            writer = w
            tracks = list
            for (p in buffered) w.push(p.trackId, p.data, p.ptsUs, p.key)
            buffered.clear()
        }
    }

    private fun writeMeta(d: File, sum: NativeEnc.Summary, plainSha: String,
                          wrapped: EncKeyVault.Wrapped) {
        val meta = JSONObject().apply {
            put("fmt", EncFormat.E1)
            put("alg", "AES-256-GCM")
            put("segMs", SEG_MS)
            put("segCount", sum.segCount)
            put("segRoot", sum.segRoot)
            put("cipherSha", sum.cipherSha)
            put("durationMs", sum.durationMs)
            put("plainSha", plainSha)
            put("createdAt", startTs)
            put("tracks", EncFormat.tracksToJson(tracks))
            put("dek", wrapped.b64)
            put("level", wrapped.level)
            put("previewNote", "应用内预览临时件写在 cacheDir/preview_plain，不计入沙盒核验")
        }
        EncFormat.writeMeta(d, meta)
        // 公钥托管：私钥持有方可在 PC 上解封 DEK（设置里配置了 SM2 公钥才写）
        runCatching { EncFormat.escrow(d, dek ?: ByteArray(0), sm2PubPem) }
    }

    // ---------------- 收尾 ----------------

    private fun closeCamera() {
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        session = null
        runCatching { camera?.close() }
        camera = null
        runCatching { encoderSurface?.release() }
        encoderSurface = null
        runCatching { videoCodec?.stop() }
        runCatching { videoCodec?.release() }
        videoCodec = null
    }

    private fun closeAudio() {
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { audioCodec?.stop() }
        runCatching { audioCodec?.release() }
        audioCodec = null
    }

    private fun release() {
        running.set(false)
        writer?.release()
        writer = null
        closeCamera()
        closeAudio()
    }

    private fun fail(msg: String) {
        if (lastError.isEmpty()) lastError = msg
        failed.set(true)
        running.set(false)
    }

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    /** 把编码器给出的 csd 归一化为 Annex-B（部分厂商给的是 4 字节长度前缀）。 */
    private fun toAnnexB(csd: ByteArray): ByteArray {
        if (csd.size >= 4 && csd[0] == 0.toByte() && csd[1] == 0.toByte() &&
            (csd[2] == 1.toByte() || (csd[2] == 0.toByte() && csd[3] == 1.toByte()))
        ) return csd
        val out = ByteArrayOutputStream()
        var p = 0
        while (p + 4 <= csd.size) {
            val len = ((csd[p].toInt() and 0xFF) shl 24) or ((csd[p + 1].toInt() and 0xFF) shl 16) or
                    ((csd[p + 2].toInt() and 0xFF) shl 8) or (csd[p + 3].toInt() and 0xFF)
            if (len <= 0 || p + 4 + len > csd.size) return csd
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(csd, p + 4, len)
            p += 4 + len
        }
        return if (out.size() == 0) csd else out.toByteArray()
    }
}
