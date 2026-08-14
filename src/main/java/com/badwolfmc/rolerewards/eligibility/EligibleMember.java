package com.badwolfmc.rolerewards.eligibility;

import java.util.UUID;

public record EligibleMember(UUID uuid, String username) {
    public String displayName() {
        return username != null && !username.isBlank() ? username : uuid.toString();
    }
}
