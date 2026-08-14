package com.badwolfmc.rolerewards;

import com.badwolfmc.rolerewards.command.RoleRewardsCommand;
import com.badwolfmc.rolerewards.config.ConfigManager;
import com.badwolfmc.rolerewards.database.SqliteStore;
import com.badwolfmc.rolerewards.eligibility.LuckPermsEligibilityService;
import com.badwolfmc.rolerewards.message.MessageService;
import com.badwolfmc.rolerewards.reward.RewardService;
import com.badwolfmc.rolerewards.schedule.RewardScheduler;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.List;

public final class RoleRewardsPlugin extends JavaPlugin {
    private SqliteStore store;
    private RewardScheduler scheduler;

    @Override
    public void onEnable() {
        try {
            ConfigManager configManager = new ConfigManager(this);
            configManager.loadInitial();

            MessageService messages = new MessageService(this);
            messages.loadInitial();

            LuckPerms luckPerms = LuckPermsProvider.get();
            LuckPermsEligibilityService eligibilityService = new LuckPermsEligibilityService(luckPerms);

            Path databasePath = getDataFolder().toPath().resolve("rolerewards.db");
            this.store = new SqliteStore(databasePath, getLogger());
            store.initialize();

            RewardService rewardService = new RewardService(this, configManager, store, eligibilityService);
            this.scheduler = new RewardScheduler(this, configManager, store, rewardService);

            RoleRewardsCommand command = new RoleRewardsCommand(
                    this, configManager, messages, rewardService, scheduler
            );
            getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                    event.registrar().register(
                            command.build(),
                            "Manage scheduled LuckPerms role rewards",
                            List.of("rr")
                    )
            );

            scheduler.start();
            getLogger().info("RoleRewards enabled with " + configManager.current().rewards().size() + " configured reward(s).");
        } catch (Exception ex) {
            getLogger().log(java.util.logging.Level.SEVERE, "RoleRewards could not start; disabling plugin.", ex);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (scheduler != null) {
            scheduler.stop();
        }
        if (store != null) {
            store.close();
        }
    }
}
