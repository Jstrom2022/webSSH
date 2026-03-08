package com.webssh.session;

/**
 * 持久化存储的 SSH 会话配置实体。
 * <p>
 * 与 {@link SshSessionProfile} 不同，本类用于磁盘存储和内部处理，敏感字段
 * （password、privateKey、passphrase）以加密形式存储在 encryptedXxx 字段中，
 * 确保 JSON 文件中不出现明文凭据。
 * </p>
 */
public class StoredSshSessionProfile {

    /** 会话唯一标识 */
    private String id;
    /** 会话显示名称 */
    private String name;
    /** SSH 服务器主机地址 */
    private String host;
    /** SSH 端口，默认 22 */
    private int port = 22;
    /** SSH 登录用户名 */
    private String username;
    /** 认证方式：PASSWORD 或 PRIVATE_KEY */
    private String authType;
    /** 主机公钥指纹 */
    private String hostFingerprint;
    /** 最后更新时间戳（毫秒） */
    private long updatedAt;
    /** 加密后的密码，格式为 v1$base64(iv)$base64(encrypted) */
    private String encryptedPassword;
    /** 加密后的私钥内容 */
    private String encryptedPrivateKey;
    /** 加密后的私钥 passphrase */
    private String encryptedPassphrase;

    /** @return 会话唯一标识 */
    public String getId() {
        return id;
    }

    /** @param id 会话唯一标识 */
    public void setId(String id) {
        this.id = id;
    }

    /** @return 会话显示名称 */
    public String getName() {
        return name;
    }

    /** @param name 会话显示名称 */
    public void setName(String name) {
        this.name = name;
    }

    /** @return SSH 服务器主机地址 */
    public String getHost() {
        return host;
    }

    /** @param host SSH 服务器主机地址 */
    public void setHost(String host) {
        this.host = host;
    }

    /** @return SSH 端口 */
    public int getPort() {
        return port;
    }

    /** @param port SSH 端口 */
    public void setPort(int port) {
        this.port = port;
    }

    /** @return SSH 登录用户名 */
    public String getUsername() {
        return username;
    }

    /** @param username SSH 登录用户名 */
    public void setUsername(String username) {
        this.username = username;
    }

    /** @return 认证方式 */
    public String getAuthType() {
        return authType;
    }

    /** @param authType 认证方式 */
    public void setAuthType(String authType) {
        this.authType = authType;
    }

    /** @return 主机公钥指纹 */
    public String getHostFingerprint() {
        return hostFingerprint;
    }

    /** @param hostFingerprint 主机公钥指纹 */
    public void setHostFingerprint(String hostFingerprint) {
        this.hostFingerprint = hostFingerprint;
    }

    /** @return 最后更新时间戳（毫秒） */
    public long getUpdatedAt() {
        return updatedAt;
    }

    /** @param updatedAt 最后更新时间戳 */
    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** @return 加密后的密码 */
    public String getEncryptedPassword() {
        return encryptedPassword;
    }

    /** @param encryptedPassword 加密后的密码 */
    public void setEncryptedPassword(String encryptedPassword) {
        this.encryptedPassword = encryptedPassword;
    }

    /** @return 加密后的私钥内容 */
    public String getEncryptedPrivateKey() {
        return encryptedPrivateKey;
    }

    /** @param encryptedPrivateKey 加密后的私钥内容 */
    public void setEncryptedPrivateKey(String encryptedPrivateKey) {
        this.encryptedPrivateKey = encryptedPrivateKey;
    }

    /** @return 加密后的私钥 passphrase */
    public String getEncryptedPassphrase() {
        return encryptedPassphrase;
    }

    /** @param encryptedPassphrase 加密后的私钥 passphrase */
    public void setEncryptedPassphrase(String encryptedPassphrase) {
        this.encryptedPassphrase = encryptedPassphrase;
    }
}
