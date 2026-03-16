package com.webssh.bot.telegram;

import com.webssh.bot.BotSettings;
import com.webssh.bot.BotSshSessionManager;
import com.webssh.bot.ChatBotProvider;
import com.webssh.bot.AiCliExecutor;
import com.webssh.session.SshSessionProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

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

    private final BotSshSessionManager sshManager;
    private final AiCliExecutor aiCliExecutor;

    private volatile TelegramBotsApi botsApi;
    private volatile InternalBot bot;
    private volatile boolean running = false;
    private volatile String statusMessage = "未启动";
    private volatile DefaultBotSession botSession;

    public TelegramBotProvider(BotSshSessionManager sshManager, AiCliExecutor aiCliExecutor) {
        this.sshManager = sshManager;
        this.aiCliExecutor = aiCliExecutor;
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
            bot = new InternalBot(token, botUsername, allowedUsers, sshUsername);
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
        sshManager.disconnectAll(TYPE);
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
     * 内部 Telegram Bot 实现，处理消息接收和命令分发。
     */
    private class InternalBot extends TelegramLongPollingBot {

        private final String botUsername;
        private final Set<String> allowedUsers;
        private final String sshUsername;

        InternalBot(String token, String botUsername, Set<String> allowedUsers, String sshUsername) {
            super(token);
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
            if (!update.hasMessage() || !update.getMessage().hasText()) {
                return;
            }

            Message message = update.getMessage();
            long chatId = message.getChatId();
            String userId = String.valueOf(message.getFrom().getId());
            String text = message.getText().trim();

            // 鉴权检查
            if (!allowedUsers.isEmpty() && !allowedUsers.contains(userId)) {
                sendText(chatId, "⛔ 你没有权限使用此机器人。\n你的用户 ID: " + userId);
                return;
            }

            try {
                if (text.startsWith("/")) {
                    handleCommand(chatId, userId, text);
                } else {
                    handleShellInput(chatId, userId, text);
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
                case "/codex" -> handleAiCli(chatId, userId, arg, AiCliExecutor.CliType.CODEX);
                case "/codex_stop" -> handleAiCliStop(chatId, userId, AiCliExecutor.CliType.CODEX);
                case "/codex_status" -> handleAiCliStatus(chatId, userId, AiCliExecutor.CliType.CODEX);
                case "/codex_clear" -> handleAiCliClear(chatId, userId, AiCliExecutor.CliType.CODEX);
                case "/claude" -> handleAiCli(chatId, userId, arg, AiCliExecutor.CliType.CLAUDE);
                case "/claude_stop" -> handleAiCliStop(chatId, userId, AiCliExecutor.CliType.CLAUDE);
                case "/claude_status" -> handleAiCliStatus(chatId, userId, AiCliExecutor.CliType.CLAUDE);
                case "/claude_clear" -> handleAiCliClear(chatId, userId, AiCliExecutor.CliType.CLAUDE);
                default -> sendText(chatId, "未知命令: " + cmd + "\n使用 /help 查看可用命令。");
            }
        }

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
                    /codex <提示词> — Codex AI 任务
                    /codex\\_stop — 停止 Codex 任务
                    /codex\\_clear — 清除 Codex 会话 ID
                    /claude <提示词> — Claude Code 任务
                    /claude\\_stop — 停止 Claude 任务
                    /claude\\_clear — 清除 Claude 会话 ID

                    连接后直接发送文字即执行 Shell 命令。""", true);
        }

        private void handleList(long chatId) {
            List<SshSessionProfile> profiles = sshManager.listProfiles(sshUsername);
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

        private void handleConnect(long chatId, String userId, String target) {
            if (target.isEmpty()) {
                sendText(chatId, "用法: /connect <会话名称或序号>\n例如: `/connect 1` 或 `/connect MyVPS`", true);
                return;
            }

            sendText(chatId, "⏳ 正在连接...");
            try {
                String result = sshManager.connect(TYPE, userId, sshUsername, target);
                sendText(chatId, result);
            } catch (Exception e) {
                sendText(chatId, "❌ 连接失败: " + e.getMessage());
            }
        }

        private void handleDisconnect(long chatId, String userId) {
            BotSshSessionManager.SshConnection conn = sshManager.getConnection(TYPE, userId);
            if (conn == null) {
                sendText(chatId, "当前没有活跃的 SSH 连接。");
                return;
            }
            String name = conn.getProfileName();
            sshManager.disconnect(TYPE, userId);
            sendText(chatId, "🔌 已断开与 " + name + " 的连接。");
        }

        private void handleStatus(long chatId, String userId) {
            BotSshSessionManager.SshConnection conn = sshManager.getConnection(TYPE, userId);
            if (conn == null) {
                sendText(chatId, "📊 状态: 未连接\n使用 /list 查看可用会话，/connect 连接。");
            } else {
                sendText(chatId, "📊 状态: 已连接到 *" + escapeMarkdown(conn.getProfileName()) + "*\n"
                        + "直接发送文字执行命令，/disconnect 断开。", true);
            }
        }

        private void handleShellInput(long chatId, String userId, String text) {
            BotSshSessionManager.SshConnection conn = sshManager.getConnection(TYPE, userId);
            if (conn == null) {
                sendText(chatId, "未连接 SSH。请先使用 /connect 连接。\n使用 /list 查看可用会话。");
                return;
            }

            sshManager.executeCommand(TYPE, userId, text, output -> {
                sendText(chatId, "```\n" + output + "\n```", true);
            });
        }

        // ========== AI CLI 通用命令 ==========

        private void handleAiCli(long chatId, String userId, String prompt, AiCliExecutor.CliType cliType) {
            String name = cliType.getDisplayName();
            String cmdName = cliType.name().toLowerCase();

            if (prompt.isEmpty()) {
                sendText(chatId, "用法: /" + cmdName + " <提示词>\n例如: `/" + cmdName + " 分析当前项目结构`", true);
                return;
            }

            String userKey = TYPE + ":" + userId;
            if (aiCliExecutor.isRunning(cliType, userKey)) {
                sendText(chatId, "⚠️ 已有 " + name + " 任务在运行。\n使用 /" + cmdName + "\\_stop 停止后再试。", true);
                return;
            }

            // 工作目录：优先使用 SSH 会话追踪到的当前目录，未连接时使用 /tmp
            String workDir = "/tmp";
            BotSshSessionManager.SshConnection conn = sshManager.getConnection(TYPE, userId);
            if (conn != null && conn.getCwd() != null) {
                workDir = conn.getCwd();
            }

            aiCliExecutor.execute(cliType, userKey, prompt, workDir,
                    output -> sendText(chatId, output, true),
                    () -> log.debug("{} 任务结束 [{}]", name, userKey));
        }

        private void handleAiCliClear(long chatId, String userId, AiCliExecutor.CliType cliType) {
            String userKey = TYPE + ":" + userId;
            aiCliExecutor.clearSession(cliType, userKey);
            sendText(chatId, "✨ " + cliType.getDisplayName() + " 的会话 ID 已清除。");
        }

        private void handleAiCliStop(long chatId, String userId, AiCliExecutor.CliType cliType) {
            String userKey = TYPE + ":" + userId;
            if (aiCliExecutor.stop(cliType, userKey)) {
                sendText(chatId, "🛑 " + cliType.getDisplayName() + " 任务已停止。");
            } else {
                sendText(chatId, "当前没有正在运行的 " + cliType.getDisplayName() + " 任务。");
            }
        }

        private void handleAiCliStatus(long chatId, String userId, AiCliExecutor.CliType cliType) {
            String userKey = TYPE + ":" + userId;
            String name = cliType.getDisplayName();
            String cmdName = cliType.name().toLowerCase();
            if (aiCliExecutor.isRunning(cliType, userKey)) {
                sendText(chatId, "📊 " + name + " 状态: ⏳ 任务执行中...");
            } else {
                sendText(chatId, "📊 " + name + " 状态: 空闲\n使用 `/" + cmdName + " <提示词>` 启动任务。", true);
            }
        }

        private void sendText(long chatId, String text) {
            sendText(chatId, text, false);
        }

        private void sendText(long chatId, String text, boolean markdown) {
            try {
                // 分批发送超长消息
                String content = text;
                while (!content.isEmpty()) {
                    String chunk;
                    if (content.length() > 4000) {
                        int splitAt = content.lastIndexOf('\n', 4000);
                        if (splitAt <= 0)
                            splitAt = 4000;
                        chunk = content.substring(0, splitAt);
                        content = content.substring(splitAt);
                    } else {
                        chunk = content;
                        content = "";
                    }
                    SendMessage msg = new SendMessage();
                    msg.setChatId(String.valueOf(chatId));
                    msg.setText(chunk);
                    if (markdown) {
                        msg.setParseMode("Markdown");
                    }
                    msg.setDisableWebPagePreview(true);
                    execute(msg);
                }
            } catch (TelegramApiException e) {
                log.error("发送 Telegram 消息失败: {}", e.getMessage(), e);
                // 如果 Markdown 解析失败，尝试不用 Markdown 重发
                if (markdown) {
                    try {
                        SendMessage fallback = new SendMessage();
                        fallback.setChatId(String.valueOf(chatId));
                        fallback.setText(text);
                        fallback.setDisableWebPagePreview(true);
                        execute(fallback);
                    } catch (TelegramApiException ignored) {
                    }
                }
            }
        }

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
