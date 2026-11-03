package com.zhouzihang.exam.web;

import com.zhouzihang.exam.security.AuthPrincipal;
import com.zhouzihang.exam.security.CurrentUser;
import com.zhouzihang.exam.service.AuthService;
import com.zhouzihang.exam.web.dto.AuthDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/** 认证接口。登录接口在 WebConfig 中被排除在鉴权拦截器之外。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public AuthDtos.LoginResponse login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public AuthDtos.UserView me() {
        AuthPrincipal principal = CurrentUser.require();
        return new AuthDtos.UserView(principal.userId(), principal.username(),
                principal.realName(), principal.role());
    }
}
