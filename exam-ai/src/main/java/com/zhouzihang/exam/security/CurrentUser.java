package com.zhouzihang.exam.security;

import com.zhouzihang.exam.common.ApiException;

/**
 * 当前请求的登录用户（ThreadLocal）。
 *
 * <p>面试讲解点：拦截器 preHandle 写入、afterCompletion 必须清理，
 * 否则线程池复用会把上一个请求的用户身份泄漏给下一个请求（越权事故）。
 * 这里用 try/finally 语义的 afterCompletion 兜底，并在 {@link #require()} 里
 * 对“未登录”显式抛 401，而不是返回 null 让调用方 NPE。</p>
 */
public final class CurrentUser {

    private static final ThreadLocal<AuthPrincipal> HOLDER = new ThreadLocal<>();

    private CurrentUser() {
    }

    public static void set(AuthPrincipal principal) {
        HOLDER.set(principal);
    }

    public static AuthPrincipal require() {
        AuthPrincipal principal = HOLDER.get();
        if (principal == null) {
            throw ApiException.unauthorized("未登录或登录已过期");
        }
        return principal;
    }

    public static AuthPrincipal getOrNull() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
