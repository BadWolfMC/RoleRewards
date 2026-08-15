package com.badwolfmc.rolerewards.database;

import com.badwolfmc.rolerewards.eligibility.EligibleMember;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteStoreTest {
    @TempDir
    Path tempDir;

    private SqliteStore store;

    @AfterEach
    void closeStore() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void initializesAndRecordsSchemaVersion() throws Exception {
        Path database = tempDir.resolve("test.db");
        store = new SqliteStore(database, Logger.getAnonymousLogger());
        store.initialize();

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA user_version")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
    }

    @Test
    void newerDatabaseSchemaIsRejected() throws Exception {
        Path database = tempDir.resolve("test.db");
        store = new SqliteStore(database, Logger.getAnonymousLogger());
        store.initialize();
        store.close();
        store = null;

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 2");
        }

        var newerStore = new SqliteStore(database, Logger.getAnonymousLogger());
        try {
            var thrown = assertThrows(java.sql.SQLException.class, newerStore::initialize);
            assertTrue(thrown.getMessage().contains("newer than this plugin supports"));
        } finally {
            newerStore.close();
        }
    }

    @Test
    void currentSchemaVersionStillRejectsStructurallyIncompatibleDatabase() throws Exception {
        Path database = tempDir.resolve("test.db");

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE reward_runs (
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
                    CREATE TABLE reward_grants (
                        reward_id TEXT NOT NULL,
                        period TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        player_name TEXT,
                        status TEXT NOT NULL,
                        failure_reason TEXT,
                        granted_at TEXT,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY (reward_id, period, player_uuid)
                    )
                    """);
            statement.execute("PRAGMA user_version = 1");
        }

        store = new SqliteStore(database, Logger.getAnonymousLogger());
        var thrown = assertThrows(java.sql.SQLException.class, store::initialize);
        assertTrue(thrown.getMessage().contains("next_command_index"));
    }

    @Test
    void pendingWorkIsRecoveredAsFailedAfterRestart() throws Exception {
        Path database = tempDir.resolve("test.db");
        UUID uuid = UUID.randomUUID();

        store = new SqliteStore(database, Logger.getAnonymousLogger());
        store.initialize();
        assertTrue(store.createRunSnapshot(
                "companion",
                "2026-08",
                "TEST",
                List.of(new EligibleMember(uuid, "ExamplePlayer"))
        ).join());
        store.close();

        store = new SqliteStore(database, Logger.getAnonymousLogger());
        store.initialize();

        var failed = store.getFailedGrants("companion", "2026-08").join();
        assertEquals(1, failed.size());
        assertEquals(uuid, failed.getFirst().playerUuid());
        assertTrue(failed.getFirst().failureReason().contains("interrupted"));

        var run = store.getRun("companion", "2026-08").join().orElseThrow();
        assertEquals("INTERRUPTED", run.status());
        assertEquals(0, run.grantedCount());
        assertEquals(1, run.failedCount());
    }

    @Test
    void runningZeroRecipientSnapshotIsSafelyCompletedAfterRestart() throws Exception {
        Path database = tempDir.resolve("test.db");

        store = new SqliteStore(database, Logger.getAnonymousLogger());
        store.initialize();
        assertTrue(store.createRunSnapshot("companion", "2026-08", "TEST", List.of()).join());
        store.close();

        store = new SqliteStore(database, Logger.getAnonymousLogger());
        store.initialize();

        RewardRun run = store.getRun("companion", "2026-08").join().orElseThrow();
        assertEquals("COMPLETE", run.status());
        assertEquals(0, run.grantedCount());
        assertEquals(0, run.failedCount());
    }

    @Test
    void runningSnapshotWithOnlyTerminalGrantsIsSafelyFinalizedAfterRestart() throws Exception {
        Path database = tempDir.resolve("test.db");
        UUID uuid = UUID.randomUUID();

        store = new SqliteStore(database, Logger.getAnonymousLogger());
        store.initialize();
        assertTrue(store.createRunSnapshot(
                "companion",
                "2026-08",
                "TEST",
                List.of(new EligibleMember(uuid, "ExamplePlayer"))
        ).join());
        store.markGrantGranted("companion", "2026-08", uuid, "ExamplePlayer").join();
        store.close();

        store = new SqliteStore(database, Logger.getAnonymousLogger());
        store.initialize();

        RewardRun run = store.getRun("companion", "2026-08").join().orElseThrow();
        assertEquals("COMPLETE", run.status());
        assertEquals(1, run.grantedCount());
        assertEquals(0, run.failedCount());
    }

    @Test
    void runningSnapshotWithTerminalFailureIsFinalizedWithFailuresAfterRestart() throws Exception {
        Path database = tempDir.resolve("test.db");
        UUID uuid = UUID.randomUUID();

        store = new SqliteStore(database, Logger.getAnonymousLogger());
        store.initialize();
        assertTrue(store.createRunSnapshot(
                "companion",
                "2026-08",
                "TEST",
                List.of(new EligibleMember(uuid, "ExamplePlayer"))
        ).join());
        store.markGrantFailed("companion", "2026-08", uuid, "ExamplePlayer", "test failure").join();
        store.close();

        store = new SqliteStore(database, Logger.getAnonymousLogger());
        store.initialize();

        RewardRun run = store.getRun("companion", "2026-08").join().orElseThrow();
        assertEquals("COMPLETE_WITH_FAILURES", run.status());
        assertEquals(0, run.grantedCount());
        assertEquals(1, run.failedCount());
    }

    @Test
    void runAndGrantKeysPreventDuplicateSnapshots() throws Exception {
        store = new SqliteStore(tempDir.resolve("test.db"), Logger.getAnonymousLogger());
        store.initialize();

        UUID uuid = UUID.randomUUID();
        var members = List.of(new EligibleMember(uuid, "ExamplePlayer"));

        assertTrue(store.createRunSnapshot("companion", "2026-08", "TEST", members).join());
        assertFalse(store.createRunSnapshot("companion", "2026-08", "TEST", members).join());

        var grants = store.getGrants("companion", "2026-08").join();
        assertEquals(1, grants.size());
        assertEquals(GrantStatus.PENDING, grants.getFirst().status());
    }

    @Test
    void failedGrantKeepsCommandProgressForRetry() throws Exception {
        store = new SqliteStore(tempDir.resolve("test.db"), Logger.getAnonymousLogger());
        store.initialize();

        UUID uuid = UUID.randomUUID();
        var members = List.of(new EligibleMember(uuid, "ExamplePlayer"));
        assertTrue(store.createRunSnapshot("companion", "2026-08", "TEST", members).join());

        store.advanceGrantCommandIndex("companion", "2026-08", uuid, 1).join();
        store.markGrantFailed("companion", "2026-08", uuid, "ExamplePlayer", "second command failed").join();

        var failed = store.getFailedGrants("companion", "2026-08").join();
        assertEquals(1, failed.size());
        assertEquals(1, failed.getFirst().nextCommandIndex());
        assertEquals("second command failed", failed.getFirst().failureReason());
    }

    @Test
    void failedPeriodsRemainDiscoverableAfterMonthRollover() throws Exception {
        store = new SqliteStore(tempDir.resolve("test.db"), Logger.getAnonymousLogger());
        store.initialize();

        UUID uuid = UUID.randomUUID();
        var member = new EligibleMember(uuid, "ExamplePlayer");
        assertTrue(store.createRunSnapshot("companion", "2026-07", "TEST", List.of(member)).join());
        store.markGrantFailed("companion", "2026-07", uuid, "ExamplePlayer", "test failure").join();

        assertEquals(List.of("2026-07"), store.getFailedPeriods("companion", 24).join());
    }

    @Test
    void missingGrantMutationFailsInsteadOfSilentlySucceeding() throws Exception {
        store = new SqliteStore(tempDir.resolve("test.db"), Logger.getAnonymousLogger());
        store.initialize();

        var thrown = assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> store.markGrantGranted(
                        "companion", "2026-08", UUID.randomUUID(), "MissingPlayer"
                ).join()
        );
        assertInstanceOf(SqliteStore.StoreException.class, thrown.getCause());
    }

    @Test
    void zeroEligibleRunStillRecordsThePeriod() throws Exception {
        store = new SqliteStore(tempDir.resolve("test.db"), Logger.getAnonymousLogger());
        store.initialize();

        assertTrue(store.createRunSnapshot("companion", "2026-08", "TEST", List.of()).join());
        RewardRun run = store.completeRun("companion", "2026-08").join();

        assertEquals("COMPLETE", run.status());
        assertEquals(0, run.eligibleCount());
        assertTrue(store.getRun("companion", "2026-08").join().isPresent());
    }
}
