package com.webssh.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webssh.config.ResourceGovernanceProperties;
import com.webssh.task.BoundedExecutorFactory;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * AI CLI 统一进程管理器 — 同时支持 Codex CLI 和 Claude Code。
 * <p>
 * 通过各自的非交互模式启动子进程，异步解析 JSON 事件流，
 * 将有意义的输出实时回传给调用方。
 * 每个用户每种工具最多维护一个进程。
 * </p>
 */
@Service
public class AiCliExecutor {

    private static final Logger log = LoggerFactory.getLogger(AiCliExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 当远端未提供 UTF-8 locale 时，默认兜底值。 */
    private static final String DEFAULT_SHELL_UTF8_LOCALE = "en_US.UTF-8";
    /** 远端命令执行前强制 UTF-8 locale 的脚本。 */
    private static final String UTF8_LOCALE_BOOTSTRAP_SCRIPT =
            "case \"${LC_ALL:-${LC_CTYPE:-$LANG}}\" in *[Uu][Tt][Ff]-8*) ;; *) "
                    + "export LANG=" + DEFAULT_SHELL_UTF8_LOCALE
                    + " LC_ALL=" + DEFAULT_SHELL_UTF8_LOCALE
                    + " LC_CTYPE=" + DEFAULT_SHELL_UTF8_LOCALE
                    + " ;; esac; ";
    /** ANSI/VT100 控制序列（CSI/OSC/DCS/单字符 ESC）。 */
    private static final Pattern ANSI_ESCAPE_PATTERN = Pattern.compile(
            "\\u001B\\[[0-?]*[ -/]*[@-~]" // CSI
                    + "|\\u001B\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)" // OSC
                    + "|\\u001B[P_X^_][^\\u001B]*(?:\\u001B\\\\)" // DCS/SOS/PM/APC
                    + "|\\u001B[@-_]"); // 单字符 ESC
    /** 某些平台吞掉 ESC 后遗留的终端控制片段。 */
    private static final Pattern ORPHAN_TERMINAL_FRAGMENT_PATTERN = Pattern.compile(
            "\\[\\?[0-9;:]*[a-zA-Z]" // 例如 [?1004l
                    + "|\\[<u" // 例如 [<u
                    + "|\\][0-9;:]*;[^\\s]*"); // 例如 ]9;4;0;

    /**
     * 支持的 AI CLI 工具类型。
     */
    public enum CliType {
        CODEX("Codex", "codex", new String[] {
                "/opt/homebrew/bin/codex",
                "/usr/local/bin/codex",
                "/usr/bin/codex",
                System.getProperty("user.home") + "/.local/bin/codex",
                System.getProperty("user.home") + "/.npm-global/bin/codex"
        }),
        CLAUDE("Claude Code", "claude", new String[] {
                "/usr/local/bin/claude",
                "/opt/homebrew/bin/claude",
                "/usr/bin/claude",
                System.getProperty("user.home") + "/.npm-global/bin/claude",
                System.getProperty("user.home") + "/.local/bin/claude"
        });

        private final String displayName;
        private final String commandName;
        private final String[] fallbackBins;

        CliType(String displayName, String commandName, String[] fallbackBins) {
            this.displayName = displayName;
            this.commandName = commandName;
            this.fallbackBins = fallbackBins;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getCommandName() {
            return commandName;
        }

        public String[] getFallbackBins() {
            return fallbackBins.clone();
        }
    }

    /** 正在运行的进程，key = cliType:botType:userId */
    private final ConcurrentMap<String, Process> runningProcesses = new ConcurrentHashMap<>();
    /** 正在运行的远端任务，key = cliType:botType:userId */
    private final ConcurrentMap<String, BotSshSessionManager.RemoteCommandHandle> runningRemoteCommands = new ConcurrentHashMap<>();

    /** 用户的会话 ID，key = cliType:botType:userId */
    private final ConcurrentMap<String, String> userSessionIds = new ConcurrentHashMap<>();
    /** 记录最近一次 Claude assistant 文本，用于 result 事件去重。 */
    private final ConcurrentMap<String, String> lastClaudeAssistantTexts = new ConcurrentHashMap<>();
    /** Codex 流式输出已发送字符数，用于 item.updated 增量推送。 */
    private final ConcurrentMap<String, Integer> codexStreamedLengths = new ConcurrentHashMap<>();
    /** 启动后缓存的 CLI 可执行路径，key = cliType。 */
    private final ConcurrentMap<CliType, String> resolvedBins = new ConcurrentHashMap<>();
    /** AI 任务治理配置。 */
    private final ResourceGovernanceProperties resourceProperties;

    /**
     * 远端命令启动器抽象。
     * <p>
     * 由调用方注入具体的 SSH exec 启动实现，使当前类仅关注命令构建与事件解析。
     * </p>
     */
    @FunctionalInterface
    public interface RemoteCommandStarter {
        BotSshSessionManager.RemoteCommandHandle start(String command) throws Exception;
    }

    /** AI 任务执行线程池，避免阻塞机器人消息处理线程。 */
    private final ExecutorService executor;
    /** AI 任务超时调度器。 */
    private final ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ai-cli-timeout");
        t.setDaemon(true);
        return t;
    });

