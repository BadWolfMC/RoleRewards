package com.badwolfmc.rolerewards.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlFileIOTest {
    @TempDir
    Path tempDir;

    @Test
    void existingFileIsBackedUpBeforeSafeReplacement() throws Exception {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "config-version: 0\ncustom: old\n", StandardCharsets.UTF_8);

        var result = YamlFileIO.pendingWrite(
                file,
                "config-version: 1\ncustom: new\n",
                true,
                0,
                1
        ).commit();

        assertTrue(result.isPresent());
        Path backup = result.orElseThrow();
        assertTrue(backup.getFileName().toString().contains("config.yml.v0-to-v1."));
        assertTrue(backup.getFileName().toString().contains(".bak"));
        assertEquals("config-version: 0\ncustom: old\n", Files.readString(backup));
        assertEquals("config-version: 1\ncustom: new\n", Files.readString(file));
    }

    @Test
    void firstRunCreationDoesNotCreateMeaninglessBackup() throws Exception {
        Path file = tempDir.resolve("messages.yml");

        var result = YamlFileIO.pendingWrite(
                file,
                "messages-version: 1\nprefix: test\n",
                false,
                1,
                1
        ).commit();

        assertFalse(result.isPresent());
        assertEquals("messages-version: 1\nprefix: test\n", Files.readString(file));
    }

    @Test
    void malformedYamlLoadLeavesOriginalBytesUntouched() throws Exception {
        Path file = tempDir.resolve("config.yml");
        String malformed = "config-version: 0\nrewards: [\n";
        Files.writeString(file, malformed, StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> YamlFileIO.load(file, "config.yml"));
        assertEquals(malformed, Files.readString(file));
    }
}
