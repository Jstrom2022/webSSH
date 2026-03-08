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
}
