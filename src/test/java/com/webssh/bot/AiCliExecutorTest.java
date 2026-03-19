package com.webssh.bot;

import com.webssh.config.ResourceGovernanceProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiCliExecutorTest {

        @Test
        void shouldInjectUtf8LocaleWhenBuildingRemoteCommand() throws Exception {
                AiCliExecutor executor = new AiCliExecutor(new ResourceGovernanceProperties());
                Method method = AiCliExecutor.class.getDeclaredMethod("buildRemoteCommand",
                                AiCliExecutor.CliType.class, String.class, String.class, String.class);
                method.setAccessible(true);

                String command = (String) method.invoke(executor,
                                AiCliExecutor.CliType.CODEX, "列出当前目录", "/tmp", "CODEX:telegram:user-1");

                assertTrue(command.startsWith("case \"${LC_ALL:-${LC_CTYPE:-$LANG}}\" in"));
                assertTrue(command.contains("export LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8 LC_CTYPE=en_US.UTF-8"));
                assertTrue(command.contains("\"$__webssh_ai_bin\""));
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldUseCommandNameInsteadOfHardcodedAbsolutePath() throws Exception {
                AiCliExecutor executor = new AiCliExecutor(new ResourceGovernanceProperties());

                Method codexMethod = AiCliExecutor.class.getDeclaredMethod("buildCodexCommand",
                                String.class, String.class, String.class);
                codexMethod.setAccessible(true);
                List<String> codexCmd = (List<String>) codexMethod.invoke(executor, "hi", "/tmp", null);
                assertEquals("codex", codexCmd.get(0));

                Method claudeMethod = AiCliExecutor.class.getDeclaredMethod("buildClaudeCommand",
                                String.class, String.class, String.class);
                claudeMethod.setAccessible(true);
                List<String> claudeCmd = (List<String>) claudeMethod.invoke(executor, "hi", "/tmp", null);
                assertEquals("claude", claudeCmd.get(0));
                assertFalse(claudeCmd.contains("--cwd"));
                assertTrue(claudeCmd.contains("--verbose"));

                List<String> resumedClaudeCmd = (List<String>) claudeMethod.invoke(executor, "hi again", "/tmp",
                                "cf466b71-27fe-4be4-8a81-0a6420f824f5");
                assertTrue(resumedClaudeCmd.contains("--resume"));
                assertFalse(resumedClaudeCmd.contains("--session-id"));
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldNormalizeClaudeKnownIssuesAndClearCachedSession() throws Exception {
                AiCliExecutor executor = new AiCliExecutor(new ResourceGovernanceProperties());
                String userKey = "telegram:user-1";
                String processKey = "CLAUDE:" + userKey;

                Field sessionField = AiCliExecutor.class.getDeclaredField("userSessionIds");
                sessionField.setAccessible(true);
                ConcurrentMap<String, String> sessions = (ConcurrentMap<String, String>) sessionField.get(executor);
                sessions.put(processKey, "cf466b71-27fe-4be4-8a81-0a6420f824f5");

                Method processLine = AiCliExecutor.class.getDeclaredMethod("processLine",
                                AiCliExecutor.CliType.class, String.class, String.class,
                                java.util.function.Consumer.class);
                processLine.setAccessible(true);

                List<String> outputs = new ArrayList<>();
                boolean handled = (boolean) processLine.invoke(executor,
                                AiCliExecutor.CliType.CLAUDE,
                                userKey,
                                "Not logged in • Please run /login",
                                (java.util.function.Consumer<String>) outputs::add);

                assertTrue(handled);
                assertFalse(outputs.isEmpty());
                assertTrue(outputs.get(0).contains("claude auth login"));
                assertFalse(sessions.containsKey(processKey));

                sessions.put(processKey, "cf466b71-27fe-4be4-8a81-0a6420f824f5");
                outputs.clear();
                handled = (boolean) processLine.invoke(executor,
                                AiCliExecutor.CliType.CLAUDE,
                                userKey,
                                "Error: Session ID cf466b71-27fe-4be4-8a81-0a6420f824f5 is already in use.",
                                (java.util.function.Consumer<String>) outputs::add);

                assertTrue(handled);
                assertFalse(outputs.isEmpty());
                assertTrue(outputs.get(0).contains("会话 ID 冲突"));
                assertFalse(sessions.containsKey(processKey));
        }

        @Test
        void shouldStripTerminalControlSequencesFromRemoteOutput() throws Exception {
                AiCliExecutor executor = new AiCliExecutor(new ResourceGovernanceProperties());
                Method sanitizeMethod = AiCliExecutor.class.getDeclaredMethod("sanitizeRemoteRawLine", String.class);
                sanitizeMethod.setAccessible(true);

                String withAnsi = "\u001B[?1004l\u001B[?2004l\u001B[?25h\u001B]9;4;0;\u0007\u001B]0;title\u0007";
                String sanitized = (String) sanitizeMethod.invoke(executor, withAnsi);
                assertTrue(sanitized.isBlank());

                String orphaned = "[?1004l [?2004l [?25h ]9;4;0; ]0; [<u";
                sanitized = (String) sanitizeMethod.invoke(executor, orphaned);
                assertTrue(sanitized.isBlank());

                String normal = "Build done [123]";
                sanitized = (String) sanitizeMethod.invoke(executor, normal);
                assertEquals("Build done [123]", sanitized);
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldParseClaudeAssistantContentArray() throws Exception {
                AiCliExecutor executor = new AiCliExecutor(new ResourceGovernanceProperties());
                String userKey = "telegram:user-2";
                String processKey = "CLAUDE:" + userKey;

                Method processLine = AiCliExecutor.class.getDeclaredMethod("processLine",
                                AiCliExecutor.CliType.class, String.class, String.class,
                                java.util.function.Consumer.class);
                processLine.setAccessible(true);

                String json = """
                                {"type":"assistant","session_id":"sid-1","message":{"role":"assistant","content":[{"type":"text","text":"你好"},{"type":"tool_use","name":"Read","input":{"path":"README.md"}}]}}
                                """
                                .trim();
                List<String> outputs = new ArrayList<>();
                boolean handled = (boolean) processLine.invoke(executor,
                                AiCliExecutor.CliType.CLAUDE,
                                userKey,
                                json,
                                (java.util.function.Consumer<String>) outputs::add);

                assertTrue(handled);
                assertTrue(outputs.stream().anyMatch(text -> text.contains("🤖 你好")));
                assertTrue(outputs.stream().anyMatch(text -> text.contains("🔧 读取文件: README.md")));

                Field sessionField = AiCliExecutor.class.getDeclaredField("userSessionIds");
                sessionField.setAccessible(true);
                ConcurrentMap<String, String> sessions = (ConcurrentMap<String, String>) sessionField.get(executor);
                assertEquals("sid-1", sessions.get(processKey));
        }

        @Test
        void shouldDeduplicateClaudeResultWhenAssistantAlreadyContainsSameText() throws Exception {
                AiCliExecutor executor = new AiCliExecutor(new ResourceGovernanceProperties());
                String userKey = "telegram:user-3";

                Method processLine = AiCliExecutor.class.getDeclaredMethod("processLine",
                                AiCliExecutor.CliType.class, String.class, String.class,
                                java.util.function.Consumer.class);
                processLine.setAccessible(true);

                String assistantJson = """
                                {"type":"assistant","session_id":"sid-2","message":{"role":"assistant","content":[{"type":"text","text":"我是 Claude, 由 Anthropic 开发的 AI 助手。"}]}}
                                """
                                .trim();
                String resultJson = """
                                {"type":"result","session_id":"sid-2","result":"我是 Claude, 由 Anthropic 开发的 AI 助手。","usage":{"input_tokens":3,"output_tokens":76}}
                                """
                                .trim();

                List<String> outputs = new ArrayList<>();
                processLine.invoke(executor,
                                AiCliExecutor.CliType.CLAUDE,
                                userKey,
                                assistantJson,
                                (java.util.function.Consumer<String>) outputs::add);
                processLine.invoke(executor,
                                AiCliExecutor.CliType.CLAUDE,
                                userKey,
                                resultJson,
                                (java.util.function.Consumer<String>) outputs::add);

                long duplicatedTextCount = outputs.stream()
                                .filter(text -> text.contains("我是 Claude, 由 Anthropic 开发的 AI 助手。"))
                                .count();
                assertEquals(1L, duplicatedTextCount);
                assertTrue(outputs.stream().anyMatch(text -> text.contains("📊 Token: 输入=3, 输出=76")));
        }
}
