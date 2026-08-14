package com.badwolfmc.rolerewards.database;

import com.badwolfmc.rolerewards.eligibility.EligibleMember;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SqliteStore implements AutoCloseable {
    private final Path databasePath;
    private final Logger logger;
    private final ExecutorService executor;

    public SqliteStore(Path databasePath, Logger logger) {
        this.databasePath = databasePath;
        this.logger = logger;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "RoleRewards-SQLite");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void initialize() throws Exception {
        Files.createDirectories(databasePath.getParent());
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS reward_runs (
                        reward_id TEXT NOT NULL,
                        period TEXT NOT NULL,
                        status TEXT NOT NULL,
                        trigger TEXT NOT NULL,
                        started_at TEXT NOT NULL,
                        completed_at TEXT,
                        eligible_count INTEGER NOT NULL DEFAULT 0,
                        granted_count INTEGER NOT NULL DEFAULT 0,
                        failed_count INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (reward_id, period)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS reward_grants (
                        reward_id TEXT NOT NULL,
                        period TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        player_name TEXT,
                        status TEXT NOT NULL,
                        failure_reason TEXT,
                        granted_at TEXT,
                        updated_at TEXT NOT NULL,
                        next_command_index INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (reward_id, period, player_uuid),
                        FOREIGN KEY (reward_id, period)
                            REFERENCES reward_runs(reward_id, period)
                            ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_reward_grants_uuid ON reward_grants(player_uuid)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_reward_grants_name ON reward_grants(player_name COLLATE NOCASE)");
        }
        recoverInterruptedWork();
    }

    private void recoverInterruptedWork() throws SQLException {
        Instant now = Instant.now();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement grants = connection.prepareStatement("""
                    UPDATE reward_grants
                    SET status = 'FAILED',
                        failure_reason = COALESCE(failure_reason, 'Previous reward run was interrupted before completion; verify before retrying.'),
                        updated_at = ?
                    WHERE status = 'PENDING'
                    """)) {
                grants.setString(1, now.toString());
                int recovered = grants.executeUpdate();
                if (recovered > 0) {
                    logger.warning("Recovered " + recovered + " interrupted pending reward grant(s) as FAILED for manual review.");
                }
            }
            try (PreparedStatement runs = connection.prepareStatement("""
                    UPDATE reward_runs
                    SET status = 'INTERRUPTED', completed_at = COALESCE(completed_at, ?)
                    WHERE status = 'RUNNING'
                    """)) {
                runs.setString(1, now.toString());
                runs.executeUpdate();
            }
            connection.commit();
        }
    }

    public CompletableFuture<Optional<RewardRun>> getRun(String rewardId, String period) {
        return supplyAsync(() -> {
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM reward_runs WHERE reward_id = ? AND period = ?
                    """)) {
                statement.setString(1, rewardId);
                statement.setString(2, period);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.of(readRun(rs)) : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<Optional<RewardRun>> getLatestRun(String rewardId) {
        return supplyAsync(() -> {
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM reward_runs
                    WHERE reward_id = ?
                    ORDER BY period DESC
                    LIMIT 1
                    """)) {
                statement.setString(1, rewardId);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.of(readRun(rs)) : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<List<RewardGrant>> getGrants(String rewardId, String period) {
        return supplyAsync(() -> queryGrants("""
                SELECT * FROM reward_grants
                WHERE reward_id = ? AND period = ?
                ORDER BY player_name COLLATE NOCASE, player_uuid
                """, rewardId, period));
    }

    public CompletableFuture<List<RewardGrant>> getFailedGrants(String rewardId, String period) {
        return supplyAsync(() -> queryGrants("""
                SELECT * FROM reward_grants
                WHERE reward_id = ? AND period = ? AND status = 'FAILED'
                ORDER BY player_name COLLATE NOCASE, player_uuid
                """, rewardId, period));
    }

    public CompletableFuture<List<String>> getFailedPeriods(String rewardId, int limit) {
        return supplyAsync(() -> {
            List<String> periods = new ArrayList<>();
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                    SELECT DISTINCT period
                    FROM reward_grants
                    WHERE reward_id = ? AND status = 'FAILED'
                    ORDER BY period DESC
                    LIMIT ?
                    """)) {
                statement.setString(1, rewardId);
                statement.setInt(2, limit);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        periods.add(rs.getString("period"));
                    }
                }
            }
            return List.copyOf(periods);
        });
    }

    public CompletableFuture<Boolean> createRunSnapshot(
            String rewardId,
            String period,
            String trigger,
            List<EligibleMember> members
    ) {
        return supplyAsync(() -> {
            Instant now = Instant.now();
            try (Connection connection = open()) {
                connection.setAutoCommit(false);
                try {
                    int inserted;
                    try (PreparedStatement run = connection.prepareStatement("""
                            INSERT OR IGNORE INTO reward_runs
                                (reward_id, period, status, trigger, started_at, eligible_count)
                            VALUES (?, ?, 'RUNNING', ?, ?, ?)
                            """)) {
                        run.setString(1, rewardId);
                        run.setString(2, period);
                        run.setString(3, trigger);
                        run.setString(4, now.toString());
                        run.setInt(5, members.size());
                        inserted = run.executeUpdate();
                    }
                    if (inserted == 0) {
                        connection.rollback();
                        return false;
                    }

                    try (PreparedStatement grant = connection.prepareStatement("""
                            INSERT INTO reward_grants
                                (reward_id, period, player_uuid, player_name, status, updated_at)
                            VALUES (?, ?, ?, ?, 'PENDING', ?)
                            """)) {
                        for (EligibleMember member : members) {
                            grant.setString(1, rewardId);
                            grant.setString(2, period);
                            grant.setString(3, member.uuid().toString());
                            grant.setString(4, member.username());
                            grant.setString(5, now.toString());
                            grant.addBatch();
                        }
                        grant.executeBatch();
                    }
                    connection.commit();
                    return true;
                } catch (SQLException ex) {
                    connection.rollback();
                    throw ex;
                }
            }
        });
    }

    public CompletableFuture<Void> advanceGrantCommandIndex(String rewardId, String period, UUID uuid, int nextCommandIndex) {
        return runAsync(() -> {
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                    UPDATE reward_grants
                    SET next_command_index = ?, updated_at = ?
                    WHERE reward_id = ? AND period = ? AND player_uuid = ?
                    """)) {
                statement.setInt(1, nextCommandIndex);
                statement.setString(2, Instant.now().toString());
                statement.setString(3, rewardId);
                statement.setString(4, period);
                statement.setString(5, uuid.toString());
                requireSingleUpdate(statement.executeUpdate(), "advance command progress for " + rewardId + " / " + period + " / " + uuid);
            }
        });
    }

    public CompletableFuture<Void> markGrantGranted(String rewardId, String period, UUID uuid, String playerName) {
        return runAsync(() -> {
            Instant now = Instant.now();
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                    UPDATE reward_grants
                    SET player_name = ?, status = 'GRANTED', failure_reason = NULL,
                        granted_at = ?, updated_at = ?
                    WHERE reward_id = ? AND period = ? AND player_uuid = ?
                    """)) {
                statement.setString(1, playerName);
                statement.setString(2, now.toString());
                statement.setString(3, now.toString());
                statement.setString(4, rewardId);
                statement.setString(5, period);
                statement.setString(6, uuid.toString());
                requireSingleUpdate(statement.executeUpdate(), "mark grant GRANTED for " + rewardId + " / " + period + " / " + uuid);
            }
        });
    }

    public CompletableFuture<Void> markGrantFailed(String rewardId, String period, UUID uuid, String playerName, String reason) {
        return runAsync(() -> {
            Instant now = Instant.now();
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                    UPDATE reward_grants
                    SET player_name = ?, status = 'FAILED', failure_reason = ?, updated_at = ?
                    WHERE reward_id = ? AND period = ? AND player_uuid = ?
                    """)) {
                statement.setString(1, playerName);
                statement.setString(2, reason);
                statement.setString(3, now.toString());
                statement.setString(4, rewardId);
                statement.setString(5, period);
                statement.setString(6, uuid.toString());
                requireSingleUpdate(statement.executeUpdate(), "mark grant FAILED for " + rewardId + " / " + period + " / " + uuid);
            }
        });
    }

    public CompletableFuture<Void> markGrantPending(String rewardId, String period, UUID uuid, String playerName) {
        return runAsync(() -> {
            Instant now = Instant.now();
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                    UPDATE reward_grants
                    SET player_name = ?, status = 'PENDING', failure_reason = NULL, updated_at = ?
                    WHERE reward_id = ? AND period = ? AND player_uuid = ?
                    """)) {
                statement.setString(1, playerName);
                statement.setString(2, now.toString());
                statement.setString(3, rewardId);
                statement.setString(4, period);
                statement.setString(5, uuid.toString());
                requireSingleUpdate(statement.executeUpdate(), "mark grant PENDING for " + rewardId + " / " + period + " / " + uuid);
            }
        });
    }

    public CompletableFuture<RewardRun> completeRun(String rewardId, String period) {
        return supplyAsync(() -> {
            try (Connection connection = open()) {
                int granted = countByStatus(connection, rewardId, period, "GRANTED");
                int failed = countByStatus(connection, rewardId, period, "FAILED");
                int pending = countByStatus(connection, rewardId, period, "PENDING");
                String status = pending > 0 ? "INTERRUPTED" : (failed > 0 ? "COMPLETE_WITH_FAILURES" : "COMPLETE");
                Instant now = Instant.now();
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE reward_runs
                        SET status = ?, completed_at = ?, granted_count = ?, failed_count = ?
                        WHERE reward_id = ? AND period = ?
                        """)) {
                    statement.setString(1, status);
                    statement.setString(2, now.toString());
                    statement.setInt(3, granted);
                    statement.setInt(4, failed);
                    statement.setString(5, rewardId);
                    statement.setString(6, period);
                    requireSingleUpdate(statement.executeUpdate(), "complete reward run " + rewardId + " / " + period);
                }
                return loadRun(connection, rewardId, period)
                        .orElseThrow(() -> new SQLException("Run disappeared while completing it"));
            }
        });
    }

    public CompletableFuture<List<RewardGrant>> historyByUuid(UUID uuid, int limit) {
        return supplyAsync(() -> {
            List<RewardGrant> result = new ArrayList<>();
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM reward_grants
                    WHERE player_uuid = ?
                    ORDER BY period DESC, updated_at DESC
                    LIMIT ?
                    """)) {
                statement.setString(1, uuid.toString());
                statement.setInt(2, limit);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        result.add(readGrant(rs));
                    }
                }
            }
            return result;
        });
    }

    public CompletableFuture<List<RewardGrant>> historyByName(String playerName, int limit) {
        return supplyAsync(() -> {
            List<RewardGrant> result = new ArrayList<>();
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM reward_grants
                    WHERE player_name = ? COLLATE NOCASE
                    ORDER BY period DESC, updated_at DESC
                    LIMIT ?
                    """)) {
                statement.setString(1, playerName);
                statement.setInt(2, limit);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        result.add(readGrant(rs));
                    }
                }
            }
            return result;
        });
    }

    public CompletableFuture<List<String>> knownPlayerNames(int limit) {
        return supplyAsync(() -> {
            Set<String> names = new LinkedHashSet<>();
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                    SELECT player_name, MAX(updated_at) AS latest
                    FROM reward_grants
                    WHERE player_name IS NOT NULL AND player_name <> ''
                    GROUP BY player_name COLLATE NOCASE
                    ORDER BY latest DESC
                    LIMIT ?
                    """)) {
                statement.setInt(1, limit);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        names.add(rs.getString("player_name"));
                    }
                }
            }
            return List.copyOf(names);
        });
    }

    private List<RewardGrant> queryGrants(String sql, String rewardId, String period) throws SQLException {
        List<RewardGrant> result = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, rewardId);
            statement.setString(2, period);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(readGrant(rs));
                }
            }
        }
        return result;
    }

    private int countByStatus(Connection connection, String rewardId, String period, String status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM reward_grants
                WHERE reward_id = ? AND period = ? AND status = ?
                """)) {
            statement.setString(1, rewardId);
            statement.setString(2, period);
            statement.setString(3, status);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private void requireSingleUpdate(int updatedRows, String operation) throws SQLException {
        if (updatedRows != 1) {
            throw new SQLException("Expected exactly one row while attempting to " + operation + ", but updated " + updatedRows);
        }
    }

    private Optional<RewardRun> loadRun(Connection connection, String rewardId, String period) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM reward_runs WHERE reward_id = ? AND period = ?
                """)) {
            statement.setString(1, rewardId);
            statement.setString(2, period);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(readRun(rs)) : Optional.empty();
            }
        }
    }

    private RewardRun readRun(ResultSet rs) throws SQLException {
        return new RewardRun(
                rs.getString("reward_id"),
                rs.getString("period"),
                rs.getString("status"),
                rs.getString("trigger"),
                Instant.parse(rs.getString("started_at")),
                parseInstant(rs.getString("completed_at")),
                rs.getInt("eligible_count"),
                rs.getInt("granted_count"),
                rs.getInt("failed_count")
        );
    }

    private RewardGrant readGrant(ResultSet rs) throws SQLException {
        return new RewardGrant(
                rs.getString("reward_id"),
                rs.getString("period"),
                UUID.fromString(rs.getString("player_uuid")),
                rs.getString("player_name"),
                GrantStatus.valueOf(rs.getString("status")),
                rs.getString("failure_reason"),
                parseInstant(rs.getString("granted_at")),
                Instant.parse(rs.getString("updated_at")),
                rs.getInt("next_command_index")
        );
    }

    private Instant parseInstant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA journal_mode = WAL");
        }
        return connection;
    }

    private <T> CompletableFuture<T> supplyAsync(SqlSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (Exception ex) {
                throw new StoreException(ex);
            }
        }, executor);
    }

    private CompletableFuture<Void> runAsync(SqlRunnable runnable) {
        return CompletableFuture.runAsync(() -> {
            try {
                runnable.run();
            } catch (Exception ex) {
                throw new StoreException(ex);
            }
        }, executor);
    }

    @Override
    public void close() {
        executor.shutdown();
        boolean terminated = false;
        try {
            terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
            if (!terminated) {
                executor.shutdownNow();
                terminated = executor.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }

        if (terminated) {
            checkpointWal();
        } else {
            logger.warning("RoleRewards database executor did not terminate cleanly; skipping final WAL checkpoint.");
        }
    }

    private void checkpointWal() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (SQLException ex) {
            logger.log(Level.WARNING, "Could not checkpoint RoleRewards SQLite WAL during shutdown.", ex);
        }
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    private interface SqlRunnable {
        void run() throws Exception;
    }

    public static final class StoreException extends RuntimeException {
        public StoreException(Throwable cause) {
            super(cause);
        }
    }
}
