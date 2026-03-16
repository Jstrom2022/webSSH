package com.webssh.bot.qqofficial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QqOfficialBotProviderTest {

    @Test
    void shouldDowngradeMarkdownToPlainTextForQq() {
        String input = """
                - 独立前端页面：一个纯静态短视频播放器页面

                代表文件:
                [index.html](/Users/jin/Downloads/git/LDH/index.html#L941)

                **技术风格**

                - 全部采用单文件 IIFE，自带 `localStorage` 与 `GM_*` API。
                """;

        String output = QqOfficialBotProvider.normalizeForQq(input);

        assertTrue(output.contains("index.html: /Users/jin/Downloads/git/LDH/index.html#L941"));
        assertTrue(output.contains("【技术风格】"));
        assertTrue(output.contains("localStorage"));
        assertTrue(output.contains("GM_*"));
        assertFalse(output.contains("**技术风格**"));
        assertFalse(output.contains("`localStorage`"));
        assertFalse(output.contains("]("));
    }

    @Test
    void shouldKeepPlainShellOutputUntouched() {
        String input = """
                #!/bin/sh
                # keep comment
                printf '%s\\n' ok
                """;

        assertEquals(input.trim(), QqOfficialBotProvider.normalizeForQq(input));
    }

    @Test
    void shouldReturnFallbackWhenMarkdownFenceHasNoVisibleContent() {
        String input = """
                ```markdown
                ```
                """;

        assertEquals("(无输出)", QqOfficialBotProvider.truncateForQq(input));
    }
}
