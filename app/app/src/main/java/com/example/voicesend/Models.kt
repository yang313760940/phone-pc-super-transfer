package com.example.voicesend

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * 纯逻辑层（可在 JVM 单测，无设备依赖）：地址规范化、multipart 构造、响应解析。
 * 只承担"把输入变形成可用的字节/状态"，不做 IO、不碰 Android API。
 */

/** 把用户输入的服务器地址规范成 `https://host:port/upload`。空输入返回 ""。 */
fun normalizeServerUrl(input: String): String {
    val s = input.trim()
    if (s.isEmpty()) return ""
    var body = s.trimEnd('/')
    if (!body.startsWith("http://") && !body.startsWith("https://")) {
        body = "https://$body"
    }
    return if (body.endsWith("/upload")) body else "$body/upload"
}

/** multipart 请求体构造的产物。 */
data class MultipartBody(
    val contentType: String,   // "multipart/form-data; boundary=..."
    val bytes: ByteArray,
)

/**
 * 构造 `POST /upload` 的 multipart 请求体：单文件字段 `audio`，Content-Type `audio/mp4`。
 * boundary 可注入以便测试；缺省随机生成。
 */
fun buildMultipartBody(
    audioBytes: ByteArray,
    filename: String = "sample.m4a",
    mimeType: String = "audio/mp4",
    boundary: String = "----VoiceSend" + UUID.randomUUID().toString(),
): MultipartBody {
    val out = ByteArrayOutputStream()
    val header = "--$boundary\r\n" +
        "Content-Disposition: form-data; name=\"audio\"; filename=\"$filename\"\r\n" +
        "Content-Type: $mimeType\r\n\r\n"
    out.write(header.toByteArray(Charsets.UTF_8))
    out.write(audioBytes)
    out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
    return MultipartBody("multipart/form-data; boundary=$boundary", out.toByteArray())
}

/** UI 状态：一次上传的结果。 */
sealed class UploadResult {
    data class Success(val text: String, val durationMs: Int) : UploadResult()
    data class Failure(val message: String) : UploadResult()
}

/**
 * 把 (HTTP 状态码, 响应体) 解析成 UI 可用的结果。
 * - 200 + ok=true → Success(text, duration_ms)
 * - ok=false → Failure(带 stage 的可读原因)
 * - 非 JSON / 其它 → Failure(原始片段)
 */
fun parseUploadResponse(httpCode: Int, body: String): UploadResult {
    // 精确匹配顶层 "ok" 布尔值，避免误匹配 "broken" 等子串
    val okMatch = Regex("\"ok\"\\s*:\\s*(true|false)").find(body)
    if (okMatch != null) {
        if (okMatch.groupValues[1] == "true") {
            val text = extractJsonString(body, "text")
            val dur = Regex("\"duration_ms\"\\s*:\\s*(-?\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return UploadResult.Success(text, dur)
        } else {
            val err = extractJsonString(body, "error")
            val stage = extractJsonString(body, "stage")
            val msg = if (err.isNotEmpty()) {
                if (stage.isNotEmpty()) "$err（stage=$stage）" else err
            } else "转写失败（HTTP $httpCode）"
            return UploadResult.Failure(msg)
        }
    }
    return UploadResult.Failure("响应无法解析（HTTP $httpCode）：" + body.take(120))
}

/** 从 JSON 文本里取某个字符串字段的值（极简解析，够本项目用）。 */
internal fun extractJsonString(json: String, key: String): String {
    val re = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    val m = re.find(json) ?: return ""
    return m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
}
