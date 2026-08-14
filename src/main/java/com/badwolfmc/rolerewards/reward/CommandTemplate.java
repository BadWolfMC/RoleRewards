package com.badwolfmc.rolerewards.reward;

import java.util.UUID;

public final class CommandTemplate {
    private CommandTemplate() {
    }

    public static String render(String template, String player, UUID uuid, String reward, String period) {
        String rendered = template
                .replace("{player}", player)
                .replace("{uuid}", uuid.toString())
                .replace("{reward}", reward)
                .replace("{period}", period)
                .trim();
        while (rendered.startsWith("/")) {
            rendered = rendered.substring(1).trim();
        }
        return rendered;
    }
}
