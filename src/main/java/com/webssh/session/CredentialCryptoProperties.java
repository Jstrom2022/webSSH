package com.webssh.session;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 凭据加密配置属性类。
 * <p>
 * 绑定 {@code webssh.crypto.*} 配置项，用于配置凭据加解密所需的主密钥（master-key）。
 * 生产环境必须修改默认值，否则已加密的凭据将面临被破解的风险。
 * </p>
 *
 * @see CredentialCryptoService 使用本配置进行加解密
 */
@ConfigurationProperties(prefix = "webssh.crypto")
public class CredentialCryptoProperties {

    /**
     * 主密钥，用于派生 AES 加密密钥。
     * 默认值仅适用于开发环境，生产环境必须通过配置覆盖，否则存在安全风险。
     */
    private String masterKey = "change-this-master-key-in-production";

    /**
     * 获取主密钥。
     *
     * @return 主密钥字符串
     */
    public String getMasterKey() {
        return masterKey;
    }

    /**
     * 设置主密钥。
     *
     * @param masterKey 主密钥，建议使用足够长且随机的字符串
     */
    public void setMasterKey(String masterKey) {
        this.masterKey = masterKey;
    }
}
