package org.lddxmyg.tvbox.core

import com.github.catvod.crawler.JarLoader
import com.github.catvod.crawler.JsLoader
import com.github.catvod.crawler.Spider
import org.json.JSONObject

/**
 * 统一源接口分发（复刻原版 SourceViewModel 路由）：
 *   api 含 csp_ → JarLoader（DexClassLoader 加载 spider jar）
 *   api 是 .js   → JsLoader（QuickJS + drpy）
 *   其余        → CmsApi（type 0/1/2 CMS 直连）
 */
object SourceApi {

    private val jarLoader = JarLoader()
    private val jsLoader = JsLoader()

    private fun extString(bean: SourceBean): String = when (val e = bean.ext) {
        null -> ""
        is String -> e
        else -> e.toString()
    }

    fun spiderFor(bean: SourceBean): Spider? {
        val api = bean.api.trim()
        val jar = bean.jar.ifEmpty { ApiConfig.spiderUrl }
        if (api.isEmpty()) return null
        return try {
            if (api.contains("csp_")) {
                jarLoader.getSpider(bean.key, api, extString(bean), jar)
            } else if (api.endsWith(".js") || api.contains(".js?") || api.contains(".js;")) {
                jsLoader.getSpider(bean.key, api, extString(bean), jar)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun json(s: String?): JSONObject? =
        if (s.isNullOrEmpty()) null else try { JSONObject(s) } catch (_: Exception) { null }

    // ---------- 分类 ----------

    fun getClasses(bean: SourceBean): List<Pair<String, String>> {
        if (bean.isType012) return CmsApi.getClasses(bean)
        val sp = spiderFor(bean) ?: return emptyList()
        val j = json(try { sp.homeContent(false) } catch (_: Throwable) { "" })
        val out = ArrayList<Pair<String, String>>()
        val arr = j?.optJSONArray("class") ?: return out
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val name = c.optString("type_name", "")
            if (name.isNotEmpty()) out.add(c.optString("type_id", "") to name)
        }
        return out
    }

    fun getHomeVods(bean: SourceBean): Pair<List<Vod>, Int> {
        if (bean.isType012) {
            val (vods, _, pc) = CmsApi.getVods(bean, null, 1)
            return vods to pc
        }
        val sp = spiderFor(bean) ?: return emptyList<Vod>() to 1
        val home = json(try { sp.homeContent(false) } catch (_: Throwable) { "" })
        var list = vodsOf(home)
        if (list.isEmpty()) {
            val hv = json(try { sp.homeVideoContent() } catch (_: Throwable) { "" })
            list = vodsOf(hv)
        }
        return list to pageCountOf(home)
    }

    fun getVods(bean: SourceBean, classId: String?, page: Int): Triple<List<Vod>, Int, Int> {
        if (bean.isType012) return CmsApi.getVods(bean, classId, page)
        val sp = spiderFor(bean) ?: return Triple(emptyList(), page, 1)
        val j = json(try {
            sp.categoryContent(classId ?: "", page.toString(), false, HashMap())
        } catch (_: Throwable) { "" })
        return Triple(vodsOf(j), page, pageCountOf(j))
    }

    fun searchVods(bean: SourceBean, wd: String): List<Vod> {
        if (bean.isType012) return CmsApi.searchVods(bean, wd)
        val sp = spiderFor(bean) ?: return emptyList()
        val j = json(try { sp.searchContent(wd, false) } catch (_: Throwable) { "" })
        return vodsOf(j)
    }

    /**
     * 全源聚合搜索（复刻原版 msearch: 链路）：
     * Douban/DouDou 这类"仅推荐"源的 vod_id 是 msearch: 前缀、无 detailContent，
     * 详情页须拿片名到全部可搜索的源里聚合出真实条目。
     * 返回（源, 条目）对；片名完全相等的排前面。
     */
    fun searchAllSources(wd: String, skipKey: String = ""): List<Pair<SourceBean, Vod>> {
        val out = ArrayList<Pair<SourceBean, Vod>>()
        val sources = ApiConfig.sourceBeanList.filter { it.key != skipKey && it.searchable == 1 }
        if (sources.isEmpty()) return out
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(sources.size, 6))
        val futures = sources.map { b -> pool.submit(java.util.concurrent.Callable {
            val list = try { searchVods(b, wd) } catch (_: Throwable) { emptyList<Vod>() }
            list.filter { matchName(it.name, wd) }.map { b to it }
        }) }
        for (f in futures) {
            try { out.addAll(f.get(30, java.util.concurrent.TimeUnit.SECONDS)) } catch (_: Throwable) {}
        }
        pool.shutdownNow()
        val exact = out.filter { it.second.name.replace(" ", "").equals(wd.replace(" ", ""), true) }
        val fuzzy = out.filter { it !in exact }
        return exact + fuzzy
    }

    private fun matchName(name: String, wd: String): Boolean {
        val n = name.replace(" ", "").lowercase()
        val w = wd.replace(" ", "").lowercase()
        if (w.isEmpty() || n.isEmpty()) return false
        return n == w || n.contains(w) || w.contains(n)
    }

    fun getDetail(bean: SourceBean, vodId: String): DetailInfo {
        if (bean.isType012) return CmsApi.getDetail(bean, vodId)
        val sp = spiderFor(bean) ?: return DetailInfo()
        val j = json(try { sp.detailContent(arrayListOf(vodId)) } catch (_: Throwable) { "" })
        return detailOf(j)
    }

    /**
     * 播放地址。flag：线路名；ep：选集 id。
     * Spider 的 playerContent 返回 parse=1/jx=1 时再走嗅探+解析器（复用 CmsApi.getPlayUrl）。
     */
    fun getPlayUrl(bean: SourceBean, flag: String, ep: String): PlayResult {
        if (bean.isType012) return CmsApi.getPlayUrl(bean, ep)
        val sp = spiderFor(bean) ?: return PlayResult(ep, null)
        val j = json(try { sp.playerContent(flag, ep, ArrayList()) } catch (_: Throwable) { "" })
            ?: return PlayResult(ep, null)
        var url = j.optString("url", "")
        if (url.isEmpty()) return PlayResult(ep, null)
        val playUrl = j.optString("playUrl", "")
        if (playUrl.isNotEmpty()) url = playUrl + url
        val parse = j.optInt("parse", if (j.optString("parse", "0") == "1") 1 else 0)
        val jx = j.optInt("jx", 0)
        val headers = headersOf(j)
        return if (parse == 1 || jx == 1) {
            val r = CmsApi.getPlayUrl(bean, url)
            PlayResult(r.url, if (r.headers.isNullOrEmpty()) headers else r.headers)
        } else {
            PlayResult(url, headers.ifEmpty { null })
        }
    }

    // ---------- Spider JSON → 应用模型 ----------

    private fun vodsOf(j: JSONObject?): List<Vod> {
        val out = ArrayList<Vod>()
        val arr = j?.optJSONArray("list") ?: return out
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            out.add(Vod(v.optString("vod_id", ""), v.optString("vod_name", ""),
                v.optString("vod_pic", ""), v.optString("vod_remarks", "")))
        }
        return out
    }

