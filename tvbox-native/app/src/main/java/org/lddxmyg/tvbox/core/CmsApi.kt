package org.lddxmyg.tvbox.core

import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URI

data class Vod(val id: String, val name: String, val pic: String, val remark: String)

class DetailInfo {
    var name = ""
    var pic = ""
    var remark = ""
    var actors = ""
    var director = ""
    var content = ""
    var year = ""
    var area = ""
    var lang = ""
    var cls = ""
    val seriesFlags = ArrayList<Pair<String, List<String>>>()   // 线路名 → 集名列表
    val seriesUrls = ArrayList<List<String>>()                  // 与线路一一对应的集地址
}

data class PlayResult(val url: String, val headers: Map<String, String>?)

/** CMS 数据接口层（对应 data_api.py，仅 type 0/1/2） */
object CmsApi {

    private val classesCache = HashMap<String, List<Pair<String, String>>>()
    private val classesLock = Any()

    fun cmsUrl(bean: SourceBean, query: String): String {
        val api = bean.api.trimEnd('&', '?').replace(Regex("[?&]ac=[a-z]+$"), "")
        val sep = if (api.contains("?")) "&" else "?"
        return "$api$sep$query"
    }

    // ---------- XML (type 0) ----------

    private fun xmlRoot(text: String?): org.w3c.dom.Element? {
        if (text.isNullOrBlank()) return null
        return try {
            val db = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            db.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val doc = db.newDocumentBuilder().parse(
                org.xml.sax.InputSource(StringReader(text.trim())))
            doc.documentElement
        } catch (_: Exception) { null }
    }

    private fun elemText(parent: org.w3c.dom.Element, tag: String): String =
        parent.getElementsByTagName(tag).let { l ->
            if (l.length > 0) l.item(0).textContent?.trim() ?: "" else ""
        }

    private fun xmlClasses(text: String?): List<Pair<String, String>> {
        val root = xmlRoot(text) ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        val list = root.getElementsByTagName("ty")
        for (i in 0 until list.length) {
            val el = list.item(i) as org.w3c.dom.Element
            out.add(el.getAttribute("id") to (el.textContent ?: "").trim())
        }
        return out
    }

    private fun xmlVods(text: String?): List<Vod> {
        val root = xmlRoot(text) ?: return emptyList()
        val out = ArrayList<Vod>()
        val list = root.getElementsByTagName("video")
        for (i in 0 until list.length) {
            val v = list.item(i) as org.w3c.dom.Element
            out.add(Vod(elemText(v, "id"), elemText(v, "name"),
                elemText(v, "pic"), elemText(v, "note")))
        }
        return out
    }

    private fun xmlPageCount(text: String?): Int {
        val root = xmlRoot(text) ?: return 1
        val node = if (root.tagName == "list") root
            else root.getElementsByTagName("list").let { if (it.length > 0) it.item(0) as org.w3c.dom.Element else root }
        return try { maxOf(1, node.getAttribute("pagecount").toIntOrNull() ?: 1) } catch (_: Exception) { 1 }
    }

    private fun xmlDetail(text: String?): DetailInfo {
        val info = DetailInfo()
        val root = xmlRoot(text) ?: return info
        val videos = root.getElementsByTagName("video")
        if (videos.length == 0) return info
        val v = videos.item(0) as org.w3c.dom.Element
        info.name = elemText(v, "name")
        info.pic = elemText(v, "pic")
        info.remark = elemText(v, "note")
        info.actors = elemText(v, "actor")
        info.director = elemText(v, "director")
        info.content = cleanHtml(elemText(v, "des"))
        val dds = v.getElementsByTagName("dd")
        for (i in 0 until dds.length) {
            val dd = dds.item(i) as org.w3c.dom.Element
            val flag = dd.getAttribute("flag")
            val names = ArrayList<String>()
            val urls = ArrayList<String>()
            for (ep in (dd.textContent ?: "").split("#")) {
                val idx = ep.indexOf('$')
                if (idx > 0) {
                    names.add(ep.substring(0, idx).trim())
                    urls.add(ep.substring(idx + 1).trim())
                }
            }
            if (urls.isNotEmpty()) {
                info.seriesFlags.add(flag to names)
                info.seriesUrls.add(urls)
            }
        }
        return info
    }

    // ---------- JSON (type 1/2) ----------

