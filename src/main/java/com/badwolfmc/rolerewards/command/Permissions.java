package com.badwolfmc.rolerewards.command;

import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;

import java.util.List;

public final class Permissions {
    public static final String ADMIN = "rolerewards.admin";
    public static final String STATUS = "rolerewards.status";
    public static final String PREVIEW = "rolerewards.preview";
    public static final String RUN = "rolerewards.run";
    public static final String RETRY = "rolerewards.retry";
    public static final String RELOAD = "rolerewards.reload";
    public static final String HISTORY = "rolerewards.history";
    public static final String HISTORY_OTHERS = "rolerewards.history.others";

    private static final List<String> ALL = List.of(
            STATUS, PREVIEW, RUN, RETRY, RELOAD, HISTORY, HISTORY_OTHERS
    );

    private Permissions() {
    }

    public static boolean has(CommandSender sender, String permission) {
        return sender instanceof ConsoleCommandSender
                || sender instanceof RemoteConsoleCommandSender
                || sender.hasPermission(permission);
    }

    public static boolean canUseAny(CommandSender sender) {
        return ALL.stream().anyMatch(permission -> has(sender, permission));
    }
}
