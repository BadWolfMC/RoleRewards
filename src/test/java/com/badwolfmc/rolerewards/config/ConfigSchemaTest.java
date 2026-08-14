package com.badwolfmc.rolerewards.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigSchemaTest {
    @Test
    void unversionedConfigPreservesCustomValuesAndAddsOnlyFrozenV1Defaults() throws Exception {
        YamlConfiguration config = yaml("""
                timezone: "Europe/London"
                scheduler-check-minutes: 17
                rewards:
                  companion:
                    group: "companion"
                    membership:
                      direct-only: true
                    schedule:
                      enabled: true
                      day-of-month: 15
                      time: "19:30"
                    commands:
                      - "points give {player} 75"
                    operator-data:
                      retries: 3
                      labels:
                        - alpha
                        - beta
                  custom-reward:
                    group: "custom"
                    commands:
                      - "custom give {player}"
                """);

        SchemaMigrationResult result = ConfigSchema.migrateForStartup(config);

        assertEquals(0, result.fromVersion());
        assertEquals(1, result.toVersion());
        assertTrue(result.changed());
        assertEquals(1, config.getInt("config-version"));

        assertEquals("Europe/London", config.getString("timezone"));
        assertEquals(17, config.getInt("scheduler-check-minutes"));
        assertEquals("19:30", config.getString("rewards.companion.schedule.time"));
        assertEquals(15, config.getInt("rewards.companion.schedule.day-of-month"));
        assertTrue(config.getBoolean("rewards.companion.schedule.enabled"));
        assertEquals(List.of("points give {player} 75"), config.getStringList("rewards.companion.commands"));

        assertEquals(3, config.getInt("rewards.companion.operator-data.retries"));
        assertInstanceOf(Integer.class, config.get("rewards.companion.operator-data.retries"));
        assertEquals(List.of("alpha", "beta"), config.getStringList("rewards.companion.operator-data.labels"));

        assertTrue(config.getBoolean("rewards.custom-reward.membership.direct-only"));
        assertFalse(config.getBoolean("rewards.custom-reward.schedule.enabled"));
        assertEquals(1, config.getInt("rewards.custom-reward.schedule.day-of-month"));
        assertEquals("22:00", config.getString("rewards.custom-reward.schedule.time"));
        assertEquals("custom", config.getString("rewards.custom-reward.group"));
        assertEquals(List.of("custom give {player}"), config.getStringList("rewards.custom-reward.commands"));
    }

    @Test
    void migrationRefusesToReplaceScalarParentWithManagedSubsection() throws Exception {
        YamlConfiguration config = yaml("""
                rewards:
                  custom:
                    group: custom
                    membership: "operator-owned-scalar"
                    commands:
                      - "custom give {player}"
                """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ConfigSchema.migrateForStartup(config)
        );

        assertTrue(error.getMessage().contains("membership"));
        assertEquals("operator-owned-scalar", config.getString("rewards.custom.membership"));
        assertFalse(config.isSet("config-version"));
    }

    @Test
    void currentConfigIsNotRewrittenBySchemaMigration() throws Exception {
        YamlConfiguration config = yaml("""
                config-version: 1
                timezone: "America/New_York"
                scheduler-check-minutes: 5
                rewards:
                  companion:
                    group: companion
                    membership:
                      direct-only: true
                    schedule:
                      enabled: false
                      day-of-month: 1
                      time: "22:00"
                    commands:
                      - "points give {player} 50"
                """);

        SchemaMigrationResult result = ConfigSchema.migrateForStartup(config);

        assertEquals(1, result.fromVersion());
        assertEquals(1, result.toVersion());
        assertFalse(result.changed());
        assertTrue(result.changes().isEmpty());
    }

    @Test
    void unsupportedNewerConfigSchemaFailsSafely() throws Exception {
        YamlConfiguration config = yaml("config-version: 2\nrewards: {}\n");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ConfigSchema.migrateForStartup(config)
        );
        assertTrue(error.getMessage().contains("only supports up to version 1"));
        assertEquals(2, config.getInt("config-version"));
    }

    @Test
    void reloadRequiresCurrentSchemaMarker() throws Exception {
        YamlConfiguration unversioned = yaml("timezone: America/New_York\n");
        YamlConfiguration current = yaml("config-version: 1\n");

        assertThrows(IllegalArgumentException.class, () -> ConfigSchema.requireCurrent(unversioned));
        ConfigSchema.requireCurrent(current);
    }

    private static YamlConfiguration yaml(String content) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.options().parseComments(true);
        config.loadFromString(content);
        return config;
    }
}
