package com.webssh.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
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
        CODEX("Codex", "/opt/homebrew/bin/codex"),
        CLAUDE("Claude Code", "/usr/local/bin/claude");

        private final String displayName;
        private final String defaultBin;

        CliType(String displayName, String defaultBin) {
            this.displayName = displayName;
            this.defaultBin = defaultBin;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDefaultBin() {
            return defaultBin;
        }
    }

    /** 正在运行的进程，key = cliType:botType:userId */
    private final ConcurrentMap<String, Process> runningProcesses = new ConcurrentHashMap<>();

    /** 用户的会话 ID，key = cliType:botType:userId */
    private final ConcurrentMap<String, String> userSessionIds = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ai-cli-exec");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    public void shutdown() {
        runningProcesses.values().forEach(Process::destroyForcibly);
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
        return false;
    }

    /** 检查是否有任务在运行 */
    public boolean isRunning(CliType cliType, String userKey) {
        Process process = runningProcesses.get(processKey(cliType, userKey));
        return process != null && process.isAlive();
    }

    /** 检查是否有任何 AI CLI 任务在运行 */
    public boolean isAnyRunning(String userKey) {
        for (CliType type : CliType.values()) {
            if (isRunning(type, userKey))
                return true;
        }
        return false;
    }

    private String processKey(CliType cliType, String userKey) {
        return cliType.name() + ":" + userKey;
    }

    // ==================== 命令构建 ====================

    private List<String> buildCommand(CliType cliType, String prompt, String workDir, String processKey) {
        String sessionId = userSessionIds.get(processKey);
        return switch (cliType) {
            case CODEX -> buildCodexCommand(prompt, workDir, sessionId);
            case CLAUDE -> buildClaudeCommand(prompt, workDir, sessionId);
        };
    }

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

    // ==================== 输出解析 ====================

    private void processLine(CliType cliType, String userKey, String line, Consumer<String> callback) {
        if (line.isBlank())
            return;

        // 非 JSON 行
        if (!line.startsWith("{")) {
            return;
        }

        String processKey = processKey(cliType, userKey);
        switch (cliType) {
            case CODEX -> processCodexEvent(processKey, line, callback);
            case CLAUDE -> processClaudeEvent(processKey, line, callback);
        }
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
                        String prefix = switch (itemType) {
                            case "agent_message" -> "🤖 ";
                            case "tool_call" -> "🔧 ";
                            default -> "📝 ";
                        };
                        callback.accept(prefix + text);
                    }
                }
                case "turn.completed" -> {
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
