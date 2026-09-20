package com.netsight.common.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Token 对称加密工具（AES-256-GCM，认证加密）
 *
 * 用途：网关 Token / Webhook Token / PushPlus Token 落库加密存储，
 * 解决「Token 明文入库」风险（数据库泄露不直接暴露可用凭证）。
 *
 * 设计要点：
 * 1. 密文带前缀 {@code enc:}，用于区分存量明文与密文，支持平滑迁移
 *    （decrypt 对非 enc: 前缀值原样返回，迁移期间读写兼容）。
 * 2. 密钥来自环境变量 {@code AES_TOKEN_KEY}（application.yml 提供开发兜底值，
 *    生产必须由部署环境注入），变更密钥会导致旧密文无法解密——生产禁止随意更换。
 * 3. AES-GCM 自带完整性校验（128 位 tag），密文被篡改时解密抛异常而非返回脏数据。
 * 4. 每次加密生成随机 12 字节 IV，同一明文多次加密结果不同。
 */
@Slf4j
@Component
public class TokenCrypto {

    /** 密文前缀：识别已加密值（未加密的存量明文无此前缀） */
    private static final String PREFIX = "enc:";
    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec keySpec;
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenCrypto(@Value("${aes.token-key:NetSightAesTokenKey2026DevOnly!}") String key) {
        if (key == null || key.isBlank() || key.length() < 16) {
            throw new IllegalStateException("Token 加密密钥（aes.token-key / AES_TOKEN_KEY）未配置或过短，拒绝启动");
        }
        byte[] raw = Arrays.copyOf(key.getBytes(StandardCharsets.UTF_8), 32);
        this.keySpec = new SecretKeySpec(raw, "AES");
        log.info("TokenCrypto 初始化完成（AES-256-GCM）");
    }

    /**
     * 加密明文 Token；已是密文（enc: 前缀）或空值原样返回（幂等）
     */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank() || plain.startsWith(PREFIX)) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LEN];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(ct);
        } catch (Exception e) {
            throw new IllegalStateException("Token 加密失败", e);
        }
    }

    /**
     * 解密密文 Token；非 enc: 前缀（存量明文/空值）原样返回（兼容迁移期数据）
     */
    public String decrypt(String cipher) {
        if (cipher == null || !cipher.startsWith(PREFIX)) {
            return cipher;
        }
        try {
            String body = cipher.substring(PREFIX.length());
            int idx = body.indexOf(':');
            if (idx <= 0) {
                return cipher;
            }
            byte[] iv = Base64.getDecoder().decode(body.substring(0, idx));
            byte[] ct = Base64.getDecoder().decode(body.substring(idx + 1));
            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Token 解密失败（密钥变更或密文损坏）", e);
        }
    }
}
