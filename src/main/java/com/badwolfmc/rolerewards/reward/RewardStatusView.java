package com.badwolfmc.rolerewards.reward;

public record RewardStatusView(
        String rewardId,
        boolean scheduleEnabled,
        String nextDue,
        String lastRun
) {
}
