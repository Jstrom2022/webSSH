package com.webssh.bot;

import com.webssh.session.SshSessionProfile;
import com.webssh.task.UserResourceGovernor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * 机器人通用交互服务。
 * <p>
 * 统一封装 SSH 会话操作与 AI CLI 任务状态缓存，供不同平台的 Provider 复用，
 * 避免 Telegram、QQ 等机器人重复实现同一套业务逻辑。
 * </p>
 */
@Service
public class BotInteractionService {

    /** 单个 AI 任务在内存中缓存的最大字符数，超出后仅保留最新内容。 */
    private static final int AI_OUTPUT_CACHE_LIMIT = 6000;

    private final BotSshSessionManager sshManager;
    private final AiCliExecutor aiCliExecutor;
    private final UserResourceGovernor resourceGovernor;
    private final ConcurrentMap<String, AiTaskState> aiTaskStates = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AiCliExecutor.CliType> aiModes = new ConcurrentHashMap<>();

    /** SSH 连接状态快照。 */
    public record ConnectionStatus(boolean connected, String profileName, String cwd) {
    }

    /** 断开连接操作结果。 */
    public record DisconnectResult(boolean disconnected, String profileName) {
    }

    /** 启动 AI 任务结果。 */
    public record StartAiTaskResult(boolean started, String message, String workDir) {
    }

    /** AI 任务状态快照，用于状态查询命令。 */
    public record AiTaskSnapshot(boolean running, String lastOutput, String workDir, String prompt,
            Instant updatedAt) {
        /** 是否存在可展示输出。 */
        public boolean hasOutput() {
            return lastOutput != null && !lastOutput.isBlank();
        }
    }

    /**
     * AI 任务内部状态。
     * <p>
     * 采用 synchronized 保护 StringBuilder，避免并发回调时输出乱序或内容损坏。
     * </p>
     */
    private static final class AiTaskState {
        private final StringBuilder output = new StringBuilder();
        private volatile boolean running;
        private volatile String workDir = "/tmp";
        private volatile String prompt = "";
        private volatile Instant updatedAt = Instant.now();

        synchronized void start(String prompt, String workDir) {
            output.setLength(0);
            this.running = true;
            this.prompt = prompt == null ? "" : prompt;
            this.workDir = (workDir == null || workDir.isBlank()) ? "/tmp" : workDir;
            this.updatedAt = Instant.now();
        }

        synchronized void append(String chunk) {
            if (chunk == null || chunk.isBlank()) {
                return;
            }
            if (output.length() > 0) {
                output.append('\n');
            }
            output.append(chunk);
            if (output.length() > AI_OUTPUT_CACHE_LIMIT) {
                // 仅截断最旧内容，保留最新上下文，利于 /status 查看近期输出。
                output.delete(0, output.length() - AI_OUTPUT_CACHE_LIMIT);
            }
            updatedAt = Instant.now();
        }

        void markCompleted() {
            running = false;
            updatedAt = Instant.now();
        }

        void markStopped(String message) {
            running = false;
            append(message);
            updatedAt = Instant.now();
        }

        synchronized AiTaskSnapshot snapshot() {
            return new AiTaskSnapshot(running, output.toString(), workDir, prompt, updatedAt);
        }
    }

    public BotInteractionService(BotSshSessionManager sshManager, AiCliExecutor aiCliExecutor,
            UserResourceGovernor resourceGovernor) {
        this.sshManager = sshManager;
        this.aiCliExecutor = aiCliExecutor;
        this.resourceGovernor = resourceGovernor;
    }

    /** 列出指定 WebSSH 用户已保存的 SSH 会话。 */
    public List<SshSessionProfile> listProfiles(String sshUsername) {
        return sshManager.listProfiles(sshUsername);
    }

    /** 按会话名/序号连接 SSH。 */
    public String connect(String botType, String userId, String sshUsername, String target) throws Exception {
        return sshManager.connect(botType, userId, sshUsername, target);
    }

