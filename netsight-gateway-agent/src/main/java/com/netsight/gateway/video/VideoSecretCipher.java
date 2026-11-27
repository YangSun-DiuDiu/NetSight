package com.netsight.gateway.video;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 视频密码本地加密（AES-256-GCM）。
 * <p>密钥保存在 conf/.video-key（POSIX 0600），与视频端点配置分开，避免配置文件泄露即密码泄露。</p>
 */
@Slf4j
@Component
public class VideoSecretCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private SecretKeySpec key;
    private Path keyPath;

    @PostConstruct
    public void init() {
        try {
            String confDir = System.getProperty("nams.conf",
                    "/opt/nams-gateway/conf/config.json");
            Path dir = Paths.get(confDir).getParent();
            if (!Files.exists(dir) && Files.exists(Paths.get("conf/config.json"))) {
                dir = Paths.get("conf").toAbsolutePath();
            }
            Files.createDirectories(dir);
            keyPath = dir.resolve(".video-key");
            if (Files.exists(keyPath)) {
                byte[] raw = Base64.getDecoder().decode(Files.readString(keyPath).trim());
                if (raw.length != 32) {
                    throw new IllegalStateException("video key length must be 32 bytes");
                }
                key = new SecretKeySpec(raw, "AES");
            } else {
                byte[] raw = new byte[32];
                random.nextBytes(raw);
                Files.writeString(keyPath, Base64.getEncoder().encodeToString(raw));
                try {
                    Files.setPosixFilePermissions(keyPath, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignored) {
                    // Windows 本地开发环境无 POSIX 权限
                }
                key = new SecretKeySpec(raw, "AES");
                log.info("已生成本地视频加密密钥: {}", keyPath);
            }
        } catch (Exception e) {
            log.error("初始化视频加密密钥失败: {}", e.getMessage(), e);
            throw new IllegalStateException("视频加密密钥初始化失败", e);
        }
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] enc = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + enc.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(enc, 0, out, iv.length, enc.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("视频密码加密失败", e);
        }
    }

    public String decrypt(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (!value.startsWith(PREFIX)) {
            // 兼容人工写入的明文配置；保存时会自动转为密文
            return value;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(raw, 0, iv, 0, IV_LEN);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(raw, IV_LEN, raw.length - IV_LEN), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("视频密码解密失败（配置可能来自其他网关）");
            return "";
        }
    }
}
