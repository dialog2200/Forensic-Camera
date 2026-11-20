package com.forensic.capture.engine

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.forensic.camera.data.EvidenceStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一次录制完成后的原始结果（尚未计算摘要）。 */
data class PendingCapture(
    val name: String,
    val file: File,
    val type: String, // video | audio
    val startTs: Long,
    val durationMs: Long,
    val fmt: String = "F0",        // E1：file 是密文目录，需原生还原后才可播放
    val plainSha256: String = ""   // E1：录制时由原生算出的还原后明文摘要
)

/**
 * CameraX / MediaRecorder 录制封装（逻辑移植自工程 A MainActivity 的非 View 部分）。
 * 视频 = VideoRecorder 录 mp4（同期声），音频 = MediaRecorder 录 m4a。
 * 录制连续不中断：仅开始/停止两种状态，保证视频完整性与真实性。
 */
class CaptureController(private val ctx: Context) {

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var mediaRecorder: MediaRecorder? = null
    private var provider: ProcessCameraProvider? = null

    private var modeAudio = false
    private var startTs = 0L
    private var currentName: String? = null
    private var onDone: ((PendingCapture) -> Unit)? = null
    private var onFail: ((String) -> Unit)? = null

    var currentFile: File? = null
        private set

    /**
     * 绑定相机预览与视频用例；surfaceProvider 来自 PreviewView。
     * 每次进入取景页都重新绑定（修复退出再进黑屏：旧实现 bound 短路导致
     * 预览仍绑在已销毁的旧 PreviewView surface 上）。录制中不重绑。
     */
    fun bind(activity: Activity, previewView: PreviewView, lifecycleOwner: LifecycleOwner, onError: (String) -> Unit) {
        if (recording != null || mediaRecorder != null) return
        val existing = provider
        if (existing != null) {
            doBind(existing, previewView, lifecycleOwner, onError)
            return
        }
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            val p = runCatching { future.get() }
                .getOrElse { onError("相机不可用: ${it.message}"); return@addListener }
            provider = p
            doBind(p, previewView, lifecycleOwner, onError)
        }, ContextCompat.getMainExecutor(activity))
    }

    private fun doBind(provider: ProcessCameraProvider, previewView: PreviewView, lifecycleOwner: LifecycleOwner, onError: (String) -> Unit) {
        runCatching {
            provider.unbindAll()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            if (videoCapture == null) {
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD))
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)
            }
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, videoCapture)
        }.onFailure { onError("相机启动失败: ${it.message}") }
    }

    /** 离开取景页时释放相机占用（录制未结束时跳过）。 */
    fun unbind() {
        if (recording != null || mediaRecorder != null) return
        runCatching { provider?.unbindAll() }
    }

    val cameraReady get() = videoCapture != null

    private fun hasAudioPerm() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun newFile(prefix: String, ext: String): Pair<String, File> {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "${prefix}$stamp.$ext"
        return name to File(EvidenceStore.mediaDir(ctx), name)
    }

    /** @return null=已启动；否则为错误信息 */
    fun startVideo(onDone: (PendingCapture) -> Unit, onFail: (String) -> Unit): String? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) return "需要相机权限"
        val vc = videoCapture ?: return "相机未就绪"
        val (name, file) = newFile("VID_", "mp4")
        modeAudio = false
        currentName = name
        currentFile = file
        startTs = System.currentTimeMillis()
        this.onDone = onDone; this.onFail = onFail
        val outputOptions = FileOutputOptions.Builder(file).build()
        recording = vc.output
            .prepareRecording(ctx, outputOptions)
            .apply { if (hasAudioPerm()) withAudioEnabled() }
            .start(ContextCompat.getMainExecutor(ctx)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    recording = null
                    if (event.hasError()) {
                        file.delete()
                        currentFile = null
                        this.onFail?.invoke("录像失败: code=${event.error}")
                    } else {
                        this.onDone?.invoke(PendingCapture(name, file, "video", startTs, activeDurationMs()))
                    }
                }
            }
        return null
    }

    /** @return null=已启动；否则为错误信息 */
    fun startAudio(onDone: (PendingCapture) -> Unit, onFail: (String) -> Unit): String? {
        if (!hasAudioPerm()) return "需要录音权限"
        val (name, file) = newFile("AUD_", "m4a")
        modeAudio = true
        currentName = name
        currentFile = file
        startTs = System.currentTimeMillis()
        this.onDone = onDone; this.onFail = onFail
        val result = runCatching {
            mediaRecorder = (if (Build.VERSION.SDK_INT >= 31)
                MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        }
        if (result.isFailure) {
            currentFile = null
            return "录音启动失败: ${result.exceptionOrNull()?.message}"
        }
        return null
    }

    /** 结束录制；完成回调可能在 Finalize 事件里异步到达。 */
    fun stop() {
        if (modeAudio) {
            val mr = mediaRecorder ?: return
            mediaRecorder = null
            runCatching { mr.stop() }
            mr.release()
            val file = currentFile
            val name = currentName
            if (file != null && name != null && file.exists()) {
                onDone?.invoke(PendingCapture(name, file, "audio", startTs, activeDurationMs()))
            } else {
                onFail?.invoke("录音文件未生成")
            }
            currentFile = null
        } else {
            recording?.stop()
        }
    }

    /** 释放底层资源（Activity 销毁时调用）。 */
    fun release() {
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
        runCatching { recording?.stop() }
        recording = null
        onDone = null
        onFail = null
    }

    private fun activeDurationMs(): Long =
        (System.currentTimeMillis() - startTs).coerceAtLeast(0)
}
