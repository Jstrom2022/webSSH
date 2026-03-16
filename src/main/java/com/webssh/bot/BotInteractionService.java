package com.webssh.bot;

import com.webssh.session.SshSessionProfile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
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

    private static final int AI_OUTPUT_CACHE_LIMIT = 6000;

    private final BotSshSessionManager sshManager;
    private final AiCliExecutor aiCliExecutor;
    private final ConcurrentMap<String, AiTaskState> aiTaskStates = new ConcurrentHashMap<>();

    public record ConnectionStatus(boolean connected, String profileName, String cwd) {
    }

    public record DisconnectResult(boolean disconnected, String profileName) {
    }

    public record StartAiTaskResult(boolean started, String message, String workDir) {
    }

    public record AiTaskSnapshot(boolean running, String lastOutput, String workDir, String prompt,
            Instant updatedAt) {
        public boolean hasOutput() {
            return lastOutput != null && !lastOutput.isBlank();
        }
    }

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

    public BotInteractionService(BotSshSessionManager sshManager, AiCliExecutor aiCliExecutor) {
        this.sshManager = sshManager;
        this.aiCliExecutor = aiCliExecutor;
    }

    public List<SshSessionProfile> listProfiles(String sshUsername) {
        return sshManager.listProfiles(sshUsername);
    }

    public String connect(String botType, String userId, String sshUsername, String target) throws Exception {
        return sshManager.connect(botType, userId, sshUsername, target);
    }

    public DisconnectResult disconnect(String botType, String userId) {
        String userKey = userKey(botType, userId);
        aiCliExecutor.stopAllForUser(userKey);
        aiCliExecutor.clearAllSessions(userKey);
        aiTaskStates.keySet().removeIf(key -> key.endsWith(":" + userKey));

        BotSshSessionManager.SshConnection conn = sshManager.getConnection(botType, userId);
        if (conn == null) {
            return new DisconnectResult(false, null);
        }
        String profileName = conn.getProfileName();
        sshManager.disconnect(botType, userId);
        return new DisconnectResult(true, profileName);
    }

    public void disconnectAll(String botType) {
        sshManager.disconnectAll(botType);
        aiCliExecutor.stopAllForBotType(botType);
        aiTaskStates.keySet().removeIf(key -> key.contains(":" + botType + ":"));
    }

    public ConnectionStatus getConnectionStatus(String botType, String userId) {
        BotSshSessionManager.SshConnection conn = sshManager.getConnection(botType, userId);
        if (conn == null) {
            return new ConnectionStatus(false, null, null);
        }
        return new ConnectionStatus(true, conn.getProfileName(), conn.getCwd());
    }

    public void executeShellCommand(String botType, String userId, String command, Consumer<String> callback) {
        sshManager.executeCommand(botType, userId, command, callback);
    }

    public java.util.concurrent.CompletableFuture<String> executeShellCommandAsync(String botType, String userId,
            String command) {
        return sshManager.executeCommandAsync(botType, userId, command);
    }

    public StartAiTaskResult startAiTask(String botType, String userId, String prompt, AiCliExecutor.CliType cliType,
            Consumer<String> outputCallback, Runnable onComplete) {
        if (prompt == null || prompt.isBlank()) {
            return new StartAiTaskResult(false, "提示词不能为空", null);
        }

        String userKey = userKey(botType, userId);
        if (aiCliExecutor.isRunning(cliType, userKey)) {
            return new StartAiTaskResult(false, "已有任务在运行", null);
        }

        BotSshSessionManager.SshConnection conn = sshManager.getConnection(botType, userId);
        if (conn == null) {
            return new StartAiTaskResult(false, "未连接 SSH。请先使用 /connect 连接。", null);
        }

        String workDir = "/tmp";
        if (conn.getCwd() != null && !conn.getCwd().isBlank()) {
            workDir = conn.getCwd();
        }

        String stateKey = aiStateKey(cliType, userKey);
        AiTaskState state = aiTaskStates.computeIfAbsent(stateKey, key -> new AiTaskState());
        state.start(prompt, workDir);

        aiCliExecutor.executeRemote(cliType, userKey, prompt, workDir,
                command -> sshManager.startRemoteCommand(botType, userId, command),
                chunk -> {
            state.append(chunk);
            if (outputCallback != null) {
                outputCallback.accept(chunk);
            }
        }, () -> {
            state.markCompleted();
            if (onComplete != null) {
                onComplete.run();
            }
        });

        return new StartAiTaskResult(true, "任务已启动", workDir);
    }

    public boolean stopAiTask(String botType, String userId, AiCliExecutor.CliType cliType) {
        String userKey = userKey(botType, userId);
        boolean stopped = aiCliExecutor.stop(cliType, userKey);
        if (stopped) {
            aiTaskStates.computeIfAbsent(aiStateKey(cliType, userKey), key -> new AiTaskState())
                    .markStopped("🛑 " + cliType.getDisplayName() + " 任务已停止。");
        }
        return stopped;
    }

    public void clearAiSession(String botType, String userId, AiCliExecutor.CliType cliType) {
        String userKey = userKey(botType, userId);
        aiCliExecutor.clearSession(cliType, userKey);
        aiTaskStates.remove(aiStateKey(cliType, userKey));
    }

    public boolean isAiTaskRunning(String botType, String userId, AiCliExecutor.CliType cliType) {
        return aiCliExecutor.isRunning(cliType, userKey(botType, userId));
    }

    public AiTaskSnapshot getAiTaskSnapshot(String botType, String userId, AiCliExecutor.CliType cliType) {
        AiTaskState state = aiTaskStates.get(aiStateKey(cliType, userKey(botType, userId)));
        if (state == null) {
            return new AiTaskSnapshot(false, "", "/tmp", "", Instant.EPOCH);
        }
        return state.snapshot();
    }

    private String userKey(String botType, String userId) {
        return botType + ":" + userId;
    }

    private String aiStateKey(AiCliExecutor.CliType cliType, String userKey) {
        return cliType.name() + ":" + userKey;
    }
}
