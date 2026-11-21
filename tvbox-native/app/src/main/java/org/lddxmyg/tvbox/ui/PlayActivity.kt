package org.lddxmyg.tvbox.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.lddxmyg.tvbox.core.ApiConfig
import org.lddxmyg.tvbox.core.CmsApi
import org.lddxmyg.tvbox.core.DetailInfo
import org.lddxmyg.tvbox.core.PlayResult
import org.lddxmyg.tvbox.core.Prefs
import org.lddxmyg.tvbox.core.SourceApi
import org.lddxmyg.tvbox.core.SourceBean
import org.lddxmyg.tvbox.core.State
import org.lddxmyg.tvbox.core.WebSniffer

/**
 * 播放页：
 * - 选集面板（线路切换 + 集数横滑，当前集高亮）
 * - 播完自动下一集
 * - 播放失败：重试 / 换线路 / 换源（全源聚合搜索该片名）
 * - 播放进度记忆（按 源+片id+线路+集 记录）
 * - 非直链地址走 WebView 深度嗅探（复刻原版 SniffActivity）
 */
class PlayActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var statusView: TextView
    private lateinit var titleView: TextView
    private lateinit var lineRow: LinearLayout
    private lateinit var epRow: LinearLayout
    private lateinit var actionRow: LinearLayout

    private var currentUrl = ""
    private var lineIdx = 0
    private var epIdx = 0
    private var detail: DetailInfo? = null
    private var bean: SourceBean? = null
    private var vodId = ""
    private var vodName = ""
    private var savedPos = 0L
    // 播放令牌：切集/换线路后，旧集迟到的嗅探回调/解析结果不得污染当前播放
    private val playToken = java.util.concurrent.atomic.AtomicInteger(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 单集模式（Intent 传参）
        val intentEpUrl = intent.getStringExtra("ep_url") ?: ""
        val intentFlag = intent.getStringExtra("line_flag") ?: ""
        val intentEpName = intent.getStringExtra("ep_name") ?: "播放"
        // 详情上下文（有则可选集/自动下一集/换源）
        val useDetail = intent.getBooleanExtra("use_detail", false)
        val d = if (useDetail) State.playDetail else null
        if (d != null && State.playVodId.isNotEmpty()) {
            detail = d
            bean = State.playSource ?: State.homeSource
            vodId = State.playVodId
            vodName = State.playVodName
            lineIdx = State.playLineIndex.coerceAtLeast(0)
            epIdx = 0
        } else {
            bean = intent.getStringExtra("source_key")?.takeIf { it.isNotEmpty() }
                ?.let { ApiConfig.get(it) } ?: State.homeSource
            vodName = intent.getStringExtra("vod_name").orEmpty()
        }

        val frame = FrameLayout(this)
        playerView = PlayerView(this)
        playerView.setBackgroundColor(0xFF000000.toInt())
        playerView.useController = true
        frame.addView(playerView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        statusView = TextView(this)
        statusView.text = "解析播放地址…"
        statusView.setTextColor(0xFFE2E8F0.toInt())
        statusView.textSize = 15f
        statusView.setPadding(dp(24), 0, dp(24), 0)
        statusView.gravity = Gravity.CENTER
        val slp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.gravity = Gravity.CENTER
        frame.addView(statusView, slp)

        // 底部控制面板
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(0xCC000000.toInt())
        panel.setPadding(dp(12), dp(6), dp(12), dp(8))

        titleView = TextView(this)
        titleView.setTextColor(0xFF93C5FD.toInt())
        titleView.textSize = 13f
        titleView.text = intentEpName
        panel.addView(titleView)

        val lineScroll = HorizontalScrollView(this)
        lineRow = LinearLayout(this)
        lineRow.orientation = LinearLayout.HORIZONTAL
        lineScroll.addView(lineRow)
        panel.addView(lineScroll)

        val epScroll = HorizontalScrollView(this)
        epRow = LinearLayout(this)
        epRow.orientation = LinearLayout.HORIZONTAL
        epScroll.addView(epRow)
        panel.addView(epScroll)

        actionRow = LinearLayout(this)
        actionRow.orientation = LinearLayout.HORIZONTAL
        actionRow.visibility = View.GONE
        panel.addView(actionRow)

        val plp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        plp.gravity = Gravity.BOTTOM
        frame.addView(panel, plp)

        setContentView(frame)

        if (detail != null) {
            setTitle(vodName)
            renderPanel()
            playEpisode(lineIdx, 0)
        } else {
            setTitle(intentEpName)
            resolveAndPlay(intentEpUrl, intentFlag, intentEpName)
        }
    }

    // ---------- 选集面板 ----------

    private fun renderPanel() {
        val d = detail ?: return
        titleView.text = "$vodName  ·  线路 ${lineIdx + 1}/${d.seriesFlags.size}"
        lineRow.removeAllViews()
        d.seriesFlags.forEachIndexed { i, (flag, _) ->
            val t = chip(flag.ifBlank { "线路${i + 1}" }, i == lineIdx)
            t.setOnClickListener { playEpisode(i, 0) }
            lineRow.addView(t)
        }
        epRow.removeAllViews()
        val names = d.seriesFlags.getOrNull(lineIdx)?.second ?: emptyList()
        names.forEachIndexed { i, nm ->
            val t = chip(nm.ifBlank { "第${i + 1}集" }, i == epIdx)
            t.setOnClickListener { playEpisode(lineIdx, i) }
            epRow.addView(t)
        }
    }

    private fun chip(text: String, active: Boolean): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 13f
        t.setPadding(dp(12), dp(6), dp(12), dp(6))
        t.setTextColor(if (active) 0xFF0F172A.toInt() else 0xFFE2E8F0.toInt())
        t.setBackgroundColor(if (active) 0xFF60A5FA.toInt() else 0x33FFFFFF)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.rightMargin = dp(6)
        t.layoutParams = lp
        return t
    }

    private fun playEpisode(line: Int, ep: Int) {
        val d = detail
        if (d == null) return
        val urls = d.seriesUrls.getOrNull(line)
        if (urls == null || ep !in urls.indices) {
            statusView.text = "该线路没有可播放的选集"
            statusView.visibility = View.VISIBLE
            return
        }
        lineIdx = line
        epIdx = ep
        State.playLineIndex = line
        renderPanel()
        val flag = d.seriesFlags.getOrNull(line)?.first ?: ""
        val nm = d.seriesFlags.getOrNull(line)?.second?.getOrNull(ep).orEmpty().ifBlank { "第${ep + 1}集" }
        titleView.text = "$vodName  ·  $nm"
        setTitle("$vodName $nm")
        resolveAndPlay(urls[ep], flag, nm)
    }

    // ---------- 地址解析 + 播放 ----------

    private fun resolveAndPlay(epUrl: String, lineFlag: String, epName: String) {
        val my = playToken.incrementAndGet()
        releasePlayer()
        actionRow.visibility = View.GONE
        statusView.text = "解析播放地址…"
        statusView.visibility = View.VISIBLE
        savedPos = Prefs.getString(posKey(), "0").toLongOrNull() ?: 0L
        val src = bean
        Thread {
            val result: PlayResult = try {
                if (src != null) SourceApi.getPlayUrl(src, lineFlag, epUrl)
                else PlayResult(epUrl, null)
            } catch (e: Exception) {
                PlayResult(epUrl, null)
            }
            if (my != playToken.get()) return@Thread
            // OkHttp 快路径没拿到媒体直链 → WebView 深度嗅探（JS 注入/跳转页）
            if (result.url.startsWith("http") && !CmsApi.isVideoFormat(result.url)) {
                runOnUiThread {
                    if (my != playToken.get() || isFinishing || isDestroyed) return@runOnUiThread
                    statusView.text = "页面为动态播放页，正在深度嗅探…"
                    WebSniffer.sniff(this, result.url) { found ->
                        if (my != playToken.get()) return@sniff
                        if (found != null) startPlay(PlayResult(found, result.headers), epName)
                        else showFail("播放地址解析失败", result.url)
                    }
                }
                return@Thread
            }
            runOnUiThread {
                if (my != playToken.get()) return@runOnUiThread
                startPlay(result, epName)
            }
        }.start()
    }

    private fun startPlay(result: PlayResult, epName: String) {
        if (result.url.isBlank()) {
            showFail("播放地址为空", "")
            return
        }
        val dsFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("okhttp/4.9.3")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
        val extraHeaders = result.headers?.filterKeys { !it.equals("User-Agent", true) }
        if (!extraHeaders.isNullOrEmpty()) dsFactory.setDefaultRequestProperties(extraHeaders)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dsFactory))
            .build()
        player = p
        playerView.player = p
        currentUrl = result.url
        val item = MediaItem.Builder()
            .setUri(result.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(epName).build())
            .build()
        p.setMediaItem(item)
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause?.message?.take(160) ?: ""
                showFail(error.errorCodeName, currentUrl, cause)
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        statusView.visibility = View.GONE
                        actionRow.visibility = View.GONE
                        if (savedPos in 3_000 until p.duration.coerceAtLeast(1)) {
                            try { p.seekTo(savedPos) } catch (_: Throwable) {}
                        }
                        savedPos = 0L
                    }
                    Player.STATE_ENDED -> autoNext()
                }
            }
        })
        p.prepare()
        p.play()
    }

    private fun showFail(code: String, url: String, cause: String = "") {
        statusView.visibility = View.VISIBLE
        statusView.text = "播放失败：$code\n" +
            (if (cause.isNotEmpty()) "$cause\n" else "") +
            (if (url.isNotEmpty()) "地址：${url.take(110)}" else "")
        // 失败自愈入口：重试 / 换线路 / 换源
        actionRow.removeAllViews()
        actionRow.addView(actionButton("重试") { playEpisode(lineIdx, epIdx) })
        val d = detail
        if (d != null && d.seriesFlags.size > 1) {
            actionRow.addView(actionButton("换线路") { playEpisode((lineIdx + 1) % d.seriesFlags.size, 0) })
        }
        if (vodName.isNotBlank()) {
            actionRow.addView(actionButton("换源播放") { switchSourceDialog() })
        }
        actionRow.visibility = View.VISIBLE
    }

    private fun actionButton(text: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 14f
        t.setTextColor(0xFF60A5FA.toInt())
        t.setPadding(dp(4), dp(8), dp(20), dp(4))
        t.setOnClickListener { onClick() }
        return t
    }

    /** 播完自动下一集（同线路） */
    private fun autoNext() {
        val d = detail ?: return
        val urls = d.seriesUrls.getOrNull(lineIdx) ?: return
        if (epIdx + 1 < urls.size) {
            playEpisode(lineIdx, epIdx + 1)
        } else {
            statusView.visibility = View.VISIBLE
            statusView.text = "已是最后一集"
        }
    }

    // ---------- 换源 ----------

    private fun switchSourceDialog() {
        val name = vodName.ifBlank { title?.toString().orEmpty() }
        if (name.isBlank()) return
        statusView.visibility = View.VISIBLE
        statusView.text = "正在搜索「$name」的其他播放源…"
        actionRow.visibility = View.GONE
        Thread {
            val cands = try { SourceApi.searchAllSources(name, bean?.key ?: "") }
                        catch (_: Throwable) { emptyList() }
            runOnUiThread {
                if (cands.isEmpty()) {
                    statusView.text = "没有找到其他可播放源"
                    actionRow.visibility = View.VISIBLE
                    return@runOnUiThread
                }
                val labels = cands.take(20).map { (b, v) ->
                    "${b.name} · ${v.name}" + (if (v.remark.isNotBlank()) " · ${v.remark}" else "")
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("换源播放（${cands.size} 个候选）")
                    .setItems(labels) { _, i ->
                        val (nb, nv) = cands[i]
                        loadDetailThenPlay(nb, nv.id, nv.name)
                    }
                    .setNegativeButton("取消") { _, _ -> }
                    .show()
            }
        }.start()
    }

    private fun loadDetailThenPlay(nb: SourceBean, id: String, name: String) {
        statusView.visibility = View.VISIBLE
        statusView.text = "加载「$name」…"
        Thread {
            val nd = try { SourceApi.getDetail(nb, id) } catch (_: Exception) { DetailInfo() }
            runOnUiThread {
                if (nd.seriesUrls.isEmpty()) {
                    statusView.text = "该源没有可播放地址，再换一个试试"
                    actionRow.visibility = View.VISIBLE
                    return@runOnUiThread
                }
                detail = nd
                bean = nb
                vodId = id
                vodName = nd.name.ifBlank { name }
                State.playDetail = nd
                State.playSource = nb
                State.playVodId = vodId
                State.playVodName = vodName
                State.playLineIndex = 0
                lineIdx = 0
                epIdx = 0
                renderPanel()
                playEpisode(0, 0)
            }
        }.start()
    }

    // ---------- 进度记忆 ----------

    private fun posKey(): String = "pos_${bean?.key ?: ""}_${vodId}_${lineIdx}_$epIdx"

    private fun savePosition() {
        val p = player ?: return
        val ms = try { p.currentPosition } catch (_: Throwable) { return }
        val dur = try { p.duration } catch (_: Throwable) { 0L }
        if (ms > 3_000 && dur > 0 && ms < dur) Prefs.putString(posKey(), ms.toString())
    }

    override fun onPause() {
        savePosition()
        super.onPause()
    }

    override fun onDestroy() {
        savePosition()
        releasePlayer()
        super.onDestroy()
    }

    private fun releasePlayer() {
        try { player?.release() } catch (_: Throwable) {}
        player = null
        playerView.player = null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
