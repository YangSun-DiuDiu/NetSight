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
import com.netsight.modules.workorder.mapper.RepairerMapper;
import com.netsight.modules.alert.channel.ChannelRegistry;
import com.netsight.modules.alert.channel.NotificationChannelSender;
import com.netsight.modules.alert.channel.SendRequest;
import com.netsight.modules.alert.channel.SendResult;
import com.netsight.modules.alert.service.NotifyChannelService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 认证服务
 * PC 端：手机号 + 短信验证码登录（开发阶段使用模拟验证码）
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
    private final NotifyChannelService notifyChannelService;
    private final ChannelRegistry channelRegistry;
    private final RepairerMapper repairerMapper;

    /** 验证码 Redis Key 前缀 */
    private static final String SMS_CODE_KEY = "netsight:sms:code:";

    /** 验证码发送限流 Key 前缀 */
    private static final String SMS_LIMIT_KEY = "netsight:sms:limit:";

    @Value("${netsight.sms.mock-mode:true}")
    private boolean mockMode;

    @Value("${netsight.sms.mock-code:123456}")
    private String mockCode;

    @Value("${netsight.sms.expire-minutes:5}")
    private long expireMinutes;

    /** 生产模式登录短信通道实例名（渠道管理里的实例名称） */
    @Value("${netsight.sms.login-channel-name:平台登录认证}")
    private String loginChannelName;

    /** 该通道实例所属租户（登录前无租户上下文，固定超管租户） */
    @Value("${netsight.sms.login-tenant-id:1}")
    private Long loginTenantId;

    /**
     * 发送短信验证码
     * 开发阶段：模拟验证码打印到日志，不实际发送
     * 生产阶段：对接短信服务商 API
     */
    public void sendSmsCode(String phone) {
        // 1. 校验手机号格式
        if (phone == null || !phone.matches("^1[3-9]\\d{9}$")) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "手机号格式不正确");
        }
        // 2. 校验发送频率：同一手机号 1 分钟内只能发送一次
        String limitKey = SMS_LIMIT_KEY + phone;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(limitKey))) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "发送过于频繁，请1分钟后再试");
        }
        // 3. 生成验证码（开发模式固定 123456）
        String code = mockMode ? mockCode : String.format("%06d", ThreadLocalRandom.current().nextInt(1000000));
        // 4. 存入 Redis，5 分钟过期
        String redisKey = SMS_CODE_KEY + phone;
        redisTemplate.opsForValue().set(redisKey, code, Duration.ofMinutes(expireMinutes));
        // 5. 限流标记：开发模式 10 秒便于联调，生产模式 1 分钟
        long limitSeconds = mockMode ? 10L : 60L;
        redisTemplate.opsForValue().set(limitKey, "1", Duration.ofSeconds(limitSeconds));
        // 6. 开发模式：打印验证码到日志；生产模式：走通知中心阿里云短信实例真实发送
        if (mockMode) {
            log.info("【模拟验证码】手机号: {}, 验证码: {}", phone, code);
        } else {
            sendLoginSms(phone, code, redisKey);
        }
    }

    /**
     * 生产环境真实发送登录验证码短信。
     * 复用通知中心：按实例名 + 超管租户查出阿里云短信实例，把验证码作为 {code} 变量传给阿里云模板渲染发送。
     * 发送失败则清除 Redis 中的验证码并抛业务异常，避免用户收不到码却留下可用验证码。
     */
    private void sendLoginSms(String phone, String code, String redisKey) {
        Map<String, Object> ch = notifyChannelService.resolveSystemChannel(loginChannelName, loginTenantId);
        if (ch == null) {
            redisTemplate.delete(redisKey);
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(),
                    "登录短信通道未配置（实例：" + loginChannelName + "），请联系管理员");
        }
        NotificationChannelSender sender = channelRegistry.get((String) ch.get("channelType"));
        if (sender == null) {
            redisTemplate.delete(redisKey);
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "短信通道实现未加载");
        }
        SendRequest request = SendRequest.builder()
                .receiverList(List.of(phone))
                .contentVars(Map.of("code", code))
                .channelConfig((Map<String, Object>) ch.get("config"))
                .build();
        SendResult result = sender.send(request);
        if (result == null || !result.isSuccess()) {
            redisTemplate.delete(redisKey);
            String msg = result == null ? "未知错误" : result.getErrorMsg();
            log.error("登录短信发送失败 phone={} err={}", phone, msg);
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "短信发送失败：" + msg);
        }
        log.info("登录短信已发送 phone={} bizId={}", phone, result.getThirdPartyMsgId());
    }

    /**
     * 手机号 + 验证码登录
     */
    public Map<String, Object> loginByPhone(String phone, String code, String clientType) {
        // 1. 校验验证码
        String redisKey = SMS_CODE_KEY + phone;
        String savedCode = redisTemplate.opsForValue().get(redisKey);
        if (savedCode == null || !savedCode.equals(code)) {
            throw new ServiceException(ResultCode.VERIFY_CODE_ERROR);
        }
        // 2. 验证码一次性使用，校验后删除
        redisTemplate.delete(redisKey);
        // 3. 按手机号查用户
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getPhone, phone)
                .last("LIMIT 1"));
        if (user == null) {
            throw new ServiceException(ResultCode.PASSWORD_ERROR);
        }
        if (user.getStatus() == null || user.getStatus() == 0) {
            throw new ServiceException(ResultCode.USER_DISABLED);
        }
        // 4. H5 维修端登录前置校验：clientType='m' 时必须已关联维修人员档案
        if ("m".equals(clientType)) {
            Repairer repairer = repairerMapper.selectOne(new LambdaQueryWrapper<Repairer>()
                    .eq(Repairer::getPhone, phone)
                    .eq(Repairer::getTenantId, user.getTenantId())
                    .last("LIMIT 1"));
            if (repairer == null) {
                throw new ServiceException(500, "当前账号未关联维修人员档案，请联系管理员");
            }
        }
        // 5. 构造 LoginUser 并签发 Token
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(user.getId());
        loginUser.setUsername(user.getUsername());
        loginUser.setRealName(user.getRealName());
        loginUser.setPhone(user.getPhone());
        loginUser.setTenantId(user.getTenantId());
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
        return result;
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
