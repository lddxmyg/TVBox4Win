package org.lddxmyg.tvbox.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 对应 hawk.py：json 持久化（Android 版落到 filesDir/hawk.json） */
object Prefs {
    private lateinit var file: File
    private val data = LinkedHashMap<String, Any?>()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "hawk.json")
        if (file.exists()) {
            try {
                val obj = JSONObject(file.readText())
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    data[k] = obj.opt(k)
                }
            } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun getString(key: String, def: String = ""): String =
        (data[key] as? String) ?: def

    @Synchronized
    fun putString(key: String, value: String) {
        data[key] = value
        flush()
    }

    @Synchronized
    fun getList(key: String): List<String> {
        val arr = data[key] as? JSONArray ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) out.add(arr.optString(i, ""))
        return out
    }

    @Synchronized
    fun addHistory(key: String, value: String, limit: Int = 10) {
        val list = getList(key).toMutableList()
        list.remove(value)
        list.add(0, value)
        while (list.size > limit) list.removeAt(list.size - 1)
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        data[key] = arr
        flush()
    }

    private fun flush() {
        try {
            val obj = JSONObject()
            data.forEach { (k, v) -> obj.put(k, v ?: JSONObject.NULL) }
            file.writeText(obj.toString(1))
        } catch (_: Exception) {}
    }
}

/** 对应 apiconfig.ApiConfig：sources / parses / lives / flags / ads / hosts */
object ApiConfig {
    /** 默认：残月影视多仓（tvbox.txt 是多仓索引，含多条线路，自动选可用线路） */
    const val DEFAULT_API = "https://gh-proxy.com/https://raw.githubusercontent.com/1771245847/TvBox/master/tvbox.txt"

    /** 预设源（配置页一键切换） */
    val PRESET_APIS: List<Pair<String, String>> = listOf(
        "残月多仓（默认）" to DEFAULT_API,
        "6800 fish 蜘蛛源" to "https://6800.kstore.vip/fish.json"
    )

    /** 历史默认源/测试源：见到这些就自动迁到当前默认（用户手动设置的其它地址不动） */
    private val LEGACY_APIS = listOf(
        "6800.kstore.vip/fish.json",
        "cyao2q.github.io/files/m.json",
        "cyao2q.github.io/files/jar",
        "127.0.0.1:8000",
        "localhost:8000"
    )

    fun isLegacyApi(url: String): Boolean =
        url.isBlank() || LEGACY_APIS.any { url.contains(it) }

    val sourceBeanList = ArrayList<SourceBean>()
    val parseBeanList = ArrayList<ParseBean>()
    var spider: String? = null
    var spiderUrl = ""   // 全局 spider jar 的完整地址（含 ;md5;xxx 尾巴）
    var lives: JSONArray = JSONArray()
    var flags: List<String> = emptyList()
    var lastFromCache = false
    var currentUrl = ""

    /** 多仓：repoList 为索引里的线路清单；repoPickIndex 为当前使用的线路下标；multiUrl 为多仓索引地址 */
    class RepoEntry(val name: String, val url: String)

    var repoList: List<RepoEntry> = emptyList()
    var repoPickIndex = -1
    var repoName = ""
    var multiUrl = ""

    private val adHosts = HashSet<String>()
    private val hostsMap = HashMap<String, String>()
    private var loadLock = Object()

    fun isAdHost(url: String): Boolean = adHosts.any { it.isNotEmpty() && url.contains(it) }

    fun hostMapped(host: String): String? = hostsMap[host]

    fun get(key: String): SourceBean? = sourceBeanList.firstOrNull { it.key == key }

    fun sources012(): List<SourceBean> = sourceBeanList.filter { it.isType012 }

