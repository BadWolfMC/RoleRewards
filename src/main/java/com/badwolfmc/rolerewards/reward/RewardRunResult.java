package com.badwolfmc.rolerewards.reward;

public record RewardRunResult(
        String rewardId,
        String period,
        boolean alreadyRecorded,
        int granted,
        int failed,
        int skipped
) {
    public static RewardRunResult alreadyRecorded(String rewardId, String period) {
        return new RewardRunResult(rewardId, period, true, 0, 0, 0);
    }
}
