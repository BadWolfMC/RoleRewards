package com.badwolfmc.rolerewards.database;

import java.time.Instant;

public record RewardRun(
        String rewardId,
        String period,
        String status,
        String trigger,
        Instant startedAt,
        Instant completedAt,
        int eligibleCount,
        int grantedCount,
        int failedCount
) {
}
