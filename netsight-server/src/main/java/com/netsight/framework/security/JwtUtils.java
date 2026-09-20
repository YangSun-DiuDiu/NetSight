package com.netsight.framework.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT Token 工具类
 * 负责 Token 的生成、解析、过期校验
 * 密钥通过配置注入（开发阶段有默认值，生产必须环境变量注入）
 */
@Slf4j
@Component
public class JwtUtils {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expire-minutes:120}")
    private long expireMinutes;

    private SecretKey key;

    @PostConstruct
    public void init() {
        // HS256 要求密钥至少 256 位（32字节）
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 Token（角色/权限写入 Claims，供过滤器恢复登录态）
     */
    public String createToken(LoginUser loginUser) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expireMinutes * 60 * 1000);
        return Jwts.builder()
                .subject(String.valueOf(loginUser.getUserId()))
                .claim("username", loginUser.getUsername())
                .claim("realName", loginUser.getRealName())
                .claim("phone", loginUser.getPhone())
                .claim("tenantId", loginUser.getTenantId())
                .claim("clientType", loginUser.getClientType())
                .claim("roles", loginUser.getRoles() == null ? new java.util.HashSet<>() : loginUser.getRoles())
                .claim("permissions", loginUser.getPermissions() == null ? new java.util.HashSet<>() : loginUser.getPermissions())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    /**
     * 解析 Token，返回 Claims；无效/过期返回 null
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            log.debug("Token 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 校验 Token 是否有效
     */
    public boolean validateToken(String token) {
        Claims claims = parseToken(token);
        return claims != null && claims.getExpiration().after(new Date());
    }
}
