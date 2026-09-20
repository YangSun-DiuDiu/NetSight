package com.netsight.gateway.localapi;

import com.netsight.gateway.common.R;
import com.netsight.gateway.common.ResultCode;
import com.netsight.gateway.core.ConfigHolder;
import com.netsight.gateway.core.RuntimeState;
import com.netsight.gateway.sync.MappingSync;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地管理接口（路由器风格管理页面数据源），前缀 /local/api/*。
 * 鉴权由 {@link LocalAuthFilter} 统一处理（cookie 会话 + 强制改密）。
 */
@Slf4j
@RestController
@RequestMapping("/local/api")
public class LocalApiController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String VERSION = "nams-agent 1.0.0 (NetSight 边缘网关)";

    private final ConfigHolder configHolder;
    private final RuntimeState runtimeState;
    private final MappingSync mappingSync;
    private final LocalAuthService authService;
    private final LocalLogService logService;

    public LocalApiController(ConfigHolder configHolder, RuntimeState runtimeState,
                              MappingSync mappingSync, LocalAuthService authService,
                              LocalLogService logService) {
        this.configHolder = configHolder;
        this.runtimeState = runtimeState;
        this.mappingSync = mappingSync;
        this.authService = authService;
        this.logService = logService;
    }

    /** 登录：成功返回会话 token（Set-Cookie） */
    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody LoginReq req, HttpServletResponse response) {
        String token = authService.login(req.getUsername(), req.getPassword());
        if ("rate-limit".equals(token)) {
            return R.fail(ResultCode.RATE_LIMIT, "登录尝试过于频繁，请 1 分钟后再试");
        }
        if (token == null) {
            return R.fail(ResultCode.BAD_CREDENTIALS, "用户名或密码错误");
        }
        Cookie cookie = new Cookie(LocalAuthService.COOKIE_NAME, token);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(12 * 3600);
        response.addCookie(cookie);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("needChangePwd", authService.needChangePwd(token));
        return R.ok(data);
    }

    @PostMapping("/logout")
    public R<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        authService.logout(resolveToken(request));
        Cookie cookie = new Cookie(LocalAuthService.COOKIE_NAME, "");
        cookie.setMaxAge(0);
        cookie.setPath("/");
        response.addCookie(cookie);
        return R.ok(null);
    }

    /** 修改本地管理员密码 */
    @PostMapping("/password")
    public R<Void> changePassword(@RequestBody ChangePwdReq req, HttpServletRequest request) {
        String err = authService.changePassword(resolveToken(request), req.getOldPwd(), req.getNewPwd());
        if (err != null) {
            return R.fail(ResultCode.OPERATION_FAILED, err);
        }
        return R.ok(null);
    }

    /** 状态总览 */
    @GetMapping("/overview")
    public R<Map<String, Object>> overview(HttpServletRequest request) {
        ConfigHolder.GatewayConfig cfg = configHolder.getConfig();
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> gateway = new LinkedHashMap<>();
        gateway.put("gatewayCode", cfg.getGatewayCode());
        gateway.put("gatewayName", cfg.getGatewayName());
        gateway.put("cloudConnected", runtimeState.isCloudConnected());
        gateway.put("currentLink", runtimeState.getCurrentLink());
        gateway.put("lastHeartbeatTime", runtimeState.getLastHeartbeatTime());
        gateway.put("lastSnapshotTime", runtimeState.getLastSnapshotTime());
        gateway.put("lastError", runtimeState.getLastError());
        gateway.put("version", VERSION);
        gateway.put("needChangePwd", authService.needChangePwd(resolveToken(request)));
        data.put("gateway", gateway);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("heartbeatCount", runtimeState.getHeartbeatCount());
        stats.put("snapshotCount", runtimeState.getSnapshotCount());
        stats.put("snapshotFailCount", runtimeState.getSnapshotFailCount());
        stats.put("alertForwardCount", runtimeState.getAlertForwardCount());
        stats.put("alertFailCount", runtimeState.getAlertFailCount());
        stats.put("cacheQueueSize", runtimeState.getCacheQueueSize());
        stats.put("lastSyncResult", runtimeState.getLastSyncResult());
        data.put("stats", stats);

        Map<String, Object> deviceStat = new LinkedHashMap<>();
        deviceStat.put("total", runtimeState.getDeviceStates().size());
        deviceStat.put("online", runtimeState.onlineCount());
        deviceStat.put("offline", runtimeState.offlineCount());
        deviceStat.put("lineAbnormal", runtimeState.lineAbnormalCount());
        data.put("device", deviceStat);

        Map<String, Object> cloud = new LinkedHashMap<>();
        cloud.put("baseUrl", cfg.getCloud().getBaseUrl());
        cloud.put("tenantId", cfg.getCloud().getTenantId());
        cloud.put("heartbeatInterval", cfg.getCloud().getHeartbeatInterval());
        cloud.put("statusInterval", cfg.getCloud().getStatusInterval());
        data.put("cloud", cloud);
        return R.ok(data);
    }

    /** 设备采集列表（映射清单元信息 + 实时状态） */
    @GetMapping("/devices")
    public R<List<Map<String, Object>>> devices() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (MappingSync.MappingDevice d : mappingSync.getDevices()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("deviceCode", d.getDeviceCode());
            item.put("deviceName", d.getDeviceName());
            item.put("deviceIp", d.getDeviceIp());
            item.put("deviceType", d.getDeviceType());
            item.put("location", d.getLocation());
            item.put("collectType", d.getCollectType());
            item.put("collectPort", d.getCollectPort());
            RuntimeState.DeviceState st = runtimeState.getDeviceStates().get(d.getDeviceCode());
            if (st != null) {
                item.put("up", st.getUp());
                item.put("lineAbnormal", st.getLineAbnormal());
                item.put("collectedAt", st.getCollectedAt());
            } else {
                item.put("up", null);
                item.put("lineAbnormal", null);
                item.put("collectedAt", "-");
            }
            list.add(item);
        }
        return R.ok(list);
    }

    /** 手动同步采集清单（立即拉取/检测 mapping.json） */
    @PostMapping("/sync")
    public R<String> sync() {
        String result = mappingSync.sync();
        return R.ok(result);
    }

    /** 链路配置（只读展示） */
    @GetMapping("/links")
    public R<List<Map<String, Object>>> links() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ConfigHolder.Link l : configHolder.getConfig().getLinks()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", l.getType());
            item.put("priority", l.getPriority());
            item.put("enabled", l.isEnabled());
            list.add(item);
        }
        return R.ok(list);
    }

    /** 系统日志尾部 */
    @GetMapping("/logs")
    public R<String> logs(@RequestParam(defaultValue = "200") int lines) {
        return R.ok(logService.tail(lines));
    }

    /** 重启 nams-agent（systemd） */
    @PostMapping("/restart")
    public R<String> restart() {
        try {
            Process p = new ProcessBuilder("systemctl", "restart", "nams-agent")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            int code = p.waitFor();
            if (code == 0) {
                return R.ok("重启指令已下发，服务将在数秒内恢复");
            }
            return R.fail(ResultCode.OPERATION_FAILED, "systemctl 返回 " + code + ": " + out.trim());
        } catch (Exception e) {
            log.error("重启指令失败: {}", e.getMessage(), e);
            return R.fail(ResultCode.OPERATION_FAILED, "重启指令失败（非 systemd 环境？）: " + e.getMessage());
        }
    }

    private String resolveToken(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (LocalAuthService.COOKIE_NAME.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return request.getHeader("X-NAMS-Token");
    }

    @Data
    public static class LoginReq {
        private String username;
        private String password;
    }

    @Data
    public static class ChangePwdReq {
        private String oldPwd;
        private String newPwd;
    }
}