    private fun pageCountOf(j: JSONObject?): Int {
        val pc = j?.opt("pagecount") ?: return 1
        return when (pc) {
            is Int -> pc
            is String -> pc.toIntOrNull() ?: 1
            else -> 1
        }
    }

    private fun detailOf(j: JSONObject?): DetailInfo {
        val info = DetailInfo()
        val arr = j?.optJSONArray("list") ?: return info
        val v = arr.optJSONObject(0) ?: return info
        info.name = v.optString("vod_name", "")
        info.pic = v.optString("vod_pic", "")
        info.remark = v.optString("vod_remarks", "")
        info.actors = v.optString("vod_actor", "")
        info.director = v.optString("vod_director", "")
        info.content = CmsApi.cleanHtml(v.optString("vod_content", ""))
        info.year = v.optString("vod_year", "")
        info.area = v.optString("vod_area", "")
        info.lang = v.optString("vod_lang", "")
        info.cls = v.optString("type_name", v.optString("vod_class", ""))
        val from = v.optString("vod_play_from", "")
        val urls = v.optString("vod_play_url", "")
        val flags = from.split("\\$\\$\\$".toRegex())
        val groups = urls.split("\\$\\$\\$".toRegex())
        for (i in flags.indices) {
            val names = ArrayList<String>()
            val eps = ArrayList<String>()
            for (ep in (groups.getOrNull(i) ?: "").split("#")) {
                val idx = ep.indexOf('$')
                if (idx > 0) {
                    names.add(ep.substring(0, idx).trim())
                    eps.add(ep.substring(idx + 1).trim())
                }
            }
            if (eps.isNotEmpty()) {
                info.seriesFlags.add(flags[i] to names)
                info.seriesUrls.add(eps)
            }
        }
        return info
    }

    private fun headersOf(j: JSONObject?): Map<String, String> {
        val h = j?.optJSONObject("header") ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        val keys = h.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = h.optString(k, "")
            if (v.isNotEmpty()) out[k] = v
        }
        return out
    }
}
