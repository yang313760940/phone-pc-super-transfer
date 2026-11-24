package com.example.voicesend

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 单屏录音发送 App（可分享版，沉浸式录音优先 UI）。
 * 录音(MediaRecorder→AAC/m4a) → 自动 multipart 发到电脑 /upload → 显示可复制中文 → 失败重试。
 * 地址收进右上齿轮弹窗并记忆；证书「首次连接信任」（TOFU）。
 */
class ProbeActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val PREFS = "voicesend"
    private val KEY_URL = "server_url"

    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var lastText: String = ""
    private var lastAudio: File? = null
    private var recording = false
    private var startedAt = 0L
    private var certChanged = false
    private var retryText: TextView? = null

    // 视图
    private lateinit var recordBtn: FrameLayout
    private lateinit var recordIcon: ImageView
    private lateinit var wave: WaveformView
    private lateinit var timerText: TextView
    private lateinit var liveRow: LinearLayout
    private lateinit var hintText: TextView
    private lateinit var serverText: TextView
    private lateinit var serverDot: View
    private lateinit var resultCard: LinearLayout
    private lateinit var resultText: TextView
    private lateinit var errorCard: LinearLayout
    private lateinit var errorDesc: TextView
    private lateinit var statusLine: TextView

    // 定时器（计时 + 波形采样）
    private val tick = object : Runnable {
        override fun run() {
            if (!recording) return
            val ms = System.currentTimeMillis() - startedAt
            timerText.text = String.format("00:%02d", ms / 1000)
            val amp = recorder?.maxAmplitude ?: 0
            // 归一化：maxAmplitude 典型 0..32767
            wave.pushLevel(amp / 32767f)
            ui.postDelayed(this, 80)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 状态栏跟随明暗主题反色（浅色下用深色图标，深色下用浅色图标），避免文字看不清
        val night = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        window.statusBarColor = Color.TRANSPARENT
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
        }
        val rootView = buildUi()
        // 给内容加顶部状态栏高度的 inset，避免被状态栏压住
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).top
            v.setPadding(dp(20), top + dp(12), dp(20), dp(20))
            insets
        }
        setContentView(rootView)
        updateServerChip()
        ensurePermission()
    }

    // ---------- 颜色取 token ----------
    private fun c(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c(R.color.bg))
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }

        // 顶栏：标题 + 齿轮
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "录音发送"; textSize = 20f; setTextColor(c(R.color.fg))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val gear = ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            setColorFilter(c(R.color.muted_fg))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "设置服务器地址"
            setOnClickListener { showSettings() }
        }
        bar.addView(gear, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(bar)

        // 当前服务器 chip（一行小字）
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(c(R.color.muted), dp(12))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(8); layoutParams = lp
        }
        serverDot = View(this).apply { background = circle(c(R.color.ok)) }
        chip.addView(serverDot, LinearLayout.LayoutParams(dp(8), dp(8)).also { it.rightMargin = dp(8) })
        serverText = TextView(this).apply { textSize = 13f; setTextColor(c(R.color.muted_fg)) }
        chip.addView(serverText)
        root.addView(chip)

        // 中央录音区（占据主轴）
        val zone = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        // 录音中行（红点 + 录音中…）默认隐藏
        liveRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            visibility = View.GONE
            val d = View(this@ProbeActivity).apply { background = circle(c(R.color.primary)) }
            addView(d, LinearLayout.LayoutParams(dp(9), dp(9)).also { it.rightMargin = dp(8) })
            addView(TextView(this@ProbeActivity).apply {
                text = "录音中…"; setTextColor(c(R.color.primary)); textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        }
        zone.addView(liveRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(18) })

        timerText = TextView(this).apply {
            text = ""; textSize = 34f; setTextColor(c(R.color.fg))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER; visibility = View.GONE
        }
        zone.addView(timerText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(20) })

        wave = WaveformView(this).apply { visibility = View.GONE }
        zone.addView(wave, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).also { it.bottomMargin = dp(24) })

        hintText = TextView(this).apply {
            text = "点一下开始录音"; textSize = 15f; setTextColor(c(R.color.muted_fg)); gravity = Gravity.CENTER
        }
        zone.addView(hintText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(22) })

        // 大圆录音键
        recordBtn = FrameLayout(this).apply {
            background = circle(c(R.color.primary))
            elevation = dp(10).toFloat()
            contentDescription = "开始录音"
            isClickable = true
            setOnClickListener { if (recording) stopAndSend() else startRecording() }
        }
        recordIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic); setColorFilter(c(R.color.on_primary))
            layoutParams = FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER)
        }
        recordBtn.addView(recordIcon)
        zone.addView(recordBtn, LinearLayout.LayoutParams(dp(168), dp(168)).also { it.bottomMargin = dp(22) })

        val subHint = TextView(this).apply {
            text = "录完自动发送到电脑转写"; textSize = 13f; setTextColor(c(R.color.muted_fg)); gravity = Gravity.CENTER
        }
        zone.addView(subHint)

        root.addView(zone)

        // 结果卡片（默认隐藏）
        resultCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = bordered(c(R.color.card), c(R.color.border), dp(20))
        }
        resultCard.addView(TextView(this).apply {
            text = "转写结果"; textSize = 12f; setTextColor(c(R.color.muted_fg))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.06f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(10) })
        resultText = TextView(this).apply {
            textSize = 19f; setTextColor(c(R.color.fg)); setTextIsSelectable(true); setLineSpacing(6f, 1f)
        }
        val rs = ScrollView(this).apply { addView(resultText) }
        resultCard.addView(rs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(16) }
        }
        val copyBtn = pillBtn("复制", R.drawable.ic_copy, solid = true)
        copyBtn.setOnClickListener { doCopy() }
        val againBtn = pillBtn("再录一段", R.drawable.ic_mic, solid = false)
        againBtn.setOnClickListener { resetToIdle() }
        actions.addView(copyBtn, LinearLayout.LayoutParams(0, dp(52), 1f).also { it.rightMargin = dp(10) })
        actions.addView(againBtn, LinearLayout.LayoutParams(0, dp(52), 1f))
        resultCard.addView(actions)
        root.addView(resultCard)

        // 错误卡片（默认隐藏）
        errorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = bordered(c(R.color.err_card_bg), c(R.color.err_card_border), dp(20))
        }
        errorCard.addView(TextView(this).apply {
            text = "发送失败"; textSize = 15f; setTextColor(c(R.color.danger))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(6) })
        errorDesc = TextView(this).apply { textSize = 14f; setTextColor(c(R.color.muted_fg)); setLineSpacing(4f, 1f) }
        errorCard.addView(errorDesc)
        val eActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(16) }
        }
        val retryBtn = pillBtn("重试", R.drawable.ic_refresh, solid = true, dangerStyle = true)
        retryText = retryBtn.getChildAt(retryBtn.childCount - 1) as TextView
        retryBtn.setOnClickListener {
            if (certChanged) {
                // 证书指纹变了：清除记住的指纹让 TOFU 重新信任，再重试
                val host = currentHost()
                if (host != null) getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .remove("certfp_$host").apply()
                certChanged = false
            }
            lastAudio?.let { showSending(); upload(it) }
        }
        val redoBtn = pillBtn("重新录音", 0, solid = false)
        redoBtn.setOnClickListener { resetToIdle() }
        eActions.addView(retryBtn, LinearLayout.LayoutParams(0, dp(52), 1f).also { it.rightMargin = dp(10) })
        eActions.addView(redoBtn, LinearLayout.LayoutParams(0, dp(52), 1f))
        errorCard.addView(eActions)
        root.addView(errorCard)

        // 状态行
        statusLine = TextView(this).apply {
            text = ""; textSize = 14f; setTextColor(c(R.color.muted_fg))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(14); layoutParams = lp
            visibility = View.GONE
        }
        root.addView(statusLine)

        return root
    }

    // ---------- 小工具：圆角背景 ----------
    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = radius.toFloat()
    }
    private fun bordered(fill: Int, border: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill); cornerRadius = radius.toFloat(); setStroke(dp(1), border)
    }
    private fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    private fun pillBtn(text: String, icon: Int, solid: Boolean, dangerStyle: Boolean = false): LinearLayout {
        val bg = if (solid) (if (dangerStyle) c(R.color.primary) else c(R.color.accent)) else c(R.color.card)
        val fg = if (solid) Color.WHITE else c(R.color.fg)
        val btn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = if (solid) rounded(bg, dp(14)) else bordered(bg, c(R.color.border), dp(14))
            isClickable = true
        }
        if (icon != 0) {
            val iv = ImageView(this).apply { setImageResource(icon); setColorFilter(fg); setPadding(0, 0, dp(8), 0) }
            btn.addView(iv, LinearLayout.LayoutParams(dp(22), dp(22)))
        }
        btn.addView(TextView(this).apply {
            this.text = text; textSize = 16f; setTextColor(fg); setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        return btn
    }

    // ---------- 状态切换 ----------
    private fun resetToIdle() {
        resultCard.visibility = View.GONE
        errorCard.visibility = View.GONE
        statusLine.visibility = View.GONE
        recordBtn.isEnabled = true
        setRecordVisual(recording = false)
        wave.visibility = View.GONE; wave.stop()
        timerText.visibility = View.GONE
        liveRow.visibility = View.GONE
        hintText.visibility = View.VISIBLE; hintText.text = "点一下开始录音"
    }

    private fun setRecordVisual(recording: Boolean) {
        recordBtn.background = circle(if (recording) c(R.color.stop_dark) else c(R.color.primary))
        recordIcon.setImageResource(if (recording) R.drawable.ic_stop else R.drawable.ic_mic)
        recordIcon.setColorFilter(Color.WHITE)
        recordBtn.contentDescription = if (recording) "停止并发送" else "开始录音"
    }

    private fun showSending() {
        resultCard.visibility = View.GONE; errorCard.visibility = View.GONE
        statusLine.visibility = View.VISIBLE
        statusLine.text = "发送中…"; statusLine.setTextColor(c(R.color.muted_fg))
        recordBtn.isEnabled = false
    }

    private fun showError(msg: String) {
        certChanged = msg.contains("证书已变化") || msg.contains("指纹不一致")
        retryText?.text = if (certChanged) "重新信任并重试" else "重试"
        errorCard.visibility = View.VISIBLE
        errorDesc.text = msg
        errorCard.announceForAccessibility("发送失败：$msg")
        statusLine.visibility = View.VISIBLE
        statusLine.text = "失败 · 网络或证书错误"; statusLine.setTextColor(c(R.color.danger))
        recordBtn.isEnabled = true
        setRecordVisual(recording = false)
    }

    private fun currentHost(): String? {
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, "") ?: return null
        return try { java.net.URL(normalizeServerUrl(raw)).host } catch (t: Throwable) { null }
    }

    private fun showResult(text: String, durMs: Int) {
        resultCard.visibility = View.VISIBLE
        resultText.text = if (text.isBlank()) "（没识别到内容，可能是空录音，可重试）" else text
        errorCard.visibility = View.GONE
        statusLine.visibility = View.VISIBLE
        statusLine.text = "✓ 转写完成 · %.1fs".format(durMs / 1000f)
        statusLine.setTextColor(c(R.color.ok))
        recordBtn.isEnabled = true
        setRecordVisual(recording = false)
    }

    private fun doCopy() {
        if (lastText.isEmpty()) return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("transcript", lastText))
        statusLine.text = "已复制到剪贴板"; statusLine.setTextColor(c(R.color.ok))
    }

    private fun updateServerChip() {
        val cur = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, "") ?: ""
        serverText.text = cur.ifEmpty { "未设置服务器（点右上齿轮）" }
    }

    private fun showSettings() {
        if (recording) return
        val input = EditText(this).apply {
            setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, "") ?: "")
            hint = "如 192.168.0.153:8443"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("服务器地址")
            .setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) })
            .setPositiveButton("保存") { _, _ ->
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_URL, input.text.toString().trim()).apply()
                updateServerChip()
            }
            .setNegativeButton("取消", null)
            .show()
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
            showError("需要麦克风权限，请授权后重试"); ensurePermission(); return
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
            recorder = r; recordFile = f; recording = true
            startedAt = System.currentTimeMillis()
            resultCard.visibility = View.GONE; errorCard.visibility = View.GONE; statusLine.visibility = View.GONE
            setRecordVisual(recording = true)
            hintText.visibility = View.GONE
            liveRow.visibility = View.VISIBLE
            timerText.visibility = View.VISIBLE; timerText.text = "00:00"
            wave.visibility = View.VISIBLE; wave.start()
            ui.post(tick)
        } catch (t: Throwable) {
            showError("无法开始录音：${t.message}")
        }
    }

    private fun stopAndSend() {
        recording = false
        ui.removeCallbacks(tick)
        wave.stop()
        try { recorder?.stop(); recorder?.release() } catch (t: Throwable) { Log.w("VoiceSend", "stop err", t) }
        recorder = null
        setRecordVisual(recording = false)
        liveRow.visibility = View.GONE
        timerText.visibility = View.GONE
        wave.visibility = View.GONE
        hintText.visibility = View.VISIBLE; hintText.text = "点一下开始录音"
        val f = recordFile
        recordFile = null
        if (f == null || !f.exists() || f.length() == 0L) { showError("没录到音频，请重试"); return }
        lastAudio = f
        showSending()
        upload(f)
    }

    private fun upload(file: File) {
        val url = normalizeServerUrl(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, "") ?: "")
        if (url.isEmpty()) { showError("请先在设置里填服务器地址"); return }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, url).apply()
        if (currentStatusEmpty()) showSending()
        Thread {
            val outcome: UploadResult = try {
                doUpload(url, file.readBytes())
            } catch (t: Throwable) {
                UploadResult.Failure("网络或证书错误：${t.javaClass.simpleName}: ${t.message}")
            }
            ui.post {
                when (outcome) {
                    is UploadResult.Success -> { lastText = outcome.text; showResult(outcome.text, outcome.durationMs) }
                    is UploadResult.Failure -> showError(outcome.message)
                }
            }
        }.start()
    }

    private fun currentStatusEmpty(): Boolean = statusLine.text.isNullOrEmpty()

    private fun doUpload(urlStr: String, audio: ByteArray): UploadResult {
        val mp = buildMultipartBody(audio, filename = "clip.m4a", mimeType = "audio/mp4")
        val conn = java.net.URL(urlStr).openConnection() as HttpsURLConnection
        val host = java.net.URL(urlStr).host
        conn.sslSocketFactory = tofuContext(host).socketFactory
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

    private fun tofuContext(host: String): SSLContext {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain[0]
                val fp = sha256Hex(leaf.encoded)
                val key = "certfp_$host"
                val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(key, null)
                if (saved == null) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(key, fp).apply()
                    Log.i("VoiceSend", "首次信任 $host 证书指纹 $fp")
                } else if (saved != fp) {
                    throw java.security.cert.CertificateException(
                        "服务器证书已变化（指纹不一致），可能换了电脑或存在风险。如确认无误，请在系统设置中清除本应用数据后重连。"
                    )
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        return d.joinToString("") { "%02x".format(it) }
    }
}
