package com.webssh.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

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

    /**
     * 支持的 AI CLI 工具类型。
     */
    public enum CliType {
        CODEX("Codex", "/opt/homebrew/bin/codex", "codex"),
        CLAUDE("Claude Code", "/usr/local/bin/claude", "claude");

        private final String displayName;
        private final String defaultBin;
        private final String commandName;

        CliType(String displayName, String defaultBin, String commandName) {
            this.displayName = displayName;
            this.defaultBin = defaultBin;
            this.commandName = commandName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDefaultBin() {
            return defaultBin;
        }

        public String getCommandName() {
            return commandName;
        }
    }

    /** 正在运行的进程，key = cliType:botType:userId */
    private final ConcurrentMap<String, Process> runningProcesses = new ConcurrentHashMap<>();
    /** 正在运行的远端任务，key = cliType:botType:userId */
    private final ConcurrentMap<String, BotSshSessionManager.RemoteCommandHandle> runningRemoteCommands = new ConcurrentHashMap<>();

    /** 用户的会话 ID，key = cliType:botType:userId */
    private final ConcurrentMap<String, String> userSessionIds = new ConcurrentHashMap<>();

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
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ai-cli-exec");
        t.setDaemon(true);
        return t;
    });

    /** 应用销毁前关闭所有任务并回收线程。 */
    @PreDestroy
    public void shutdown() {
        runningProcesses.values().forEach(Process::destroyForcibly);
        runningRemoteCommands.values().forEach(BotSshSessionManager.RemoteCommandHandle::stop);
        executor.shutdownNow();
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
    public void execute(CliType cliType, String userKey, String prompt, String workDir,
            Consumer<String> outputCallback, Runnable onComplete) {
        String processKey = processKey(cliType, userKey);
        // 如果已有任务在运行，先停止
        stop(cliType, userKey);

        executor.submit(() -> {
            Process process = null;
            try {
                List<String> cmd = buildCommand(cliType, prompt, workDir, processKey);
                // 检查二进制是否存在（尝试寻找）
                String resolvedBin = resolveBin(cliType);
                if (resolvedBin == null) {
                    outputCallback.accept("❌ 未找到 " + cliType.getDisplayName() + " CLI。\n请先安装后再使用。");
                    onComplete.run();
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
                log.info("{} 任务完成 [{}], exit={}", cliType.getDisplayName(), userKey, exitCode);
                outputCallback.accept("✅ " + cliType.getDisplayName() + " 任务已完成 (exit=" + exitCode + ")");

            } catch (Exception e) {
                if (process != null && !process.isAlive()) {
                    outputCallback.accept("🛑 " + cliType.getDisplayName() + " 任务已停止。");
                } else {
                    log.error("{} 执行异常 [{}]: {}", cliType.getDisplayName(), userKey, e.getMessage(), e);
                    outputCallback.accept("❌ " + cliType.getDisplayName() + " 执行失败: " + e.getMessage());
                }
            } finally {
                runningProcesses.remove(processKey);
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
                onComplete.run();
            }
        });
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
    public void executeRemote(CliType cliType, String userKey, String prompt, String workDir,
            RemoteCommandStarter remoteStarter,
            Consumer<String> outputCallback, Runnable onComplete) {
        String processKey = processKey(cliType, userKey);
        stop(cliType, userKey);

        executor.submit(() -> {
            BotSshSessionManager.RemoteCommandHandle handle = null;
            try {
                String remoteCommand = buildRemoteCommand(cliType, prompt, workDir, processKey);
                handle = remoteStarter.start(remoteCommand);
                runningRemoteCommands.put(processKey, handle);

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
                log.info("{} 远端任务完成 [{}], exit={}", cliType.getDisplayName(), userKey, exitCode);
                outputCallback.accept("✅ " + cliType.getDisplayName() + " 任务已完成 (exit=" + exitCode + ")");
            } catch (Exception e) {
                BotSshSessionManager.RemoteCommandHandle active = handle;
                if (active != null && !active.isRunning()) {
                    outputCallback.accept("🛑 " + cliType.getDisplayName() + " 任务已停止。");
                } else {
                    log.error("{} 远端执行异常 [{}]: {}", cliType.getDisplayName(), userKey, e.getMessage(), e);
                    outputCallback.accept("❌ " + cliType.getDisplayName() + " 执行失败: " + e.getMessage());
                }
            } finally {
                runningRemoteCommands.remove(processKey);
                if (handle != null && handle.isRunning()) {
                    handle.stop();
                }
                onComplete.run();
            }
        });
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
        cmd.add(CliType.CODEX.getDefaultBin());

        cmd.add("exec");

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
        cmd.add(CliType.CLAUDE.getDefaultBin());
        cmd.add("-p");
        cmd.add(prompt);
        cmd.add("--output-format");
        cmd.add("stream-json");
        if (sessionId != null && !sessionId.isBlank()) {
            cmd.add("--session-id");
            cmd.add(sessionId);
        }
        if (workDir != null && !workDir.isBlank()) {
            cmd.add("--cwd");
            cmd.add(workDir);
        }
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
        String text = line
                .replace("\u0002", "")
                .replace("\u0003", "")
                .trim();
        int marker = text.indexOf("__WEBSSH_CWD__:");
        if (marker >= 0) {
            text = text.substring(0, marker).trim();
        }
        return text;
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

        // 非 JSON 行
        if (!normalized.startsWith("{")) {
            return false;
        }

        String processKey = processKey(cliType, userKey);
        switch (cliType) {
            case CODEX -> processCodexEvent(processKey, normalized, callback);
            case CLAUDE -> processClaudeEvent(processKey, normalized, callback);
        }
        return true;
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
                case "item.completed" -> {
                    JsonNode item = node.path("item");
                    String itemType = item.path("type").asText("");
                    String text = item.path("text").asText("");
                    if (!text.isBlank()) {
                        // item.completed 是最终可展示内容，按语义打前缀便于聊天界面快速区分。
                        String prefix = switch (itemType) {
                            case "agent_message" -> "🤖 ";
                            case "tool_call" -> "🔧 ";
                            default -> "📝 ";
                        };
                        callback.accept(prefix + text);
                    }
                }
                case "turn.completed" -> {
                    // turn 完成时附带 token 用量，便于用户了解一次请求消耗。
                    JsonNode usage = node.path("usage");
                    if (!usage.isMissingNode()) {
                        int input = usage.path("input_tokens").asInt(0);
                        int output = usage.path("output_tokens").asInt(0);
                        int cached = usage.path("cached_input_tokens").asInt(0);
                        callback.accept(String.format(
                                "📊 Token: 输入=%d (缓存=%d), 输出=%d",
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
                    String msgType = message.path("type").asText("");

                    if ("text".equals(msgType)) {
                        String text = message.path("text").asText("");
                        if (!text.isBlank()) {
                            callback.accept("🤖 " + text);
                        }
                    } else if ("tool_use".equals(msgType)) {
                        String toolName = message.path("name").asText("");
                        JsonNode input = message.path("input");
                        // 简化显示工具调用
                        String summary = formatToolCall(toolName, input);
                        if (!summary.isBlank()) {
                            callback.accept("🔧 " + summary);
                        }
                    }
                }
                case "result" -> {
                    // result 事件通常代表一轮输出结束，优先同步会话 ID 便于续聊。
                    String sid = node.path("session_id").asText("");
                    if (!sid.isBlank()) {
                        userSessionIds.put(processKey, sid);
                    }
                    String result = node.path("result").asText("");
                    if (!result.isBlank() && result.length() <= 4000) {
                        callback.accept("📋 " + result);
                    }
                    // 用量信息
                    JsonNode usage = node.path("usage");
                    if (!usage.isMissingNode()) {
                        int input = usage.path("input_tokens").asInt(0);
                        int output = usage.path("output_tokens").asInt(0);
                        callback.accept(String.format("📊 Token: 输入=%d, 输出=%d", input, output));
                    }
                }
                default -> log.debug("Claude 事件: {}", type);
            }
        } catch (Exception e) {
            log.debug("解析 Claude 事件失败: {}", e.getMessage());
        }
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

    // ==================== 二进制路径解析 ====================

    /** 尝试找到 CLI 二进制路径 */
    private String resolveBin(CliType cliType) {
        // 优先使用默认路径
        if (new java.io.File(cliType.getDefaultBin()).canExecute()) {
            return cliType.getDefaultBin();
        }
        // 尝试常见路径
        String[] candidates = switch (cliType) {
            case CODEX -> new String[] { "/opt/homebrew/bin/codex", "/usr/local/bin/codex" };
            case CLAUDE -> new String[] { "/usr/local/bin/claude", "/opt/homebrew/bin/claude",
                    System.getProperty("user.home") + "/.npm-global/bin/claude",
                    System.getProperty("user.home") + "/.local/bin/claude" };
        };
        for (String path : candidates) {
            if (new java.io.File(path).canExecute()) {
                return path;
            }
        }
        // 最后尝试 which
        try {
            Process p = new ProcessBuilder("which", cliType == CliType.CODEX ? "codex" : "claude")
                    .redirectErrorStream(true).start();
            String result = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (p.waitFor() == 0 && !result.isBlank()) {
                return result;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
