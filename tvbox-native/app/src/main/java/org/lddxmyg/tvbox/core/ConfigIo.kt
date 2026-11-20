package org.lddxmyg.tvbox.core

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

object ConfigIo {

    val FALLBACK_STATUS = setOf(403, 429, 495, 496, 497, 525, 526, 527)

    val client: OkHttpClient = run {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf(tm), SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** 对应 apiconfig.fetch_text：本地文件 / 广告域拦截 / 重试 / 换 UA 兜底 */
    fun fetchText(url: String, headers: Map<String, String>? = null, timeoutSec: Int = 15, retries: Int = 3): String? {
        if (ApiConfig.isAdHost(url)) return null
        for (attempt in 1..maxOf(1, retries)) {
            try {
                val b = Request.Builder().url(url).header("User-Agent", "okhttp/4.9.3")
                headers?.forEach { (k, v) -> if (!k.equals("User-Agent", true)) b.header(k, v) }
                val c = client.newBuilder().callTimeout(timeoutSec.toLong(), TimeUnit.SECONDS).build()
                val resp = c.newCall(b.build()).execute()
                resp.use { r ->
                    if (r.code == 200 && r.body != null) {
                        val text = r.body!!.string()
                        if (text.isNotEmpty()) return text
                    }
                    if (r.code in FALLBACK_STATUS) {
                        try {
                            val b2 = Request.Builder().url(url).header("User-Agent", "okhttp/4.12.0")
                            headers?.forEach { (k, v) -> b2.header(k, v) }
                            client.newCall(b2.build()).execute().use { r2 ->
                                if (r2.code == 200 && r2.body != null) {
                                    val t = r2.body!!.string()
                                    if (t.isNotEmpty()) return t
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    /** 下载二进制（spider jar 用），带 md5 校验缓存 */
    fun fetchBytes(url: String, timeoutSec: Int = 30, retries: Int = 2): ByteArray? {
        if (ApiConfig.isAdHost(url)) return null
        for (attempt in 1..maxOf(1, retries)) {
            try {
                val b = Request.Builder().url(url).header("User-Agent", "okhttp/4.9.3")
                val c = client.newBuilder().callTimeout(timeoutSec.toLong(), TimeUnit.SECONDS).build()
                c.newCall(b.build()).execute().use { r ->
                    if (r.code == 200 && r.body != null) {
                        val bytes = r.body!!.bytes()
                        if (bytes.isNotEmpty()) return bytes
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    /** 对应 apiconfig._b64decode */
    fun b64Decode(text: String): String {
        var s = text.replace(Regex("\\s+"), "")
        val pad = (4 - s.length % 4) % 4
        s += "=".repeat(pad)
        return try {
            String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) {
            try {
                String(Base64.decode(s, Base64.URL_SAFE), Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
        }
    }

    fun bytesFromHex(text: String): ByteArray {
        val s = text.replace(Regex("\\s+"), "").let { it.substring(0, it.length / 2 * 2) }
        val out = ByteArray(s.length / 2)
        for (i in out.indices) out[i] = s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }

    fun rightPad(text: String?, ch: Char, length: Int): String {
        val s = (text ?: "").trim()
        return if (s.length >= length) s.substring(0, length) else s + ch.toString().repeat(length - s.length)
    }

    fun aesEcbDecrypt(data: ByteArray, key: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return c.doFinal(data)
    }

    fun aesCbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    fun isJsonObject(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return try {
            org.json.JSONObject(text.trim()).length() >= 0 && text.trim().startsWith("{")
        } catch (_: Exception) { false }
    }

    /** 对应 _decode_2423：hex( "$#" + key + "#$" + cipher + iv13 ) AES-CBC */
    private fun decode2423(content: String): String? {
        return try {
            val raw = bytesFromHex(content.replace(Regex("\\s+"), ""))
            var marker = -1
            var i = 2
            while (i + 1 < raw.size) {
                if (raw[i] == '#'.code.toByte() && raw[i + 1] == '$'.code.toByte()) { marker = i; break }
                i++
            }
            if (marker < 2 || raw.size - marker - 2 - 13 < 16) return null
            val cipher = raw.copyOfRange(marker + 2, raw.size - 13)
            val key = rightPad(String(raw, 2, marker - 2, Charsets.UTF_8).lowercase(), '0', 16).toByteArray()
            val iv = rightPad(String(raw, raw.size - 13, 13, Charsets.UTF_8).lowercase(), '0', 16).toByteArray()
            String(aesCbcDecrypt(cipher, key, iv), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    /** 对应 apiconfig.decode_config：Base64 包裹 / 2423+AES-CBC / ;pk;+AES-ECB */
    fun decodeConfig(text: String?, configKey: String? = null): String? {
        if (text.isNullOrEmpty()) return text
        return try {
            if (isJsonObject(text)) return text
            var content = text
            val m = Regex("[A-Za-z0-9]{8}\\*\\*").find(content)
            if (m != null) content = b64Decode(content.substring(m.range.first + 10))
            content = content.trim()
            if (content.startsWith("2423")) {
                val decoded = decode2423(content)
                if (decoded != null) return decoded
                return text
            }
            if (configKey != null && !isJsonObject(content)) {
                val key = rightPad(configKey, '0', 16).toByteArray(Charsets.UTF_8)
                return String(aesEcbDecrypt(bytesFromHex(content), key), Charsets.UTF_8)
            }
            content
        } catch (_: Exception) { text }
    }

    /** 对应 apiconfig._strip_comments：剥 JS 风格注释（TVBox 配置常见） */
    fun stripComments(text: String): String {
        val out = StringBuilder()
        var i = 0
        var inStr = false
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (inStr) {
                out.append(c)
                if (c == '\\' && i + 1 < n) { out.append(text[i + 1]); i += 2; continue }
                if (c == '"') inStr = false
                i++
            } else when {
                c == '"' -> { inStr = true; out.append(c); i++ }
                c == '/' && i + 1 < n && text[i + 1] == '/' -> { while (i < n && text[i] != '\n') i++ }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < n && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i += 2
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** 对应 apiconfig.extract_json：纯 JSON 或带前后缀文本自动截取 */
    fun extractJson(text: String?): org.json.JSONObject? {
        if (text.isNullOrEmpty()) return null
        try { return org.json.JSONObject(text) } catch (_: Exception) {}
        val s = text.indexOf('{')
        val e = text.lastIndexOf('}')
        if (s in 0 until e) {
            val slice = text.substring(s, e + 1)
            try { return org.json.JSONObject(slice) } catch (_: Exception) {}
            try { return org.json.JSONObject(stripComments(slice)) } catch (_: Exception) {}
        }
        return null
    }

    fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun hostOf(url: String): String = try {
        java.net.URI(url).host?.lowercase() ?: ""
    } catch (_: Exception) { "" }
}
