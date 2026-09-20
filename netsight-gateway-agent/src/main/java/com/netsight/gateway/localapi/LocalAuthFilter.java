package com.netsight.gateway.localapi;

import com.netsight.gateway.common.R;
import com.netsight.gateway.common.ResultCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 本地管理接口鉴权过滤器：
 * <ul>
 *   <li>放行：/local/api/login、/local/api/logout、/local/api/password（改密）、静态资源</li>
 *   <li>拦截：其余 /local/api/** —— 未登录 401；已登录但未完成强制改密 4002（仅放行 password）</li>
 * </ul>
 */
@Slf4j
@Component
public class LocalAuthFilter extends OncePerRequestFilter {

    private final LocalAuthService authService;

    private static final Set<String> PUBLIC_PATHS = Set.of("/local/api/login", "/local/api/logout", "/local/api/password");

    public LocalAuthFilter(LocalAuthService authService) {
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        // 非本地 API 与静态资源直接放行
        if (!uri.startsWith("/local/api/")) {
            chain.doFilter(request, response);
            return;
        }
        if (PUBLIC_PATHS.contains(uri)) {
            chain.doFilter(request, response);
            return;
        }
        String token = resolveToken(request);
        if (!authService.isLoggedIn(token)) {
            writeJson(response, ResultCode.UNAUTHORIZED, "未登录或会话失效");
            return;
        }
        // 强制改密未完成：仅放行 password 接口
        if (authService.needChangePwd(token) && !"/local/api/password".equals(uri)) {
            writeJson(response, ResultCode.NEED_CHANGE_PWD, "请先修改默认密码");
            return;
        }
        request.setAttribute("nams.token", token);
        chain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (LocalAuthService.COOKIE_NAME.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        String header = request.getHeader("X-NAMS-Token");
        return header;
    }

    private void writeJson(HttpServletResponse response, int code, String msg) throws IOException {
        response.setStatus(200);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":" + code + ",\"msg\":\"" + msg + "\"}");
    }
}
