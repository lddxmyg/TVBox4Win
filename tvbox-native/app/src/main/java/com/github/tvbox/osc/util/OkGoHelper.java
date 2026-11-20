package com.github.tvbox.osc.util;

import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.X509TrustManager;

import com.github.tvbox.osc.util.SSL.SSLSocketFactoryCompat;

import okhttp3.Authenticator;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;

/**
 * 桩类：原版 OkGoHelper 的最小复刻（去 okgo/cronet/picasso 依赖）。
 * 提供爬虫 JS 网络层用的宽松 TLS OkHttpClient。
 */
public class OkGoHelper {

    private static volatile OkHttpClient defaultClient;
    private static volatile OkHttpClient noRedirectClient;

    /** 原版经 cronet/DoH 构建；桩版恒为 null，OkDns 自动回退系统 DNS */
    public static okhttp3.Dns dnsOverHttps;

    public static OkHttpClient getDefaultClient() {
        if (defaultClient == null) {
            synchronized (OkGoHelper.class) {
                if (defaultClient == null) defaultClient = build(true);
            }
        }
        return defaultClient;
    }

    public static OkHttpClient getNoRedirectClient() {
        if (noRedirectClient == null) {
            synchronized (OkGoHelper.class) {
                if (noRedirectClient == null) {
                    noRedirectClient = build(true).newBuilder()
                            .followRedirects(false).followSslRedirects(false).build();
                }
            }
        }
        return noRedirectClient;
    }

    public static ProxySelector proxySelector() {
        return new ProxySelector() {
            @Override
            public List<Proxy> select(java.net.URI uri) {
                return Collections.singletonList(Proxy.NO_PROXY);
            }

            @Override
            public void connectFailed(java.net.URI uri, SocketAddress sa, java.io.IOException ioe) {
            }
        };
    }

    public static Authenticator proxyAuthenticator() {
        return new Authenticator() {
            @Override
            public Request authenticate(Route route, Response response) {
                return null;
            }
        };
    }

    private static OkHttpClient build(boolean trustAll) {
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .hostnameVerifier((hostname, session) -> true);
        try {
            X509TrustManager tm = new X509TrustManager() {
                @Override
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                }

                @Override
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[]{};
                }
            };
            b.sslSocketFactory(new SSLSocketFactoryCompat(tm), tm);
        } catch (Throwable ignored) {
        }
        return b.build();
    }
}
