package org.lddxmyg.tvbox.core

import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import org.lddxmyg.tvbox.R
import java.net.URLDecoder

/**
 * 复刻原版 MyOkhttpDownLoader 的图片 URL 约定（修海报不显示）：
 *   url@Headers={"k":"v"}@Cookie=x@User-Agent=x@Referer=x
 * 爬虫返回的 vod_pic 常带 @ 后缀（如豆瓣源），直接当 URL 加载必失败。
 * 剥掉后缀、按需附加 Referer/UA/Cookie 再交给 Glide。
 */
object PicLoader {
    private const val DEFAULT_UA =
        "Dalvik/2.1.0 (Linux; U; Android 13; M2102J2SC Build/TKQ1.220829.002)"

    fun load(view: ImageView, raw: String?) {
        if (raw.isNullOrBlank()) return
        var url: String = raw
        var ua = ""
        var referer = ""
        var cookie = ""
        try {
            if (url.contains("@Headers=")) {
                val json = URLDecoder.decode(url.split("@Headers=")[1].split("@")[0], "UTF-8")
                val obj = org.json.JSONObject(json)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.optString(k, "")
                    if (v.isNotBlank()) when {
                        k.equals("User-Agent", true) -> ua = v
                        k.equals("Referer", true) -> referer = v
                        k.equals("Cookie", true) -> cookie = v
                    }
                }
            }
            if (url.contains("@Cookie=")) cookie = url.split("@Cookie=")[1].split("@")[0]
            if (url.contains("@User-Agent=")) ua = url.split("@User-Agent=")[1].split("@")[0]
            if (url.contains("@Referer=")) referer = url.split("@Referer=")[1].split("@")[0]
            url = url.split("@")[0]
        } catch (_: Exception) {
        }
        if (!url.startsWith("http")) return
        val builder = LazyHeaders.Builder().addHeader("User-Agent", ua.ifBlank { DEFAULT_UA })
        if (referer.isNotBlank()) builder.addHeader("Referer", referer)
        if (cookie.isNotBlank()) builder.addHeader("Cookie", cookie)
        Glide.with(view)
            .load(GlideUrl(url, builder.build()))
            .centerCrop()
            .placeholder(R.color.surface)
            .into(view)
    }
}
