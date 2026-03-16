package com.webssh.bot;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.webssh.config.SshCompatibilityProperties;
import com.webssh.session.SessionProfileStore;
import com.webssh.session.SshSessionProfile;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Bot 侧 SSH 会话管理器，所有机器人类型共享。
 * <p>
 * 每个聊天用户（通过 {@code botType:userId} 唯一标识）维护一个 SSH 连接。
 * 复用 {@link SessionProfileStore} 读取已保存的会话配置（含解密后的凭据），
 * 通过 JSch 建立 SSH 会话；普通命令使用独立的 exec 通道执行，避免长驻交互式 shell 的状态污染。
 * </p>
 */
@Service
public class BotSshSessionManager {

    private static final Logger log = LoggerFactory.getLogger(BotSshSessionManager.class);

    /** Telegram 单条消息最大字符数 */
    private static final int MAX_MESSAGE_LENGTH = 4000;

    /** Shell 输出读取缓冲区大小 */
    private static final int OUTPUT_BUFFER_SIZE = 8192;

    /** 命令执行后等待输出的初始延迟（毫秒） */
    private static final long OUTPUT_INITIAL_DELAY_MS = 100;

    /** 无新输出后的最大等待时间（毫秒） */
    private static final long OUTPUT_IDLE_TIMEOUT_MS = 2000;

    /** Shell 工作目录标记字节 */
    private static final byte SHELL_CWD_MARKER_START = 0x02;
    private static final byte SHELL_CWD_MARKER_END = 0x03;
    private static final byte[] SHELL_CWD_MARKER_PREFIX = "__WEBSSH_CWD__:".getBytes(StandardCharsets.US_ASCII);

