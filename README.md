# RoleRewards

RoleRewards is a small Paper plugin for granting scheduled rewards to members of a LuckPerms group. It was designed for a single authoritative Paper server and intentionally keeps reward delivery generic: each reward is just a LuckPerms group, a calendar schedule, and one or more console commands.

## Requirements

- Paper 26.2+
- Java 25
- LuckPerms 5.x

RoleRewards does not support an external database. SQLite is bundled into the plugin JAR and stored at `plugins/RoleRewards/rolerewards.db`.

> **Platform target:** the distributed/deployable RoleRewards JAR is intentionally built for **Linux x86_64 with glibc** (the BadWolfMC Paper host). Xerial SQLite JDBC is kept universal on the development/test classpath, but the shaded plugin JAR retains only its Linux x86_64 native library. This keeps the production artifact small while allowing the project and tests to build normally on Windows. A build-time verification task fails if that packaging guarantee changes.

## Build

The project uses Gradle Kotlin DSL, the committed Gradle Wrapper, and a Java 25 toolchain. A separate system Gradle installation is not required for normal builds.

Linux/macOS:

```bash
./gradlew clean build
```

Windows PowerShell:

```powershell
.\gradlew.bat clean build
```

The deployable shaded JAR is written to `build/libs/RoleRewards-<version>.jar`. The normal development version is `1.0.0-SNAPSHOT`; automated release builds override it with the requested release version. The deployable JAR is platform-targeted to Linux x86_64/glibc even when the build itself runs on Windows.

## First-run commissioning

The default Companion reward is present but its automatic schedule is disabled. This is deliberate.

1. Install RoleRewards on the single Paper server that should be authoritative for rewards.
2. Confirm LuckPerms is installed and the configured group exists.
3. Start the server and inspect `plugins/RoleRewards/config.yml`.
4. Use `/rolerewards preview companion` to verify the membership list.
5. If desired, use `/rolerewards run companion` to test a real current-period reward. **A manual run creates that month's immutable recipient snapshot even if zero members are eligible**, so do not use it merely as a dry run.
6. Set `schedule.enabled: true` only when automatic execution is wanted, then `/rolerewards reload`.

If automatic scheduling is enabled after the configured date/time has already passed for the current month, that month is considered due and will run on the next scheduler check unless a run snapshot already exists.

## Configuration

```yaml
config-version: 1

timezone: "America/New_York"
scheduler-check-minutes: 5

rewards:
  companion:
    group: "companion"
    membership:
      direct-only: true

    schedule:
      enabled: false
      day-of-month: 1
      time: "22:00"

    commands:
      - "points give {player} 50"
```

RoleRewards v1 intentionally supports **direct LuckPerms group membership only**, matching the broad behavior of `lp group <group> listmembers`. Eligibility requires a positive, unexpired direct inheritance node; negated or expired inheritance nodes are ignored. Before previewing or creating a run snapshot, RoleRewards also verifies that the configured LuckPerms group exists. A misspelled or deleted group therefore fails safely instead of recording an empty month. A configured `direct-only: false` is rejected rather than silently changing eligibility semantics.

Reward IDs are case-normalized and must be unique after normalization. Empty/command-only-slash entries are rejected during configuration loading. `/rolerewards reload` stages and validates both `config.yml` and `messages.yml` before either live configuration is replaced, then restarts the scheduler only after both files have loaded successfully.

### Configuration lifecycle and upgrades

`config.yml` and `messages.yml` carry independent plugin-managed schema markers:

```yaml
config-version: 1
```

```yaml
messages-version: 1
```

Schema migration is a **startup-only** operation. `/rolerewards reload` never migrates or rewrites either file; it only accepts the schema version supported by the running plugin, validates the hand-edited files, and applies them if both candidates are valid. If a legacy/unversioned file is intentionally being upgraded, restart RoleRewards rather than using reload.

On startup RoleRewards:

