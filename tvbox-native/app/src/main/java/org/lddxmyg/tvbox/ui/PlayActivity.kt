package org.lddxmyg.tvbox.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import org.lddxmyg.tvbox.core.History
import org.lddxmyg.tvbox.core.PlayResult
import org.lddxmyg.tvbox.core.Prefs
import org.lddxmyg.tvbox.core.SourceApi
import org.lddxmyg.tvbox.core.SourceBean
import org.lddxmyg.tvbox.core.State
import org.lddxmyg.tvbox.core.WebSniffer

/**
 * 播放页（沉浸式全屏）：
 * - 画面铺满、隐藏系统栏；选集/线路面板默认收起，右下角「选集」按钮唤出，选完自动收起
 * - 播完自动下一集；支持从历史/详情指定集数与线路续播
 * - 播放失败：重试 / 换线路 / 换源
 * - 播放进度记忆 + 写入观看历史
 * - 非直链地址走 WebView 深度嗅探（复刻原版 SniffActivity）
 */
class PlayActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var statusView: TextView
    private lateinit var panel: LinearLayout
    private lateinit var panelTitle: TextView
    private lateinit var lineRow: LinearLayout
    private lateinit var epRow: LinearLayout
    private lateinit var failBar: LinearLayout
    private lateinit var btnPanel: TextView

    private var currentUrl = ""
    private var lineIdx = 0
    private var epIdx = 0
    private var currentEpName = "播放"
    private var detail: DetailInfo? = null
    private var bean: SourceBean? = null
    private var vodId = ""
    private var vodName = ""
    private var savedPos = 0L
    private var panelOpen = false
    private val playToken = java.util.concurrent.atomic.AtomicInteger(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterFullscreen()

        val intentEpUrl = intent.getStringExtra("ep_url") ?: ""
        val intentFlag = intent.getStringExtra("line_flag") ?: ""
        val intentEpName = intent.getStringExtra("ep_name") ?: "播放"
        val useDetail = intent.getBooleanExtra("use_detail", false)
        // 历史续播：指定线路 + 集数（此前固定从第 0 集起，点第 5 集也会从第 1 集播）
        val resumeLine = intent.getIntExtra("resume_line", -1)
        val resumeEp = intent.getIntExtra("resume_ep", -1)
        val d = if (useDetail) State.playDetail else null
        if (d != null && State.playVodId.isNotEmpty()) {
            detail = d
            bean = State.playSource ?: State.homeSource
            vodId = State.playVodId
            vodName = State.playVodName
            lineIdx = (if (resumeLine >= 0) resumeLine else State.playLineIndex).coerceAtLeast(0)
            epIdx = resumeEp.coerceAtLeast(0)
            currentEpName = intentEpName
        } else {
            bean = intent.getStringExtra("source_key")?.takeIf { it.isNotEmpty() }
                ?.let { ApiConfig.get(it) } ?: State.homeSource
            vodName = intent.getStringExtra("vod_name").orEmpty()
            currentEpName = intentEpName
        }

        val frame = FrameLayout(this)
        frame.setBackgroundColor(0xFF000000.toInt())
        playerView = PlayerView(this)
        playerView.setBackgroundColor(0xFF000000.toInt())
        playerView.useController = true
        // 画面铺满整个窗口（全屏，不留状态栏/导航栏黑边）
        frame.addView(playerView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        statusView = TextView(this)
        statusView.text = "解析播放地址…"
        statusView.setTextColor(0xFFE2E8F0.toInt())
        statusView.textSize = 15f
        statusView.setPadding(dp(24), 0, dp(24), 0)
        statusView.gravity = Gravity.CENTER
        frame.addView(statusView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })

        // 失败/提示条（仅在出错时出现，不常驻遮挡画面）
        failBar = LinearLayout(this)
        failBar.orientation = LinearLayout.HORIZONTAL
        failBar.setBackgroundColor(0xE6000000.toInt())
        failBar.setPadding(dp(16), dp(8), dp(16), dp(8))
        failBar.visibility = View.GONE
        frame.addView(failBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        })

        // 底部抽屉面板：默认收起
        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(0xF2000000.toInt())
        panel.setPadding(dp(14), dp(8), dp(14), dp(12))
        panel.visibility = View.GONE

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        panelTitle = TextView(this)
        panelTitle.setTextColor(0xFF93C5FD.toInt())
        panelTitle.textSize = 13f
        head.addView(panelTitle, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val close = TextView(this)
        close.text = "收起 ✕"
        close.setTextColor(0xFFE2E8F0.toInt())
        close.textSize = 13f
        close.setPadding(dp(12), dp(6), dp(4), dp(6))
        close.setOnClickListener { hidePanel() }
        head.addView(close)
        panel.addView(head)

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

        frame.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        })

        // 右下角「选集」浮动按钮（不挡画面中间区域）
        btnPanel = TextView(this)
        btnPanel.text = "选集"
        btnPanel.setTextColor(0xFFE2E8F0.toInt())
        btnPanel.textSize = 12f
        btnPanel.setPadding(dp(12), dp(6), dp(12), dp(6))
        btnPanel.setBackgroundColor(0x66000000)
        btnPanel.setOnClickListener { if (panelOpen) hidePanel() else showPanel() }
        val blp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        blp.gravity = Gravity.END or Gravity.BOTTOM
        blp.setMargins(0, 0, dp(14), dp(12))
        frame.addView(btnPanel, blp)

        setContentView(frame)

        if (detail != null) {
            renderPanel()
            playEpisode(lineIdx, epIdx)
        } else {
            btnPanel.visibility = View.GONE
            resolveAndPlay(intentEpUrl, intentFlag, intentEpName)
        }
    }

    // ---------- 全屏 ----------

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun showPanel() {
        if (detail == null) return
        panelOpen = true
        panel.visibility = View.VISIBLE
        panel.translationY = dp(24).toFloat()
        panel.alpha = 0f
        panel.animate().translationY(0f).alpha(1f).setDuration(160).start()
        btnPanel.visibility = View.GONE
        panel.removeCallbacks(hideRunnable)
        panel.postDelayed(hideRunnable, 12_000)
    }

    private fun hidePanel() {
        if (!panelOpen) return
        panelOpen = false
        panel.animate().translationY(dp(24).toFloat()).alpha(0f).setDuration(140)
            .withEndAction { panel.visibility = View.GONE }.start()
        btnPanel.visibility = if (detail != null) View.VISIBLE else View.GONE
        panel.removeCallbacks(hideRunnable)
    }

    private val hideRunnable = Runnable { hidePanel() }

    // ---------- 选集面板 ----------

    private fun renderPanel() {
        val d = detail ?: return
        panelTitle.text = "$vodName  ·  线路 ${lineIdx + 1}/${d.seriesFlags.size}  ·  $currentEpName"
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
        val nm = d.seriesFlags.getOrNull(line)?.second?.getOrNull(ep).orEmpty()
            .ifBlank { "第${ep + 1}集" }
        currentEpName = nm
        renderPanel()
        hidePanel()
        val flag = d.seriesFlags.getOrNull(line)?.first ?: ""
        resolveAndPlay(urls[ep], flag, nm)
    }

    // ---------- 地址解析 + 播放 ----------

    private fun resolveAndPlay(epUrl: String, lineFlag: String, epName: String) {
        val my = playToken.incrementAndGet()
        releasePlayer()
        failBar.visibility = View.GONE
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
                        failBar.visibility = View.GONE
                        if (savedPos in 3_000 until p.duration.coerceAtLeast(1)) {
                            try { p.seekTo(savedPos) } catch (_: Throwable) {}
                        }
                        savedPos = 0L
                        recordHistory(0)
                    }
                    Player.STATE_ENDED -> {
                        recordHistory(0)
                        autoNext()
                    }
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
        failBar.removeAllViews()
        failBar.addView(actionButton("重试") { playEpisode(lineIdx, epIdx) })
        val d = detail
        if (d != null && d.seriesFlags.size > 1) {
            failBar.addView(actionButton("换线路") { playEpisode((lineIdx + 1) % d.seriesFlags.size, 0) })
        }
        if (d != null) failBar.addView(actionButton("选集") { showPanel() })
        if (vodName.isNotBlank()) {
            failBar.addView(actionButton("换源") { switchSourceDialog() })
        }
        failBar.visibility = View.VISIBLE
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

    private fun autoNext() {
        val d = detail ?: return
        val urls = d.seriesUrls.getOrNull(lineIdx) ?: return
        if (epIdx + 1 < urls.size) playEpisode(lineIdx, epIdx + 1)
        else {
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
        failBar.visibility = View.GONE
        Thread {
            val cands = try { SourceApi.searchAllSources(name, bean?.key ?: "") }
                        catch (_: Throwable) { emptyList() }
            runOnUiThread {
                if (cands.isEmpty()) {
                    statusView.text = "没有找到其他可播放源"
                    failBar.visibility = View.VISIBLE
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
                    failBar.visibility = View.VISIBLE
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
                currentEpName = "第1集"
                btnPanel.visibility = View.VISIBLE
                renderPanel()
                playEpisode(0, 0)
            }
        }.start()
    }

    // ---------- 进度记忆 / 历史 ----------

    private fun posKey(): String = "pos_${bean?.key ?: ""}_${vodId}_${lineIdx}_$epIdx"

    private fun savePosition() {
        val p = player ?: return
        val ms = try { p.currentPosition } catch (_: Throwable) { return }
        val dur = try { p.duration } catch (_: Throwable) { 0L }
        if (ms > 3_000 && dur > 0 && ms < dur) Prefs.putString(posKey(), ms.toString())
        if (dur > 0) recordHistory(ms)
    }

    /** 写入观看历史（首页「历史」据此续播） */
    private fun recordHistory(pos: Long) {
        val src = bean ?: return
        if (vodId.isEmpty() || vodName.isEmpty()) return
        val dur = try { player?.duration ?: 0L } catch (_: Throwable) { 0L }
        val p = if (pos > 0) pos else try { player?.currentPosition ?: 0L } catch (_: Throwable) { 0L }
        History.record(History.Item(
            src = src.key,
            id = vodId,
            name = vodName,
            pic = detail?.pic ?: "",
            remark = detail?.remark ?: "",
            line = lineIdx,
            ep = epIdx,
            epName = currentEpName,
            pos = p,
            dur = dur
        ))
    }

    override fun onPause() {
        savePosition()
        hidePanel()
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
