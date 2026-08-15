# RoleRewards technical notes

This document contains implementation and maintenance details that are useful to developers and operators troubleshooting unusual states. The main [README](../README.md) is the normal usage reference.

## Architecture

RoleRewards is deliberately small:

- Paper provides lifecycle command registration, scheduling, console command dispatch, and Adventure/MiniMessage.
- LuckPerms is the authoritative eligibility source.
- SQLite stores period snapshots, per-player grant state, and command progress.
- Reward integrations remain opaque console commands.

There is no login listener, deferred notification queue, external database, PlaceholderAPI integration, or direct dependency on the plugin that implements the configured reward command.

## Threading model

Paper/Bukkit operations that require the server thread, including arbitrary console command dispatch, are returned to the primary thread.

SQLite work runs through one dedicated single-thread executor. This serializes database mutations without blocking the server thread and keeps multi-step grant progress deterministic inside RoleRewards.

LuckPerms lookups use the futures returned by the LuckPerms API. A run takes its recipient snapshot only after eligibility has been resolved.

The scheduler uses a generation token. Reload/stop invalidates an asynchronous due-check made against an older configuration so it cannot later start a reward using a stale schedule decision. A reward execution that has already begun is not cancelled; cancelling arbitrary external console side effects would be less safe than allowing that captured run to finish.

## Period and snapshot lifecycle

A reward period is a `YearMonth` serialized as `YYYY-MM`.

The first execution attempt for a reward/period performs these durable steps:

1. verify the configured LuckPerms group exists;
2. resolve direct positive, unexpired membership;
3. insert one `reward_runs` row with status `RUNNING`;
4. insert one `PENDING` `reward_grants` row per eligible UUID in the same SQLite transaction;
5. execute configured commands for each grant;
6. persist `next_command_index` after each accepted command;
7. mark each grant `GRANTED` or `FAILED`;
8. finalize aggregate run status/counts.

The `(reward_id, period)` run primary key prevents a second recipient snapshot. The `(reward_id, period, player_uuid)` grant primary key prevents duplicate rows for one recipient.

A zero-recipient run is intentional and still consumes the period. This is what prevents eligibility from drifting later in the month.

## Command progress and retries

Each grant stores `next_command_index`.

For a reward with commands `A`, `B`, and `C`, a stored index of `1` means command `A` was accepted and a retry begins with `B`. Earlier accepted commands are not normally replayed.

A retry uses the **current** configured command list together with the stored numeric index. Operators should therefore review failed grants before reordering or materially changing a reward's command list. If the stored index is beyond the end of the current command list, RoleRewards refuses to continue that grant and records a failure requiring manual review.

A successful Bukkit dispatch means the server accepted an arbitrary command for execution. RoleRewards cannot generically prove that another plugin completed the intended economic or external effect.

## Crash recovery and exactly-once limits

Exactly-once delivery across a hard process crash is not possible for arbitrary external console commands without transactional cooperation from the target plugin.

The important crash window is:

```text
external command accepted
        ↓
JVM/server crashes
        ↓
next_command_index has not yet been committed
```

On startup, any surviving `PENDING` grant becomes `FAILED` with an interrupted-work reason. These grants require operator review before `/rolerewards retry` is used.

A `RUNNING` period is reconciled during startup:

- if it contained an interrupted `PENDING` grant, the run becomes `INTERRUPTED` and current grant counts are stored;
- if all grants were already terminal and none failed, it is safely finalized as `COMPLETE`;
- if all grants were terminal but one or more had already failed, it is safely finalized as `COMPLETE_WITH_FAILURES`.

This avoids leaving a zero-recipient run, or a run whose grants all finished immediately before a crash, permanently stuck as `INTERRUPTED` with nothing meaningful to retry.

## Scheduling semantics

The scheduler checks only the current calendar month in the configured timezone.

A reward is due when:

- its schedule is enabled;
- the configured local date/time for the current month has passed; and
- no run snapshot exists for that reward/month.

Days beyond the end of a short month clamp to that month's final day.

