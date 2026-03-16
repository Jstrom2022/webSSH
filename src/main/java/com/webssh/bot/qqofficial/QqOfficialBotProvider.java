package com.webssh.bot.qqofficial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webssh.bot.AiCliExecutor;
import com.webssh.bot.BotInteractionService;
import com.webssh.bot.BotSettings;
import com.webssh.bot.ChatBotProvider;
import com.webssh.session.SshSessionProfile;
import jakarta.annotation.PreDestroy;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.CloseReason;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QQ 私聊机器人 Provider。
 * <p>
 * 按 OpenClaw qqbot 插件的接入方式实现：
 * access_token + /gateway + WebSocket，处理 C2C_MESSAGE_CREATE 事件。
 * </p>
 */
@Component
public class QqOfficialBotProvider implements ChatBotProvider {

    private static final Logger log = LoggerFactory.getLogger(QqOfficialBotProvider.class);
    private static final String TYPE = "qq-official";
    private static final String TOKEN_DOMAIN = "https://bots.qq.com";
    private static final String API_DOMAIN = "https://api.sgroup.qq.com";
    private static final int C2C_INTENT = 1 << 25;
    private static final long DEFAULT_RECONNECT_DELAY_MS = 3_000L;
    private static final int MAX_RECENT_EVENT_IDS = 256;
    private static final int MAX_PRIVATE_MESSAGE_LENGTH = 1500;
    private static final long AI_STREAM_FLUSH_INTERVAL_MS = 2_500L;
    private static final int AI_STREAM_BATCH_THRESHOLD = 700;
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern MARKDOWN_BULLET = Pattern.compile("^\\s*[-*+]\\s+");
    private static final Pattern MARKDOWN_QUOTE = Pattern.compile("^>\\s*");
    private static final Pattern MARKDOWN_CODE_FENCE = Pattern.compile("^```.*$");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("!?\\[([^\\]]+)]\\(([^)\\n]+)\\)");
    private static final Pattern MARKDOWN_BOLD = Pattern.compile("\\*\\*([^*\\n]+)\\*\\*");
    private static final Pattern MARKDOWN_INLINE_CODE = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern MARKDOWN_MULTI_HASH_HEADING = Pattern.compile("(?m)^#{2,6}\\s+\\S+");
    private static final Pattern EXTRA_BLANK_LINES = Pattern.compile("\\n{3,}");

    private final BotInteractionService interactionService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final WebSocketContainer webSocketContainer;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "qq-bot-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService eventExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "qq-bot-event");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> recentEventIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedDeque<String> recentEventOrder = new ConcurrentLinkedDeque<>();

    private volatile ProviderConfig currentConfig;
    private volatile boolean running = false;
    private volatile boolean stopRequested = false;
    private volatile String statusMessage = "未启动";
    private volatile Session gatewaySession;
    private volatile ScheduledFuture<?> heartbeatFuture;
    private volatile ScheduledFuture<?> reconnectFuture;
    private volatile long lastSeq = 0L;
    private volatile String sessionId;
    private volatile String accessToken;
    private volatile Instant accessTokenExpiry = Instant.EPOCH;
    private volatile String gatewayStage = "未连接";
    private volatile Instant gatewayConnectedAt = Instant.EPOCH;

    record ProviderConfig(String appId, String appSecret, String sshUsername, Set<String> allowedUserIds) {
    }

    record AccessTokenInfo(String accessToken, long expiresInSeconds) {
    }

    record GatewayInfo(String url) {
    }

    record IncomingC2cMessage(String eventId, String messageId, String userOpenId, String content) {
    }

    record CommandInput(String command, String argument) {
    }

