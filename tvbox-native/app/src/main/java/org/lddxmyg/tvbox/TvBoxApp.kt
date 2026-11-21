package org.lddxmyg.tvbox

import com.github.tvbox.osc.base.App
import com.whl.quickjs.android.QuickJSLoader
import org.lddxmyg.tvbox.core.ApiConfig
import org.lddxmyg.tvbox.core.Prefs
import org.lddxmyg.tvbox.core.PrefsRunDir

class TvBoxApp : App() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        PrefsRunDir.init(this)
        // 换默认配置源时，老用户 Prefs 里存的是旧地址/测试地址，会让新默认源不生效
        val stored = Prefs.getString("home_api", "")
        if (ApiConfig.isLegacyApi(stored)) {
            Prefs.putString("home_api", ApiConfig.DEFAULT_API)
        }
        ApiConfig.currentUrl = Prefs.getString("home_api", ApiConfig.DEFAULT_API)
        QuickJSLoader.init()
    }
}
