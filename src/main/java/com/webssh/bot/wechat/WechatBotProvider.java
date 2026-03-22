package com.webssh.bot.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webssh.bot.AiCliExecutor;
import com.webssh.bot.BotInteractionService;
import com.webssh.bot.BotSettings;
import com.webssh.bot.ChatBotProvider;
import com.webssh.config.ResourceGovernanceProperties;
import com.webssh.session.SshSessionProfile;
import com.webssh.task.BoundedExecutorFactory;
import com.webssh.task.UserResourceGovernor;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信 ClawBot (QClaw AGP 协议) Provider。
 * <p>
 * 通过 AGP (Agent Gateway Protocol) WebSocket 连接到腾讯 QClaw 平台，
 * 接收来自微信用户的消息并回复。协议参考自
 * <a href="https://github.com/photon-hq/qclaw-wechat-client">qclaw-wechat-client</a>。
 * </p>
 */
@Component
public class WechatBotProvider implements ChatBotProvider {

    private static final Logger log = LoggerFactory.getLogger(WechatBotProvider.class);
    private static final String TYPE = "wechat";

    // ===== AGP 协议常量 =====

    /** 生产环境 jprx 网关地址。 */
    static final String JPRX_GATEWAY = "https://jprx.m.qq.com/";
    /** 生产环境 AGP WebSocket 地址。 */
    private static final String AGP_WS_URL = "wss://mmgrcalltoken.3g.qq.com/agentwss";
    /** 刷新 channelToken 的 jprx 端点。 */
    private static final String ENDPOINT_REFRESH_TOKEN = "data/4058/forward";
    /** 默认登录密钥。 */
    static final String DEFAULT_LOGIN_KEY = "m83qdao0AmE5";
    /** jprx 请求版本号。 */
    static final String WEB_VERSION = "1.4.0";

    // ===== 连接管理常量 =====

    /** 心跳间隔（毫秒）。 */
    private static final long HEARTBEAT_INTERVAL_MS = 20_000L;
    /** 心跳超时 = 2 倍间隔。 */
    private static final long PONG_TIMEOUT_MS = HEARTBEAT_INTERVAL_MS * 2;
    /** 重连基础延迟（毫秒）。 */
    private static final long RECONNECT_BASE_MS = 3_000L;
    /** 重连延迟倍增因子。 */
    private static final double RECONNECT_MULTIPLIER = 1.5;
    /** 重连最大延迟（毫秒）。 */
    private static final long RECONNECT_MAX_MS = 25_000L;
    /** 去重窗口大小。 */
    private static final int MAX_RECENT_MSG_IDS = 1000;
    /** 去重清理周期（毫秒）。 */
    private static final long DEDUP_CLEANUP_INTERVAL_MS = 5 * 60 * 1000L;

    // ===== 消息常量 =====

    /** 单条消息最大长度（保守值，微信文本上限约 2048）。 */
    private static final int MAX_MESSAGE_LENGTH = 1800;
    /** AI 输出聚合后定时刷新间隔。 */
    private static final long AI_STREAM_FLUSH_INTERVAL_MS = 2_500L;
    /** AI 输出达到阈值后立即推送。 */
    private static final int AI_STREAM_BATCH_THRESHOLD = 700;

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern MARKDOWN_CODE_FENCE = Pattern.compile("^```.*$");
    private static final Pattern MARKDOWN_BOLD = Pattern.compile("\\*\\*([^*\\n]+)\\*\\*");
    private static final Pattern MARKDOWN_INLINE_CODE = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("!?\\[([^\\]]+)]\\(([^)\\n]+)\\)");
    private static final Pattern MARKDOWN_MULTI_HASH_HEADING = Pattern.compile("(?m)^#{2,6}\\s+\\S+");
    private static final Pattern EXTRA_BLANK_LINES = Pattern.compile("\\n{3,}");

    // ===== 依赖注入 =====

    private final BotInteractionService interactionService;
    private final ObjectMapper objectMapper;
    private final UserResourceGovernor resourceGovernor;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "wechat-bot-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService eventExecutor;
    private final Set<String> recentMsgIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedDeque<String> recentMsgIdOrder = new ConcurrentLinkedDeque<>();

    // ===== 运行状态 =====

    private volatile ProviderConfig currentConfig;
    private volatile boolean running = false;
    private volatile boolean stopRequested = false;
    private volatile String statusMessage = "未启动";
    private volatile WebSocket agpWebSocket;
    private volatile ScheduledFuture<?> heartbeatFuture;
    private volatile ScheduledFuture<?> reconnectFuture;
    private volatile ScheduledFuture<?> dedupCleanupFuture;
    private volatile Instant lastPongTime = Instant.now();
    private volatile int reconnectAttempt = 0;
    private volatile Instant connectedAt = Instant.EPOCH;

    /** Provider 启动配置快照。 */
    record ProviderConfig(String channelToken, String jwtToken, String guid, String userId,
            String sshUsername, Set<String> allowedUserIds) {
    }