    public QqOfficialBotProvider(BotInteractionService interactionService, ObjectMapper objectMapper) {
        this.interactionService = interactionService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.webSocketContainer = ContainerProvider.getWebSocketContainer();
        this.webSocketContainer.setDefaultMaxSessionIdleTimeout(0L);
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public String getDisplayName() {
        return "QQ 私聊机器人";
    }

    @Override
    public synchronized void start(BotSettings settings) throws Exception {
        stop();

        ProviderConfig config = parseSettings(settings);
        this.currentConfig = config;
        this.stopRequested = false;
        this.running = false;
        this.statusMessage = "启动中";
        this.lastSeq = 0L;
        this.sessionId = null;
        this.gatewayStage = "启动中";
        this.gatewayConnectedAt = Instant.EPOCH;

        try {
            ensureAccessToken(config, true);
            connectGateway(config);
        } catch (Exception e) {
            running = false;
            currentConfig = null;
            accessToken = null;
            accessTokenExpiry = Instant.EPOCH;
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
        closeWebSocket();
        currentConfig = null;
        sessionId = null;
        lastSeq = 0L;
        accessToken = null;
        accessTokenExpiry = Instant.EPOCH;
        gatewayStage = "已停止";
        gatewayConnectedAt = Instant.EPOCH;
        interactionService.disconnectAll(TYPE);
        clearRecentEvents();
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

    static ProviderConfig parseSettings(BotSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("机器人配置不能为空");
        }
        Map<String, String> config = settings.getConfig() == null ? Map.of() : settings.getConfig();
        String appId = trimToNull(config.get("appId"));
        String appSecret = trimToNull(config.get("appSecret"));
        if (appId == null) {
            throw new IllegalArgumentException("QQ App ID 不能为空");
        }
        if (appSecret == null) {
            throw new IllegalArgumentException("QQ App Secret 不能为空");
        }

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
                appId,
                appSecret,
                trimToNull(settings.getSshUsername()) == null ? "admin" : settings.getSshUsername().trim(),
                allowedUserIds);
    }

    static String normalizeIncomingContent(String rawContent) {
        if (rawContent == null) {
            return "";
        }
        return rawContent.replace('\u2005', ' ').trim();
    }

    static boolean isUserAllowed(Set<String> allowedUserIds, String userOpenId) {
        if (allowedUserIds == null || allowedUserIds.isEmpty()) {
            return true;
        }
        return userOpenId != null && allowedUserIds.contains(userOpenId);
    }

    static CommandInput parseCommandInput(String text) {
        if (text == null || text.isBlank()) {
            return new CommandInput("", "");
        }
        String[] parts = text.trim().split("\\s+", 2);
        return new CommandInput(parts[0].toLowerCase(), parts.length > 1 ? parts[1].trim() : "");
    }

    void processIncomingMessage(IncomingC2cMessage message, ProviderConfig config, ReplySender sender) {
        if (message == null || config == null) {
            return;
        }
        if (!isUserAllowed(config.allowedUserIds(), message.userOpenId())) {
            sender.passiveReply(message, "⛔ 当前 QQ 用户未被授权。\n用户 OpenID: " + safe(message.userOpenId()));
            return;
        }

        String text = normalizeIncomingContent(message.content());
        if (text.isBlank()) {
            return;
        }

        if (text.startsWith("/")) {
            handleCommand(message, config, sender, parseCommandInput(text));
        } else {
            handleShellInput(message, sender, text);
        }
    }

    private void handleCommand(IncomingC2cMessage message, ProviderConfig config, ReplySender sender,
            CommandInput input) {
        switch (input.command()) {
            case "/start", "/help" -> sender.passiveReply(message, buildHelpText());
            case "/list" -> sender.passiveReply(message, buildProfileListText(config.sshUsername()));
            case "/connect" -> handleConnect(message, config, sender, input.argument());
            case "/disconnect" -> handleDisconnect(message, sender);
            case "/status" -> handleStatus(message, sender);
            case "/codex" -> handleAiStart(message, sender, input.argument(), AiCliExecutor.CliType.CODEX);
            case "/codex_stop" -> handleAiStop(message, sender, AiCliExecutor.CliType.CODEX);
            case "/codex_status" -> handleAiStatus(message, sender, AiCliExecutor.CliType.CODEX);
            case "/codex_clear" -> handleAiClear(message, sender, AiCliExecutor.CliType.CODEX);
            case "/claude" -> handleAiStart(message, sender, input.argument(), AiCliExecutor.CliType.CLAUDE);
            case "/claude_stop" -> handleAiStop(message, sender, AiCliExecutor.CliType.CLAUDE);
            case "/claude_status" -> handleAiStatus(message, sender, AiCliExecutor.CliType.CLAUDE);
            case "/claude_clear" -> handleAiClear(message, sender, AiCliExecutor.CliType.CLAUDE);
            default -> sender.passiveReply(message, "未知命令: " + input.command() + "\n使用 /help 查看可用命令。");
        }
    }

    private void handleConnect(IncomingC2cMessage message, ProviderConfig config, ReplySender sender, String target) {
        if (target == null || target.isBlank()) {
            sender.passiveReply(message, "用法: /connect <会话名称或序号>\n例如: /connect 1");
            return;
        }

        try {
            sender.passiveReply(message,
                    interactionService.connect(TYPE, message.userOpenId(), config.sshUsername(), target));
        } catch (Exception e) {
            sender.passiveReply(message, "❌ 连接失败: " + e.getMessage());
        }
    }

    private void handleDisconnect(IncomingC2cMessage message, ReplySender sender) {
        BotInteractionService.DisconnectResult result = interactionService.disconnect(TYPE, message.userOpenId());
        if (!result.disconnected()) {
            sender.passiveReply(message, "当前没有活跃的 SSH 连接。");
            return;
        }
        sender.passiveReply(message, "🔌 已断开与 " + result.profileName() + " 的连接。");
    }

    private void handleStatus(IncomingC2cMessage message, ReplySender sender) {
        BotInteractionService.ConnectionStatus status = interactionService.getConnectionStatus(TYPE, message.userOpenId());
        if (!status.connected()) {
            sender.passiveReply(message, "📊 状态: 未连接\n使用 /list 查看可用会话，/connect 连接。");
            return;
        }

        StringBuilder sb = new StringBuilder("📊 状态: 已连接到 ").append(status.profileName());
        if (status.cwd() != null && !status.cwd().isBlank()) {
            sb.append("\n当前目录: ").append(status.cwd());
        }
        sb.append("\n直接发送文字即可执行 Shell 命令。");
        sender.passiveReply(message, sb.toString());
    }

    private void handleShellInput(IncomingC2cMessage message, ReplySender sender, String command) {
        BotInteractionService.ConnectionStatus status = interactionService.getConnectionStatus(TYPE,
                message.userOpenId());
        if (!status.connected()) {
            sender.passiveReply(message, "未连接 SSH。请先使用 /connect 连接。\n使用 /list 查看可用会话。");
            return;
        }

        interactionService.executeShellCommandAsync(TYPE, message.userOpenId(), command)
                .whenComplete((output, error) -> {
                    if (error != null) {
                        sender.passiveReply(message, "❌ " + error.getMessage());
                        return;
                    }
                    sender.passiveReply(message, wrapQqCodeBlock(output));
                });
    }

    private void handleAiStart(IncomingC2cMessage message, ReplySender sender, String prompt,
            AiCliExecutor.CliType cliType) {
        String cmdName = cliType.name().toLowerCase();
        if (prompt == null || prompt.isBlank()) {
            sender.passiveReply(message, "用法: /" + cmdName + " <提示词>\n例如: /" + cmdName + " 分析当前项目结构");
            return;
        }
        if (interactionService.isAiTaskRunning(TYPE, message.userOpenId(), cliType)) {
            sender.passiveReply(message, "⚠️ 已有 " + cliType.getDisplayName() + " 任务在运行。\n使用 /" + cmdName + "_stop 停止后再试。");
            return;
        }

        BufferedAiReplyPublisher publisher = new BufferedAiReplyPublisher(message, sender, cliType);
        BotInteractionService.StartAiTaskResult result = interactionService.startAiTask(TYPE, message.userOpenId(),
                prompt,
                cliType,
                publisher::append,
                () -> {
                    BotInteractionService.AiTaskSnapshot snapshot = interactionService.getAiTaskSnapshot(TYPE,
                            message.userOpenId(), cliType);
                    publisher.finish(snapshot);
                });

        if (!result.started()) {
            sender.passiveReply(message, "❌ " + result.message());
            return;
        }

        StringBuilder sb = new StringBuilder("⏳ ").append(cliType.getDisplayName()).append(" 任务已启动");
        if (result.workDir() != null && !result.workDir().isBlank()) {
            sb.append("\n工作目录: ").append(result.workDir());
        }
        sb.append("\n输出会按分段自动推送，也可使用 /").append(cmdName).append("_status 查看最近输出。");
        sender.passiveReply(message, sb.toString());
    }

    private void handleAiStop(IncomingC2cMessage message, ReplySender sender, AiCliExecutor.CliType cliType) {
        if (interactionService.stopAiTask(TYPE, message.userOpenId(), cliType)) {
            sender.passiveReply(message, "🛑 " + cliType.getDisplayName() + " 任务已停止。");
        } else {
            sender.passiveReply(message, "当前没有正在运行的 " + cliType.getDisplayName() + " 任务。");
        }
    }

    private void handleAiStatus(IncomingC2cMessage message, ReplySender sender, AiCliExecutor.CliType cliType) {
        BotInteractionService.AiTaskSnapshot snapshot = interactionService.getAiTaskSnapshot(TYPE, message.userOpenId(),
                cliType);
        sender.passiveReply(message, buildAiSummary(cliType, snapshot, true));
    }

    private void handleAiClear(IncomingC2cMessage message, ReplySender sender, AiCliExecutor.CliType cliType) {
        interactionService.clearAiSession(TYPE, message.userOpenId(), cliType);
        sender.passiveReply(message, "✨ " + cliType.getDisplayName() + " 的会话 ID 已清除。");
    }

    private String buildHelpText() {
        return """
                WebSSH QQ 私聊机器人

                SSH 命令:
                /list - 查看已保存的 SSH 会话
                /connect <名称或序号> - 连接 SSH
                /disconnect - 断开当前连接
                /status - 查看 SSH 连接状态

                AI 编程命令:
                /codex <提示词> - 启动 Codex 任务
                /codex_stop - 停止 Codex 任务
                /codex_status - 查看 Codex 最近输出
                /codex_clear - 清除 Codex 会话 ID
                /claude <提示词> - 启动 Claude Code 任务
                /claude_stop - 停止 Claude Code 任务
                /claude_status - 查看 Claude Code 最近输出
                /claude_clear - 清除 Claude Code 会话 ID

                使用 AppID + AppSecret 直连 QQ Gateway，无需配置回调地址。
                连接 SSH 后，直接发送文字即可执行 Shell 命令。""";
    }

    private String buildProfileListText(String sshUsername) {
        List<SshSessionProfile> profiles = interactionService.listProfiles(sshUsername);
        if (profiles.isEmpty()) {
            return "📋 没有已保存的 SSH 会话。\n请先在 WebSSH 页面中添加会话配置。";
        }

        StringBuilder sb = new StringBuilder("📋 已保存的 SSH 会话:\n\n");
        for (int i = 0; i < profiles.size(); i++) {
            SshSessionProfile profile = profiles.get(i);
            sb.append(i + 1)
                    .append(". ")
                    .append(profile.getName())
                    .append("  ")
                    .append(profile.getUsername())
                    .append("@")
                    .append(profile.getHost())
                    .append(":")
                    .append(profile.getPort())
                    .append(" [")
                    .append(profile.getAuthType())
                    .append("]\n");
        }
        sb.append("\n使用 /connect <序号或名称> 连接。");
        return sb.toString();
    }

    private String buildAiSummary(AiCliExecutor.CliType cliType, BotInteractionService.AiTaskSnapshot snapshot,
            boolean includeIdleHint) {
        if (snapshot == null || (!snapshot.running() && !snapshot.hasOutput())) {
            if (!includeIdleHint) {
                return "";
            }
            String cmdName = cliType.name().toLowerCase();
            return "📊 " + cliType.getDisplayName() + " 状态: 空闲\n使用 /" + cmdName + " <提示词> 启动任务。";
        }

        StringBuilder sb = new StringBuilder("📊 ")
                .append(cliType.getDisplayName())
                .append(" 状态: ")
                .append(snapshot.running() ? "执行中" : "已结束");
        if (snapshot.workDir() != null && !snapshot.workDir().isBlank()) {
            sb.append("\n工作目录: ").append(snapshot.workDir());
        }
        if (snapshot.hasOutput()) {
            sb.append("\n\n最近输出:\n").append(truncateForQq(snapshot.lastOutput()));
        } else if (snapshot.running()) {
            sb.append("\n暂无可展示输出。");
        }
        return sb.toString();
    }

    private void connectGateway(ProviderConfig config) throws Exception {
        GatewayInfo gatewayInfo = fetchGatewayInfo(config);
        closeWebSocket();
        statusMessage = "连接 QQ Gateway 中";
        gatewayStage = "连接中";
        ClientEndpointConfig endpointConfig = ClientEndpointConfig.Builder.create().build();
        gatewaySession = webSocketContainer.connectToServer(new GatewayEndpoint(), endpointConfig,
                URI.create(gatewayInfo.url()));
    }

    private synchronized void scheduleReconnect(String reason) {
        if (stopRequested || currentConfig == null) {
            return;
        }
        running = false;
        cancelHeartbeat();
        gatewayStage = "等待重连";
        statusMessage = "重连中: " + reason;
        if (reconnectFuture != null && !reconnectFuture.isDone()) {
            return;
        }

        reconnectFuture = scheduler.schedule(() -> {
            try {
                if (!stopRequested && currentConfig != null) {
                    connectGateway(currentConfig);
                }
            } catch (Exception e) {
                log.error("QQ Gateway 重连失败: {}", e.getMessage(), e);
                reconnectFuture = null;
                scheduleReconnect("连接失败");
            }
        }, DEFAULT_RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void cancelReconnect() {
        if (reconnectFuture != null) {
            reconnectFuture.cancel(true);
            reconnectFuture = null;
        }
    }

    private void cancelHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(true);
            heartbeatFuture = null;
        }
    }

    private void closeWebSocket() {
        Session session = this.gatewaySession;
        this.gatewaySession = null;
        if (session != null) {
            try {
                if (session.isOpen()) {
                    session.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "client closing"));
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void sendIdentify() throws Exception {
        ProviderConfig config = currentConfig;
        if (config == null) {
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", "QQBot " + ensureAccessToken(config, false));
        data.put("intents", C2C_INTENT);
        data.put("shard", List.of(0, 1));

        gatewayStage = "Identify 中";
        statusMessage = "已连接 QQ Gateway，正在鉴权";
        log.info("QQ Gateway 发送 Identify: intents={}", C2C_INTENT);
        sendGatewayPayload(Map.of("op", 2, "d", data));
    }

    private void sendHeartbeat() {
        sendGatewayPayload(Map.of("op", 1, "d", lastSeq <= 0 ? null : lastSeq));
    }

    private void sendGatewayPayload(Object payload) {
        try {
            Session session = this.gatewaySession;
            if (session == null || !session.isOpen()) {
                return;
            }
            String json = objectMapper.writeValueAsString(payload);
            session.getAsyncRemote().sendText(json, result -> {
                if (!result.isOK()) {
                    Throwable error = result.getException();
                    log.error("发送 QQ Gateway 数据失败: {}",
                            error == null ? "未知错误" : error.getMessage(),
                            error);
                }
            });
        } catch (Exception e) {
            log.error("发送 QQ Gateway 数据失败: {}", e.getMessage(), e);
        }
    }

    private void handleGatewayMessage(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            if (root.hasNonNull("s")) {
                lastSeq = root.path("s").asLong(lastSeq);
            }

            int op = root.path("op").asInt(-1);
            switch (op) {
                case 10 -> handleHello(root.path("d"));
                case 11 -> {
                }
                case 0 -> handleDispatch(root);
                case 7 -> scheduleReconnect("服务端要求重连");
                case 9 -> scheduleReconnect("会话失效");
                default -> log.debug("忽略 QQ Gateway op={}", op);
            }
        } catch (Exception e) {
            log.error("处理 QQ Gateway 消息失败: {}", e.getMessage(), e);
        }
    }

    private void handleHello(JsonNode data) throws Exception {
        long intervalMs = data.path("heartbeat_interval").asLong(45_000L);
        cancelHeartbeat();
        gatewayStage = "已收到 HELLO";
        statusMessage = "已连接 QQ Gateway，等待 READY";
        log.info("QQ Gateway HELLO: heartbeat={}ms", intervalMs);
        heartbeatFuture = scheduler.scheduleAtFixedRate(this::sendHeartbeat, intervalMs, intervalMs,
                TimeUnit.MILLISECONDS);
        sendIdentify();
    }

    private void handleDispatch(JsonNode root) {
        String type = root.path("t").asText("");
        if ("READY".equals(type)) {
            sessionId = root.path("d").path("session_id").asText("");
            running = true;
            cancelReconnect();
            gatewayStage = "READY";
            statusMessage = "运行中（QQ Gateway 已连接）";
            log.info("QQ Gateway READY: sessionId={}", sessionId);
            return;
        }

        if (!"C2C_MESSAGE_CREATE".equals(type)) {
            return;
        }

        IncomingC2cMessage event = parseIncomingC2cMessage(root.path("id").asText(""), root.path("d"));
        if (event == null || isDuplicateEvent(event)) {
            return;
        }

        ProviderConfig config = currentConfig;
        if (config == null) {
            return;
        }

        eventExecutor.submit(() -> processIncomingMessage(event, config, new HttpReplySender()));
    }

    IncomingC2cMessage parseIncomingC2cMessage(String eventId, JsonNode data) {
        if (data == null || data.isMissingNode()) {
            return null;
        }
        String messageId = trimToNull(data.path("id").asText(""));
        JsonNode author = data.path("author");
        String userOpenId = trimToNull(author.path("user_openid").asText(""));
        if (userOpenId == null) {
            userOpenId = trimToNull(author.path("id").asText(""));
        }
        String content = data.path("content").asText("");
        if (messageId == null || userOpenId == null) {
            return null;
        }
        return new IncomingC2cMessage(
                trimToNull(eventId) == null ? messageId : eventId,
                messageId,
                userOpenId,
                content);
    }

    private boolean isDuplicateEvent(IncomingC2cMessage event) {
        String key = event.eventId();
        if (key == null || key.isBlank()) {
            return false;
        }
        if (!recentEventIds.add(key)) {
            return true;
        }
        recentEventOrder.addLast(key);
        while (recentEventOrder.size() > MAX_RECENT_EVENT_IDS) {
            String removed = recentEventOrder.pollFirst();
            if (removed != null) {
                recentEventIds.remove(removed);
            }
        }
        return false;
    }

    private void clearRecentEvents() {
        recentEventIds.clear();
        recentEventOrder.clear();
    }

    private String ensureAccessToken(ProviderConfig config, boolean forceRefresh) throws Exception {
        if (!forceRefresh && accessToken != null && Instant.now().isBefore(accessTokenExpiry.minusSeconds(30))) {
            return accessToken;
        }

        AccessTokenInfo tokenInfo = fetchAccessToken(config.appId(), config.appSecret());
        accessToken = tokenInfo.accessToken();
        accessTokenExpiry = Instant.now().plusSeconds(Math.max(60, tokenInfo.expiresInSeconds()));
        return accessToken;
    }

    private AccessTokenInfo fetchAccessToken(String appId, String appSecret) throws Exception {
        Map<String, String> body = Map.of(
                "appId", appId,
                "clientSecret", appSecret);
        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_DOMAIN + "/app/getAppAccessToken"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return parseAccessTokenResponse(response.statusCode(), response.body());
    }

    static AccessTokenInfo parseAccessTokenResponse(int statusCode, String responseBody) throws Exception {
        JsonNode json = new ObjectMapper().readTree(responseBody);
        String token = trimToNull(json.path("access_token").asText(""));
        long expiresIn = json.path("expires_in").asLong(0L);

        if (statusCode >= 200 && statusCode < 300 && token != null) {
            return new AccessTokenInfo(token, expiresIn);
        }

        String message = trimToNull(json.path("message").asText(""));
        if (message == null) {
            message = responseBody;
        }
        throw new IllegalStateException("获取 QQ access_token 失败: " + message);
    }

    private GatewayInfo fetchGatewayInfo(ProviderConfig config) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_DOMAIN + "/gateway"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "QQBot " + ensureAccessToken(config, false))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return parseGatewayResponse(response.statusCode(), response.body());
    }

    static GatewayInfo parseGatewayResponse(int statusCode, String responseBody) throws Exception {
        JsonNode json = new ObjectMapper().readTree(responseBody);
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException("获取 QQ Gateway 失败: " + responseBody);
        }
        String url = trimToNull(json.path("url").asText(""));
        if (url == null) {
            throw new IllegalStateException("QQ Gateway URL 为空");
        }
        return new GatewayInfo(url);
    }

