package com.webssh.session;

/**
 * SSH 会话配置 DTO（数据传输对象）。
 * <p>
 * 用于 API 层面与前端交互，包含连接信息（主机、端口、用户名）、认证信息（密码、私钥）
 * 和元数据（指纹、更新时间）。
 * </p>
 * <p>
 * <b>敏感字段暴露策略：</b> password、privateKey、passphrase 仅在 GET /{id} 详情查询时
 * 返回明文；列表接口（list）仅返回 hasSavedCredentials 等元信息，不返回实际凭据，
 * 以降低敏感数据在传输和展示中的暴露面。
 * </p>
 */
public class SshSessionProfile {

    /** 会话唯一标识，新建时由服务端生成 UUID */
    private String id;
    /** 会话显示名称，便于用户区分多个连接 */
    private String name;
    /** SSH 服务器主机地址 */
    private String host;
    /** SSH 端口，默认 22 */
    private int port = 22;
    /** SSH 登录用户名 */
    private String username;
    /** 认证方式：PASSWORD 或 PRIVATE_KEY */
    private String authType;
    /** 主机公钥指纹（如 SHA256:xxx），用于首次连接校验 */
    private String hostFingerprint;
    /** 最后更新时间戳（毫秒） */
    private long updatedAt;
    /** 是否保存凭据到本地存储 */
    private boolean saveCredentials;
    /** 是否已有保存的凭据（列表接口用，不暴露实际凭据） */
    private boolean hasSavedCredentials;
    /** 密码（明文），仅详情接口返回 */
    private String password;
    /** 私钥内容（明文），仅详情接口返回 */
    private String privateKey;
    /** 私钥 passphrase（明文），仅详情接口返回 */
    private String passphrase;

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

    /** @return 认证方式：PASSWORD 或 PRIVATE_KEY */
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

    /** @return 是否保存凭据到本地存储 */
    public boolean isSaveCredentials() {
        return saveCredentials;
    }

    /** @param saveCredentials 是否保存凭据 */
    public void setSaveCredentials(boolean saveCredentials) {
        this.saveCredentials = saveCredentials;
    }

    /** @return 是否已有保存的凭据（列表接口用） */
    public boolean isHasSavedCredentials() {
        return hasSavedCredentials;
    }

    /** @param hasSavedCredentials 是否已有保存的凭据 */
    public void setHasSavedCredentials(boolean hasSavedCredentials) {
        this.hasSavedCredentials = hasSavedCredentials;
    }

    /** @return 密码（明文），仅详情接口返回 */
    public String getPassword() {
        return password;
    }

    /** @param password 密码 */
    public void setPassword(String password) {
        this.password = password;
    }

    /** @return 私钥内容（明文），仅详情接口返回 */
    public String getPrivateKey() {
        return privateKey;
    }

    /** @param privateKey 私钥内容 */
    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    /** @return 私钥 passphrase（明文），仅详情接口返回 */
    public String getPassphrase() {
        return passphrase;
    }

    /** @param passphrase 私钥 passphrase */
    public void setPassphrase(String passphrase) {
        this.passphrase = passphrase;
    }
}
