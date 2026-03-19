package com.webssh.task;

import com.webssh.config.ResourceGovernanceProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserResourceGovernorTest {

    @Test
    void shouldEnforcePerUserConcurrencyLimit() {
        ResourceGovernanceProperties properties = new ResourceGovernanceProperties();
        properties.setAiTaskPerUser(1);
        UserResourceGovernor governor = new UserResourceGovernor(properties);

        UserResourceGovernor.Permit first = governor.tryAcquireAiTask("telegram:u1");
        UserResourceGovernor.Permit second = governor.tryAcquireAiTask("telegram:u1");

        assertTrue(first.granted());
        assertFalse(second.granted());

        first.close();

        UserResourceGovernor.Permit third = governor.tryAcquireAiTask("telegram:u1");
        assertTrue(third.granted());
        third.close();
    }

    @Test
    void shouldApplySlidingWindowRateLimit() throws Exception {
        ResourceGovernanceProperties properties = new ResourceGovernanceProperties();
        properties.setBotMessageRateLimit(2);
        properties.setBotMessageRateWindow(Duration.ofMillis(80));
        UserResourceGovernor governor = new UserResourceGovernor(properties);

        assertTrue(governor.allowBotMessage("qq:user-1"));
        assertTrue(governor.allowBotMessage("qq:user-1"));
        assertFalse(governor.allowBotMessage("qq:user-1"));

        Thread.sleep(100L);

        assertTrue(governor.allowBotMessage("qq:user-1"));
    }
}
