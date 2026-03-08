package com.webssh.session;

import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 凭据加解密服务。
 * <p>
 * 使用 AES-256-GCM 对称加密保护用户的 SSH 密码和私钥等敏感凭据。
 * 密文格式为 {@code "v1$base64(iv)$base64(encrypted)"}，其中 v1 为版本前缀，
 * 便于未来升级加密算法时向后兼容。
 * </p>
 * <p>
 * 主密钥通过 SHA-256 哈希从配置的 master-key 派生，而非直接使用原始字符串，
 * 这样即使 master-key 长度不固定，也能得到固定 256 位的 AES 密钥。
 * </p>
 */
@Service
public class CredentialCryptoService {

    /** IV 长度 12 字节，符合 GCM 模式推荐值，兼顾安全与性能 */
    private static final int IV_SIZE = 12;
    /** GCM 认证标签 128 位，提供完整性校验，防止密文被篡改 */
    private static final int GCM_TAG_BITS = 128;
    /** 密文版本前缀，用于未来算法升级时识别并迁移旧数据 */
    private static final String PREFIX = "v1";

    /** AES 密钥，由 master-key 经 SHA-256 派生得到 */
    private final SecretKeySpec secretKeySpec;
    /** 密码学安全的随机数生成器，用于生成每次加密的 IV */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 构造凭据加解密服务。
     *
     * @param properties 加密配置，必须包含非空的 master-key
     * @throws IllegalStateException 当 master-key 为空时抛出
     */
    public CredentialCryptoService(CredentialCryptoProperties properties) {
        String masterKey = properties.getMasterKey();
        if (masterKey == null || masterKey.isBlank()) {
            throw new IllegalStateException("webssh.crypto.master-key 不能为空");
        }
        // 使用 SHA-256 将 master-key 固定为 32 字节，满足 AES-256 密钥长度要求
        this.secretKeySpec = new SecretKeySpec(sha256(masterKey), "AES");
    }

    /**
     * 加密明文凭据。
     * <p>
     * 每次加密使用新的随机 IV，确保相同明文产生不同密文，防止重放和模式分析攻击。
     * </p>
     *
     * @param plainText 明文（如密码、私钥、passphrase）
     * @return 密文字符串，格式为 v1$base64(iv)$base64(encrypted)；若输入为空则返回 null
     */
    public String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return null;
        }
        try {
            // 每次加密生成新 IV，GCM 要求 IV 不可重复使用
            byte[] iv = new byte[IV_SIZE];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            // 拼接版本、IV、密文，便于存储和解析
            return PREFIX
                    + "$"
                    + Base64.getEncoder().encodeToString(iv)
                    + "$"
                    + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("凭据加密失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解密密文凭据。
     * <p>
     * 会校验密文格式和 GCM 认证标签，若密文被篡改将抛出异常。
     * </p>
     *
     * @param cipherText 密文字符串，格式须为 v1$base64(iv)$base64(encrypted)
     * @return 解密后的明文；若输入为空或格式非法则返回 null 或抛出异常
     */
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            return null;
        }
        try {
            String[] parts = cipherText.split("\\$");
            if (parts.length != 3 || !PREFIX.equals(parts[0])) {
                throw new IllegalArgumentException("密文格式不支持");
            }
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] data = Base64.getDecoder().decode(parts[2]);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(data);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("凭据解密失败: " + e.getMessage(), e);
        }
    }

    /**
     * 使用 SHA-256 对输入进行哈希，用于从 master-key 派生 AES 密钥。
     * <p>
     * 不直接使用 master-key 作为密钥的原因：用户配置的字符串长度不固定，
     * 而 AES 需要固定长度密钥；哈希可输出 256 位，满足 AES-256 要求。
     * </p>
     *
     * @param input 原始字符串（通常为 master-key）
     * @return 32 字节的 SHA-256 哈希值
     */
    private byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("初始化密钥失败: " + e.getMessage(), e);
        }
    }
}
