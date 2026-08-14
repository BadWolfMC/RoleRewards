package com.badwolfmc.rolerewards.config;

import java.time.LocalTime;
import java.util.List;

public record RewardDefinition(
        String id,
        String group,
        boolean directOnly,
        boolean scheduleEnabled,
        int dayOfMonth,
        LocalTime time,
        List<String> commands
) {
}
