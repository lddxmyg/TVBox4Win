package com.whl.quickjs.wrapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注 JSAPI 类中用于注入 QuickJSContext 的方法（jun 版 wrapper 扩展，语义复刻）。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ContextSetter {
}
