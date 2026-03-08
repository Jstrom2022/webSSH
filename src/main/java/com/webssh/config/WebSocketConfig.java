package com.webssh.config;

import com.webssh.ws.SshWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * WebSocket 配置类。
 * <p>
 * 负责注册 SSH 终端对应的 WebSocket 端点，并配置消息缓冲区大小。前端通过 {@code /ws/ssh} 建立
 * WebSocket 连接后，可在此通道上收发 SSH 会话数据（键盘输入、终端输出等），实现浏览器内的 SSH 终端。
 * </p>
 *
 * @see SshWebSocketHandler 处理 SSH 协议与 WebSocket 的桥接
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    /**
     * WebSocket 单条消息的最大字节数（8MB）。
     * <p>
     * SSH 终端可能传输大量输出（如 cat 大文件、ls 大量文件），设置较大缓冲区可避免消息被截断。
     * 过小会导致长输出丢失，过大则占用更多内存，8MB 是常见的折中值。
     * </p>
     */
    private static final int WS_MAX_MESSAGE_BYTES = 8 * 1024 * 1024;
    /** SSH WebSocket 处理器，负责建立 SSH 连接并转发终端 I/O */
    private final SshWebSocketHandler sshWebSocketHandler;

    /**
     * 构造函数，注入 SSH WebSocket 处理器。
     *
     * @param sshWebSocketHandler 由 Spring 自动注入的处理器实例
     */
    public WebSocketConfig(SshWebSocketHandler sshWebSocketHandler) {
        this.sshWebSocketHandler = sshWebSocketHandler;
    }

    /**
     * 注册 WebSocket 处理器到指定路径。
     * <p>
     * 将 {@code /ws/ssh} 映射到 {@link SshWebSocketHandler}，客户端连接此路径后即可开始 SSH 会话。
     * 路径选择 {@code /ws/ssh} 是为了语义清晰，且与常见的 WebSocket 路径规范一致。
     * </p>
     *
     * @param registry WebSocket 处理器注册表
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(sshWebSocketHandler, "/ws/ssh");
    }

    /**
     * 配置 WebSocket 容器的消息缓冲区大小。
     * <p>
     * 底层 Servlet 容器（如 Tomcat）对 WebSocket 消息有默认大小限制，通常较小。
     * 通过本 Bean 提高限制，避免 SSH 终端大量输出时触发缓冲区溢出或消息被拒绝。
     * 文本和二进制消息使用相同限制，因为 SSH 通道可能传输两种类型。
     * </p>
     *
     * @return 配置好的 Servlet WebSocket 容器工厂
     */
    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(WS_MAX_MESSAGE_BYTES);
        container.setMaxBinaryMessageBufferSize(WS_MAX_MESSAGE_BYTES);
        container.setMaxSessionIdleTimeout(30 * 60 * 1000L);
        return container;
    }
}