    private void sendC2cMessage(String userOpenId, String content, String msgId, boolean active) throws Exception {
        ProviderConfig config = currentConfig;
        if (config == null) {
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", truncateForQq(content));
        body.put("msg_type", 0);
        body.put("msg_seq", msgId == null || msgId.isBlank() ? 1 : nextMsgSeq(msgId));
        if (!active && msgId != null && !msgId.isBlank()) {
            body.put("msg_id", msgId);
        }

        HttpRequest request = HttpRequest.newBuilder(
                URI.create(API_DOMAIN + "/v2/users/" + userOpenId + "/messages"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "QQBot " + ensureAccessToken(config, false))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("发送 QQ 私聊消息失败: " + response.body());
        }
    }

    private int nextMsgSeq(String messageId) {
        int mixed = (int) (System.currentTimeMillis() ^ messageId.hashCode() ^ ThreadLocalRandom.current().nextInt());
        return Math.floorMod(mixed, 65_536);
    }

    static String truncateForQq(String text) {
        String normalized = normalizeForQq(text);
        if (normalized.isBlank()) {
            return "(无输出)";
        }
        if (normalized.length() <= MAX_PRIVATE_MESSAGE_LENGTH) {
            return normalized;
        }
        int cut = normalized.lastIndexOf('\n', MAX_PRIVATE_MESSAGE_LENGTH - 20);
        if (cut <= 0) {
            cut = MAX_PRIVATE_MESSAGE_LENGTH - 20;
        }
        return normalized.substring(0, cut).trim() + "\n...(输出过长，已截断)";
    }

    private String wrapQqCodeBlock(String text) {
        return truncateForQq(text);
    }

    private List<String> splitForQq(String text) {
        String normalized = normalizeForQq(text);
        if (normalized.isBlank()) {
            return List.of();
        }
        if (normalized.length() <= MAX_PRIVATE_MESSAGE_LENGTH) {
            return List.of(normalized);
        }

        ArrayList<String> parts = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int remaining = normalized.length() - start;
            if (remaining <= MAX_PRIVATE_MESSAGE_LENGTH) {
                parts.add(normalized.substring(start).trim());
                break;
            }

            int end = start + MAX_PRIVATE_MESSAGE_LENGTH;
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

    static String normalizeForQq(String text) {
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
                sb.append("【").append(formatInlineMarkdown(headingMatcher.group(2).trim())).append("】").append('\n');
                continue;
            }

            if (markdownRich && MARKDOWN_QUOTE.matcher(trimmed).find()) {
                trimmed = MARKDOWN_QUOTE.matcher(trimmed).replaceFirst("引用: ");
            } else if (markdownRich && MARKDOWN_BULLET.matcher(trimmed).find()) {
                trimmed = MARKDOWN_BULLET.matcher(trimmed).replaceFirst("- ");
            }

            sb.append(formatInlineMarkdown(trimmed)).append('\n');
        }

        return EXTRA_BLANK_LINES.matcher(sb.toString().trim()).replaceAll("\n\n");
    }

