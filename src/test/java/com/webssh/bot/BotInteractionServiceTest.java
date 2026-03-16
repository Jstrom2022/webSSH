package com.webssh.bot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotInteractionServiceTest {

    @Mock
    private BotSshSessionManager sshManager;

    @Mock
    private AiCliExecutor aiCliExecutor;

    @Test
    void shouldCacheAiOutputAndExposeSnapshot() {
        BotInteractionService service = new BotInteractionService(sshManager, aiCliExecutor);
        AtomicBoolean completed = new AtomicBoolean(false);

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> output = invocation.getArgument(4);
            Runnable onComplete = invocation.getArgument(5);
            output.accept("⏳ Codex 任务已启动...");
            output.accept("🤖 第一段输出");
            output.accept("✅ Codex 任务已完成 (exit=0)");
            onComplete.run();
            return null;
        }).when(aiCliExecutor).execute(
                eq(AiCliExecutor.CliType.CODEX),
                eq("qq-official:user-1"),
                eq("分析项目"),
                eq("/tmp"),
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
        assertEquals("/tmp", result.workDir());
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
    void shouldDisconnectAndStopAllTasksForBotType() {
        BotInteractionService service = new BotInteractionService(sshManager, aiCliExecutor);

        service.disconnectAll("qq-official");

        verify(sshManager).disconnectAll("qq-official");
        verify(aiCliExecutor).stopAllForBotType("qq-official");
    }

    @Test
    void shouldReturnDisconnectedStatusWhenNoActiveConnection() {
        BotInteractionService service = new BotInteractionService(sshManager, aiCliExecutor);
        when(sshManager.getConnection("telegram", "user-1")).thenReturn(null);

        BotInteractionService.DisconnectResult result = service.disconnect("telegram", "user-1");

        assertFalse(result.disconnected());
    }
}
