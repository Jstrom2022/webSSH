package com.webssh.bot;

import com.webssh.config.ResourceGovernanceProperties;
import com.webssh.task.UserResourceGovernor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotInteractionServiceTest {

    @Mock
    private BotSshSessionManager sshManager;

    @Mock
    private AiCliExecutor aiCliExecutor;

    private BotInteractionService newService() {
        ResourceGovernanceProperties properties = new ResourceGovernanceProperties();
        return new BotInteractionService(sshManager, aiCliExecutor, new UserResourceGovernor(properties));
    }

    @Test
    void shouldCacheAiOutputAndExposeSnapshot() {
        BotInteractionService service = newService();
        AtomicBoolean completed = new AtomicBoolean(false);
        BotSshSessionManager.SshConnection connection = org.mockito.Mockito
                .mock(BotSshSessionManager.SshConnection.class);
        when(sshManager.getConnection("qq-official", "user-1")).thenReturn(connection);
        when(connection.getCwd()).thenReturn("/srv/app");

        doAnswer(invocation -> {
            java.util.function.Consumer<String> output = invocation.getArgument(5);
            Runnable onComplete = invocation.getArgument(6);
            output.accept("⏳ Codex 任务已启动...");
            output.accept("🤖 第一段输出");
            output.accept("✅ Codex 任务已完成 (exit=0)");
            onComplete.run();
            return true;
        }).when(aiCliExecutor).executeRemote(
                eq(AiCliExecutor.CliType.CODEX),
                eq("qq-official:user-1"),
                eq("分析项目"),
                eq("/srv/app"),
                any(),
                any(),
                any());

        BotInteractionService.StartAiTaskResult result = service.startAiTask(
                "qq-official",
                "user-1",
                "分析项目",
                AiCliExecutor.CliType.CODEX,
                chunk -> {
                },
                () -> completed.set(true));

        assertTrue(result.started());
        assertEquals("/srv/app", result.workDir());
        assertTrue(completed.get());

        BotInteractionService.AiTaskSnapshot snapshot = service.getAiTaskSnapshot(
                "qq-official",
                "user-1",
                AiCliExecutor.CliType.CODEX);

        assertFalse(snapshot.running());
        assertTrue(snapshot.lastOutput().contains("第一段输出"));
        assertTrue(snapshot.lastOutput().contains("任务已完成"));
    }

    @Test
    void shouldRejectAiTaskWhenSshNotConnected() {
        BotInteractionService service = newService();
        when(sshManager.getConnection("qq-official", "user-2")).thenReturn(null);

        BotInteractionService.StartAiTaskResult result = service.startAiTask(
                "qq-official",
                "user-2",
                "分析项目",
                AiCliExecutor.CliType.CODEX,
                chunk -> {
                },
                null);

        assertFalse(result.started());
        assertTrue(result.message().contains("未连接 SSH"));
        verify(aiCliExecutor, never()).executeRemote(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldRejectClaudeLoginShortcutPrompt() {
        BotInteractionService service = newService();

        BotInteractionService.StartAiTaskResult result = service.startAiTask(
                "qq-official",
                "user-3",
                "/login",
                AiCliExecutor.CliType.CLAUDE,
                chunk -> {
                },
                null);

        assertFalse(result.started());
        assertTrue(result.message().contains("claude auth login"));
        verify(sshManager, never()).getConnection(any(), any());
        verify(aiCliExecutor, never()).executeRemote(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldDisconnectAndStopAllTasksForBotType() {
        BotInteractionService service = newService();
        service.enterAiMode("qq-official", "user-a", AiCliExecutor.CliType.CODEX);
        service.enterAiMode("telegram", "user-b", AiCliExecutor.CliType.CLAUDE);

        service.disconnectAll("qq-official");

        verify(sshManager).disconnectAll("qq-official");
        verify(aiCliExecutor).stopAllForBotType("qq-official");
        assertNull(service.getAiMode("qq-official", "user-a"));
        assertEquals(AiCliExecutor.CliType.CLAUDE, service.getAiMode("telegram", "user-b"));
    }

    @Test
    void shouldReturnDisconnectedStatusWhenNoActiveConnection() {
        BotInteractionService service = newService();
        when(sshManager.getConnection("telegram", "user-1")).thenReturn(null);
        service.enterAiMode("telegram", "user-1", AiCliExecutor.CliType.CODEX);
        assertTrue(service.isInAiMode("telegram", "user-1"));

        BotInteractionService.DisconnectResult result = service.disconnect("telegram", "user-1");

        assertFalse(result.disconnected());
        assertFalse(service.isInAiMode("telegram", "user-1"));
        verify(aiCliExecutor).stopAllForUser("telegram:user-1");
        verify(aiCliExecutor).clearAllSessions("telegram:user-1");
    }

    @Test
    void shouldEnterAndExitAiMode() {
        BotInteractionService service = newService();

        service.enterAiMode("telegram", "user-9", AiCliExecutor.CliType.CODEX);
        assertEquals(AiCliExecutor.CliType.CODEX, service.getAiMode("telegram", "user-9"));
        assertTrue(service.isInAiMode("telegram", "user-9"));

        service.exitAiMode("telegram", "user-9");
        assertNull(service.getAiMode("telegram", "user-9"));
        assertFalse(service.isInAiMode("telegram", "user-9"));
    }
}
