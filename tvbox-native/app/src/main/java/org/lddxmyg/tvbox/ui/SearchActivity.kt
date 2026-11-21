package org.lddxmyg.tvbox.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.lddxmyg.tvbox.R
import org.lddxmyg.tvbox.core.PicLoader
import org.lddxmyg.tvbox.core.Prefs
import org.lddxmyg.tvbox.core.SourceApi
import org.lddxmyg.tvbox.core.SourceBean
import org.lddxmyg.tvbox.core.Vod
import java.util.concurrent.atomic.AtomicInteger

/**
 * 全源搜索（复刻原版 TVBox）：
 * 同时向配置里所有 searchable=1 的源并发搜索，边搜边出结果，
 * 每条结果标注来源源名，点进详情时带上该源 key（此前不带，导致用当前源加载必失败）。
 */
class SearchActivity : AppCompatActivity() {

    private val results = ArrayList<Pair<SourceBean, Vod>>()
    private val token = AtomicInteger(0)
    private var cancel: () -> Unit = {}

    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "搜索"

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(14), dp(14), dp(14), 0)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        input = EditText(this)
        input.hint = "输入片名，搜索全部源"
        input.setTextColor(resources.getColor(R.color.textPrimary, theme))
        input.setHintTextColor(resources.getColor(R.color.textSecondary, theme))
        input.setSingleLine(true)
        input.textSize = 15f
        input.imeOptions = EditorInfo.IME_ACTION_SEARCH
        input.setBackgroundResource(R.color.surface)
        input.setPadding(dp(14), dp(10), dp(14), dp(10))
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(input.text.toString().trim()); true
            } else false
        }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val btn = TextView(this)
        btn.text = "搜索"
        btn.setTextColor(resources.getColor(R.color.accent, theme))
        btn.textSize = 16f
        btn.setPadding(dp(20), dp(10), dp(6), dp(10))
        btn.setOnClickListener { doSearch(input.text.toString().trim()) }
        row.addView(btn)
        root.addView(row)

        status = TextView(this)
        status.setTextColor(resources.getColor(R.color.textSecondary, theme))
        status.textSize = 12f
        status.setPadding(dp(2), dp(10), 0, dp(6))
        root.addView(status)

        val recycler = RecyclerView(this)
        root.addView(recycler, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = results.size
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(
                    layoutInflater.inflate(R.layout.item_vod, parent, false)) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val (bean, v) = results[pos]
                val v0 = h.itemView
                PicLoader.load(v0.findViewById(R.id.imgPoster), v.pic)
                v0.findViewById<TextView>(R.id.txtName).text = v.name
                v0.findViewById<TextView>(R.id.txtRemark).text =
                    if (v.remark.isBlank()) bean.name else "${bean.name} · ${v.remark}"
                v0.setOnClickListener {
                    val it = Intent(this@SearchActivity, DetailActivity::class.java)
                    it.putExtra("vod_id", v.id)
                    it.putExtra("vod_name", v.name)
                    it.putExtra("source_key", bean.key)
                    startActivity(it)
                }
            }
        }
        recycler.layoutManager = GridLayoutManager(this, 3)
        recycler.adapter = adapter

        val count = SourceApi.searchableSources().size
        status.text = if (count > 0) "将同时搜索 $count 个源" else "当前配置里没有可搜索的源"

        // 回填上次搜索词，聚焦并弹键盘
        val preset = intent.getStringExtra("wd").orEmpty()
        val last = if (preset.isNotEmpty()) preset else Prefs.getString("last_search", "")
        if (last.isNotEmpty()) {
            input.setText(last)
            input.setSelection(last.length)
        }
        input.requestFocus()
        if (preset.isNotEmpty()) input.post { doSearch(preset) }
    }

    private fun doSearch(wd: String) {
        if (wd.isEmpty()) {
            Toast.makeText(this, "请输入关键词", Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.putString("last_search", wd)
        val my = token.incrementAndGet()
        cancel()
        results.clear()
        adapter.notifyDataSetChanged()

        val total = SourceApi.searchableSources().size
        if (total == 0) {
            status.text = "当前配置里没有可搜索的源，请先更换接口"
            return
        }
        status.text = "正在同时搜索 $total 个源…"

        cancel = SourceApi.searchAllStream(
            wd,
            onSource = { bean, list, done, all ->
                runOnUiThread {
                    if (my != token.get() || isFinishing || isDestroyed) return@runOnUiThread
                    if (list.isNotEmpty()) {
                        val start = results.size
                        list.forEach { results.add(bean to it) }
                        adapter.notifyItemRangeInserted(start, list.size)
                    }
                    status.text = "已搜 $done/$all 个源 · 找到 ${results.size} 条"
                }
            },
            onDone = { all ->
                runOnUiThread {
                    if (my != token.get() || isFinishing || isDestroyed) return@runOnUiThread
                    status.text = if (results.isEmpty()) "没有搜到「$wd」（已搜 $all 个源），换个关键词试试"
                                 else "「$wd」共 ${results.size} 条结果（已搜 $all 个源）"
                }
            })
    }

    override fun onDestroy() {
        cancel()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
