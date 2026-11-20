package com.orhanobut.hawk;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 桩类：原版 Hawk 持久化的最小复刻。
 * JS 爬虫的 local 存储与 jsRuntime 字节码缓存走内存 Map；
 * 字符串值同步落盘到 WorkBuddy Prefs，进程重启不丢。
 */
public class Hawk {

    private static final ConcurrentHashMap<String, Object> map = new ConcurrentHashMap<>();

    private static void mirror(String key, Object value) {
        try {
            org.lddxmyg.tvbox.core.Prefs.INSTANCE.putString("hawk_js_" + key,
                    value == null ? "" : String.valueOf(value));
        } catch (Throwable ignored) {
        }
    }

    public static <T> T put(String key, T value) {
        if (value == null) return delete(key);
        map.put(key, value);
        if (value instanceof String || value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            mirror(key, value);
        }
        return value;
    }

    public static <T> T get(String key) {
        return (T) map.get(key);
    }

    public static <T> T get(String key, T defaultValue) {
        Object v = map.get(key);
        if (v == null && defaultValue != null) {
            try {
                String s = org.lddxmyg.tvbox.core.Prefs.INSTANCE.getString("hawk_js_" + key, "");
                if (!s.isEmpty()) {
                    if (defaultValue instanceof Boolean) v = Boolean.parseBoolean(s);
                    else if (defaultValue instanceof Integer) v = Integer.parseInt(s);
                    else if (defaultValue instanceof Long) v = Long.parseLong(s);
                    else v = s;
                }
            } catch (Throwable ignored) {
            }
        }
        return v == null ? defaultValue : (T) v;
    }

    public static <T> T delete(String key) {
        map.remove(key);
        try {
            org.lddxmyg.tvbox.core.Prefs.INSTANCE.putString("hawk_js_" + key, "");
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static boolean contains(String key) {
        return map.containsKey(key);
    }

    public static int count() {
        return map.size();
    }
}
