package com.badwolfmc.rolerewards.schedule;

import com.badwolfmc.rolerewards.config.ConfigManager;
import com.badwolfmc.rolerewards.config.RewardDefinition;
import com.badwolfmc.rolerewards.database.SqliteStore;
import com.badwolfmc.rolerewards.reward.RewardService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public final class RewardScheduler {
    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final SqliteStore store;
    private final RewardService rewardService;
    private final AtomicBoolean checking = new AtomicBoolean(false);
    private final AtomicLong generation = new AtomicLong();
    private BukkitTask task;

    public RewardScheduler(
            JavaPlugin plugin,
            ConfigManager configManager,
            SqliteStore store,
            RewardService rewardService
    ) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.store = store;
        this.rewardService = rewardService;
    }

    public void start() {
        stop();
        long schedulerGeneration = generation.get();
        long intervalTicks = configManager.current().schedulerCheckMinutes() * 60L * 20L;
        task = Bukkit.getScheduler().runTaskTimer(
                plugin,
                () -> checkDueRewards(schedulerGeneration),
                100L,
                intervalTicks
        );
    }

    public void stop() {
        generation.incrementAndGet();
        checking.set(false);
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void checkDueRewards(long schedulerGeneration) {
        if (generation.get() != schedulerGeneration || !checking.compareAndSet(false, true)) {
            return;
        }

        var config = configManager.current();
        ZonedDateTime now = ZonedDateTime.now(config.zoneId());
        String period = ScheduleCalculator.period(now);
        YearMonth yearMonth = YearMonth.from(now);

        var enabled = config.rewards().values().stream()
                .filter(RewardDefinition::scheduleEnabled)
                .toList();

        if (enabled.isEmpty()) {
            finishCheck(schedulerGeneration);
            return;
        }

        var checks = enabled.stream().map(reward -> {
            if (now.isBefore(ScheduleCalculator.dueAt(reward, yearMonth, now.getZone()))) {
                return CompletableFuture.completedFuture(null);
            }
            return store.getRun(reward.id(), period)
                    .thenCompose(existing -> {
                        if (existing.isPresent() || generation.get() != schedulerGeneration) {
                            return CompletableFuture.completedFuture(null);
                        }
                        return rewardService.runPeriod(reward.id(), yearMonth, "AUTO")
                                .thenAccept(result -> plugin.getLogger().info(
                                        "Automatic reward '" + reward.id() + "' / " + period
                                                + " completed: " + result.granted() + " granted, "
                                                + result.failed() + " failed."
                                ));
                    })
                    .exceptionally(throwable -> {
                        if (generation.get() == schedulerGeneration) {
                            plugin.getLogger().log(Level.SEVERE,
                                    "Automatic reward check failed for '" + reward.id() + "'", throwable);
                        }
                        return null;
                    });
        }).toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(checks)
                .whenComplete((ignored, throwable) -> finishCheck(schedulerGeneration));
    }

    private void finishCheck(long schedulerGeneration) {
        if (generation.get() == schedulerGeneration) {
            checking.set(false);
        }
    }
}
