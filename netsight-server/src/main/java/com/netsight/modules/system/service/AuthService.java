package com.netsight.modules.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.JwtUtils;
import com.netsight.framework.security.LoginUser;
import com.netsight.modules.system.entity.SysUser;
import com.netsight.modules.system.mapper.SysRolePermissionMapper;
import com.netsight.modules.system.mapper.SysUserMapper;
import com.netsight.modules.system.mapper.SysUserRoleMapper;
import com.netsight.modules.workorder.entity.Repairer;
import com.netsight.modules.workorder.service.RepairerAccountResolver;
import com.netsight.modules.alert.wechat.WechatBindResult;
import com.netsight.modules.alert.wechat.WechatBindingService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 认证服务
 * PC 端：手机号 + 短信验证码登录
 * 登录成功签发 JWT Token
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRolePermissionMapper sysRolePermissionMapper;
    private final JwtUtils jwtUtils;
    private final StringRedisTemplate redisTemplate;
    private final SmsCodeSender smsCodeSender;
    private final RepairerAccountResolver repairerAccountResolver;
    private final WechatBindingService wechatBindingService;

    /** 验证码 Redis Key 前缀 */
    private static final String SMS_CODE_KEY = "netsight:sms:code:";

    /** 验证码发送限流 Key 前缀 */
    private static final String SMS_LIMIT_KEY = "netsight:sms:limit:";

    @Value("${netsight.sms.expire-minutes:5}")
    private long expireMinutes = 5;

    /**
     * 发送短信验证码。
     * 验证码生成与真实/模拟发送由 {@link SmsCodeSender} 的实现决定：
     * 生产 profile 使用真实发送，dev/test profile 使用固定验证码。
     */
    public void sendSmsCode(String phone) {
        // 1. 校验手机号格式
        if (phone == null || !phone.matches("^1[3-9]\\d{9}$")) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "手机号格式不正确");
        }
        // 2. 校验发送频率
        String limitKey = SMS_LIMIT_KEY + phone;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(limitKey))) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "发送过于频繁，请1分钟后再试");
        }
        // 3. 生成验证码
        String code = smsCodeSender.generateCode();
        // 4. 存入 Redis，5 分钟过期
        String redisKey = SMS_CODE_KEY + phone;
        redisTemplate.opsForValue().set(redisKey, code, Duration.ofMinutes(expireMinutes));
        // 5. 限流标记
        redisTemplate.opsForValue().set(limitKey, "1",
                Duration.ofSeconds(smsCodeSender.rateLimitSeconds()));
        // 6. 真实发送或开发/测试日志输出
        smsCodeSender.send(phone, code, redisKey);
    }

    /**
     * 手机号 + 验证码登录
     */
    public Map<String, Object> loginByPhone(String phone, String code, String clientType) {
        return loginByPhone(phone, code, clientType, null);
    }

    /**
     * 手机号 + 验证码登录；可选微信 bindTicket 在登录成功后追加绑定，绑定失败不影响登录。
     */
    public Map<String, Object> loginByPhone(String phone, String code, String clientType, String wechatBindTicket) {
        String redisKey = SMS_CODE_KEY + phone;
        String savedCode = redisTemplate.opsForValue().get(redisKey);
        if (savedCode == null || !savedCode.equals(code)) {
            throw new ServiceException(ResultCode.VERIFY_CODE_ERROR);
        }
        redisTemplate.delete(redisKey);

        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getPhone, phone)
                .orderByAsc(SysUser::getId));
        if (users.isEmpty()) {
            throw new ServiceException(ResultCode.PASSWORD_ERROR);
        }

        SysUser user;
        Repairer repairer = null;
        if ("m".equals(clientType)) {
            List<LoginCandidate> candidates = new java.util.ArrayList<>();
            for (SysUser candidateUser : users) {
                List<Repairer> bound = repairerAccountResolver.listBound(
                        candidateUser.getTenantId(), candidateUser.getId());
                List<Repairer> legacy = bound.isEmpty()
                        ? repairerAccountResolver.listLegacyByPhone(
                                candidateUser.getTenantId(), candidateUser.getPhone())
                        : List.of();
                for (Repairer candidateRepairer : bound) {
                    candidates.add(new LoginCandidate(candidateUser, candidateRepairer));
                }
                for (Repairer candidateRepairer : legacy) {
                    candidates.add(new LoginCandidate(candidateUser, candidateRepairer));
                }
            }
            if (candidates.isEmpty()) {
                throw new ServiceException(500, "当前账号未关联维修人员档案，请联系管理员");
            }
            if (candidates.size() > 1) {
                throw new ServiceException(500, "手机号关联多个维修人员账号，请联系管理员处理");
            }
            user = candidates.get(0).user();
            repairer = candidates.get(0).repairer();
            if (user.getStatus() == null || user.getStatus() != 1) {
                throw new ServiceException(500, "当前账号已被禁用，请联系管理员");
            }
            repairerAccountResolver.assertUsable(repairer);
        } else {
            List<SysUser> enabled = users.stream()
                    .filter(item -> item.getStatus() != null && item.getStatus() == 1)
                    .toList();
            if (enabled.isEmpty()) {
                throw new ServiceException(ResultCode.USER_DISABLED);
            }
            if (enabled.size() > 1) {
                throw new ServiceException(500, "手机号关联多个账号，请联系管理员");
            }
            user = enabled.get(0);
        }

        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(user.getId());
        loginUser.setUsername(user.getUsername());
        loginUser.setRealName(user.getRealName());
        loginUser.setPhone(user.getPhone());
        loginUser.setTenantId(user.getTenantId());
        loginUser.setRepairerId(repairer == null ? null : repairer.getId());
        // 从数据库加载真实角色与权限：sys_user_role -> sys_role -> sys_role_permission -> sys_permission
        Set<String> roles = loadRoles(user.getId());
        loginUser.setRoles(roles);
        loginUser.setPermissions(loadPermissions(user.getId(), roles));
        // 【修复】超管标识以角色为准（super_admin），不再按用户名"admin"硬编码判定
        loginUser.setIsSuperAdmin(roles.contains("super_admin"));
        loginUser.setClientType(clientType);

        String token = jwtUtils.createToken(loginUser);
        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("user", loginUser);
        if (StringUtils.hasText(wechatBindTicket)) {
            try {
                result.put("wechatBind", wechatBindingService.bindByTicket(
                        loginUser.getTenantId(), phone, wechatBindTicket));
            } catch (Exception e) {
                log.warn("微信绑定失败，不影响登录: {}", e.getMessage());
                result.put("wechatBind", WechatBindResult.builder().bound(false)
                        .reason("BIND_ERROR").message("微信绑定失败，请稍后重试或联系管理员。").build());
            }
        } else {
            result.put("wechatBind", WechatBindResult.builder().bound(false)
                    .reason("NO_TICKET").message("").build());
        }
        return result;
    }

    private record LoginCandidate(SysUser user, Repairer repairer) {
    }

    /**
     * 加载用户角色编码集合（sys_user_role -> sys_role）
     */
    private Set<String> loadRoles(Long userId) {
        List<String> roleKeys = sysUserRoleMapper.selectRoleKeysByUserId(userId);
        if (roleKeys == null || roleKeys.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(roleKeys);
    }

    /**
     * 加载用户权限标识集合（sys_user_role -> sys_role_permission -> sys_permission）
     * 超级管理员直接返回全权限 *:*:*
     */
    private Set<String> loadPermissions(Long userId, Set<String> roles) {
        if (roles.contains("super_admin")) {
            return Set.of("*:*:*");
        }
        Set<String> perms = new HashSet<>();
        List<Long> roleIds = sysUserRoleMapper.selectRoleIdsByUserId(userId);
        if (roleIds != null) {
            for (Long roleId : roleIds) {
                List<String> keys = sysRolePermissionMapper.selectPermKeysByRoleId(roleId);
                if (keys != null) {
                    perms.addAll(keys);
                }
            }
        }
        return perms;
    }

    /**
     * 退出登录：Token 加入黑名单，立即失效
     * <p>
     * 【修复】黑名单 Key 与 JwtAuthenticationFilter 校验格式保持一致（{subject}:{hashCode}），
     * 否则登出写入的 Key 永远无法被命中，旧 Token 在有效期内仍可访问接口；
     * TTL 按 Token 剩余有效期动态计算（此前固定 2h，与 JWT 12h 有效期不一致导致登出标记提前失效）。
     * </p>
     */
    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        Claims claims = jwtUtils.parseToken(token);
        if (claims == null || claims.getExpiration() == null || !claims.getExpiration().after(new Date())) {
            log.debug("登出跳过：Token 无效或已过期");
            return;
        }
        String redisKey = "netsight:token:blacklist:" + claims.getSubject() + ":" + token.hashCode();
        long remainSeconds = Duration.between(new Date().toInstant(), claims.getExpiration().toInstant()).getSeconds();
        if (remainSeconds <= 0) {
            return;
        }
        redisTemplate.opsForValue().set(redisKey, "1", Duration.ofSeconds(remainSeconds));
        log.info("用户退出登录，Token 已加入黑名单（剩余有效期 {}s）", remainSeconds);
    }
}
