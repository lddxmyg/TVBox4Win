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
        ApiConfig.currentUrl = Prefs.getString("home_api", ApiConfig.DEFAULT_API)
        QuickJSLoader.init()
    }
}
