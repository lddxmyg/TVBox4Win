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
import org.lddxmyg.tvbox.core.Prefs
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

    /**
     * 选默认源。
     * 111 个源全是 Jar 源时串行探测会卡几分钟，这里：
     * ① 上次成功用过的源优先（命中就秒开）；② 否则并发探测前几个（优先带首页推荐位的）；
     * ③ 总时间盒 25 秒；④ 全失败落到第一个源（用户可手动切换，不会白屏）。
     */
    private fun pickDefaultAny(): SourceBean? {
        CmsApi.pickDefaultSource()?.let { return it }

        val lastKey = Prefs.getString("last_good_source", "")
        if (lastKey.isNotEmpty()) {
            ApiConfig.get(lastKey)?.let { b ->
                if (runCatching { SourceApi.getClasses(b).isNotEmpty() }.getOrDefault(false)) return b
            }
        }

        val all = ApiConfig.sourceBeanList
        if (all.isEmpty()) return null
        val ranked = all.filter { it.isSpider && it.indexs == 1 } + all.filter { !(it.isSpider && it.indexs == 1) }
        val probe = ranked.take(6)

        val picked = java.util.concurrent.atomic.AtomicReference<SourceBean?>(null)
        val done = java.util.concurrent.CountDownLatch(probe.size)
        // 守护线程池：超时后不中断（否则会把正在下载的 jar 打断）
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(probe.size, 4)) { r ->
            Thread(r, "pick-source").apply { isDaemon = true }
        }
        for (b in probe) {
            pool.execute {
                try {
                    if (picked.get() == null && SourceApi.getClasses(b).isNotEmpty()) {
                        picked.compareAndSet(null, b)
                    }
                } catch (_: Throwable) {
                } finally {
                    done.countDown()
                }
            }
        }
        try { done.await(25, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Throwable) {}
        pool.shutdown()
        return picked.get() ?: probe.firstOrNull() ?: all.firstOrNull()
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
            if (cats.isNotEmpty()) Prefs.putString("last_good_source", bean.key)
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
                    emptyView.text = "「${bean.name}」暂无内容\n点左上角源名可切换其他源（共 ${ApiConfig.sourceBeanList.size} 个）"
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