    /**
     * 断开指定聊天用户连接，并同步清理 AI 任务状态。
     * <p>
     * 这里不仅断 SSH，还会停止该用户所有 AI CLI 任务，避免“连接已断但任务仍跑”。
     * </p>
     */
    public DisconnectResult disconnect(String botType, String userId) {
        String userKey = userKey(botType, userId);
        aiCliExecutor.stopAllForUser(userKey);
        aiCliExecutor.clearAllSessions(userKey);
        aiTaskStates.keySet().removeIf(key -> key.endsWith(":" + userKey));
        aiModes.remove(userKey);

        BotSshSessionManager.SshConnection conn = sshManager.getConnection(botType, userId);
        if (conn == null) {
            return new DisconnectResult(false, null);
        }
        String profileName = conn.getProfileName();
        sshManager.disconnect(botType, userId);
        return new DisconnectResult(true, profileName);
    }

    /** 按机器人类型断开所有用户连接并清理对应 AI 状态。 */
    public void disconnectAll(String botType) {
        sshManager.disconnectAll(botType);
        aiCliExecutor.stopAllForBotType(botType);
        aiTaskStates.keySet().removeIf(key -> key.contains(":" + botType + ":"));
        aiModes.keySet().removeIf(key -> key.startsWith(botType + ":"));
    }

    /** 查询当前用户 SSH 连接状态。 */
    public ConnectionStatus getConnectionStatus(String botType, String userId) {
        BotSshSessionManager.SshConnection conn = sshManager.getConnection(botType, userId);
        if (conn == null) {
            return new ConnectionStatus(false, null, null);
        }
        return new ConnectionStatus(true, conn.getProfileName(), conn.getCwd());
    }

    /** 执行 Shell 命令并通过回调分段返回。 */
    public void executeShellCommand(String botType, String userId, String command, Consumer<String> callback) {
        String userKey = userKey(botType, userId);
        if (!resourceGovernor.allowBotMessage(userKey)) {
            callback.accept("❌ 请求过于频繁，请稍后再试。");
            return;
        }
        sshManager.executeCommand(botType, userId, command, callback);
    }