    /** 加载配置（后台线程，回调回主线程由调用方决定） */
    fun load(apiUrl: String, okCb: () -> Unit, errCb: (String) -> Unit) {
        Thread {
            synchronized(loadLock) {
                lastFromCache = false
                try {
                    var url = (apiUrl ?: "").trim()
                    var configKey: String? = null
                    if (url.contains(";pk;")) {
                        val parts = url.split(";pk;")
                        url = parts[0]
                        configKey = if (parts.size > 1) parts[1] else ""
                    }
                    if (!url.startsWith("http") && !File(url).exists()) url = "http://" + url

                    val r = resolveConfig(url, configKey, 0)
                    if (r == null) {
                        errCb(if (repoList.isNotEmpty()) "所有线路都拉取失败（共 ${repoList.size} 条），请检查网络或换接口"
                              else "配置拉取失败（网络不可达或地址无效），且没有可用缓存")
                        return@synchronized
                    }
                    currentUrl = r.url
                    parse(r.cfg)
                    lastFromCache = r.fromCache
                    // 多仓时 home_api 记用户输入的索引地址，保留线路切换能力
                    Prefs.addHistory("api_history", url)
                    Prefs.putString("home_api", url)
                    okCb()
                } catch (e: Exception) {
                    errCb("配置加载异常：${e.message}")
                }
            }
        }.start()
    }

    private class Resolved(val cfg: JSONObject, val url: String, val fromCache: Boolean)

    /**
     * 解析配置：普通单仓直接返回；多仓（有 urls 无 sites）则按线路清单逐个试，
     * 优先上次成功的那条，成功后把线路写进 Prefs，下次直接命中。
     */
    private fun resolveConfig(url: String, configKey: String?, depth: Int): Resolved? {
        val (cfg, cached) = fetchCfg(url, configKey, withCache = true)
        if (cfg == null) return null
        val urlsArr = cfg.optJSONArray("urls")
        val sitesArr = cfg.optJSONArray("sites")
        val isMulti = urlsArr != null && urlsArr.length() > 0 &&
                (sitesArr == null || sitesArr.length() == 0)
        if (!isMulti || depth >= 2) {
            if (!isMulti) {
                repoList = emptyList(); repoPickIndex = -1; repoName = ""; multiUrl = ""
            }
            return Resolved(cfg, url, cached)
        }

        val entries = ArrayList<RepoEntry>()
        for (i in 0 until urlsArr!!.length()) {
            val o = urlsArr.optJSONObject(i) ?: continue
            val su = o.optString("url", "").trim()
            if (su.isEmpty()) continue
            entries.add(RepoEntry(o.optString("name", "线路${entries.size + 1}"), su))
        }
        repoList = entries
        multiUrl = url
        saveRepoList(url, entries)
        if (entries.isEmpty()) return Resolved(cfg, url, cached)

        val remembered = Prefs.getString(repoPickKey(url), "").toIntOrNull() ?: -1
        val order = LinkedHashSet<Int>()
        if (remembered in entries.indices) order.add(remembered)
        entries.indices.forEach { order.add(it) }

        for (i in order) {
            val sub = resolveRef(url, entries[i].url) ?: entries[i].url
            val (subCfg, subCached) = fetchCfg(sub, null, withCache = true)
            val n = subCfg?.optJSONArray("sites")?.length() ?: 0
            if (subCfg != null && n > 0) {
                repoPickIndex = i
                repoName = entries[i].name
                Prefs.putString(repoPickKey(url), i.toString())
                return Resolved(subCfg, sub, subCached)
            }
        }
        // 线路全挂：若索引自身带 sites 就用它，否则让上层报错（缓存也会在这里兜过一遍）
        return if ((sitesArr?.length() ?: 0) > 0) Resolved(cfg, url, cached) else null
    }

    /** 拉取一条配置并解析；网络失败回落该地址的本地缓存 */
    private fun fetchCfg(url: String, configKey: String?, withCache: Boolean): Pair<JSONObject?, Boolean> {
        var text: String? = null
        if (File(url).exists()) {
            text = try { File(url).readText() } catch (_: Exception) { null }
        } else {
            text = ConfigIo.fetchText(url, timeoutSec = 12, retries = 1)
        }
        val fromNet = !text.isNullOrEmpty()
        if (!text.isNullOrEmpty()) text = ConfigIo.decodeConfig(text, configKey)
        var cached = false
        if (ConfigIo.extractJson(text) == null && withCache) {
            val cf = cacheFile(url)
            if (cf.exists()) {
                val t = try { cf.readText() } catch (_: Exception) { null }
                if (ConfigIo.extractJson(t) != null) { text = t; cached = true }
            }
        }
        if (fromNet && !cached) {
            try { cacheFile(url).writeText(text ?: "") } catch (_: Exception) {}
        }
        return ConfigIo.extractJson(text) to cached
    }

