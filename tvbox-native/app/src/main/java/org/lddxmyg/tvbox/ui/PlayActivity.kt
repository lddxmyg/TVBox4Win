package org.lddxmyg.tvbox.ui

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.lddxmyg.tvbox.R
import org.lddxmyg.tvbox.core.ApiConfig
import org.lddxmyg.tvbox.core.PlayResult
import org.lddxmyg.tvbox.core.SourceApi
import org.lddxmyg.tvbox.core.State

class PlayActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val frame = FrameLayout(this)
        playerView = PlayerView(this)
        playerView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        frame.addView(playerView)
        statusView = TextView(this)
        statusView.text = "解析播放地址…"
        statusView.setTextColor(resources.getColor(R.color.textSecondary, theme))
        statusView.textSize = 16f
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT)
        lp.gravity = android.view.Gravity.CENTER
        statusView.layoutParams = lp
        frame.addView(statusView)
        setContentView(frame)
        title = intent.getStringExtra("ep_name") ?: "播放"

        val epUrl = intent.getStringExtra("ep_url") ?: ""
        val lineFlag = intent.getStringExtra("line_flag") ?: ""
        // msearch 聚合详情时，播放要走用户选中的源（source_key），而非首页源
        val bean = intent.getStringExtra("source_key")?.takeIf { it.isNotEmpty() }
            ?.let { ApiConfig.get(it) } ?: State.homeSource

        Thread {
            val result: PlayResult = try {
                if (bean != null) SourceApi.getPlayUrl(bean, lineFlag, epUrl)
                else PlayResult(epUrl, null)
            } catch (e: Exception) {
                PlayResult(epUrl, null)
            }
            runOnUiThread {
                if (result.url.isBlank()) {
                    statusView.text = "播放地址为空"
                    return@runOnUiThread
                }
                startPlay(result)
            }
        }.start()
    }

    private fun startPlay(result: PlayResult) {
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
        val item = MediaItem.Builder()
            .setUri(result.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(intent.getStringExtra("ep_name") ?: "").build())
            .build()
        p.setMediaItem(item)
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                statusView.text = "播放失败：${error.errorCodeName}\n源地址可能不支持或已失效"
                statusView.visibility = View.VISIBLE
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                statusView.visibility = if (isPlaying) View.GONE else statusView.visibility
            }
        })
        p.prepare()
        p.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
