package com.netsight.framework.security;

import com.netsight.common.exception.ServiceException;
import com.netsight.common.core.ResultCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import lombok.extern.slf4j.Slf4j;

/**
 * 安全上下文工具类
 * 从 Spring Security 上下文获取当前登录用户信息
 */
@Slf4j
public class SecurityUtils {

    private SecurityUtils() {
    }

    /**
     * 获取当前登录用户
     */
    public static LoginUser getLoginUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof LoginUser loginUser)) {
            throw new ServiceException(ResultCode.UNAUTHORIZED);
        }
        return loginUser;
    }

    /**
     * 当前是否已登录（存在有效的 LoginUser）
     */
    public static boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof LoginUser;
    }

    /**
     * 获取当前用户ID（未登录返回 null，用于租户插件兜底）
     */
    public static Long getUserId() {
        try {
            return getLoginUser().getUserId();
        } catch (ServiceException e) {
            return null;
        }
    }

    /**
     * 获取当前租户ID（未登录返回 0，避免租户过滤异常）
     */
    public static Long getTenantId() {
        try {
            return getLoginUser().getTenantId();
        } catch (ServiceException e) {
            return 0L;
        }
    }

    /**
     * 判断当前用户是否为超级管理员
     */
    public static boolean isSuperAdmin() {
        try {
            return getLoginUser().getIsSuperAdmin();
        } catch (ServiceException e) {
            return false;
        }
    }
}
