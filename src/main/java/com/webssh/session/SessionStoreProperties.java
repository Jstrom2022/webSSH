package com.webssh.session;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会话存储配置属性类。
 * <p>
 * 绑定 {@code webssh.session-store.*} 配置项，用于配置会话数据（JSON 文件）的存储目录。
 * 每个用户的会话配置按用户名隔离存储为独立文件。
 * </p>
 *
 * @see SessionProfileStore 使用本配置进行会话持久化
 */
@ConfigurationProperties(prefix = "webssh.session-store")
public class SessionStoreProperties {

    /**
     * 会话数据存储根目录。
     * 相对路径相对于应用工作目录解析，建议生产环境使用绝对路径以便于备份和迁移。
     */
    private String directory = "./data/sessions";

    /**
     * 获取会话存储目录路径。
     *
     * @return 目录路径字符串
     */
    public String getDirectory() {
        return directory;
    }

    /**
     * 设置会话存储目录路径。
     *
     * @param directory 目录路径，支持相对路径或绝对路径
     */
    public void setDirectory(String directory) {
        this.directory = directory;
    }
}
