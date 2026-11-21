package org.lddxmyg.tvbox.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 播放历史（对应原版 Keep / 观看记录）：
 * 每条含 源key + 片id + 片名 + 海报 + 线路 + 集 + 播放进度，首页「历史」一键续播。
 */
object History {

    private const val KEY = "play_history"
    private const val LIMIT = 60

    class Item(
        var src: String = "",
        var id: String = "",
        var name: String = "",
        var pic: String = "",
        var remark: String = "",
        var line: Int = 0,
        var ep: Int = 0,
        var epName: String = "",
        var pos: Long = 0,
        var dur: Long = 0,
        var time: Long = 0
    ) {
        /** 进度百分比 0..100 */
        val percent: Int get() = if (dur > 0) ((pos * 100) / dur).toInt().coerceIn(0, 100) else 0
        val finished: Boolean get() = dur > 0 && pos >= dur - 30_000
    }

    @Synchronized
    fun all(): List<Item> {
        val raw = Prefs.getString(KEY, "")
        if (raw.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val it = Item(
                    src = o.optString("src", ""),
                    id = o.optString("id", ""),
                    name = o.optString("name", ""),
                    pic = o.optString("pic", ""),
                    remark = o.optString("remark", ""),
                    line = o.optInt("line", 0),
                    ep = o.optInt("ep", 0),
                    epName = o.optString("epName", ""),
                    pos = o.optLong("pos", 0),
                    dur = o.optLong("dur", 0),
                    time = o.optLong("time", 0)
                )
                if (it.id.isEmpty() || it.name.isEmpty()) null else it
            }
        } catch (_: Exception) { emptyList() }
    }

    /** 记录/更新一条（同源同片覆盖，最新在最前） */
    @Synchronized
    fun record(item: Item) {
        if (item.id.isEmpty() || item.name.isEmpty()) return
        item.time = System.currentTimeMillis()
        val list = all().toMutableList()
        val old = list.firstOrNull { it.src == item.src && it.id == item.id }
        // 播放页拿不到海报/备注时不要把已有信息清掉
        if (old != null) {
            if (item.pic.isEmpty()) item.pic = old.pic
            if (item.remark.isEmpty()) item.remark = old.remark
        }
        list.removeAll { it.src == item.src && it.id == item.id }
        list.add(0, item)
        while (list.size > LIMIT) list.removeAt(list.size - 1)
        save(list)
    }

    /** 只更新进度（不清空其它字段） */
    @Synchronized
    fun updateProgress(src: String, id: String, pos: Long, dur: Long) {
        if (src.isEmpty() || id.isEmpty()) return
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.src == src && it.id == id }
        if (idx < 0) return
        list[idx].pos = pos
        list[idx].dur = dur
        list[idx].time = System.currentTimeMillis()
        val item = list.removeAt(idx)
        list.add(0, item)
        save(list)
    }

    @Synchronized
    fun remove(src: String, id: String) {
        val list = all().toMutableList()
        list.removeAll { it.src == src && it.id == id }
        save(list)
    }

    @Synchronized
    fun clear() = save(emptyList())

    private fun save(list: List<Item>) {
        val arr = JSONArray()
        list.forEach { it ->
            arr.put(JSONObject().apply {
                put("src", it.src); put("id", it.id); put("name", it.name)
                put("pic", it.pic); put("remark", it.remark)
                put("line", it.line); put("ep", it.ep); put("epName", it.epName)
                put("pos", it.pos); put("dur", it.dur); put("time", it.time)
            })
        }
        Prefs.putString(KEY, arr.toString())
    }
}
