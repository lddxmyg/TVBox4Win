package org.lddxmyg.tvbox.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import org.lddxmyg.tvbox.R
import org.lddxmyg.tvbox.core.CmsApi
import org.lddxmyg.tvbox.core.DetailInfo
import org.lddxmyg.tvbox.core.SourceApi
import org.lddxmyg.tvbox.core.State

class DetailActivity : AppCompatActivity() {

    private var vodId = ""
    private var lineIndex = 0
    private var detail: DetailInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        vodId = intent.getStringExtra("vod_id") ?: ""
        val name = intent.getStringExtra("vod_name") ?: ""
        title = name

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(24, 24, 24, 24)
        setContentView(root)

        val scroll = android.widget.ScrollView(this)
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
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

        val progress = View(this)
        progress.setBackgroundColor(0)
        setLoadingView(body)

        Thread {
            val bean = State.homeSource!!
            val d = try { SourceApi.getDetail(bean, vodId) } catch (e: Exception) { DetailInfo() }
            detail = d
            runOnUiThread {
                body.removeAllViews()
                renderInfo(body, d)
                renderLines(bottom, d)
            }
        }.start()
    }

    private fun setLoadingView(body: LinearLayout) {
        val tv = TextView(this)
        tv.text = "加载中…"
        tv.setTextColor(resources.getColor(R.color.textSecondary, theme))
        tv.gravity = Gravity.CENTER
        tv.setPadding(0, 80, 0, 80)
        body.addView(tv)
    }

    private fun renderInfo(body: LinearLayout, d: DetailInfo) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val img = ImageView(this)
        img.setBackgroundColor(resources.getColor(R.color.surface, theme))
        img.layoutParams = LinearLayout.LayoutParams(dp(110), dp(150)).apply { rightMargin = dp(14) }
        Glide.with(img).load(d.pic).centerCrop().placeholder(R.color.surface).into(img)
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
            t.text = "没有可播放的选集"
            t.setTextColor(resources.getColor(R.color.textSecondary, theme))
            bottom.addView(t)
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
                val it = Intent(this, PlayActivity::class.java)
                it.putExtra("ep_url", urls[i])
                it.putExtra("ep_name", t.text)
                it.putExtra("line_flag", d.seriesFlags.getOrNull(lineIndex)?.first ?: "")
                startActivity(it)
            }
            grid.addView(t)
        }
        bottom.addView(grid)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
