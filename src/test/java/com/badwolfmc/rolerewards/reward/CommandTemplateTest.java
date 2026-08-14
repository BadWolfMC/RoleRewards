package com.badwolfmc.rolerewards.reward;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandTemplateTest {
    @Test
    void rendersAllPlaceholdersAndStripsLeadingSlash() {
        UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        String result = CommandTemplate.render(
                " /points give {player} 50 # {uuid} {reward} {period}",
                "TestPlayer",
                uuid,
                "companion",
                "2026-08"
        );
        assertEquals(
                "points give TestPlayer 50 # 123e4567-e89b-12d3-a456-426614174000 companion 2026-08",
                result
        );
    }
}
