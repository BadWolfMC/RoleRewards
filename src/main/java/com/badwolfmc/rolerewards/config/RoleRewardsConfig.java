package com.badwolfmc.rolerewards.config;

import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public record RoleRewardsConfig(
        ZoneId zoneId,
        int schedulerCheckMinutes,
        Map<String, RewardDefinition> rewards
) {
    public RoleRewardsConfig {
        rewards = Collections.unmodifiableMap(new LinkedHashMap<>(rewards));
    }

    public Optional<RewardDefinition> reward(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(rewards.get(id.toLowerCase(java.util.Locale.ROOT)));
    }
}
