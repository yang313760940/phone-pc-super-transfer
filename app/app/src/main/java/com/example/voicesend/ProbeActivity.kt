package com.example.voicesend

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * 单屏录音发送 App（A 方案）。
 * 录音(MediaRecorder→AAC/m4a) → 自动 multipart 发到电脑 /upload → 显示可复制中文 → 失败重试。
 * 地址可配并记忆；证书 pinning 内置 cert.pem。
 */
class ProbeActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val PREFS = "voicesend"
    private val KEY_URL = "server_url"

    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private lateinit var recBtn: Button
    private lateinit var retryBtn: Button
    private lateinit var copyBtn: Button
    private lateinit var urlInput: EditText
    private lateinit var status: TextView
    private lateinit var resultText: TextView
    private var lastText: String = ""
    private var lastAudio: File? = null
    private var recording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        ensurePermission()
    }

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32)
            layoutParams = LinearLayout.LayoutParams(-1, -1)
        }
        urlInput = EditText(this).apply {
            setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, "https://192.168.0.153:8443") ?: "")
            hint = "服务器地址，如 192.168.0.153:8443"
        }
        recBtn = Button(this).apply { text = "开始录音" }
        retryBtn = Button(this).apply { text = "重试"; isEnabled = false }
        copyBtn = Button(this).apply { text = "复制"; isEnabled = false }
        status = TextView(this).apply { text = "就绪" }
        resultText = TextView(this).apply { setTextIsSelectable(true); text = "" }
        val scroll = ScrollView(this).apply { addView(resultText) }

        root.addView(TextView(this).apply { text = "手机语音 → 电脑转写" })
        root.addView(urlInput)
        root.addView(recBtn)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(copyBtn, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(retryBtn, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)
        root.addView(status)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        recBtn.setOnClickListener { if (recording) stopAndSend() else startRecording() }
        retryBtn.setOnClickListener { lastAudio?.let { upload(it) } }
        copyBtn.setOnClickListener {
            if (lastText.isNotEmpty()) {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("transcript", lastText))
                status.text = "已复制到剪贴板"
            }
        }
        // 保存地址
        urlInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_URL, urlInput.text.toString().trim()).apply()
        }
        return root
    }

    private fun ensurePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            status.text = "需要麦克风权限，请授权后重试"
            ensurePermission(); return
        }
        try {
            val f = File.createTempFile("rec-", ".m4a", cacheDir)
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this)
                    else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(48000)
            r.setAudioEncodingBitRate(64000)
            r.setOutputFile(f.absolutePath)
            r.prepare(); r.start()
            recorder = r; recordFile = f
            recording = true
            recBtn.text = "停止并发送"
            status.text = "录音中…"
            resultText.text = ""; copyBtn.isEnabled = false; retryBtn.isEnabled = false
        } catch (t: Throwable) {
            status.text = "无法开始录音：${t.message}"
        }
    }

    private fun stopAndSend() {
        try {
            recorder?.stop(); recorder?.release()
        } catch (t: Throwable) {
            Log.w("VoiceSend", "stop err", t)
        }
        recorder = null; recording = false
        recBtn.text = "开始录音"
        val f = recordFile
        if (f == null || !f.exists() || f.length() == 0L) {
            status.text = "没录到音频，请重试"; return
        }
        lastAudio = f
        upload(f)
    }

    private fun upload(file: File) {
        val url = normalizeServerUrl(urlInput.text.toString())
        if (url.isEmpty()) { status.text = "请先填服务器地址"; return }
        // 保存地址
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_URL, urlInput.text.toString().trim()).apply()
        status.text = "发送中…"
        recBtn.isEnabled = false; retryBtn.isEnabled = false
        Thread {
            val outcome: UploadResult = try {
                doUpload(url, file.readBytes())
            } catch (t: Throwable) {
                UploadResult.Failure("网络或证书错误：${t.javaClass.simpleName}: ${t.message}")
            }
            ui.post {
                recBtn.isEnabled = true
                when (outcome) {
                    is UploadResult.Success -> {
                        lastText = outcome.text
                        resultText.text = outcome.text
                        status.text = "转写完成（${outcome.durationMs} ms）"
                        copyBtn.isEnabled = true
                    }
                    is UploadResult.Failure -> {
                        status.text = "失败：${outcome.message}"
                        retryBtn.isEnabled = true
                    }
                }
            }
        }.start()
    }

    private fun doUpload(urlStr: String, audio: ByteArray): UploadResult {
        val mp = buildMultipartBody(audio, filename = "clip.m4a", mimeType = "audio/mp4")
        val conn = java.net.URL(urlStr).openConnection() as HttpsURLConnection
        conn.sslSocketFactory = pinnedContext().socketFactory
        conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 8000
        conn.readTimeout = 120000
        conn.setRequestProperty("Content-Type", mp.contentType)
        conn.outputStream.use { it.write(mp.bytes) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        conn.disconnect()
        return parseUploadResponse(code, body)
    }

    private fun pinnedContext(): SSLContext {
        val cf = CertificateFactory.getInstance("X.509")
        val cert = assets.open("cert.pem").use { cf.generateCertificate(it) as X509Certificate }
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        ks.setCertificateEntry("voicesend", cert)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(ks) }
        return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
    }
}
