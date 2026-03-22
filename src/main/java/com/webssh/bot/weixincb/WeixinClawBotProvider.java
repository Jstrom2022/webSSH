package com.webssh.bot.weixincb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webssh.bot.AiCliExecutor;
import com.webssh.bot.BotInteractionService;
import com.webssh.bot.BotSettings;
import com.webssh.bot.ChatBotProvider;
import com.webssh.config.ResourceGovernanceProperties;
import com.webssh.task.BoundedExecutorFactory;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信 ClawBot (iLink 协议) Provider。
 * <p>
 * 通过 iLink HTTP 长轮询连接到微信 ClawBot 插件，
 * 使用 {@code getupdates} 接收消息，{@code sendmessage} 发送回复。
 * </p>
 */
@Component
public class WeixinClawBotProvider implements ChatBotProvider {

    private static final Logger log = LoggerFactory.getLogger(WeixinClawBotProvider.class);
    static final String TYPE = "weixin-clawbot";

    static final String DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com";
    private static final String CHANNEL_VERSION = "1.0.0";
    private static final long LONG_POLL_TIMEOUT_MS = 35_000;
    private static final long RECONNECT_BASE_MS = 3_000;
    private static final double RECONNECT_MULTIPLIER = 1.5;
    private static final long RECONNECT_MAX_MS = 25_000;
    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final long AI_STREAM_FLUSH_INTERVAL_MS = 30_000;
    private static final int AI_STREAM_BATCH_THRESHOLD = 1800;

    // Markdown 规范化用正则
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern MARKDOWN_MULTI_HASH_HEADING = Pattern.compile("(?m)^#{2,6}\\s+\\S+");
    private static final Pattern MARKDOWN_CODE_FENCE = Pattern.compile("^```.*$");
    private static final Pattern MARKDOWN_BOLD = Pattern.compile("\\*\\*([^*\\n]+)\\*\\*");
    private static final Pattern MARKDOWN_INLINE_CODE = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("!?\\[([^\\]]+)]\\(([^)\\n]+)\\)");
    private static final Pattern MARKDOWN_QUOTE = Pattern.compile("^>\\s*");
    private static final Pattern MARKDOWN_BULLET = Pattern.compile("^\\s*[-*+]\\s+");
    private static final Pattern EXTRA_BLANK_LINES = Pattern.compile("\\n{3,}");

