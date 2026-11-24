package com.example.voicesend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯逻辑 seam 单测（JVM，无需设备）。 */
class ModelsTest {

    // ---- normalizeServerUrl ----
    @Test
    fun normalizes_bare_ip_with_port_to_https_upload() {
        assertEquals("https://192.168.0.153:8443/upload", normalizeServerUrl("192.168.0.153:8443"))
    }

    @Test
    fun keeps_https_and_appends_upload() {
        assertEquals("https://192.168.0.153:8443/upload", normalizeServerUrl("https://192.168.0.153:8443"))
    }

    @Test
    fun keeps_existing_upload_path() {
        assertEquals("https://192.168.0.153:8443/upload", normalizeServerUrl("https://192.168.0.153:8443/upload"))
    }

    @Test
    fun trims_spaces_and_trailing_slash() {
        assertEquals("https://10.0.0.5:8443/upload", normalizeServerUrl("  10.0.0.5:8443/  "))
    }

    @Test
    fun empty_returns_empty() {
        assertEquals("", normalizeServerUrl("   "))
    }

    // ---- buildMultipartBody ----
    @Test
    fun multipart_has_field_and_content_type() {
        val audio = byteArrayOf(1, 2, 3, 4)
        val mp = buildMultipartBody(audio, filename = "clip.m4a", mimeType = "audio/mp4", boundary = "BOUND")
        assertTrue(mp.contentType == "multipart/form-data; boundary=BOUND")
        val s = String(mp.bytes, Charsets.UTF_8)
        assertTrue(s.contains("name=\"audio\""))
        assertTrue(s.contains("filename=\"clip.m4a\""))
        assertTrue(s.contains("Content-Type: audio/mp4"))
    }

    @Test
    fun multipart_embeds_audio_bytes_verbatim() {
        val audio = byteArrayOf(9, -1, 0, 42, 7)
        val mp = buildMultipartBody(audio, boundary = "X")
        // 音频原始字节应原样出现在 body 中
        val needle = audio
        var found = false
        for (i in 0..mp.bytes.size - needle.size) {
            var ok = true
            for (j in needle.indices) if (mp.bytes[i + j] != needle[j]) { ok = false; break }
            if (ok) { found = true; break }
        }
        assertTrue(found)
    }

    @Test
    fun multipart_ends_with_closing_boundary() {
        val mp = buildMultipartBody(byteArrayOf(1), boundary = "B")
        val s = String(mp.bytes, Charsets.UTF_8)
        assertTrue(s.trimEnd().endsWith("--B--"))
    }

    // ---- parseUploadResponse ----
    @Test
    fun parses_success() {
        val body = "{\"ok\": true, \"text\": \"你好世界\", \"duration_ms\": 1900}"
        val r = parseUploadResponse(200, body)
        assertTrue(r is UploadResult.Success)
        r as UploadResult.Success
        assertEquals("你好世界", r.text)
        assertEquals(1900, r.durationMs)
    }

    @Test
    fun parses_decode_stage_failure() {
        val body = "{\"ok\": false, \"error\": \"音频解码失败\", \"stage\": \"decode\"}"
        val r = parseUploadResponse(422, body)
        assertTrue(r is UploadResult.Failure)
        r as UploadResult.Failure
        assertTrue(r.message.contains("音频解码失败"))
        assertTrue(r.message.contains("decode"))
    }

    @Test
    fun parses_internal_failure_without_stage() {
        val body = "{\"ok\": false, \"error\": \"崩溃\", \"stage\": \"internal\"}"
        val r = parseUploadResponse(500, body)
        assertTrue(r is UploadResult.Failure)
        assertTrue((r as UploadResult.Failure).message.contains("崩溃"))
    }

    @Test
    fun parses_non_json_to_failure() {
        val r = parseUploadResponse(500, "<html>oops</html>")
        assertTrue(r is UploadResult.Failure)
        assertTrue((r as UploadResult.Failure).message.contains("HTTP 500"))
    }

    @Test
    fun does_not_misfire_on_ok_substring_in_error() {
        // 正文里出现 "broken" 这类含 ok 子串，不能被误判为成功
        val body = "{\"ok\": false, \"error\": \"broken audio\", \"stage\": \"decode\"}"
        val r = parseUploadResponse(422, body)
        assertTrue(r is UploadResult.Failure)
        assertTrue((r as UploadResult.Failure).message.contains("broken audio"))
    }
}
