package org.lddxmyg.tvbox.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.lddxmyg.tvbox.R
import org.lddxmyg.tvbox.core.ApiConfig
import org.lddxmyg.tvbox.core.CmsApi
import org.lddxmyg.tvbox.core.DetailInfo
import org.lddxmyg.tvbox.core.PicLoader
import org.lddxmyg.tvbox.core.SourceApi
import org.lddxmyg.tvbox.core.SourceBean
import org.lddxmyg.tvbox.core.State
import org.lddxmyg.tvbox.core.Vod

class DetailActivity : AppCompatActivity() {

    private var vodId = ""
    private var vodName = ""
    private var lineIndex = 0
    private var detail: DetailInfo? = null
    // msearch 聚合后选中的真实播放源；普通源为 null（用 State.homeSource）
    private var playSource: SourceBean? = null
    private lateinit var bodyView: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        vodId = intent.getStringExtra("vod_id") ?: ""
        vodName = intent.getStringExtra("vod_name") ?: ""
        title = vodName

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(24, 24, 24, 24)
        setContentView(root)

        val scroll = android.widget.ScrollView(this)
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        bodyView = body
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(View(this).apply { setBackgroundColor(resources.getColor(R.color.accent, theme)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 3))
        // 底部：线路+选集区
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.setPadding(24, 16, 24, 16)
        bottom.id = View.generateViewId()
        root.addView(bottom, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.6f))

