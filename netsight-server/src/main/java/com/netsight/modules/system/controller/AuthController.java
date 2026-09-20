package com.netsight.modules.system.controller;

import com.netsight.common.core.R;
import com.netsight.modules.system.service.AuthService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import com.netsight.framework.aspectj.Log;
import com.netsight.framework.aspectj.RateLimit;

/**
 * 认证接口
 * 登录、获取验证码、登出
 */
@Validated
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;

    /**
     * 发送短信验证码
     * POST /auth/sms-code?phone=138xxxxxxx
     * 限流：同手机号+同 IP 60 秒内最多 5 次（防验证码接口被刷）；手机号维度另有 AuthService 内 Redis 限流（1 分钟 1 次）
     */
    @PostMapping("/sms-code")
    @RateLimit(key = "phone,ip", limit = 5, window = 60, message = "验证码获取过于频繁，请1分钟后再试")
    public R<Void> sendSmsCode(@RequestParam
                               @NotBlank(message = "手机号不能为空")
                               @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
                               String phone) {
        authService.sendSmsCode(phone);
        return R.ok("验证码已发送", null);
    }

    /**
     * 手机号 + 验证码登录
     * POST /auth/login
     */
    @PostMapping("/login")
    @Log(module="系统认证", action="用户登录")
    @RateLimit(key = "phone,ip", limit = 5, window = 60, message = "登录尝试过于频繁，请1分钟后再试")
    public R<Map<String, Object>> login(@RequestBody @Validated LoginRequest request) {
        return R.ok(authService.loginByPhone(request.getPhone(), request.getCode(), request.getClientType()));
    }

    /**
     * 退出登录
     * POST /auth/logout（需携带 Token）
     */
    @PostMapping("/logout")
    @Log(module="系统认证", action="用户登出")
    public R<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : null;
        authService.logout(token);
        return R.ok();
    }

    /**
     * 登录请求体
     */
    @Data
    public static class LoginRequest {
        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        private String phone;

        @NotBlank(message = "验证码不能为空")
        private String code;

        /** 登录端：pc / mini / app，默认 pc */
        private String clientType = "pc";
    }
}
