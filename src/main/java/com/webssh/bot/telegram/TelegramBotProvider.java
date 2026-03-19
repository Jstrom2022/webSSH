package com.webssh.bot.telegram;

import com.webssh.bot.BotSettings;
import com.webssh.bot.AiCliExecutor;
import com.webssh.bot.BotInteractionService;
import com.webssh.bot.ChatBotProvider;
import com.webssh.session.SshSessionProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import org.telegram.telegrambots.updatesreceivers.ExponentialBackOff;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Telegram 机器人提供者 — 实现 {@link ChatBotProvider} 的 Telegram 版本。
 * <p>
 * 使用 Long Polling 模式与 Telegram 服务器通信，无需公网域名和 SSL。
 * 处理 SSH 相关命令（/list, /connect, /disconnect, /status），
 * 普通文本消息作为 Shell 命令发送到已连接的 SSH 服务器。
 * </p>
 */
@Component
public class TelegramBotProvider implements ChatBotProvider {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotProvider.class);
    private static final String TYPE = "telegram";
    /** Long Polling 断线重连：首次退避时长（毫秒）。 */
    private static final int RECONNECT_INITIAL_INTERVAL_MS = 1_000;
    /** Long Polling 断线重连：最大退避时长（毫秒）。 */
    private static final int RECONNECT_MAX_INTERVAL_MS = 30_000;
    /** Long Polling 断线重连：总退避窗口（毫秒，达到后固定为该值）。 */
    private static final int RECONNECT_MAX_ELAPSED_MS = 30_000;

    private final BotInteractionService interactionService;

    /** Telegram Bot API 入口对象。 */
    private volatile TelegramBotsApi botsApi;
    /** 当前运行中的 Bot 实例。 */
    private volatile InternalBot bot;
    /** 运行状态标记。 */
    private volatile boolean running = false;
    /** UI 展示状态描述。 */
    private volatile String statusMessage = "未启动";
    /** 长轮询会话对象，用于 stop 时主动关闭。 */
    private volatile DefaultBotSession botSession;

    public TelegramBotProvider(BotInteractionService interactionService) {
        this.interactionService = interactionService;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public String getDisplayName() {
        return "Telegram";
    }

    @Override
    public void start(BotSettings settings) throws Exception {
        stop();

        // Telegram 参数全部来自 bot-settings 配置。
        String token = settings.getConfig().get("token");
        String botUsername = settings.getConfig().get("botUsername");
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Telegram Bot Token 不能为空");
        }
        if (botUsername == null || botUsername.isBlank()) {
            throw new IllegalArgumentException("Telegram Bot Username 不能为空");
        }

        Set<String> allowedUsers = new CopyOnWriteArraySet<>(settings.getAllowedUserIds());
        String sshUsername = settings.getSshUsername();

        try {
            bot = new InternalBot(buildBotOptions(), token, botUsername, allowedUsers, sshUsername);
            botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botSession = (DefaultBotSession) botsApi.registerBot(bot);
            running = true;
            statusMessage = "运行中 (@" + botUsername + ")";
            log.info("Telegram Bot 已启动: @{}", botUsername);
        } catch (TelegramApiException e) {
            running = false;
            statusMessage = "启动失败: " + e.getMessage();
            throw new IllegalStateException("Telegram Bot 启动失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void stop() {
        running = false;
        if (botSession != null) {
            try {
                botSession.stop();
            } catch (Exception e) {
                log.debug("停止 Telegram Bot Session 异常: {}", e.getMessage());
            }
            botSession = null;
        }
        bot = null;
        botsApi = null;
        interactionService.disconnectAll(TYPE);
        statusMessage = "已停止";
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public String getStatusMessage() {
        return statusMessage;
    }

    /**
     * 构建 Telegram Long Polling 的运行参数。
     * 这里显式收紧重连退避上限，避免网络抖动后长时间不重试。
     */
    private DefaultBotOptions buildBotOptions() {
        DefaultBotOptions options = new DefaultBotOptions();
        options.setBackOff(new ExponentialBackOff.Builder()
                .setInitialIntervalMillis(RECONNECT_INITIAL_INTERVAL_MS)
                .setMultiplier(1.5)
                .setMaxIntervalMillis(RECONNECT_MAX_INTERVAL_MS)
                .setMaxElapsedTimeMillis(RECONNECT_MAX_ELAPSED_MS)
                .build());
        return options;
    }

    /**
     * 内部 Telegram Bot 实现，处理消息接收和命令分发。
     */
    private class InternalBot extends TelegramLongPollingBot {

        private static final int MAX_MESSAGE_LENGTH = 4000;
        private final String botUsername;
        private final Set<String> allowedUsers;
        private final String sshUsername;

        InternalBot(DefaultBotOptions options, String token, String botUsername,
                Set<String> allowedUsers, String sshUsername) {
            super(options, token);
            this.botUsername = botUsername;
            this.allowedUsers = allowedUsers;
            this.sshUsername = sshUsername;
        }

        @Override
        public String getBotUsername() {
            return botUsername;
        }

        @Override
        public void onUpdateReceived(Update update) {
            // 仅处理文本消息，其他类型（图片/文件）直接忽略。
            if (!update.hasMessage() || !update.getMessage().hasText()) {
                return;
            }

            Message message = update.getMessage();
            long chatId = message.getChatId();
            String userId = String.valueOf(message.getFrom().getId());
            String text = message.getText().trim();
            String aliasCommand = normalizeAiControlAlias(text);

            // 鉴权检查
            if (!allowedUsers.isEmpty() && !allowedUsers.contains(userId)) {
                sendText(chatId, "⛔ 你没有权限使用此机器人。\n你的用户 ID: " + userId);
                return;
            }

            try {
                if (aliasCommand != null) {
                    handleCommand(chatId, userId, aliasCommand);
                } else if (text.startsWith("/")) {
                    // /命令 走管理逻辑；普通文本按 Shell 命令执行。
                    handleCommand(chatId, userId, text);
                } else {
                    handleUserInput(chatId, userId, text);
                }
            } catch (Exception e) {
                log.error("处理消息异常: {}", e.getMessage(), e);
                sendText(chatId, "❌ 处理失败: " + e.getMessage());
            }
        }

        private void handleCommand(long chatId, String userId, String text) {
            String[] parts = text.split("\\s+", 2);
            // 去掉命令中的 @botname 后缀，例如 /connect@MyBot -> /connect
            String cmd = parts[0].split("@")[0].toLowerCase();
            String arg = parts.length > 1 ? parts[1].trim() : "";

            switch (cmd) {
                case "/start", "/help" -> handleStart(chatId);
                case "/list" -> handleList(chatId);
                case "/connect" -> handleConnect(chatId, userId, arg);
                case "/disconnect" -> handleDisconnect(chatId, userId);
                case "/status" -> handleStatus(chatId, userId);
                case "/codex" -> handleAiModeCommand(chatId, userId, arg, AiCliExecutor.CliType.CODEX);
                case "/codex_stop" -> handleAiCliStop(chatId, userId, AiCliExecutor.CliType.CODEX);
                case "/codex_status" -> handleAiCliStatus(chatId, userId, AiCliExecutor.CliType.CODEX);
                case "/codex_clear" -> handleAiCliClear(chatId, userId, AiCliExecutor.CliType.CODEX);
                case "/claude" -> handleAiModeCommand(chatId, userId, arg, AiCliExecutor.CliType.CLAUDE);
                case "/claude_stop" -> handleAiCliStop(chatId, userId, AiCliExecutor.CliType.CLAUDE);
                case "/claude_status" -> handleAiCliStatus(chatId, userId, AiCliExecutor.CliType.CLAUDE);
                case "/claude_clear" -> handleAiCliClear(chatId, userId, AiCliExecutor.CliType.CLAUDE);
                default -> sendText(chatId, "未知命令: " + cmd + "\n使用 /help 查看可用命令。");
            }
        }

        /** /start 与 /help 共用说明文本。 */
        private void handleStart(long chatId) {
            sendText(chatId, """
                    🖥️ *WebSSH Telegram Bot*

                    通过此机器人管理和使用 SSH 连接。

                    *SSH 命令:*
                    /list — 查看已保存的 SSH 会话
                    /connect <名称或序号> — 连接 SSH
                    /disconnect — 断开当前连接
                    /status — 查看连接状态

                    *AI 编程命令:*
                    /codex [提示词] — 进入 Codex AI 模式
                    /codex\\_stop — 停止 Codex 任务
                    /codex\\_clear — 清除 Codex 会话 ID
                    /claude [提示词] — 进入 Claude Code 模式
                    /claude\\_stop — 停止 Claude 任务
                    /claude\\_clear — 清除 Claude 会话 ID

                    AI 模式下，后续普通输入会持续走对应 AI，直到 stop/clear 退出。
                    未进入 AI 模式时，连接后直接发送文字即执行 Shell 命令。""", true);
        }

        /** 列出当前 WebSSH 用户可用的 SSH 会话。 */
        private void handleList(long chatId) {
            List<SshSessionProfile> profiles = interactionService.listProfiles(sshUsername);
            if (profiles.isEmpty()) {
                sendText(chatId, "📋 没有已保存的 SSH 会话。\n请在 WebSSH 界面中添加会话配置。");
                return;
            }

            StringBuilder sb = new StringBuilder("📋 *已保存的 SSH 会话:*\n\n");
            for (int i = 0; i < profiles.size(); i++) {
                SshSessionProfile p = profiles.get(i);
                sb.append(String.format("`%d.` *%s*\n    `%s@%s:%d` \\[%s\\]\n\n",
                        i + 1,
                        escapeMarkdown(p.getName()),
                        escapeMarkdown(p.getUsername()),
                        escapeMarkdown(p.getHost()),
                        p.getPort(),
                        p.getAuthType()));
            }
            sb.append("使用 `/connect <序号或名称>` 连接");
            sendText(chatId, sb.toString(), true);
        }

        /** 建立 SSH 连接。target 支持“序号”或“名称”。 */
        private void handleConnect(long chatId, String userId, String target) {
            if (target.isEmpty()) {
                sendText(chatId, "用法: /connect <会话名称或序号>\n例如: `/connect 1` 或 `/connect MyVPS`", true);
                return;
            }

            sendText(chatId, "⏳ 正在连接...");
            try {
                String result = interactionService.connect(TYPE, userId, sshUsername, target);
                sendText(chatId, result);
            } catch (Exception e) {
                sendText(chatId, "❌ 连接失败: " + e.getMessage());
            }
        }

        /** 断开当前 Telegram 用户绑定的 SSH 连接。 */
        private void handleDisconnect(long chatId, String userId) {
            BotInteractionService.DisconnectResult result = interactionService.disconnect(TYPE, userId);
            if (!result.disconnected()) {
                sendText(chatId, "当前没有活跃的 SSH 连接。");
                return;
            }
            sendText(chatId, "🔌 已断开与 " + result.profileName() + " 的连接。");
        }

        /** 查询 SSH 连接状态。 */
        private void handleStatus(long chatId, String userId) {
            BotInteractionService.ConnectionStatus status = interactionService.getConnectionStatus(TYPE, userId);
            AiCliExecutor.CliType aiMode = interactionService.getAiMode(TYPE, userId);
            if (!status.connected()) {
                StringBuilder sb = new StringBuilder("📊 状态: 未连接\n使用 /list 查看可用会话，/connect 连接。");
                if (aiMode != null) {
                    sb.append("\n🤖 AI 模式: ").append(aiMode.getDisplayName());
                }
                sendText(chatId, sb.toString());
            } else {
                StringBuilder sb = new StringBuilder("📊 状态: 已连接到 *")
                        .append(escapeMarkdown(status.profileName()))
                        .append("*\n");
                if (aiMode != null) {
                    String cmdName = aiMode.name().toLowerCase();
                    sb.append("🤖 AI 模式: ")
                            .append(aiMode.getDisplayName())
                            .append("（使用 /")
                            .append(cmdName)
                            .append("\\_stop 或 /")
                            .append(cmdName)
                            .append("\\_clear 退出）\n");
                } else {
                    sb.append("🤖 AI 模式: 未开启\n");
                }
                sb.append("直接发送文字执行命令，/disconnect 断开。");
                sendText(chatId, sb.toString(), true);
            }
        }

        /** 普通输入路由：AI 模式下走 AI，否则走 Shell。 */
        private void handleUserInput(long chatId, String userId, String text) {
            AiCliExecutor.CliType aiMode = interactionService.getAiMode(TYPE, userId);
            if (aiMode != null) {
                startAiTask(chatId, userId, text, aiMode);
                return;
            }
            handleShellInput(chatId, userId, text);
        }

        /** 普通文本按 Shell 命令执行，并将输出包裹为代码块。 */
        private void handleShellInput(long chatId, String userId, String text) {
            BotInteractionService.ConnectionStatus status = interactionService.getConnectionStatus(TYPE, userId);
            if (!status.connected()) {
                sendText(chatId, "未连接 SSH。请先使用 /connect 连接。\n使用 /list 查看可用会话。");
                return;
            }

            interactionService.executeShellCommand(TYPE, userId, text, output -> {
                sendText(chatId, "```\n" + output + "\n```", true);
            });
        }

        // ========== AI CLI 通用命令 ==========

        /** 进入 AI 模式；若带提示词则立即执行一次。 */
        private void handleAiModeCommand(long chatId, String userId, String prompt, AiCliExecutor.CliType cliType) {
            interactionService.enterAiMode(TYPE, userId, cliType);
            String cmdName = cliType.name().toLowerCase();
            if (prompt == null || prompt.isBlank()) {
                sendText(chatId, "🤖 已进入 " + cliType.getDisplayName() + " 模式。\n"
                        + "后续直接发送内容将按该模式执行。\n"
                        + "使用 /" + cmdName + "\\_stop 或 /" + cmdName + "\\_clear 退出。", true);
                return;
            }
            startAiTask(chatId, userId, prompt, cliType);
        }

        /** 启动 AI CLI 任务并将流式输出推送到聊天窗口。 */
        private void startAiTask(long chatId, String userId, String prompt, AiCliExecutor.CliType cliType) {
            String name = cliType.getDisplayName();
            String cmdName = cliType.name().toLowerCase();

            if (prompt == null || prompt.isBlank()) {
                sendText(chatId, "用法: /" + cmdName + " <提示词>\n例如: `/" + cmdName + " 分析当前项目结构`", true);
                return;
            }

            if (interactionService.isAiTaskRunning(TYPE, userId, cliType)) {
                sendText(chatId, "⚠️ 已有 " + name + " 任务在运行。\n使用 /" + cmdName + "\\_stop 停止后再试。", true);
                return;
            }

            BotInteractionService.StartAiTaskResult result = interactionService.startAiTask(TYPE, userId, prompt, cliType,
                    // AI 输出内容不可控，可能包含 Telegram Markdown 非法片段；统一按纯文本发送更稳妥。
                    output -> sendText(chatId, output, false),
                    () -> log.debug("{} 任务结束 [{}:{}]", name, TYPE, userId));
            if (!result.started()) {
                sendText(chatId, "❌ " + result.message());
            }
        }

        /** 清理 AI 会话上下文。 */
        private void handleAiCliClear(long chatId, String userId, AiCliExecutor.CliType cliType) {
            interactionService.clearAiSession(TYPE, userId, cliType);
            interactionService.exitAiMode(TYPE, userId);
            sendText(chatId, "✨ " + cliType.getDisplayName() + " 的会话 ID 已清除，并已退出 AI 模式。");
        }

        /** 停止运行中的 AI 任务。 */
        private void handleAiCliStop(long chatId, String userId, AiCliExecutor.CliType cliType) {
            interactionService.exitAiMode(TYPE, userId);
            if (interactionService.stopAiTask(TYPE, userId, cliType)) {
                sendText(chatId, "🛑 " + cliType.getDisplayName() + " 任务已停止，并已退出 AI 模式。");
            } else {
                sendText(chatId, "当前没有正在运行的 " + cliType.getDisplayName() + " 任务，已退出 AI 模式。");
            }
        }

        /** 查询 AI 任务运行状态。 */
        private void handleAiCliStatus(long chatId, String userId, AiCliExecutor.CliType cliType) {
            String name = cliType.getDisplayName();
            String cmdName = cliType.name().toLowerCase();
            if (interactionService.isAiTaskRunning(TYPE, userId, cliType)) {
                sendText(chatId, "📊 " + name + " 状态: ⏳ 任务执行中...");
            } else {
                sendText(chatId, "📊 " + name + " 状态: 空闲\n使用 `/" + cmdName + " <提示词>` 启动任务。", true);
            }
        }

        /** 兼容无斜杠 AI 控制命令（仅 stop/clear）。 */
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

        private void sendText(long chatId, String text) {
            sendText(chatId, text, false);
        }

        /**
         * 发送消息到 Telegram。
         * <p>
         * Telegram 单条消息有限长，这里按 4000 字符分片发送。
         * </p>
         */
        private void sendText(long chatId, String text, boolean markdown) {
            List<String> chunks = splitForTelegram(text);
            for (String chunk : chunks) {
                try {
                    sendChunk(chatId, chunk, markdown);
                } catch (TelegramApiException e) {
                    log.error("发送 Telegram 消息分片失败: {}", e.getMessage(), e);
                    // Markdown 失败时仅回退当前分片，避免整段超长重发再次失败。
                    if (markdown) {
                        try {
                            sendChunk(chatId, chunk, false);
                        } catch (TelegramApiException fallbackError) {
                            log.error("发送 Telegram 消息纯文本兜底失败: {}", fallbackError.getMessage(), fallbackError);
                        }
                    } else {
                        break;
                    }
                }
            }
        }

        /** 发送单个 Telegram 消息分片。 */
        private void sendChunk(long chatId, String chunk, boolean markdown) throws TelegramApiException {
            SendMessage msg = new SendMessage();
            msg.setChatId(String.valueOf(chatId));
            msg.setText(chunk);
            if (markdown) {
                msg.setParseMode("Markdown");
            }
            msg.setDisableWebPagePreview(true);
            execute(msg);
        }

        /** 将长消息拆分为多个 Telegram 可接收的分片。 */
        private List<String> splitForTelegram(String text) {
            if (text == null || text.isEmpty()) {
                return List.of();
            }
            if (text.length() <= MAX_MESSAGE_LENGTH) {
                return List.of(text);
            }

            ArrayList<String> parts = new ArrayList<>();
            int start = 0;
            while (start < text.length()) {
                int remaining = text.length() - start;
                if (remaining <= MAX_MESSAGE_LENGTH) {
                    parts.add(text.substring(start));
                    break;
                }

                int end = start + MAX_MESSAGE_LENGTH;
                int lineBreak = text.lastIndexOf('\n', end);
                if (lineBreak <= start + 80) {
                    lineBreak = end;
                }
                parts.add(text.substring(start, lineBreak));
                start = lineBreak;
                while (start < text.length() && text.charAt(start) == '\n') {
                    start++;
                }
            }
            return parts;
        }

        /** 转义 Telegram Markdown 保留字符，避免消息渲染失败。 */
        private String escapeMarkdown(String text) {
            if (text == null)
                return "";
            return text.replace("_", "\\_")
                    .replace("*", "\\*")
                    .replace("[", "\\[")
                    .replace("]", "\\]")
                    .replace("`", "\\`");
        }
    }
}
