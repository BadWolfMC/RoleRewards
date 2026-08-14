package com.badwolfmc.rolerewards.database;

import java.time.Instant;
import java.util.UUID;

public record RewardGrant(
        String rewardId,
        String period,
        UUID playerUuid,
        String playerName,
        GrantStatus status,
        String failureReason,
        Instant grantedAt,
        Instant updatedAt,
        int nextCommandIndex
) {
}
