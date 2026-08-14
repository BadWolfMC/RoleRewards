package com.badwolfmc.rolerewards.database;

import com.badwolfmc.rolerewards.eligibility.EligibleMember;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
