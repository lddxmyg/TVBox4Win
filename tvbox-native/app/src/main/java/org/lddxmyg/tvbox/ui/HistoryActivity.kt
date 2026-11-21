package org.lddxmyg.tvbox.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.lddxmyg.tvbox.R
import org.lddxmyg.tvbox.core.ApiConfig
import org.lddxmyg.tvbox.core.History
import org.lddxmyg.tvbox.core.PicLoader

/** 观看历史：点条目直接续播上次那一集 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "观看历史"

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(resources.getColor(R.color.bg, theme))
        root.setPadding(16, 12, 16, 12)
        setContentView(root)

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val count = TextView(this)
        count.setTextColor(resources.getColor(R.color.textSecondary, theme))
        count.textSize = 14f
        header.addView(count, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val clear = TextView(this)
        clear.text = "清空"
        clear.setTextColor(resources.getColor(R.color.accent, theme))
        clear.textSize = 14f
        clear.setPadding(dp(12), dp(8), dp(4), dp(8))
        clear.setOnClickListener {
            if (History.all().isEmpty()) return@setOnClickListener
            AlertDialog.Builder(this).setMessage("清空全部观看历史？")
                .setPositiveButton("清空") { _, _ -> History.clear(); render() }
                .setNegativeButton("取消", null).show()
        }
        header.addView(clear)
        root.addView(header)

        val sv = ScrollView(this)
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        sv.addView(list)
        root.addView(sv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        empty = TextView(this)
        empty.text = "还没有观看记录\n去首页挑一部开始看吧"
        empty.setTextColor(resources.getColor(R.color.textSecondary, theme))
        empty.textSize = 14f
        empty.gravity = Gravity.CENTER
        empty.setPadding(0, dp(60), 0, 0)
        root.addView(empty)

        render()
        countView = count
    }

    private var countView: TextView? = null

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val items = History.all()
        list.removeAllViews()
        countView?.text = "共 ${items.size} 条记录（点条目继续观看）"
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        items.forEach { h ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(dp(8), dp(10), dp(8), dp(10))
            row.setBackgroundResource(R.color.surface)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }

            val img = ImageView(this)
            img.setBackgroundColor(resources.getColor(R.color.bg, theme))
            val ilp = LinearLayout.LayoutParams(dp(72), dp(100)).apply { rightMargin = dp(12) }
            row.addView(img, ilp)
            PicLoader.load(img, h.pic)

            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            val name = TextView(this)
            name.text = h.name
            name.setTextColor(resources.getColor(R.color.textPrimary, theme))
            name.textSize = 15f
            name.maxLines = 1
            col.addView(name)

            val sub = TextView(this)
            val srcName = ApiConfig.get(h.src)?.name ?: h.src
            val epTxt = h.epName.ifBlank { "第${h.ep + 1}集" }
            sub.text = "$epTxt · 线路${h.line + 1}" + (if (srcName.isNotBlank()) " · $srcName" else "")
            sub.setTextColor(resources.getColor(R.color.textSecondary, theme))
            sub.textSize = 12f
            sub.maxLines = 1
            col.addView(sub)

            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
            bar.max = 100
            bar.progress = if (h.finished) 100 else h.percent
            val blp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4))
            blp.topMargin = dp(8)
            col.addView(bar, blp)

            val meta = TextView(this)
            meta.text = if (h.finished) "已看完" else "已看 ${h.percent}% · ${ago(h.time)}"
            meta.setTextColor(resources.getColor(R.color.textSecondary, theme))
            meta.textSize = 11f
            meta.setPadding(0, dp(4), 0, 0)
            col.addView(meta)

            row.addView(col, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            row.setOnClickListener { open(h) }
            row.setOnLongClickListener {
                AlertDialog.Builder(this).setMessage("删除「${h.name}」的观看记录？")
                    .setPositiveButton("删除") { _, _ -> History.remove(h.src, h.id); render() }
                    .setNegativeButton("取消", null).show()
                true
            }
            list.addView(row, lp)
        }
    }

    /** 直接进详情并自动续播上次那一集；详情拿不到时会停在详情页，用户可手动选集 */
    private fun open(h: History.Item) {
        val it = Intent(this, DetailActivity::class.java)
        it.putExtra("vod_id", h.id)
        it.putExtra("vod_name", h.name)
        it.putExtra("source_key", h.src)
        it.putExtra("resume_line", h.line)
        it.putExtra("resume_ep", h.ep)
        it.putExtra("auto_play", true)
        startActivity(it)
    }

    private fun ago(t: Long): String {
        if (t <= 0) return "刚刚"
        val d = (System.currentTimeMillis() - t) / 1000
        return when {
            d < 60 -> "刚刚"
            d < 3600 -> "${d / 60} 分钟前"
            d < 86400 -> "${d / 3600} 小时前"
            d < 86400 * 30 -> "${d / 86400} 天前"
            else -> "${d / (86400 * 30)} 个月前"
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
