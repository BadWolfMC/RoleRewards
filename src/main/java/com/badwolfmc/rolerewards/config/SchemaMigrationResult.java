package com.badwolfmc.rolerewards.config;

import org.bukkit.configuration.file.YamlConfiguration;

import java.util.List;
import java.util.Objects;

public record SchemaMigrationResult(
        YamlConfiguration configuration,
        int fromVersion,
        int toVersion,
        boolean changed,
        List<String> changes
) {
    public SchemaMigrationResult {
        Objects.requireNonNull(configuration, "configuration");
        changes = List.copyOf(changes);
    }
}