1. loads the bundled and installed YAML;
2. treats an older unversioned/pre-release file as schema version `0`;
3. refuses any installed schema newer than the running plugin supports;
4. applies explicit version-to-version migrations in sequence;
5. validates the migrated in-memory result before scheduling any disk update;
6. backs up an existing file before replacing it; and
7. writes through a temporary file and uses an atomic move when the filesystem supports one.

Existing administrator values are authoritative. A migration may add a property that did not exist in an older schema, but it does not replace an existing value merely because a newer bundled default differs. In particular, RoleRewards never generically merges or replaces the `rewards:` tree: administrator-defined reward IDs, groups, schedules, commands, and unknown custom data remain intact unless a specific future schema migration explicitly transforms a setting. Defaults used by a schema migration are frozen in that migration step rather than read from whatever defaults a later plugin release happens to bundle, so an upstream default change cannot retroactively alter how an old schema is upgraded.

For `messages.yml`, startup also performs a non-destructive missing-key merge from the bundled messages for the current plugin version. Existing/customized MiniMessage strings are preserved exactly; only absent bundled keys are added. The bundled message set is also kept as an in-memory fallback, so a current-schema file with a missing message key can still render the plugin default after `/rolerewards reload` without rewriting the file. On the next startup, that missing bundled key is written to `messages.yml`.

When an existing file actually changes, backups are named along these lines:

```text
config.yml.v0-to-v1.20260814-221500-000.bak
messages.yml.v1-to-v1.20260814-221500-000.bak
```

The `v1-to-v1` form can occur when the schema is already current but a missing bundled message key is repaired. A current, complete file is not rewritten on subsequent restarts. First-run creation does not produce a meaningless backup.

Malformed YAML, an invalid migrated result, a non-integer schema marker, or a newer unsupported schema causes startup/reload to fail clearly rather than silently replacing administrator data. Migration validation happens before the existing YAML is rewritten; if a safe-write operation itself fails after a backup is created, the backup is left in place for recovery. Downgrading RoleRewards across a configuration schema change is therefore intentionally rejected rather than guessed at.

The configured day is clamped to the last valid day of shorter months. For example, day 31 runs on February's final day.

### Command placeholders

Each console command supports:

- `{player}` — last known LuckPerms username
- `{uuid}` — player UUID
- `{reward}` — configured reward ID
- `{period}` — calendar period such as `2026-08`

A leading `/` in a configured console command is accepted and stripped before dispatch.

Multiple commands are supported. RoleRewards records the next command index after each accepted command, so a normal retry resumes at the failed command instead of replaying earlier successful commands. If a period has failed grants, review them before changing that reward's command list; retries use the current configuration together with the stored command index. RoleRewards refuses a retry if the stored command index is impossible for the current command list.

## Scheduling and duplicate protection

RoleRewards works in calendar periods (`YYYY-MM`) rather than fixed 30-day intervals.

For each reward and month, SQLite stores:

- one period-level run snapshot;
- one grant row per eligible UUID;
- grant status (`PENDING`, `GRANTED`, or `FAILED`);
- the next command index for safe normal retries;
- timestamps and failure details.

The `(reward_id, period)` run key and `(reward_id, period, player_uuid)` grant key are unique. A server restart at the scheduled minute therefore does not skip the month, while repeated scheduler checks cannot create a second run snapshot. Automatic checks carry the exact calendar period they evaluated through to execution, avoiding a month-boundary mismatch if an asynchronous lookup crosses midnight.

If the JVM/server is interrupted while a grant is `PENDING`, RoleRewards converts that grant to `FAILED` on next startup for manual review. Because console commands are external side effects, no plugin can guarantee exactly-once behavior across a hard crash between command execution and the SQLite update. Verify an interrupted grant before manually retrying it.

A successful Bukkit command dispatch means the server accepted the command for execution; RoleRewards cannot generically verify that an arbitrary third-party command produced its intended external effect.

RoleRewards deliberately does not reconstruct a fully missed historical month after the calendar has already advanced, because current LuckPerms membership is not proof of historical eligibility. If the authoritative server is offline across an entire reward period, review that period manually rather than expecting a later month to backfill it.

## Commands

