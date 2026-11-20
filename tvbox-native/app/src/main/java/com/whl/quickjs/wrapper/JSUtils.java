package com.whl.quickjs.wrapper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JS/Java 值转换工具（jun 版 wrapper 扩展，语义复刻）。
 */
public class JSUtils<T> {

    /** JSObject → JSONObject（递归，通过 stringify 保真） */
    public static JSONObject toJsonObject(Object obj) {
        if (obj instanceof JSONObject) return (JSONObject) obj;
        if (obj instanceof JSObject) {
            try {
                String json = ((JSObject) obj).stringify();
                if (json != null && !json.isEmpty()) return new JSONObject(json);
            } catch (Throwable ignored) {
            }
            return new JSONObject();
        }
        if (obj instanceof Map) {
            try {
                return new JSONObject((Map) obj);
            } catch (Throwable ignored) {
            }
        }
        if (obj instanceof String) {
            try {
                String s = ((String) obj).trim();
                if (s.startsWith("{")) return new JSONObject(s);
            } catch (Throwable ignored) {
            }
        }
        return new JSONObject();
    }

    /** JSArray → JSONArray */
    public static JSONArray toJsonArray(JSArray array) {
        if (array == null) return new JSONArray();
        try {
            String json = array.stringify();
            if (json != null && !json.isEmpty()) return new JSONArray(json);
        } catch (Throwable ignored) {
        }
        return new JSONArray();
    }

    /** List → JSArray（Connect 里多值 header 转换用） */
    public <T> JSArray toArray(QuickJSContext ctx, List<T> list) {
        JSArray out = ctx.createNewJSArray();
        if (list == null) return out;
        for (int i = 0; i < list.size(); i++) {
            T v = list.get(i);
            if (v instanceof Integer) out.set((Integer) v, i);
            else if (v instanceof Long) out.set(((Long) v).intValue(), i);
            else if (v instanceof Boolean) out.set((Boolean) v, i);
            else if (v instanceof Double) out.set((Double) v, i);
            else out.set(v == null ? "" : String.valueOf(v), i);
        }
        return out;
    }

    /** Java 对象（Map / JSONObject / JSON 字符串）→ JSObject（爬虫 extend/params 转换用） */
    public JSObject toObj(QuickJSContext ctx, Object src) {
        JSObject out = ctx.createNewJSObject();
        if (src == null) return out;
        try {
            if (src instanceof JSObject) return (JSObject) src;
            if (src instanceof Map) {
                for (Object o : ((Map<?, ?>) src).keySet()) {
                    String k = String.valueOf(o);
                    Object v = ((Map<?, ?>) src).get(o);
                    putAny(ctx, out, k, v);
                }
                return out;
            }
            if (src instanceof JSONObject) {
                JSONObject jo = (JSONObject) src;
                java.util.Iterator<String> keys = jo.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    putAny(ctx, out, k, jo.opt(k));
                }
                return out;
            }
            if (src instanceof String) {
                String s = ((String) src).trim();
                if (s.startsWith("{")) return toObj(ctx, new JSONObject(s));
                out.setProperty("value", s);
                return out;
            }
            out.setProperty("value", String.valueOf(src));
        } catch (Throwable ignored) {
        }
        return out;
    }

    private void putAny(QuickJSContext ctx, JSObject target, String key, Object v) {
        try {
            if (v == null) target.setProperty(key, "");
            else if (v instanceof Integer) target.setProperty(key, (Integer) v);
            else if (v instanceof Long) target.setProperty(key, (Long) v);
            else if (v instanceof Boolean) target.setProperty(key, (Boolean) v);
            else if (v instanceof Double) target.setProperty(key, (Double) v);
            else if (v instanceof JSONObject) target.setProperty(key, toObj(ctx, v));
            else if (v instanceof Map) target.setProperty(key, toObj(ctx, v));
            else if (v instanceof JSObject) target.setProperty(key, (JSObject) v);
            else target.setProperty(key, String.valueOf(v));
        } catch (Throwable ignored) {
        }
    }

    /** Map → JSObject */
    public static JSObject toJsObject(QuickJSContext ctx, Map<String, Object> map) {
        JSObject out = ctx.createNewJSObject();
        if (map == null) return out;
        Set<String> keys = map.keySet();
        for (String k : keys) {
            Object v = map.get(k);
            if (v instanceof Integer) out.setProperty(k, (Integer) v);
            else if (v instanceof Long) out.setProperty(k, (Long) v);
            else if (v instanceof Boolean) out.setProperty(k, (Boolean) v);
            else if (v instanceof Double) out.setProperty(k, (Double) v);
            else if (v instanceof JSObject) out.setProperty(k, (JSObject) v);
            else out.setProperty(k, v == null ? "" : String.valueOf(v));
        }
        return out;
    }

    /** HashMap 形式的 toJsonObject 兼容（部分爬虫直接传 HashMap） */
    public static JSONObject toJsonObjectFromMap(HashMap<String, Object> map) {
        JSONObject out = new JSONObject();
        if (map == null) return out;
        for (String k : map.keySet()) {
            Object v = map.get(k);
            try {
                if (v instanceof Integer) out.put(k, (int) v);
                else if (v instanceof Boolean) out.put(k, (boolean) v);
                else if (v instanceof Double) out.put(k, (double) v);
                else if (v instanceof Long) out.put(k, (long) v);
                else if (v instanceof JSObject) out.put(k, toJsonObject(v));
                else if (v instanceof JSArray) out.put(k, toJsonArray((JSArray) v));
                else out.put(k, v == null ? "" : String.valueOf(v));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }
}
