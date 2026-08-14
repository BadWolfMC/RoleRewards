package com.badwolfmc.rolerewards.message;

import com.badwolfmc.rolerewards.config.ConfigSchema;
import com.badwolfmc.rolerewards.config.SchemaMigrationResult;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MessagesSchema {
    public static final String VERSION_KEY = "messages-version";
    public static final int CURRENT_VERSION = 1;

    private MessagesSchema() {
    }

    public static void requireBundledCurrent(YamlConfiguration bundled) {
        int version = readVersion(bundled, "bundled messages.yml", false);
        if (version != CURRENT_VERSION) {
            throw new IllegalStateException(
                    "Bundled messages.yml schema is " + version + ", but this plugin expects " + CURRENT_VERSION
            );
        }
        validateBundledMessages(bundled);
    }

    public static void requireCurrent(YamlConfiguration installed, YamlConfiguration bundled) {
        int version = readVersion(installed, "messages.yml", true);
        if (version > CURRENT_VERSION) {
            throw ConfigSchema.newerSchema("messages.yml", version, CURRENT_VERSION);
        }
        if (version < CURRENT_VERSION) {
            throw new IllegalArgumentException(
                    "messages.yml uses schema version " + version + ", but this plugin requires version "
                            + CURRENT_VERSION + ". Restart the server/plugin to run startup migrations."
            );
        }
        validateKnownMessageTypes(installed, bundled);
    }

    public static SchemaMigrationResult migrateForStartup(
            YamlConfiguration installed,
            YamlConfiguration bundled
    ) {
        requireBundledCurrent(bundled);
        int fromVersion = readVersion(installed, "messages.yml", false);
        if (fromVersion > CURRENT_VERSION) {
            throw ConfigSchema.newerSchema("messages.yml", fromVersion, CURRENT_VERSION);
        }

        int version = fromVersion;
        List<String> changes = new ArrayList<>();
        while (version < CURRENT_VERSION) {
            switch (version) {
                case 0 -> {
                    // Version 1 establishes the schema marker. Message additions remain a
                    // non-destructive missing-key merge after sequential migrations finish.
                }
                default -> throw new IllegalStateException("No messages.yml migration path from schema version " + version);
            }
            version++;
            installed.set(VERSION_KEY, version);
            installed.setComments(VERSION_KEY, List.of(
                    "Managed schema marker. Missing bundled message keys are merged only during startup."
            ));
            changes.add("schema " + (version - 1) + " -> " + version);
        }

        int added = mergeMissingBundledMessages(installed, bundled);
        if (added > 0) {
            changes.add("added " + added + " missing bundled message key(s)");
        }
        validateKnownMessageTypes(installed, bundled);

        return new SchemaMigrationResult(installed, fromVersion, version, !changes.isEmpty(), changes);
    }

    public static Map<String, String> stringValues(YamlConfiguration config) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String path : config.getKeys(true)) {
            if (VERSION_KEY.equals(path) || config.isConfigurationSection(path)) {
                continue;
            }
            if (config.isString(path)) {
                values.put(path, config.getString(path));
            }
        }
        return Map.copyOf(values);
    }

    public static String resolve(
            Map<String, String> configured,
            Map<String, String> bundledDefaults,
            String key
    ) {
        String raw = configured.get(key);
        return raw != null ? raw : bundledDefaults.get(key);
    }

    private static int mergeMissingBundledMessages(YamlConfiguration installed, YamlConfiguration bundled) {
        int added = 0;
        for (String path : bundled.getKeys(true)) {
            if (VERSION_KEY.equals(path) || bundled.isConfigurationSection(path)) {
                continue;
            }
            if (!bundled.isString(path)) {
                throw new IllegalStateException("Bundled messages.yml key '" + path + "' must be a string");
            }
            if (installed.isSet(path)) {
                continue;
            }
            ensureParentsAreSections(installed, path);
            installed.set(path, bundled.getString(path));
            installed.setComments(path, bundled.getComments(path));
            installed.setInlineComments(path, bundled.getInlineComments(path));
            added++;
        }
        return added;
    }

    private static void ensureParentsAreSections(YamlConfiguration installed, String path) {
        int separator = path.indexOf('.');
        while (separator > 0) {
            String parent = path.substring(0, separator);
            if (installed.isSet(parent) && !installed.isConfigurationSection(parent)) {
                throw new IllegalArgumentException(
                        "messages.yml key '" + parent + "' must be a section to add bundled key '" + path + "'"
                );
            }
            separator = path.indexOf('.', separator + 1);
        }
    }

    private static void validateBundledMessages(YamlConfiguration bundled) {
        for (String path : bundled.getKeys(true)) {
            if (VERSION_KEY.equals(path) || bundled.isConfigurationSection(path)) {
                continue;
            }
            if (!bundled.isString(path)) {
                throw new IllegalStateException("Bundled messages.yml key '" + path + "' must be a string");
            }
        }
    }

    private static void validateKnownMessageTypes(YamlConfiguration installed, YamlConfiguration bundled) {
        validateBundledMessages(bundled);
        for (String path : bundled.getKeys(true)) {
            if (VERSION_KEY.equals(path) || bundled.isConfigurationSection(path) || !installed.isSet(path)) {
                continue;
            }
            if (!installed.isString(path)) {
                throw new IllegalArgumentException("messages.yml key '" + path + "' must be a string");
            }
        }
    }

    private static int readVersion(YamlConfiguration config, String displayName, boolean requireMarker) {
        Object raw = config.get(VERSION_KEY);
        if (raw == null) {
            if (requireMarker) {
                throw new IllegalArgumentException(
                        displayName + " is missing required schema marker '" + VERSION_KEY + "'"
                );
            }
            return 0;
        }
        if (!(raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long)) {
            throw new IllegalArgumentException(displayName + " '" + VERSION_KEY + "' must be an integer");
        }
        long longVersion = ((Number) raw).longValue();
        if (longVersion < 0 || longVersion > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(displayName + " '" + VERSION_KEY + "' must be a non-negative integer");
        }
        return (int) longVersion;
    }
}
