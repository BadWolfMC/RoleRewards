package com.badwolfmc.rolerewards.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigManagerTest {
    @Test
    void parsesValidConfigAndKeepsRuntimeDefaultsForMissingOptionalFields() throws Exception {
        YamlConfiguration config = yaml("""
                config-version: 1
                rewards:
                  custom:
                    group: custom
                    commands:
                      - "custom give {player}"
                """);

        RoleRewardsConfig parsed = ConfigManager.parse(config);
        RewardDefinition reward = parsed.reward("custom").orElseThrow();

        assertEquals(ZoneId.of("America/New_York"), parsed.zoneId());
        assertEquals(5, parsed.schedulerCheckMinutes());
        assertTrue(reward.directOnly());
        assertEquals(1, reward.dayOfMonth());
        assertEquals(LocalTime.of(22, 0), reward.time());
        assertEquals(List.of("custom give {player}"), reward.commands());
    }

    @Test
    void rejectsStringWhereSchedulerIntervalMustBeInteger() throws Exception {
        YamlConfiguration config = baseConfig();
        config.set("scheduler-check-minutes", "5");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ConfigManager.parse(config)
        );
        assertTrue(error.getMessage().contains("scheduler-check-minutes must be an integer"));
    }

    @Test
    void rejectsStringWhereScheduleEnabledMustBeBoolean() throws Exception {
        YamlConfiguration config = baseConfig();
        config.set("rewards.companion.schedule.enabled", "false");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ConfigManager.parse(config)
        );
        assertTrue(error.getMessage().contains("schedule.enabled must be true or false"));
    }

    @Test
    void rejectsNonStringCommandListEntryInsteadOfSilentlyDroppingIt() throws Exception {
        YamlConfiguration config = baseConfig();
        config.set("rewards.companion.commands", List.of("points give {player} 50", 123));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ConfigManager.parse(config)
        );
        assertTrue(error.getMessage().contains("commands entry 2 must be a string"));
    }

    @Test
    void rejectsWrongTypeForGroupInsteadOfCoercingIt() throws Exception {
        YamlConfiguration config = baseConfig();
        config.set("rewards.companion.group", 123);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ConfigManager.parse(config)
        );
        assertTrue(error.getMessage().contains("group must be a string"));
    }

    private static YamlConfiguration baseConfig() throws Exception {
        return yaml("""
                config-version: 1
                timezone: "America/New_York"
                scheduler-check-minutes: 5
                rewards:
                  companion:
                    group: "companion"
                    membership:
                      direct-only: true
                    schedule:
                      enabled: false
                      day-of-month: 1
                      time: "22:00"
                    commands:
                      - "points give {player} 50"
                """);
    }

    private static YamlConfiguration yaml(String content) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(content);
        return config;
    }
}
