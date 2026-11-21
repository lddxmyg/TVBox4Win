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
    const val DEFAULT_API = "https://6800.kstore.vip/fish.json"

    /** 历史默认源/测试源：见到这些就自动迁到当前默认（用户手动设置的其它地址不动） */
    private val LEGACY_APIS = listOf(
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

                    var text: String? = null
                    if (File(url).exists()) {
                        text = try { File(url).readText() } catch (_: Exception) { null }
                    } else {
                        text = ConfigIo.fetchText(url, timeoutSec = 12, retries = 1)
                    }
                    val fromNet = !text.isNullOrEmpty()
                    if (!text.isNullOrEmpty()) text = ConfigIo.decodeConfig(text, configKey)

                    var fromCache = false
                    val cacheFile = cacheFile(url)
                    if ((text == null || ConfigIo.extractJson(text) == null)) {
                        if (cacheFile.exists()) {
                            val cached = try { cacheFile.readText() } catch (_: Exception) { null }
                            if (!cached.isNullOrEmpty() && ConfigIo.extractJson(cached) != null) {
                                text = cached
                                fromCache = true
                            }
                        }
                    }
                    if (fromNet && !fromCache) {
                        try { cacheFile.writeText(text ?: "") } catch (_: Exception) {}
                    }
                    val cfg = text?.let { ConfigIo.extractJson(it) }
                    if (cfg == null) {
                        errCb(if (fromNet || fromCache) "配置解析失败，且缓存也不可用"
                              else "配置拉取失败（网络不可达或地址无效），且没有可用缓存")
                        return@synchronized
                    }
                    currentUrl = url
                    parse(cfg)
                    Prefs.addHistory("api_history", url)
                    Prefs.putString("home_api", url)
                    lastFromCache = fromCache
                    okCb()
                } catch (e: Exception) {
                    errCb("配置加载异常：${e.message}")
                }
            }
        }.start()
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
