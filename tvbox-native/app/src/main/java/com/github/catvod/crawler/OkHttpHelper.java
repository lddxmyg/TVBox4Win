package com.github.catvod.crawler;

import com.github.tvbox.osc.util.OkGoHelper;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** OkGo 替身：仅提供爬虫 jar 下载用的同步 GET */
final class OkHttpHelper {

    static Response download(String url) throws Exception {
        OkHttpClient client = OkGoHelper.getDefaultClient().newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        Request req = new Request.Builder().url(url)
                .header("User-Agent", "okhttp/3.15").build();
        return client.newCall(req).execute();
    }
}