    /** 异步执行 Shell 命令并返回完整输出。 */
    public java.util.concurrent.CompletableFuture<String> executeShellCommandAsync(String botType, String userId,
            String command) {
        String userKey = userKey(botType, userId);
        if (!resourceGovernor.allowBotMessage(userKey)) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("请求过于频繁，请稍后再试。"));
        }
        return sshManager.executeCommandAsync(botType, userId, command);
    }

    /**
     * 启动 AI CLI 任务。
     * <p>
     * 要求当前用户已连接 SSH；任务实际在远端机器执行，状态在本地内存缓存用于 /status 查询。
     * </p>
     */
    public StartAiTaskResult startAiTask(String botType, String userId, String prompt, AiCliExecutor.CliType cliType,
            Consumer<String> outputCallback, Runnable onComplete) {
        if (prompt == null || prompt.isBlank()) {
            return new StartAiTaskResult(false, "提示词不能为空", null);
        }
        if (isClaudeLoginShortcut(cliType, prompt)) {
            return new StartAiTaskResult(false,
                    "检测到登录指令。请先在 SSH 主机执行 `claude auth login` 完成授权，再回到 /claude 模式提问。",
                    null);
        }

        String userKey = userKey(botType, userId);
        if (!resourceGovernor.allowBotMessage(userKey)) {
            return new StartAiTaskResult(false, "请求过于频繁，请稍后再试。", null);
        }
        if (aiCliExecutor.isRunning(cliType, userKey)) {
            return new StartAiTaskResult(false, "已有任务在运行", null);
        }
        UserResourceGovernor.Permit aiPermit = resourceGovernor.tryAcquireAiTask(userKey);
        if (!aiPermit.granted()) {
            return new StartAiTaskResult(false, "当前已有 AI 任务在运行，请等待结束后再试。", null);
        }

        BotSshSessionManager.SshConnection conn = sshManager.getConnection(botType, userId);
        if (conn == null) {
            aiPermit.close();
            return new StartAiTaskResult(false, "未连接 SSH。请先使用 /connect 连接。", null);
        }

        String workDir = "/tmp";
        if (conn.getCwd() != null && !conn.getCwd().isBlank()) {
            workDir = conn.getCwd();
        }

        // 先初始化缓存状态，再启动任务，保证回调到达前状态已就绪。
        String stateKey = aiStateKey(cliType, userKey);
        AiTaskState state = aiTaskStates.computeIfAbsent(stateKey, key -> new AiTaskState());
        state.start(prompt, workDir);

        boolean accepted = aiCliExecutor.executeRemote(cliType, userKey, prompt, workDir,
                command -> sshManager.startRemoteCommand(botType, userId, command),
                chunk -> {
            state.append(chunk);
            if (outputCallback != null) {
                outputCallback.accept(chunk);
            }
        }, () -> {
            try {
                state.markCompleted();
                if (onComplete != null) {
                    onComplete.run();
                }
            } finally {
                aiPermit.close();
            }
        });
        if (!accepted) {
            aiPermit.close();
            state.markStopped("❌ 系统繁忙，请稍后再试。");
            return new StartAiTaskResult(false, "系统繁忙，请稍后再试。", null);
        }

        return new StartAiTaskResult(true, "任务已启动", workDir);
    }

    /** Claude 模式下拦截误发的登录快捷词，避免被当作普通提示词执行。 */
    private boolean isClaudeLoginShortcut(AiCliExecutor.CliType cliType, String prompt) {
        if (cliType != AiCliExecutor.CliType.CLAUDE || prompt == null) {
            return false;
        }
        String normalized = prompt.trim().toLowerCase(Locale.ROOT);
        return "login".equals(normalized) || "/login".equals(normalized);
    }

    /** 停止 AI 任务，并向缓存中写入“已停止”提示。 */
    public boolean stopAiTask(String botType, String userId, AiCliExecutor.CliType cliType) {
        String userKey = userKey(botType, userId);
        boolean stopped = aiCliExecutor.stop(cliType, userKey);
        if (stopped) {
            aiTaskStates.computeIfAbsent(aiStateKey(cliType, userKey), key -> new AiTaskState())
                    .markStopped("🛑 " + cliType.getDisplayName() + " 任务已停止。");
        }
        return stopped;
    }

    /** 清空指定 AI 工具的会话上下文及缓存输出。 */
    public void clearAiSession(String botType, String userId, AiCliExecutor.CliType cliType) {
        String userKey = userKey(botType, userId);
        aiCliExecutor.clearSession(cliType, userKey);
        aiTaskStates.remove(aiStateKey(cliType, userKey));
    }

    /** 判断指定 AI 工具是否仍在执行。 */
    public boolean isAiTaskRunning(String botType, String userId, AiCliExecutor.CliType cliType) {
        return aiCliExecutor.isRunning(cliType, userKey(botType, userId));
    }

    /** 获取指定 AI 工具的最新快照。 */
    public AiTaskSnapshot getAiTaskSnapshot(String botType, String userId, AiCliExecutor.CliType cliType) {
        AiTaskState state = aiTaskStates.get(aiStateKey(cliType, userKey(botType, userId)));
        if (state == null) {
            return new AiTaskSnapshot(false, "", "/tmp", "", Instant.EPOCH);
        }
        return state.snapshot();
    }

    /** 进入指定 AI 模式；后续普通输入将按该工具执行。 */
    public void enterAiMode(String botType, String userId, AiCliExecutor.CliType cliType) {
        if (cliType == null) {
            return;
        }
        aiModes.put(userKey(botType, userId), cliType);
    }

    /** 退出 AI 模式，恢复普通输入路由。 */
    public void exitAiMode(String botType, String userId) {
        aiModes.remove(userKey(botType, userId));
    }

    /** 获取当前 AI 模式；返回 null 表示未处于 AI 模式。 */
    public AiCliExecutor.CliType getAiMode(String botType, String userId) {
        return aiModes.get(userKey(botType, userId));
    }

    /** 当前用户是否处于任意 AI 模式。 */
    public boolean isInAiMode(String botType, String userId) {
        return getAiMode(botType, userId) != null;
    }

    /** 生成用户唯一键，格式：botType:userId。 */
    private String userKey(String botType, String userId) {
        return botType + ":" + userId;
    }

    /** 生成 AI 状态键，格式：cliType:botType:userId。 */
    private String aiStateKey(AiCliExecutor.CliType cliType, String userKey) {
        return cliType.name() + ":" + userKey;
    }
}