    public AiCliExecutor(ResourceGovernanceProperties resourceProperties) {
        this.resourceProperties = resourceProperties;
        this.executor = BoundedExecutorFactory.newExecutor("ai-cli-exec-",
                resourceProperties.getAiTask());
        detectCliBinariesAtStartup();
    }

    /** 应用销毁前关闭所有任务并回收线程。 */
    @PreDestroy
    public void shutdown() {
        runningProcesses.values().forEach(Process::destroyForcibly);
        runningRemoteCommands.values().forEach(BotSshSessionManager.RemoteCommandHandle::stop);
        executor.shutdownNow();
        timeoutScheduler.shutdownNow();
    }

    /**
     * 执行 AI CLI 任务。
     *
     * @param cliType        AI 工具类型
     * @param userKey        用户唯一标识（botType:userId）
     * @param prompt         用户提示词
     * @param workDir        工作目录（可为 null）
     * @param outputCallback 输出回调
     * @param onComplete     任务结束回调
     */
    public boolean execute(CliType cliType, String userKey, String prompt, String workDir,
            Consumer<String> outputCallback, Runnable onComplete) {
        String processKey = processKey(cliType, userKey);
        lastClaudeAssistantTexts.remove(processKey);
        codexStreamedLengths.remove(processKey);
        // 如果已有任务在运行，先停止
        stop(cliType, userKey);

        try {
            executor.submit(() -> {
                Process process = null;
                ScheduledFuture<?> timeoutFuture = null;
                AtomicBoolean timedOut = new AtomicBoolean(false);
                try {
                    List<String> cmd = buildCommand(cliType, prompt, workDir, processKey);
                    // 检查二进制是否存在（尝试寻找）
                    String resolvedBin = resolveBin(cliType);
                    if (resolvedBin == null) {
                        outputCallback.accept("❌ 未找到 " + cliType.getDisplayName() + " CLI。\n请先安装后再使用。");
                        return;
                    }
                    cmd.set(0, resolvedBin);

                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.redirectErrorStream(true);
                    // 确保子进程能找到正确的 PATH
                    pb.environment().put("TERM", "dumb");
                    if (workDir != null && !workDir.isBlank()) {
                        pb.directory(new File(workDir));
                    }
                    process = pb.start();
                    runningProcesses.put(processKey, process);
                    Process startedProcess = process;
                    timeoutFuture = scheduleTimeout(() -> {
                        Process active = runningProcesses.get(processKey);
                        if (active == startedProcess && active.isAlive()) {
                            timedOut.set(true);
                            active.destroyForcibly();
                        }
                    });

                    // 关闭标准输入，防止交互式等待导致进程挂起
                    process.getOutputStream().close();

                    log.info("{} 任务已启动 [{}]: {}", cliType.getDisplayName(), userKey, prompt);
                    outputCallback.accept("⏳ " + cliType.getDisplayName() + " 任务已启动...");

                    // 逐行读取输出
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            processLine(cliType, userKey, line, outputCallback);
                        }
                    }

                    int exitCode = process.waitFor();
                    if (timedOut.get()) {
                        log.warn("{} 任务超时 [{}]", cliType.getDisplayName(), userKey);
                        outputCallback.accept("⏱️ " + cliType.getDisplayName() + " 任务执行超时，已停止。");
                    } else {
                        log.info("{} 任务完成 [{}], exit={}", cliType.getDisplayName(), userKey, exitCode);
                        outputCallback.accept("✅ " + cliType.getDisplayName() + " 任务已完成 (exit=" + exitCode + ")");
                    }

                } catch (Exception e) {
                    if (timedOut.get()) {
                        outputCallback.accept("⏱️ " + cliType.getDisplayName() + " 任务执行超时，已停止。");
                    } else if (process != null && !process.isAlive()) {
                        outputCallback.accept("🛑 " + cliType.getDisplayName() + " 任务已停止。");
                    } else {
                        log.error("{} 执行异常 [{}]: {}", cliType.getDisplayName(), userKey, e.getMessage(), e);
                        outputCallback.accept("❌ " + cliType.getDisplayName() + " 执行失败: " + e.getMessage());
                    }
                } finally {
                    cancelTimeout(timeoutFuture);
                    runningProcesses.remove(processKey);
                    lastClaudeAssistantTexts.remove(processKey);
                    codexStreamedLengths.remove(processKey);
                    if (process != null && process.isAlive()) {
                        process.destroyForcibly();
                    }
                    onComplete.run();
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("{} 任务提交被拒绝 [{}]: {}", cliType.getDisplayName(), userKey, e.getMessage());
            return false;
        }
        return true;
    }

    /**
     * 通过已建立的 SSH 会话执行 AI CLI 任务。
     *
     * @param cliType        AI 工具类型
     * @param userKey        用户唯一标识（botType:userId）
     * @param prompt         用户提示词
     * @param workDir        工作目录（可为 null）
     * @param remoteStarter  远端命令启动器
     * @param outputCallback 输出回调
     * @param onComplete     任务结束回调
     */
    public boolean executeRemote(CliType cliType, String userKey, String prompt, String workDir,
            RemoteCommandStarter remoteStarter,
            Consumer<String> outputCallback, Runnable onComplete) {
        String processKey = processKey(cliType, userKey);
        lastClaudeAssistantTexts.remove(processKey);
        codexStreamedLengths.remove(processKey);
        stop(cliType, userKey);

        try {
            executor.submit(() -> {
                BotSshSessionManager.RemoteCommandHandle handle = null;
                ScheduledFuture<?> timeoutFuture = null;
                AtomicBoolean timedOut = new AtomicBoolean(false);
                try {
                    String remoteCommand = buildRemoteCommand(cliType, prompt, workDir, processKey);
                    handle = remoteStarter.start(remoteCommand);
                    runningRemoteCommands.put(processKey, handle);
                    BotSshSessionManager.RemoteCommandHandle startedHandle = handle;
                    timeoutFuture = scheduleTimeout(() -> {
                        BotSshSessionManager.RemoteCommandHandle active = runningRemoteCommands.get(processKey);
                        if (active == startedHandle && active.isRunning()) {
                            timedOut.set(true);
                            active.stop();
                        }
                    });

                    log.info("{} 远端任务已启动 [{}]: {}", cliType.getDisplayName(), userKey, prompt);
                    outputCallback.accept("⏳ " + cliType.getDisplayName() + " 任务已启动...");

                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(handle.output(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (processLine(cliType, userKey, line, outputCallback)) {
                                continue;
                            }
                            String visible = sanitizeRemoteRawLine(line);
                            if (!visible.isBlank()) {
                                outputCallback.accept("🖥️ " + visible);
                            }
                        }
                    }

                    int exitCode = handle.waitForExit();
                    if (timedOut.get()) {
                        log.warn("{} 远端任务超时 [{}]", cliType.getDisplayName(), userKey);
                        outputCallback.accept("⏱️ " + cliType.getDisplayName() + " 任务执行超时，已停止。");
                    } else {
                        log.info("{} 远端任务完成 [{}], exit={}", cliType.getDisplayName(), userKey, exitCode);
                        outputCallback.accept("✅ " + cliType.getDisplayName() + " 任务已完成 (exit=" + exitCode + ")");
                    }
                } catch (Exception e) {
                    BotSshSessionManager.RemoteCommandHandle active = handle;
                    if (timedOut.get()) {
                        outputCallback.accept("⏱️ " + cliType.getDisplayName() + " 任务执行超时，已停止。");
                    } else if (active != null && !active.isRunning()) {
                        outputCallback.accept("🛑 " + cliType.getDisplayName() + " 任务已停止。");
                    } else {
                        log.error("{} 远端执行异常 [{}]: {}", cliType.getDisplayName(), userKey, e.getMessage(), e);
                        outputCallback.accept("❌ " + cliType.getDisplayName() + " 执行失败: " + e.getMessage());
                    }
                } finally {
                    cancelTimeout(timeoutFuture);
                    runningRemoteCommands.remove(processKey);
                    lastClaudeAssistantTexts.remove(processKey);
                    codexStreamedLengths.remove(processKey);
                    if (handle != null && handle.isRunning()) {
                        handle.stop();
                    }
                    onComplete.run();
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("{} 远端任务提交被拒绝 [{}]: {}", cliType.getDisplayName(), userKey, e.getMessage());
            return false;
        }
        return true;
    }

    /** 清除指定用户的 AI 会话上下文 */
    public void clearSession(CliType cliType, String userKey) {
        String key = processKey(cliType, userKey);
        userSessionIds.remove(key);
        log.info("已清除 {} 会话上下文 [{}]", cliType.getDisplayName(), userKey);
    }

    /** 清除指定用户的所有 AI 会话上下文 */
    public void clearAllSessions(String userKey) {
        for (CliType type : CliType.values()) {
            clearSession(type, userKey);
        }
    }

    /** 停止指定用户的 AI CLI 任务 */
    public boolean stop(CliType cliType, String userKey) {
        String processKey = processKey(cliType, userKey);
        lastClaudeAssistantTexts.remove(processKey);
        codexStreamedLengths.remove(processKey);
        Process process = runningProcesses.remove(processKey);
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            log.info("{} 任务已强制终止 [{}]", cliType.getDisplayName(), userKey);
            return true;
        }
        BotSshSessionManager.RemoteCommandHandle remote = runningRemoteCommands.remove(processKey);
        if (remote != null && remote.isRunning()) {
            remote.stop();
            log.info("{} 远端任务已停止 [{}]", cliType.getDisplayName(), userKey);
            return true;
        }
        return false;
    }

    /** 停止指定机器人类型的所有 AI CLI 任务，并清理对应会话上下文 */
    public void stopAllForBotType(String botType) {
        if (botType == null || botType.isBlank()) {
            return;
        }

        runningProcesses.forEach((key, process) -> {
            if (belongsToBotType(key, botType) && process != null && process.isAlive()) {
                process.destroyForcibly();
                runningProcesses.remove(key, process);
            }
        });
        runningRemoteCommands.forEach((key, handle) -> {
            if (belongsToBotType(key, botType) && handle != null && handle.isRunning()) {
                handle.stop();
                runningRemoteCommands.remove(key, handle);
            }
        });
        userSessionIds.keySet().removeIf(key -> belongsToBotType(key, botType));
        lastClaudeAssistantTexts.keySet().removeIf(key -> belongsToBotType(key, botType));
        codexStreamedLengths.keySet().removeIf(key -> belongsToBotType(key, botType));
    }

    /** 停止指定用户的所有 AI 任务（本机与远端）。 */
    public void stopAllForUser(String userKey) {
        if (userKey == null || userKey.isBlank()) {
            return;
        }
        for (CliType type : CliType.values()) {
            stop(type, userKey);
        }
    }

    /** 检查是否有任务在运行 */
    public boolean isRunning(CliType cliType, String userKey) {
        String key = processKey(cliType, userKey);
        Process process = runningProcesses.get(key);
        if (process != null && process.isAlive()) {
            return true;
        }
        BotSshSessionManager.RemoteCommandHandle remote = runningRemoteCommands.get(key);
        return remote != null && remote.isRunning();
    }

    /** 检查是否有任何 AI CLI 任务在运行 */
    public boolean isAnyRunning(String userKey) {
        for (CliType type : CliType.values()) {
            if (isRunning(type, userKey))
                return true;
        }
        return false;
    }

    /** 生成统一任务键，作为进程表和会话表的索引。 */
    private String processKey(CliType cliType, String userKey) {
        return cliType.name() + ":" + userKey;
    }

    private ScheduledFuture<?> scheduleTimeout(Runnable onTimeout) {
        long timeoutMs = resourceProperties.getAiTaskTimeout().toMillis();
        if (timeoutMs <= 0) {
            return null;
        }
        return timeoutScheduler.schedule(onTimeout, timeoutMs, TimeUnit.MILLISECONDS);
    }

    private void cancelTimeout(ScheduledFuture<?> timeoutFuture) {
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
        }
    }

    /** 判断任务键是否属于指定 botType，用于批量停止与清理。 */
    private boolean belongsToBotType(String processKey, String botType) {
        String prefix = ":" + botType + ":";
        return processKey != null && processKey.contains(prefix);
    }

    // ==================== 命令构建 ====================

    /** 构建本地执行命令，并尽量复用历史 sessionId 延续上下文。 */
    private List<String> buildCommand(CliType cliType, String prompt, String workDir, String processKey) {
        String sessionId = userSessionIds.get(processKey);
        return switch (cliType) {
            case CODEX -> buildCodexCommand(prompt, workDir, sessionId);
            case CLAUDE -> buildClaudeCommand(prompt, workDir, sessionId);
        };
    }

    /**
     * 构建远端执行命令字符串。
     * <p>
     * 首先注入“查找 CLI 二进制”的脚本，然后拼接参数并做 shell 转义。
     * </p>
     */
    private String buildRemoteCommand(CliType cliType, String prompt, String workDir, String processKey) {
        List<String> args = new ArrayList<>(buildCommand(cliType, prompt, workDir, processKey));
        if (!args.isEmpty()) {
            args.remove(0);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(UTF8_LOCALE_BOOTSTRAP_SCRIPT);
        sb.append(buildRemoteBinResolveScript(cliType, cliType.getCommandName()));
        sb.append("\"$__webssh_ai_bin\"");
        for (String arg : args) {
            sb.append(' ').append(shellQuote(arg));
        }
        return sb.toString();
    }

    /**
     * 生成远端 CLI 路径探测脚本。
     * <p>
     * 按 command -v、固定路径、用户常见 bin 路径、nvm 路径依次探测；失败返回 127。
     * </p>
     */
    private String buildRemoteBinResolveScript(CliType cliType, String commandName) {
        String[] candidates = switch (cliType) {
            case CODEX -> new String[] {
                    "/opt/homebrew/bin/codex",
                    "/usr/local/bin/codex",
                    "/usr/bin/codex"
            };
            case CLAUDE -> new String[] {
                    "/usr/local/bin/claude",
                    "/opt/homebrew/bin/claude",
                    "/usr/bin/claude"
            };
        };

        StringBuilder sb = new StringBuilder();
        sb.append("export PATH=\"$PATH:/usr/local/bin:/opt/homebrew/bin:$HOME/.local/bin:$HOME/.npm-global/bin:$HOME/.cargo/bin\"; ");
        sb.append("__webssh_ai_bin=\"$(command -v ").append(commandName).append(" 2>/dev/null || true)\"; ");
        sb.append("if [ -z \"$__webssh_ai_bin\" ]; then ");
        sb.append("for __webssh_candidate in");
        for (String candidate : candidates) {
            sb.append(' ').append(shellQuote(candidate));
        }
        sb.append("; do ");
        sb.append("if [ -x \"$__webssh_candidate\" ]; then __webssh_ai_bin=\"$__webssh_candidate\"; break; fi; ");
        sb.append("done; ");
        sb.append("fi; ");
        sb.append("if [ -z \"$__webssh_ai_bin\" ]; then ");
        sb.append("for __webssh_candidate in ");
        sb.append("\"$HOME/.local/bin/").append(commandName).append("\" ");
        sb.append("\"$HOME/.npm-global/bin/").append(commandName).append("\" ");
        sb.append("\"$HOME/.cargo/bin/").append(commandName).append("\"; do ");
        sb.append("if [ -x \"$__webssh_candidate\" ]; then __webssh_ai_bin=\"$__webssh_candidate\"; break; fi; ");
        sb.append("done; ");
        sb.append("fi; ");
        sb.append("if [ -z \"$__webssh_ai_bin\" ]; then ");
        sb.append("for __webssh_candidate in \"$HOME/.nvm/versions/node\"/*/bin/").append(commandName).append("; do ");
        sb.append("if [ -x \"$__webssh_candidate\" ]; then __webssh_ai_bin=\"$__webssh_candidate\"; break; fi; ");
        sb.append("done; ");
        sb.append("fi; ");
        sb.append("if [ -z \"$__webssh_ai_bin\" ]; then ");
        sb.append("echo ")
                .append(shellQuote("❌ 未找到 " + cliType.getDisplayName()
                        + " CLI（命令: " + commandName + "）。请在 SSH 主机安装并确保 PATH 可见。"));
        sb.append("; exit 127; ");
        sb.append("fi; ");
        return sb.toString();
    }

    /** 构建 Codex CLI 命令参数。 */
    private List<String> buildCodexCommand(String prompt, String workDir, String sessionId) {
        List<String> cmd = new ArrayList<>();
        cmd.add(CliType.CODEX.getCommandName());

        cmd.add("exec");
        cmd.add("--full-auto");

        if (workDir != null && !workDir.isBlank()) {
            cmd.add("-C");
            cmd.add(workDir);
        }
        cmd.add("--skip-git-repo-check");

        if (sessionId != null && !sessionId.isBlank()) {
            cmd.add("resume");
            cmd.add(sessionId);
        }

        cmd.add("--json");
        cmd.add("--ephemeral");
        cmd.add(prompt);
        return cmd;
    }

    /** 构建 Claude Code 命令参数。 */
    private List<String> buildClaudeCommand(String prompt, String workDir, String sessionId) {
        List<String> cmd = new ArrayList<>();
        cmd.add(CliType.CLAUDE.getCommandName());
        cmd.add("-p");
        cmd.add(prompt);
        // 机器人场景无交互终端，跳过权限审批以实现完全自动化执行。
        cmd.add("--dangerously-skip-permissions");
        // 新版 Claude CLI 在 --print + stream-json 下要求显式开启 verbose。
        cmd.add("--verbose");
        cmd.add("--output-format");
        cmd.add("stream-json");
        if (sessionId != null && !sessionId.isBlank()) {
            // 续聊应使用 --resume；--session-id 更适合“指定新会话 ID”，可能触发 in-use 冲突。
            cmd.add("--resume");
            cmd.add(sessionId);
        }
        // 工作目录由 ProcessBuilder.directory(...)（本地）或远端 exec 包装脚本中的 cd 统一处理；
        // 避免传递某些 Claude 版本不支持的 --cwd 参数。
        return cmd;
    }

    /** 进行 shell 单引号转义，避免命令拼接时参数被拆分。 */
    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    /** 清理远端原始输出中的控制字符与 cwd 标记，只保留可读文本。 */
    private String sanitizeRemoteRawLine(String line) {
        if (line == null) {
            return "";
        }
        String text = stripTerminalControlSequences(line)
                .replace("\u0002", "")
                .replace("\u0003", "")
                .trim();
        int marker = text.indexOf("__WEBSSH_CWD__:");
        if (marker >= 0) {
            text = text.substring(0, marker).trim();
        }
        return text;
    }

    /** 去除 ANSI/VT100 控制序列及其常见残片。 */
    private String stripTerminalControlSequences(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String normalized = ANSI_ESCAPE_PATTERN.matcher(text).replaceAll("");
        normalized = ORPHAN_TERMINAL_FRAGMENT_PATTERN.matcher(normalized).replaceAll("");
        // 移除不可见控制字符，保留 \t \n \r。
        return normalized.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
    }

    // ==================== 输出解析 ====================

    /**
     * 尝试解析一行 JSON 事件。
     *
     * @return true 表示该行已按事件处理；false 表示应按普通文本回显
     */
    private boolean processLine(CliType cliType, String userKey, String line, Consumer<String> callback) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String normalized = line.trim();
        String processKey = processKey(cliType, userKey);

        // 非 JSON 行
        if (!normalized.startsWith("{")) {
            return processPlainTextLine(cliType, processKey, normalized, callback);
        }

        switch (cliType) {
            case CODEX -> processCodexEvent(processKey, normalized, callback);
            case CLAUDE -> processClaudeEvent(processKey, normalized, callback);
        }
        return true;
    }

    /**
     * 处理非 JSON 文本行中的已知错误模式。
     *
     * @return true 表示该行已被识别并转为友好提示，调用方无需再原样回显。
     */
    private boolean processPlainTextLine(CliType cliType, String processKey, String line, Consumer<String> callback) {
        if (cliType != CliType.CLAUDE) {
            return false;
        }
        return handleClaudeKnownIssueText(processKey, line, callback);
    }

    /**
     * 解析 Codex JSONL 事件。
     */
    private void processCodexEvent(String processKey, String json, Consumer<String> callback) {
        try {
            JsonNode node = MAPPER.readTree(json);
            String type = node.path("type").asText("");

            // 捕获会话 ID
            if (type.equals("thread.started")) {
                String sessionId = node.path("thread_id").asText("");
                if (!sessionId.isBlank()) {
                    userSessionIds.put(processKey, sessionId);
                    log.debug("Codex 会话已启动: {}", sessionId);
                }
            }

            switch (type) {
                case "item.started" -> {
                    // 工具调用开始时展示工具名称，让用户知道 AI 正在操作。
                    JsonNode item = node.path("item");
                    String itemType = item.path("type").asText("");
                    if ("tool_call".equals(itemType)) {
                        String toolName = item.path("name").asText("");
                        String args = item.path("arguments").asText("");
                        if (!toolName.isBlank()) {
                            String summary = formatCodexToolStart(toolName, args);
                            callback.accept("⎡🔧 " + summary + "⎦");
                        }
                    }
                }
                case "item.updated" -> {
                    // agent_message 流式增量推送：只发送新增部分，避免重复。
                    JsonNode item = node.path("item");
                    String itemType = item.path("type").asText("");
                    if ("agent_message".equals(itemType)) {
                        String text = item.path("text").asText("");
                        if (!text.isBlank()) {
                            int lastLen = codexStreamedLengths.getOrDefault(processKey, 0);
                            if (text.length() > lastLen) {
                                String delta = text.substring(lastLen);
                                if (!delta.isBlank()) {
                                    callback.accept(delta);
                                }
                                codexStreamedLengths.put(processKey, text.length());
                            }
                        }
                    }
                }
                case "item.completed" -> {
                    JsonNode item = node.path("item");
                    String itemType = item.path("type").asText("");
                    String text = item.path("text").asText("");
                    if (!text.isBlank()) {
                        // 如果已通过 item.updated 流式发送过，只补发剩余部分。
                        int lastLen = codexStreamedLengths.getOrDefault(processKey, 0);
                        String toSend = lastLen > 0 ? text.substring(Math.min(lastLen, text.length())) : text;
                        if (!toSend.isBlank()) {
                            if ("tool_call".equals(itemType)) {
                                callback.accept("⎡🔧 " + toSend + "⎦");
                            } else {
                                callback.accept(toSend);
                            }
                        }
                    }
                    codexStreamedLengths.remove(processKey);
                }
                case "turn.completed" -> {
                    // turn 完成时附带 token 用量，便于用户了解一次请求消耗。
                    JsonNode usage = node.path("usage");
                    if (!usage.isMissingNode()) {
                        int input = usage.path("input_tokens").asInt(0);
                        int output = usage.path("output_tokens").asInt(0);
                        int cached = usage.path("cached_input_tokens").asInt(0);
                        callback.accept(String.format(
                                "— Token: 输入=%d (缓存=%d), 输出=%d",
                                input, cached, output));
                    }
                }
                default -> log.debug("Codex 事件: {}", type);
            }
        } catch (Exception e) {
            log.debug("解析 Codex 事件失败: {}", e.getMessage());
        }
    }

    /**
     * 解析 Claude Code stream-json 事件。
     */
    private void processClaudeEvent(String processKey, String json, Consumer<String> callback) {
        try {
            JsonNode node = MAPPER.readTree(json);
            String type = node.path("type").asText("");

            switch (type) {
                case "assistant" -> {
                    // 某些版本的 Claude 会在 assistant 消息中包含 session_id
                    String sid = node.path("session_id").asText("");
                    if (!sid.isBlank())
                        userSessionIds.put(processKey, sid);
                    JsonNode message = node.path("message");
                    String assistantText = emitClaudeAssistantMessage(message, callback);
                    if (!assistantText.isBlank()) {
                        lastClaudeAssistantTexts.put(processKey, normalizeClaudeTextForDedup(assistantText));
                    }
                }
                case "result" -> {
                    // result 事件通常代表一轮输出结束，优先同步会话 ID 便于续聊。
                    String sid = node.path("session_id").asText("");
                    if (!sid.isBlank()) {
                        userSessionIds.put(processKey, sid);
                    }
                    String result = node.path("result").asText("");
                    if (handleClaudeKnownIssueText(processKey, result, callback)) {
                        return;
                    }
                    boolean duplicatedWithAssistant = false;
                    if (!result.isBlank()) {
                        String normalizedResult = normalizeClaudeTextForDedup(result);
                        String normalizedAssistant = lastClaudeAssistantTexts.get(processKey);
                        duplicatedWithAssistant = !normalizedResult.isBlank()
                                && normalizedResult.equals(normalizedAssistant);
                    }
                    if (!duplicatedWithAssistant && !result.isBlank() && result.length() <= 4000) {
                        callback.accept(result);
                    }
                    // 用量信息
                    JsonNode usage = node.path("usage");
                    if (!usage.isMissingNode()) {
                        int input = usage.path("input_tokens").asInt(0);
                        int output = usage.path("output_tokens").asInt(0);
                        callback.accept(String.format("— Token: 输入=%d, 输出=%d", input, output));
                    }
                }
                default -> {
                    // system/user 事件数量很大，默认不刷 DEBUG，避免淹没有价值日志。
                    if (!"system".equals(type) && !"user".equals(type)) {
                        log.debug("Claude 事件: {}", type);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("解析 Claude 事件失败: {}", e.getMessage());
        }
    }

    /**
     * 识别 Claude 常见可恢复错误，并统一给出操作指引。
     *
     * @return true 表示已处理（通常会清理缓存会话并发出友好提示）。
     */
    private boolean handleClaudeKnownIssueText(String processKey, String text, Consumer<String> callback) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        if (normalized.contains("not logged in") || normalized.contains("please run /login")) {
            userSessionIds.remove(processKey);
            callback.accept("🔐 Claude 未登录。请先在 SSH 主机执行 `claude auth login` 完成授权，然后重试。");
            return true;
        }
        if (normalized.contains("session id") && normalized.contains("already in use")) {
            userSessionIds.remove(processKey);
            callback.accept("♻️ Claude 会话 ID 冲突，已自动清理缓存会话。请直接重试当前问题。");
            return true;
        }
        return false;
    }

    /** 兼容 Claude assistant 事件的新旧结构，提取可展示内容。 */
    private String emitClaudeAssistantMessage(JsonNode message, Consumer<String> callback) {
        if (message == null || message.isMissingNode()) {
            return "";
        }
        StringBuilder assistantText = new StringBuilder();
        JsonNode content = message.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                String emitted = emitClaudeAssistantBlock(block, callback);
                if (!emitted.isBlank()) {
                    if (assistantText.length() > 0) {
                        assistantText.append('\n');
                    }
                    assistantText.append(emitted);
                }
            }
            return assistantText.toString();
        }
        // 兼容旧结构：message.type/message.text
        return emitClaudeAssistantBlock(message, callback);
    }

    /** 处理 assistant 内容块（text/tool_use）。 */
    private String emitClaudeAssistantBlock(JsonNode block, Consumer<String> callback) {
        if (block == null || block.isMissingNode()) {
            return "";
        }
        String blockType = block.path("type").asText("");
        if ("tool_use".equals(blockType)) {
            String toolName = block.path("name").asText("");
            JsonNode input = block.path("input");
            String summary = formatToolCall(toolName, input);
            if (!summary.isBlank()) {
                callback.accept("⎡🔧 " + summary + "⎦");
            }
            return "";
        }
        if ("text".equals(blockType) || blockType.isBlank()) {
            String text = block.path("text").asText("");
            if (text.isBlank() && block.path("content").isTextual()) {
                text = block.path("content").asText("");
            }
            if (!text.isBlank()) {
                callback.accept(text);
                return text;
            }
        }
        return "";
    }

    /** 归一化 Claude 文本，降低换行/空白差异导致的误判。 */
    private String normalizeClaudeTextForDedup(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .trim()
                .replaceAll("\\s+", " ");
    }

    /** 格式化工具调用的简要描述 */
    private String formatToolCall(String toolName, JsonNode input) {
        return switch (toolName) {
            case "Read", "read_file" -> "读取文件: " + input.path("file_path").asText(input.path("path").asText(""));
            case "Edit", "edit_file" -> "编辑文件: " + input.path("file_path").asText(input.path("path").asText(""));
            case "Write", "write_file" -> "写入文件: " + input.path("file_path").asText(input.path("path").asText(""));
            case "Bash", "bash" -> "执行命令: " + truncate(input.path("command").asText(""), 200);
            case "ListDir", "list_dir" -> "列出目录: " + input.path("path").asText("");
            default -> toolName + ": " + truncate(input.toString(), 200);
        };
    }

    /** 对长字符串做安全截断，避免消息输出过长。 */
    private String truncate(String text, int maxLen) {
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    /** 格式化 Codex tool_call 启动时的简要描述（从 item.started 事件中提取）。 */
    private String formatCodexToolStart(String toolName, String arguments) {
        String label = switch (toolName) {
            case "shell" -> "执行命令";
            case "read_file" -> "读取文件";
            case "write_file" -> "写入文件";
            case "apply_diff" -> "应用修改";
            case "list_dir" -> "列出目录";
            default -> toolName;
        };
        if (arguments == null || arguments.isBlank()) {
            return label;
        }
        // 尝试从 arguments JSON 中提取关键参数
        try {
            JsonNode args = MAPPER.readTree(arguments);
            String detail = args.path("command").asText(
                    args.path("path").asText(
                            args.path("file_path").asText("")));
            if (!detail.isBlank()) {
                return label + ": " + truncate(detail, 150);
            }
        } catch (Exception ignored) {
        }
        return label;
    }

    // ==================== 二进制路径解析 ====================

    /** 服务启动时优先用 `which` 探测 CLI 路径并缓存。 */
    private void detectCliBinariesAtStartup() {
        for (CliType cliType : CliType.values()) {
            String resolved = resolveBinNow(cliType);
            if (resolved != null) {
                resolvedBins.put(cliType, resolved);
                log.info("{} CLI 已探测到可执行路径: {}", cliType.getDisplayName(), resolved);
            } else {
                log.warn("{} CLI 未在启动时探测到可执行路径（命令: {}）", cliType.getDisplayName(), cliType.getCommandName());
            }
        }
    }

    /** 尝试找到 CLI 二进制路径 */
    private String resolveBin(CliType cliType) {
        String cached = resolvedBins.get(cliType);
        if (isExecutable(cached)) {
            return cached;
        }
        String refreshed = resolveBinNow(cliType);
        if (refreshed != null) {
            resolvedBins.put(cliType, refreshed);
            return refreshed;
        }
        resolvedBins.remove(cliType);
        return null;
    }

    /**
     * 实际执行一次路径探测。
     * <p>
     * 先尝试 `which`，再尝试固定候选路径。
     * </p>
     */
    private String resolveBinNow(CliType cliType) {
        String fromWhich = resolveByWhich(cliType.getCommandName());
        if (isExecutable(fromWhich)) {
            return fromWhich;
        }
        for (String candidate : cliType.getFallbackBins()) {
            if (isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** 用 which 查找命令路径。 */
    private String resolveByWhich(String commandName) {
        try {
            Process p = new ProcessBuilder("which", commandName)
                    .redirectErrorStream(true).start();
            String result = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (p.waitFor() == 0 && !result.isBlank()) {
                return result;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean isExecutable(String path) {
        return path != null && !path.isBlank() && new File(path).canExecute();
    }
}
