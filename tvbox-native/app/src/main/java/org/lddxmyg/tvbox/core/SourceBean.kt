package org.lddxmyg.tvbox.core

import android.util.Base64
import org.json.JSONObject

class SourceBean {
    var key = ""
    var name = ""
    var type = 0
    var api = ""
    var searchable = 1
    var quickSearch = 1
    var changeable = 1
    var filterable = 1
    var playUrl = ""
    var ext: Any? = null
    var timeout = 0
    var click = ""
    var style = ""
    var jar = ""   // 站点级 spider jar（空 = 用全局）

    val isType012: Boolean get() = type in 0..2
    val isSpider: Boolean get() = type == 3
    val isXml: Boolean get() = type == 0

    companion object {
        fun fromJson(d: JSONObject): SourceBean {
            val b = SourceBean()
            b.key = d.optString("key", "")
            b.name = d.optString("name", "")
            b.type = try { d.optInt("type", 0) } catch (_: Exception) { 0 }
            b.api = d.optString("api", "")
            b.searchable = d.optInt("searchable", 1)
            b.quickSearch = d.optInt("quickSearch", 1)
            b.changeable = d.optInt("changeable", 1)
            b.filterable = d.optInt("filterable", 1)
            b.playUrl = d.optString("playUrl", "")
            b.ext = if (d.has("ext")) d.opt("ext") else null
            b.timeout = d.optInt("timeout", 0)
            b.click = d.optString("click", "")
            b.style = d.optString("style", "")
            b.jar = d.optString("jar", "")
            if (b.key.startsWith("py_")) b.filterable = 1
            return b
        }
    }
}

class ParseBean(val name: String, val url: String, val type: Int = 0) {
    /** ext 编码进 URL（对应 ParseBean.mixUrl） */
    fun mixUrl(ext: String?): String {
        if (ext.isNullOrEmpty() || !url.contains("?")) return url
        val enc = Base64.encodeToString(ext.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val idx = url.indexOf('?')
        return url.substring(0, idx + 1) + "cat_ext=" + enc + "&" + url.substring(idx + 1)
    }

        companion object {
            /** 对应 apiconfig.headers_from：JSON 对象/字符串里提取 header/headers */
            fun headersFrom(obj: Any?): Map<String, String> {
                val out = LinkedHashMap<String, String>()
                val json: JSONObject? = when (obj) {
                    is JSONObject -> obj
                    is String -> {
                        val t = obj.trim()
                        if (!t.startsWith("{")) null
                        else try { JSONObject(t) } catch (_: Exception) { null }
                    }
                    else -> null
                }
                if (json == null) return out
                val raw: JSONObject? = json.optJSONObject("header")
                    ?: json.optJSONObject("headers")
                if (raw != null) {
                    val keys = raw.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        out[k] = raw.optString(k, "")
                    }
                }
                return out
            }
        }
}
