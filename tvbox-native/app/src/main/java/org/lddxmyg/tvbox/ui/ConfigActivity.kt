package org.lddxmyg.tvbox.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.lddxmyg.tvbox.core.ApiConfig
import org.lddxmyg.tvbox.core.Prefs
import org.lddxmyg.tvbox.core.State

class ConfigActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "接口配置"

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(48, 48, 48, 48)

        val label = TextView(this)
        label.text = "TVBox 配置接口地址（URL 或 ;pk; 密钥）"
        label.setTextColor(resources.getColor(android.R.color.white, theme))
        root.addView(label)

        val input = EditText(this)
        input.setText(Prefs.getString("home_api", ApiConfig.DEFAULT_API))
        input.setTextColor(resources.getColor(android.R.color.white, theme))
        input.textSize = 13f
        root.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 预设源一键切换
        val presetBtn = Button(this)
        presetBtn.text = "选择预设接口（${ApiConfig.PRESET_APIS.size}）"
        presetBtn.setOnClickListener {
            val names = ApiConfig.PRESET_APIS.map { it.first }.toTypedArray()
            AlertDialog.Builder(this)
                .setItems(names) { _, which ->
                    input.setText(ApiConfig.PRESET_APIS[which].second)
                    Toast.makeText(this, "已选择：${ApiConfig.PRESET_APIS[which].first}",
                        Toast.LENGTH_SHORT).show()
                }.show()
        }
        root.addView(presetBtn)

        // 多仓线路切换（上次加载到的线路清单）
        val repoEntries = loadRepoList()
        if (repoEntries.isNotEmpty()) {
            val repoBtn = Button(this)
            val cur = if (ApiConfig.repoName.isNotEmpty()) "当前：${ApiConfig.repoName}"
                      else "当前未确定"
            repoBtn.text = "多仓线路（${repoEntries.size} 条，$cur）"
            repoBtn.setOnClickListener {
                val names = repoEntries.map { it.first }.toTypedArray()
                AlertDialog.Builder(this)
                    .setItems(names) { _, which ->
                        Prefs.putString("repo_pick_" +
                            Integer.toHexString(Prefs.getString("repo_list_url", "").hashCode()),
                            which.toString())
                        Prefs.putString("home_api",
                            Prefs.getString("repo_list_url", ApiConfig.DEFAULT_API))
                        State.configLoaded = false
                        Toast.makeText(this, "已切换线路：${repoEntries[which].first.substringBefore(" →")}",
                            Toast.LENGTH_SHORT).show()
                        reloadHome()
                        finish()
                    }.show()
            }
            root.addView(repoBtn)
        }

        val hist = Prefs.getList("api_history")
        if (hist.isNotEmpty()) {
            val histBtn = Button(this)
            histBtn.text = "历史接口（${hist.size}）"
            histBtn.setOnClickListener {
                AlertDialog.Builder(this)
                    .setItems(hist.toTypedArray()) { _, which ->
                        input.setText(hist[which])
                    }.show()
            }
            root.addView(histBtn)
        }

        val ok = Button(this)
        ok.text = "保存并重新加载"
        ok.setOnClickListener {
            val url = input.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "地址不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Prefs.putString("home_api", url)
            State.configLoaded = false
            Toast.makeText(this, "已保存，返回首页重新加载", Toast.LENGTH_SHORT).show()
            reloadHome()
            finish()
        }
        root.addView(ok, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val tv = TextView(this)
        tv.text = "说明：支持单仓与多仓接口；多仓会自动选可用线路，也可在上面手动切线路。\n" +
                "JS（drpy）与 Jar（csp_）爬虫源需在线加载，CMS 直连源（type 0/1/2）始终可用。"
        tv.setTextColor(resources.getColor(android.R.color.darker_gray, theme))
        tv.textSize = 12f
        tv.setPadding(0, 40, 0, 0)
        tv.gravity = Gravity.START
        root.addView(tv)

        setContentView(root)
    }

    private fun reloadHome() {
        startActivity(Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        })
    }

    /** 上次加载保存下来的多仓线路清单 */
    private fun loadRepoList(): List<Pair<String, String>> {
        val s = Prefs.getString("repo_list", "")
        if (s.isEmpty()) return emptyList()
        return try {
            val arr = org.json.JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val n = o.optString("name", "")
                val u = o.optString("url", "")
                if (u.isEmpty()) null else "$n → $u" to u
            }
        } catch (_: Exception) { emptyList() }
    }
}
