package com.webssh.bot;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AiCliExecutorTest {

    @Test
    void shouldInjectUtf8LocaleWhenBuildingRemoteCommand() throws Exception {
        AiCliExecutor executor = new AiCliExecutor();
        Method method = AiCliExecutor.class.getDeclaredMethod("buildRemoteCommand",
                AiCliExecutor.CliType.class, String.class, String.class, String.class);
        method.setAccessible(true);

        String command = (String) method.invoke(executor,
                AiCliExecutor.CliType.CODEX, "列出当前目录", "/tmp", "CODEX:telegram:user-1");

        assertTrue(command.startsWith("case \"${LC_ALL:-${LC_CTYPE:-$LANG}}\" in"));
        assertTrue(command.contains("export LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8 LC_CTYPE=en_US.UTF-8"));
        assertTrue(command.contains("\"$__webssh_ai_bin\""));
    }
}
