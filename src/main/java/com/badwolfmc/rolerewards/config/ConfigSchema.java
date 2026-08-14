package com.badwolfmc.rolerewards.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;

public final class ConfigSchema {
    public static final String VERSION_KEY = "config-version";
    public static final int CURRENT_VERSION = 1;

    private ConfigSchema() {
    }

    public static void requireBundledCurrent(YamlConfiguration bundled) {
        int version = readVersion(bundled, VERSION_KEY, "bundled config.yml", false);
        if (version != CURRENT_VERSION) {
            throw new IllegalStateException(
                    "Bundled config.yml schema is " + version + ", but this plugin expects " + CURRENT_VERSION
            );
        }
    }

    public static void requireCurrent(YamlConfiguration installed) {
        int version = readVersion(installed, VERSION_KEY, "config.yml", true);
        if (version > CURRENT_VERSION) {
            throw newerSchema("config.yml", version, CURRENT_VERSION);
        }
        if (version < CURRENT_VERSION) {
            throw new IllegalArgumentException(
                    "config.yml uses schema version " + version + ", but this plugin requires version "
                            + CURRENT_VERSION + ". Restart the server/plugin to run startup migrations."
            );
        }
    }

    public static SchemaMigrationResult migrateForStartup(YamlConfiguration installed) {
        int fromVersion = readVersion(installed, VERSION_KEY, "config.yml", false);
        if (fromVersion > CURRENT_VERSION) {
            throw newerSchema("config.yml", fromVersion, CURRENT_VERSION);
        }

        int version = fromVersion;
        List<String> changes = new ArrayList<>();
        while (version < CURRENT_VERSION) {
            switch (version) {
                case 0 -> migrateZeroToOne(installed, changes);
                default -> throw new IllegalStateException("No config.yml migration path from schema version " + version);
            }
            version++;
            installed.set(VERSION_KEY, version);
            installed.setComments(VERSION_KEY, List.of(
                    "Managed schema marker. RoleRewards upgrades older schemas only during startup."
            ));
            changes.add("schema " + (version - 1) + " -> " + version);
        }

        return new SchemaMigrationResult(installed, fromVersion, version, !changes.isEmpty(), changes);
    }

    private static void migrateZeroToOne(YamlConfiguration config, List<String> changes) {
        setIfMissing(config, "timezone", "America/New_York", changes);
        setIfMissing(config, "scheduler-check-minutes", 5, changes);

        ConfigurationSection rewards = config.getConfigurationSection("rewards");
        if (rewards == null) {
            return;
        }
        for (String rewardId : rewards.getKeys(false)) {
            ConfigurationSection reward = rewards.getConfigurationSection(rewardId);
            if (reward == null) {
                continue;
            }
            setIfMissing(config, "rewards." + rewardId + ".membership.direct-only", true, changes);
            setIfMissing(config, "rewards." + rewardId + ".schedule.enabled", false, changes);
            setIfMissing(config, "rewards." + rewardId + ".schedule.day-of-month", 1, changes);
            setIfMissing(config, "rewards." + rewardId + ".schedule.time", "22:00", changes);
        }
    }

    private static void setIfMissing(YamlConfiguration config, String path, Object value, List<String> changes) {
        if (config.isSet(path)) {
            return;
        }
        ensureParentsAreSections(config, path);
        config.set(path, value);
        changes.add("added " + path);
    }

    private static void ensureParentsAreSections(YamlConfiguration config, String path) {
        int separator = path.indexOf('.');
        while (separator > 0) {
            String parent = path.substring(0, separator);
            if (config.isSet(parent) && !config.isConfigurationSection(parent)) {
                throw new IllegalArgumentException(
                        "config.yml key '" + parent + "' must be a section to add migrated key '" + path + "'"
                );
            }
            separator = path.indexOf('.', separator + 1);
        }
    }

    static int readVersion(
            YamlConfiguration config,
            String versionKey,
            String displayName,
            boolean requireMarker
    ) {
        Object raw = config.get(versionKey);
        if (raw == null) {
            if (requireMarker) {
                throw new IllegalArgumentException(
                        displayName + " is missing required schema marker '" + versionKey + "'"
                );
            }
            return 0;
        }
        if (!(raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long)) {
            throw new IllegalArgumentException(displayName + " '" + versionKey + "' must be an integer");
        }
        long longVersion = ((Number) raw).longValue();
        if (longVersion < 0 || longVersion > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(displayName + " '" + versionKey + "' must be a non-negative integer");
        }
        return (int) longVersion;
    }

    public static IllegalArgumentException newerSchema(String displayName, int installed, int supported) {
        return new IllegalArgumentException(
                displayName + " uses schema version " + installed + ", but this plugin only supports up to version "
                        + supported + ". Refusing to downgrade or interpret a newer configuration."
        );
    }
}
