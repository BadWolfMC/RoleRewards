package com.badwolfmc.rolerewards.reward;

import com.badwolfmc.rolerewards.database.RewardGrant;
import com.badwolfmc.rolerewards.eligibility.EligibleMember;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RewardPreview(
        String rewardId,
        String period,
        boolean runRecorded,
        List<EligibleMember> eligible,
        Map<UUID, RewardGrant> grants
) {
}
