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
            startActivity(Intent(this, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
            finish()
        }
        root.addView(ok, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val tv = TextView(this)
        tv.text = "说明：本原生版仅支持 CMS 直连源（type 0/1/2），\nJar/JS 爬虫源暂不支持。"
        tv.setTextColor(resources.getColor(android.R.color.darker_gray, theme))
        tv.textSize = 12f
        tv.setPadding(0, 40, 0, 0)
        tv.gravity = Gravity.START
        root.addView(tv)

        setContentView(root)
    }
}
