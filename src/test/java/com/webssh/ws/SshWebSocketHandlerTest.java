package com.webssh.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webssh.config.ResourceGovernanceProperties;
import com.webssh.config.SshCompatibilityProperties;
import com.webssh.task.UserResourceGovernor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SshWebSocketHandlerTest {

    @Mock
    private SshCompatibilityProperties sshCompatibilityProperties;

    @Mock
    private WebSocketSession webSocketSession;

    @Captor
    private ArgumentCaptor<TextMessage> messageCaptor;

    @Test
    void shouldReplyPongWhenReceivePing() throws Exception {
        SshWebSocketHandler handler = new SshWebSocketHandler(
                sshCompatibilityProperties,
                new ObjectMapper(),
                new ResourceGovernanceProperties(),
                new UserResourceGovernor(new ResourceGovernanceProperties()));
        try {
            when(webSocketSession.getId()).thenReturn("ws-test");
            when(webSocketSession.isOpen()).thenReturn(true);
            doNothing().when(webSocketSession).setTextMessageSizeLimit(anyInt());
            doNothing().when(webSocketSession).sendMessage(any(TextMessage.class));

            handler.afterConnectionEstablished(webSocketSession);
            handler.handleTextMessage(webSocketSession, new TextMessage("{\"type\":\"ping\"}"));

            verify(webSocketSession, atLeastOnce()).sendMessage(messageCaptor.capture());
            List<TextMessage> messages = messageCaptor.getAllValues();
            String lastPayload = messages.get(messages.size() - 1).getPayload();
            assertTrue(lastPayload.contains("\"type\":\"pong\""));
        } finally {
            handler.shutdown();
        }
    }

    @Test
    void shouldUseSeparatedExecutorsForShellOutputAndSftpTasks() throws Exception {
        SshWebSocketHandler handler = new SshWebSocketHandler(
                sshCompatibilityProperties,
                new ObjectMapper(),
                new ResourceGovernanceProperties(),
                new UserResourceGovernor(new ResourceGovernanceProperties()));
        try {
            ThreadPoolExecutor sftpExecutor = (ThreadPoolExecutor) readField(handler, "sftpExecutor");
            ExecutorService shellOutputExecutor = (ExecutorService) readField(handler, "shellOutputExecutor");
            ThreadPoolExecutor shellOutputThreadPool = (ThreadPoolExecutor) shellOutputExecutor;

            assertNotSame(sftpExecutor, shellOutputExecutor);
            assertTrue(sftpExecutor.getQueue() instanceof ArrayBlockingQueue);
            assertTrue(shellOutputThreadPool.getQueue() instanceof SynchronousQueue);
        } finally {
            handler.shutdown();
        }
    }

    @Test
    void shutdownShouldCloseBothExecutors() throws Exception {
        SshWebSocketHandler handler = new SshWebSocketHandler(
                sshCompatibilityProperties,
                new ObjectMapper(),
                new ResourceGovernanceProperties(),
                new UserResourceGovernor(new ResourceGovernanceProperties()));
        ThreadPoolExecutor sftpExecutor = (ThreadPoolExecutor) readField(handler, "sftpExecutor");
        ExecutorService shellOutputExecutor = (ExecutorService) readField(handler, "shellOutputExecutor");

        handler.shutdown();

        assertTrue(sftpExecutor.isShutdown());
        assertTrue(shellOutputExecutor.isShutdown());
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
