package org.lddxmyg.tvbox.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.lddxmyg.tvbox.R
import org.lddxmyg.tvbox.core.ApiConfig
import org.lddxmyg.tvbox.core.CmsApi
import org.lddxmyg.tvbox.core.PicLoader
import org.lddxmyg.tvbox.core.SourceApi
import org.lddxmyg.tvbox.core.SourceBean
import org.lddxmyg.tvbox.core.State
import org.lddxmyg.tvbox.core.Vod

class HomeActivity : AppCompatActivity() {

    private lateinit var adapter: VodAdapter
    private var categories: List<Pair<String, String>> = emptyList()
    private var currentClassId: String? = null
    private var page = 1
    private var pageCount = 1
    @Volatile private var loading = false

    private lateinit var progress: View
    private lateinit var emptyView: TextView
    private lateinit var btnSource: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        val recycler = findViewById<RecyclerView>(R.id.recycler)
        progress = findViewById(R.id.progress)
        emptyView = findViewById(R.id.emptyView)
        btnSource = findViewById(R.id.btnSource)

        adapter = VodAdapter { vod -> openDetail(vod) }
        recycler.layoutManager = GridLayoutManager(this, 3)
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as GridLayoutManager
                if (!loading && page < pageCount &&
                    lm.findLastVisibleItemPosition() >= adapter.itemCount - 6) {
                    loadVods(currentClassId, page + 1, append = true)
                }
            }
        })

        findViewById<TextView>(R.id.btnSearch).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        findViewById<TextView>(R.id.btnConfig).setOnClickListener {
            startActivity(Intent(this, ConfigActivity::class.java))
        }
        btnSource.setOnClickListener { showSourcePicker() }

        if (State.configLoaded && State.homeSource != null) {
            loadAll()
        } else {
            loadConfig(ApiConfig.currentUrl)
        }
    }

    private fun openDetail(vod: Vod) {
        val it = Intent(this, DetailActivity::class.java)
        it.putExtra("vod_id", vod.id)
        it.putExtra("vod_name", vod.name)
        startActivity(it)
    }

    private fun showSourcePicker() {
        val sources = ApiConfig.sourceBeanList
        if (sources.isEmpty()) { Toast.makeText(this, "配置里没有源", Toast.LENGTH_SHORT).show(); return }
        val names = sources.map { s ->
            val tag = when {
                s.isSpider -> "[Jar]"
                else -> "[CMS]"
            }
            "$tag ${s.name}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择源（共 ${sources.size} 个）")
            .setItems(names) { _, which -> switchSource(sources[which]) }
            .show()
    }

    private fun loadConfig(url: String) {
        setLoading(true)
        btnSource.text = "加载配置…"
        ApiConfig.load(url,
            okCb = {
                runOnUiThread {
                    Thread {
                        val pick = pickDefaultAny()
                        runOnUiThread {
                            setLoading(false)
                            if (pick == null) {
                                emptyView.text = "所有源都不可用，点「配置」换接口"
                                emptyView.visibility = View.VISIBLE
                                return@runOnUiThread
                            }
                            State.homeSource = pick
                            State.configLoaded = true
                            loadAll()
                        }
                    }.start()
                }
            },
            errCb = { msg ->
                runOnUiThread {
                    setLoading(false)
                    btnSource.text = "TVBox"
                    emptyView.text = msg + "\n点右上角「配置」更换接口"
                    emptyView.visibility = View.VISIBLE
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            })
    }

    /** 先探测 CMS 直连源，都不通就依次试 Jar 爬虫源；全失败则落到第一个源（手动切换兜底） */
    private fun pickDefaultAny(): SourceBean? {
        CmsApi.pickDefaultSource()?.let { return it }
        for (b in ApiConfig.sourceBeanList) {
            if (!b.isSpider) continue
            try {
                if (SourceApi.getClasses(b).isNotEmpty()) return b
            } catch (_: Exception) {}
        }
        return ApiConfig.sourceBeanList.firstOrNull()
    }

    private fun switchSource(bean: SourceBean) {
        State.homeSource = bean
        CmsApi.clearCaches()
        loadAll()
    }

    private fun loadAll() {
        val bean = State.homeSource ?: return
        btnSource.text = bean.name
        emptyView.visibility = View.GONE
        setLoading(true)
        Thread {
            val cats = try { SourceApi.getClasses(bean) } catch (_: Exception) { emptyList() }
            runOnUiThread {
                setLoading(false)
                categories = listOf("<首页>" to "") + cats
                renderChips()
                currentClassId = null
                adapter.replaceAll(emptyList())
                loadVods(null, 1)
            }
        }.start()
    }

    private fun renderChips() {
        val row = findViewById<LinearLayout>(R.id.chipsRow)
        row.removeAllViews()
        categories.forEach { (cid, name) ->
            val tv = TextView(this)
            tv.text = name
            tv.setPadding(24, 10, 24, 10)
            tv.setTextColor(if (cid == (currentClassId ?: "")) resources.getColor(R.color.accent, theme)
                else resources.getColor(R.color.textPrimary, theme))
            tv.textSize = 14f
            tv.setOnClickListener {
                currentClassId = cid.ifEmpty { null }
                renderChips()
                adapter.replaceAll(emptyList())
                loadVods(currentClassId, 1)
            }
            row.addView(tv)
        }
    }

    private fun loadVods(classId: String?, pg: Int, append: Boolean = false) {
        val bean = State.homeSource ?: return
        loading = true
        if (!append) setLoading(true)
        Thread {
            val (vods, realPage, pc) = if (classId == null) {
                // 首页：Spider 走 homeContent，CMS 走推荐位
                try {
                    val (h, pc1) = SourceApi.getHomeVods(bean)
                    Triple(h, 1, pc1)
                } catch (_: Exception) {
                    Triple(emptyList(), 1, 1)
                }
            } else try {
                SourceApi.getVods(bean, classId, pg)
            } catch (e: Exception) {
                Triple(emptyList(), pg, 1)
            }
            runOnUiThread {
                loading = false
                setLoading(false)
                page = realPage
                pageCount = pc
                if (append) adapter.append(vods) else adapter.replaceAll(vods)
                if (adapter.itemCount == 0) {
                    emptyView.text = "暂无数据"
                    emptyView.visibility = View.VISIBLE
                } else {
                    emptyView.visibility = View.GONE
                }
            }
        }.start()
    }

    private fun setLoading(b: Boolean) {
        progress.visibility = if (b) View.VISIBLE else View.GONE
    }

    inner class VodAdapter(val onClick: (Vod) -> Unit) :
        RecyclerView.Adapter<VodAdapter.Holder>() {
        private val items = ArrayList<Vod>()

        fun replaceAll(list: List<Vod>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
        fun append(list: List<Vod>) { val s = items.size; items.addAll(list); notifyItemRangeInserted(s, list.size) }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
            Holder(layoutInflater.inflate(R.layout.item_vod, parent, false))
        override fun onBindViewHolder(h: Holder, pos: Int) {
            val v = items[pos]
            h.name.text = v.name
            h.remark.text = v.remark
            PicLoader.load(h.img, v.pic)
            h.itemView.setOnClickListener { onClick(v) }
        }
        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val img: android.widget.ImageView = v.findViewById(R.id.imgPoster)
            val name: TextView = v.findViewById(R.id.txtName)
            val remark: TextView = v.findViewById(R.id.txtRemark)
        }
    }

    @Suppress("unused")
    private fun debugEditText(): EditText? = null
}
