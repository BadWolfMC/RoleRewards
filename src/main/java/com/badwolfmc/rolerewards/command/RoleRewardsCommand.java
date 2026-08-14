package com.badwolfmc.rolerewards.command;

import com.badwolfmc.rolerewards.config.ConfigManager;
import com.badwolfmc.rolerewards.database.RewardGrant;
import com.badwolfmc.rolerewards.message.MessageService;
import com.badwolfmc.rolerewards.reward.RewardPreview;
import com.badwolfmc.rolerewards.reward.RewardRunResult;
import com.badwolfmc.rolerewards.reward.RewardService;
import com.badwolfmc.rolerewards.reward.RewardStatusView;
import com.badwolfmc.rolerewards.schedule.RewardScheduler;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static com.badwolfmc.rolerewards.message.MessageService.text;

public final class RoleRewardsCommand {
    private static final int HISTORY_LIMIT = 25;
    private static final DateTimeFormatter HISTORY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final MessageService messages;
    private final RewardService rewardService;
    private final RewardScheduler scheduler;

    public RoleRewardsCommand(
            JavaPlugin plugin,
            ConfigManager configManager,
            MessageService messages,
            RewardService rewardService,
            RewardScheduler scheduler
    ) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.messages = messages;
        this.rewardService = rewardService;
        this.scheduler = scheduler;
    }

    public com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("rolerewards")
                .requires(source -> Permissions.canUseAny(source.getSender()))
                .executes(this::help);

        root.then(Commands.literal("status")
                .requires(source -> Permissions.has(source.getSender(), Permissions.STATUS))
                .executes(this::status));

        root.then(Commands.literal("preview")
                .requires(source -> Permissions.has(source.getSender(), Permissions.PREVIEW))
                .then(Commands.argument("reward", StringArgumentType.word())
                        .suggests(this::suggestRewards)
                        .executes(context -> preview(context, StringArgumentType.getString(context, "reward")))));

        root.then(Commands.literal("run")
                .requires(source -> Permissions.has(source.getSender(), Permissions.RUN))
                .then(Commands.argument("reward", StringArgumentType.word())
                        .suggests(this::suggestRewards)
                        .executes(context -> run(context, StringArgumentType.getString(context, "reward")))));

        root.then(Commands.literal("retry")
                .requires(source -> Permissions.has(source.getSender(), Permissions.RETRY))
                .then(Commands.argument("reward", StringArgumentType.word())
                        .suggests(this::suggestRewards)
                        .executes(context -> retry(context, StringArgumentType.getString(context, "reward")))));

        root.then(Commands.literal("reload")
                .requires(source -> Permissions.has(source.getSender(), Permissions.RELOAD))
                .executes(this::reload));

        LiteralArgumentBuilder<CommandSourceStack> history = Commands.literal("history")
                .requires(source -> Permissions.has(source.getSender(), Permissions.HISTORY)
                        || Permissions.has(source.getSender(), Permissions.HISTORY_OTHERS))
                .executes(this::historySelf);

        history.then(Commands.argument("player", StringArgumentType.word())
                .requires(source -> Permissions.has(source.getSender(), Permissions.HISTORY_OTHERS))
                .suggests(this::suggestPlayers)
                .executes(context -> historyOther(context, StringArgumentType.getString(context, "player"))));
        root.then(history);

        return root.build();
    }

    private int help(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        messages.send(sender, "help-header");
        helpEntry(sender, Permissions.STATUS, "/rolerewards status", "View scheduler and run status");
        helpEntry(sender, Permissions.PREVIEW, "/rolerewards preview <reward>", "Preview current eligible members");
        helpEntry(sender, Permissions.RUN, "/rolerewards run <reward>", "Run the current reward period manually");
        helpEntry(sender, Permissions.RETRY, "/rolerewards retry <reward>", "Retry failed grants in the current period");
        helpEntry(sender, Permissions.HISTORY, "/rolerewards history", "View your reward history");
        helpEntry(sender, Permissions.HISTORY_OTHERS, "/rolerewards history <player>", "View another player's reward history");
        helpEntry(sender, Permissions.RELOAD, "/rolerewards reload", "Reload configuration and messages");
        return Command.SINGLE_SUCCESS;
    }

    private int status(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!guard(sender, Permissions.STATUS)) {
            return Command.SINGLE_SUCCESS;
        }
        rewardService.status().whenComplete((views, throwable) -> onMain(() -> {
            if (throwable != null) {
                sendFailure(sender, "Status", throwable);
                return;
            }
            messages.send(sender, "status-header");
            for (RewardStatusView view : views) {
                messages.send(sender, "status-entry",
                        text("reward", view.rewardId()),
                        text("schedule", view.scheduleEnabled() ? "enabled" : "disabled"),
                        text("next_due", view.nextDue()),
                        text("last_run", view.lastRun()));
            }
        }));
        return Command.SINGLE_SUCCESS;
    }

    private int preview(CommandContext<CommandSourceStack> context, String rewardId) {
        CommandSender sender = context.getSource().getSender();
        if (!guard(sender, Permissions.PREVIEW) || !knownReward(sender, rewardId)) {
            return Command.SINGLE_SUCCESS;
        }
        messages.send(sender, "operation-started", text("operation", "Preview"), text("reward", rewardId));
        rewardService.preview(rewardId).whenComplete((preview, throwable) -> onMain(() -> {
            if (throwable != null) {
                sendFailure(sender, "Preview", throwable);
                return;
            }
            sendPreview(sender, preview);
        }));
        return Command.SINGLE_SUCCESS;
    }

    private int run(CommandContext<CommandSourceStack> context, String rewardId) {
        CommandSender sender = context.getSource().getSender();
        if (!guard(sender, Permissions.RUN) || !knownReward(sender, rewardId)) {
            return Command.SINGLE_SUCCESS;
        }
        messages.send(sender, "operation-started", text("operation", "Manual run"), text("reward", rewardId));
        rewardService.runCurrentPeriod(rewardId, "MANUAL").whenComplete((result, throwable) -> onMain(() -> {
            if (throwable != null) {
                sendFailure(sender, "Manual run", throwable);
                return;
            }
            sendRunResult(sender, result);
        }));
        return Command.SINGLE_SUCCESS;
    }

    private int retry(CommandContext<CommandSourceStack> context, String rewardId) {
        CommandSender sender = context.getSource().getSender();
        if (!guard(sender, Permissions.RETRY) || !knownReward(sender, rewardId)) {
            return Command.SINGLE_SUCCESS;
        }
        messages.send(sender, "operation-started", text("operation", "Retry"), text("reward", rewardId));
        rewardService.retryCurrentPeriod(rewardId).whenComplete((result, throwable) -> onMain(() -> {
            if (throwable != null) {
                sendFailure(sender, "Retry", throwable);
                return;
            }
            if (result.granted() == 0 && result.failed() == 0) {
                messages.send(sender, "retry-none", text("reward", result.rewardId()), text("period", result.period()));
                return;
            }
            messages.send(sender, "retry-complete",
                    text("reward", result.rewardId()),
                    text("period", result.period()),
                    text("granted", result.granted()),
                    text("failed", result.failed()));
        }));
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!guard(sender, Permissions.RELOAD)) {
            return Command.SINGLE_SUCCESS;
        }
        try {
            configManager.reload();
            messages.reload();
            scheduler.restart();
            messages.send(sender, "reload-success");
        } catch (Exception ex) {
            messages.send(sender, "reload-failed", text("reason", safeMessage(ex)));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int historySelf(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!guard(sender, Permissions.HISTORY)) {
            return Command.SINGLE_SUCCESS;
        }
        if (!(sender instanceof Player player)) {
            messages.send(sender, "player-only");
            return Command.SINGLE_SUCCESS;
        }
        rewardService.historyByUuid(player.getUniqueId(), HISTORY_LIMIT)
                .whenComplete((grants, throwable) -> onMain(() -> sendHistory(sender, player.getName(), grants, throwable)));
        return Command.SINGLE_SUCCESS;
    }

    private int historyOther(CommandContext<CommandSourceStack> context, String playerName) {
        CommandSender sender = context.getSource().getSender();
        if (!guard(sender, Permissions.HISTORY_OTHERS)) {
            return Command.SINGLE_SUCCESS;
        }
        rewardService.historyByName(playerName, HISTORY_LIMIT)
                .whenComplete((grants, throwable) -> onMain(() -> sendHistory(sender, playerName, grants, throwable)));
        return Command.SINGLE_SUCCESS;
    }

    private CompletableFuture<Suggestions> suggestRewards(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        configManager.current().rewards().keySet().stream()
                .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(remaining))
                .forEach(builder::suggest);
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestPlayers(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        if (!Permissions.has(context.getSource().getSender(), Permissions.HISTORY_OTHERS)) {
            return builder.buildFuture();
        }
        String remaining = builder.getRemainingLowerCase();
        return rewardService.knownPlayerNames(200)
                .thenCombine(onlinePlayerNames(), (known, online) -> {
                    Set<String> names = new LinkedHashSet<>(known);
                    names.addAll(online);
                    names.stream()
                            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(remaining))
                            .sorted(String.CASE_INSENSITIVE_ORDER)
                            .forEach(builder::suggest);
                    return builder.build();
                });
    }

    private CompletableFuture<List<String>> onlinePlayerNames() {
        CompletableFuture<List<String>> result = new CompletableFuture<>();
        Runnable collect = () -> result.complete(Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .toList());
        if (Bukkit.isPrimaryThread()) {
            collect.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, collect);
        }
        return result;
    }

    private void sendPreview(CommandSender sender, RewardPreview preview) {
        messages.send(sender, "preview-header",
                text("reward", preview.rewardId()),
                text("period", preview.period()),
                text("eligible", preview.eligible().size()));
        if (preview.runRecorded()) {
            messages.send(sender, "preview-run-recorded");
        }
        if (preview.eligible().isEmpty()) {
            messages.send(sender, "preview-empty");
            return;
        }
        preview.eligible().forEach(member -> {
            RewardGrant grant = preview.grants().get(member.uuid());
            String status;
            if (grant != null) {
                status = grant.status().name().toLowerCase(Locale.ROOT);
            } else if (preview.runRecorded()) {
                status = "not in recorded snapshot";
            } else {
                status = "would be rewarded";
            }
            messages.send(sender, "preview-player", text("player", member.displayName()), text("status", status));
        });
    }

    private void sendRunResult(CommandSender sender, RewardRunResult result) {
        if (result.alreadyRecorded()) {
            messages.send(sender, "run-already-recorded",
                    text("reward", result.rewardId()), text("period", result.period()));
            return;
        }
        messages.send(sender, "run-complete",
                text("reward", result.rewardId()),
                text("period", result.period()),
                text("granted", result.granted()),
                text("failed", result.failed()),
                text("skipped", result.skipped()));
    }

    private void sendHistory(CommandSender sender, String playerName, List<RewardGrant> grants, Throwable throwable) {
        if (throwable != null) {
            sendFailure(sender, "History lookup", throwable);
            return;
        }
        if (grants.isEmpty()) {
            messages.send(sender, "history-empty", text("player", playerName));
            return;
        }
        String canonicalName = grants.stream()
                .map(RewardGrant::playerName)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse(playerName);
        messages.send(sender, "history-header", text("player", canonicalName));
        for (RewardGrant grant : grants) {
            Instant timestamp = grant.grantedAt() != null ? grant.grantedAt() : grant.updatedAt();
            String status = grant.status().name().toLowerCase(Locale.ROOT);
            if (grant.failureReason() != null && !grant.failureReason().isBlank()) {
                status += " — " + grant.failureReason();
            }
            messages.send(sender, "history-entry",
                    text("period", grant.period()),
                    text("reward", grant.rewardId()),
                    text("status", status),
                    text("timestamp", HISTORY_TIME.withZone(configManager.current().zoneId()).format(timestamp)));
        }
    }

    private void helpEntry(CommandSender sender, String permission, String command, String description) {
        if (Permissions.has(sender, permission)) {
            messages.send(sender, "help-entry", text("command", command), text("description", description));
        }
    }

    private boolean guard(CommandSender sender, String permission) {
        if (Permissions.has(sender, permission)) {
            return true;
        }
        messages.send(sender, "no-permission");
        return false;
    }

    private boolean knownReward(CommandSender sender, String rewardId) {
        if (configManager.current().reward(rewardId).isPresent()) {
            return true;
        }
        messages.send(sender, "unknown-reward", text("reward", rewardId));
        return false;
    }

    private void sendFailure(CommandSender sender, String operation, Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause instanceof java.util.concurrent.CompletionException) {
            cause = cause.getCause();
        }
        messages.send(sender, "operation-failed", text("operation", operation), text("reason", safeMessage(cause)));
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private void onMain(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }
}
