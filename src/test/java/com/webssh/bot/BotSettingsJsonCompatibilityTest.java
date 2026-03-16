package com.webssh.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotSettingsJsonCompatibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldReadLegacyJsonWithoutAllowedGroupIds() throws Exception {
        String legacyJson = """
                {
                  "type": "telegram",
                  "enabled": true,
                  "sshUsername": "admin",
                  "config": {
                    "token": "abc",
                    "botUsername": "demo_bot"
                  },
                  "allowedUserIds": ["10001"]
                }
                """;

        BotSettings settings = objectMapper.readValue(legacyJson, BotSettings.class);

        assertEquals("telegram", settings.getType());
        assertNotNull(settings.getAllowedGroupIds());
        assertTrue(settings.getAllowedGroupIds().isEmpty());
        assertEquals(1, settings.getAllowedUserIds().size());
    }

    @Test
    void shouldSerializeAllowedGroupIdsForQqOfficialBot() throws Exception {
        BotSettings settings = new BotSettings();
        settings.setType("qq-official");
        settings.setAllowedGroupIds(java.util.List.of("group-1", "group-2"));

        String json = objectMapper.writeValueAsString(settings);

        assertTrue(json.contains("\"allowedGroupIds\""));
        assertTrue(json.contains("group-1"));
        assertTrue(json.contains("group-2"));
    }
}