    private fun repoPickKey(url: String) = "repo_pick_" + Integer.toHexString(url.hashCode())

    private fun saveRepoList(url: String, entries: List<RepoEntry>) {
        try {
            val arr = JSONArray()
            entries.forEach { e -> arr.put(JSONObject().put("name", e.name).put("url", e.url)) }
            Prefs.putString("repo_list", arr.toString())
            Prefs.putString("repo_list_url", url)
        } catch (_: Exception) {}
    }

    /** 配置页切线路：写入选择后由调用方重新 load */
    fun pickRepo(index: Int) {
        if (index !in repoList.indices) return
        Prefs.putString(repoPickKey(multiUrl), index.toString())
    }

    /**
     * 缓存按配置地址分开存：否则换源后新地址拉取失败会回落成旧源的缓存，
     * 表现就是"换了源还是老样子"。
     */
    private fun cacheFile(url: String = currentUrl): File =
        File(PrefsRunDir.dir, "tvbox_" + Integer.toHexString(url.hashCode()) + ".json")

    fun resolveRef(baseUrl: String, ref: String?): String? {
        if (ref == null) return null
        if (!(ref.startsWith("./") || ref.startsWith("../"))) return ref
        if (baseUrl.startsWith("http")) {
            return try {
                java.net.URI(baseUrl).resolve(ref).toString()
            } catch (_: Exception) { ref }
        }
        val base = File(baseUrl).parentFile ?: return ref
        return File(base, ref.replace('/', File.separatorChar)).normalize().path
    }

    private fun parse(cfg: JSONObject) {
        spider = cfg.optString("spider", null)
        spiderUrl = resolveRef(currentUrl, spider) ?: ""
        val ads = cfg.optJSONArray("ads")
        adHosts.clear()
        if (ads != null) for (i in 0 until ads.length()) {
            val a = ads.optString(i, "")
            if (a.isNotEmpty()) adHosts.add(a)
        }
        hostsMap.clear()
        val hosts = cfg.optJSONArray("hosts")
        if (hosts != null) for (i in 0 until hosts.length()) {
            val entry = hosts.optString(i, "")
            val idx = entry.indexOf('=')
            if (idx > 0 && idx < entry.length - 1) {
                hostsMap[entry.substring(0, idx).trim().lowercase().trimEnd('.')] =
                    entry.substring(idx + 1).trim()
            }
        }
        val flagsArr = cfg.optJSONArray("flags")
        flags = if (flagsArr != null) (0 until flagsArr.length()).map { flagsArr.optString(it, "") } else emptyList()
        lives = cfg.optJSONArray("lives") ?: JSONArray()

        sourceBeanList.clear()
        val sites = cfg.optJSONArray("sites") ?: JSONArray()
        for (i in 0 until sites.length()) {
            val d = sites.optJSONObject(i) ?: continue
            val b = SourceBean.fromJson(d)
            if (b.name.isEmpty() || b.api.isEmpty()) continue
            b.api = resolveRef(currentUrl, b.api) ?: b.api
            if (b.jar.isNotEmpty()) b.jar = resolveRef(currentUrl, b.jar) ?: b.jar
            // drpy 引擎要拿 ext 去拉规则文件，必须先解析成绝对地址
            val extStr = b.ext as? String
            if (!extStr.isNullOrEmpty()) {
                b.ext = resolveRef(currentUrl, extStr) ?: extStr
            }
            sourceBeanList.add(b)
        }

        parseBeanList.clear()
        val parses = cfg.optJSONArray("parses") ?: JSONArray()
        for (i in 0 until parses.length()) {
            val d = parses.optJSONObject(i) ?: continue
            val ptype = d.optInt("type", d.optInt("flag", 0))
            val purl = resolveRef(currentUrl, d.optString("url", "")) ?: ""
            parseBeanList.add(ParseBean(d.optString("name", ""), purl, ptype))
        }
    }
}

object PrefsRunDir {
    lateinit var dir: File
    fun init(ctx: Context) { dir = ctx.filesDir }
}
