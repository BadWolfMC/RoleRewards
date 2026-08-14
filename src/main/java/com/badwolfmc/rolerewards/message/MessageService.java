package com.badwolfmc.rolerewards.message;

import com.badwolfmc.rolerewards.config.SchemaMigrationResult;
import com.badwolfmc.rolerewards.config.YamlFileIO;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MessageService {
    private final JavaPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Path file;
    private volatile Map<String, String> configuredMessages = Map.of();
    private volatile Map<String, String> bundledDefaults = Map.of();
    private volatile Component prefix = Component.empty();

    public MessageService(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.file = plugin.getDataFolder().toPath().resolve("messages.yml");
    }

    public InitialLoad prepareInitial() {
        YamlConfiguration bundled = YamlFileIO.loadResource(plugin, "messages.yml");
        MessagesSchema.requireBundledCurrent(bundled);

        boolean existed = Files.exists(file);
        YamlConfiguration installed = existed ? YamlFileIO.load(file, "messages.yml") : bundled;
        SchemaMigrationResult migration = MessagesSchema.migrateForStartup(installed, bundled);
        ReloadSnapshot snapshot = createSnapshot(migration.configuration(), bundled);

        YamlFileIO.PendingWrite pendingWrite = null;
        if (!existed || migration.changed()) {
            pendingWrite = YamlFileIO.pendingWrite(
                    file,
                    migration.configuration().saveToString(),
                    existed,
                    migration.fromVersion(),
                    migration.toVersion()
            );
        }
        return new InitialLoad(snapshot, migration, pendingWrite, existed);
    }

    public void commitInitial(InitialLoad initial) {
        Objects.requireNonNull(initial, "initial");
        if (initial.pendingWrite() == null) {
            return;
        }
        try {
            var backup = initial.pendingWrite().commit();
            if (!initial.existed()) {
                plugin.getLogger().info("Created messages.yml at schema version " + MessagesSchema.CURRENT_VERSION + ".");
            } else {
                String details = initial.migration().changes().stream()
                        .filter(change -> change.startsWith("added "))
                        .findFirst()
                        .map(change -> "; " + change)
                        .orElse("");
                String versionText = initial.migration().fromVersion() == initial.migration().toVersion()
                        ? "Updated messages.yml at schema version " + initial.migration().toVersion()
                        : "Updated messages.yml schema from " + initial.migration().fromVersion() + " to "
                                + initial.migration().toVersion();
                plugin.getLogger().info(versionText + details + formatBackup(backup.orElse(null)));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not safely write upgraded messages.yml", ex);
        }
    }

    public void reload() {
        apply(loadCandidate());
    }

    public ReloadSnapshot loadCandidate() {
        YamlConfiguration bundled = YamlFileIO.loadResource(plugin, "messages.yml");
        MessagesSchema.requireBundledCurrent(bundled);
        YamlConfiguration loaded = YamlFileIO.load(file, "messages.yml");
        MessagesSchema.requireCurrent(loaded, bundled);
        return createSnapshot(loaded, bundled);
    }

    public void apply(ReloadSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        this.configuredMessages = snapshot.configuredMessages();
        this.bundledDefaults = snapshot.bundledDefaults();
        this.prefix = snapshot.prefix();
    }

    public void send(CommandSender sender, String key, TagResolver... resolvers) {
        sender.sendMessage(render(key, resolvers));
    }

    public Component render(String key, TagResolver... resolvers) {
        String raw = MessagesSchema.resolve(configuredMessages, bundledDefaults, key);
        if (raw == null) {
            raw = "<red>Missing message key: " + key + "</red>";
        }

        List<TagResolver> all = new ArrayList<>(resolvers.length + 1);
        all.add(Placeholder.component("prefix", prefix));
        all.addAll(Arrays.asList(resolvers));
        return miniMessage.deserialize(raw, all.toArray(TagResolver[]::new));
    }

    private ReloadSnapshot createSnapshot(YamlConfiguration installed, YamlConfiguration bundled) {
        Map<String, String> configured = MessagesSchema.stringValues(installed);
        Map<String, String> defaults = MessagesSchema.stringValues(bundled);
        String rawPrefix = MessagesSchema.resolve(configured, defaults, "prefix");
        if (rawPrefix == null) {
            throw new IllegalStateException("Bundled messages.yml is missing required 'prefix' message");
        }
        Component parsedPrefix;
        try {
            parsedPrefix = miniMessage.deserialize(rawPrefix);
        } catch (Exception ex) {
            throw new IllegalArgumentException("messages.yml prefix contains invalid MiniMessage", ex);
        }
        return new ReloadSnapshot(configured, defaults, parsedPrefix);
    }

    private String formatBackup(Path backup) {
        if (backup == null) {
            return ".";
        }
        return " (backup: " + backup.getFileName() + ").";
    }

    public record ReloadSnapshot(
            Map<String, String> configuredMessages,
            Map<String, String> bundledDefaults,
            Component prefix
    ) {
        public ReloadSnapshot {
            configuredMessages = Map.copyOf(configuredMessages);
            bundledDefaults = Map.copyOf(bundledDefaults);
            Objects.requireNonNull(prefix, "prefix");
        }
    }

    public record InitialLoad(
            ReloadSnapshot snapshot,
            SchemaMigrationResult migration,
            YamlFileIO.PendingWrite pendingWrite,
            boolean existed
    ) {
        public InitialLoad {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(migration, "migration");
        }
    }

    public static TagResolver text(String key, Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }
}
