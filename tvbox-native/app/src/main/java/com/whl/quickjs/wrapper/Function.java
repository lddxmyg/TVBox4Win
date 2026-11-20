package com.whl.quickjs.wrapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注要暴露给 JS 的 Java 方法，可自定义导出名（jun 版 wrapper 扩展，语义复刻）。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Function {
    String name() default "";
}
