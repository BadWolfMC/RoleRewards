package com.badwolfmc.rolerewards.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.Set;

public final class YamlFileIO {
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC);

    private YamlFileIO() {
    }

    public static YamlConfiguration load(Path path, String displayName) {
        YamlConfiguration config = new YamlConfiguration();
        config.options().parseComments(true);
        try {
            config.load(path.toFile());
            return config;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not load " + displayName, ex);
        }
    }

    public static YamlConfiguration loadResource(JavaPlugin plugin, String resourceName) {
        try (var input = plugin.getResource(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("Bundled resource is missing: " + resourceName);
            }
            YamlConfiguration config = new YamlConfiguration();
            config.options().parseComments(true);
            try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                config.load(reader);
            }
            return config;
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read bundled resource " + resourceName, ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not parse bundled resource " + resourceName, ex);
        }
    }

    public static PendingWrite pendingWrite(
            Path target,
            String content,
            boolean existingFile,
            int fromVersion,
            int toVersion
    ) {
        return new PendingWrite(target, content, existingFile, fromVersion, toVersion, Clock.systemUTC());
    }

    public static final class PendingWrite {
        private final Path target;
        private final String content;
        private final boolean existingFile;
        private final int fromVersion;
        private final int toVersion;
        private final Clock clock;

        PendingWrite(
                Path target,
                String content,
                boolean existingFile,
                int fromVersion,
                int toVersion,
                Clock clock
        ) {
            this.target = target;
            this.content = content;
            this.existingFile = existingFile;
            this.fromVersion = fromVersion;
            this.toVersion = toVersion;
            this.clock = clock;
        }

        public Optional<Path> commit() throws IOException {
            Path parent = target.toAbsolutePath().getParent();
            if (parent == null) {
                throw new IOException("Cannot determine parent directory for " + target);
            }
            Files.createDirectories(parent);

            Path backup = null;
            if (existingFile) {
                if (!Files.isRegularFile(target)) {
                    throw new IOException("Expected existing configuration file: " + target);
                }
                backup = nextBackupPath(parent, target.getFileName().toString(), fromVersion, toVersion, clock.instant());
                Files.copy(target, backup, StandardCopyOption.COPY_ATTRIBUTES);
            }

            Path temporary = Files.createTempFile(parent, "." + target.getFileName() + ".", ".tmp");
            try {
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                try (FileChannel channel = FileChannel.open(
                        temporary,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING
                )) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    channel.force(true);
                }

                copyPosixPermissionsIfAvailable(target, temporary, existingFile);

                try {
                    Files.move(
                            temporary,
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING
                    );
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }

            return Optional.ofNullable(backup);
        }

        private static void copyPosixPermissionsIfAvailable(Path source, Path target, boolean sourceExists) {
            if (!sourceExists) {
                return;
            }
            try {
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(source);
                Files.setPosixFilePermissions(target, permissions);
            } catch (UnsupportedOperationException | IOException ignored) {
                // Windows/non-POSIX filesystems do not expose these attributes.
            }
        }

        private static Path nextBackupPath(
                Path parent,
                String fileName,
                int fromVersion,
                int toVersion,
                Instant instant
        ) {
            String stamp = BACKUP_TIME.format(instant);
            String baseName = fileName + ".v" + fromVersion + "-to-v" + toVersion + "." + stamp + ".bak";
            Path candidate = parent.resolve(baseName);
            int suffix = 1;
            while (Files.exists(candidate)) {
                candidate = parent.resolve(baseName + "." + suffix++);
            }
            return candidate;
        }
    }
}
