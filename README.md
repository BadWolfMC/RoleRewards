# RoleRewards

RoleRewards is a small Paper plugin for granting scheduled rewards to members of a LuckPerms group. It was designed for a single authoritative Paper server and intentionally keeps reward delivery generic: each reward is just a LuckPerms group, a calendar schedule, and one or more console commands.

## Requirements

- Paper 26.2+
- Java 25
- LuckPerms 5.x

RoleRewards has no hard dependency on CMI, PlayerPoints, PlaceholderAPI, Continuum, or an external database. SQLite is bundled into the plugin JAR and stored at `plugins/RoleRewards/rolerewards.db`.

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

The deployable shaded JAR is written to `build/libs/RoleRewards-<version>.jar`. The normal development version is `1.0.0-SNAPSHOT`; automated release builds override it with the requested release version.

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

RoleRewards v1 intentionally supports **direct LuckPerms group membership only**, matching `lp group <group> listmembers`. A configured `direct-only: false` is rejected rather than silently changing eligibility semantics.

Reward IDs are case-normalized and must be unique after normalization. Empty/command-only-slash entries are rejected during configuration loading.

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

User-facing plugin messages are stored in `messages.yml` and parsed with Paper's bundled Adventure MiniMessage implementation. The full standard MiniMessage tag set is available, including colors, hex colors, gradients, decorations, hover/click events, fonts, and the other standard tags supported by the server's Adventure version.

Dynamic values are inserted with MiniMessage resolvers rather than reparsed as markup. Malformed `messages.yml` is treated as a reload/startup error instead of being silently accepted.

RoleRewards does not register a player login listener. Offline-capable reward commands run at reward time; players can use history (where permitted) to verify grants.

## Data files and backups

```text
plugins/RoleRewards/
├── config.yml
├── messages.yml
└── rolerewards.db
```

SQLite runs in WAL mode. RoleRewards performs a WAL checkpoint on a clean plugin shutdown. The safest backup is therefore taken after a clean server stop. If plugin data is copied while the server is running, treat `rolerewards.db`, `rolerewards.db-wal`, and `rolerewards.db-shm` as one SQLite database set rather than copying only the main database file during an active write.

## GitHub automation

The repository includes three GitHub Actions workflows plus a monthly Dependabot configuration for Gradle and GitHub Actions dependencies:

- **Build** — builds and tests with Java 25 on every push and pull request, can also be run manually, validates the Gradle Wrapper through Gradle's setup action, and uploads the deployable JAR. Failure reports are uploaded when available.
- **CodeQL** — performs Java CodeQL analysis on pushes, pull requests, a weekly schedule, and manual runs using the same explicit Gradle build path as CI.
- **Build Release** — manually builds a versioned release from the repository's default branch, runs the full test/build first, then creates a `v<version>` GitHub release with the plugin JAR, sources JAR, and SHA-256 checksum. It can optionally create a draft or prerelease.

For a normal release, open **Actions → Build Release → Run workflow**, enter a version such as `1.0.0`, and run it from the default branch. The release workflow does not publish automatically from ordinary pushes.