All command permissions default to **OP**. Console and RCON are authorized. Permissions are also checked inside command handlers, not only in the visible Brigadier command tree.

| Command | Permission | Purpose |
|---|---|---|
| `/rolerewards status` | `rolerewards.status` | Show schedule and last-run status |
| `/rolerewards preview <reward>` | `rolerewards.preview` | Show current eligible members and current-period state |
| `/rolerewards run <reward>` | `rolerewards.run` | Manually create/run the current period snapshot |
| `/rolerewards retry <reward> [period]` | `rolerewards.retry` | Retry failed grants; defaults to the current period, or accepts `YYYY-MM` |
| `/rolerewards history` | `rolerewards.history` | Show your own reward history |
| `/rolerewards history <player>` | `rolerewards.history.others` | Show another player's reward history |
| `/rolerewards reload` | `rolerewards.reload` | Reload config/messages and scheduler interval |

`rolerewards.admin` grants all RoleRewards command permissions.

The `/rr` alias is also registered.

Command/subcommand visibility is permission-aware. Reward IDs tab-complete dynamically from `config.yml`; retry periods suggest the current month plus recent months that still contain failed grants. History player names are suggested from SQLite history plus currently online players, so offline recipients remain discoverable without querying LuckPerms every time Tab is pressed.

History lookup by explicit player name is based on names stored in RoleRewards grant history. A player who changes their Minecraft name may therefore have older entries under the previous name; self-history remains UUID-based.

## Messages

User-facing plugin messages are stored in `messages.yml`, whose current schema begins with `messages-version: 1`, and are parsed with Paper's bundled Adventure MiniMessage implementation. The full standard MiniMessage tag set is available, including colors, hex colors, gradients, decorations, hover/click events, fonts, and the other standard tags supported by the server's Adventure version.

Dynamic values are inserted with MiniMessage resolvers rather than reparsed as markup. Known bundled message keys must remain strings. Missing known keys use the bundled in-memory fallback described above; malformed YAML or an invalid known-key type is treated as a reload/startup error instead of being silently accepted.

RoleRewards does not register a player login listener. Offline-capable reward commands run at reward time; players can use history (where permitted) to verify grants.

## Data files and backups

```text
plugins/RoleRewards/
├── config.yml
├── config.yml.v*-to-v*.*.bak      # only when a startup upgrade changes config.yml
├── messages.yml
├── messages.yml.v*-to-v*.*.bak    # only when a startup upgrade/merge changes messages.yml
└── rolerewards.db
```

Configuration backups are exact copies of the pre-upgrade installed files. They are separate from SQLite backup requirements below.

SQLite runs in WAL mode. RoleRewards records and validates its database schema version with SQLite `PRAGMA user_version`; startup refuses a database created by a newer unsupported schema rather than attempting to use it blindly. Pre-release/unversioned databases are migrated idempotently to schema version 1 on first startup with this build.

RoleRewards performs a WAL checkpoint on a clean plugin shutdown. The safest backup is therefore taken after a clean server stop. If plugin data is copied while the server is running, treat `rolerewards.db`, `rolerewards.db-wal`, and `rolerewards.db-shm` as one SQLite database set rather than copying only the main database file during an active write.

## GitHub automation

The repository includes three GitHub Actions workflows plus a monthly Dependabot configuration for Gradle and GitHub Actions dependencies:

- **Build** — builds and tests with Java 25 on every push and pull request, can also be run manually, validates the Gradle Wrapper through Gradle's setup action, and uploads the deployable JAR. Failure reports are uploaded when available.
- **CodeQL** — performs Java CodeQL analysis on pushes, pull requests, a weekly schedule, and manual runs using the same explicit Gradle build path as CI.
- **Build Release** — manually builds a versioned release from the repository's default branch, runs the full test/build first, then creates a `v<version>` GitHub release with the plugin JAR, sources JAR, and SHA-256 checksum. It can optionally create a draft or prerelease.

For a normal release, open **Actions → Build Release → Run workflow**, enter a version such as `1.0.0`, and run it from the default branch. The release workflow does not publish automatically from ordinary pushes.
