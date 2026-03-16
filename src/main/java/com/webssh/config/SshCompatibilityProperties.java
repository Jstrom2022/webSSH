package com.webssh.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SSH 兼容性配置属性类。
 * <p>
 * 用于控制与老旧 SSH 服务器的连接行为。OpenSSH 8.8+ 默认禁用了 ssh-rsa 签名算法（SHA-1 存在安全风险），
 * 但部分旧版 Linux 或嵌入式设备仍仅支持 ssh-rsa。通过本类可在必要时开启兼容模式，
 * 在安全性与兼容性之间做权衡。
 * </p>
 * <p>
 * 配置前缀：{@code webssh.ssh}，例如 {@code webssh.ssh.allow-legacy-ssh-rsa=true}
 * </p>
 *
 * @see <a href="https://www.openssh.com/legacy.html">OpenSSH 8.8 变更说明</a>
 */
@ConfigurationProperties(prefix = "webssh.ssh")
public class SshCompatibilityProperties {

    /**
     * 是否允许使用旧版 ssh-rsa 算法连接目标主机。
     * <p>
     * 默认 false，即优先使用更安全的 rsa-sha2-256/512。仅在目标主机明确不支持新算法、
     * 且无法升级时设为 true。开启后会降低连接安全性，需谨慎使用。
     * </p>
     * <p>
     * 配置项：{@code webssh.ssh.allow-legacy-ssh-rsa}
     * </p>
     */
    private boolean allowLegacySshRsa = false;

    /**
     * SSH keepalive 间隔（毫秒）。
     * <p>
     * 通过 JSch {@code setServerAliveInterval} 发送 keepalive 包，
     * 避免空闲连接被中间网络设备或服务器提前断开。
     * </p>
     * <p>
     * 配置项：{@code webssh.ssh.server-alive-interval-ms}
     * </p>
     */
    private int serverAliveIntervalMs = 30_000;

    /**
     * SSH keepalive 最大连续失败次数。
     * <p>
     * 对应 JSch {@code setServerAliveCountMax}，
     * 当连续发送 keepalive 失败超过该值时，JSch 会断开连接。
     * </p>
     * <p>
     * 配置项：{@code webssh.ssh.server-alive-count-max}
     * </p>
     */
    private int serverAliveCountMax = 3;

    /**
     * 获取是否允许旧版 ssh-rsa 算法。
     *
     * @return true 表示允许，false 表示不允许（默认）
     */
    public boolean isAllowLegacySshRsa() {
        return allowLegacySshRsa;
    }

    /**
     * 设置是否允许旧版 ssh-rsa 算法。
     *
     * @param allowLegacySshRsa true 表示允许，用于连接仅支持 ssh-rsa 的旧主机
     */
    public void setAllowLegacySshRsa(boolean allowLegacySshRsa) {
        this.allowLegacySshRsa = allowLegacySshRsa;
    }

    /**
     * 获取 SSH keepalive 间隔（毫秒）。
     *
     * @return keepalive 间隔，<= 0 表示不启用
     */
    public int getServerAliveIntervalMs() {
        return serverAliveIntervalMs;
    }

    /**
     * 设置 SSH keepalive 间隔（毫秒）。
     *
     * @param serverAliveIntervalMs keepalive 间隔，<= 0 表示不启用
     */
    public void setServerAliveIntervalMs(int serverAliveIntervalMs) {
        this.serverAliveIntervalMs = serverAliveIntervalMs;
    }

    /**
     * 获取 SSH keepalive 最大连续失败次数。
     *
     * @return 最大失败次数，<= 0 表示使用 JSch 默认值
     */
    public int getServerAliveCountMax() {
        return serverAliveCountMax;
    }

    /**
     * 设置 SSH keepalive 最大连续失败次数。
     *
     * @param serverAliveCountMax 最大失败次数，<= 0 表示使用 JSch 默认值
     */
    public void setServerAliveCountMax(int serverAliveCountMax) {
        this.serverAliveCountMax = serverAliveCountMax;
    }
}
