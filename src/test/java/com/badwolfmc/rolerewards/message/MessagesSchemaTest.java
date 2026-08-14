package com.badwolfmc.rolerewards.message;

import com.badwolfmc.rolerewards.config.SchemaMigrationResult;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesSchemaTest {
    @Test
    void unversionedMessagesPreserveCustomizedTextAndMergeMissingKeys() throws Exception {
        YamlConfiguration bundled = yaml("""
                messages-version: 1
                prefix: "<aqua>RoleRewards</aqua>"
                greeting: "Default greeting"
                added-later: "Bundled new text"
                """);
        YamlConfiguration installed = yaml("""
                prefix: "<gold>My custom prefix</gold>"
                greeting: "My customized wording"
                custom-only: "Keep me too"
                """);

        SchemaMigrationResult result = MessagesSchema.migrateForStartup(installed, bundled);

        assertEquals(0, result.fromVersion());
        assertEquals(1, result.toVersion());
        assertTrue(result.changed());
        assertEquals(1, installed.getInt("messages-version"));
        assertEquals("<gold>My custom prefix</gold>", installed.getString("prefix"));
        assertEquals("My customized wording", installed.getString("greeting"));
        assertEquals("Bundled new text", installed.getString("added-later"));
        assertEquals("Keep me too", installed.getString("custom-only"));
    }

    @Test
    void currentSchemaStillRepairsMissingBundledMessageKeysOnce() throws Exception {
        YamlConfiguration bundled = yaml("""
                messages-version: 1
                prefix: "Default prefix"
                new-key: "New default"
                """);
        YamlConfiguration installed = yaml("""
                messages-version: 1
                prefix: "Custom prefix"
                """);

        SchemaMigrationResult first = MessagesSchema.migrateForStartup(installed, bundled);
        SchemaMigrationResult second = MessagesSchema.migrateForStartup(installed, bundled);

        assertTrue(first.changed());
        assertTrue(first.changes().stream().anyMatch(change -> change.contains("1 missing bundled message key")));
        assertEquals("Custom prefix", installed.getString("prefix"));
        assertEquals("New default", installed.getString("new-key"));
        assertFalse(second.changed());
    }

    @Test
    void bundledDefaultsProvideInMemoryFallbackWithoutReloadMigration() throws Exception {
        YamlConfiguration bundled = yaml("""
                messages-version: 1
                prefix: "Default prefix"
                new-key: "Fallback text"
                """);
        YamlConfiguration installed = yaml("""
                messages-version: 1
                prefix: "Custom prefix"
                """);

        MessagesSchema.requireCurrent(installed, bundled);
        Map<String, String> configured = MessagesSchema.stringValues(installed);
        Map<String, String> defaults = MessagesSchema.stringValues(bundled);

        assertEquals("Custom prefix", MessagesSchema.resolve(configured, defaults, "prefix"));
        assertEquals("Fallback text", MessagesSchema.resolve(configured, defaults, "new-key"));
    }

    @Test
    void configuredMessageWithWrongTypeFailsValidationInsteadOfBeingOverwritten() throws Exception {
        YamlConfiguration bundled = yaml("""
                messages-version: 1
                prefix: "Default prefix"
                greeting: "Default greeting"
                """);
        YamlConfiguration installed = yaml("""
                messages-version: 1
                prefix: "Custom prefix"
                greeting:
                  nested: "not a message string"
                """);

        assertThrows(
                IllegalArgumentException.class,
                () -> MessagesSchema.migrateForStartup(installed, bundled)
        );
        assertTrue(installed.isConfigurationSection("greeting"));
    }

    @Test
    void unsupportedNewerMessagesSchemaFailsSafely() throws Exception {
        YamlConfiguration bundled = yaml("messages-version: 1\nprefix: Default\n");
        YamlConfiguration installed = yaml("messages-version: 2\nprefix: Custom\n");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> MessagesSchema.migrateForStartup(installed, bundled)
        );
        assertTrue(error.getMessage().contains("only supports up to version 1"));
        assertEquals(2, installed.getInt("messages-version"));
    }

    private static YamlConfiguration yaml(String content) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.options().parseComments(true);
        config.loadFromString(content);
        return config;
    }
}
