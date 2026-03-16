package com.webssh.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webssh.config.SshCompatibilityProperties;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSH WebSocket 核心处理器 —— 整个 WebSSH 系统的心脏。
 *
 * <p>
 * 该类负责将浏览器端通过 WebSocket 发来的 JSON 指令转换为对远程 SSH 服务器的操作，
 * 实现了完整的 Web 终端功能，包括：
 * <ul>
 * <li><b>终端交互</b>：connect / input / resize / disconnect —— 基于 JSch 的
 * ChannelShell</li>
 * <li><b>SFTP 文件管理</b>：sftp_list / sftp_download / sftp_upload /
 * sftp_mkdir</li>
 * <li><b>端口转发</b>：port_forward_add / port_forward_remove /
 * port_forward_list</li>
 * <li><b>Shell 工作目录追踪</b>：通过注入 shell 钩子函数实时感知远端 $PWD 变化</li>
 * </ul>
 *
 * <h3>架构设计要点</h3>
 * <ol>
 * <li>每个 WebSocket 会话对应一个 {@link ClientConnection}，存储 SSH 会话、通道和所有运行状态</li>
 * <li>SFTP 下载采用分块 + ACK 流控机制，避免大文件传输时浏览器内存溢出</li>
 * <li>SFTP 上传支持分片传输，由前端分片后逐块发送</li>
 * <li>IO 密集型操作（SFTP）提交到独立线程池 {@code ioExecutor}，避免阻塞 WebSocket 线程</li>
 * <li>Shell 输出流通过 {@link ShellOutputFilter} 过滤掉注入的工作目录探测命令回显</li>
 * </ol>
 *
 * @see com.webssh.config.WebSocketConfig 注册该处理器到 /ws/ssh 端点
 */
