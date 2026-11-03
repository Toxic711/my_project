package com.zhouzihang.exam.service;

import com.zhouzihang.exam.common.ApiException;
import com.zhouzihang.exam.domain.User;
import com.zhouzihang.exam.mapper.UserMapper;
import com.zhouzihang.exam.security.JwtService;
import com.zhouzihang.exam.security.Pbkdf2PasswordEncoder;
import com.zhouzihang.exam.web.dto.AuthDtos;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 登录服务：校验 PBKDF2 密码 -> 签发 JWT。
 *
 * <p>面试讲解点：登录失败统一返回“用户名或密码错误”，不区分用户不存在/密码错，
 * 避免账号枚举；同时用恒定时间比较密码哈希（见 Pbkdf2PasswordEncoder）。</p>
 */
@Slf4j
@Service
public class AuthService {

    private final UserMapper userMapper;
    private final Pbkdf2PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserMapper userMapper, Pbkdf2PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public AuthDtos.LoginResponse login(AuthDtos.LoginRequest request) {
        User user = userMapper.findByUsername(request.username());
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("登录失败 username={}", request.username());
            throw ApiException.unauthorized("用户名或密码错误");
        }
        if (user.getEnabled() == null || user.getEnabled() != 1) {
            throw ApiException.forbidden("账号已被禁用");
        }
        String token = jwtService.issue(user);
        return new AuthDtos.LoginResponse(token, "Bearer", jwtService.expiresInSeconds(),
                new AuthDtos.UserView(user.getId(), user.getUsername(), user.getRealName(), user.getRole()));
    }
}
