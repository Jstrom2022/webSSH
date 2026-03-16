package com.webssh.bot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 机器人配置数据模型。
 * <p>
 * 每种机器人类型（telegram、qq 等）对应一个 BotSettings 实例。
 * 具体的机器人参数（如 Token、BotUsername）存储在 config Map 中，
 * 使各类型机器人可以灵活定义自己的配置项，而无需修改此模型。
 * </p>
 */
public class BotSettings {

    /** 机器人类型标识，如 "telegram"、"qq" */
    private String type;

    /** 是否启用 */
    private boolean enabled;

    /** 关联的 WebSSH 用户名，用于读取该用户的 SSH 会话配置列表 */
    private String sshUsername = "admin";

    /**
     * 机器人特定配置项。
     * <p>
     * 对于 Telegram：token, botUsername
     * 对于 QQ：appId, appSecret 等
     * </p>
     */
    private Map<String, String> config = new LinkedHashMap<>();

    /** 允许使用此机器人的用户 ID 列表 */
    private List<String> allowedUserIds = new ArrayList<>();

    /** 允许使用此机器人的群 ID 列表，保留兼容旧版 QQ 群聊配置 */
    private List<String> allowedGroupIds = new ArrayList<>();

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSshUsername() {
        return sshUsername;
    }

    public void setSshUsername(String sshUsername) {
        this.sshUsername = sshUsername;
    }

    public Map<String, String> getConfig() {
        return config;
    }

    public void setConfig(Map<String, String> config) {
        this.config = config;
    }

    public List<String> getAllowedUserIds() {
        return allowedUserIds;
    }

    public void setAllowedUserIds(List<String> allowedUserIds) {
        this.allowedUserIds = allowedUserIds;
    }

    public List<String> getAllowedGroupIds() {
        return allowedGroupIds;
    }

    public void setAllowedGroupIds(List<String> allowedGroupIds) {
        this.allowedGroupIds = allowedGroupIds;
    }
}
