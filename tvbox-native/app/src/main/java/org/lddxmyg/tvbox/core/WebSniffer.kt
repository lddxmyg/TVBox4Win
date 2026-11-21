package org.lddxmyg.tvbox.core

import android.annotation.SuppressLint
import android.app.Activity
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * WebView 深度嗅探（复刻原版 TVBox SniffActivity 的核心能力）：
 * 很多播放页的媒体地址由 JS 运行时注入/跳转，静态 HTML 正则抓不到，
 * 必须 WebView 加载页面、拦截真实媒体请求。
 * 命中 .m3u8/.mp4/... 或原版嗅探正则即回调；超时回调 null。
 * 回调固定在主线程。
 */
object WebSniffer {

    private const val TAG = "WebSniffer"

    @SuppressLint("SetJavaScriptEnabled")
    fun sniff(activity: Activity, pageUrl: String, timeoutMs: Long = 15_000, cb: (String?) -> Unit) {
        activity.runOnUiThread {
            var done = false
            lateinit var webView: WebView

            fun finish(url: String?) {
                if (done) return
                done = true
                try { webView.stopLoading(); webView.destroy() } catch (_: Throwable) {}
                activity.runOnUiThread { cb(url) }
            }

            try {
                webView = WebView(activity)
            } catch (t: Throwable) {
                // 设备没有 WebView 实现（部分精简模拟器）：立即失败，不空等超时
                android.util.Log.i(TAG, "webview unavailable: ${t.message}")
                cb(null)
                return@runOnUiThread
            }
            try {
                webView.layoutParams = android.view.ViewGroup.LayoutParams(1, 1)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.blockNetworkImage = true
                webView.settings.mediaPlaybackRequiresUserGesture = false
                webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
                webView.settings.userAgentString = "Mozilla/5.0 (Linux; Android 9) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.210 Mobile Safari/537.36"

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView, request: WebResourceRequest
                    ): WebResourceResponse? {
                        val url = request.url.toString()
                        if (CmsApi.isVideoFormat(url)) {
                            android.util.Log.i(TAG, "sniffed url=$url")
                            finish(url)
                        }
                        return null
                    }

                    // location 跳转等主框架导航也要查（有些页面直接 302/跳转到 m3u8）
                    override fun shouldOverrideUrlLoading(
                        view: WebView, request: WebResourceRequest
                    ): Boolean {
                        val url = request.url.toString()
                        if (CmsApi.isVideoFormat(url)) {
                            android.util.Log.i(TAG, "sniffed(redirect) url=$url")
                            finish(url)
                            return true
                        }
                        return false
                    }
                }
                webView.loadUrl(pageUrl)
            } catch (t: Throwable) {
                android.util.Log.i(TAG, "webview load failed: ${t.message}")
                finish(null)
                return@runOnUiThread
            }

            android.os.Handler(activity.mainLooper).postDelayed({ finish(null) }, timeoutMs)
        }
    }
}
