package org.lddxmyg.tvbox.core

/** 全局运行状态：当前源、当前分类等 */
object State {
    var homeSource: SourceBean? = null
    var configLoaded = false

    // ---------- 播放上下文（详情页点选集时写入，供播放页做选集面板/自动下一集/换源） ----------
    var playDetail: DetailInfo? = null
    var playSource: SourceBean? = null
    var playVodId: String = ""
    var playVodName: String = ""
    var playLineIndex: Int = 0
}