    private final SessionProfileStore profileStore;
    private final SshCompatibilityProperties sshProperties;
    private final AiCliExecutor aiCliExecutor;
    private final ConcurrentMap<String, SshConnection> connections = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ReconnectContext> reconnectContexts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> reconnectLocks = new ConcurrentHashMap<>();
    private final ExecutorService outputExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "bot-ssh-output");
        t.setDaemon(true);
        return t;
    });

    record ConnectionSpec(
            String profileName,
            String username,
            String host,
            int port,
            String authType,
            String password,
            String privateKey,
            String passphrase) {
        private static ConnectionSpec fromProfile(SshSessionProfile profile) {
            return new ConnectionSpec(
                    profile.getName(),
                    profile.getUsername(),
                    profile.getHost(),
                    profile.getPort(),
                    profile.getAuthType(),
                    profile.getPassword(),
                    profile.getPrivateKey(),
                    profile.getPassphrase());
        }
    }

    static final class ReconnectContext {
        private final ConnectionSpec spec;
        private volatile String lastKnownCwd;

        private ReconnectContext(ConnectionSpec spec) {
            this.spec = spec;
        }

        private String lastKnownCwd() {
            return lastKnownCwd;
        }

        private void updateCwd(String cwd) {
            if (cwd != null && !cwd.isBlank()) {
                this.lastKnownCwd = cwd;
            }
        }
    }

    /** 远端 exec 通道句柄，用于 AI CLI 等长任务。 */
    public static final class RemoteCommandHandle {
        private final ChannelExec channel;
        private final InputStream output;

        private RemoteCommandHandle(ChannelExec channel, InputStream output) {
            this.channel = channel;
            this.output = output;
        }

        public InputStream output() {
            return output;
        }

        public boolean isRunning() {
            return channel != null && channel.isConnected() && !channel.isClosed();
        }

        public int waitForExit() throws InterruptedException {
            while (isRunning()) {
                Thread.sleep(100);
            }
            return channel == null ? -1 : channel.getExitStatus();
        }

        public void stop() {
            if (channel == null) {
                return;
            }
            try {
                channel.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    /** SSH 连接状态封装 */
    public static class SshConnection {
        private final Session session;
        private final ChannelShell channel;
        private final String profileName;
        private final ReconnectContext reconnectContext;
        private volatile String cwd;
        private volatile boolean closed = false;
        private ChannelExec activeExecChannel;
        private InputStream activeExecOutput;

        SshConnection(Session session, ChannelShell channel, OutputStream inputWriter,
                InputStream outputReader, String profileName) {
            this(session, channel, inputWriter, outputReader, profileName, null);
        }

        SshConnection(Session session, ChannelShell channel, OutputStream inputWriter,
                InputStream outputReader, String profileName, ReconnectContext reconnectContext) {
            this(session, channel, profileName, reconnectContext);
        }

        SshConnection(Session session, String profileName, ReconnectContext reconnectContext) {
            this(session, null, profileName, reconnectContext);
        }

        SshConnection(Session session, ChannelShell channel, String profileName, ReconnectContext reconnectContext) {
            this.session = session;
            this.channel = channel;
            this.profileName = profileName;
            this.reconnectContext = reconnectContext;
        }

        public String getProfileName() {
            return profileName;
        }

        public String getCwd() {
            return cwd != null ? cwd : reconnectContext == null ? null : reconnectContext.lastKnownCwd();
        }

        public Session session() {
            return session;
        }

        public ChannelShell channel() {
            return channel;
        }

        public boolean isConnected() {
            return !closed && session != null && session.isConnected();
        }

        /** 为当前命令创建独立 exec 通道，避免长驻 shell 状态污染。 */
        public synchronized void sendCommand(String command) throws IOException {
            if (!isConnected()) {
                throw new IOException("SSH 连接已断开");
            }
            closeActiveExec();
            try {
                ChannelExec execChannel = (ChannelExec) session.openChannel("exec");
                execChannel.setPty(true);
                execChannel.setPtyType("xterm-256color");
                execChannel.setCommand(BotSshSessionManager.buildExecCommand(command, getCwd()));
                InputStream execOutput = execChannel.getInputStream();
                execChannel.connect(5_000);
                activeExecChannel = execChannel;
                activeExecOutput = execOutput;
            } catch (Exception e) {
                closeActiveExec();
                throw new IOException(e.getMessage(), e);
            }
        }

        /** 为长任务创建独立 exec 通道，调用方负责读取输出与生命周期管理。 */
        public synchronized RemoteCommandHandle startRemoteCommand(String command) throws IOException {
            if (!isConnected()) {
                throw new IOException("SSH 连接已断开");
            }
            try {
                ChannelExec execChannel = (ChannelExec) session.openChannel("exec");
                execChannel.setPty(true);
                execChannel.setPtyType("xterm-256color");
                execChannel.setCommand(BotSshSessionManager.buildExecCommand(command, getCwd()));
                InputStream execOutput = execChannel.getInputStream();
                execChannel.connect(5_000);
                return new RemoteCommandHandle(execChannel, execOutput);
            } catch (Exception e) {
                throw new IOException(e.getMessage(), e);
            }
        }

        /** 读取当前命令的完整可见输出，并同步更新工作目录。 */
        public synchronized String readAvailableOutput(long initialDelayMs, long idleTimeoutMs) {
            ByteArrayOutputStream visibleBuffer = new ByteArrayOutputStream();
            ChannelExec execChannel = activeExecChannel;
            InputStream execOutput = activeExecOutput;
            if (execChannel == null || execOutput == null) {
                return "";
            }

            byte[] buf = new byte[OUTPUT_BUFFER_SIZE];
            try {
                Thread.sleep(initialDelayMs);

                long lastReadTime = System.currentTimeMillis();
                while (System.currentTimeMillis() - lastReadTime < idleTimeoutMs) {
                    int available = execOutput.available();
                    if (available > 0) {
                        int read = execOutput.read(buf, 0, Math.min(available, buf.length));
                        if (read > 0) {
                            lastReadTime = System.currentTimeMillis();
                            visibleBuffer.write(buf, 0, read);
                            if (visibleBuffer.size() > MAX_MESSAGE_LENGTH * 20) {
                                break;
                            }
                        }
                    } else {
                        Thread.sleep(50);
                    }
                }
            } catch (IOException | InterruptedException e) {
                log.debug("读取 SSH 输出异常: {}", e.getMessage());
            } finally {
                closeActiveExec();
            }
            ExecResult result = BotSshSessionManager.parseExecResult(visibleBuffer.toByteArray());
            if (result.cwd() != null && !result.cwd().isBlank()) {
                this.cwd = result.cwd();
                if (reconnectContext != null) {
                    reconnectContext.updateCwd(this.cwd);
                }
            }
            return result.output();
        }

        public void close() {
            closed = true;
            closeActiveExec();
            try {
                if (channel != null) {
                    channel.disconnect();
                }
            } catch (Exception ignored) {
            }
            try {
                session.disconnect();
            } catch (Exception ignored) {
            }
        }

        private void closeActiveExec() {
            ChannelExec execChannel = activeExecChannel;
            activeExecChannel = null;
            activeExecOutput = null;
            if (execChannel != null) {
                try {
                    execChannel.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public BotSshSessionManager(SessionProfileStore profileStore,
            SshCompatibilityProperties sshProperties,
            AiCliExecutor aiCliExecutor) {
        this.profileStore = profileStore;
        this.sshProperties = sshProperties;
        this.aiCliExecutor = aiCliExecutor;
    }

    @PreDestroy
    public void shutdown() {
        connections.values().forEach(SshConnection::close);
        outputExecutor.shutdownNow();
    }

    /** 生成连接的唯一标识 */
    private String connectionKey(String botType, String userId) {
        return botType + ":" + userId;
    }

    private Object reconnectLock(String key) {
        return reconnectLocks.computeIfAbsent(key, ignored -> new Object());
    }

    /** 列出指定用户的 SSH 会话配置 */
    public List<SshSessionProfile> listProfiles(String sshUsername) {
        return profileStore.list(sshUsername);
    }

    /** 获取指定用户的当前连接 */
    public SshConnection getConnection(String botType, String userId) {
        return findActiveConnection(connectionKey(botType, userId));
    }

    /** 断开指定用户的 SSH 连接 */
    public void disconnect(String botType, String userId) {
        String key = connectionKey(botType, userId);
        SshConnection conn = connections.remove(key);
        aiCliExecutor.clearAllSessions(botType + ":" + userId);
        if (conn != null) {
            conn.close();
        }
        reconnectContexts.remove(key);
        reconnectLocks.remove(key);
    }

    /** 断开指定机器人类型的所有连接 */
    public void disconnectAll(String botType) {
        List<String> toRemove = new ArrayList<>();
        connections.forEach((key, conn) -> {
            if (key.startsWith(botType + ":")) {
                toRemove.add(key);
                conn.close();
                String userKey = key.substring(botType.length() + 1);
                aiCliExecutor.clearAllSessions(botType + ":" + userKey);
                reconnectContexts.remove(key);
                reconnectLocks.remove(key);
            }
        });
        toRemove.forEach(connections::remove);
        reconnectContexts.keySet().removeIf(key -> key.startsWith(botType + ":"));
        reconnectLocks.keySet().removeIf(key -> key.startsWith(botType + ":"));
    }

    /**
     * 连接到指定 SSH 会话（通过名称或序号匹配）。
     *
     * @param botType     机器人类型
     * @param userId      聊天用户 ID
     * @param sshUsername WebSSH 用户名
     * @param target      会话名称或序号（1-based）
     * @return 连接成功后的描述信息
     */
    public String connect(String botType, String userId, String sshUsername, String target) throws Exception {
        // 先断开已有连接
        disconnect(botType, userId);

        List<SshSessionProfile> profiles = profileStore.list(sshUsername);
        if (profiles.isEmpty()) {
            throw new IllegalStateException("没有已保存的 SSH 会话配置");
        }

        // 匹配目标会话：优先名称精确匹配，其次序号
        SshSessionProfile matched = null;
        try {
            int index = Integer.parseInt(target.trim()) - 1;
            if (index >= 0 && index < profiles.size()) {
                matched = profiles.get(index);
            }
        } catch (NumberFormatException ignored) {
        }
        if (matched == null) {
            for (SshSessionProfile p : profiles) {
                if (target.trim().equalsIgnoreCase(p.getName())) {
                    matched = p;
                    break;
                }
            }
        }
        if (matched == null) {
            throw new IllegalArgumentException("未找到匹配的会话: " + target);
        }

        // 获取完整凭据
        SshSessionProfile detail = profileStore.get(sshUsername, matched.getId());
        if (detail == null) {
            throw new IllegalStateException("无法获取会话详情: " + matched.getName());
        }

        // 建立 SSH 连接
        String key = connectionKey(botType, userId);
        ReconnectContext reconnectContext = new ReconnectContext(ConnectionSpec.fromProfile(detail));
        SshConnection conn = openSshConnection(reconnectContext);
        reconnectContexts.put(key, reconnectContext);
        connections.put(key, conn);

        return String.format("✅ 已连接到 %s (%s@%s:%d)",
                detail.getName(), detail.getUsername(), detail.getHost(), detail.getPort());
    }

    /**
     * 执行命令并返回输出。
     *
     * @param botType  机器人类型
     * @param userId   聊天用户 ID
     * @param command  Shell 命令
     * @param callback 输出回调，可能被多次调用（分批发送）
     */
    public void executeCommand(String botType, String userId, String command, Consumer<String> callback) {
        executeCommandAsync(botType, userId, command)
                .whenComplete((output, error) -> {
                    if (error != null) {
                        callback.accept("❌ " + error.getMessage());
                        return;
                    }
                    sendInChunks(output, callback);
                });
    }

    /** 异步执行命令并聚合完整输出，便于 QQ 等平台在单条消息中回复结果 */
    public CompletableFuture<String> executeCommandAsync(String botType, String userId, String command) {
        CompletableFuture<String> future = new CompletableFuture<>();
        outputExecutor.submit(() -> {
            try {
                SshConnection conn = sendCommandWithReconnect(botType, userId, command);
                String output = conn.readAvailableOutput(OUTPUT_INITIAL_DELAY_MS, OUTPUT_IDLE_TIMEOUT_MS);
                if (output.isEmpty()) {
                    future.complete("(无输出)");
                    return;
                }
                future.complete(stripAnsiCodes(output));
            } catch (IOException e) {
                future.completeExceptionally(new IllegalStateException(e.getMessage(), e));
            } catch (Exception e) {
                log.error("执行命令异常: {}", e.getMessage(), e);
                future.completeExceptionally(new IllegalStateException("读取输出失败: " + e.getMessage(), e));
            }
        });
        return future;
    }

    /** 启动远端长任务命令（例如 Codex/Claude CLI）。 */
    public RemoteCommandHandle startRemoteCommand(String botType, String userId, String command) throws IOException {
        String key = connectionKey(botType, userId);
        IOException lastError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            SshConnection conn = ensureConnection(botType, userId);
            if (conn == null) {
                throw new IOException("未连接 SSH。请先使用 /connect 连接。");
            }
            try {
                return conn.startRemoteCommand(command);
            } catch (IOException e) {
                lastError = e;
                log.warn("启动远端命令失败，准备重试 [{}][attempt={}]: {}", key, attempt, e.getMessage());
                invalidateConnection(key, conn);
            }
        }
        throw new IOException("启动远端命令失败: " + (lastError == null ? "未知错误" : lastError.getMessage()),
                lastError);
    }

    /** 建立 SSH 会话，并用一次轻量 exec 初始化当前目录。 */
    SshConnection openSshConnection(ReconnectContext reconnectContext) throws Exception {
        return openSshConnection(reconnectContext.spec, reconnectContext, reconnectContext.lastKnownCwd());
    }

    /** 建立 SSH 会话，并在需要时恢复上次记录的工作目录。 */
    SshConnection openSshConnection(ConnectionSpec spec, ReconnectContext reconnectContext, String restoreCwd)
            throws Exception {
        JSch jsch = new JSch();

        if ("PRIVATE_KEY".equalsIgnoreCase(spec.authType())) {
            byte[] passBytes = spec.passphrase() != null && !spec.passphrase().isBlank()
                    ? spec.passphrase().getBytes(StandardCharsets.UTF_8)
                    : null;
            jsch.addIdentity(
                    "bot-ssh-key-" + UUID.randomUUID(),
                    spec.privateKey().getBytes(StandardCharsets.UTF_8),
                    null,
                    passBytes);
        }

        Session sshSession = null;
        try {
            sshSession = jsch.getSession(spec.username(), spec.host(), spec.port());
            if ("PASSWORD".equalsIgnoreCase(spec.authType())) {
                sshSession.setPassword(spec.password());
            }

            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            config.put("PreferredAuthentications",
                    "PASSWORD".equalsIgnoreCase(spec.authType()) ? "password" : "publickey");
            if (sshProperties.isAllowLegacySshRsa()) {
                String hostKeyAlgos = sshSession.getConfig("server_host_key");
                if (hostKeyAlgos != null && !hostKeyAlgos.contains("ssh-rsa")) {
                    config.put("server_host_key", hostKeyAlgos + ",ssh-rsa");
                }
                String pubKeyAlgos = sshSession.getConfig("PubkeyAcceptedAlgorithms");
                if (pubKeyAlgos != null && !pubKeyAlgos.contains("ssh-rsa")) {
                    config.put("PubkeyAcceptedAlgorithms", pubKeyAlgos + ",ssh-rsa");
                }
            }
            sshSession.setConfig(config);

            int keepAliveMs = sshProperties.getServerAliveIntervalMs();
            if (keepAliveMs > 0) {
                sshSession.setServerAliveInterval(keepAliveMs);
            }
            int keepAliveMax = sshProperties.getServerAliveCountMax();
            if (keepAliveMax > 0) {
                sshSession.setServerAliveCountMax(keepAliveMax);
            }

            sshSession.connect(10_000);

            SshConnection conn = new SshConnection(sshSession, spec.profileName(), reconnectContext);
            if (restoreCwd != null && !restoreCwd.isBlank()) {
                conn.cwd = restoreCwd;
            }
            conn.sendCommand("printf '%s' \"$PWD\"");
            conn.readAvailableOutput(50, 500);

            return conn;
        } catch (Exception e) {
            if (sshSession != null) {
                try {
                    sshSession.disconnect();
                } catch (Exception ignored) {
                }
            }
            throw e;
        }
    }

    private record ExecResult(String output, String cwd) {
    }

    private static String buildExecCommand(String command, String cwd) {
        StringBuilder script = new StringBuilder();
        if (cwd != null && !cwd.isBlank()) {
            script.append("if [ -d ").append(shellQuote(cwd)).append(" ]; then cd -- ")
                    .append(shellQuote(cwd)).append("; fi; ");
        }
        script.append("__webssh_cmd=").append(shellQuote(command)).append("; ");
        script.append("eval \"$__webssh_cmd\" 2>&1; ");
        script.append("__webssh_status=$?; ");
        script.append("printf '\\002__WEBSSH_CWD__:%s\\003' \"$PWD\"; ");
        script.append("exit $__webssh_status");
        return script.toString();
    }

    private static ExecResult parseExecResult(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return new ExecResult("", null);
        }
        int markerStart = lastIndexOf(raw, SHELL_CWD_MARKER_START);
        if (markerStart < 0 || markerStart + 1 + SHELL_CWD_MARKER_PREFIX.length >= raw.length) {
            return new ExecResult(new String(raw, StandardCharsets.UTF_8), null);
        }
        if (!startsWith(raw, markerStart + 1, SHELL_CWD_MARKER_PREFIX)) {
            return new ExecResult(new String(raw, StandardCharsets.UTF_8), null);
        }
        int pathStart = markerStart + 1 + SHELL_CWD_MARKER_PREFIX.length;
        int markerEnd = indexOf(raw, SHELL_CWD_MARKER_END, pathStart);
        if (markerEnd < 0) {
            return new ExecResult(new String(raw, StandardCharsets.UTF_8), null);
        }

        ByteArrayOutputStream visible = new ByteArrayOutputStream(raw.length);
        visible.write(raw, 0, markerStart);
        if (markerEnd + 1 < raw.length) {
            visible.write(raw, markerEnd + 1, raw.length - markerEnd - 1);
        }
        String cwd = new String(raw, pathStart, markerEnd - pathStart, StandardCharsets.UTF_8);
        return new ExecResult(visible.toString(StandardCharsets.UTF_8), cwd);
    }

    private static int indexOf(byte[] data, byte value, int from) {
        for (int i = Math.max(from, 0); i < data.length; i++) {
            if (data[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOf(byte[] data, byte value) {
        for (int i = data.length - 1; i >= 0; i--) {
            if (data[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static boolean startsWith(byte[] data, int from, byte[] prefix) {
        if (from < 0 || from + prefix.length > data.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[from + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** 去除 ANSI 转义序列 */
    private String stripAnsiCodes(String text) {
        return text.replaceAll("\\x1b\\[[0-9;]*[a-zA-Z]", "")
                .replaceAll("\\x1b\\][^\\x07]*\\x07", "")
                .replaceAll("\\x1b\\[\\?[0-9;]*[a-zA-Z]", "")
                .replaceAll("[\\x00-\\x08\\x0e-\\x1f]", "");
    }

    /** 将长文本分批发送 */
    private void sendInChunks(String text, Consumer<String> callback) {
        if (text.length() <= MAX_MESSAGE_LENGTH) {
            callback.accept(text);
            return;
        }
        int offset = 0;
        while (offset < text.length()) {
            int end = Math.min(offset + MAX_MESSAGE_LENGTH, text.length());
            // 尝试在换行处截断
            if (end < text.length()) {
                int lastNewline = text.lastIndexOf('\n', end);
                if (lastNewline > offset) {
                    end = lastNewline + 1;
                }
            }
            callback.accept(text.substring(offset, end));
            offset = end;
        }
    }

    private SshConnection findActiveConnection(String key) {
        SshConnection conn = connections.get(key);
        if (conn == null) {
            return null;
        }
        if (conn.isConnected()) {
            return conn;
        }
        log.info("检测到 SSH 连接已失效 [{}], session={}",
                key,
                conn.session().isConnected());
        invalidateConnection(key, conn);
        return null;
    }

    private void invalidateConnection(String key, SshConnection conn) {
        if (conn == null) {
            return;
        }
        connections.remove(key, conn);
        conn.close();
    }

    private SshConnection ensureConnection(String botType, String userId) {
        String key = connectionKey(botType, userId);
        SshConnection conn = findActiveConnection(key);
        if (conn != null) {
            return conn;
        }

        ReconnectContext reconnectContext = reconnectContexts.get(key);
        if (reconnectContext == null) {
            return null;
        }

        synchronized (reconnectLock(key)) {
            conn = findActiveConnection(key);
            if (conn != null) {
                return conn;
            }
            try {
                SshConnection reopened = openSshConnection(reconnectContext);
                connections.put(key, reopened);
                log.info("SSH 连接已自动恢复 [{} -> {}@{}:{}]",
                        key,
                        reconnectContext.spec.username(),
                        reconnectContext.spec.host(),
                        reconnectContext.spec.port());
                return reopened;
            } catch (Exception e) {
                log.warn("SSH 自动恢复失败 [{}]: {}", key, e.getMessage());
                return null;
            }
        }
    }

    private SshConnection sendCommandWithReconnect(String botType, String userId, String command) throws IOException {
        String key = connectionKey(botType, userId);
        IOException lastError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            SshConnection conn = ensureConnection(botType, userId);
            if (conn == null) {
                throw new IOException("未连接 SSH。请先使用 /connect 连接。");
            }
            try {
                conn.sendCommand(command);
                return conn;
            } catch (IOException e) {
                lastError = e;
                log.warn("发送 SSH 命令失败，准备重试 [{}][attempt={}]: {}", key, attempt, e.getMessage());
                invalidateConnection(key, conn);
            }
        }
        throw new IOException("发送命令失败: " + (lastError == null ? "未知错误" : lastError.getMessage()),
                lastError);
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
