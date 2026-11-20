package org.lddxmyg.tvbox.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.lddxmyg.tvbox.R
import org.lddxmyg.tvbox.core.SourceApi
import org.lddxmyg.tvbox.core.State
import org.lddxmyg.tvbox.core.Vod

class SearchActivity : AppCompatActivity() {

    private val results = ArrayList<Vod>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "搜索"

        val root = android.widget.LinearLayout(this)
        root.orientation = android.widget.LinearLayout.VERTICAL
        root.setPadding(32, 32, 32, 32)

        val row = android.widget.LinearLayout(this)
        row.orientation = android.widget.LinearLayout.HORIZONTAL
        val input = EditText(this)
        input.hint = "输入片名搜索当前源"
        input.setTextColor(resources.getColor(R.color.textPrimary, theme))
        input.setSingleLine(true)
        row.addView(input, android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val btn = TextView(this)
        btn.text = "搜索"
        btn.setTextColor(resources.getColor(R.color.accent, theme))
        btn.textSize = 16f
        btn.setPadding(32, 0, 0, 0)
        btn.setOnClickListener { doSearch(input.text.toString().trim()) }
        row.addView(btn)
        root.addView(row)

        val recycler = RecyclerView(this)
        recycler.layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = 32 }
        val empty = TextView(this)
        empty.text = "输入关键词开始搜索"
        empty.setTextColor(resources.getColor(R.color.textSecondary, theme))
        empty.gravity = android.view.Gravity.CENTER
        root.addView(recycler, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(empty)
        setContentView(root)

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = results.size
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(
                    layoutInflater.inflate(R.layout.item_vod, parent, false)) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val v = results[pos]
                val v0 = h.itemView
                val img = v0.findViewById<android.widget.ImageView>(R.id.imgPoster)
                Glide.with(img).load(v.pic).centerCrop().placeholder(R.color.surface).into(img)
                v0.findViewById<TextView>(R.id.txtName).text = v.name
                v0.findViewById<TextView>(R.id.txtRemark).text = v.remark
                v0.setOnClickListener {
                    val it = Intent(this@SearchActivity, DetailActivity::class.java)
                    it.putExtra("vod_id", v.id)
                    it.putExtra("vod_name", v.name)
                    startActivity(it)
                }
            }
        }
        recycler.layoutManager = GridLayoutManager(this, 3)
        recycler.adapter = adapter
        results.clear()

        doSearch = { wd ->
            if (wd.isEmpty()) {
                Toast.makeText(this, "请输入关键词", Toast.LENGTH_SHORT).show()
            } else {
                empty.text = "搜索中…"
                empty.visibility = View.VISIBLE
                Thread {
                    val list = try {
                        State.homeSource?.let { SourceApi.searchVods(it, wd) } ?: emptyList()
                    } catch (_: Exception) { emptyList() }
                    runOnUiThread {
                        results.clear()
                        results.addAll(list)
                        adapter.notifyDataSetChanged()
                        empty.text = if (list.isEmpty()) "没有搜到结果" else ""
                        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    }
                }.start()
            }
        }
    }

    private var doSearch: (String) -> Unit = {}
}
