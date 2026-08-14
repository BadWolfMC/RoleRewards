package com.badwolfmc.rolerewards.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class MessageService {
    private final JavaPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private File file;
    private volatile YamlConfiguration config;
    private volatile Component prefix = Component.empty();

    public MessageService(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void loadInitial() {
        this.file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        reload();
    }

    public void reload() {
        apply(loadCandidate());
    }

    public ReloadSnapshot loadCandidate() {
        YamlConfiguration loaded = new YamlConfiguration();
        try {
            loaded.load(file);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not load messages.yml", ex);
        }
        String rawPrefix = loaded.getString("prefix", "<aqua><bold>RoleRewards</bold></aqua> <dark_gray>»</dark_gray>");
        Component parsedPrefix = miniMessage.deserialize(rawPrefix);
        return new ReloadSnapshot(loaded, parsedPrefix);
    }

    public void apply(ReloadSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        this.config = snapshot.config();
        this.prefix = snapshot.prefix();
    }

    public void send(CommandSender sender, String key, TagResolver... resolvers) {
        sender.sendMessage(render(key, resolvers));
    }

    public Component render(String key, TagResolver... resolvers) {
        YamlConfiguration snapshot = config;
        String raw = snapshot == null ? null : snapshot.getString(key);
        if (raw == null) {
            raw = "<red>Missing message key: " + key + "</red>";
        }

        List<TagResolver> all = new ArrayList<>(resolvers.length + 1);
        all.add(Placeholder.component("prefix", prefix));
        all.addAll(Arrays.asList(resolvers));
        return miniMessage.deserialize(raw, all.toArray(TagResolver[]::new));
    }

    public record ReloadSnapshot(YamlConfiguration config, Component prefix) {
        public ReloadSnapshot {
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(prefix, "prefix");
        }
    }

    public static TagResolver text(String key, Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }
}
