package com.whl.quickjs.wrapper;

import java.net.URI;
import java.net.URL;

/**
 * JS 模块名解析工具（jun 版 wrapper 扩展，语义复刻）。
 * 把相对模块名按 base 解析成绝对 URL，供 ModuleLoader 下载。
 */
public class UriUtil {

    public static String resolve(String base, String ref) {
        if (ref == null || ref.isEmpty()) return base;
        if (ref.contains("://")) return ref;
        if (ref.startsWith("/") && base != null && base.contains("://")) {
            try {
                URL u = new URL(base);
                return u.getProtocol() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + ref;
            } catch (Throwable ignored) {
            }
        }
        if (base != null && base.contains("://")) {
            try {
                return new URL(new URL(base), ref).toString();
            } catch (Throwable ignored) {
            }
        }
        if (base != null && !base.isEmpty()) {
            try {
                URI b = new URI(base);
                URI r = b.resolve(ref);
                if (r != null) return r.toString();
            } catch (Throwable ignored) {
            }
        }
        return ref;
    }
}