    private fun parseClasses(cfg: JSONObject?): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val arr = cfg?.optJSONArray("class") ?: return out
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val name = c.optString("type_name", null) ?: continue
            out.add(c.optString("type_id", "") to name)
        }
        return out
    }

    private fun jsonVods(cfg: JSONObject?): List<Vod> {
        val out = ArrayList<Vod>()
        val arr = cfg?.optJSONArray("list") ?: return out
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            out.add(Vod(v.optString("vod_id", ""), v.optString("vod_name", ""),
                v.optString("vod_pic", ""), v.optString("vod_remarks", "")))
        }
        return out
    }

    fun getClasses(bean: SourceBean): List<Pair<String, String>> {
        val key = "${bean.key}:${bean.api}"
        synchronized(classesLock) { classesCache[key]?.let { return it } }
        val out = if (bean.isXml) {
            xmlClasses(ConfigIo.fetchText(cmsUrl(bean, "ac=class")))
        } else {
            parseClasses(ConfigIo.extractJson(ConfigIo.fetchText(cmsUrl(bean, "ac=class"))))
        }
        if (out.isNotEmpty()) synchronized(classesLock) { classesCache[key] = out }
        return out
    }

    fun clearCaches() { synchronized(classesLock) { classesCache.clear() } }

    /** 返回 (vods, page, pagecount) */
    fun getVods(bean: SourceBean, classId: String?, page: Int = 1): Triple<List<Vod>, Int, Int> {
        val url = if (classId == null) cmsUrl(bean, "ac=videolist")
            else cmsUrl(bean, "ac=videolist&t=$classId&pg=$page")
        if (bean.isXml) {
            val text = ConfigIo.fetchText(url)
            return Triple(xmlVods(text), page, xmlPageCount(text))
        }
        val cfg = ConfigIo.extractJson(ConfigIo.fetchText(url))
        val pc = try { cfg?.optInt("pagecount", 1) ?: 1 } catch (_: Exception) { 1 }
        return Triple(jsonVods(cfg), page, maxOf(1, pc))
    }

    fun searchVods(bean: SourceBean, wd: String): List<Vod> {
        return if (bean.isXml) {
            xmlVods(ConfigIo.fetchText(cmsUrl(bean, "ac=videolist&wd=${ConfigIo.urlEncode(wd)}")))
        } else {
            jsonVods(ConfigIo.extractJson(
                ConfigIo.fetchText(cmsUrl(bean, "ac=detail&wd=${ConfigIo.urlEncode(wd)}"))))
        }
    }

    fun cleanHtml(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        var t = android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        t = t.replace(Regex("\n{3,}"), "\n\n")
        return t.trim()
    }

    fun getDetail(bean: SourceBean, vodId: String): DetailInfo {
        if (bean.isXml) return xmlDetail(ConfigIo.fetchText(cmsUrl(bean, "ac=videolist&ids=$vodId")))
        val info = DetailInfo()
        val cfg = ConfigIo.extractJson(ConfigIo.fetchText(cmsUrl(bean, "ac=videolist&ids=$vodId")))
        val v = cfg?.optJSONArray("list")?.optJSONObject(0) ?: return info
        info.name = v.optString("vod_name", "")
        info.pic = v.optString("vod_pic", "")
        info.remark = v.optString("vod_remarks", "")
        info.actors = v.optString("vod_actor", "")
        info.director = v.optString("vod_director", "")
        info.content = cleanHtml(v.optString("vod_content", ""))
        info.year = (v.optString("vod_year", "").ifEmpty { v.optString("vod_time", "") }).trim()
        info.area = v.optString("vod_area", "")
        info.lang = v.optString("vod_lang", "")
        info.cls = v.optString("vod_class", "")
        val flagList = v.optString("vod_play_from", "").split("\\$\\$\\$")
        val urlGroups = v.optString("vod_play_url", "").split("\\$\\$\\$")
        for (i in flagList.indices) {
            val names = ArrayList<String>()
            val urls = ArrayList<String>()
            if (i < urlGroups.size) {
                for (ep in urlGroups[i].split("#")) {
                    val idx = ep.indexOf('$')
                    if (idx > 0) {
                        names.add(ep.substring(0, idx))
                        urls.add(ep.substring(idx + 1))
                    }
                }
            }
            info.seriesFlags.add(flagList[i] to names)
            info.seriesUrls.add(urls)
        }
        return info
    }

    // ---------- 播放地址解析 ----------

    private val SNIFFER_RE = Regex(
        """http((?!http).){12,}?\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|m4a|mp3|aac|mpd)\?.*|""" +
        """http((?!http).){12,}\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|m4a|mp3|aac|mpd)|""" +
        """http((?!http).)*?video/tos*|""" +
        """http((?!http).)*?default\.ixigua\.com/.*|""" +
        """http.*?/player/m3u8play\.php\?url=.*|""" +
        """http.*?/player/.*?[pP]lay\.php\?url=.*|""" +
        """http.*?/playlist/m3u8/\?vid=.*|""" +
        """http.*?\.php\?type=m3u8&.*|""" +
        """http.*?/download\.aspx\?.*|""" +
        """http.*?/api/up_api\.php\?.*|""" +
        """https.*?\.66yk\.cn.*|""" +
        """http((?!http).)*?netease\.com/file/.*""",
        RegexOption.IGNORE_CASE)

    private val MEDIA_RE = Regex(
        """\.(m3u8|mp4|flv|mkv|ts|avi|mov|mp3|aac|m4a|mpd)([?#]|$)""", RegexOption.IGNORE_CASE)

    private val QUOTED_MEDIA_RE = Regex(
        """["']([^"'\s<>]*?\.(?:m3u8|mp4|flv|mkv|ts|avi|mov|mpd|mp3|m4a|aac)(?:\?[^"'\s<>]*)?)["']""",
        RegexOption.IGNORE_CASE)

    private val ABS_URL_RE = Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)

    fun isVideoFormat(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        if (url.startsWith("rtsp://") || url.startsWith("rtmp://") ||
            url.startsWith("rtp://") || url.startsWith("udp://")) return true
        return SNIFFER_RE.containsMatchIn(url) || MEDIA_RE.containsMatchIn(url)
    }

    private fun cleanCandidate(url: String): String =
        url.trim().trim('"', '\'').trimEnd(',', ';', ':', ')', ']', '}').trim()

    /** 对应 data_api._sniff_text：在 HTML/JSON 里找可播地址 */
    fun sniffText(text: String?, baseUrl: String): String? {
        if (text.isNullOrEmpty()) return null
        val t = text.replace("\\/", "/").replace("&amp;", "&")
        for (m in QUOTED_MEDIA_RE.findAll(t)) {
            val u = keepCandidate(cleanCandidate(m.groupValues[1]), baseUrl) ?: continue
            return u
        }
        for (m in ABS_URL_RE.findAll(t)) {
            val u = keepCandidate(cleanCandidate(m.value), baseUrl) ?: continue
            return u
        }
        return null
    }

    private fun keepCandidate(cand: String, baseUrl: String): String? {
        if (cand.isEmpty()) return null
        if (cand.startsWith("data:") || cand.startsWith("blob:") ||
            cand.startsWith("javascript:") || cand.startsWith("about:")) return null
        val url = try { URI(baseUrl).resolve(cand.replace(" ", "%20")).toString() }
            catch (_: Exception) { return null }
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return null
        if (isVideoFormat(url)) return url
        return null
    }

    fun sniffPage(pageUrl: String, headers: Map<String, String>? = null): PlayResult? {
        val text = ConfigIo.fetchText(pageUrl, headers, timeoutSec = 10, retries = 1)
        val found = sniffText(text, pageUrl) ?: return null
        return PlayResult(found, null)
    }

    /** 对应 _join_parse_url */
    private fun joinParseUrl(api: String, pageUrl: String, encoded: Boolean = true): String {
        val tail = if (encoded) ConfigIo.urlEncode(pageUrl) else pageUrl
        return when {
            api.endsWith("=") || api.endsWith("?") || api.endsWith("&") -> api + tail
            !api.contains("?") -> "$api?$tail"
            else -> api + tail
        }
    }

    private fun parseJsonApi(api: String, pageUrl: String, headers: Map<String, String>?): PlayResult? {
        if (!api.startsWith("http://") && !api.startsWith("https://")) return null
        val text = ConfigIo.fetchText(joinParseUrl(api, pageUrl), headers, timeoutSec = 10, retries = 1)
            ?: return null
        val cfg = ConfigIo.extractJson(text)
        if (cfg != null) {
            val u = cfg.optString("url", cfg.optString("playUrl", "")).trim()
            if (u.isNotEmpty()) {
                val parseFlag = try { cfg.optInt("parse", 0) } catch (_: Exception) { 0 }
                val hdr = ParseBean.headersFrom(cfg)
                return resolveParsed(PlayResult(u, hdr.ifEmpty { null }), pageUrl, parseFlag)
            }
        }
        val plain = text.trim()
        if (plain.startsWith("http://") || plain.startsWith("https://")) {
            if (plain.length < 2048 && !plain.contains("<")) return PlayResult(plain, null)
        }
        return null
    }

    private fun resolveParsed(result: PlayResult?, pageUrl: String, parseFlag: Int): PlayResult? {
        if (result == null || result.url.isEmpty()) return null
        val url = result.url
        if (parseFlag == 1 || !isVideoFormat(url)) {
            return sniffPage(url, result.headers)
        }
        return result
    }

    /** 对应 _try_parse_bean（串行版：MVP 一个个试，10s 超时） */
    private fun tryParseBean(p: ParseBean, pageUrl: String, siteExt: String?): PlayResult? {
        val api = if (p.type == 2) p.mixUrl(siteExt) else p.url
        if (!(api.startsWith("http://") || api.startsWith("https://"))) return null
        val targets = ArrayList<String>()
        targets.add(joinParseUrl(api, pageUrl, p.type != 0))
        if (p.type == 4) targets.add(joinParseUrl(api, pageUrl, encoded = false))
        for (target in targets) {
            val text = ConfigIo.fetchText(target, timeoutSec = 10, retries = 1) ?: continue
            val cfg = ConfigIo.extractJson(text)
            if (cfg != null) {
                val u = cfg.optString("url", cfg.optString("playUrl", "")).trim()
                if (u.isNotEmpty()) {
                    val parseFlag = try { cfg.optInt("parse", 0) } catch (_: Exception) { 0 }
                    val hdr = ParseBean.headersFrom(cfg).ifEmpty { null }
                    val got = resolveParsed(PlayResult(u, hdr), pageUrl, parseFlag)
                    if (got != null) return got
                }
            }
            val sniffed = sniffText(text, target)
            if (sniffed != null) return PlayResult(sniffed, null)
        }
        return null
    }

    /** 对应 _second_parse：parses 并发 → 串行 + 页面嗅探兜底 */
    private fun secondParse(pageUrl: String, sitePlayUrl: String): PlayResult? {
        val beans = ArrayList(ApiConfig.parseBeanList)
        if (sitePlayUrl.startsWith("http") && !isVideoFormat(sitePlayUrl)) {
            beans.add(0, ParseBean("playUrl", sitePlayUrl, 4))
        }
        // 并发尝试，谁先成功用谁
        val results = java.util.concurrent.ConcurrentLinkedQueue<PlayResult>()
        val threads = beans.take(4).map { p ->
            Thread {
                try { tryParseBean(p, pageUrl, null)?.let { results.add(it) } } catch (_: Exception) {}
            }
        }
        threads.forEach { it.start() }
        val deadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < deadline) {
            if (results.isNotEmpty()) return results.peek()
            Thread.sleep(150)
            if (threads.none { it.isAlive }) break
        }
        threads.forEach { if (it.isAlive) { try { it.join(500) } catch (_: Exception) {} } }
        if (results.isNotEmpty()) return results.peek()
        return sniffPage(pageUrl)
    }

    /** 播放地址收口：配置 hosts 映射（对应 _finalize） */
    private fun finalize(url: String, header: Map<String, String>?): PlayResult {
        var u = url
        val host = try { URI(u).host?.lowercase() } catch (_: Exception) { null }
        val mapped = host?.let { ApiConfig.hostMapped(it) }
        if (!mapped.isNullOrEmpty() && mapped != host) {
            u = try {
                val parts = URI(u)
                val port = if (parts.port > 0) ":${parts.port}" else ""
                "${parts.scheme}://$mapped$port${parts.path ?: ""}${if (parts.query != null) "?" + parts.query else ""}"
            } catch (_: Exception) { u }
            val hdr = LinkedHashMap<String, String>()
            header?.forEach { (k, v) -> hdr[k] = v }
            hdr.putIfAbsent("Host", host)
            return PlayResult(u, hdr)
        }
        return PlayResult(u, header)
    }

    /** 对应 get_play_url：type0/1/2 的选集地址 → 真实媒体地址 */
    fun getPlayUrl(bean: SourceBean, epUrl: String): PlayResult {
        val ep = epUrl.trim()
        if (isVideoFormat(ep) || !(ep.startsWith("http://") || ep.startsWith("https://"))) {
            return finalize(ep, null)
        }
        val sniffed = try { sniffPage(ep) } catch (_: Exception) { null }
        if (sniffed != null) return finalize(sniffed.url, null)
        val got = try { secondParse(ep, bean.playUrl) } catch (_: Exception) { null }
        if (got != null) return finalize(got.url, got.headers)
        return finalize(ep, null)   // 解析失败原样返回，让播放器报错可见
    }

    /** 对应 pick_default_source：并发探测，返回第一个分类非空的源 */
    fun pickDefaultSource(): SourceBean? {
        val beans = ApiConfig.sources012()
        if (beans.isEmpty()) return null
        val result = arrayOfNulls<SourceBean>(beans.size)
        val threads = beans.mapIndexed { idx, b ->
            Thread {
                try {
                    val text = ConfigIo.fetchText(cmsUrl(b, "ac=class"), timeoutSec = 8, retries = 1)
                    val ok = parseClasses(ConfigIo.extractJson(text)).isNotEmpty() ||
                        xmlClasses(text).isNotEmpty()
                    if (ok) result[idx] = b
                } catch (_: Exception) {}
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(15_000) }
        return result.firstOrNull { it != null }
    }
}
