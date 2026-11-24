package com.example.voicesend

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
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 连通性原型（throwaway）——只验证一件事：
 * 原生 Android 能否用【固定信任 server/certs/cert.pem】的方式，经 HTTPS 把一段音频
 * 以 multipart 字段 audio 发到电脑 /upload，并拿回转写 JSON。
 *
 * 不追求 UI，不实现录音。用 res/raw 里打包的一段真实音频当作"录音产物"直接发。
 */
class ProbeActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 32) }
        val urlInput = EditText(this).apply {
            setText("https://192.168.0.153:8443/upload")
            hint = "https://电脑IP:8443/upload"
        }
        val sendBtn = Button(this).apply { text = "发送打包的音频到电脑" }
        val out = TextView(this).apply { text = "就绪" }
        val scroll = ScrollView(this).apply { addView(out) }
        root.addView(TextView(this).apply { text = "连通性原型：原生 App → 电脑 /upload" })
        root.addView(urlInput)
        root.addView(sendBtn)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        sendBtn.setOnClickListener {
            val url = urlInput.text.toString().trim()
            out.text = "发送中…\n$url"
            sendBtn.isEnabled = false
            Thread {
                val result = try {
                    postSample(url)
                } catch (t: Throwable) {
                    "异常: ${t.javaClass.simpleName}: ${t.message}\n" + Log.getStackTraceString(t)
                }
                ui.post {
                    out.text = result
                    sendBtn.isEnabled = true
                }
            }.start()
        }
    }

    /** 用打包的 cert.pem 建只信它的 SSLContext，再发 multipart。 */
    private fun buildPinnedContext(): SSLContext {
        val cf = CertificateFactory.getInstance("X.509")
        val cert = assets.open("cert.pem").use { cf.generateCertificate(it) as X509Certificate }
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        ks.setCertificateEntry("voicesend", cert)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(ks)
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, tmf.trustManagers, null)
        return ctx
    }

    private fun postSample(urlStr: String): String {
        val audio = assets.open("sample.m4a").use { it.readBytes() }
        val boundary = "----VoiceSend${UUID.randomUUID()}"
        val conn = URL(urlStr).openConnection() as HttpsURLConnection
        conn.sslSocketFactory = buildPinnedContext().socketFactory
        conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true } // 自用局域网：证书已 pin，主机名从宽
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 8000
        conn.readTimeout = 120000
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

        val w = OutputStreamWriter(conn.outputStream, "UTF-8")
        w.write("--$boundary\r\n")
        w.write("Content-Disposition: form-data; name=\"audio\"; filename=\"sample.m4a\"\r\n")
        w.write("Content-Type: audio/mp4\r\n\r\n")
        w.flush()
        conn.outputStream.write(audio)
        conn.outputStream.flush()
        w.write("\r\n--$boundary--\r\n")
        w.flush()

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
        conn.disconnect()
        return "HTTP $code\n\n$body"
    }
}