        if (vodId.startsWith("msearch:")) {
            // 复刻原版：仅推荐源（豆瓣类）走全源聚合搜索，先选真实播放源
            renderAggregateSearch(body, bottom)
        } else {
            setLoadingView(body)
            // 搜索结果会带 source_key：必须用产出该条目的源加载详情（此前一律用 homeSource，导致打不开）
            val srcKey = intent.getStringExtra("source_key").orEmpty()
            val src = if (srcKey.isNotEmpty()) ApiConfig.get(srcKey) ?: State.homeSource
                      else State.homeSource
            loadDetail(src, vodId, body, bottom)
        }
    }

    /** 常规详情加载：bean 为 null 时用 homeSource */
    private fun loadDetail(bean: SourceBean?, id: String, body: LinearLayout, bottom: LinearLayout) {
        if (bean == null) {
            body.removeAllViews()
            body.addView(hintText("当前没有可用源"))
            return
        }
        playSource = bean
        vodId = id   // 聚合后用的是真实条目 id，播放上下文要用它
        val src = bean
        Thread {
            val d = try { SourceApi.getDetail(src, id) } catch (e: Exception) { DetailInfo() }
            detail = d
            runOnUiThread {
                body.removeAllViews()
                renderInfo(body, d)
                renderLines(bottom, d)
            }
        }.start()
    }

    /** msearch: 前缀 → 全源聚合搜索出真实条目，用户点选后加载对应源详情 */
    private fun renderAggregateSearch(body: LinearLayout, bottom: LinearLayout) {
        body.removeAllViews()
        bottom.removeAllViews()
        val loading = hintText("正在全源聚合搜索「$vodName」…")
        body.addView(loading)
        Thread {
            val candidates = try { SourceApi.searchAllSources(vodName) } catch (_: Throwable) { emptyList() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                body.removeAllViews()
                bottom.removeAllViews()
                if (candidates.isEmpty()) {
                    body.addView(hintText("没有聚合到可播放源\n试试切换其他源或更换接口"))
                    return@runOnUiThread
                }
                body.addView(hintText("聚合到 ${candidates.size} 个播放源，点选观看："))
                candidates.take(30).forEach { (bean, vod) ->
                    val row = LinearLayout(this)
                    row.orientation = LinearLayout.HORIZONTAL
                    row.setPadding(dp(8), dp(12), dp(8), dp(12))
                    row.setBackgroundResource(R.color.surface)
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
                    val name = TextView(this)
                    name.text = "${bean.name}"
                    name.setTextColor(resources.getColor(R.color.accent, theme))
                    name.textSize = 13f
                    name.setPadding(dp(10), 0, dp(10), 0)
                    name.gravity = Gravity.CENTER_VERTICAL
                    row.addView(name, LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.MATCH_PARENT, 0.38f))
                    val vodNameTv = TextView(this)
                    vodNameTv.text = vod.name + (if (vod.remark.isNotBlank()) " · ${vod.remark}" else "")
                    vodNameTv.setTextColor(resources.getColor(R.color.textPrimary, theme))
                    vodNameTv.textSize = 14f
                    row.addView(vodNameTv, LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.MATCH_PARENT, 0.62f))
                    row.setOnClickListener {
                        loading.visibility = View.VISIBLE
                        loading.text = "正在加载「${vod.name}」详情…"
                        loadDetail(bean, vod.id, body, bottom)
                    }
                    body.addView(row, lp)
                }
            }
        }.start()
    }

    private fun hintText(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(resources.getColor(R.color.textSecondary, theme))
        tv.textSize = 14f
        tv.setPadding(0, dp(24), 0, dp(24))
        return tv
    }

    private fun setLoadingView(body: LinearLayout) {
        body.addView(hintText("加载中…"))
    }

    private fun renderInfo(body: LinearLayout, d: DetailInfo) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val img = ImageView(this)
        img.setBackgroundColor(resources.getColor(R.color.surface, theme))
        img.layoutParams = LinearLayout.LayoutParams(dp(110), dp(150)).apply { rightMargin = dp(14) }
        PicLoader.load(img, d.pic)
        row.addView(img)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        fun line(label: String, value: String) {
            if (value.isBlank()) return
            val t = TextView(this)
            t.text = "$label  $value"
            t.setTextColor(resources.getColor(R.color.textSecondary, theme))
            t.textSize = 13f
            col.addView(t)
        }
        val title = TextView(this)
        title.text = d.name
        title.textSize = 19f
        title.setTextColor(resources.getColor(R.color.textPrimary, theme))
        col.addView(title)
        line("年份", d.year); line("地区", d.area); line("语言", d.lang)
        line("类型", d.cls); line("导演", d.director); line("演员", d.actors)
        d.remark.takeIf { it.isNotBlank() }?.let {
            val r = TextView(this); r.text = it
            r.setTextColor(resources.getColor(R.color.accent, theme)); r.textSize = 13f
            col.addView(r)
        }
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(row)

        if (d.content.isNotBlank()) {
            val content = TextView(this)
            content.text = d.content
            content.setTextColor(resources.getColor(R.color.textSecondary, theme))
            content.textSize = 13f
            content.setPadding(0, dp(12), 0, 0)
            body.addView(content)
        }
    }

    private fun renderLines(bottom: LinearLayout, d: DetailInfo) {
        bottom.removeAllViews()
        if (d.seriesFlags.isEmpty()) {
            val t = TextView(this)
            t.text = "该源没有可播放地址"
            t.setTextColor(resources.getColor(R.color.textSecondary, theme))
            bottom.addView(t)
            // 兜底：换源播放（此前这里只有一行死文案，用户无路可走）
            if (vodName.isNotBlank()) {
                val btn = TextView(this)
                btn.text = "搜索其他源播放"
                btn.setTextColor(resources.getColor(R.color.accent, theme))
                btn.textSize = 14f
                btn.setPadding(0, dp(10), 0, dp(10))
                btn.setOnClickListener { renderAggregateSearch(bodyView, bottom) }
                bottom.addView(btn)
            }
            return
        }
        val lineRow = LinearLayout(this)
        lineRow.orientation = LinearLayout.HORIZONTAL
        d.seriesFlags.forEachIndexed { i, (flag, _) ->
            val t = TextView(this)
            t.text = flag.ifBlank { "线路${i + 1}" }
            t.setPadding(dp(14), dp(8), dp(14), dp(8))
            t.setTextColor(if (i == lineIndex) resources.getColor(R.color.accent, theme)
                else resources.getColor(R.color.textPrimary, theme))
            t.textSize = 14f
            t.setOnClickListener { lineIndex = i; renderLines(bottom, d) }
            lineRow.addView(t)
        }
        // 常驻换源入口（任何条目都能换到其他源播）
        val sw = TextView(this)
        sw.text = "换源"
        sw.setPadding(dp(14), dp(8), dp(14), dp(8))
        sw.setTextColor(resources.getColor(R.color.textSecondary, theme))
        sw.textSize = 13f
        sw.setOnClickListener { renderAggregateSearch(bodyView, bottom) }
        lineRow.addView(sw)
        bottom.addView(lineRow)

        val grid = GridLayout(this)
        grid.columnCount = 4
        val urls = d.seriesUrls.getOrNull(lineIndex) ?: emptyList()
        val names = d.seriesFlags.getOrNull(lineIndex)?.second ?: emptyList()
        urls.forEachIndexed { i, _ ->
            val t = TextView(this)
            t.text = names.getOrNull(i)?.ifBlank { "第${i + 1}集" } ?: "第${i + 1}集"
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(10), 0, dp(10))
            t.setBackgroundResource(R.color.surface)
            t.setTextColor(resources.getColor(R.color.textPrimary, theme))
            t.textSize = 13f
            val p = GridLayout.LayoutParams()
            p.width = 0
            p.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            p.setMargins(dp(3), dp(3), dp(3), dp(3))
            t.layoutParams = p
            t.setOnClickListener {
                // 写入播放上下文：播放页据此渲染选集面板/自动下一集/换源
                State.playDetail = d
                State.playSource = playSource ?: State.homeSource
                State.playVodId = vodId
                State.playVodName = vodName.ifBlank { d.name }
                State.playLineIndex = lineIndex
                val it = Intent(this, PlayActivity::class.java)
                it.putExtra("use_detail", true)
                it.putExtra("ep_url", urls[i])
                it.putExtra("ep_name", t.text)
                it.putExtra("line_flag", d.seriesFlags.getOrNull(lineIndex)?.first ?: "")
                it.putExtra("source_key", playSource?.key ?: "")
                it.putExtra("vod_name", vodName.ifBlank { d.name })
                startActivity(it)
            }
            grid.addView(t)
        }
        val gs = android.widget.ScrollView(this)
        gs.addView(grid)
        bottom.addView(gs)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
