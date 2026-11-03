package com.zhouzihang.exam.security;

/**
 * 认证后的当前用户快照（从 JWT 解出，不查库）。
 *
 * <p>面试讲解点：JWT 自包含，鉴权路径上无需查库，省一次 IO；
 * 代价是“改角色/禁用账号”不能即时生效，需要短 TTL + 刷新机制。
 * 本 demo 取 720 分钟，生产建议 access token 15~30 分钟 + refresh token。</p>
 */
public record AuthPrincipal(long userId, String username, String realName, String role) {

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    public boolean isTeacher() {
        return "TEACHER".equals(role);
    }

    public boolean isStudent() {
        return "STUDENT".equals(role);
    }
}
