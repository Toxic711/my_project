package com.zhouzihang.exam.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * RBAC 角色注解：标注在 Controller 类或方法上。
 *
 * <p>面试讲解点：本项目没有引入 spring-security（离线仓库无对应 starter），
 * 用“拦截器 + 注解”实现等价的 RBAC。优点是依赖少、链路短、好调试；
 * 代价是缺少 Spring Security 的过滤器链、CSRF、方法级 SpEL 等能力，
 * 生产化需要补齐（见 DESIGN.md 已知限制）。</p>
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {
    /** 允许访问的角色，例如 {"TEACHER","ADMIN"}。 */
    String[] value();
}
