package com.webssh.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 统一资源治理配置。
 * <p>
 * 用于收敛各类异步执行入口的线程池规模、每用户并发上限、消息速率与任务超时，
 * 避免不同模块各自维护一套参数，导致资源治理策略不一致。
 * </p>
 */
@ConfigurationProperties(prefix = "webssh.resource")
public class ResourceGovernanceProperties {

    private static final int AVAILABLE_PROCESSORS = Math.max(1, Runtime.getRuntime().availableProcessors());

    /** SFTP 文件操作线程池。 */
    private ExecutorPool sftp = new ExecutorPool(
            Math.max(4, AVAILABLE_PROCESSORS),
            Math.max(4, AVAILABLE_PROCESSORS) * 2,
            256);

    /** WebSocket Shell 输出读取线程池。 */
    private ExecutorPool shellOutput = new ExecutorPool(
            Math.max(4, AVAILABLE_PROCESSORS),
            128,
            0);

    /** Bot Shell 命令执行与输出读取线程池。 */
    private ExecutorPool botCommand = new ExecutorPool(
            Math.max(2, Math.min(AVAILABLE_PROCESSORS, 4)),
            16,
            128);

    /** AI CLI 任务线程池。 */
    private ExecutorPool aiTask = new ExecutorPool(
            2,
            8,
            32);

    /** QQ 事件处理线程池。 */
    private ExecutorPool qqEvent = new ExecutorPool(
            2,
            16,
            128);

    /** 微信 ClawBot 事件处理线程池。 */
    private ExecutorPool wechatEvent = new ExecutorPool(
            2,
            16,
            128);

    /** 单个登录用户允许同时持有的 WebSocket Shell 数量。 */
    private int wsShellPerUser = 6;

    /** 单个 Bot 用户允许同时执行的普通 Shell 命令数量。 */
    private int botCommandPerUser = 2;

    /** 单个 Bot 用户允许同时执行的 AI 任务数量。 */
    private int aiTaskPerUser = 1;

    /** 单个 QQ 用户允许同时占用的事件处理任务数量。 */
    private int qqEventPerUser = 2;

    /** 单个微信用户允许同时占用的事件处理任务数量。 */
    private int wechatEventPerUser = 2;

    /** 单个 Bot 用户在速率窗口内允许提交的命令/提示词数量。 */
    private int botMessageRateLimit = 8;

    /** Bot 用户消息速率窗口。 */
    private Duration botMessageRateWindow = Duration.ofSeconds(10);

    /** 单个 QQ 用户在速率窗口内允许提交的消息数量。 */
    private int qqMessageRateLimit = 6;

    /** QQ 用户消息速率窗口。 */
    private Duration qqMessageRateWindow = Duration.ofSeconds(10);

    /** 单个微信用户在速率窗口内允许提交的消息数量。 */
    private int wechatMessageRateLimit = 6;

    /** 微信用户消息速率窗口。 */
    private Duration wechatMessageRateWindow = Duration.ofSeconds(10);

    /** Bot 普通 Shell 命令的最大执行时长。 */
    private Duration botCommandTimeout = Duration.ofSeconds(30);

    /** AI CLI 任务的最大执行时长。 */
    private Duration aiTaskTimeout = Duration.ofMinutes(15);

    public ExecutorPool getSftp() {
        return sftp;
    }

    public void setSftp(ExecutorPool sftp) {
        this.sftp = sftp;
    }

    public ExecutorPool getShellOutput() {
        return shellOutput;
    }

    public void setShellOutput(ExecutorPool shellOutput) {
        this.shellOutput = shellOutput;
    }

    public ExecutorPool getBotCommand() {
        return botCommand;
    }

    public void setBotCommand(ExecutorPool botCommand) {
        this.botCommand = botCommand;
    }

    public ExecutorPool getAiTask() {
        return aiTask;
    }

    public void setAiTask(ExecutorPool aiTask) {
        this.aiTask = aiTask;
    }

