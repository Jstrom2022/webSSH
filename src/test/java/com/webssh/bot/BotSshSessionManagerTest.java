package com.webssh.bot;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.Session;
import com.webssh.config.SshCompatibilityProperties;
import com.webssh.session.SessionProfileStore;
import com.webssh.session.SshSessionProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotSshSessionManagerTest {

    @Mock
    private SessionProfileStore profileStore;

    @Mock
    private SshCompatibilityProperties sshProperties;

    @Mock
    private AiCliExecutor aiCliExecutor;

    @Test
    void shouldReconnectAndRetryShellCommandWhenExistingShellHasExpired() throws Exception {
        TestableBotSshSessionManager manager = new TestableBotSshSessionManager(profileStore, sshProperties,
                aiCliExecutor);

        SshSessionProfile profile = createProfile();
        when(profileStore.list("admin")).thenReturn(List.of(profile));
        when(profileStore.get("admin", "profile-1")).thenReturn(profile);

        StubConnection staleConnection = StubConnection.withSendFailure("prod-vps", "broken pipe");
        StubConnection recoveredConnection = StubConnection.withOutput("prod-vps", "uid=0(root)\n");
        manager.enqueueOpenedConnection(staleConnection);
        manager.enqueueOpenedConnection(recoveredConnection);

        manager.connect("telegram", "user-1", "admin", "1");
        clearInvocations(aiCliExecutor);

        String output = manager.executeCommandAsync("telegram", "user-1", "id")
                .get(5, TimeUnit.SECONDS);

        assertEquals("uid=0(root)\n", output);
        assertEquals("id\n", recoveredConnection.writtenCommand());
        assertTrue(staleConnection.wasClosed());
        verify(aiCliExecutor).stopAllForUser("telegram:user-1");
        verify(aiCliExecutor).clearAllSessions("telegram:user-1");
    }

    @Test
    void shouldClearAiContextWhenGetConnectionDetectsExpiredSession() throws Exception {
        TestableBotSshSessionManager manager = new TestableBotSshSessionManager(profileStore, sshProperties,
                aiCliExecutor);

        SshSessionProfile profile = createProfile();
        when(profileStore.list("admin")).thenReturn(List.of(profile));
        when(profileStore.get("admin", "profile-1")).thenReturn(profile);

        StubConnection staleConnection = StubConnection.disconnected("prod-vps");
        manager.enqueueOpenedConnection(staleConnection);

        manager.connect("telegram", "user-2", "admin", "1");
        clearInvocations(aiCliExecutor);

        BotSshSessionManager.SshConnection connection = manager.getConnection("telegram", "user-2");

        assertNull(connection);
        assertTrue(staleConnection.wasClosed());
        verify(aiCliExecutor).stopAllForUser("telegram:user-2");
        verify(aiCliExecutor).clearAllSessions("telegram:user-2");
    }

    @Test
    void shouldInjectUtf8LocaleWhenBuildingExecCommand() throws Exception {
        Method method = BotSshSessionManager.class.getDeclaredMethod("buildExecCommand", String.class, String.class);
        method.setAccessible(true);

        String script = (String) method.invoke(null, "ls -la", "/tmp");

        assertTrue(script.startsWith("case \"${LC_ALL:-${LC_CTYPE:-$LANG}}\" in"));
        assertTrue(script.contains("export LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8 LC_CTYPE=en_US.UTF-8"));
        assertTrue(script.contains("cd -- '/tmp'"));
        assertTrue(script.contains("__webssh_cmd='ls -la'"));
    }

    private SshSessionProfile createProfile() {
        SshSessionProfile profile = new SshSessionProfile();
        profile.setId("profile-1");
        profile.setName("prod-vps");
        profile.setUsername("root");
        profile.setHost("127.0.0.1");
        profile.setPort(22);
        profile.setAuthType("PASSWORD");
        profile.setPassword("secret");
        return profile;
    }

    private static final class TestableBotSshSessionManager extends BotSshSessionManager {
        private final Deque<SshConnection> openedConnections = new ArrayDeque<>();

        private TestableBotSshSessionManager(SessionProfileStore profileStore,
                SshCompatibilityProperties sshProperties,
                AiCliExecutor aiCliExecutor) {
            super(profileStore, sshProperties, aiCliExecutor);
        }

        private void enqueueOpenedConnection(SshConnection connection) {
            openedConnections.addLast(connection);
        }

        @Override
        SshConnection openSshConnection(ReconnectContext reconnectContext) {
            return openedConnections.removeFirst();
        }
    }

    private static final class StubConnection extends BotSshSessionManager.SshConnection {
        private final AtomicBoolean connected = new AtomicBoolean(true);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final IOException sendFailure;
        private final String output;
        private final ByteArrayOutputStream commandBuffer = new ByteArrayOutputStream();

        private StubConnection(String profileName, IOException sendFailure, String output) {
            super(mock(Session.class),
                    mock(ChannelShell.class),
                    new ByteArrayOutputStream(),
                    new ByteArrayInputStream(new byte[0]),
                    profileName);
            this.sendFailure = sendFailure;
            this.output = output;
        }

        private static StubConnection withSendFailure(String profileName, String message) {
            return new StubConnection(profileName, new IOException(message), "");
        }

        private static StubConnection withOutput(String profileName, String output) {
            return new StubConnection(profileName, null, output);
        }

        private static StubConnection disconnected(String profileName) {
            StubConnection connection = new StubConnection(profileName, null, "");
            connection.connected.set(false);
            return connection;
        }

        @Override
        public boolean isConnected() {
            return connected.get();
        }

        @Override
        public synchronized void sendCommand(String command) throws IOException {
            if (!connected.get()) {
                throw new IOException("SSH 连接已断开");
            }
            if (sendFailure != null) {
                connected.set(false);
                throw sendFailure;
            }
            commandBuffer.write((command + "\n").getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public synchronized String readAvailableOutput(long initialDelayMs, long idleTimeoutMs) {
            return output;
        }

        @Override
        public void close() {
            connected.set(false);
            closed.set(true);
        }

        private String writtenCommand() {
            return commandBuffer.toString(StandardCharsets.UTF_8);
        }

        private boolean wasClosed() {
            return closed.get();
        }
    }
}
