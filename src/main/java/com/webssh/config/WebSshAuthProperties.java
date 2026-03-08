package com.webssh.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Web 应用认证属性类。
 * <p>
 * 用于配置登录本 Web SSH 应用所需的用户名和密码。这些凭据用于访问 Web 界面本身（而非目标 SSH 服务器），
 * 由 {@link com.webssh.config.SecurityConfig} 注入到 Spring Security 的用户服务中。
 * 通过配置文件外部化凭据，便于在不同环境中使用不同账号，且避免将密码硬编码在代码中。
 * </p>
 * <p>
 * 配置前缀：{@code webssh.auth}，例如：
 * </p>
 * <pre>
 * webssh:
 *   auth:
 *     username: myuser
 *     password: mypassword
 * </pre>
 *
 * @see com.webssh.config.SecurityConfig 使用本配置构建用户详情服务
 */
@ConfigurationProperties(prefix = "webssh.auth")
public class WebSshAuthProperties {

    /**
     * 登录用户名。
     * <p>
     * 配置项：{@code webssh.auth.username}，默认 "admin"。
     * 用于访问 Web 界面（登录页、SSH 终端页等），与目标 SSH 主机的账号无关。
     * </p>
     */
    private String username = "admin";

    /**
     * 登录密码。
     * <p>
     * 配置项：{@code webssh.auth.password}，默认 "admin123"。
     * 生产环境务必通过配置文件或环境变量覆盖，切勿使用默认值。
     * </p>
     */
    private String password = "admin123";

    /**
     * 获取登录用户名。
     *
     * @return 用户名
     */
    public String getUsername() {
        return username;
    }

    /**
     * 设置登录用户名。
     *
     * @param username 用户名
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * 获取登录密码。
     *
     * @return 密码（明文，用于与用户输入比对前会经 BCrypt 编码）
     */
    public String getPassword() {
        return password;
    }

    /**
     * 设置登录密码。
     *
     * @param password 密码
     */
    public void setPassword(String password) {
        this.password = password;
    }
}