    private final BotInteractionService interactionService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ExecutorService eventExecutor;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "weixin-cb-scheduler");
        t.setDaemon(true);
        return t;
    });

    private volatile ProviderConfig currentConfig;
    private volatile boolean running = false;
    private volatile String statusMessage = "未启动";
    private volatile Thread pollThread;

    /** 每用户缓存 context_token，回复时必须回传。 */
    private final ConcurrentHashMap<String, String> contextTokenCache = new ConcurrentHashMap<>();

    record ProviderConfig(String botToken, String baseUrl, String sshUsername,
            Set<String> allowedUserIds) {
    }

    record IncomingMessage(String userId, String text, String contextToken) {
    }

    record CommandInput(String command, String argument) {
    }

    public WeixinClawBotProvider(BotInteractionService interactionService,
            ObjectMapper objectMapper,
            ResourceGovernanceProperties govProps) {
        this.interactionService = interactionService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        ResourceGovernanceProperties.ExecutorPool poolCfg = govProps.getWechatEvent();
        this.eventExecutor = BoundedExecutorFactory.newExecutor("weixin-cb-event-",
                new ResourceGovernanceProperties.ExecutorPool(
                        poolCfg.getCoreSize(), poolCfg.getMaxSize(), poolCfg.getQueueCapacity()));
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
    public boolean isRunning() {
        return running;
    }

    @Override
    public String getStatusMessage() {
        return statusMessage;
    }

    @Override
    public void start(BotSettings settings) throws Exception {
        stop();
        ProviderConfig config = parseSettings(settings);
        if (config.botToken() == null || config.botToken().isBlank()) {
            throw new IllegalArgumentException("微信 ClawBot: botToken 不能为空");
        }
        this.currentConfig = config;
        this.running = true;
        this.statusMessage = "正在连接...";
        contextTokenCache.clear();

        Thread thread = new Thread(() -> pollLoop(config), "weixin-cb-poll");
        thread.setDaemon(true);
        thread.start();
        this.pollThread = thread;
        log.info("微信 ClawBot 已启动");
    }

    @Override
    public void stop() {
        running = false;
        Thread t = pollThread;
        if (t != null) {
            t.interrupt();
            pollThread = null;
        }
        interactionService.disconnectAll(TYPE);
        statusMessage = "已停止";
        contextTokenCache.clear();
    }

    @PreDestroy
    void destroy() {
        stop();
        scheduler.shutdownNow();
        eventExecutor.shutdownNow();
    }

    // ===== 配置解析 =====

    static ProviderConfig parseSettings(BotSettings settings) {
        Map<String, String> config = settings.getConfig();
        String botToken = trimToNull(config.get("botToken"));
        String baseUrl = trimToNull(config.get("baseUrl"));
        if (baseUrl == null)
            baseUrl = DEFAULT_BASE_URL;
        Set<String> allowed = new LinkedHashSet<>();
        if (settings.getAllowedUserIds() != null) {
            settings.getAllowedUserIds().stream()
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .forEach(allowed::add);
        }
        return new ProviderConfig(botToken, baseUrl, settings.getSshUsername(), allowed);
    }

    // ===== 长轮询主循环 =====

    private void pollLoop(ProviderConfig config) {
        String getUpdatesBuf = "";
        int reconnectAttempt = 0;

        while (running) {
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("get_updates_buf", getUpdatesBuf);
                body.put("base_info", Map.of("channel_version", CHANNEL_VERSION));

                HttpRequest request = buildILinkRequest(config, "ilink/bot/getupdates",
                        objectMapper.writeValueAsString(body),
                        Duration.ofMillis(LONG_POLL_TIMEOUT_MS + 5_000));

                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());

                if (!running)
                    break;

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    reconnectAttempt = 0;
                    statusMessage = "运行中（iLink 已连接）";

                    JsonNode json = objectMapper.readTree(response.body());
                    // 错误码处理
                    int errcode = json.path("errcode").asInt(0);
                    if (errcode == -14) {
                        log.warn("微信 ClawBot: 会话超时 (errcode=-14)，将重连");
                        getUpdatesBuf = "";
                        continue;
                    }

                    String newBuf = json.path("get_updates_buf").asText(null);
                    if (newBuf != null && !newBuf.isEmpty()) {
                        getUpdatesBuf = newBuf;
                    }

                    JsonNode msgs = json.path("msgs");
                    if (msgs.isArray() && msgs.size() > 0) {
                        processMessages(msgs, config);
                    }
                } else {
                    log.warn("微信 ClawBot: getupdates 返回 HTTP {}", response.statusCode());
                    statusMessage = "连接异常 (HTTP " + response.statusCode() + ")";
                    reconnectWait(reconnectAttempt++);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (!running)
                    break;
                log.error("微信 ClawBot 轮询异常: {}", e.getMessage());
                statusMessage = "连接异常: " + e.getMessage();
                try {
                    reconnectWait(reconnectAttempt++);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        statusMessage = "已停止";
    }

    private void reconnectWait(int attempt) throws InterruptedException {
        long delay = Math.min((long) (RECONNECT_BASE_MS * Math.pow(RECONNECT_MULTIPLIER, attempt)),
                RECONNECT_MAX_MS);
        log.info("微信 ClawBot: {}ms 后重连 (attempt={})", delay, attempt + 1);
        Thread.sleep(delay);
    }

    // ===== 消息处理 =====

    private void processMessages(JsonNode msgs, ProviderConfig config) {
        for (JsonNode msg : msgs) {
            int messageType = msg.path("message_type").asInt(0);
            if (messageType != 1)
                continue; // 只处理用户消息

            String userId = msg.path("from_user_id").asText("");
            String contextToken = msg.path("context_token").asText(null);
            if (userId.isEmpty())
                continue;

            // 白名单检查
            if (!config.allowedUserIds().isEmpty() && !config.allowedUserIds().contains(userId)) {
                continue;
            }

            // 缓存 context_token
            if (contextToken != null) {
                contextTokenCache.put(userId, contextToken);
            }

            // 提取文本
            String text = extractText(msg.path("item_list"));
            if (text.isEmpty())
                continue;

            IncomingMessage incoming = new IncomingMessage(userId, text, contextToken);
            try {
                eventExecutor.submit(() -> processIncomingMessage(incoming, config));
            } catch (Exception e) {
                log.warn("微信 ClawBot: 事件提交被拒: {}", e.getMessage());
            }
        }
    }

    private String extractText(JsonNode itemList) {
        if (!itemList.isArray())
            return "";
        for (JsonNode item : itemList) {
            int type = item.path("type").asInt(0);
            if (type == 1) { // TEXT
                return item.path("text_item").path("text").asText("");
            }
            if (type == 3) { // VOICE - 语音转文字
                String voiceText = item.path("voice_item").path("text").asText(null);
                if (voiceText != null && !voiceText.isEmpty())
                    return voiceText;
            }
        }
        return "";
    }

    // ===== 命令路由 =====

    private void processIncomingMessage(IncomingMessage message, ProviderConfig config) {
        String text = message.text().trim();
        if (text.startsWith("/")) {
            handleCommand(message, config, parseCommandInput(text));
        } else {
            handleUserInput(message, text);
        }
    }

    static CommandInput parseCommandInput(String text) {
        String[] parts = text.trim().split("\\s+", 2);
        return new CommandInput(parts[0].toLowerCase(), parts.length > 1 ? parts[1].trim() : "");
    }

    private void handleCommand(IncomingMessage msg, ProviderConfig config, CommandInput input) {
        switch (input.command()) {
            case "/start", "/help" -> reply(msg, buildHelpText());
            case "/list" -> reply(msg, buildProfileListText(config.sshUsername()));
            case "/connect" -> handleConnect(msg, config, input.argument());
            case "/disconnect" -> handleDisconnect(msg);
            case "/status" -> handleStatus(msg);
            case "/codex" -> handleAiMode(msg, input.argument(), AiCliExecutor.CliType.CODEX);
            case "/codex_stop" -> handleAiStop(msg, AiCliExecutor.CliType.CODEX);
            case "/codex_status" -> handleAiStatus(msg, AiCliExecutor.CliType.CODEX);
            case "/codex_clear" -> handleAiClear(msg, AiCliExecutor.CliType.CODEX);
            case "/claude" -> handleAiMode(msg, input.argument(), AiCliExecutor.CliType.CLAUDE);
            case "/claude_stop" -> handleAiStop(msg, AiCliExecutor.CliType.CLAUDE);
            case "/claude_status" -> handleAiStatus(msg, AiCliExecutor.CliType.CLAUDE);
            case "/claude_clear" -> handleAiClear(msg, AiCliExecutor.CliType.CLAUDE);
            default -> reply(msg, "未知命令: " + input.command() + "\n使用 /help 查看可用命令。");
        }
    }

    private void handleConnect(IncomingMessage msg, ProviderConfig config, String target) {
        if (target == null || target.isBlank()) {
            reply(msg, "用法: /connect <会话名称或序号>\n例如: /connect 1");
            return;
        }
        try {
            String result = interactionService.connect(TYPE, msg.userId(), config.sshUsername(), target);
            reply(msg, result);
        } catch (Exception e) {
            reply(msg, "连接失败: " + e.getMessage());
        }
    }

    private void handleDisconnect(IncomingMessage msg) {
        var result = interactionService.disconnect(TYPE, msg.userId());
        reply(msg, result.disconnected()
                ? "已断开与 " + result.profileName() + " 的连接。"
                : "当前没有活跃的 SSH 连接。");
    }

    private void handleStatus(IncomingMessage msg) {
        var status = interactionService.getConnectionStatus(TYPE, msg.userId());
        AiCliExecutor.CliType aiMode = interactionService.getAiMode(TYPE, msg.userId());
        StringBuilder sb = new StringBuilder();
        if (!status.connected()) {
            sb.append("状态: 未连接\n使用 /list 查看可用会话");
        } else {
            sb.append("状态: 已连接到 ").append(status.profileName());
            if (status.cwd() != null && !status.cwd().isBlank())
                sb.append("\n目录: ").append(status.cwd());
        }
        if (aiMode != null)
            sb.append("\nAI: ").append(aiMode.getDisplayName());
        reply(msg, sb.toString());
    }

    private void handleUserInput(IncomingMessage msg, String text) {
        AiCliExecutor.CliType aiMode = interactionService.getAiMode(TYPE, msg.userId());
        if (aiMode != null) {
            startAiTask(msg, text, aiMode);
            return;
        }
        var status = interactionService.getConnectionStatus(TYPE, msg.userId());
        if (!status.connected()) {
            reply(msg, "未连接 SSH。请先使用 /connect 连接。\n使用 /list 查看可用会话。");
            return;
        }
        interactionService.executeShellCommandAsync(TYPE, msg.userId(), text)
                .whenComplete((output, error) -> {
                    if (error != null) {
                        reply(msg, "执行失败: " + error.getMessage());
                        return;
                    }
                    reply(msg, truncate(output));
                });
    }

    private void handleAiMode(IncomingMessage msg, String prompt, AiCliExecutor.CliType cliType) {
        interactionService.enterAiMode(TYPE, msg.userId(), cliType);
        if (prompt == null || prompt.isBlank()) {
            reply(msg, "已进入 " + cliType.getDisplayName() + " 模式。\n后续消息将按此模式执行。");
            return;
        }
        startAiTask(msg, prompt, cliType);
    }

    private void startAiTask(IncomingMessage msg, String prompt, AiCliExecutor.CliType cliType) {
        if (interactionService.isAiTaskRunning(TYPE, msg.userId(), cliType)) {
            reply(msg, "已有 " + cliType.getDisplayName() + " 任务在运行。");
            return;
        }

        BufferedAiReplyPublisher publisher = new BufferedAiReplyPublisher(msg, cliType);
        var result = interactionService.startAiTask(TYPE, msg.userId(), prompt, cliType,
                chunk -> {
                    // 过滤非核心消息，节省 context_token 配额
                    if (chunk == null || chunk.isBlank()) return;
                    if (chunk.startsWith("⎡🔧")) return;       // 工具调用提示
                    if (chunk.startsWith("🖥️")) return;        // 原始行回显
                    if (chunk.startsWith("— Token:")) return;  // 用量统计
                    if (chunk.startsWith("⏳")) return;         // 启动消息（finish 会发完成标记）
                    if (chunk.startsWith("✅") && chunk.contains("exit=")) return; // executor 完成消息（finish 会发）
                    publisher.append(chunk);
                },
                () -> {
                    var snapshot = interactionService.getAiTaskSnapshot(TYPE, msg.userId(), cliType);
                    publisher.finish(snapshot);
                });
        if (!result.started()) {
            reply(msg, "启动失败: " + result.message());
        }
    }

    private void handleAiStop(IncomingMessage msg, AiCliExecutor.CliType cliType) {
        interactionService.exitAiMode(TYPE, msg.userId());
        interactionService.stopAiTask(TYPE, msg.userId(), cliType);
        reply(msg, cliType.getDisplayName() + " 已停止，已退出 AI 模式。");
    }

    private void handleAiStatus(IncomingMessage msg, AiCliExecutor.CliType cliType) {
        var snapshot = interactionService.getAiTaskSnapshot(TYPE, msg.userId(), cliType);
        if (snapshot == null || !snapshot.running()) {
            reply(msg, "当前没有运行中的 " + cliType.getDisplayName() + " 任务。");
        } else {
            String last = snapshot.lastOutput();
            reply(msg, cliType.getDisplayName() + " 运行中\n最近输出:\n" + truncate(last));
        }
    }

    private void handleAiClear(IncomingMessage msg, AiCliExecutor.CliType cliType) {
        interactionService.exitAiMode(TYPE, msg.userId());
        interactionService.clearAiSession(TYPE, msg.userId(), cliType);
        reply(msg, cliType.getDisplayName() + " 会话已清除。");
    }

    // ===== 消息发送 =====

    private void reply(IncomingMessage msg, String text) {
        if (text == null || text.isBlank())
            return;
        ProviderConfig config = currentConfig;
        if (config == null || !running) {
            log.debug("微信 ClawBot reply 跳过: config={}, running={}", config != null, running);
            return;
        }

        // 优先使用最新缓存的 contextToken，避免持有过期 token
        String contextToken = contextTokenCache.get(msg.userId());
        if (contextToken == null)
            contextToken = msg.contextToken();

        // 分片发送长消息
        List<String> chunks = splitMessage(text, MAX_MESSAGE_LENGTH);
        for (int i = 0; i < chunks.size(); i++) {
            if (i > 0) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            sendILinkMessage(config, msg.userId(), chunks.get(i), contextToken);
        }
    }

    private static final int SEND_MAX_RETRIES = 2;
    private static final long SEND_RETRY_BASE_MS = 500;

    private void sendILinkMessage(ProviderConfig config, String toUserId, String text,
            String contextToken) {
        String preview = text.length() > 200 ? text.substring(0, 200) + "..." : text;
        for (int attempt = 0; attempt <= SEND_MAX_RETRIES; attempt++) {
            try {
                boolean useContextToken = contextToken != null && attempt < SEND_MAX_RETRIES;
                SendResult sr = doSendILinkMessage(config, toUserId, text,
                        useContextToken ? contextToken : null);
                if (sr.success()) {
                    log.info("微信 ClawBot 发送成功 [to={}, len={}, attempt={}]: {}",
                            toUserId, text.length(), attempt + 1, preview);
                    return;
                }
                log.warn("微信 ClawBot 发送失败 HTTP={} ret={} resp={} (attempt={}, withCtx={}): {}",
                        sr.httpStatus, sr.ret, sr.body, attempt + 1, useContextToken, preview);
            } catch (Exception e) {
                log.warn("微信 ClawBot 发送消息异常 (attempt={}): {} | content: {}",
                        attempt + 1, e.getMessage(), preview);
            }
            if (attempt < SEND_MAX_RETRIES) {
                try {
                    Thread.sleep(SEND_RETRY_BASE_MS * (attempt + 1));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.error("微信 ClawBot 消息发送最终失败 [to={}, len={}]: {}", toUserId, text.length(), preview);
    }

    /** iLink sendmessage 的结果。 */
    private record SendResult(int httpStatus, int ret, String body) {
        boolean success() {
            return httpStatus >= 200 && httpStatus < 300 && ret == 0;
        }
    }

    private SendResult doSendILinkMessage(ProviderConfig config, String toUserId, String text,
            String contextToken) throws Exception {
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("from_user_id", "");
        msg.put("to_user_id", toUserId);
        msg.put("client_id", UUID.randomUUID().toString());
        msg.put("message_type", 2); // BOT
        msg.put("message_state", 2); // FINISH
        if (contextToken != null)
            msg.put("context_token", contextToken);

        ArrayNode itemList = msg.putArray("item_list");
        ObjectNode textItem = itemList.addObject();
        textItem.put("type", 1); // TEXT
        textItem.putObject("text_item").put("text", text);

        ObjectNode body = objectMapper.createObjectNode();
        body.set("msg", msg);
        body.putObject("base_info").put("channel_version", CHANNEL_VERSION);

        HttpRequest request = buildILinkRequest(config, "ilink/bot/sendmessage",
                objectMapper.writeValueAsString(body), Duration.ofSeconds(15));

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());
        int httpStatus = response.statusCode();
        int ret = 0;
        try {
            JsonNode respJson = objectMapper.readTree(response.body());
            ret = respJson.path("ret").asInt(0);
        } catch (Exception ignored) {
        }
        return new SendResult(httpStatus, ret, response.body());
    }

    /**
     * 一个 context_token 最多可发送的消息条数（iLink 限制）。
     * 配额分配：N 流式 + 1 finish（剩余内容 + 完成标记）。
     */
    private static final int CONTEXT_TOKEN_MSG_LIMIT = 10;
    private static final int AI_STREAM_RESERVED_MSGS = 1; // finish
    private static final int AI_STREAM_MAX_FLUSHES = CONTEXT_TOKEN_MSG_LIMIT - AI_STREAM_RESERVED_MSGS;

    private final class BufferedAiReplyPublisher {
        private final IncomingMessage message;
        private final AiCliExecutor.CliType cliType;
        private final StringBuilder pending = new StringBuilder();
        private ScheduledFuture<?> flushFuture;
        private boolean sentAnyChunk;
        private boolean finished;
        private int flushCount;

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

            if (flushCount >= AI_STREAM_MAX_FLUSHES) {
                // 配额已用完，只缓冲不发送，留给 finish 一次性发
                return;
            }

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
            if (finished)
                return;
            finished = true;
            cancelScheduledFlush();

            String suffix = "\n✅ " + cliType.getDisplayName() + " 任务已结束。";
            int maxBody = MAX_MESSAGE_LENGTH - suffix.length();
            String remaining = pending.toString().trim();
            pending.setLength(0);

            if (!remaining.isEmpty()) {
                if (remaining.length() > maxBody) {
                    remaining = remaining.substring(remaining.length() - maxBody);
                }
                reply(message, remaining + suffix);
                return;
            }

            if (!sentAnyChunk) {
                if (snapshot != null && snapshot.hasOutput()) {
                    String last = truncate(snapshot.lastOutput());
                    if (last.length() > maxBody) {
                        last = last.substring(last.length() - maxBody);
                    }
                    reply(message, last + suffix);
                } else {
                    reply(message, "(无输出)" + suffix);
                }
                return;
            }

            reply(message, "✅ " + cliType.getDisplayName() + " 任务已结束。");
        }

        private void cancelScheduledFlush() {
            if (flushFuture != null) {
                flushFuture.cancel(false);
                flushFuture = null;
            }
        }

        /** 流式期间的定期刷新，受配额限制。 */
        private void flushPending() {
            if (flushCount >= AI_STREAM_MAX_FLUSHES) {
                return;
            }
            String text = pending.toString().trim();
            pending.setLength(0);
            if (text.isEmpty()) {
                return;
            }
            reply(message, text);
            sentAnyChunk = true;
            flushCount++;
        }
    }

    // ===== HTTP 请求构建 =====

    private HttpRequest buildILinkRequest(ProviderConfig config, String endpoint,
            String jsonBody, Duration timeout) {
        String url = config.baseUrl();
        if (!url.endsWith("/"))
            url += "/";
        url += endpoint;

        SecureRandom random = new SecureRandom();
        byte[] uinBytes = new byte[4];
        random.nextBytes(uinBytes);
        String wechatUin = Base64.getEncoder().encodeToString(
                String.valueOf(Math.abs(java.nio.ByteBuffer.wrap(uinBytes).getInt())).getBytes(StandardCharsets.UTF_8));

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.botToken())
                .header("AuthorizationType", "ilink_bot_token")
                .header("X-WECHAT-UIN", wechatUin);

        return builder.build();
    }

    // ===== 辅助方法 =====

    private String buildHelpText() {
        return """
                微信 ClawBot 命令列表:
                /help - 显示帮助
                /list - 列出 SSH 会话
                /connect <名称> - 连接 SSH
                /disconnect - 断开连接
                /status - 查看状态
                /claude <提示词> - AI 模式
                /claude_stop - 停止 AI
                /claude_clear - 清除 AI 会话
                连接 SSH 后直接发送文字执行命令。""";
    }

    private String buildProfileListText(String sshUsername) {
        var profiles = interactionService.listProfiles(sshUsername);
        if (profiles == null || profiles.isEmpty())
            return "没有可用的 SSH 会话。";
        StringBuilder sb = new StringBuilder("可用 SSH 会话:\n");
        for (int i = 0; i < profiles.size(); i++) {
            sb.append(i + 1).append(". ").append(profiles.get(i).getName()).append("\n");
        }
        sb.append("\n使用 /connect <序号> 连接。");
        return sb.toString();
    }

    static List<String> splitMessage(String text, int maxLen) {
        String normalized = normalizeForWeixinCb(text);
        if (normalized.isBlank())
            return List.of();
        if (normalized.length() <= maxLen)
            return List.of(normalized);
        java.util.List<String> chunks = new java.util.ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + maxLen, normalized.length());
            if (end < normalized.length()) {
                int nl = normalized.lastIndexOf('\n', end);
                if (nl > start + maxLen / 2)
                    end = nl + 1;
            }
            chunks.add(normalized.substring(start, end));
            start = end;
        }
        return chunks;
    }

    private String truncate(String text) {
        if (text == null)
            return "(无输出)";
        String normalized = normalizeForWeixinCb(text);
        return normalized.length() > MAX_MESSAGE_LENGTH * 3
                ? normalized.substring(0, MAX_MESSAGE_LENGTH * 3) + "\n...(已截断)"
                : normalized;
    }

    /** 将 Markdown 转为微信纯文本友好格式。 */
    static String normalizeForWeixinCb(String text) {
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
        if (text == null || text.isBlank())
            return false;
        return text.contains("**") || text.contains("```")
                || text.contains("](") || MARKDOWN_MULTI_HASH_HEADING.matcher(text).find();
    }

    private static String formatInlineMarkdown(String text) {
        if (text == null || text.isBlank())
            return "";
        String formatted = MARKDOWN_LINK.matcher(text)
                .replaceAll(match -> {
                    if (match.group(0).startsWith("!"))
                        return "图片: " + match.group(1) + " " + match.group(2);
                    return match.group(1) + ": " + match.group(2);
                });
        formatted = MARKDOWN_BOLD.matcher(formatted).replaceAll("【$1】");
        formatted = MARKDOWN_INLINE_CODE.matcher(formatted).replaceAll("$1");
        return formatted;
    }

    private static String trimToNull(String text) {
        if (text == null)
            return null;
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