    public WechatBotProvider(BotInteractionService interactionService, ObjectMapper objectMapper,
            ResourceGovernanceProperties resourceProperties, UserResourceGovernor resourceGovernor) {
        this.interactionService = interactionService;
        this.objectMapper = objectMapper;
        this.resourceGovernor = resourceGovernor;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.eventExecutor = BoundedExecutorFactory.newExecutor("wechat-bot-event-",
                resourceProperties.getWechatEvent());
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public String getDisplayName() {
        return "微信 ClawBot";
    }

    @Override
    public synchronized void start(BotSettings settings) throws Exception {
        stop();

        ProviderConfig config = parseSettings(settings);
        this.currentConfig = config;
        this.stopRequested = false;
        this.running = false;
        this.statusMessage = "启动中";
        this.reconnectAttempt = 0;
        this.connectedAt = Instant.EPOCH;

        try {
            connectAgp(config);
            // 启动去重清理定时任务
            dedupCleanupFuture = scheduler.scheduleAtFixedRate(this::cleanupDedupCache,
                    DEDUP_CLEANUP_INTERVAL_MS, DEDUP_CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            running = false;
            currentConfig = null;
            statusMessage = "启动失败: " + e.getMessage();
            throw e;
        }
    }

    @Override
    public synchronized void stop() {
        stopRequested = true;
        running = false;
        cancelHeartbeat();
        cancelReconnect();
        cancelDedupCleanup();
        closeWebSocket();
        currentConfig = null;
        connectedAt = Instant.EPOCH;
        interactionService.disconnectAll(TYPE);
        clearRecentMsgIds();
        statusMessage = "已停止";
    }

    @PreDestroy
    public void destroy() {
        stop();
        scheduler.shutdownNow();
        eventExecutor.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public String getStatusMessage() {
        return statusMessage;
    }

    // ===== 配置解析 =====

    static ProviderConfig parseSettings(BotSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("机器人配置不能为空");
        }
        Map<String, String> config = settings.getConfig() == null ? Map.of() : settings.getConfig();
        String channelToken = trimToNull(config.get("channelToken"));
        if (channelToken == null) {
            throw new IllegalArgumentException("Channel Token 不能为空");
        }
        String jwtToken = trimToNull(config.get("jwtToken"));
        String guid = trimToNull(config.get("guid"));
        if (guid == null) {
            guid = UUID.randomUUID().toString();
        }
        String userId = trimToNull(config.get("userId"));

        Set<String> allowedUserIds = new LinkedHashSet<>();
        if (settings.getAllowedUserIds() != null) {
            for (String value : settings.getAllowedUserIds()) {
                String normalized = trimToNull(value);
                if (normalized != null) {
                    allowedUserIds.add(normalized);
                }
            }
        }

        return new ProviderConfig(
                channelToken,
                jwtToken,
                guid,
                userId == null ? "1" : userId,
                trimToNull(settings.getSshUsername()) == null ? "admin" : settings.getSshUsername().trim(),
                allowedUserIds);
    }

    // ===== AGP WebSocket 连接 =====

    private void connectAgp(ProviderConfig config) throws Exception {
        closeWebSocket();
        statusMessage = "连接 AGP WebSocket 中";

        String wsUrl = AGP_WS_URL + "?token=" + config.channelToken();
        log.info("微信 ClawBot 连接 AGP WebSocket: {}", AGP_WS_URL);

        CompletableFuture<WebSocket> future = httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create(wsUrl), new AgpWebSocketListener());

        try {
            agpWebSocket = future.get(20, TimeUnit.SECONDS);
            running = true;
            reconnectAttempt = 0;
            connectedAt = Instant.now();
            lastPongTime = Instant.now();
            cancelReconnect();
            startHeartbeat();
            statusMessage = "运行中（AGP WebSocket 已连接）";
            log.info("微信 ClawBot AGP WebSocket 已连接");
        } catch (Exception e) {
            log.error("微信 ClawBot AGP WebSocket 连接失败: {}", e.getMessage(), e);
            throw new IllegalStateException("AGP WebSocket 连接失败: " + e.getMessage(), e);
        }
    }

    /** 启动心跳定时任务。 */
    private void startHeartbeat() {
        cancelHeartbeat();
        heartbeatFuture = scheduler.scheduleAtFixedRate(() -> {
            try {
                WebSocket ws = agpWebSocket;
                if (ws == null) {
                    return;
                }
                // 检查 pong 超时
                if (Duration.between(lastPongTime, Instant.now()).toMillis() > PONG_TIMEOUT_MS) {
                    log.warn("微信 ClawBot AGP pong 超时，触发重连");
                    scheduleReconnect("pong 超时");
                    return;
                }
                // 发送 ping
                ws.sendPing(ByteBuffer.allocate(0));
            } catch (Exception e) {
                log.debug("微信 ClawBot 心跳异常: {}", e.getMessage());
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** 计划一次延迟重连。 */
    private synchronized void scheduleReconnect(String reason) {
        if (stopRequested || currentConfig == null) {
            return;
        }
        running = false;
        cancelHeartbeat();
        statusMessage = "重连中: " + reason;
        if (reconnectFuture != null && !reconnectFuture.isDone()) {
            return;
        }

        reconnectAttempt++;
        long delay = Math.min(
                (long) (RECONNECT_BASE_MS * Math.pow(RECONNECT_MULTIPLIER, reconnectAttempt - 1)),
                RECONNECT_MAX_MS);

        log.info("微信 ClawBot 计划 {}ms 后重连（第 {} 次）: {}", delay, reconnectAttempt, reason);
        reconnectFuture = scheduler.schedule(() -> {
            try {
                if (!stopRequested && currentConfig != null) {
                    connectAgp(currentConfig);
                }
            } catch (Exception e) {
                log.error("微信 ClawBot 重连失败: {}", e.getMessage(), e);
                reconnectFuture = null;
                scheduleReconnect("连接失败");
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void cancelHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(true);
            heartbeatFuture = null;
        }
    }

    private void cancelReconnect() {
        if (reconnectFuture != null) {
            reconnectFuture.cancel(true);
            reconnectFuture = null;
        }
    }

    private void cancelDedupCleanup() {
        if (dedupCleanupFuture != null) {
            dedupCleanupFuture.cancel(true);
            dedupCleanupFuture = null;
        }
    }

    private void closeWebSocket() {
        WebSocket ws = agpWebSocket;
        agpWebSocket = null;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "client closing");
            } catch (Exception ignored) {
            }
        }
    }

    // ===== AGP 消息收发 =====

    /** 发送 AGP 信封消息。 */
    private void sendAgpMessage(String method, Object payload) {
        WebSocket ws = agpWebSocket;
        if (ws == null) {
            return;
        }
        try {
            ProviderConfig config = currentConfig;
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("msg_id", UUID.randomUUID().toString());
            envelope.put("guid", config != null ? config.guid() : "1");
            envelope.put("user_id", config != null ? config.userId() : "1");
            envelope.put("method", method);
            envelope.set("payload", objectMapper.valueToTree(payload));

            String json = objectMapper.writeValueAsString(envelope);
            ws.sendText(json, true);
        } catch (Exception e) {
            log.error("微信 ClawBot 发送 AGP 消息失败: {}", e.getMessage(), e);
        }
    }

    /** 发送流式文本分块。 */
    private void sendMessageChunk(String sessionId, String promptId, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session_id", sessionId);
        payload.put("prompt_id", promptId);
        payload.put("update_type", "message_chunk");
        payload.put("content", Map.of("type", "text", "text", text));
        sendAgpMessage("session.update", payload);
    }

    /** 发送最终响应（结束回合）。 */
    private void sendTextResponse(String sessionId, String promptId, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session_id", sessionId);
        payload.put("prompt_id", promptId);
        payload.put("stop_reason", "end_turn");
        payload.put("content", List.of(Map.of("type", "text", "text", text)));
        sendAgpMessage("session.promptResponse", payload);
    }

    /** 发送错误响应。 */
    private void sendErrorResponse(String sessionId, String promptId, String errorMessage) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session_id", sessionId);
        payload.put("prompt_id", promptId);
        payload.put("stop_reason", "error");
        payload.put("error", errorMessage);
        sendAgpMessage("session.promptResponse", payload);
    }

    // ===== AGP WebSocket 监听器 =====

    private class AgpWebSocketListener implements WebSocket.Listener {

        private final StringBuilder messageBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            log.info("微信 ClawBot AGP WebSocket onOpen");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messageBuffer.append(data);
            if (last) {
                String fullMessage = messageBuffer.toString();
                messageBuffer.setLength(0);
                handleAgpMessage(fullMessage);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            lastPongTime = Instant.now();
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            running = false;
            agpWebSocket = null;
            cancelHeartbeat();
            log.warn("微信 ClawBot AGP WebSocket 关闭: code={}, reason={}", statusCode, reason);
            if (!stopRequested) {
                scheduleReconnect("连接关闭(" + statusCode + ")");
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            running = false;
            agpWebSocket = null;
            cancelHeartbeat();
            log.error("微信 ClawBot AGP WebSocket 异常: {}", error.getMessage(), error);
            if (!stopRequested) {
                scheduleReconnect("连接异常");
            }
        }
    }

    /** 处理 AGP 消息总入口。 */
    private void handleAgpMessage(String rawMessage) {
        try {
            JsonNode root = objectMapper.readTree(rawMessage);
            String method = root.path("method").asText("");
            String msgId = root.path("msg_id").asText("");

            // 去重
            if (!msgId.isBlank() && isDuplicateMsg(msgId)) {
                return;
            }

            switch (method) {
                case "session.prompt" -> handleSessionPrompt(root);
                case "session.cancel" -> log.debug("微信 ClawBot 收到 session.cancel");
                case "ping" -> log.debug("微信 ClawBot 收到 ping");
                default -> log.debug("微信 ClawBot 忽略未知 method: {}", method);
            }
        } catch (Exception e) {
            log.error("微信 ClawBot 处理 AGP 消息失败: {}", e.getMessage(), e);
        }
    }

    /** 处理 session.prompt：接收用户消息并路由处理。 */
    private void handleSessionPrompt(JsonNode root) {
        JsonNode payload = root.path("payload");
        String sessionId = payload.path("session_id").asText("");
        String promptId = payload.path("prompt_id").asText("");

        // 提取文本内容
        StringBuilder textBuilder = new StringBuilder();
        JsonNode contentArray = payload.path("content");
        if (contentArray.isArray()) {
            for (JsonNode block : contentArray) {
                if ("text".equals(block.path("type").asText(""))) {
                    String text = block.path("text").asText("");
                    if (!text.isBlank()) {
                        if (textBuilder.length() > 0) {
                            textBuilder.append('\n');
                        }
                        textBuilder.append(text);
                    }
                }
            }
        }

        String text = textBuilder.toString().trim();
        if (text.isBlank()) {
            return;
        }

        ProviderConfig config = currentConfig;
        if (config == null) {
            return;
        }

        // 使用 sessionId 作为用户标识
        String userKey = TYPE + ":" + sessionId;
        if (!resourceGovernor.allowWechatMessage(userKey)) {
            log.warn("微信 ClawBot 消息触发限流 [{}]", userKey);
            return;
        }
        UserResourceGovernor.Permit permit = resourceGovernor.tryAcquireWechatEvent(userKey);
        if (!permit.granted()) {
            log.warn("微信 ClawBot 事件并发超限 [{}]", userKey);
            return;
        }

        IncomingMessage message = new IncomingMessage(sessionId, promptId, text);
        try {
            eventExecutor.submit(() -> {
                try (permit) {
                    processIncomingMessage(message, config);
                } catch (Throwable t) {
                    log.warn("微信 ClawBot 处理消息失败 [{}]: {}", userKey, t.getMessage(), t);
                }
            });
        } catch (RejectedExecutionException e) {
            permit.close();
            log.warn("微信 ClawBot 事件线程池繁忙，丢弃消息 [{}]", userKey);
        }
    }

    /** 入站消息抽象。 */
    record IncomingMessage(String sessionId, String promptId, String content) {
    }

    /** 解析后的命令结构。 */
    record CommandInput(String command, String argument) {
    }

    // ===== 消息处理 =====

    void processIncomingMessage(IncomingMessage message, ProviderConfig config) {
        if (message == null || config == null) {
            return;
        }

        // 白名单检查（使用 sessionId）
        if (!config.allowedUserIds().isEmpty()
                && !config.allowedUserIds().contains(message.sessionId())) {
            sendTextResponse(message.sessionId(), message.promptId(),
                    "⛔ 当前用户未被授权。\n会话 ID: " + message.sessionId());
            return;
        }

        String text = message.content().trim();
        if (text.isBlank()) {
            return;
        }

        String aliasCommand = normalizeAiControlAlias(text);
        if (aliasCommand != null) {
            handleCommand(message, config, parseCommandInput(aliasCommand));
            return;
        }

        if (text.startsWith("/")) {
            handleCommand(message, config, parseCommandInput(text));
        } else {
            handleUserInput(message, text);
        }
    }

    static CommandInput parseCommandInput(String text) {
        if (text == null || text.isBlank()) {
            return new CommandInput("", "");
        }
        String[] parts = text.trim().split("\\s+", 2);
        return new CommandInput(parts[0].toLowerCase(), parts.length > 1 ? parts[1].trim() : "");
    }

    /** 命令分发中心。 */
    private void handleCommand(IncomingMessage message, ProviderConfig config, CommandInput input) {
        switch (input.command()) {
            case "/start", "/help" ->
                    sendTextResponse(message.sessionId(), message.promptId(), buildHelpText());
            case "/list" ->
                    sendTextResponse(message.sessionId(), message.promptId(),
                            buildProfileListText(config.sshUsername()));
            case "/connect" -> handleConnect(message, config, input.argument());
            case "/disconnect" -> handleDisconnect(message);
            case "/status" -> handleStatus(message);
            case "/codex" ->
                    handleAiModeCommand(message, input.argument(), AiCliExecutor.CliType.CODEX);
            case "/codex_stop" -> handleAiStop(message, AiCliExecutor.CliType.CODEX);
            case "/codex_status" -> handleAiStatus(message, AiCliExecutor.CliType.CODEX);
            case "/codex_clear" -> handleAiClear(message, AiCliExecutor.CliType.CODEX);
            case "/claude" ->
                    handleAiModeCommand(message, input.argument(), AiCliExecutor.CliType.CLAUDE);
            case "/claude_stop" -> handleAiStop(message, AiCliExecutor.CliType.CLAUDE);
            case "/claude_status" -> handleAiStatus(message, AiCliExecutor.CliType.CLAUDE);
            case "/claude_clear" -> handleAiClear(message, AiCliExecutor.CliType.CLAUDE);
            default ->
                    sendTextResponse(message.sessionId(), message.promptId(),
                            "未知命令: " + input.command() + "\n使用 /help 查看可用命令。");
        }
    }

    private void handleConnect(IncomingMessage message, ProviderConfig config, String target) {
        if (target == null || target.isBlank()) {
            sendTextResponse(message.sessionId(), message.promptId(),
                    "用法: /connect <会话名称或序号>\n例如: /connect 1");
            return;
        }
        try {
            String result = interactionService.connect(TYPE, message.sessionId(),
                    config.sshUsername(), target);
            sendTextResponse(message.sessionId(), message.promptId(), result);
        } catch (Exception e) {
            sendTextResponse(message.sessionId(), message.promptId(),
                    "❌ 连接失败: " + e.getMessage());
        }
    }

    private void handleDisconnect(IncomingMessage message) {
        BotInteractionService.DisconnectResult result = interactionService.disconnect(TYPE,
                message.sessionId());
        if (!result.disconnected()) {
            sendTextResponse(message.sessionId(), message.promptId(), "当前没有活跃的 SSH 连接。");
            return;
        }
        sendTextResponse(message.sessionId(), message.promptId(),
                "🔌 已断开与 " + result.profileName() + " 的连接。");
    }

    private void handleStatus(IncomingMessage message) {
        BotInteractionService.ConnectionStatus status = interactionService.getConnectionStatus(TYPE,
                message.sessionId());
        AiCliExecutor.CliType aiMode = interactionService.getAiMode(TYPE, message.sessionId());

        StringBuilder sb = new StringBuilder();
        if (!status.connected()) {
            sb.append("📊 状态: 未连接\n使用 /list 查看可用会话，/connect 连接。");
            if (aiMode != null) {
                sb.append("\n🤖 AI 模式: ").append(aiMode.getDisplayName());
            }
        } else {
            sb.append("📊 状态: 已连接到 ").append(status.profileName());
            if (status.cwd() != null && !status.cwd().isBlank()) {
                sb.append("\n当前目录: ").append(status.cwd());
            }
            if (aiMode != null) {
                String cmdName = aiMode.name().toLowerCase();
                sb.append("\n🤖 AI 模式: ").append(aiMode.getDisplayName())
                        .append(" (使用 /").append(cmdName).append("_stop 或 /")
                        .append(cmdName).append("_clear 退出)");
            } else {
                sb.append("\n🤖 AI 模式: 未开启");
            }
            sb.append("\n未开启 AI 模式时，直接发送文字执行 Shell 命令。");
        }
        sendTextResponse(message.sessionId(), message.promptId(), sb.toString());
    }

    /** 普通输入路由：AI 模式下走 AI，否则走 Shell。 */
    private void handleUserInput(IncomingMessage message, String text) {
        AiCliExecutor.CliType aiMode = interactionService.getAiMode(TYPE, message.sessionId());
        if (aiMode != null) {
            startAiTask(message, text, aiMode);
            return;
        }
        handleShellInput(message, text);
    }

    private void handleShellInput(IncomingMessage message, String command) {
        BotInteractionService.ConnectionStatus status = interactionService.getConnectionStatus(TYPE,
                message.sessionId());
        if (!status.connected()) {
            sendTextResponse(message.sessionId(), message.promptId(),
                    "未连接 SSH。请先使用 /connect 连接。\n使用 /list 查看可用会话。");
            return;
        }

        interactionService.executeShellCommandAsync(TYPE, message.sessionId(), command)
                .whenComplete((output, error) -> {
                    if (error != null) {
                        sendTextResponse(message.sessionId(), message.promptId(),
                                "❌ " + error.getMessage());
                        return;
                    }
                    sendTextResponse(message.sessionId(), message.promptId(),
                            truncateForWechat(output));
                });
    }

    private void handleAiModeCommand(IncomingMessage message, String prompt,
            AiCliExecutor.CliType cliType) {
        interactionService.enterAiMode(TYPE, message.sessionId(), cliType);
        String cmdName = cliType.name().toLowerCase();
        if (prompt == null || prompt.isBlank()) {
            sendTextResponse(message.sessionId(), message.promptId(),
                    "🤖 已进入 " + cliType.getDisplayName() + " 模式。\n"
                            + "后续直接发送内容将按该模式执行。\n"
                            + "使用 /" + cmdName + "_stop 或 /" + cmdName + "_clear 退出。");
            return;
        }
        startAiTask(message, prompt, cliType);
    }

    /** 启动 AI 任务并使用缓冲发布器分段推送输出。 */
    private void startAiTask(IncomingMessage message, String prompt, AiCliExecutor.CliType cliType) {
        String cmdName = cliType.name().toLowerCase();
        if (prompt == null || prompt.isBlank()) {
            sendTextResponse(message.sessionId(), message.promptId(),
                    "用法: /" + cmdName + " <提示词>\n例如: /" + cmdName + " 分析当前项目结构");
            return;
        }
        if (interactionService.isAiTaskRunning(TYPE, message.sessionId(), cliType)) {
            sendTextResponse(message.sessionId(), message.promptId(),
                    "⚠️ 已有 " + cliType.getDisplayName() + " 任务在运行。\n使用 /"
                            + cmdName + "_stop 停止后再试。");
            return;
        }

        BufferedAiReplyPublisher publisher = new BufferedAiReplyPublisher(message, cliType);
        BotInteractionService.StartAiTaskResult result = interactionService.startAiTask(TYPE,
                message.sessionId(), prompt, cliType,
                publisher::append,
                () -> {
                    BotInteractionService.AiTaskSnapshot snapshot = interactionService
                            .getAiTaskSnapshot(TYPE, message.sessionId(), cliType);
                    publisher.finish(snapshot);
                });

        if (!result.started()) {
            sendTextResponse(message.sessionId(), message.promptId(), "❌ " + result.message());
            return;
        }

        StringBuilder sb = new StringBuilder("⏳ ").append(cliType.getDisplayName()).append(" 任务已启动");
        if (result.workDir() != null && !result.workDir().isBlank()) {
            sb.append("\n工作目录: ").append(result.workDir());
        }
        sb.append("\n输出会按分段自动推送，也可使用 /").append(cmdName).append("_status 查看最近输出。");
        // AI 任务启动提示通过流式 chunk 发送，不结束回合
        sendMessageChunk(message.sessionId(), message.promptId(), sb.toString());
    }

    private void handleAiStop(IncomingMessage message, AiCliExecutor.CliType cliType) {
        interactionService.exitAiMode(TYPE, message.sessionId());
        if (interactionService.stopAiTask(TYPE, message.sessionId(), cliType)) {
            sendTextResponse(message.sessionId(), message.promptId(),
                    "🛑 " + cliType.getDisplayName() + " 任务已停止，并已退出 AI 模式。");
        } else {
            sendTextResponse(message.sessionId(), message.promptId(),
                    "当前没有正在运行的 " + cliType.getDisplayName() + " 任务，已退出 AI 模式。");
        }
    }

    private void handleAiStatus(IncomingMessage message, AiCliExecutor.CliType cliType) {
        BotInteractionService.AiTaskSnapshot snapshot = interactionService.getAiTaskSnapshot(TYPE,
                message.sessionId(), cliType);
        sendTextResponse(message.sessionId(), message.promptId(),
                buildAiSummary(cliType, snapshot, true));
    }

    private void handleAiClear(IncomingMessage message, AiCliExecutor.CliType cliType) {
        interactionService.clearAiSession(TYPE, message.sessionId(), cliType);
        interactionService.exitAiMode(TYPE, message.sessionId());
        sendTextResponse(message.sessionId(), message.promptId(),
                "✨ " + cliType.getDisplayName() + " 的会话 ID 已清除，并已退出 AI 模式。");
    }

    // ===== 帮助文案与格式化 =====

    private String buildHelpText() {
        return """
                WebSSH 微信 ClawBot

                SSH 命令:
                /list - 查看已保存的 SSH 会话
                /connect <名称或序号> - 连接 SSH
                /disconnect - 断开当前连接
                /status - 查看 SSH 连接状态

                AI 编程命令:
                /codex [提示词] - 进入 Codex 模式
                /codex_stop - 停止 Codex 任务
                /codex_status - 查看 Codex 最近输出
                /codex_clear - 清除 Codex 会话 ID
                /claude [提示词] - 进入 Claude Code 模式
                /claude_stop - 停止 Claude Code 任务
                /claude_status - 查看 Claude Code 最近输出
                /claude_clear - 清除 Claude Code 会话 ID

                通过 QClaw AGP 协议与微信连接，无需配置回调地址。
                AI 模式下，后续普通输入会持续走对应 AI，直到 stop/clear 退出。
                未进入 AI 模式时，连接 SSH 后直接发送文字即可执行 Shell 命令。""";
    }

    private String normalizeAiControlAlias(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.trim().toLowerCase();
        return switch (normalized) {
            case "codex_stop", "codex_clear", "claude_stop", "claude_clear" -> "/" + normalized;
            default -> null;
        };
    }

    private String buildProfileListText(String sshUsername) {
        List<SshSessionProfile> profiles = interactionService.listProfiles(sshUsername);
        if (profiles.isEmpty()) {
            return "📋 没有已保存的 SSH 会话。\n请先在 WebSSH 页面中添加会话配置。";
        }

        StringBuilder sb = new StringBuilder("📋 已保存的 SSH 会话:\n\n");
        for (int i = 0; i < profiles.size(); i++) {
            SshSessionProfile profile = profiles.get(i);
            sb.append(i + 1).append(". ").append(profile.getName())
                    .append("  ").append(profile.getUsername())
                    .append("@").append(profile.getHost())
                    .append(":").append(profile.getPort())
                    .append(" [").append(profile.getAuthType()).append("]\n");
        }
        sb.append("\n使用 /connect <序号或名称> 连接。");
        return sb.toString();
    }

    private String buildAiSummary(AiCliExecutor.CliType cliType,
            BotInteractionService.AiTaskSnapshot snapshot, boolean includeIdleHint) {
        if (snapshot == null || (!snapshot.running() && !snapshot.hasOutput())) {
            if (!includeIdleHint) {
                return "";
            }
            String cmdName = cliType.name().toLowerCase();
            return "📊 " + cliType.getDisplayName() + " 状态: 空闲\n使用 /" + cmdName + " <提示词> 启动任务。";
        }

        StringBuilder sb = new StringBuilder("📊 ")
                .append(cliType.getDisplayName()).append(" 状态: ")
                .append(snapshot.running() ? "执行中" : "已结束");
        if (snapshot.workDir() != null && !snapshot.workDir().isBlank()) {
            sb.append("\n工作目录: ").append(snapshot.workDir());
        }
        if (snapshot.hasOutput()) {
            sb.append("\n\n最近输出:\n").append(truncateForWechat(snapshot.lastOutput()));
        } else if (snapshot.running()) {
            sb.append("\n暂无可展示输出。");
        }
        return sb.toString();
    }

    // ===== 消息格式化 =====

    /** 单条消息截断到微信可接受长度。 */
    static String truncateForWechat(String text) {
        String normalized = normalizeForWechat(text);
        if (normalized.isBlank()) {
            return "(无输出)";
        }
        if (normalized.length() <= MAX_MESSAGE_LENGTH) {
            return normalized;
        }
        int cut = normalized.lastIndexOf('\n', MAX_MESSAGE_LENGTH - 20);
        if (cut <= 0) {
            cut = MAX_MESSAGE_LENGTH - 20;
        }
        return normalized.substring(0, cut).trim() + "\n...(输出过长，已截断)";
    }

    /** 将长文本拆分为多条消息。 */
    private List<String> splitForWechat(String text) {
        String normalized = normalizeForWechat(text);
        if (normalized.isBlank()) {
            return List.of();
        }
        if (normalized.length() <= MAX_MESSAGE_LENGTH) {
            return List.of(normalized);
        }

        ArrayList<String> parts = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int remaining = normalized.length() - start;
            if (remaining <= MAX_MESSAGE_LENGTH) {
                parts.add(normalized.substring(start).trim());
                break;
            }
            int end = start + MAX_MESSAGE_LENGTH;
            int lineBreak = normalized.lastIndexOf('\n', end);
            if (lineBreak <= start + 80) {
                lineBreak = end;
            }
            parts.add(normalized.substring(start, lineBreak).trim());
            start = lineBreak;
            while (start < normalized.length() && normalized.charAt(start) == '\n') {
                start++;
            }
        }
        parts.removeIf(String::isBlank);
        return parts;
    }

    /** 将 Markdown 转为微信友好的纯文本。 */
    static String normalizeForWechat(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        boolean markdownRich = looksLikeMarkdown(text);
        StringBuilder sb = new StringBuilder();
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.stripTrailing();
            if (markdownRich && MARKDOWN_CODE_FENCE.matcher(line.trim()).matches()) {
                continue;
            }

            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                sb.append('\n');
                continue;
            }

            Matcher headingMatcher = MARKDOWN_HEADING.matcher(trimmed);
            if (markdownRich && headingMatcher.matches()) {
                sb.append("【").append(formatInlineMarkdown(headingMatcher.group(2).trim()))
                        .append("】").append('\n');
                continue;
            }

            sb.append(formatInlineMarkdown(trimmed)).append('\n');
        }

        return EXTRA_BLANK_LINES.matcher(sb.toString().trim()).replaceAll("\n\n");
    }

    private static boolean looksLikeMarkdown(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains("**") || text.contains("```")
                || text.contains("](") || MARKDOWN_MULTI_HASH_HEADING.matcher(text).find();
    }

    private static String formatInlineMarkdown(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String formatted = MARKDOWN_LINK.matcher(text)
                .replaceAll(match -> formatMarkdownLink(match.group(0), match.group(1),
                        match.group(2)));
        formatted = MARKDOWN_BOLD.matcher(formatted).replaceAll("【$1】");
        formatted = MARKDOWN_INLINE_CODE.matcher(formatted).replaceAll("$1");
        return formatted;
    }

    private static String formatMarkdownLink(String fullMatch, String label, String target) {
        if (fullMatch != null && fullMatch.startsWith("!")) {
            return "图片: " + label + " " + target;
        }
        return label + ": " + target;
    }

    // ===== 去重 =====

    private boolean isDuplicateMsg(String msgId) {
        if (msgId == null || msgId.isBlank()) {
            return false;
        }
        if (!recentMsgIds.add(msgId)) {
            return true;
        }
        recentMsgIdOrder.addLast(msgId);
        while (recentMsgIdOrder.size() > MAX_RECENT_MSG_IDS) {
            String removed = recentMsgIdOrder.pollFirst();
            if (removed != null) {
                recentMsgIds.remove(removed);
            }
        }
        return false;
    }

    private void clearRecentMsgIds() {
        recentMsgIds.clear();
        recentMsgIdOrder.clear();
    }

    private void cleanupDedupCache() {
        // 周期性清理，保留最近的一半
        int size = recentMsgIdOrder.size();
        if (size > MAX_RECENT_MSG_IDS / 2) {
            int toRemove = size - MAX_RECENT_MSG_IDS / 2;
            for (int i = 0; i < toRemove; i++) {
                String removed = recentMsgIdOrder.pollFirst();
                if (removed != null) {
                    recentMsgIds.remove(removed);
                }
            }
        }
    }

    // ===== AI 输出缓冲发布器 =====

    /**
     * AI 输出缓冲发布器。
     * <p>
     * 将高频碎片输出聚合后再发送，避免微信消息过于零散。
     * 通过 AGP session.update 流式推送分块，最终通过 session.promptResponse 结束回合。
     * </p>
     */
    private final class BufferedAiReplyPublisher {
        private final IncomingMessage message;
        private final AiCliExecutor.CliType cliType;
        private final StringBuilder pending = new StringBuilder();
        private ScheduledFuture<?> flushFuture;
        private boolean sentAnyChunk;
        private boolean finished;

        private BufferedAiReplyPublisher(IncomingMessage message, AiCliExecutor.CliType cliType) {
            this.message = message;
            this.cliType = cliType;
        }

        synchronized void append(String chunk) {
            if (finished || chunk == null || chunk.isBlank()) {
                return;
            }
            if (pending.length() > 0) {
                pending.append('\n');
            }
            pending.append(chunk.trim());

            if (pending.length() >= AI_STREAM_BATCH_THRESHOLD) {
                flushPending();
                cancelScheduledFlush();
            } else if (flushFuture == null || flushFuture.isDone()) {
                flushFuture = scheduler.schedule(() -> {
                    synchronized (BufferedAiReplyPublisher.this) {
                        flushPending();
                        flushFuture = null;
                    }
                }, AI_STREAM_FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
            }
        }

        synchronized void finish(BotInteractionService.AiTaskSnapshot snapshot) {
            if (finished) {
                return;
            }
            finished = true;
            cancelScheduledFlush();
            flushPending();

            // 发送最终响应结束回合
            StringBuilder endText = new StringBuilder();
            if (!sentAnyChunk && snapshot != null && snapshot.hasOutput()) {
                String summary = buildAiSummary(cliType, snapshot, false);
                if (!summary.isBlank()) {
                    endText.append(summary).append('\n');
                }
            }
            endText.append("✅ ").append(cliType.getDisplayName()).append(" 任务已结束。");
            sendTextResponse(message.sessionId(), message.promptId(), endText.toString());
        }

        private void cancelScheduledFlush() {
            if (flushFuture != null) {
                flushFuture.cancel(false);
                flushFuture = null;
            }
        }

        private void flushPending() {
            String text = pending.toString().trim();
            pending.setLength(0);
            if (text.isBlank()) {
                return;
            }
            for (String part : splitForWechat(text)) {
                sendMessageChunk(message.sessionId(), message.promptId(), part);
                sentAnyChunk = true;
            }
        }
    }

    // ===== Token 刷新（可选） =====

    /**
     * 刷新 channelToken。
     * <p>
     * 若当前持有 jwtToken，可调用 jprx 端点刷新 channelToken。
     * 当 WebSocket 连接因 token 过期断开时自动触发。
     * </p>
     */
    String refreshChannelToken(ProviderConfig config) {
        if (config == null || config.jwtToken() == null) {
            return null;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("web_version", WEB_VERSION);
            body.put("web_env", "release");

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("X-Version", "1");
            headers.put("X-Token", config.guid() != null ? config.guid() : DEFAULT_LOGIN_KEY);
            headers.put("X-Guid", config.guid() != null ? config.guid() : "1");
            headers.put("X-Account", config.userId() != null ? config.userId() : "1");
            headers.put("X-Session", "");
            headers.put("X-OpenClaw-Token", config.jwtToken());

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(
                    URI.create(JPRX_GATEWAY + ENDPOINT_REFRESH_TOKEN))
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body)));
            headers.forEach(reqBuilder::header);

            HttpResponse<String> response = httpClient.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JsonNode json = objectMapper.readTree(response.body());
                // 解包 Tencent 嵌套响应
                JsonNode data = unwrapData(json);
                if (data != null && data.has("openclaw_channel_token")) {
                    return data.path("openclaw_channel_token").asText(null);
                }
            }
        } catch (Exception e) {
            log.error("微信 ClawBot 刷新 channelToken 失败: {}", e.getMessage(), e);
        }
        return null;
    }

    /** 解包 Tencent 嵌套响应格式。 */
    static JsonNode unwrapData(JsonNode root) {
        JsonNode d = root;
        for (int i = 0; i < 4; i++) {
            if (d.has("resp") && d.path("resp").has("data")) {
                d = d.path("resp").path("data");
            } else if (d.has("data") && d.path("data").isObject()) {
                d = d.path("data");
            } else {
                break;
            }
        }
        return d;
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