The scheduler is deliberately not a historical eligibility engine. If the authoritative server remains offline across an entire month, current LuckPerms membership is insufficient evidence of who was eligible during that old period, so RoleRewards does not reconstruct it automatically.

## Configuration lifecycle

`config.yml` and `messages.yml` have separate integer schema markers.

Startup supports sequential migrations from older known schemas. Reload does not migrate files; it requires the current schema and only stages/validates the operator's edits before activation.

### `config.yml`

The `rewards:` tree contains administrator-owned definitions, so it is never blindly merged with a new bundled config. Schema changes must be explicit migrations.

Migration defaults are frozen in the migration that introduces them. If a later release changes a bundled default, that does not retroactively change the value applied by an older migration.

Known operational fields are strictly type-checked during parsing. This prevents YAML mistakes such as `enabled: "false"` or `scheduler-check-minutes: "5"` from silently becoming fallback values.

### `messages.yml`

Existing strings always win. After sequential schema migrations, startup copies only bundled message keys that are absent from the installed file.

Bundled strings are also retained separately in memory. Reload therefore does not need to rewrite a current-schema `messages.yml` just because a known key is absent; the bundled value remains available until the next startup repair.

### Safe writes

When migration changes an existing YAML file:

1. the pre-migration installed file is copied to a timestamped `.bak` file;
2. new content is written to a same-directory temporary file;
3. the temporary file is flushed;
4. existing POSIX permissions are copied where available;
5. an atomic replace is attempted, with a normal same-filesystem replace as fallback.

Both startup YAML candidates are prepared and validated before the first file is committed. The two independent files are not treated as one cross-file filesystem transaction; if the first write succeeds and the second filesystem write itself fails, the first safe migration remains valid and the second is retried on the next startup.

## SQLite schema lifecycle

SQLite uses `PRAGMA user_version`; the current schema is version 1.

Initialization:

1. rejects a database whose `user_version` is newer than the plugin supports;
2. enables WAL mode;
3. performs known sequential schema migration;
4. validates required tables, columns, and primary-key structure;
5. performs interrupted-work recovery.

Structural validation is intentional even when `user_version` already matches. A manually altered, partially copied, or otherwise incompatible database should fail clearly at startup rather than defeating duplicate protection or failing later during a reward.

### WAL and backups

Every opened connection enables foreign keys and a SQLite busy timeout. A clean plugin shutdown waits briefly for the SQLite executor and then performs a `wal_checkpoint(TRUNCATE)`.

The easiest consistent backup is taken after a clean server stop. A live filesystem copy must include the main DB plus active `-wal` and `-shm` companions as one set.

## Command and permission model

Commands use Paper's Brigadier/lifecycle registration. Subcommands are filtered with `.requires(...)`, while handler-level permission checks remain as a second boundary.

Console and RCON are authorized directly. Player permissions default to OP in `plugin.yml`; `rolerewards.admin` is the convenience parent.

Reward IDs are completed from the in-memory configuration. Retry periods are read from failed SQLite grants. History name completion combines known SQLite names with currently online players without performing a LuckPerms-wide search on every Tab press.

## Build and artifact targeting

The project uses:

- Gradle 9.7.0 Kotlin DSL;
- Java 25 toolchains;
- Paper 26.2 API as `compileOnly`;
- LuckPerms API 5.5 as `compileOnly`;
- shaded Xerial SQLite JDBC.

The production Shadow JAR intentionally keeps only:

```text
org/sqlite/native/Linux/x86_64/libsqlitejdbc.so
```

`verifyTargetedJar` fails the build if additional SQLite native libraries are accidentally included or the required Linux library is absent.

`.gitattributes` pins the Unix Gradle wrapper to LF and Windows batch launchers to CRLF so Windows development cannot accidentally make `gradlew` unusable on Linux.

## CI and releases

The repository currently contains:

- normal build/test CI on pushes, pull requests, and manual runs;
- CodeQL analysis;
- a manually triggered release workflow;
- monthly Gradle and GitHub Actions Dependabot checks.

The release workflow accepts a semantic-style version, runs the full build/tests, creates the versioned shaded JAR and sources JAR, generates a SHA-256 checksum, and creates the GitHub release from the repository's default branch.
