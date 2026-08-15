# RoleRewards

RoleRewards is a small Paper plugin for granting monthly rewards to members of configured LuckPerms groups. LuckPerms supplies eligibility; RoleRewards stores an immutable recipient snapshot and grant history in SQLite, then runs one or more configured console commands for each recipient.

The plugin is intentionally designed for one authoritative Paper server and avoids reward-plugin-specific integrations.

## Requirements

- Paper 26.2+
- Java 25
- LuckPerms 5.x
- Linux x86_64 with glibc for the production JAR

The shaded production JAR bundles Xerial SQLite JDBC but retains only its Linux x86_64 native library. Development/tests can still run on Windows with the universal dependency.

No MariaDB, PlaceholderAPI, CMI, PlayerPoints, login listener, or deferred notification system is required.

## Behavior

- Reward periods are calendar months (`YYYY-MM`) in the configured timezone.
- Eligibility is a **positive, unexpired direct LuckPerms group inheritance**. The configured group must exist.
- The first run for a reward/month stores the recipient snapshot, even when zero players are eligible.
- Once a period is processed, newly eligible players begin with the next period.
- SQLite prevents duplicate period snapshots and duplicate per-player grants.
- Multiple commands are tracked per grant; normal retries resume from the first command not recorded as accepted.
- Interrupted `PENDING` grants recover as `FAILED` for manual review rather than being blindly reissued.
- Automatic scheduling catches up a due **current** month after restart, but does not reconstruct fully missed historical months from present-day LuckPerms membership.

Because configured console commands are external side effects, a hard crash can occur after a command executes but before RoleRewards records that fact. Review interrupted grants before retrying them.

## Installation

1. Put the JAR in `plugins/` and ensure LuckPerms is installed.
2. Start the server once to create `plugins/RoleRewards/config.yml`, `messages.yml`, and `rolerewards.db`.
3. Review `config.yml` and confirm the configured LuckPerms group exists.
4. Run `/rolerewards preview companion` to verify eligibility without consuming the month.
5. Enable automatic scheduling only when ready.

The bundled Companion schedule is disabled by default.

> `/rolerewards run <reward>` is **not** a dry run. It locks the current month's recipient snapshot even when no players are eligible.

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

### Global settings

| Setting | Purpose |
|---|---|
| `config-version` | Plugin-managed schema marker. Do not edit manually. |
| `timezone` | IANA timezone used for periods and schedules. |
| `scheduler-check-minutes` | Due-check interval from 1–60 minutes. |

### Reward settings

| Setting | Purpose |
|---|---|
| `group` | Required LuckPerms group. |
| `membership.direct-only` | Must be `true` in v1. |
| `schedule.enabled` | Enables automatic monthly execution. |
| `schedule.day-of-month` | Day 1–31; short months clamp to their last day. |
| `schedule.time` | Local 24-hour time in `HH:mm` format. |
| `commands` | Required list of console commands, run in order. |

Reward IDs are case-normalized and may contain `a-z`, `0-9`, `_`, and `-`.

Known settings are type-checked. Mistakes such as `enabled: "false"` or `scheduler-check-minutes: "5"` are rejected rather than silently coerced to a default.

### Command placeholders

- `{player}` — last known LuckPerms username
- `{uuid}` — player UUID
- `{reward}` — reward ID
- `{period}` — period such as `2026-08`

A leading `/` in a configured command is accepted and stripped before console dispatch.

## Commands

`/rr` is an alias for `/rolerewards`.

| Command | Purpose |
|---|---|
| `/rolerewards status` | Show schedule and last-run state. |
| `/rolerewards preview <reward>` | Preview current eligibility and snapshot state without creating a run. |
| `/rolerewards run <reward>` | Create and execute the current-period snapshot manually. |
| `/rolerewards retry <reward> [YYYY-MM]` | Retry failed grants; defaults to the current period. |
| `/rolerewards history` | Show your own reward history. |
| `/rolerewards history <player>` | Show another player's stored history. |
| `/rolerewards reload` | Validate/apply current-schema config and messages, then restart scheduling. |

Tab completion is permission-aware. Reward IDs come from config, retry completion includes failed periods, and history names come from SQLite plus currently online players.

## Permissions

All permissions default to OP. Console and RCON are authorized. Permissions are enforced both in the Brigadier command tree and inside handlers.

| Permission | Allows |
|---|---|
| `rolerewards.admin` | All RoleRewards commands. |
| `rolerewards.status` | Status. |
| `rolerewards.preview` | Eligibility previews. |
| `rolerewards.run` | Manual reward runs. |
| `rolerewards.retry` | Failed-grant retries. |
| `rolerewards.reload` | Configuration reload. |
| `rolerewards.history` | Own history. |
| `rolerewards.history.others` | Another player's history. |

## Messages

`messages.yml` uses Paper's Adventure MiniMessage implementation and its standard tags. Dynamic values are inserted with MiniMessage resolvers rather than reparsed as markup.

Customized message strings are preserved during upgrades. Startup non-destructively adds newly bundled message keys, while bundled messages also remain available as an in-memory fallback for missing known keys.

## Files, upgrades, and backups

```text
plugins/RoleRewards/
├── config.yml
├── messages.yml
├── rolerewards.db
└── *.bak
```

`config.yml` and `messages.yml` use independent schema versions. Migrations run **only at startup**; `/rolerewards reload` never migrates or rewrites them.

Existing administrator values are authoritative. Config migrations are explicit and do not blindly merge/replace the administrator-owned `rewards:` tree. Existing message strings remain unchanged while missing bundled keys may be added.

When startup changes an existing YAML file, RoleRewards creates a timestamped `.bak`, writes through a temporary file, and atomically replaces the installed file where supported. Newer unsupported YAML or SQLite schemas are rejected instead of downgraded.

SQLite uses WAL mode. The simplest consistent database backup is taken after a clean server stop. For a live filesystem copy, treat `rolerewards.db`, `rolerewards.db-wal`, and `rolerewards.db-shm` as one set.

## Building

The project uses Gradle 9.7.0 Kotlin DSL, Java 25 toolchains, and the committed wrapper.

```bash
./gradlew clean build
```

Windows PowerShell:

```powershell
.\gradlew.bat clean build
```

The deployable JAR is written to `build/libs/RoleRewards-<version>.jar`.

For architecture, crash/retry semantics, schema lifecycle details, and release automation, see [docs/TECHNICAL.md](docs/TECHNICAL.md).
