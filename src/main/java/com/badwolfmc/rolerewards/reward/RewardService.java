package com.badwolfmc.rolerewards.reward;

import com.badwolfmc.rolerewards.config.ConfigManager;
import com.badwolfmc.rolerewards.config.RewardDefinition;
import com.badwolfmc.rolerewards.database.RewardGrant;
import com.badwolfmc.rolerewards.database.RewardRun;
import com.badwolfmc.rolerewards.database.SqliteStore;
import com.badwolfmc.rolerewards.eligibility.EligibleMember;
import com.badwolfmc.rolerewards.eligibility.LuckPermsEligibilityService;
import com.badwolfmc.rolerewards.schedule.ScheduleCalculator;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

public final class RewardService {
    private static final DateTimeFormatter STATUS_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final SqliteStore store;
    private final LuckPermsEligibilityService eligibilityService;
    private final ConcurrentMap<String, Boolean> activeRuns = new ConcurrentHashMap<>();

    public RewardService(
            JavaPlugin plugin,
            ConfigManager configManager,
            SqliteStore store,
            LuckPermsEligibilityService eligibilityService
    ) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.store = store;
        this.eligibilityService = eligibilityService;
    }

    public Optional<RewardDefinition> reward(String rewardId) {
        return configManager.current().reward(rewardId);
    }

    public CompletableFuture<RewardPreview> preview(String rewardId) {
        RewardDefinition reward = requireReward(rewardId);
        ZonedDateTime now = ZonedDateTime.now(configManager.current().zoneId());
        String period = ScheduleCalculator.period(now);

        CompletableFuture<List<EligibleMember>> eligibleFuture = eligibilityService.findEligible(reward);
        CompletableFuture<Optional<RewardRun>> runFuture = store.getRun(reward.id(), period);
        CompletableFuture<List<RewardGrant>> grantsFuture = store.getGrants(reward.id(), period);

        return CompletableFuture.allOf(eligibleFuture, runFuture, grantsFuture)
                .thenApply(ignored -> {
                    Map<UUID, RewardGrant> grants = new LinkedHashMap<>();
                    for (RewardGrant grant : grantsFuture.join()) {
                        grants.put(grant.playerUuid(), grant);
                    }
                    return new RewardPreview(
                            reward.id(),
                            period,
                            runFuture.join().isPresent(),
                            eligibleFuture.join(),
                            Map.copyOf(grants)
                    );
                });
    }

    public CompletableFuture<RewardRunResult> runCurrentPeriod(String rewardId, String trigger) {
        YearMonth period = YearMonth.from(ZonedDateTime.now(configManager.current().zoneId()));
        return runPeriod(rewardId, period, trigger);
    }

    public CompletableFuture<RewardRunResult> runPeriod(String rewardId, YearMonth targetPeriod, String trigger) {
        RewardDefinition reward = requireReward(rewardId);
        String period = targetPeriod.toString();
        String runKey = reward.id() + ":" + period;

        if (activeRuns.putIfAbsent(runKey, Boolean.TRUE) != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("A reward operation is already in progress for " + reward.id() + " / " + period)
            );
        }

        return store.getRun(reward.id(), period)
                .thenCompose(existing -> {
                    if (existing.isPresent()) {
                        return CompletableFuture.completedFuture(RewardRunResult.alreadyRecorded(reward.id(), period));
                    }
                    return eligibilityService.findEligible(reward)
                            .thenCompose(members -> store.createRunSnapshot(reward.id(), period, trigger, members)
                                    .thenCompose(created -> {
                                        if (!created) {
                                            return CompletableFuture.completedFuture(RewardRunResult.alreadyRecorded(reward.id(), period));
                                        }
                                        return executeSnapshot(reward, period, members);
                                    }));
                })
                .whenComplete((result, throwable) -> activeRuns.remove(runKey));
    }

    public CompletableFuture<RewardRunResult> retryCurrentPeriod(String rewardId) {
        YearMonth period = YearMonth.from(ZonedDateTime.now(configManager.current().zoneId()));
        return retryPeriod(rewardId, period);
    }

    public CompletableFuture<RewardRunResult> retryPeriod(String rewardId, YearMonth targetPeriod) {
        RewardDefinition reward = requireReward(rewardId);
        String period = targetPeriod.toString();
        String runKey = reward.id() + ":" + period;

        if (activeRuns.putIfAbsent(runKey, Boolean.TRUE) != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("A reward operation is already in progress for " + reward.id() + " / " + period)
            );
        }

        return store.getFailedGrants(reward.id(), period)
                .thenCompose(failed -> {
                    if (failed.isEmpty()) {
                        return CompletableFuture.completedFuture(new RewardRunResult(reward.id(), period, false, 0, 0, 0));
                    }
                    List<CompletableFuture<Boolean>> attempts = failed.stream()
                            .map(grant -> retryGrant(reward, period, grant))
                            .toList();
                    return CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new))
                            .thenCompose(ignored -> store.completeRun(reward.id(), period))
                            .thenApply(run -> {
                                int recovered = (int) attempts.stream().filter(CompletableFuture::join).count();
                                int stillFailed = attempts.size() - recovered;
                                return new RewardRunResult(reward.id(), period, false, recovered, stillFailed, 0);
                            });
                })
                .whenComplete((result, throwable) -> activeRuns.remove(runKey));
    }

    public CompletableFuture<List<RewardStatusView>> status() {
        ZonedDateTime now = ZonedDateTime.now(configManager.current().zoneId());
        String period = ScheduleCalculator.period(now);
        List<CompletableFuture<RewardStatusView>> futures = new ArrayList<>();

        for (RewardDefinition reward : configManager.current().rewards().values()) {
            CompletableFuture<Optional<RewardRun>> current = store.getRun(reward.id(), period);
            CompletableFuture<Optional<RewardRun>> latest = store.getLatestRun(reward.id());
            futures.add(current.thenCombine(latest, (currentRun, latestRun) -> {
                String nextDue;
                if (!reward.scheduleEnabled()) {
                    nextDue = "disabled";
                } else {
                    nextDue = ScheduleCalculator.nextDue(reward, now, currentRun.isPresent()).format(STATUS_TIME);
                }
                String lastRun = latestRun
                        .map(run -> run.period() + " / " + run.status())
                        .orElse("never");
                return new RewardStatusView(reward.id(), reward.scheduleEnabled(), nextDue, lastRun);
            }));
        }

        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
    }

    public CompletableFuture<List<RewardGrant>> historyByUuid(UUID uuid, int limit) {
        return store.historyByUuid(uuid, limit);
    }

    public CompletableFuture<List<RewardGrant>> historyByName(String name, int limit) {
        return store.historyByName(name, limit);
    }

    public CompletableFuture<List<String>> knownPlayerNames(int limit) {
        return store.knownPlayerNames(limit);
    }

    public CompletableFuture<List<String>> failedPeriods(String rewardId, int limit) {
        RewardDefinition reward = requireReward(rewardId);
        return store.getFailedPeriods(reward.id(), limit);
    }

    private CompletableFuture<RewardRunResult> executeSnapshot(
            RewardDefinition reward,
            String period,
            List<EligibleMember> members
    ) {
        List<CompletableFuture<Boolean>> attempts = members.stream()
                .map(member -> processMember(reward, period, member, 0))
                .toList();

        return CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new))
                .thenCompose(ignored -> store.completeRun(reward.id(), period))
                .thenApply(run -> {
                    int granted = (int) attempts.stream().filter(CompletableFuture::join).count();
                    int failed = attempts.size() - granted;
                    return new RewardRunResult(reward.id(), period, false, granted, failed, 0);
                });
    }

    private CompletableFuture<Boolean> retryGrant(RewardDefinition reward, String period, RewardGrant grant) {
        CompletableFuture<String> nameFuture;
        if (grant.playerName() != null && !grant.playerName().isBlank()) {
            nameFuture = CompletableFuture.completedFuture(grant.playerName());
        } else {
            nameFuture = eligibilityService.resolveUsername(grant.playerUuid()).exceptionally(ignored -> null);
        }

        return nameFuture.thenCompose(name -> store.markGrantPending(
                        reward.id(), period, grant.playerUuid(), name)
                .thenCompose(ignored -> processMember(
                        reward,
                        period,
                        new EligibleMember(grant.playerUuid(), name),
                        grant.nextCommandIndex()
                )));
    }

    private CompletableFuture<Boolean> processMember(
            RewardDefinition reward,
            String period,
            EligibleMember member,
            int startCommandIndex
    ) {
        if (member.username() == null || member.username().isBlank()) {
            return store.markGrantFailed(
                            reward.id(),
                            period,
                            member.uuid(),
                            null,
                            "LuckPerms could not resolve a username for this UUID"
                    )
                    .thenApply(ignored -> false);
        }
        if (startCommandIndex < 0 || startCommandIndex > reward.commands().size()) {
            return store.markGrantFailed(
                            reward.id(),
                            period,
                            member.uuid(),
                            member.username(),
                            "Stored command progress is incompatible with the current reward configuration; verify before retrying."
                    )
                    .thenApply(ignored -> false);
        }

        CompletableFuture<Integer> chain = CompletableFuture.completedFuture(startCommandIndex);
        for (int index = startCommandIndex; index < reward.commands().size(); index++) {
            int commandIndex = index;
            String rendered = CommandTemplate.render(
                    reward.commands().get(index),
                    member.username(),
                    member.uuid(),
                    reward.id(),
                    period
            );

            chain = chain.thenCompose(ignored -> dispatchConsoleCommand(rendered)
                    .thenCompose(success -> {
                        if (!success) {
                            return CompletableFuture.failedFuture(
                                    new RewardCommandException("Console command " + (commandIndex + 1) + " was not accepted: " + rendered)
                            );
                        }
                        return store.advanceGrantCommandIndex(
                                reward.id(), period, member.uuid(), commandIndex + 1
                        ).thenApply(nothing -> commandIndex + 1);
                    }));
        }

        return chain.thenCompose(ignored -> store.markGrantGranted(
                        reward.id(), period, member.uuid(), member.username()
                ).thenApply(nothing -> true))
                .exceptionallyCompose(throwable -> {
                    Throwable cause = unwrap(throwable);
                    plugin.getLogger().log(
                            Level.WARNING,
                            "Reward '" + reward.id() + "' failed for " + member.displayName() + " in " + period + ": " + cause.getMessage()
                    );
                    return store.markGrantFailed(
                                    reward.id(), period, member.uuid(), member.username(), safeMessage(cause)
                            )
                            .thenApply(nothing -> false);
                });
    }

    private CompletableFuture<Boolean> dispatchConsoleCommand(String command) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Runnable action = () -> {
            try {
                result.complete(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        };

        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, action);
        }
        return result;
    }

    private RewardDefinition requireReward(String rewardId) {
        return configManager.current().reward(rewardId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown reward: " + rewardId));
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private static final class RewardCommandException extends RuntimeException {
        private RewardCommandException(String message) {
            super(message);
        }
    }
}