@Component
public class SshWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SshWebSocketHandler.class);

    /** SFTP 下载时每次读取的块大小：128KB，平衡传输效率与内存占用 */
    private static final int SFTP_DOWNLOAD_CHUNK_BYTES = 128 * 1024;

    /** SFTP 下载等待客户端 ACK 的超时时间：2 分钟，防止客户端掉线导致服务端线程永久阻塞 */
    private static final long SFTP_DOWNLOAD_ACK_TIMEOUT_MS = 120_000L;

    /** WebSocket 单条文本消息最大字节数：8MB，需与 WebSocketConfig 中的容器配置一致 */
    private static final int WS_TEXT_LIMIT_BYTES = 8 * 1024 * 1024;

    /** IO 线程池核心线程数：至少 4 个，取 CPU 核心数和 4 的较大值 */
    private static final int IO_CORE_THREADS = Math.max(4, Runtime.getRuntime().availableProcessors());

    /** IO 线程池最大线程数：核心线程数的 2 倍 */
    private static final int IO_MAX_THREADS = IO_CORE_THREADS * 2;

    /** IO 线程池任务队列容量：超过此限制的任务会被拒绝，返回"系统繁忙"提示 */
    private static final int IO_QUEUE_CAPACITY = 256;

    /** 最大并发 WebSocket 连接数，防止资源耗尽 */
    private static final int MAX_CONNECTIONS = 200;

    /** 分片上传超时时间：30 分钟无新数据则自动清理，防止资源泄漏 */
    private static final long UPLOAD_TIMEOUT_MS = 30 * 60 * 1000L;

    /** ShellOutputFilter 缓冲区上限：64KB，防止不完整标记导致内存无限增长 */
    private static final int SHELL_OUTPUT_BUFFER_LIMIT = 64 * 1024;

    /** 资源清理任务执行间隔（秒） */
    private static final long CLEANUP_INTERVAL_SECONDS = 60L;

    /**
     * Shell 工作目录标记字节：STX (0x02) 和 ETX (0x03)。
     * <p>
     * 通过在 shell 中注入函数，让每次命令执行后输出 {@code \002__WEBSSH_CWD__:/path\003}，
     * 从而在终端输出流中嵌入工作目录信息，前端据此同步 SFTP 面板路径。
     * 这些控制字符在正常终端输出中几乎不会出现，因此作为定界符是安全的。
     */
    private static final byte SHELL_CWD_MARKER_START = 0x02;
    private static final byte SHELL_CWD_MARKER_END = 0x03;

    /** 工作目录标记前缀的 ASCII 字节序列 */
    private static final byte[] SHELL_CWD_MARKER_PREFIX = "__WEBSSH_CWD__:".getBytes(StandardCharsets.US_ASCII);

    /**
     * 连接建立后注入到远端 shell 的初始化命令列表。
     * <p>
     * 这些命令会：
     * <ol>
     * <li>定义 {@code __w()} 函数，使用 printf 输出带 STX/ETX 定界的当前目录</li>
     * <li>对 zsh：将 {@code __w} 加入 precmd 钩子</li>
     * <li>对 bash：将 {@code __w} 加入 PROMPT_COMMAND</li>
     * <li>立即执行一次 {@code __w} 获取初始目录</li>
     * </ol>
     */
    private static final List<String> SHELL_CWD_INIT_COMMANDS = List.of(
            "__w(){ printf '\\002__WEBSSH_CWD__:%s\\003' \"$PWD\"; }",
            "[ \"$ZSH_VERSION\" ] && eval 'precmd_functions+=(__w)'",
            "[ \"$BASH_VERSION\" ] && eval 'PROMPT_COMMAND=\"__w${PROMPT_COMMAND:+;$PROMPT_COMMAND}\"'",
            "__w");

    /** JSON 序列化/反序列化工具（复用 Spring 容器中的单例，减少内存分配） */
    private final ObjectMapper objectMapper;

    /** 所有活跃的 WebSocket 客户端连接，key 为 WebSocketSession.getId() */
    private final ConcurrentMap<String, ClientConnection> connections = new ConcurrentHashMap<>();

    /** IO 密集型任务线程池，用于 SFTP 操作和 SSH 输出读取等阻塞 IO */
    private final ThreadPoolExecutor ioExecutor;

    /** 定时清理调度器，用于回收超时的上传会话等泄漏资源 */
    private final ScheduledExecutorService cleanupScheduler;

    /** SSH 兼容性配置，控制是否允许旧版 ssh-rsa 算法 */
    private final SshCompatibilityProperties sshCompatibilityProperties;

    /**
     * SFTP 通道任务的函数式接口，用于 {@link #withSharedSftp} 回调模式。
     * 
     * @param <T> 任务返回值类型
     */
    @FunctionalInterface
    private interface SftpChannelTask<T> {
        T run(ChannelSftp channel) throws Exception;
    }

    /**
     * SSH 会话连接配置，封装一次 SSH 连接所需的全部参数。
     * 同时也用于 SFTP 操作时建立独立的 SSH 会话（避免与 Shell 通道共用会话导致阻塞）。
     */
    private record SshSessionConfig(
            String host,
            int port,
            String username,
            String authType,
            String password,
            String privateKey,
            String passphrase,
            String expectedFingerprint) {
    }

    /**
     * 已认证的 SSH 会话及其主机指纹，由 {@link #openAuthenticatedSession} 返回。
     */
    private record OpenedSession(Session session, String fingerprint) {
    }

    /**
     * SFTP 客户端，封装 SSH 会话和 SFTP 通道。
     * <p>
     * 实现 AutoCloseable 以便在 try-with-resources 中自动释放资源。
     * 当 {@code ownsSession} 为 true 时，关闭时同时断开 SSH 会话；
     * 为 false 时仅关闭 SFTP 通道（复用 Shell 会话的场景）。
     */
    private record SftpClient(Session session, ChannelSftp channel, boolean ownsSession) implements AutoCloseable {
        @Override
        public void close() {
            try {
                channel.disconnect();
            } catch (Exception ignored) {
                // 忽略关闭异常，确保后续 session 也能被关闭
            }
            if (ownsSession) {
                try {
                    session.disconnect();
                } catch (Exception ignored) {
                    // 忽略关闭异常
                }
            }
        }
    }

    /**
     * 构造函数：初始化 SSH 兼容性配置和 IO 线程池。
     *
     * <p>
     * 线程池使用有界队列 + AbortPolicy 策略：当系统负载过高时，
     * 新提交的任务会被拒绝并返回"系统繁忙"提示，而不是无限排队导致内存溢出。
     * 线程设为守护线程，JVM 退出时自动终止。
     *
     * @param sshCompatibilityProperties SSH 兼容性配置，注入自 Spring 容器
     */
    public SshWebSocketHandler(SshCompatibilityProperties sshCompatibilityProperties,
            ObjectMapper objectMapper) {
        this.sshCompatibilityProperties = sshCompatibilityProperties;
        this.objectMapper = objectMapper;
        AtomicInteger threadCounter = new AtomicInteger(1);
        this.ioExecutor = new ThreadPoolExecutor(
                IO_CORE_THREADS,
                IO_MAX_THREADS,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(IO_QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("ssh-io-" + threadCounter.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.ioExecutor.allowCoreThreadTimeOut(true);
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ssh-cleanup");
            t.setDaemon(true);
            return t;
        });
        this.cleanupScheduler.scheduleWithFixedDelay(
                this::cleanupStaleResources,
                CLEANUP_INTERVAL_SECONDS,
                CLEANUP_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    // ==================== WebSocket 生命周期回调 ====================

    /**
     * WebSocket 连接建立后的回调。
     * <p>
     * 为新连接创建 {@link ClientConnection} 对象并放入连接池。
     * 如果同一 session ID 存在旧连接（理论上不应发生），先关闭旧连接。
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        if (connections.size() >= MAX_CONNECTIONS) {
            sendError(session, "服务器连接数已满（" + MAX_CONNECTIONS + "），请稍后重试。");
            try {
                session.close(CloseStatus.SERVICE_OVERLOAD);
            } catch (Exception ignored) {
            }
            return;
        }
        session.setTextMessageSizeLimit(WS_TEXT_LIMIT_BYTES);
        ClientConnection old = connections.put(session.getId(), new ClientConnection(session));
        if (old != null) {
            old.close();
        }
        sendInfo(session, "WebSocket 已连接，请发送 connect 消息建立 SSH 会话。");
    }

    /**
     * 处理前端发来的 WebSocket 文本消息。
     * <p>
     * 所有前端操作都通过 JSON 消息传递，消息必须包含 {@code type} 字段。
     * 根据 type 分发到对应的处理方法：
     * <ul>
     * <li>{@code connect} - 建立 SSH 连接</li>
     * <li>{@code input} - 发送终端输入到远端</li>
     * <li>{@code resize} - 调整伪终端尺寸</li>
     * <li>{@code disconnect} - 主动断开 SSH 连接</li>
     * <li>{@code sftp_*} - SFTP 文件操作（列表/下载/上传/创建目录）</li>
     * <li>{@code port_forward_*} - 端口转发管理</li>
     * </ul>
     * 其中 SFTP 阻塞操作会提交到 IO 线程池异步执行，避免阻塞 WebSocket 线程。
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ClientConnection connection = connections.get(session.getId());
        if (connection == null) {
            sendError(session, "连接状态不存在，请刷新页面后重试。");
            return;
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(message.getPayload());
        } catch (Exception e) {
            sendError(session, "消息必须是 JSON 格式。");
            return;
        }

        String type = textOf(payload, "type");
        if (!hasText(type)) {
            sendError(session, "缺少消息类型 type。");
            return;
        }

        try {
            switch (type) {
                case "connect" -> handleConnect(connection, payload);
                case "input" -> handleInput(connection, payload);
                case "resize" -> handleResize(connection, payload);
                case "disconnect" -> handleDisconnect(connection);
                // SFTP 操作提交到 IO 线程池异步执行，避免阻塞 WebSocket 消息处理线程
                case "sftp_list" -> submitIoTask(connection, "SFTP 列表", () -> handleSftpList(connection, payload));
                case "sftp_download" ->
                    submitIoTask(connection, "SFTP 下载", () -> handleSftpDownload(connection, payload));
                case "sftp_download_ack" -> handleSftpDownloadAck(connection, payload);
                case "sftp_upload_start" -> handleSftpUploadStart(connection, payload);
                case "sftp_upload_chunk" -> handleSftpUploadChunk(connection, payload);
                case "sftp_upload" -> submitIoTask(connection, "SFTP 上传", () -> handleSftpUpload(connection, payload));
                case "sftp_mkdir" -> submitIoTask(connection, "SFTP 创建目录", () -> handleSftpMkdir(connection, payload));
                case "port_forward_add" -> handlePortForwardAdd(connection, payload);
                case "port_forward_remove" -> handlePortForwardRemove(connection, payload);
                case "port_forward_list" -> handlePortForwardList(connection);
                default -> sendError(session, "不支持的消息类型: " + type);
            }
        } catch (Exception e) {
            log.warn("处理消息失败 type={}: {}", type, safeMessage(e), e);
            sendError(session, "请求处理失败: " + safeMessage(e));
        }
    }

    /** WebSocket 传输层错误时清理对应的客户端连接 */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("WebSocket 传输错误: {}", exception.getMessage());
        ClientConnection connection = connections.remove(session.getId());
        if (connection != null) {
            connection.close();
        }
    }

    /** WebSocket 连接关闭时清理对应的客户端连接（释放 SSH 会话、SFTP 通道等资源） */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ClientConnection connection = connections.remove(session.getId());
        if (connection != null) {
            connection.close();
        }
    }

    /**
     * 应用关闭时的清理方法（由 Spring {@code @PreDestroy} 触发）。
     * 关闭所有活跃的客户端连接，并立即终止 IO 线程池中正在执行的任务。
     */
    @PreDestroy
    public void shutdown() {
        cleanupScheduler.shutdownNow();
        connections.values().forEach(ClientConnection::close);
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void cleanupStaleResources() {
        try {
            long now = System.currentTimeMillis();
            for (ClientConnection conn : connections.values()) {
                conn.cleanupStaleUploads(now, UPLOAD_TIMEOUT_MS);
            }
        } catch (Exception e) {
            log.debug("资源清理异常: {}", e.getMessage());
        }
    }

    /**
     * 将阻塞 IO 任务提交到 IO 线程池异步执行。
     *
     * <p>
     * 如果 WebSocket 在任务执行期间关闭，不视为异常（静默忽略）。
     * 如果线程池队列已满，返回 false 并通知客户端"系统繁忙"。
     *
     * @param connection 客户端连接
     * @param taskName   任务名称，用于日志和错误提示
     * @param task       要异步执行的任务
     * @return true 表示任务已提交，false 表示被拒绝
     */
    private boolean submitIoTask(ClientConnection connection, String taskName, Runnable task) {
        try {
            ioExecutor.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    // WebSocket 已关闭不算异常，只记录 debug 日志
                    if (isWebSocketClosedSendError(t)) {
                        log.debug("异步任务结束 [{}]: WebSocket 已关闭 ({})", taskName, safeMessage(t));
                        return;
                    }
                    log.warn("异步任务执行失败 [{}]: {}", taskName, safeMessage(t), t);
                    sendError(connection.webSocketSession(), taskName + "失败: " + safeMessage(t));
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("异步任务队列已满，拒绝任务 [{}]", taskName);
            sendError(connection.webSocketSession(), "系统繁忙，请稍后重试。");
            return false;
        }
    }

    // ==================== SSH 连接与终端交互 ====================

    /**
     * 处理 "connect" 消息 —— 建立 SSH 连接并打开 Shell 通道。
     *
     * <p>
     * 处理流程：
     * <ol>
     * <li>解析并校验连接参数（主机、端口、用户名、认证类型）</li>
     * <li>自动推断认证类型（如果前端未显式指定）</li>
     * <li>调用 {@link #openAuthenticatedSession} 建立 SSH 会话并验证主机指纹</li>
     * <li>打开 Shell 通道（xterm-256color 类型的伪终端）</li>
     * <li>启动异步线程读取 Shell 输出并推送到前端</li>
     * <li>注入 Shell 工作目录追踪命令</li>
     * </ol>
     *
     * @param connection 客户端连接
     * @param payload    包含 host, port, username, authType, password/privateKey 等参数
     */
    private void handleConnect(ClientConnection connection, JsonNode payload) {
        if (connection.isConnected()) {
            sendError(connection.webSocketSession(), "SSH 会话已连接。");
            return;
        }

        String host = textOf(payload, "host");
        int port = intOf(payload, "port", 22);
        String username = textOf(payload, "username");
        String authType = normalizeAuthType(textOf(payload, "authType"));
        String password = textOf(payload, "password");
        String privateKey = textOf(payload, "privateKey");
        String passphrase = textOf(payload, "passphrase");
        String expectedFingerprint = normalizeFingerprint(textOf(payload, "hostFingerprint"));
        int cols = intOf(payload, "cols", 120);
        int rows = intOf(payload, "rows", 36);

        if (!hasText(host) || !hasText(username)) {
            sendError(connection.webSocketSession(), "host 和 username 不能为空。");
            return;
        }
        String resolvedAuthType = authType;
        if (!hasText(resolvedAuthType)) {
            resolvedAuthType = inferAuthType(password, privateKey);
        }
        if (!"PASSWORD".equals(resolvedAuthType) && !"PRIVATE_KEY".equals(resolvedAuthType)) {
            sendError(connection.webSocketSession(), "authType 只支持 PASSWORD 或 PRIVATE_KEY。");
            return;
        }
        if ("PASSWORD".equals(resolvedAuthType) && !hasText(password)) {
            sendError(connection.webSocketSession(), "密码认证时 password 不能为空。");
            return;
        }
        if ("PRIVATE_KEY".equals(resolvedAuthType) && !hasText(privateKey)) {
            sendError(connection.webSocketSession(), "私钥认证时 privateKey 不能为空。");
            return;
        }

        Session sshSession = null;
        ChannelShell channel = null;
        InputStream outputReader = null;
        OutputStream inputWriter = null;
        boolean attached = false;

        try {
            SshSessionConfig sessionConfig = new SshSessionConfig(
                    host,
                    port,
                    username,
                    resolvedAuthType,
                    password,
                    privateKey,
                    passphrase,
                    expectedFingerprint);
            OpenedSession openedSession = openAuthenticatedSession(sessionConfig);
            sshSession = openedSession.session();
            String actualFingerprint = openedSession.fingerprint();

            channel = (ChannelShell) sshSession.openChannel("shell");
            channel.setPtyType("xterm-256color");
            channel.setPtySize(cols, rows, 0, 0);
            outputReader = channel.getInputStream();
            inputWriter = channel.getOutputStream();
            channel.connect(5_000);

            connection.attach(
                    sshSession,
                    channel,
                    inputWriter,
                    outputReader,
                    new SshSessionConfig(
                            host,
                            port,
                            username,
                            resolvedAuthType,
                            password,
                            privateKey,
                            passphrase,
                            actualFingerprint));
            attached = true;
            connection.armShellCwdSync();
            if (!submitIoTask(connection, "SSH 输出读取", () -> pumpOutput(connection))) {
                connection.close();
                return;
            }
            installShellCwdSync(connection);
            sendConnected(connection.webSocketSession(), actualFingerprint, ".");
            sendPortForwardList(connection.webSocketSession(), null, connection.forwardsSnapshot());
        } catch (Exception e) {
            if (attached) {
                connection.close();
            } else {
                closeQuietly(inputWriter);
                closeQuietly(outputReader);
                disconnectQuietly(channel);
                disconnectQuietly(sshSession);
            }
            log.debug("SSH 连接失败: {}", e.getMessage());
            sendError(connection.webSocketSession(), "SSH 连接失败: " + friendlySshError(e, resolvedAuthType));
        }
    }

    /**
     * 处理 "input" 消息 —— 将用户在终端中的键盘输入发送到远端 SSH Shell。
     *
     * @param connection 客户端连接
     * @param payload    包含 data 字段（用户输入的字符串）
     */
    private void handleInput(ClientConnection connection, JsonNode payload) {
        if (!connection.isConnected()) {
            sendError(connection.webSocketSession(), "SSH 尚未连接。");
            return;
        }

        String data = textOf(payload, "data");
        if (data == null) {
            return;
        }

        try {
            connection.write(data);
        } catch (IOException e) {
            connection.close();
            sendError(connection.webSocketSession(), "写入失败: " + safeMessage(e));
            sendSimple(connection.webSocketSession(), "disconnected", "SSH 会话已断开。");
        }
    }

    /**
     * 处理 "resize" 消息 —— 当浏览器终端窗口大小改变时，同步调整远端伪终端的列数和行数。
     * 这确保了 vi、less 等全屏应用能正确渲染。
     */
    private void handleResize(ClientConnection connection, JsonNode payload) {
        if (!connection.isConnected() || connection.channel() == null) {
            return;
        }

        int cols = intOf(payload, "cols", -1);
        int rows = intOf(payload, "rows", -1);
        if (cols <= 0 || rows <= 0) {
            return;
        }
        connection.channel().setPtySize(cols, rows, 0, 0);
    }

    /** 处理 "disconnect" 消息 —— 主动断开 SSH 连接并通知前端 */
    private void handleDisconnect(ClientConnection connection) {
        connection.close();
        sendSimple(connection.webSocketSession(), "disconnected", "SSH 会话已断开。");
    }

    // ==================== SFTP 文件操作 ====================

    /**
     * 处理 "sftp_list" 消息 —— 列出远端指定目录下的文件和子目录。
     * <p>
     * 使用共享 SFTP 连接（{@link #withSharedSftp}），避免频繁创建/销毁 SFTP 会话。
     * 返回的每个条目包含：名称、完整路径、是否目录、大小、修改时间、权限。
     */
    private void handleSftpList(ClientConnection connection, JsonNode payload) {
        if (!connection.hasSessionConnected()) {
            sendError(connection.webSocketSession(), "SSH 尚未连接，无法使用 SFTP。");
            return;
        }

        String path = textOf(payload, "path");
        final String effectivePath = hasText(path) ? path : ".";

        try {
            withSharedSftp(connection, sftp -> {
                String resolvedPath = resolvePath(sftp, effectivePath);
                @SuppressWarnings("rawtypes")
                java.util.Vector items = sftp.ls(resolvedPath);
                ArrayNode entries = objectMapper.createArrayNode();
                for (Object item : items) {
                    if (!(item instanceof ChannelSftp.LsEntry entry)) {
                        continue;
                    }
                    String name = entry.getFilename();
                    if (".".equals(name) || "..".equals(name)) {
                        continue;
                    }
                    SftpATTRS attrs = entry.getAttrs();
                    ObjectNode node = objectMapper.createObjectNode();
                    node.put("name", name);
                    node.put("path", joinRemotePath(resolvedPath, name));
                    node.put("directory", attrs != null && attrs.isDir());
                    node.put("size", attrs == null ? 0L : attrs.getSize());
                    node.put("modifiedAt", attrs == null ? 0L : attrs.getMTime() * 1000L);
                    node.put("permissions", attrs == null ? "" : attrs.getPermissionsString());
                    entries.add(node);
                }
                sendSftpList(connection.webSocketSession(), resolvedPath, entries);
                return null;
            });
        } catch (Exception e) {
            sendError(connection.webSocketSession(), "SFTP 列表失败: " + friendlySftpError(e));
        }
    }

    /**
     * 处理 "sftp_download" 消息 —— 分块下载远端文件。
     *
     * <p>
     * 采用流式传输 + ACK 流控机制：
     * <ol>
     * <li>先发送 {@code sftp_download_start} 通知前端总文件大小</li>
     * <li>循环读取文件数据，每次最多 128KB，Base64 编码后发送 {@code sftp_download_chunk}</li>
     * <li>每发送一块后等待前端的 {@code sftp_download_ack} 确认，超时 2 分钟则中断</li>
     * <li>发送最后一块时 {@code last=true}，前端据此拼接完整文件并触发浏览器下载</li>
     * </ol>
     * 这种流控机制防止服务端发送速度远超浏览器处理速度，避免内存溢出。
     */
    private void handleSftpDownload(ClientConnection connection, JsonNode payload) {
        if (!connection.hasSessionConnected()) {
            sendError(connection.webSocketSession(), "SSH 尚未连接，无法使用 SFTP。");
            return;
        }

        String path = textOf(payload, "path");
        if (!hasText(path)) {
            sendError(connection.webSocketSession(), "下载失败: path 不能为空。");
            return;
        }

        SftpClient sftpClient = null;
        String downloadId = UUID.randomUUID().toString();
        connection.startDownload(downloadId);
        try {
            sftpClient = openSftpClient(connection);
            SftpATTRS attrs = sftpClient.channel().lstat(path);
            if (attrs != null && attrs.isDir()) {
                throw new IllegalArgumentException("不能下载目录，请选择文件。");
            }
            long size = attrs == null ? 0L : attrs.getSize();
            sendSftpDownloadStart(connection.webSocketSession(), path, size, downloadId);

            byte[] buf = new byte[SFTP_DOWNLOAD_CHUNK_BYTES];
            long total = 0L;
            long chunkIndex = 0L;
            try (InputStream input = sftpClient.channel().get(path)) {
                int read;
                while ((read = input.read(buf)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    total += read;
                    byte[] chunk = (read == buf.length) ? buf : Arrays.copyOf(buf, read);
                    String encoded = Base64.getEncoder().encodeToString(chunk);
                    sendSftpDownloadChunk(connection.webSocketSession(),
                            path,
                            encoded,
                            total,
                            size,
                            false,
                            downloadId,
                            chunkIndex);
                    boolean acked = connection.awaitDownloadAck(
                            downloadId,
                            chunkIndex,
                            SFTP_DOWNLOAD_ACK_TIMEOUT_MS);
                    if (!acked) {
                        throw new IllegalStateException(
                                "下载中断: 客户端确认超时("
                                        + (SFTP_DOWNLOAD_ACK_TIMEOUT_MS / 1000)
                                        + "秒)。");
                    }
                    chunkIndex += 1;
                }
            }
            sendSftpDownloadChunk(connection.webSocketSession(),
                    path,
                    "",
                    total,
                    size,
                    true,
                    downloadId,
                    chunkIndex);
        } catch (Exception e) {
            sendError(connection.webSocketSession(), "SFTP 下载失败: " + friendlySftpError(e));
        } finally {
            connection.finishDownload(downloadId);
            closeQuietly(sftpClient);
        }
    }

    /** 处理 "sftp_download_ack" 消息 —— 前端确认收到某个下载分块，用于流控 */
    private void handleSftpDownloadAck(ClientConnection connection, JsonNode payload) {
        String downloadId = textOf(payload, "downloadId");
        long chunkIndex = longOf(payload, "chunkIndex", -1L);
        if (!hasText(downloadId) || chunkIndex < 0) {
            return;
        }
        connection.ackDownload(downloadId, chunkIndex);
    }

    /**
     * 处理 "sftp_upload" 消息 —— 一次性上传小文件（整个文件内容通过 Base64 编码在单条消息中传递）。
     * <p>
     * 适用于小文件场景。大文件应使用分片上传（sftp_upload_start + sftp_upload_chunk）。
     */
    private void handleSftpUpload(ClientConnection connection, JsonNode payload) {
        if (!connection.hasSessionConnected()) {
            sendError(connection.webSocketSession(), "SSH 尚未连接，无法使用 SFTP。");
            return;
        }

        String path = textOf(payload, "path");
        String data = textOf(payload, "data");
        boolean overwrite = boolOf(payload, "overwrite", true);
        if (!hasText(path) || !hasText(data)) {
            sendError(connection.webSocketSession(), "上传失败: path 和 data 不能为空。");
            return;
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            sendError(connection.webSocketSession(), "上传失败: data 不是合法的 Base64。");
            return;
        }

        SftpClient sftpClient = null;
        try {
            sftpClient = openSftpClient(connection);
            ensureParentDirectory(sftpClient.channel(), path);
            if (!overwrite && exists(sftpClient.channel(), path)) {
                throw new IllegalArgumentException("目标文件已存在，请勾选覆盖后重试。");
            }
            try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
                sftpClient.channel().put(input, path);
            }
            sendSftpUpload(connection.webSocketSession(), path, bytes.length);
        } catch (Exception e) {
            sendError(connection.webSocketSession(), "SFTP 上传失败: " + friendlySftpError(e));
        } finally {
            closeQuietly(sftpClient);
        }
    }

    /**
     * 处理 "sftp_upload_start" 消息 —— 开始分片上传。
     * <p>
     * 创建到远端的输出流（OutputStream），后续通过 {@link #handleSftpUploadChunk} 逐块写入。
     * 这种分片机制避免一次性将大文件全部加载到内存。
     */
    private void handleSftpUploadStart(ClientConnection connection, JsonNode payload) {
        if (!connection.hasSessionConnected()) {
            sendError(connection.webSocketSession(), "SSH 尚未连接，无法使用 SFTP。");
            return;
        }

        String uploadId = textOf(payload, "uploadId");
        String path = textOf(payload, "path");
        boolean overwrite = boolOf(payload, "overwrite", true);
        if (!hasText(uploadId) || !hasText(path)) {
            sendError(connection.webSocketSession(), "上传开始失败: uploadId 和 path 不能为空。");
            return;
        }

        UploadContext old = connection.removeUpload(uploadId);
        if (old != null) {
            old.close();
        }

        SftpClient sftpClient = null;
        OutputStream remoteWriter = null;
        try {
            sftpClient = openSftpClient(connection);
            ensureParentDirectory(sftpClient.channel(), path);
            if (!overwrite && exists(sftpClient.channel(), path)) {
                throw new IllegalArgumentException("目标文件已存在，请勾选覆盖后重试。");
            }
            remoteWriter = sftpClient.channel().put(path, ChannelSftp.OVERWRITE);
            UploadContext context = new UploadContext(path, sftpClient, remoteWriter);
            connection.putUpload(uploadId, context);
            sftpClient = null;
            remoteWriter = null;
        } catch (Exception e) {
            closeQuietly(remoteWriter);
            closeQuietly(sftpClient);
            sendError(connection.webSocketSession(), "上传开始失败: " + friendlySftpError(e));
        }
    }

    /**
     * 处理 "sftp_upload_chunk" 消息 —— 接收并写入上传文件的一个分片。
     * <p>
     * 当 {@code last=true} 时关闭上传流，完成文件上传。
     */
    private void handleSftpUploadChunk(ClientConnection connection, JsonNode payload) {
        String uploadId = textOf(payload, "uploadId");
        String data = textOf(payload, "data");
        boolean last = boolOf(payload, "last", false);
        if (!hasText(uploadId)) {
            sendError(connection.webSocketSession(), "上传分片失败: uploadId 不能为空。");
            return;
        }

        UploadContext context = connection.upload(uploadId);
        if (context == null) {
            sendError(connection.webSocketSession(), "上传分片失败: 上传会话不存在或已过期。");
            return;
        }

        byte[] bytes = new byte[0];
        if (data != null && !data.isEmpty()) {
            try {
                bytes = Base64.getDecoder().decode(data);
            } catch (IllegalArgumentException e) {
                connection.removeUpload(uploadId);
                context.close();
                sendError(connection.webSocketSession(), "上传分片失败: data 不是合法的 Base64。");
                return;
            }
        } else if (!last) {
            sendError(connection.webSocketSession(), "上传分片失败: data 不能为空。");
            return;
        }

        try {
            context.write(bytes);
            if (!last) {
                return;
            }

            connection.removeUpload(uploadId);
            context.close();
            sendSftpUpload(connection.webSocketSession(), context.path(), context.bytesWritten());
        } catch (Exception e) {
            connection.removeUpload(uploadId);
            context.close();
            sendError(connection.webSocketSession(), "上传分片失败: " + friendlySftpError(e));
        }
    }

    /** 处理 "sftp_mkdir" 消息 —— 在远端创建目录（支持递归创建多级目录） */
    private void handleSftpMkdir(ClientConnection connection, JsonNode payload) {
        if (!connection.hasSessionConnected()) {
            sendError(connection.webSocketSession(), "SSH 尚未连接，无法使用 SFTP。");
            return;
        }

        String path = textOf(payload, "path");
        if (!hasText(path)) {
            sendError(connection.webSocketSession(), "创建目录失败: path 不能为空。");
            return;
        }

        try {
            withSharedSftp(connection, sftp -> {
                ensureDirectoryExists(sftp, path);
                return null;
            });
            sendSftpMkdir(connection.webSocketSession(), path);
        } catch (Exception e) {
            sendError(connection.webSocketSession(), "创建目录失败: " + friendlySftpError(e));
        }
    }

    // ==================== 端口转发管理 ====================

    /**
     * 处理 "port_forward_add" 消息 —— 创建 SSH 端口转发规则。
     *
     * <p>
     * 支持两种方向：
     * <ul>
     * <li><b>LOCAL</b>：本地转发（-L），在本机监听端口，通过 SSH 隧道转发到远端目标</li>
     * <li><b>REMOTE</b>：远程转发（-R），在远端监听端口，通过 SSH 隧道转发到本机目标</li>
     * </ul>
     * 如果指定端口已有转发规则，会先删除旧规则再创建新规则。
     * LOCAL 转发 bindPort=0 时由系统自动分配可用端口。
     */
    private void handlePortForwardAdd(ClientConnection connection, JsonNode payload) {
        if (!connection.hasSessionConnected()) {
            sendError(connection.webSocketSession(), "SSH 尚未连接，无法创建端口转发。");
            return;
        }

        String direction = normalizeDirection(textOf(payload, "direction"));
        String bindHost = normalizeBindHost(textOf(payload, "bindHost"));
        int bindPort = intOf(payload, "bindPort", -1);
        String targetHost = textOf(payload, "targetHost");
        int targetPort = intOf(payload, "targetPort", -1);

        if (bindPort < 0 || bindPort > 65535) {
            sendError(connection.webSocketSession(), "bindPort 必须在 0-65535 之间。");
            return;
        }
        if ("REMOTE".equals(direction) && bindPort <= 0) {
            sendError(connection.webSocketSession(), "REMOTE 转发时 bindPort 必须在 1-65535 之间。");
            return;
        }
        if (!hasText(targetHost) || targetPort <= 0 || targetPort > 65535) {
            sendError(connection.webSocketSession(), "targetHost 和 targetPort 必须合法。");
            return;
        }

        Session session = connection.sshSession();
        if (session == null || !session.isConnected()) {
            sendError(connection.webSocketSession(), "SSH 会话不可用。");
            return;
        }

        try {
            if (bindPort > 0) {
                PortForwardRule existed = connection.removeForward(direction, bindHost, bindPort);
                if (existed != null) {
                    removePortForward(session, existed);
                }
            }

            int assignedPort;
            if ("REMOTE".equals(direction)) {
                session.setPortForwardingR(bindHost, bindPort, targetHost, targetPort);
                assignedPort = bindPort;
            } else {
                assignedPort = session.setPortForwardingL(bindHost, bindPort, targetHost, targetPort);
            }

            PortForwardRule rule = new PortForwardRule(
                    direction,
                    bindHost,
                    assignedPort,
                    targetHost,
                    targetPort);
            connection.addForward(rule);
            sendPortForwardList(connection.webSocketSession(), "端口转发已创建。", connection.forwardsSnapshot());
        } catch (Exception e) {
            sendError(connection.webSocketSession(), "创建端口转发失败: " + safeMessage(e));
        }
    }

    /** 处理 "port_forward_remove" 消息 —— 删除已有的端口转发规则 */
    private void handlePortForwardRemove(ClientConnection connection, JsonNode payload) {
        if (!connection.hasSessionConnected()) {
            sendError(connection.webSocketSession(), "SSH 尚未连接，无法删除端口转发。");
            return;
        }

        String direction = normalizeDirection(textOf(payload, "direction"));
        String bindHost = normalizeBindHost(textOf(payload, "bindHost"));
        int bindPort = intOf(payload, "bindPort", -1);
        if (bindPort <= 0 || bindPort > 65535) {
            sendError(connection.webSocketSession(), "删除端口转发时 bindPort 必须在 1-65535 之间。");
            return;
        }

        Session session = connection.sshSession();
        if (session == null || !session.isConnected()) {
            sendError(connection.webSocketSession(), "SSH 会话不可用。");
            return;
        }

        PortForwardRule existed = connection.removeForward(direction, bindHost, bindPort);
        PortForwardRule toRemove = existed == null
                ? new PortForwardRule(direction, bindHost, bindPort, "", 0)
                : existed;
        try {
            removePortForward(session, toRemove);
            sendPortForwardList(connection.webSocketSession(), "端口转发已删除。", connection.forwardsSnapshot());
        } catch (Exception e) {
            if (existed != null) {
                connection.addForward(existed);
            }
            sendError(connection.webSocketSession(), "删除端口转发失败: " + safeMessage(e));
        }
    }

    /** 处理 "port_forward_list" 消息 —— 返回当前所有活跃的端口转发规则列表 */
    private void handlePortForwardList(ClientConnection connection) {
        if (!connection.hasSessionConnected()) {
            sendError(connection.webSocketSession(), "SSH 尚未连接，无法读取端口转发列表。");
            return;
        }
        sendPortForwardList(connection.webSocketSession(), null, connection.forwardsSnapshot());
    }

    // ==================== SSH 输出推送 ====================

    /**
     * 持续读取 SSH Shell 输出并推送到前端（在 IO 线程池中运行）。
     *
     * <p>
     * 这是一个阻塞循环，从 Shell 通道的 InputStream 读取数据，经过
     * {@link ShellOutputFilter} 过滤后（移除注入的工作目录探测命令回显），
     * 将可见输出 Base64 编码后通过 WebSocket 发送到前端。
     *
     * <p>
     * 同时提取过滤出的工作目录路径，通过 {@code cwd} 消息发送给前端，
     * 以实现 SFTP 面板自动跟随 Shell 目录。
     */
    private void pumpOutput(ClientConnection connection) {
        InputStream outputReader = connection.outputReader();
        WebSocketSession webSocketSession = connection.webSocketSession();
        if (outputReader == null) {
            return;
        }

        byte[] buf = new byte[8192];
        try {
            while (!connection.isClosed() && webSocketSession.isOpen()) {
                int read = outputReader.read(buf);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                ShellOutputChunk chunk = connection.filterShellOutput(buf, read);
                for (String cwd : chunk.cwdPaths()) {
                    sendShellCwd(webSocketSession, cwd);
                }
                if (chunk.visibleBytes().length == 0) {
                    continue;
                }
                String encoded = Base64.getEncoder().encodeToString(chunk.visibleBytes());
                ObjectNode json = objectMapper.createObjectNode();
                json.put("type", "output");
                json.put("data", encoded);
                sendJson(webSocketSession, json);
            }
        } catch (IOException e) {
            if (connection.isConnected()) {
                log.debug("读取 SSH 输出失败: {}", e.getMessage());
            }
        } finally {
            connection.close();
            sendSimple(webSocketSession, "disconnected", "SSH 会话已断开。");
        }
    }

    // ==================== WebSocket 消息发送方法 ====================

    /** 发送 info 类型消息（提示信息） */
    private void sendInfo(WebSocketSession session, String msg) {
        sendSimple(session, "info", msg);
    }

    /** 发送 error 类型消息（错误提示） */
    private void sendError(WebSocketSession session, String msg) {
        sendSimple(session, "error", msg);
    }

    /** 发送 connected 消息，表示 SSH 连接已成功建立，附带主机指纹和初始 SFTP 路径 */
    private void sendConnected(WebSocketSession session, String fingerprint, String sftpPath) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("type", "connected");
        json.put("message", "SSH 连接成功。");
        json.put("hostFingerprint", fingerprint);
        json.put("sftpPath", hasText(sftpPath) ? sftpPath : ".");
        sendJson(session, json);
    }

    /** 发送 cwd 消息，通知前端远端 Shell 的当前工作目录已变化 */
    private void sendShellCwd(WebSocketSession session, String path) {
        if (!hasText(path)) {
            return;
        }
        ObjectNode json = objectMapper.createObjectNode();
        json.put("type", "cwd");
        json.put("path", path);
        sendJson(session, json);
    }

    /** 发送 sftp_list 消息，返回目录列表结果 */
    private void sendSftpList(WebSocketSession session, String path, ArrayNode entries) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("type", "sftp_list");
        json.put("path", path);
        json.set("entries", entries);
        sendJson(session, json);
    }

    /** 发送 sftp_upload 消息，通知前端文件上传已完成 */
    private void sendSftpUpload(WebSocketSession session, String path, long size) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("type", "sftp_upload");
        json.put("path", path);
        json.put("size", size);
        json.put("message", "上传成功。");
        sendJson(session, json);
    }

    /** 发送 sftp_download_start 消息，通知前端即将开始分块下载 */
    private void sendSftpDownloadStart(WebSocketSession session, String path, long size, String downloadId) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("type", "sftp_download_start");
        json.put("path", path);
        json.put("size", size);
        json.put("downloadId", downloadId);
        json.put("message", "开始下载。");
        sendJson(session, json);
    }

    /** 发送 sftp_download_chunk 消息，传递一个下载数据分块（Base64 编码） */
    private void sendSftpDownloadChunk(WebSocketSession session,
            String path,
            String data,
            long sentBytes,
            long totalBytes,
            boolean last,
            String downloadId,
            long chunkIndex) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("type", "sftp_download_chunk");
        json.put("path", path);
        json.put("data", data);
        json.put("sentBytes", sentBytes);
        json.put("totalBytes", totalBytes);
        json.put("last", last);
        json.put("downloadId", downloadId);
        json.put("chunkIndex", chunkIndex);
        sendJson(session, json);
    }

    /** 发送 sftp_mkdir 消息，通知前端目录创建成功 */
    private void sendSftpMkdir(WebSocketSession session, String path) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("type", "sftp_mkdir");
        json.put("path", path);
        json.put("message", "目录创建成功。");
        sendJson(session, json);
    }

    /** 发送 port_forward_list 消息，返回当前所有端口转发规则（按方向和端口排序） */
    private void sendPortForwardList(WebSocketSession session, String message, List<PortForwardRule> forwards) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("type", "port_forward_list");
        if (hasText(message)) {
            json.put("message", message);
        }
        ArrayNode items = objectMapper.createArrayNode();
        List<PortForwardRule> sorted = new ArrayList<>(forwards);
        sorted.sort(Comparator
                .comparing(PortForwardRule::direction)
                .thenComparingInt(PortForwardRule::bindPort)
                .thenComparing(PortForwardRule::bindHost));
        for (PortForwardRule rule : sorted) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("direction", rule.direction());
            node.put("bindHost", rule.bindHost());
            node.put("bindPort", rule.bindPort());
            node.put("targetHost", rule.targetHost());
            node.put("targetPort", rule.targetPort());
            items.add(node);
        }
        json.set("forwards", items);
        sendJson(session, json);
    }

    /** 发送 hostkey_required 消息，要求前端确认主机指纹（首次连接新主机时） */
    private void sendHostKeyRequired(WebSocketSession session,
            String host,
            int port,
            String fingerprint) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("type", "hostkey_required");
        json.put("host", host);
        json.put("port", port);
        json.put("hostFingerprint", fingerprint);
        json.put("message", "首次连接该主机，已回填主机指纹，请直接再次点击连接。");
        sendJson(session, json);
    }

    /** 发送简单消息（仅包含 type 和可选的 message 字段） */
    private void sendSimple(WebSocketSession session, String type, String msg) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("type", type);
        if (msg != null) {
            json.put("message", msg);
        }
        sendJson(session, json);
    }

    /**
     * 线程安全地发送 JSON 消息到 WebSocket 客户端。
     * <p>
     * 使用 synchronized 保证同一 session 的消息顺序发送，防止并发写入导致异常。
     */
    private void sendJson(WebSocketSession session, ObjectNode json) {
        if (session == null || !session.isOpen()) {
            return;
        }
        synchronized (session) {
            if (!session.isOpen()) {
                return;
            }
            try {
                session.sendMessage(new TextMessage(json.toString()));
            } catch (IOException | IllegalStateException e) {
                log.debug("发送 WebSocket 消息失败: {}", e.getMessage());
            }
        }
    }

    // ==================== 工具方法 ====================

    /** 判断异常链中是否包含 "WebSocket session has been closed" 的 IllegalStateException */
    private boolean isWebSocketClosedSendError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof IllegalStateException) {
                String message = current.getMessage();
                if (message != null && message.contains("WebSocket session has been closed")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    // --- JSON 字段提取工具方法 ---

    /** 从 JSON 节点中提取字符串字段，null 或不存在返回 null */
    private String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    /** 从 JSON 节点中提取整数字段，不存在时返回默认值 */
    private int intOf(JsonNode node, String field, int defaultValue) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        return value.asInt(defaultValue);
    }

    /** 从 JSON 节点中提取布尔字段 */
    private boolean boolOf(JsonNode node, String field, boolean defaultValue) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        return value.asBoolean(defaultValue);
    }

    /** 从 JSON 节点中提取 long 字段 */
    private long longOf(JsonNode node, String field, long defaultValue) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        return value.asLong(defaultValue);
    }

    /** 检查字符串是否非 null 且非空白 */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    // --- SSH 算法和指纹工具方法 ---

    /** 将算法追加到逗号分隔的算法列表中（避免重复追加） */
    private String appendAlgorithm(String existingAlgorithms, String algorithm) {
        if (!hasText(existingAlgorithms)) {
            return algorithm;
        }
        String[] items = existingAlgorithms.split(",");
        for (String item : items) {
            if (algorithm.equals(item.trim())) {
                return existingAlgorithms;
            }
        }
        return existingAlgorithms + "," + algorithm;
    }

    /** 规范化主机指纹格式，确保以 "SHA256:" 前缀开头 */
    private String normalizeFingerprint(String value) {
        if (!hasText(value)) {
            return null;
        }
        String v = value.trim();
        if (v.startsWith("SHA256:")) {
            return "SHA256:" + v.substring("SHA256:".length()).trim();
        }
        return "SHA256:" + v;
    }

    /** 规范化认证类型为大写（PASSWORD / PRIVATE_KEY） */
    private String normalizeAuthType(String authType) {
        if (!hasText(authType)) {
            return null;
        }
        return authType.trim().toUpperCase();
    }

    /** 根据提供的凭据自动推断认证类型：有密码用 PASSWORD，有私钥用 PRIVATE_KEY */
    private String inferAuthType(String password, String privateKey) {
        if (hasText(password)) {
            return "PASSWORD";
        }
        if (hasText(privateKey)) {
            return "PRIVATE_KEY";
        }
        return null;
    }

    /**
     * 从已连接的 SSH 会话中提取主机公钥的 SHA-256 指纹。
     * <p>
     * 格式为 "SHA256:base64-without-padding"，与 OpenSSH 的 ssh-keygen -lf 输出格式一致。
     */
    private String extractSha256Fingerprint(Session sshSession) {
        try {
            HostKey hostKey = sshSession.getHostKey();
            if (hostKey == null || !hasText(hostKey.getKey())) {
                return null;
            }
            byte[] keyBytes = Base64.getDecoder().decode(hostKey.getKey());
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(keyBytes);
            String encoded = Base64.getEncoder().withoutPadding().encodeToString(digest);
            return "SHA256:" + encoded;
        } catch (Exception e) {
            return null;
        }
    }

    /** 安全获取异常消息，如果消息为空则返回异常类名 */
    private String safeMessage(Throwable e) {
        if (e.getMessage() == null || e.getMessage().isBlank()) {
            return e.getClass().getSimpleName();
        }
        return e.getMessage();
    }

    /**
     * 将 SSH 异常消息转换为用户友好的中文提示。
     * <p>
     * 针对常见错误场景（算法不匹配、认证失败）提供具体的解决建议。
     */
    private String friendlySshError(Exception e, String authType) {
        String raw = safeMessage(e);
        if (raw.contains("Algorithm negotiation fail")
                && raw.contains("algorithmName=\"server_host_key\"")
                && raw.contains("serverProposal=\"ssh-rsa\"")) {
            return "目标主机仅支持过时主机密钥算法 ssh-rsa。请在 application.properties 中开启 "
                    + "webssh.ssh.allow-legacy-ssh-rsa=true（修改后需重启），或升级目标主机到 ed25519/rsa-sha2。";
        }
        if (raw.contains("Auth fail")) {
            if ("PASSWORD".equals(authType)) {
                return "密码认证失败。请检查用户名/密码，或确认服务器开启 PasswordAuthentication。";
            }
            if ("PRIVATE_KEY".equals(authType)) {
                return "私钥认证失败。请检查私钥内容/口令，或确认服务器已配置对应公钥。";
            }
            return "认证失败。请检查用户名和凭据是否正确。";
        }
        return raw;
    }

    /** 将 SFTP 异常转换为用户友好的中文提示，特别处理"目标主机未启用 SFTP"的情况 */
    private String friendlySftpError(Exception e) {
        String raw = safeMessage(e);
        String normalized = raw.toLowerCase();
        if (normalized.contains("inputstream is closed")
                || normalized.contains("pipe closed")
                || normalized.contains("channel is not opened")
                || normalized.contains("channel is down")
                || normalized.contains("session is down")) {
            return "目标主机拒绝或未启用 SFTP 子系统，终端 SSH 可继续使用。";
        }
        if (normalized.contains("connection refused")
                || normalized.contains("connection timed out")
                || normalized.contains("socket is not established")
                || normalized.contains("read timed out")) {
            return "SFTP 连接超时或被拒绝，服务器可能限制了并发连接数，终端 SSH 可继续使用。";
        }
        return raw;
    }

    /** 规范化端口转发方向，默认为 LOCAL */
    private String normalizeDirection(String direction) {
        if (!hasText(direction)) {
            return "LOCAL";
        }
        String normalized = direction.trim().toUpperCase();
        if (!"LOCAL".equals(normalized) && !"REMOTE".equals(normalized)) {
            return "LOCAL";
        }
        return normalized;
    }

    /** 规范化绑定地址，默认为 127.0.0.1（仅本机可访问） */
    private String normalizeBindHost(String bindHost) {
        if (!hasText(bindHost)) {
            return "127.0.0.1";
        }
        return bindHost.trim();
    }

    /** 将工作目录追踪命令逐条写入远端 Shell，安装 CWD 同步钩子 */
    private void installShellCwdSync(ClientConnection connection) throws IOException {
        // 尝试关闭终端回显（{ ...; } 2>/dev/null 确保 stty 不存在时静默失败）
        connection.write("{ stty -echo; } 2>/dev/null\n");
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (String command : SHELL_CWD_INIT_COMMANDS) {
            connection.write(command + "\n");
        }
        // 恢复回显
        connection.write("{ stty echo; } 2>/dev/null\n");
    }

    // ==================== SSH 会话与 SFTP 基础设施 ====================

    /**
     * 建立并认证 SSH 会话。
     *
     * <p>
     * 处理流程：
     * <ol>
     * <li>根据认证类型加载私钥或设置密码</li>
     * <li>创建 JSch Session 并应用 SSH 配置</li>
     * <li>连接到远端（超时 10 秒）</li>
     * <li>提取并校验主机指纹（SHA-256）</li>
     * </ol>
     *
     * @param sessionConfig SSH 连接配置
     * @return 已认证的会话及其指纹
     * @throws Exception 连接或认证失败
     */
    private OpenedSession openAuthenticatedSession(SshSessionConfig sessionConfig) throws Exception {
        JSch jsch = new JSch();
        if ("PRIVATE_KEY".equals(sessionConfig.authType())) {
            byte[] passphraseBytes = hasText(sessionConfig.passphrase())
                    ? sessionConfig.passphrase().getBytes(StandardCharsets.UTF_8)
                    : null;
            jsch.addIdentity(
                    "web-ssh-key-" + UUID.randomUUID(),
                    sessionConfig.privateKey().getBytes(StandardCharsets.UTF_8),
                    null,
                    passphraseBytes);
        }

        Session sshSession = null;
        try {
            sshSession = jsch.getSession(sessionConfig.username(), sessionConfig.host(), sessionConfig.port());
            if ("PASSWORD".equals(sessionConfig.authType())) {
                sshSession.setPassword(sessionConfig.password());
            }
            sshSession.setConfig(buildSshConfig(sshSession, sessionConfig.authType()));
            int keepAliveIntervalMs = sshCompatibilityProperties.getServerAliveIntervalMs();
            if (keepAliveIntervalMs > 0) {
                sshSession.setServerAliveInterval(keepAliveIntervalMs);
            }
            int keepAliveCountMax = sshCompatibilityProperties.getServerAliveCountMax();
            if (keepAliveCountMax > 0) {
                sshSession.setServerAliveCountMax(keepAliveCountMax);
            }
            sshSession.connect(10_000);

            String actualFingerprint = extractSha256Fingerprint(sshSession);
            if (!hasText(actualFingerprint)) {
                throw new IllegalStateException("读取主机指纹失败，请检查目标主机配置。");
            }

            String expectedFingerprint = hasText(sessionConfig.expectedFingerprint())
                    ? sessionConfig.expectedFingerprint()
                    : actualFingerprint;
            if (!actualFingerprint.equalsIgnoreCase(expectedFingerprint)) {
                throw new IllegalStateException("主机指纹校验失败，期望 "
                        + expectedFingerprint + "，实际 " + actualFingerprint);
            }
            return new OpenedSession(sshSession, actualFingerprint);
        } catch (Exception e) {
            disconnectQuietly(sshSession);
            throw e;
        }
    }

    /**
     * 构建 JSch SSH 会话配置。
     * <p>
     * 禁用严格主机密钥检查（由应用层自行校验指纹），
     * 根据认证类型设置优先认证方式，可选启用旧版 ssh-rsa 算法。
     */
    private Properties buildSshConfig(Session sshSession, String authType) {
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        config.put("PreferredAuthentications", "PASSWORD".equals(authType)
                ? "password"
                : "publickey");
        if (sshCompatibilityProperties.isAllowLegacySshRsa()) {
            config.put("server_host_key", appendAlgorithm(sshSession.getConfig("server_host_key"), "ssh-rsa"));
            config.put("PubkeyAcceptedAlgorithms",
                    appendAlgorithm(sshSession.getConfig("PubkeyAcceptedAlgorithms"), "ssh-rsa"));
        }
        return config;
    }

    /**
     * 为 SFTP 操作打开一个独立的 SSH 会话和 SFTP 通道。
     * <p>
     * SFTP 使用独立会话而非复用 Shell 会话，避免在不支持 SFTP 的服务器上
     * 导致 Shell 连接断开。连接后设置 SO_TIMEOUT 防止后续读操作无限阻塞。
     */
    private SftpClient openSftpClient(ClientConnection connection) throws Exception {
        SshSessionConfig sessionConfig = connection.sftpSessionConfig();
        if (sessionConfig == null) {
            throw new IllegalStateException("SSH 会话不可用");
        }

        OpenedSession openedSession = openAuthenticatedSession(sessionConfig);
        Session sftpSession = openedSession.session();
        try {
            sftpSession.setTimeout(15_000);
            ChannelSftp sftp = (ChannelSftp) sftpSession.openChannel("sftp");
            sftp.connect(5_000);
            return new SftpClient(sftpSession, sftp, true);
        } catch (Exception e) {
            disconnectQuietly(sftpSession);
            throw e;
        }
    }

    /**
     * 使用共享的 SFTP 连接执行任务（连接池模式）。
     * <p>
     * 如果共享连接不存在或已断开，自动创建新连接。
     * 如果任务执行中出错，重置共享连接以便下次重建。
     * 适用于 sftp_list 等频繁但轻量的操作。
     */
    private <T> T withSharedSftp(ClientConnection connection, SftpChannelTask<T> task) throws Exception {
        synchronized (connection.sftpLock) {
            SftpClient sftpClient = connection.sharedSftpClient;
            if (sftpClient == null
                    || !sftpClient.session().isConnected()
                    || !sftpClient.channel().isConnected()) {
                closeQuietly(sftpClient);
                sftpClient = openSftpClient(connection);
                connection.sharedSftpClient = sftpClient;
            }
            try {
                return task.run(sftpClient.channel());
            } catch (Exception e) {
                resetSharedSftpLocked(connection);
                throw e;
            }
        }
    }

    /** 重置共享 SFTP 连接（在持锁状态下调用） */
    private void resetSharedSftpLocked(ClientConnection connection) {
        SftpClient sftpClient = connection.sharedSftpClient;
        connection.sharedSftpClient = null;
        closeQuietly(sftpClient);
    }

    // --- SFTP 路径工具方法 ---

    /** 解析远端路径为绝对路径（realpath），失败时返回原路径 */
    private String resolvePath(ChannelSftp sftp, String path) {
        try {
            return sftp.realpath(path);
        } catch (Exception ignored) {
            return path;
        }
    }

    /** 拼接远端路径：parent + "/" + name，处理根目录和相对路径等边界情况 */
    private String joinRemotePath(String parent, String name) {
        if (!hasText(parent) || ".".equals(parent)) {
            return name;
        }
        if ("/".equals(parent)) {
            return "/" + name;
        }
        if (parent.endsWith("/")) {
            return parent + name;
        }
        return parent + "/" + name;
    }

    /** 确保文件的父目录存在（递归创建） */
    private void ensureParentDirectory(ChannelSftp sftp, String filePath) throws SftpException {
        String normalized = normalizePath(filePath);
        int idx = normalized.lastIndexOf('/');
        if (idx < 0) {
            return;
        }
        String parent = idx == 0 ? "/" : normalized.substring(0, idx);
        if (!hasText(parent) || ".".equals(parent)) {
            return;
        }
        ensureDirectoryExists(sftp, parent);
    }

    /** 递归确保指定目录路径存在，逐级创建缺失的目录（安全起见，禁止 ".." 路径） */
    private void ensureDirectoryExists(ChannelSftp sftp, String dirPath) throws SftpException {
        String normalized = normalizePath(dirPath);
        if (!hasText(normalized) || ".".equals(normalized)) {
            return;
        }
        if ("/".equals(normalized)) {
            return;
        }

        boolean absolute = normalized.startsWith("/");
        String[] segments = normalized.split("/");
        String current = absolute ? "/" : ".";

        for (String segment : segments) {
            if (!hasText(segment) || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new SftpException(ChannelSftp.SSH_FX_FAILURE, "不支持路径 '..'");
            }

            String next;
            if ("/".equals(current)) {
                next = "/" + segment;
            } else if (".".equals(current)) {
                next = segment;
            } else {
                next = current + "/" + segment;
            }

            try {
                SftpATTRS attrs = sftp.lstat(next);
                if (attrs == null || !attrs.isDir()) {
                    throw new SftpException(ChannelSftp.SSH_FX_FAILURE, "路径不是目录: " + next);
                }
            } catch (SftpException e) {
                if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    throw e;
                }
                sftp.mkdir(next);
            }
            current = next;
        }
    }

    /** 规范化路径：去除首尾空白，将反斜杠替换为正斜杠 */
    private String normalizePath(String path) {
        if (!hasText(path)) {
            return ".";
        }
        return path.trim().replace('\\', '/');
    }

    /** 检查远端路径是否存在 */
    private boolean exists(ChannelSftp sftp, String path) throws SftpException {
        try {
            sftp.lstat(path);
            return true;
        } catch (SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                return false;
            }
            throw e;
        }
    }

    /** 从 SSH 会话中移除指定的端口转发规则 */
    private void removePortForward(Session session, PortForwardRule rule) throws Exception {
        if ("REMOTE".equals(rule.direction())) {
            session.delPortForwardingR(rule.bindHost(), rule.bindPort());
            return;
        }
        session.delPortForwardingL(rule.bindHost(), rule.bindPort());
    }

    // --- 资源清理工具方法（静默关闭，忽略异常） ---

    /** 静默断开 Shell 通道 */
    private void disconnectQuietly(ChannelShell channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.disconnect();
        } catch (Exception ignored) {
            // ignore close exception
        }
    }

    /** 静默断开 SFTP 通道 */
    private void disconnectQuietly(ChannelSftp channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.disconnect();
        } catch (Exception ignored) {
            // ignore close exception
        }
    }

    /** 静默断开 SSH 会话 */
    private void disconnectQuietly(Session session) {
        if (session == null) {
            return;
        }
        try {
            session.disconnect();
        } catch (Exception ignored) {
            // ignore close exception
        }
    }

    /** 静默关闭任意 AutoCloseable 资源 */
    private void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // 忽略关闭异常
        }
    }

    // ==================== 内部类定义 ====================

    /**
     * 客户端连接状态容器 —— 封装单个 WebSocket 客户端的所有运行时状态。
     *
     * <p>
     * 每个浏览器标签页对应一个 ClientConnection 实例，包含：
     * <ul>
     * <li>WebSocket 会话引用</li>
     * <li>SSH 会话和 Shell 通道</li>
     * <li>Shell 输入/输出流</li>
     * <li>共享的 SFTP 连接（用于列表等轻量操作）</li>
     * <li>端口转发规则集合</li>
     * <li>上传/下载任务的状态追踪</li>
     * <li>Shell 输出过滤器（用于 CWD 追踪）</li>
     * </ul>
     */
    private static final class ClientConnection {
        private final WebSocketSession webSocketSession;
        private final Object writeLock = new Object();
        private final Object sftpLock = new Object();
        private final ShellOutputFilter shellOutputFilter = new ShellOutputFilter();
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private volatile Session sshSession;
        private volatile ChannelShell channel;
        private volatile OutputStream inputWriter;
        private volatile InputStream outputReader;
        private volatile SshSessionConfig sftpSessionConfig;
        private SftpClient sharedSftpClient;
        private final ConcurrentMap<String, PortForwardRule> forwardRules = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, UploadContext> uploads = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, DownloadState> downloads = new ConcurrentHashMap<>();

        private ClientConnection(WebSocketSession webSocketSession) {
            this.webSocketSession = webSocketSession;
        }

        private WebSocketSession webSocketSession() {
            return webSocketSession;
        }

        private void attach(Session sshSession,
                ChannelShell channel,
                OutputStream inputWriter,
                InputStream outputReader,
                SshSessionConfig sftpSessionConfig) {
            closed.set(false);
            closeSharedSftp();
            this.sshSession = sshSession;
            this.channel = channel;
            this.inputWriter = inputWriter;
            this.outputReader = outputReader;
            this.sftpSessionConfig = sftpSessionConfig;
            this.forwardRules.clear();
            closeUploads();
            closeDownloads();
            shellOutputFilter.reset();
        }

        private boolean isClosed() {
            return closed.get();
        }

        private boolean isConnected() {
            return !closed.get()
                    && sshSession != null
                    && channel != null
                    && sshSession.isConnected()
                    && channel.isConnected();
        }

        private boolean hasSessionConnected() {
            return sshSession != null && sshSession.isConnected();
        }

        private void write(String data) throws IOException {
            OutputStream writer = this.inputWriter;
            if (writer == null) {
                throw new IOException("SSH 输入流不存在");
            }
            synchronized (writeLock) {
                writer.write(data.getBytes(StandardCharsets.UTF_8));
                writer.flush();
            }
        }

        private void armShellCwdSync() {
            // 只过滤 stty -echo 的回显；init 命令在 echo 关闭后发送不会被回显
            // 无 stty 环境中 init 命令回显会短暂显示（降级体验，可接受）
            shellOutputFilter.armInitialization(List.of("{ stty -echo; } 2>/dev/null"));
        }

        private ShellOutputChunk filterShellOutput(byte[] data, int len) {
            return shellOutputFilter.consume(data, len);
        }

        private ChannelShell channel() {
            return channel;
        }

        private InputStream outputReader() {
            return outputReader;
        }

        private Session sshSession() {
            return sshSession;
        }

        private SshSessionConfig sftpSessionConfig() {
            return sftpSessionConfig;
        }

        private void addForward(PortForwardRule rule) {
            forwardRules.put(rule.key(), rule);
        }

        private PortForwardRule removeForward(String direction, String bindHost, int bindPort) {
            return forwardRules.remove(PortForwardRule.keyOf(direction, bindHost, bindPort));
        }

        private List<PortForwardRule> forwardsSnapshot() {
            return new ArrayList<>(forwardRules.values());
        }

        private void putUpload(String uploadId, UploadContext context) {
            uploads.put(uploadId, context);
        }

        private UploadContext upload(String uploadId) {
            return uploads.get(uploadId);
        }

        private UploadContext removeUpload(String uploadId) {
            return uploads.remove(uploadId);
        }

        private void closeUploads() {
            uploads.values().forEach(UploadContext::close);
            uploads.clear();
        }

        private void cleanupStaleUploads(long now, long timeoutMs) {
            uploads.entrySet().removeIf(entry -> {
                if (now - entry.getValue().createdAt() > timeoutMs) {
                    log.debug("清理超时上传会话: {}", entry.getKey());
                    entry.getValue().close();
                    return true;
                }
                return false;
            });
        }

        private void startDownload(String downloadId) {
            DownloadState previous = downloads.put(downloadId, new DownloadState());
            if (previous != null) {
                previous.close();
            }
        }

        private void ackDownload(String downloadId, long chunkIndex) {
            DownloadState state = downloads.get(downloadId);
            if (state == null) {
                return;
            }
            state.ack(chunkIndex);
        }

        private boolean awaitDownloadAck(String downloadId, long chunkIndex, long timeoutMs) {
            DownloadState state = downloads.get(downloadId);
            if (state == null) {
                return false;
            }
            return state.awaitAck(chunkIndex, timeoutMs);
        }

        private void finishDownload(String downloadId) {
            DownloadState state = downloads.remove(downloadId);
            if (state != null) {
                state.close();
            }
        }

        private void closeDownloads() {
            downloads.values().forEach(DownloadState::close);
            downloads.clear();
        }

        private void closeSharedSftp() {
            synchronized (sftpLock) {
                closeQuietly(sharedSftpClient);
                sharedSftpClient = null;
            }
        }

        private void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            closeSharedSftp();
            closeUploads();
            closeDownloads();
            closeQuietly(inputWriter);
            closeQuietly(outputReader);

            if (channel != null) {
                try {
                    channel.disconnect();
                } catch (Exception ignored) {
                }
            }
            if (sshSession != null) {
                try {
                    sshSession.disconnect();
                } catch (Exception ignored) {
                }
            }

            inputWriter = null;
            outputReader = null;
            channel = null;
            sshSession = null;
            sftpSessionConfig = null;
            forwardRules.clear();
        }

        private void closeQuietly(AutoCloseable closeable) {
            if (closeable == null) {
                return;
            }
            try {
                closeable.close();
            } catch (Exception ignored) {
                // ignore close exception
            }
        }
    }

    /**
     * Shell 输出过滤结果 —— 包含过滤后的可见输出和提取到的工作目录路径。
     *
     * @see ShellOutputFilter#consume(byte[], int)
     */
    private static final class ShellOutputChunk {
        private static final ShellOutputChunk EMPTY = new ShellOutputChunk(new byte[0], List.of());

        private final byte[] visibleBytes;
        private final List<String> cwdPaths;

        private ShellOutputChunk(byte[] visibleBytes, List<String> cwdPaths) {
            this.visibleBytes = visibleBytes;
            this.cwdPaths = cwdPaths;
        }

        private byte[] visibleBytes() {
            return visibleBytes;
        }

        private List<String> cwdPaths() {
            return cwdPaths;
        }
    }

    /**
     * Shell 输出过滤器 —— 从终端原始输出中提取工作目录标记并过滤注入命令的回显。
     *
     * <p>
     * 设计思路：
     * <ul>
     * <li>连接建立后会向 Shell 注入 CWD 追踪命令，这些命令的回显需要从输出中剥离</li>
     * <li>Shell 每次执行命令后会输出 {@code \002__WEBSSH_CWD__:/path\003} 标记</li>
     * <li>过滤器负责识别并提取这些标记，同时将其从用户可见输出中移除</li>
     * </ul>
     *
     * <p>
     * 使用内部缓冲区处理跨数据块边界的标记（标记可能被拆分到两次 read 调用中）。
     */
    private static final class ShellOutputFilter {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final List<byte[]> pendingInitEchoLines = new ArrayList<>();

        private void reset() {
            buffer.reset();
            pendingInitEchoLines.clear();
        }

        private void armInitialization(List<String> commands) {
            reset();
            for (String command : commands) {
                pendingInitEchoLines.add(command.getBytes(StandardCharsets.UTF_8));
            }
        }

        private ShellOutputChunk consume(byte[] data, int len) {
            if (len <= 0) {
                return ShellOutputChunk.EMPTY;
            }
            buffer.write(data, 0, len);

            if (buffer.size() > SHELL_OUTPUT_BUFFER_LIMIT) {
                byte[] overflow = buffer.toByteArray();
                buffer.reset();
                pendingInitEchoLines.clear();
                return new ShellOutputChunk(overflow, List.of());
            }

            byte[] current = buffer.toByteArray();
            ByteArrayOutputStream visible = new ByteArrayOutputStream(current.length);
            List<String> cwdPaths = new ArrayList<>();
            int offset = 0;

            while (offset < current.length) {
                int nextMarker = indexOf(current, SHELL_CWD_MARKER_START, offset);
                int nextEcho = nextPendingEchoPosition(current, offset);
                int eventPos = earliestPositive(nextMarker, nextEcho);

                if (eventPos < 0) {
                    if (!pendingInitEchoLines.isEmpty()) {
                        int lastLineBreak = lastLineBreak(current, offset, current.length);
                        if (lastLineBreak >= offset) {
                            visible.write(current, offset, lastLineBreak + 1 - offset);
                            offset = lastLineBreak + 1;
                            continue;
                        }
                    } else {
                        visible.write(current, offset, current.length - offset);
                        offset = current.length;
                    }
                    break;
                }

                if (eventPos > offset) {
                    if (eventPos == nextEcho) {
                        int lastLineBreak = lastLineBreak(current, offset, eventPos);
                        if (lastLineBreak >= offset) {
                            visible.write(current, offset, lastLineBreak + 1 - offset);
                        }
                    } else {
                        visible.write(current, offset, eventPos - offset);
                    }
                    offset = eventPos;
                }

                if (offset >= current.length) {
                    break;
                }

                if (offset == nextEcho) {
                    byte[] line = pendingInitEchoLines.get(0);
                    if (!startsWith(current, offset, line)) {
                        visible.write(current[offset]);
                        offset += 1;
                        continue;
                    }
                    offset += line.length;
                    while (offset < current.length && (current[offset] == '\r' || current[offset] == '\n')) {
                        offset += 1;
                    }
                    pendingInitEchoLines.remove(0);
                    continue;
                }

                if (offset == nextMarker) {
                    if (current.length < offset + 1 + SHELL_CWD_MARKER_PREFIX.length) {
                        break;
                    }
                    if (!startsWith(current, offset + 1, SHELL_CWD_MARKER_PREFIX)) {
                        visible.write(current[offset]);
                        offset += 1;
                        continue;
                    }
                    int pathStart = offset + 1 + SHELL_CWD_MARKER_PREFIX.length;
                    int pathEnd = indexOf(current, SHELL_CWD_MARKER_END, pathStart);
                    if (pathEnd < 0) {
                        break;
                    }
                    if (pathEnd > pathStart) {
                        cwdPaths.add(new String(current, pathStart, pathEnd - pathStart, StandardCharsets.UTF_8));
                    }
                    offset = pathEnd + 1;
                }
            }

            buffer.reset();
            if (offset < current.length) {
                buffer.write(current, offset, current.length - offset);
            }
            if (visible.size() == 0 && cwdPaths.isEmpty()) {
                return ShellOutputChunk.EMPTY;
            }
            return new ShellOutputChunk(visible.toByteArray(), cwdPaths);
        }

        private int nextPendingEchoPosition(byte[] data, int fromIndex) {
            if (pendingInitEchoLines.isEmpty()) {
                return -1;
            }
            return indexOf(data, pendingInitEchoLines.get(0), fromIndex);
        }

        private static int earliestPositive(int a, int b) {
            if (a < 0) {
                return b;
            }
            if (b < 0) {
                return a;
            }
            return Math.min(a, b);
        }

        private static int lastLineBreak(byte[] data, int fromInclusive, int toExclusive) {
            for (int i = toExclusive - 1; i >= fromInclusive; i--) {
                if (data[i] == '\n' || data[i] == '\r') {
                    return i;
                }
            }
            return -1;
        }

        private static int indexOf(byte[] data, byte value, int fromIndex) {
            for (int i = Math.max(fromIndex, 0); i < data.length; i++) {
                if (data[i] == value) {
                    return i;
                }
            }
            return -1;
        }

        private static int indexOf(byte[] data, byte[] pattern, int fromIndex) {
            if (pattern.length == 0) {
                return Math.max(fromIndex, 0);
            }
            for (int i = Math.max(fromIndex, 0); i <= data.length - pattern.length; i++) {
                if (startsWith(data, i, pattern)) {
                    return i;
                }
            }
            return -1;
        }

        private static boolean startsWith(byte[] data, int offset, byte[] pattern) {
            if (offset < 0 || offset + pattern.length > data.length) {
                return false;
            }
            for (int i = 0; i < pattern.length; i++) {
                if (data[offset + i] != pattern[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * 下载流控状态 —— 用于实现 SFTP 下载的 ACK 流控机制。
     *
     * <p>
     * 服务端每发送一个数据块后调用 {@link #awaitAck} 等待前端确认，
     * 前端收到数据块后发送 {@code sftp_download_ack} 消息触发 {@link #ack}。
     * 这种生产者-消费者模式防止服务端发送速度远超浏览器处理速度。
     */
    private static final class DownloadState {
        private long ackedChunkIndex = -1L;
        private boolean closed;

        private synchronized void ack(long chunkIndex) {
            if (chunkIndex > ackedChunkIndex) {
                ackedChunkIndex = chunkIndex;
            }
            notifyAll();
        }

        private synchronized boolean awaitAck(long chunkIndex, long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!closed && ackedChunkIndex < chunkIndex) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return !closed && ackedChunkIndex >= chunkIndex;
        }

        private synchronized void close() {
            closed = true;
            notifyAll();
        }
    }

    /**
     * 分片上传上下文 —— 跟踪单个文件的分片上传状态。
     *
     * <p>
     * 保存了远端文件的输出流和 SFTP 客户端引用，
     * 由 {@code sftp_upload_start} 创建，{@code sftp_upload_chunk} 逐块写入，
     * 最后一个分块（{@code last=true}）时关闭并释放资源。
     */
    private static final class UploadContext {
        private final String path;
        private final SftpClient sftpClient;
        private final OutputStream remoteWriter;
        private final long createdAt = System.currentTimeMillis();
        private long bytesWritten;

        private UploadContext(String path,
                SftpClient sftpClient,
                OutputStream remoteWriter) {
            this.path = path;
            this.sftpClient = sftpClient;
            this.remoteWriter = remoteWriter;
        }

        private synchronized void write(byte[] bytes) throws IOException {
            if (bytes.length == 0) {
                return;
            }
            remoteWriter.write(bytes);
            remoteWriter.flush();
            bytesWritten += bytes.length;
        }

        private synchronized long bytesWritten() {
            return bytesWritten;
        }

        private String path() {
            return path;
        }

        private long createdAt() {
            return createdAt;
        }

        private void close() {
            try {
                remoteWriter.close();
            } catch (Exception ignored) {
                // ignore close exception
            }
            try {
                sftpClient.close();
            } catch (Exception ignored) {
                // ignore close exception
            }
        }
    }

    /**
     * 端口转发规则 —— 不可变记录类，表示一条 SSH 端口转发配置。
     * <p>
     * 使用 "direction|bindHost|bindPort" 作为唯一键，支持同一绑定端口的规则替换。
     */
    private record PortForwardRule(
            String direction,
            String bindHost,
            int bindPort,
            String targetHost,
            int targetPort) {
        private String key() {
            return keyOf(direction, bindHost, bindPort);
        }

        private static String keyOf(String direction, String bindHost, int bindPort) {
            return direction + "|" + bindHost + "|" + bindPort;
        }
    }
}
