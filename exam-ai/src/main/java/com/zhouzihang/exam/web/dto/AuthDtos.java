package com.zhouzihang.exam.web.dto;

import javax.validation.constraints.NotBlank;

/** 认证相关 DTO。 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank(message = "用户名不能为空") String username,
                               @NotBlank(message = "密码不能为空") String password) {
    }

    public record UserView(long id, String username, String realName, String role) {
    }

    public record LoginResponse(String token, String tokenType, long expiresInSeconds, UserView user) {
    }
}