    public ExecutorPool getQqEvent() {
        return qqEvent;
    }

    public void setQqEvent(ExecutorPool qqEvent) {
        this.qqEvent = qqEvent;
    }

    public ExecutorPool getWechatEvent() {
        return wechatEvent;
    }

    public void setWechatEvent(ExecutorPool wechatEvent) {
        this.wechatEvent = wechatEvent;
    }

    public int getWsShellPerUser() {
        return wsShellPerUser;
    }

    public void setWsShellPerUser(int wsShellPerUser) {
        this.wsShellPerUser = wsShellPerUser;
    }

    public int getBotCommandPerUser() {
        return botCommandPerUser;
    }

    public void setBotCommandPerUser(int botCommandPerUser) {
        this.botCommandPerUser = botCommandPerUser;
    }

    public int getAiTaskPerUser() {
        return aiTaskPerUser;
    }

    public void setAiTaskPerUser(int aiTaskPerUser) {
        this.aiTaskPerUser = aiTaskPerUser;
    }

    public int getQqEventPerUser() {
        return qqEventPerUser;
    }

    public void setQqEventPerUser(int qqEventPerUser) {
        this.qqEventPerUser = qqEventPerUser;
    }

    public int getWechatEventPerUser() {
        return wechatEventPerUser;
    }

    public void setWechatEventPerUser(int wechatEventPerUser) {
        this.wechatEventPerUser = wechatEventPerUser;
    }

    public int getBotMessageRateLimit() {
        return botMessageRateLimit;
    }

    public void setBotMessageRateLimit(int botMessageRateLimit) {
        this.botMessageRateLimit = botMessageRateLimit;
    }

    public Duration getBotMessageRateWindow() {
        return botMessageRateWindow;
    }

    public void setBotMessageRateWindow(Duration botMessageRateWindow) {
        this.botMessageRateWindow = botMessageRateWindow;
    }

    public int getQqMessageRateLimit() {
        return qqMessageRateLimit;
    }

    public void setQqMessageRateLimit(int qqMessageRateLimit) {
        this.qqMessageRateLimit = qqMessageRateLimit;
    }

    public Duration getQqMessageRateWindow() {
        return qqMessageRateWindow;
    }

    public void setQqMessageRateWindow(Duration qqMessageRateWindow) {
        this.qqMessageRateWindow = qqMessageRateWindow;
    }

    public int getWechatMessageRateLimit() {
        return wechatMessageRateLimit;
    }

    public void setWechatMessageRateLimit(int wechatMessageRateLimit) {
        this.wechatMessageRateLimit = wechatMessageRateLimit;
    }

    public Duration getWechatMessageRateWindow() {
        return wechatMessageRateWindow;
    }

    public void setWechatMessageRateWindow(Duration wechatMessageRateWindow) {
        this.wechatMessageRateWindow = wechatMessageRateWindow;
    }

    public Duration getBotCommandTimeout() {
        return botCommandTimeout;
    }

    public void setBotCommandTimeout(Duration botCommandTimeout) {
        this.botCommandTimeout = botCommandTimeout;
    }

    public Duration getAiTaskTimeout() {
        return aiTaskTimeout;
    }

    public void setAiTaskTimeout(Duration aiTaskTimeout) {
        this.aiTaskTimeout = aiTaskTimeout;
    }

    /**
     * 有界线程池配置。
     */
    public static class ExecutorPool {
        /** 核心线程数。 */
        private int coreSize;
        /** 最大线程数。 */
        private int maxSize;
        /** 队列容量；0 表示不排队，直接移交工作线程。 */
        private int queueCapacity;

        public ExecutorPool() {
        }

        public ExecutorPool(int coreSize, int maxSize, int queueCapacity) {
            this.coreSize = coreSize;
            this.maxSize = maxSize;
            this.queueCapacity = queueCapacity;
        }

        public int getCoreSize() {
            return coreSize;
        }

        public void setCoreSize(int coreSize) {
            this.coreSize = coreSize;
        }

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }
}
