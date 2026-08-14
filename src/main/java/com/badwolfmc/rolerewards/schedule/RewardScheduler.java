package com.badwolfmc.rolerewards.schedule;

import com.badwolfmc.rolerewards.config.ConfigManager;
import com.badwolfmc.rolerewards.config.RewardDefinition;
import com.badwolfmc.rolerewards.database.SqliteStore;
import com.badwolfmc.rolerewards.reward.RewardService;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class RewardScheduler {
    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final SqliteStore store;
    private final RewardService rewardService;
    private final AtomicBoolean checking = new AtomicBoolean(false);
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
        long intervalTicks = configManager.current().schedulerCheckMinutes() * 60L * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::checkDueRewards, 100L, intervalTicks);
    }

    public void restart() {
        start();
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void checkDueRewards() {
        if (!checking.compareAndSet(false, true)) {
            return;
        }

        ZonedDateTime now = ZonedDateTime.now(configManager.current().zoneId());
        String period = ScheduleCalculator.period(now);
        YearMonth yearMonth = YearMonth.from(now);

        var enabled = configManager.current().rewards().values().stream()
                .filter(RewardDefinition::scheduleEnabled)
                .toList();

        if (enabled.isEmpty()) {
            checking.set(false);
            return;
        }

        var checks = enabled.stream().map(reward -> {
            if (now.isBefore(ScheduleCalculator.dueAt(reward, yearMonth, now.getZone()))) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            return store.getRun(reward.id(), period)
                    .thenCompose(existing -> {
                        if (existing.isPresent()) {
                            return java.util.concurrent.CompletableFuture.completedFuture(null);
                        }
                        return rewardService.runPeriod(reward.id(), yearMonth, "AUTO")
                                .thenAccept(result -> plugin.getLogger().info(
                                        "Automatic reward '" + reward.id() + "' / " + period
                                                + " completed: " + result.granted() + " granted, "
                                                + result.failed() + " failed."
                                ));
                    })
                    .exceptionally(throwable -> {
                        plugin.getLogger().log(Level.SEVERE,
                                "Automatic reward check failed for '" + reward.id() + "'", throwable);
                        return null;
                    });
        }).toArray(java.util.concurrent.CompletableFuture[]::new);

        java.util.concurrent.CompletableFuture.allOf(checks)
                .whenComplete((ignored, throwable) -> checking.set(false));
    }
}
