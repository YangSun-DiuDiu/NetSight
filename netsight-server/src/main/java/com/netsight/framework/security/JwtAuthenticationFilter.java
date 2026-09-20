package com.netsight.framework.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * JWT 认证过滤器
 * 从请求头解析 Token → 校验 → 构造 LoginUser 存入 SecurityContext
 * 未携带/无效 Token 时放行（由 Security 配置的匿名访问与 401 兜底）
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;
    private final StringRedisTemplate redisTemplate;

    @Value("${jwt.header:Authorization}")
    private String header;

    @Value("${jwt.prefix:Bearer }")
    private String prefix;

    /** Redis 黑名单 Key 前缀 */
    private static final String TOKEN_BLACKLIST_KEY = "netsight:token:blacklist:";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (StringUtils.hasText(token) && SecurityContextHolder.getContext().getAuthentication() == null) {
            Claims claims = null;
            try {
                claims = jwtUtils.parseToken(token);
            } catch (Exception e) {
                // 无效/过期 Token 不阻断请求：白名单接口放行，受保护接口由 Security 兜底 401
                log.debug("无效 Token 忽略: {}", e.getMessage());
            }
            if (claims != null && claims.getExpiration().after(new Date())) {
                // 校验黑名单（登出后 Token 立即失效）
                String blacklistKey = TOKEN_BLACKLIST_KEY + claims.getSubject() + ":" + token.hashCode();
                Boolean isBlacklisted = redisTemplate.hasKey(blacklistKey);
                if (!Boolean.TRUE.equals(isBlacklisted)) {
                    LoginUser loginUser = buildLoginUser(claims);
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 从请求头解析 Token
     */
    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader(header);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(prefix)) {
            return bearerToken.substring(prefix.length());
        }
        return null;
    }

    /**
     * 从 Claims 构造 LoginUser
     */
    @SuppressWarnings("unchecked")
    private LoginUser buildLoginUser(Claims claims) {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(Long.valueOf(claims.getSubject()));
        loginUser.setUsername(claims.get("username", String.class));
        loginUser.setRealName(claims.get("realName", String.class));
        loginUser.setPhone(claims.get("phone", String.class));
        loginUser.setTenantId(claims.get("tenantId", Long.class));
        loginUser.setClientType(claims.get("clientType", String.class));
        // 角色与权限从 JWT Claims 恢复（骨架阶段方案；生产可切换 Redis 缓存用户权限）
        // JJWT 反序列化 JSON 数组为 List，需转 Set
        List<String> roles = claims.get("roles", List.class);
        List<String> permissions = claims.get("permissions", List.class);
        loginUser.setRoles(roles == null ? Set.of() : new java.util.HashSet<>(roles));
        loginUser.setPermissions(permissions == null ? Set.of() : new java.util.HashSet<>(permissions));
        // 超管标识：以角色为准（super_admin），避免"用户名恰为 admin"被误判为超管
        loginUser.setIsSuperAdmin(loginUser.getRoles().contains("super_admin"));
        return loginUser;
    }
}
