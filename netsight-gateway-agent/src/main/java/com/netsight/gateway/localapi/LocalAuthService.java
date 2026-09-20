package com.netsight.gateway.localapi;

import com.netsight.gateway.core.ConfigHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地管理员鉴权（与云端账号体系相互独立）。
 * <p>
 * 会话：内存 token → session（服务重启即失效，网关本地场景可接受）；
 * 密码：BCrypt 存储；config.json admin.password_hash 为空时使用出厂默认密码
 * admin123（仅开发/首启，生产部署必须预置哈希并首登强制改密）。
 * </p>
 */
@Slf4j
@Service
public class LocalAuthService {

    public static final String COOKIE_NAME = "NAMS_SESSION";
    /** 出厂默认密码（仅当 config.json 未配置 password_hash 时生效） */
    private static final String DEFAULT_PWD = "admin123";

    private final ConfigHolder configHolder;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /** token -> 是否已完成改密 */
    private final Map<String, Boolean> sessions = new ConcurrentHashMap<>();
    /** 登录失败计数（按用户名，简单限流：5 次/分钟） */
    private final Map<String, int[]> failCounter = new ConcurrentHashMap<>();

    public LocalAuthService(ConfigHolder configHolder) {
        this.configHolder = configHolder;
    }

    /** 校验用户名密码，成功返回会话 token，失败返回 null */
    public String login(String username, String password) {
        ConfigHolder.Admin admin = configHolder.getConfig().getLocal().getAdmin();
        String expectedUser = admin.getUsername();
        if (expectedUser == null) {
            expectedUser = "admin";
        }
        if (!expectedUser.equals(username)) {
            return null;
        }
        // 简单限流：同一用户名 1 分钟内失败 ≥5 次拒绝
        int[] cnt = failCounter.computeIfAbsent(username, k -> new int[]{0, 0});
        long now = System.currentTimeMillis() / 60000;
        if (cnt[1] != now) {
            cnt[0] = 0;
            cnt[1] = (int) now;
        }
        if (cnt[0] >= 5) {
            log.warn("本地管理员登录限流: {}", username);
            return "rate-limit";
        }
        boolean ok = matches(password, admin.getPasswordHash());
        if (!ok) {
            cnt[0]++;
            return null;
        }
        cnt[0] = 0;
        String token = UUID.randomUUID().toString().replace("-", "");
        sessions.put(token, admin.isForceChangePwd());
        return token;
    }

    private boolean matches(String raw, String hash) {
        if (hash == null || hash.isBlank()) {
            return DEFAULT_PWD.equals(raw);
        }
        return encoder.matches(raw, hash);
    }

    public boolean isLoggedIn(String token) {
        return token != null && sessions.containsKey(token);
    }

    /** 是否还需强制改密 */
    public boolean needChangePwd(String token) {
        Boolean v = sessions.get(token);
        return Boolean.TRUE.equals(v);
    }

    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /** 修改本地管理员密码：校验旧密码，更新 config.json（BCrypt 存储），并置 force_change_pwd=false */
    public synchronized String changePassword(String token, String oldPwd, String newPwd) {
        ConfigHolder.Admin admin = configHolder.getConfig().getLocal().getAdmin();
        if (!matches(oldPwd, admin.getPasswordHash())) {
            return "旧密码不正确";
        }
        if (newPwd == null || newPwd.length() < 8) {
            return "新密码长度至少 8 位";
        }
        String newHash = encoder.encode(newPwd);
        boolean saved = persistAdmin(newHash);
        // 内存同步更新（config.json 已落盘）
        admin.setPasswordHash(newHash);
        admin.setForceChangePwd(false);
        sessions.put(token, false);
        if (!saved) {
            return "密码已生效（本次会话内），但 config.json 写入失败，重启后恢复旧密码";
        }
        log.info("本地管理员密码已修改");
        return null;
    }

    private boolean persistAdmin(String newHash) {
        try {
            var mapper = new tools.jackson.databind.ObjectMapper();
            var node = (tools.jackson.databind.node.ObjectNode)
                    mapper.readTree(java.nio.file.Files.readString(
                            java.nio.file.Paths.get(configHolder.getConfDir(), "config.json")));
            // 修复：local/admin 节点缺失时自动创建（MissingNode 强转会抛 ClassCastException，
            // 导致首次改密写回失败、密码仅内存生效、重启后丢失）
            var local = node.has("local") && node.get("local").isObject()
                    ? (tools.jackson.databind.node.ObjectNode) node.get("local")
                    : node.putObject("local");
            var adminNode = local.has("admin") && local.get("admin").isObject()
                    ? (tools.jackson.databind.node.ObjectNode) local.get("admin")
                    : local.putObject("admin");
            adminNode.put("password_hash", newHash);
            adminNode.put("force_change_pwd", false);
            java.nio.file.Files.writeString(
                    java.nio.file.Paths.get(configHolder.getConfDir(), "config.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node));
            return true;
        } catch (Exception e) {
            log.error("回写 config.json 失败: {}", e.getMessage(), e);
            return false;
        }
    }
}
