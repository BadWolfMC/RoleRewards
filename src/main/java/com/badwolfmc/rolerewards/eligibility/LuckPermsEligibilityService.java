package com.badwolfmc.rolerewards.eligibility;

import com.badwolfmc.rolerewards.config.RewardDefinition;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.matcher.NodeMatcher;
import net.luckperms.api.node.types.InheritanceNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class LuckPermsEligibilityService {
    private final LuckPerms luckPerms;

    public LuckPermsEligibilityService(LuckPerms luckPerms) {
        this.luckPerms = luckPerms;
    }

    public CompletableFuture<List<EligibleMember>> findEligible(RewardDefinition reward) {
        if (!reward.directOnly()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("RoleRewards v1 supports direct LuckPerms membership only")
            );
        }

        InheritanceNode groupNode = InheritanceNode.builder(reward.group()).build();
        NodeMatcher<InheritanceNode> matcher = NodeMatcher.key(groupNode);

        return luckPerms.getGroupManager().loadGroup(reward.group())
                .thenCompose(group -> {
                    if (group.isEmpty()) {
                        return CompletableFuture.failedFuture(
                                new IllegalArgumentException("LuckPerms group '" + reward.group() + "' does not exist")
                        );
                    }

                    return luckPerms.getUserManager().searchAll(matcher)
                            .thenCompose(matches -> resolveMembers(matches.entrySet().stream()
                                    .filter(entry -> entry.getValue().stream()
                                            .anyMatch(LuckPermsEligibilityService::isActivePositiveMembership))
                                    .map(java.util.Map.Entry::getKey)
                                    .toList()));
                });
    }

    private static boolean isActivePositiveMembership(InheritanceNode node) {
        return node.getValue() && !node.hasExpired();
    }

    public CompletableFuture<String> resolveUsername(UUID uuid) {
        User loaded = luckPerms.getUserManager().getUser(uuid);
        if (loaded != null && loaded.getUsername() != null && !loaded.getUsername().isBlank()) {
            return CompletableFuture.completedFuture(loaded.getUsername());
        }
        return luckPerms.getUserManager().lookupUsername(uuid);
    }

    private CompletableFuture<List<EligibleMember>> resolveMembers(Iterable<UUID> uuids) {
        List<CompletableFuture<EligibleMember>> futures = new ArrayList<>();
        for (UUID uuid : uuids) {
            futures.add(resolveUsername(uuid)
                    .exceptionally(ignored -> null)
                    .thenApply(name -> new EligibleMember(uuid, name)));
        }

        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        return all.thenApply(ignored -> futures.stream()
                .map(CompletableFuture::join)
                .sorted(Comparator.comparing(EligibleMember::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList());
    }
}