    private static boolean looksLikeMarkdown(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains("**")
                || text.contains("```")
                || text.contains("](")
                || MARKDOWN_MULTI_HASH_HEADING.matcher(text).find();
    }

    private static String formatInlineMarkdown(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String formatted = MARKDOWN_LINK.matcher(text)
                .replaceAll(match -> formatMarkdownLink(match.group(0), match.group(1), match.group(2)));
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

    private static String safe(String text) {
        return text == null ? "" : text;
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String describeConnectionAge() {
        if (gatewayConnectedAt == null || gatewayConnectedAt.equals(Instant.EPOCH)) {
            return "未知";
        }
        long seconds = Duration.between(gatewayConnectedAt, Instant.now()).getSeconds();
        return seconds + "s";
    }

    interface ReplySender {
        void passiveReply(IncomingC2cMessage message, String text);

        void activeReply(String userOpenId, String text);
    }

    private final class BufferedAiReplyPublisher {
        private final IncomingC2cMessage message;
        private final ReplySender sender;
        private final AiCliExecutor.CliType cliType;
        private final StringBuilder pending = new StringBuilder();
        private ScheduledFuture<?> flushFuture;
        private boolean sentAnyChunk;
        private boolean finished;

        private BufferedAiReplyPublisher(IncomingC2cMessage message, ReplySender sender, AiCliExecutor.CliType cliType) {
            this.message = message;
            this.sender = sender;
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
                flushPending(false);
                cancelScheduledFlush();
            } else if (flushFuture == null || flushFuture.isDone()) {
                flushFuture = scheduler.schedule(() -> {
                    synchronized (BufferedAiReplyPublisher.this) {
                        flushPending(false);
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
            flushPending(false);

            if (!sentAnyChunk) {
                String summary = buildAiSummary(cliType, snapshot, false);
                if (!summary.isBlank()) {
                    sender.activeReply(message.userOpenId(), summary);
                }
                return;
            }

            String text = "✅ " + cliType.getDisplayName() + " 任务已结束。\n使用 /"
                    + cliType.name().toLowerCase()
                    + "_status 可查看最近输出。";
            sender.activeReply(message.userOpenId(), text);
        }

        private void cancelScheduledFlush() {
            if (flushFuture != null) {
                flushFuture.cancel(false);
                flushFuture = null;
            }
        }

        private void flushPending(boolean allowEmpty) {
            String text = pending.toString().trim();
            pending.setLength(0);
            if (!allowEmpty && text.isBlank()) {
                return;
            }
            for (String part : splitForQq(text)) {
                sender.activeReply(message.userOpenId(), part);
                sentAnyChunk = true;
            }
        }
    }

    private class HttpReplySender implements ReplySender {
        @Override
        public void passiveReply(IncomingC2cMessage message, String text) {
            try {
                sendC2cMessage(message.userOpenId(), text, message.messageId(), false);
            } catch (Exception e) {
                log.error("发送 QQ 私聊被动消息失败: {}", e.getMessage(), e);
            }
        }

        @Override
        public void activeReply(String userOpenId, String text) {
            try {
                sendC2cMessage(userOpenId, text, null, true);
            } catch (Exception e) {
                log.error("发送 QQ 私聊主动消息失败: {}", e.getMessage(), e);
            }
        }
    }

    private class GatewayEndpoint extends Endpoint {
        @Override
        public void onOpen(Session session, EndpointConfig config) {
            gatewaySession = session;
            gatewayConnectedAt = Instant.now();
            gatewayStage = "已连接";
            statusMessage = "已连接 QQ Gateway";
            session.addMessageHandler(String.class, (MessageHandler.Whole<String>) QqOfficialBotProvider.this::handleGatewayMessage);
            log.info("QQ Gateway 已连接: id={}", session.getId());
        }

        @Override
        public void onClose(Session session, CloseReason closeReason) {
            running = false;
            gatewaySession = null;
            cancelHeartbeat();
            String previousStage = gatewayStage;
            gatewayStage = "连接关闭";
            log.warn("QQ Gateway 已关闭: code={}, reason={}, stage={}, connectedFor={}",
                    closeReason.getCloseCode().getCode(),
                    closeReason.getReasonPhrase(),
                    previousStage,
                    describeConnectionAge());
            if (!stopRequested && !"client closing".equals(closeReason.getReasonPhrase())) {
                scheduleReconnect("连接关闭(" + closeReason.getCloseCode().getCode() + ")");
            }
        }

        @Override
        public void onError(Session session, Throwable error) {
            running = false;
            gatewaySession = null;
            cancelHeartbeat();
            String previousStage = gatewayStage;
            gatewayStage = "连接异常";
            log.error("QQ Gateway 异常(stage={}, connectedFor={}): {}",
                    previousStage,
                    describeConnectionAge(),
                    error.getMessage(),
                    error);
            if (!stopRequested) {
                scheduleReconnect("连接异常");
            }
        }
    }
}
